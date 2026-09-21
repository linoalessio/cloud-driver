"""PostgreSQL: a local server carrying the application's role and database, or the credentials
file for an external one.

Mirrors step 5 (and the matching half of step 8) of ``shell/provision-root-server.sh``:

* **Keep unless rotate.** A credential is never rotated unless it can be written down in the same
  apply. An existing ``postgres-database.json`` on the server is the record: its password is
  reused (``secrets.pg_password_kept``) unless the plan's ``rotate`` flag is set or the operator
  typed a password into the plan. Only then does the role get ``ALTER ROLE … WITH PASSWORD`` and
  the file is rewritten together with it. With no file on the server (a fresh box) a 48-character
  hex password is generated - hex, like the script's ``openssl rand -hex 24``.
* **Secrets never on a command line.** The password reaches ``psql`` over stdin only: as a
  dollar-quoted literal inside the script's own create-or-alter ``DO`` block, or as ``PGPASSWORD``
  exported by ``run_with_env`` for the TCP login probe.
* ``runuser -u postgres -- psql`` instead of the script's ``sudo -u``: ``sudo`` is not among the
  base packages and root does not need it.
* Idempotence fixes over the script: a missing role is (re)created with the kept password
  instead of being skipped, ``ALTER DATABASE … OWNER TO`` runs only when the owner differs, and
  the credentials file is rewritten only when its content would change.
"""

from __future__ import annotations

import re
import shlex
from dataclasses import dataclass

from cloud_driver_installer.config_files import parse_json, render_postgres_credentials, to_json
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError
from cloud_driver_installer.secrets import generate_hex
from cloud_driver_installer.ssh import ExecResult

#: File name (under the plan's config dir) that ``database-driver`` reads at boot.
CREDENTIALS_FILE = "postgres-database.json"
#: Packages for a local server (the script's step 1 list, PostgreSQL part).
SERVER_PACKAGES: tuple[str, ...] = ("postgresql", "postgresql-contrib")
#: Package that provides ``psql`` when only an external server is used.
CLIENT_PACKAGE = "postgresql-client"
#: The systemd unit (Debian's cluster meta-unit).
SERVICE = "postgresql"
#: Every administrative statement runs as the ``postgres`` OS user over the Unix socket.
PSQL_ADMIN = "runuser -u postgres -- psql -v ON_ERROR_STOP=1"

_IDENT_RE = re.compile(r"^[a-z_][a-z0-9_]{0,62}$")
_SHELL_UNSAFE_RE = re.compile(r'["$`\\]')


@dataclass(frozen=True)
class ResolvedPassword:
    """The password this run uses for the role, and where it came from."""

    value: str
    #: True when it was read back from the server's ``postgres-database.json``.
    kept: bool
    #: ``plan`` (typed by the operator), ``file`` (kept) or ``generated`` (fresh box or rotation).
    source: str
    #: Human-readable provenance for the sidebar detail.
    note: str


def render_role_sql(username: str, password: str) -> str:
    """The create-or-alter ``DO`` block of the shell script, with ``password`` dollar-quoted.

    The literal's tag is chosen so it cannot occur inside the password, and the block's own tag
    likewise, so any password value survives verbatim. The text is meant for ``psql``'s stdin.
    """
    tag = "pw"
    while f"${tag}$" in password:
        tag += "_"
    body = "body"
    while f"${body}$" in password:
        body += "_"
    literal = f"${tag}${password}${tag}$"
    return (
        f"DO ${body}$\n"
        "BEGIN\n"
        f"   IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '{username}') THEN\n"
        f"      CREATE ROLE {username} LOGIN PASSWORD {literal};\n"
        "   ELSE\n"
        f"      ALTER ROLE {username} WITH PASSWORD {literal};\n"
        "   END IF;\n"
        "END\n"
        f"${body}$;\n"
    )


class PostgresStep(Step):
    """Install PostgreSQL with the application's role and database, or point the file at an external server."""

    id = "postgres"
    title = "PostgreSQL"
    depends_on = ("packages",)
    mandatory = True

    # --- Step ------------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """Read-only: package, service, role, database (and owner), credentials file, password provenance."""
        pg = ctx.plan.postgres
        _require_identifiers(pg.username, pg.database)
        if pg.mode == "external":
            return self._check_external(ctx)
        remote = ctx.remote
        installed = remote.dpkg_installed("postgresql")
        active = installed and remote.service_active(SERVICE)
        version = self._server_version(ctx) if active else ""
        ctx.discovered.pg_installed = installed
        if version:
            ctx.discovered.pg_version = version
        resolved = self._resolve_password(ctx)
        role_exists = active and self._role_exists(ctx)
        db_exists = active and self._database_exists(ctx)
        owner = self._database_owner(ctx) if db_exists else ""
        file_ok = self._file_current(ctx, resolved.value)

        todo: list[str] = []
        login_note = ""
        if not installed:
            todo.append("install postgresql + postgresql-contrib")
        elif not active:
            todo.append("enable + start postgresql")
        if not role_exists:
            todo.append(f"create role {pg.username}")
        elif resolved.source == "generated":
            todo.append("rotate the role password" if pg.rotate else "set the role password (none on record)")
        elif self._tcp_login(ctx, resolved.value).ok:
            login_note = " (login ok)"
        else:
            origin = "the plan" if resolved.source == "plan" else CREDENTIALS_FILE
            todo.append(f"set the role password to the one from {origin}")
        if not db_exists:
            todo.append(f"create database {pg.database} (owner {pg.username})")
        elif owner != pg.username:
            todo.append(f"make {pg.username} the owner of {pg.database}")
        if not file_ok:
            todo.append(f"write {CREDENTIALS_FILE}")
        if todo:
            return CheckResult.needs_apply(", ".join(todo) + f" ({resolved.note})")
        return CheckResult.ok(
            f"PostgreSQL {version} installed, role {pg.username} exists{login_note}, "
            f"database {pg.database} exists (owner {owner}), {resolved.note}"
        )

    def apply(self, ctx: Context) -> None:
        """Install/start PostgreSQL, create-or-alter the role, create the database, write the file (all idempotent)."""
        pg = ctx.plan.postgres
        remote = ctx.remote
        _require_identifiers(pg.username, pg.database)
        resolved = self._resolve_password(ctx)
        if pg.mode == "external":
            self._apply_external(ctx, resolved)
            return
        ctx.progress(0.0, "PostgreSQL")
        if not remote.dpkg_installed("postgresql"):
            ctx.info("installing postgresql + postgresql-contrib")
            try:
                remote.apt_install(list(SERVER_PACKAGES))
            except RemoteError as exc:
                raise StepError(f"PostgreSQL: apt-get install failed: {exc}") from exc
            ctx.discovered.pg_installed = True
        ctx.check_cancelled()
        ctx.progress(0.4, "starting the service")
        self._ensure_running(ctx)
        ctx.check_cancelled()
        ctx.progress(0.55, f"role {pg.username}")
        if self._role_exists(ctx):
            if resolved.source == "generated" or not self._tcp_login(ctx, resolved.value).ok:
                self._create_or_alter_role(ctx, resolved.value)
                ctx.info(f"set the password of role {pg.username} ({resolved.note})")
            else:
                ctx.debug(f"role {pg.username} exists and accepts the password on record - unchanged")
        else:
            self._create_or_alter_role(ctx, resolved.value)
            ctx.info(f"created role {pg.username} ({resolved.note})")
        ctx.check_cancelled()
        ctx.progress(0.75, f"database {pg.database}")
        if not self._database_exists(ctx):
            self._admin_exec(ctx, f"CREATE DATABASE {pg.database} OWNER {pg.username};")
            ctx.info(f"created database {pg.database} (owner {pg.username})")
        elif self._database_owner(ctx) != pg.username:
            self._admin_exec(ctx, f"ALTER DATABASE {pg.database} OWNER TO {pg.username};")
            ctx.info(f"made {pg.username} the owner of database {pg.database}")
        ctx.check_cancelled()
        ctx.progress(0.9, CREDENTIALS_FILE)
        self._write_credentials(ctx, resolved.value)
        ctx.progress(1.0, "done")

    def verify(self, ctx: Context) -> VerifyResult:
        """The real probe: a TCP ``psql`` login as the role with the password on record."""
        pg = ctx.plan.postgres
        password = ctx.secrets.pg_password or self._resolve_password(ctx).value
        result = self._tcp_login(ctx, password)
        where = f"{pg.username}@{pg.host}:{pg.port}/{pg.database}"
        if result.ok:
            version = _first_token(result.text)
            if version:
                ctx.discovered.pg_version = version
            return VerifyResult(True, f"psql login as {where} ok · PostgreSQL {version or 'version unknown'}")
        return VerifyResult(False, f"psql login as {where} failed: {_last_line(result)}")

    def describe(self, plan: InstallPlan) -> str:
        """One line for the summary page."""
        pg = plan.postgres
        if pg.rotate:
            password = "rotate the role password"
        elif pg.password:
            password = "set the role password from the plan"
        else:
            password = f"keep the password from an existing {CREDENTIALS_FILE} (generate one otherwise)"
        if pg.mode == "external":
            return (
                f"use the external PostgreSQL at {pg.host}:{pg.port} (database {pg.database}, user {pg.username}), "
                f"install {CLIENT_PACKAGE} if psql is missing, write {CREDENTIALS_FILE}"
            )
        return (
            f"install postgresql if missing, enable it, create role {pg.username} + database {pg.database} "
            f"(owner {pg.username}), {password}, write {CREDENTIALS_FILE}"
        )

    # --- external mode ---------------------------------------------------------------------------

    def _check_external(self, ctx: Context) -> CheckResult:
        """External server: ``psql`` present, TCP login works, file current."""
        pg = ctx.plan.postgres
        resolved = self._resolve_password(ctx)
        has_psql = ctx.remote.command_exists("psql")
        file_ok = self._file_current(ctx, resolved.value)
        where = f"{pg.host}:{pg.port} as {pg.username}"
        todo: list[str] = []
        version = ""
        if not has_psql:
            todo.append(f"install {CLIENT_PACKAGE}")
        else:
            result = self._tcp_login(ctx, resolved.value)
            if result.ok:
                version = _first_token(result.text)
                if version:
                    ctx.discovered.pg_version = version
            else:
                todo.append(
                    f"login to {where} failed ({_last_line(result)}) - apply only writes the file, "
                    "fix the server or the credentials or verify will fail"
                )
        if not file_ok:
            todo.append(f"write {CREDENTIALS_FILE}")
        if todo:
            return CheckResult.needs_apply(", ".join(todo) + f" ({resolved.note})")
        return CheckResult.ok(f"external PostgreSQL {version} at {where}: login ok, {resolved.note}")

    def _apply_external(self, ctx: Context, resolved: ResolvedPassword) -> None:
        """External server: only the client package (when missing) and the credentials file."""
        remote = ctx.remote
        ctx.progress(0.0, "PostgreSQL client")
        if not remote.command_exists("psql"):
            ctx.info(f"installing {CLIENT_PACKAGE} (psql is needed for the login probe)")
            try:
                remote.apt_install([CLIENT_PACKAGE])
            except RemoteError as exc:
                raise StepError(f"PostgreSQL: apt-get install {CLIENT_PACKAGE} failed: {exc}") from exc
        ctx.check_cancelled()
        ctx.progress(0.7, CREDENTIALS_FILE)
        self._write_credentials(ctx, resolved.value)
        ctx.progress(1.0, "done")

    # --- password --------------------------------------------------------------------------------

    def _resolve_password(self, ctx: Context) -> ResolvedPassword:
        """Deterministic password resolution shared by check, apply and verify.

        Order: the plan's password; else the password inside the server's existing file unless
        ``rotate``; else the value already generated earlier in this run; else a fresh hex value.
        The result is stored in ``ctx.secrets`` and registered with the redactor before anything
        is logged.
        """
        pg = ctx.plan.postgres
        secrets = ctx.secrets
        existing = parse_json(ctx.remote.read_text(self._credentials_path(ctx.plan)))
        ctx.discovered.existing_postgres = existing
        on_file = existing.get("password")
        on_file = on_file if isinstance(on_file, str) else ""
        if pg.password:
            resolved = ResolvedPassword(pg.password, False, "plan", "password from the plan")
        elif on_file and not pg.rotate:
            resolved = ResolvedPassword(on_file, True, "file", f"password kept from {CREDENTIALS_FILE}")
        elif pg.mode == "external":
            raise StepError("PostgreSQL: a password is required for an external server")
        else:
            note = "new password generated (rotation requested)" if pg.rotate and on_file else "new password generated (no credentials file on the server)"
            reuse = secrets.pg_password if secrets.pg_password and not secrets.pg_password_kept else ""
            resolved = ResolvedPassword(reuse or generate_hex(24), False, "generated", note)
        if not resolved.value or "\n" in resolved.value:
            raise StepError("PostgreSQL: the password must be a single non-empty line")
        secrets.pg_password = resolved.value
        secrets.pg_password_kept = resolved.kept
        ctx.remember_secret(resolved.value)
        return resolved

    # --- credentials file ------------------------------------------------------------------------

    @staticmethod
    def _credentials_path(plan: InstallPlan) -> str:
        """``<config_dir>/postgres-database.json``."""
        return f"{plan.config_dir}/{CREDENTIALS_FILE}"

    def _file_current(self, ctx: Context, password: str) -> bool:
        """Whether the server's file already equals what this plan renders (compared as JSON)."""
        existing = parse_json(ctx.remote.read_text(self._credentials_path(ctx.plan)))
        return existing == render_postgres_credentials(ctx.plan, password)

    def _write_credentials(self, ctx: Context, password: str) -> None:
        """Write the file (mode 0600, backup of an existing one) unless its content is already identical."""
        path = self._credentials_path(ctx.plan)
        document = render_postgres_credentials(ctx.plan, password)
        if parse_json(ctx.remote.read_text(path)) == document:
            ctx.debug(f"{path} is up to date")
            return
        try:
            ctx.remote.put_text(path, to_json(document), mode=0o600)
        except RemoteError as exc:
            raise StepError(f"PostgreSQL: could not write {path}: {exc}") from exc
        ctx.discovered.existing_postgres = document
        ctx.info(f"wrote {path}")

    # --- service ---------------------------------------------------------------------------------

    def _ensure_running(self, ctx: Context) -> None:
        """``systemctl enable --now postgresql`` when not active; ``enable`` alone when only not enabled."""
        remote = ctx.remote
        try:
            if not remote.service_active(SERVICE):
                remote.systemctl("enable", "--now", SERVICE)
                ctx.info("enabled and started postgresql")
            elif not remote.run_ok(f"systemctl is-enabled --quiet {SERVICE}"):
                remote.systemctl("enable", SERVICE)
                ctx.info("enabled postgresql at boot")
        except RemoteError as exc:
            raise StepError(f"PostgreSQL: could not start the service: {exc}") from exc

    # --- psql helpers ----------------------------------------------------------------------------

    def _admin_query(self, ctx: Context, sql: str) -> ExecResult:
        """``psql -tAc <sql>`` as the postgres OS user (read-only, output not logged)."""
        return ctx.remote.run(f"{PSQL_ADMIN} -tAc {_quote_sql(sql)}", quiet=True)

    def _admin_exec(self, ctx: Context, sql: str) -> None:
        """``psql -c <sql>`` as the postgres OS user; failure becomes a readable StepError."""
        try:
            ctx.remote.run(f"{PSQL_ADMIN} -c {_quote_sql(sql)}", check=True)
        except RemoteError as exc:
            raise StepError(f"PostgreSQL: {sql.split()[0]} {sql.split()[1]} failed: {exc}") from exc

    def _create_or_alter_role(self, ctx: Context, password: str) -> None:
        """Feed the create-or-alter block over stdin (the password never touches a command line)."""
        try:
            ctx.remote.run(PSQL_ADMIN, input=render_role_sql(ctx.plan.postgres.username, password), check=True)
        except RemoteError as exc:
            raise StepError(f"PostgreSQL: could not create/alter role {ctx.plan.postgres.username}: {exc}") from exc

    def _role_exists(self, ctx: Context) -> bool:
        """``SELECT 1 FROM pg_roles WHERE rolname='<user>'``."""
        result = self._admin_query(ctx, f"SELECT 1 FROM pg_roles WHERE rolname='{ctx.plan.postgres.username}'")
        return result.ok and result.text == "1"

    def _database_exists(self, ctx: Context) -> bool:
        """``SELECT 1 FROM pg_database WHERE datname='<db>'``."""
        result = self._admin_query(ctx, f"SELECT 1 FROM pg_database WHERE datname='{ctx.plan.postgres.database}'")
        return result.ok and result.text == "1"

    def _database_owner(self, ctx: Context) -> str:
        """The owning role of the database (blank when unknown)."""
        result = self._admin_query(ctx, f"SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='{ctx.plan.postgres.database}'")
        return result.text if result.ok else ""

    def _server_version(self, ctx: Context) -> str:
        """``SHOW server_version`` -> ``15.8`` (blank when the server does not answer)."""
        result = self._admin_query(ctx, "SHOW server_version")
        return _first_token(result.text) if result.ok else ""

    def _tcp_login(self, ctx: Context, password: str) -> ExecResult:
        """``psql -h <host>`` as the role, ``PGPASSWORD`` supplied through stdin by ``run_with_env``."""
        pg = ctx.plan.postgres
        command = (
            f"psql -w -h {shlex.quote(pg.host)} -p {int(pg.port)} -U {shlex.quote(pg.username)} "
            f"-d {shlex.quote(pg.database)} -tAc 'SHOW server_version'"
        )
        try:
            return ctx.remote.run_with_env(command, {"PGPASSWORD": password}, timeout=60)
        except RemoteError as exc:
            raise StepError(f"PostgreSQL: could not run psql on the server: {exc}") from exc


# --- module helpers ------------------------------------------------------------------------------


def _require_identifiers(*names: str) -> None:
    """Refuse anything that is not a plain lowercase identifier - they are interpolated into SQL."""
    for name in names:
        if not _IDENT_RE.match(name or ""):
            raise StepError(f"PostgreSQL: '{name}' is not a simple lowercase identifier (letters, digits, _)")


def _quote_sql(sql: str) -> str:
    """Double-quote plain SQL for ``bash -c`` (readable in the log), falling back to ``shlex.quote``."""
    return shlex.quote(sql) if _SHELL_UNSAFE_RE.search(sql) else f'"{sql}"'


def _first_token(text: str) -> str:
    """First whitespace-separated token of ``text`` (``15.8 (Debian …)`` -> ``15.8``)."""
    parts = text.split()
    return parts[0] if parts else ""


def _last_line(result: ExecResult) -> str:
    """The last non-blank line of stderr (or stdout) - where psql puts the reason."""
    lines = [line for line in (result.err.strip() or result.out.strip()).splitlines() if line.strip()]
    return lines[-1].strip() if lines else f"exit {result.code}, no output"

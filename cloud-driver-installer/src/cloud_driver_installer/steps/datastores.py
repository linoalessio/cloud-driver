"""PostgreSQL and Redis: install, credentials, and the files the backend reads them from.

Both steps follow the same rule as ``shell/provision-root-server.sh``: a password is only ever
rotated together with the file that records it, and a password already on the server is read back
and kept unless the operator ticks *rotate*. The credentials file is written inside the step that
changes the service, never in a later one, so a failure can never leave the two disagreeing.
"""

from __future__ import annotations

import shlex

from cloud_driver_installer.config_files import render_postgres_credentials, render_redis_credentials, to_json
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.credentials import generate_hex

#: Dollar-quoting tag for passwords inside SQL: hex passwords can never contain it.
SQL_TAG = "cdipw"


def resolve_password(ctx: Context, *, kind: str) -> tuple[str, bool]:
    """Return ``(password, kept)`` for ``kind`` (``postgres``/``redis``).

    Order: a value already generated in this run (so a Retry never creates a second one), then the
    operator's own entry, then the value in the server's credentials file (unless rotating), then
    a fresh 48-character hex secret.
    """
    settings = ctx.plan.postgres if kind == "postgres" else ctx.plan.redis
    existing_file = ctx.discovered.existing_postgres if kind == "postgres" else ctx.discovered.existing_redis
    in_memory = ctx.secrets.pg_password if kind == "postgres" else ctx.secrets.redis_password
    kept_flag = ctx.secrets.pg_password_kept if kind == "postgres" else ctx.secrets.redis_password_kept

    if in_memory:
        password, kept = in_memory, kept_flag
    elif settings.password:
        password, kept = settings.password, False
    elif not settings.rotate and isinstance(existing_file.get("password"), str) and existing_file["password"]:
        password, kept = existing_file["password"], True
    else:
        password, kept = generate_hex(24), False

    ctx.remember_secret(password)
    if kind == "postgres":
        ctx.secrets.pg_password, ctx.secrets.pg_password_kept = password, kept
    else:
        ctx.secrets.redis_password, ctx.secrets.redis_password_kept = password, kept
    return password, kept


class PostgresStep(Step):
    """The system of record: role, database (owned by the role) and ``postgres-database.json``."""

    id = "postgres"
    title = "PostgreSQL"
    mandatory = True
    depends_on = ("packages",)
    removable = True

    def check(self, ctx: Context) -> CheckResult:
        plan = ctx.plan.postgres
        password, kept = resolve_password(ctx, kind="postgres")
        note = "password kept from postgres-database.json" if kept else ("password from the plan" if plan.password else "password generated")
        if plan.mode == "external":
            reachable = self._login_works(ctx, password)
            detail = f"external {plan.username}@{plan.host}:{plan.port}/{plan.database} · {note}"
            return CheckResult.ok(detail) if reachable and self._file_current(ctx, password) else CheckResult.needs_apply(detail)

        if not ctx.remote.dpkg_installed("postgresql"):
            return CheckResult.needs_apply("not installed")
        if not ctx.remote.service_active("postgresql"):
            return CheckResult.needs_apply("installed but not running")
        role = self._psql(ctx, f"SELECT 1 FROM pg_roles WHERE rolname='{plan.username}'") == "1"
        database = self._psql(ctx, f"SELECT 1 FROM pg_database WHERE datname='{plan.database}'") == "1"
        version = self._psql(ctx, "SHOW server_version") or "?"
        if role and database and not plan.rotate and self._file_current(ctx, password) and self._login_works(ctx, password):
            return CheckResult.ok(f"PostgreSQL {version} · role {plan.username} · database {plan.database} · {note}")
        missing = [name for name, present in (("role", role), ("database", database)) if not present]
        return CheckResult.needs_apply(f"PostgreSQL {version} · " + (", ".join(f"{m} missing" for m in missing) or ("rotate password" if plan.rotate else "credentials file / login")))

    def apply(self, ctx: Context) -> None:
        plan = ctx.plan.postgres
        password, kept = resolve_password(ctx, kind="postgres")
        if plan.mode == "install":
            if not ctx.remote.dpkg_installed("postgresql"):
                ctx.remote.apt_install(["postgresql", "postgresql-contrib"])
            ctx.remote.systemctl("enable", "--now", "postgresql")
            # The file is written first: if anything below fails, the recorded password is the one
            # the role will have after a retry, never a value that exists only in this process.
            self._write_file(ctx, password)
            if not kept or plan.rotate:
                self._run_sql(
                    ctx,
                    f"DO $$ BEGIN\n"
                    f"  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '{plan.username}') THEN\n"
                    f"    CREATE ROLE {plan.username} LOGIN PASSWORD ${SQL_TAG}${password}${SQL_TAG}$;\n"
                    f"  ELSE\n"
                    f"    ALTER ROLE {plan.username} WITH LOGIN PASSWORD ${SQL_TAG}${password}${SQL_TAG}$;\n"
                    f"  END IF;\nEND $$;",
                )
            else:
                self._run_sql(
                    ctx,
                    f"DO $$ BEGIN\n  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '{plan.username}') THEN\n"
                    f"    CREATE ROLE {plan.username} LOGIN PASSWORD ${SQL_TAG}${password}${SQL_TAG}$;\n  END IF;\nEND $$;",
                )
            if self._psql(ctx, f"SELECT 1 FROM pg_database WHERE datname='{plan.database}'") != "1":
                self._run_sql(ctx, f"CREATE DATABASE {plan.database} OWNER {plan.username};")
            self._run_sql(ctx, f"ALTER DATABASE {plan.database} OWNER TO {plan.username};")
        else:
            if not ctx.remote.command_exists("psql"):
                ctx.remote.apt_install(["postgresql-client"])
            self._write_file(ctx, password)

    def verify(self, ctx: Context) -> VerifyResult:
        plan = ctx.plan.postgres
        password = ctx.secrets.pg_password
        problem = self._login_problem(ctx, password)
        if problem:
            return VerifyResult(False, f"{plan.username} cannot log in to {plan.database} on {plan.host}:{plan.port} - {problem}")
        owner = self._login_query(ctx, password, "SELECT pg_get_userbyid(datdba) = current_user FROM pg_database WHERE datname = current_database()")
        create = self._login_query(ctx, password, "SELECT has_schema_privilege(current_user, 'public', 'CREATE')")
        if owner != "t" and create != "t":
            return VerifyResult(
                False,
                f"role {plan.username} neither owns database {plan.database} nor holds CREATE on schema public - "
                "the backend creates its own tables and would fail silently",
            )
        detail = f"{plan.username}@{plan.host}:{plan.port}/{plan.database} reachable"
        return VerifyResult(True, detail + (" · owner" if owner == "t" else " · CREATE on public"))

    def describe(self, plan: InstallPlan) -> str:
        pg = plan.postgres
        if pg.mode == "external":
            return f"write postgres-database.json for {pg.username}@{pg.host}:{pg.port}/{pg.database} (external server)"
        return f"install postgresql, create role {pg.username} and database {pg.database} (owner), write postgres-database.json"

    def remove(self, ctx: Context) -> None:
        """Delete the deployment's database. Every file ever uploaded is in it, and it is gone.

        Locally installed: the server, its clusters under ``/var/lib/postgresql`` and its
        configuration are purged. External: only the database this deployment owns is dropped -
        the server belongs to someone else, and dropping its login role needs a superuser this
        installer does not have.
        """
        plan = ctx.plan.postgres
        if plan.mode == "install":
            ctx.remote.systemctl("stop", "postgresql", check=False)
            ctx.remote.systemctl("disable", "postgresql", check=False)
            ctx.remote.apt_purge(["postgresql", "postgresql-contrib", "postgresql-common", "postgresql-client-common"])
            ctx.remote.delete("/var/lib/postgresql", "/etc/postgresql", "/etc/postgresql-common", "/var/log/postgresql")
        else:
            dropped = ctx.remote.run_with_env(
                f"dropdb -h {shlex.quote(plan.host)} -p {plan.port} -U {shlex.quote(plan.username)} --if-exists {shlex.quote(plan.database)}",
                {"PGPASSWORD": ctx.secrets.pg_password},
                timeout=600,
            )
            if dropped.ok:
                ctx.info(f"[PostgreSQL] dropped database {plan.database} on {plan.host}")
            else:
                ctx.warn(f"[PostgreSQL] could not drop {plan.database} on {plan.host}: {ctx.redactor.redact((dropped.err or dropped.out).strip())}")
            ctx.warn(f"[PostgreSQL] the login role {plan.username} is left on the external server - dropping it needs a superuser login this installer does not have")
        ctx.remote.delete(f"{ctx.plan.config_dir}/postgres-database.json")
        ctx.secrets.pg_password, ctx.secrets.pg_password_kept = "", False
        ctx.discovered.existing_postgres = {}

    def describe_removal(self, plan: InstallPlan) -> str:
        pg = plan.postgres
        if pg.mode == "install":
            return (
                f"stop and purge postgresql, then delete /var/lib/postgresql, /etc/postgresql and /var/log/postgresql, "
                f"and {plan.config_dir}/postgres-database.json · THE DATABASE {pg.database} AND EVERY FILE, USER AND SHARE IN IT IS DELETED PERMANENTLY, "
                "and an off-site backup is the only way back"
            )
        return (
            f"drop the database {pg.database} on the external server {pg.host}:{pg.port} and delete {plan.config_dir}/postgres-database.json · "
            f"EVERY FILE, USER AND SHARE IN IT IS DELETED PERMANENTLY · the role {pg.username} and the server itself are left alone"
        )

    # --- internals -------------------------------------------------------------------------------

    def _write_file(self, ctx: Context, password: str) -> None:
        ctx.remote.put_text(f"{ctx.plan.config_dir}/postgres-database.json", to_json(render_postgres_credentials(ctx.plan, password)), mode=0o600)

    def _file_current(self, ctx: Context, password: str) -> bool:
        from cloud_driver_installer.config_files import parse_json

        current = parse_json(ctx.remote.read_text(f"{ctx.plan.config_dir}/postgres-database.json"))
        return current == render_postgres_credentials(ctx.plan, password)

    def _psql(self, ctx: Context, sql: str) -> str:
        """Run ``sql`` as the local ``postgres`` superuser and return the single scalar."""
        result = ctx.remote.run(f"runuser -u postgres -- psql -v ON_ERROR_STOP=1 -tAc {shlex.quote(sql)}", quiet=True)
        return result.out.strip() if result.ok else ""

    def _run_sql(self, ctx: Context, sql: str) -> None:
        """Feed ``sql`` (which may contain a password) to psql over stdin, never on the command line."""
        result = ctx.remote.run("runuser -u postgres -- psql -v ON_ERROR_STOP=1 -q", input=sql + "\n", quiet=True)
        if not result.ok:
            raise StepError(f"psql failed: {ctx.redactor.redact((result.err or result.out).strip().splitlines()[-1] if (result.err or result.out).strip() else 'unknown error')}")

    def _login_query(self, ctx: Context, password: str, sql: str) -> str:
        plan = ctx.plan.postgres
        result = ctx.remote.run_with_env(
            f"psql -h {shlex.quote(plan.host)} -p {plan.port} -U {shlex.quote(plan.username)} -d {shlex.quote(plan.database)} -tAc {shlex.quote(sql)}",
            {"PGPASSWORD": password},
            timeout=60,
        )
        return result.out.strip() if result.ok else ""

    def _login_works(self, ctx: Context, password: str) -> bool:
        return self._login_query(ctx, password, "SELECT 1") == "1"

    def _login_problem(self, ctx: Context, password: str) -> str:
        """``""`` when the login works, else why it did not, in the operator's terms.

        psql's own wording ("FATAL: password authentication failed") says what happened but not
        what to do about it, and the answer differs per mode: a local server is one this step
        owns and can rewrite, an external one holds a role nobody here can change.
        """
        plan = ctx.plan.postgres
        result = ctx.remote.run_with_env(
            f"psql -h {shlex.quote(plan.host)} -p {plan.port} -U {shlex.quote(plan.username)} -d {shlex.quote(plan.database)} -tAc {shlex.quote('SELECT 1')}",
            {"PGPASSWORD": password},
            timeout=60,
        )
        if result.ok and result.out.strip() == "1":
            return ""
        message = (result.err or result.out or "").strip()
        lowered = message.lower()
        if "password authentication failed" in lowered or "no password supplied" in lowered:
            if plan.mode == "external":
                return (
                    f"the password is not {plan.username}'s password on that server. This step never creates or changes a role on an "
                    "external server - type the existing password on this page, or set it there yourself"
                )
            return "the password does not match the role - tick 'rotate' to set the role's password to the one in the plan"
        if "does not exist" in lowered and "database" in lowered:
            return f"the database {plan.database} does not exist on that server"
        if "does not exist" in lowered and "role" in lowered:
            return f"the role {plan.username} does not exist on that server"
        if any(text in lowered for text in ("could not connect", "connection refused", "no route to host", "timeout expired", "could not translate")):
            return f"nothing answers on {plan.host}:{plan.port} - check the address, the server's listen_addresses and pg_hba.conf, and any firewall between here and it"
        if "no pg_hba.conf entry" in lowered:
            return f"the server refuses connections from this host for {plan.username} - it needs a pg_hba.conf entry for it"
        return ctx.redactor.redact(message.splitlines()[-1]) if message else "no answer from psql"


class RedisStep(Step):
    """Redis: loopback-bound, password-protected, and never a source of truth."""

    id = "redis"
    title = "Redis"
    removable = True
    depends_on = ("packages",)

    def enabled(self, plan: InstallPlan) -> bool:
        return plan.redis.enabled

    def check(self, ctx: Context) -> CheckResult:
        plan = ctx.plan.redis
        password, kept = resolve_password(ctx, kind="redis")
        note = "password kept from redis-database.json" if kept else "password generated"
        if plan.mode == "external":
            ok = self._ping(ctx, password) and self._file_current(ctx, password)
            return (CheckResult.ok if ok else CheckResult.needs_apply)(f"external {plan.host}:{plan.port} · {note}")
        if not ctx.remote.dpkg_installed("redis-server"):
            return CheckResult.needs_apply("not installed")
        config = ctx.remote.read_text("/etc/redis/redis.conf") or ""
        bound = any(line.startswith("bind 127.0.0.1") for line in config.splitlines())
        secured = any(line.startswith("requirepass ") for line in config.splitlines())
        if bound and secured and not plan.rotate and self._file_current(ctx, password) and self._ping(ctx, password):
            return CheckResult.ok(f"redis-server on {plan.host}:{plan.port}, loopback + requirepass · {note}")
        problems = [text for text, ok in (("not loopback-bound", bound), ("no requirepass", secured)) if not ok]
        return CheckResult.needs_apply(", ".join(problems) or ("rotate password" if plan.rotate else "credentials file / PING"))

    def apply(self, ctx: Context) -> None:
        plan = ctx.plan.redis
        password, _ = resolve_password(ctx, kind="redis")
        if plan.mode == "install":
            if not ctx.remote.dpkg_installed("redis-server"):
                ctx.remote.apt_install(["redis-server"])
            config = ctx.remote.read_text("/etc/redis/redis.conf") or ""
            ctx.remote.put_text("/etc/redis/redis.conf", rewrite_redis_conf(config, password), mode=0o640)
            self._write_file(ctx, password)
            ctx.remote.systemctl("enable", "redis-server", check=False)
            ctx.remote.systemctl("restart", "redis-server")
        else:
            self._write_file(ctx, password)

    def verify(self, ctx: Context) -> VerifyResult:
        plan = ctx.plan.redis
        if self._ping(ctx, ctx.secrets.redis_password):
            return VerifyResult(True, f"PING answered on {plan.host}:{plan.port}")
        return VerifyResult(False, f"no PONG from {plan.host}:{plan.port} - check the password and the bind address")

    def describe(self, plan: InstallPlan) -> str:
        redis = plan.redis
        if redis.mode == "external":
            return f"write redis-database.json for {redis.host}:{redis.port} (external server)"
        return "install redis-server, bind to loopback, set requirepass, write redis-database.json"

    def remove(self, ctx: Context) -> None:
        """Purge Redis and its data. Nothing authoritative lives here - only cache and rate limits."""
        plan = ctx.plan.redis
        if plan.mode == "install":
            ctx.remote.systemctl("stop", "redis-server", check=False)
            ctx.remote.systemctl("disable", "redis-server", check=False)
            ctx.remote.apt_purge(["redis-server", "redis-tools"])
            ctx.remote.delete("/var/lib/redis", "/etc/redis", "/var/log/redis")
        else:
            ctx.warn(f"[Redis] the external server {plan.host}:{plan.port} is left untouched - only this deployment's credentials file is removed")
        ctx.remote.delete(f"{ctx.plan.config_dir}/redis-database.json")
        ctx.secrets.redis_password, ctx.secrets.redis_password_kept = "", False
        ctx.discovered.existing_redis = {}

    def describe_removal(self, plan: InstallPlan) -> str:
        if plan.redis.mode == "install":
            return (
                f"stop and purge redis-server, delete /var/lib/redis, /etc/redis and {plan.config_dir}/redis-database.json · "
                "rate-limit counters and webhook history are lost, nothing authoritative is (the database holds that)"
            )
        return f"delete {plan.config_dir}/redis-database.json · the external Redis at {plan.redis.host}:{plan.redis.port} is left as it is"

    # --- internals -------------------------------------------------------------------------------

    def _write_file(self, ctx: Context, password: str) -> None:
        ctx.remote.put_text(f"{ctx.plan.config_dir}/redis-database.json", to_json(render_redis_credentials(ctx.plan, password)), mode=0o600)

    def _file_current(self, ctx: Context, password: str) -> bool:
        from cloud_driver_installer.config_files import parse_json

        current = parse_json(ctx.remote.read_text(f"{ctx.plan.config_dir}/redis-database.json"))
        return current == render_redis_credentials(ctx.plan, password)

    def _ping(self, ctx: Context, password: str) -> bool:
        plan = ctx.plan.redis
        if not ctx.remote.command_exists("redis-cli"):
            return False
        command = f"redis-cli -h {shlex.quote(plan.host)} -p {plan.port}"
        if plan.mode == "external" and plan.username:
            command += f" --user {shlex.quote(plan.username)}"
        result = ctx.remote.run_with_env(command + " PING", {"REDISCLI_AUTH": password}, timeout=30)
        return "PONG" in result.out


def rewrite_redis_conf(config: str, password: str) -> str:
    """Return ``config`` with a loopback ``bind`` and ``requirepass`` set (other lines untouched)."""
    lines = config.splitlines()
    replaced_bind = replaced_pass = False
    out: list[str] = []
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("bind ") and not replaced_bind:
            out.append("bind 127.0.0.1 -::1")
            replaced_bind = True
        elif stripped.startswith("requirepass ") and not replaced_pass:
            out.append(f"requirepass {password}")
            replaced_pass = True
        elif stripped.startswith("bind ") or stripped.startswith("requirepass "):
            continue  # drop further duplicates so the last one cannot win
        else:
            out.append(line)
    if not replaced_bind:
        out.append("bind 127.0.0.1 -::1")
    if not replaced_pass:
        out.append(f"requirepass {password}")
    return "\n".join(out).rstrip("\n") + "\n"

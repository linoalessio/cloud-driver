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
from cloud_driver_installer.model import LOOPBACK_HOSTS, InstallPlan, host_is_this_server
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
    """The system of record: role, database (owned by the role) and ``postgres-database.json``.

    **The host decides, not the mode.** A host that is this server - loopback, or any address the
    server answers to - is a database *on the machine being installed*, so this step installs,
    starts, ports, populates and (on removal) purges the server there, whether the operator picked
    *Install on this server* or *Use an external server*. Only a host that is genuinely somebody
    else's machine is left alone, and then the step writes the credentials file and a client to
    probe with, nothing more. A mode that pointed at this server and installed nothing could never
    succeed: the file would name an address with no server behind it, and the next run would read
    the same file and conclude the same thing again.
    """

    id = "postgres"
    title = "PostgreSQL"
    mandatory = True
    depends_on = ("packages",)
    removable = True

    def check(self, ctx: Context) -> CheckResult:
        plan = ctx.plan.postgres
        self._adopt_local_host(ctx)
        password, kept = resolve_password(ctx, kind="postgres")
        note = "password kept from postgres-database.json" if kept else ("password from the plan" if plan.password else "password generated")
        if not self._server_is_here(ctx):
            reachable = self._login_works(ctx, password)
            detail = f"external {plan.username}@{plan.host}:{plan.port}/{plan.database} · {note}"
            if not reachable:
                # Somebody else's server is one this step cannot look at, so the checklist in
                # _login_problem is all there is; the local cases are handled by the branch below.
                hint = self._local_server_hint(ctx)
                if hint:
                    detail += f" · {hint}"
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
        self._adopt_local_host(ctx)
        password, kept = resolve_password(ctx, kind="postgres")
        if self._server_is_here(ctx):
            if plan.mode == "external":
                ctx.info(
                    f"[PostgreSQL] {plan.host} is this server, so the server itself is installed and managed here - "
                    "'Use an external server' only leaves somebody else's machine alone"
                )
            if not ctx.remote.dpkg_installed("postgresql"):
                ctx.remote.apt_install(["postgresql", "postgresql-contrib"])
            ctx.remote.systemctl("enable", "--now", "postgresql")
            self._ensure_cluster_port(ctx)
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
            # Somebody else's server: the credentials file, and a client so the probe below can run
            # from the server rather than from the operator's machine. Nothing else is ours to touch.
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
        if pg.mode == "external" and pg.host not in LOOPBACK_HOSTS:
            return (
                f"write postgres-database.json for {pg.username}@{pg.host}:{pg.port}/{pg.database} (external server) - "
                f"or install the server here first, if {pg.host} turns out to be this machine"
            )
        return f"install postgresql, create role {pg.username} and database {pg.database} (owner), write postgres-database.json"

    def remove(self, ctx: Context) -> None:
        """Delete the deployment's database. Every file ever uploaded is in it, and it is gone.

        On this server - whichever mode named it - the server, its clusters under
        ``/var/lib/postgresql`` and its configuration are purged, because this step is what put them
        there. On somebody else's machine only the database this deployment owns is dropped: the
        server is not ours, and dropping its login role needs a superuser this installer does not
        have.
        """
        plan = ctx.plan.postgres
        if self._server_is_here(ctx):
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
        if pg.mode == "install" or pg.host in LOOPBACK_HOSTS:
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

    @staticmethod
    def _this_server_addresses(ctx: Context) -> list[str]:
        """Every address that is this server, whether or not the preflight step has run yet.

        ``Discovered.own_addresses`` is the full list, but it is only filled once the *Server* step
        has checked; a single step applied on its own must reach the same conclusion, so the public
        address and the host the SSH session dialled stand in for it. Answering "not this server"
        because nothing has been discovered yet is the one wrong answer available here: it would
        install nothing and write an address with no server behind it.
        """
        return [address for address in (*ctx.discovered.own_addresses, ctx.discovered.public_ip, ctx.plan.ssh.host) if address]

    def _server_is_here(self, ctx: Context) -> bool:
        """Whether the database this plan names lives on the server we are connected to.

        The single question every branch of this step turns on. ``install`` mode always does (its
        host is validated as loopback); ``external`` mode does too whenever the host the operator
        typed is one of this server's own addresses, and then the server is this step's to install
        and to purge.
        """
        plan = ctx.plan.postgres
        return plan.mode == "install" or host_is_this_server(plan.host, self._this_server_addresses(ctx))

    def _adopt_local_host(self, ctx: Context) -> None:
        """Rewrite a host that is this server's *public* address to loopback.

        A server this step installs listens on loopback, so the public address the operator typed
        can never answer on it - and it is the backend, running on this same machine, that has to
        dial whatever the credentials file records. Normalising it here is the same rule
        ``apply_existing_config`` applies to an address it finds in an existing file; doing it in
        ``check`` as well as ``apply`` keeps the plan, the sidebar and the removal dialog agreeing
        about which machine the database is on.
        """
        plan = ctx.plan.postgres
        if plan.host in LOOPBACK_HOSTS or not host_is_this_server(plan.host, self._this_server_addresses(ctx)):
            return
        ctx.info(f"[PostgreSQL] {plan.host} is this server's own address - recording the database as 127.0.0.1, which is how it is reached")
        plan.host = "127.0.0.1"

    def _write_file(self, ctx: Context, password: str) -> None:
        ctx.remote.put_text(f"{ctx.plan.config_dir}/postgres-database.json", to_json(render_postgres_credentials(ctx.plan, password)), mode=0o600)

    def _file_current(self, ctx: Context, password: str) -> bool:
        from cloud_driver_installer.config_files import parse_json

        current = parse_json(ctx.remote.read_text(f"{ctx.plan.config_dir}/postgres-database.json"))
        return current == render_postgres_credentials(ctx.plan, password)

    def _psql(self, ctx: Context, sql: str) -> str:
        """Run ``sql`` as the local ``postgres`` superuser and return the single scalar.

        The port is always passed: psql otherwise talks to the socket of the *default* port, so on
        a host whose cluster was set up on another port every one of these probes would come back
        empty and the step would report a missing role and database that are both there.
        """
        result = ctx.remote.run(
            f"runuser -u postgres -- psql -p {ctx.plan.postgres.port} -v ON_ERROR_STOP=1 -tAc {shlex.quote(sql)}", quiet=True
        )
        return result.out.strip() if result.ok else ""

    def _run_sql(self, ctx: Context, sql: str) -> None:
        """Feed ``sql`` (which may contain a password) to psql over stdin, never on the command line."""
        result = ctx.remote.run(
            f"runuser -u postgres -- psql -p {ctx.plan.postgres.port} -v ON_ERROR_STOP=1 -q", input=sql + "\n", quiet=True
        )
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

    def _local_server_hint(self, ctx: Context) -> str:
        """Why a connection to a host that is *this server* failed, or ``""`` if that is not the reason.

        psql's "connection refused" sends the operator off to check listen_addresses, pg_hba.conf
        and firewalls, none of which exists yet on a host with no server on it. Name the actual
        cause instead - and only for a host this step can actually look at.
        """
        plan = ctx.plan.postgres
        own_address = host_is_this_server(plan.host, self._this_server_addresses(ctx)) and plan.host not in LOOPBACK_HOSTS
        if plan.host not in LOOPBACK_HOSTS and not own_address:
            return ""  # somebody else's server: nothing on this host can explain it
        if not ctx.remote.dpkg_installed("postgresql"):
            return "no PostgreSQL server is installed on this host yet - applying this step installs one"
        clusters = self._local_clusters(ctx)
        if clusters is None:
            if not ctx.remote.service_active("postgresql"):
                return "postgresql is installed on this host but not running (systemctl start postgresql)"
        else:
            online = [port for port, status in clusters if status.startswith("online")]
            if not online:
                return "postgresql is installed on this host but no cluster is online (systemctl start postgresql)"
            if str(plan.port) not in online:
                return (
                    f"the PostgreSQL server on this host listens on port {' and '.join(online)}, not {plan.port} - "
                    "run Check all to take the port from the server's own postgres-database.json, or correct it here"
                )
        if own_address:
            return (
                f"the server's PostgreSQL accepts connections on its loopback interface only, so {plan.host} can never "
                "answer it - this field is resolved on the server, not on your machine, so 127.0.0.1 is the address you "
                "want, and applying this step records it that way"
            )
        return ""

    def _ensure_cluster_port(self, ctx: Context) -> None:
        """Make the local cluster listen on the port the plan names.

        A freshly installed Debian cluster is on 5432, so a deployment that records any other port
        (this installer never invents one - it is taken over from the server's own
        ``postgres-database.json``) would otherwise be pointed at a cluster that is not there,
        and every probe, role and database below it would fail against nothing.
        """
        plan = ctx.plan.postgres
        clusters = self._local_clusters(ctx)
        if clusters is None or any(port == str(plan.port) for port, _status in clusters):
            return
        if len(clusters) > 1:
            raise StepError(
                f"this host runs {len(clusters)} PostgreSQL clusters (ports {', '.join(port for port, _ in clusters)}) - "
                f"point the plan at one of them, or leave a single cluster, before this step can set port {plan.port}"
            )
        current = clusters[0][0] if clusters else "?"
        result = ctx.remote.run(f"pg_conftool set port {plan.port}", quiet=True)
        if not result.ok:
            raise StepError(
                f"could not move the cluster from port {current} to {plan.port}: "
                f"{(result.err or result.out).strip().splitlines()[-1] if (result.err or result.out).strip() else 'pg_conftool failed'}"
            )
        ctx.remote.systemctl("restart", "postgresql")
        ctx.info(f"[PostgreSQL] cluster moved from port {current} to {plan.port}")

    @staticmethod
    def _local_clusters(ctx: Context) -> "list[tuple[str, str]] | None":
        """``(port, status)`` per Debian cluster on the host, or ``None`` if that cannot be asked.

        ``pg_lsclusters`` is the only thing here that knows a cluster's real port without being
        able to connect to it first, which is exactly the situation a refused connection leaves.
        """
        result = ctx.remote.run("pg_lsclusters --no-header", quiet=True, timeout=60)
        if not result.ok:
            return None
        rows: list[tuple[str, str]] = []
        for line in result.out.splitlines():
            parts = line.split()
            if len(parts) >= 4 and parts[2].isdigit():
                rows.append((parts[2], parts[3]))
        return rows

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
            hint = self._local_server_hint(ctx)
            if hint:
                return f"nothing answers on {plan.host}:{plan.port} - {hint}"
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
            ctx.remote.put_text("/etc/redis/redis.conf", rewrite_redis_conf(config, password, plan.port), mode=0o640)
            self._write_file(ctx, password)
            ctx.remote.systemctl("enable", "redis-server", check=False)
            ctx.remote.systemctl("restart", "redis-server")
        else:
            # Same as the external PostgreSQL branch installing postgresql-client: the probe runs
            # on the server, so the server needs a client for a store it does not host itself.
            if not ctx.remote.command_exists("redis-cli"):
                ctx.remote.apt_install(["redis-tools"])
            self._write_file(ctx, password)

    def verify(self, ctx: Context) -> VerifyResult:
        plan = ctx.plan.redis
        if self._ping(ctx, ctx.secrets.redis_password):
            return VerifyResult(True, f"PING answered on {plan.host}:{plan.port}")
        return VerifyResult(False, f"no PONG from {plan.host}:{plan.port} - {self._ping_problem(ctx)}")

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

    def _ping_problem(self, ctx: Context) -> str:
        """Why the PING went unanswered, in terms of what the operator can change here.

        The common one is a host that is not loopback while redis-server on this very machine is
        bound to it: the address answers nothing, and the same address would have been written
        into ``redis-database.json`` for the backend to fail on next.
        """
        plan = ctx.plan.redis
        own_address = bool(ctx.discovered.public_ip) and plan.host == ctx.discovered.public_ip
        on_this_host = plan.host in LOOPBACK_HOSTS or own_address
        installed = ctx.remote.dpkg_installed("redis-server")
        # Most actionable first: "nothing is installed here" beats "redis-cli is missing", which is
        # only a fact about the probe and leaves the operator to work out the cause themselves.
        if on_this_host and not installed:
            if plan.mode == "external":
                return (
                    "no Redis is installed on this host, and 'Use an external server' never installs one - "
                    "switch the mode to 'Install on this server' and apply this step again"
                )
            return "no Redis is installed on this host yet"
        if own_address and installed:
            config = ctx.remote.read_text("/etc/redis/redis.conf") or ""
            if any(line.strip().startswith("bind 127.0.0.1") for line in config.splitlines()):
                return (
                    f"redis-server on this host listens on 127.0.0.1 only, so {plan.host} can never answer it - "
                    "this field is resolved on the server, not on your machine, so 127.0.0.1 is the address you want"
                )
        if not ctx.remote.command_exists("redis-cli"):
            return "redis-cli is not installed on the server, so nothing can be probed from it"
        if plan.mode == "external":
            return "check the password, that server's bind address and any firewall between this server and it"
        return "check the password and the bind address"

    def _ping(self, ctx: Context, password: str) -> bool:
        plan = ctx.plan.redis
        if not ctx.remote.command_exists("redis-cli"):
            return False
        command = f"redis-cli -h {shlex.quote(plan.host)} -p {plan.port}"
        if plan.mode == "external" and plan.username:
            command += f" --user {shlex.quote(plan.username)}"
        result = ctx.remote.run_with_env(command + " PING", {"REDISCLI_AUTH": password}, timeout=30)
        return "PONG" in result.out


def rewrite_redis_conf(config: str, password: str, port: int = 6379) -> str:
    """Return ``config`` with a loopback ``bind``, ``requirepass`` and ``port`` set.

    The port is written for the same reason the PostgreSQL step moves its cluster: the plan's port
    is what goes into ``redis-database.json`` for the backend to dial, so the server has to be
    listening there. Every other line is left exactly as the package shipped it.
    """
    lines = config.splitlines()
    replaced_bind = replaced_pass = replaced_port = False
    out: list[str] = []
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("bind ") and not replaced_bind:
            out.append("bind 127.0.0.1 -::1")
            replaced_bind = True
        elif stripped.startswith("requirepass ") and not replaced_pass:
            out.append(f"requirepass {password}")
            replaced_pass = True
        elif stripped.startswith("port ") and not replaced_port:
            out.append(f"port {port}")
            replaced_port = True
        elif stripped.startswith(("bind ", "requirepass ", "port ")):
            continue  # drop further duplicates so the last one cannot win
        else:
            out.append(line)
    for written, line in ((replaced_bind, "bind 127.0.0.1 -::1"), (replaced_pass, f"requirepass {password}"), (replaced_port, f"port {port}")):
        if not written:
            out.append(line)
    return "\n".join(out).rstrip("\n") + "\n"

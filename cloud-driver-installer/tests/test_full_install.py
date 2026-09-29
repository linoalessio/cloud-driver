"""A bare server, installed end to end: adoption, PostgreSQL, Redis - nothing done by hand.

The unit tests around these steps each pin one decision. This one is the whole path, against the
state a server is really in after a full removal: the packages are gone, ``/var/lib/postgresql`` is
gone, and the only things left are the two credentials files a failed run wrote - holding the
server's *public* address and a non-default port.

That combination is what used to make a re-run impossible: the address read as "somebody else's
server", so the steps installed nothing, wrote the same address back, and the next run adopted it
again. Everything below therefore has to come out of one pass, with no operator intervention:
the stores adopted as local, the packages installed, the cluster moved to the recorded port, the
role and database created, both services answering, and both credentials files pointing at the
loopback the backend beside them dials.
"""

from __future__ import annotations

import pytest

from cloud_driver_installer.config_files import parse_json
from cloud_driver_installer.engine import Context, StepStatus
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.steps.datastores import PostgresStep, RedisStep
from cloud_driver_installer.steps.system import ServerStep

from fake_remote import FakeRemote

PUBLIC_IP = "82.165.48.39"
RECORDED_PORT = 20411
DATABASE = "cloud_driver"
ROLE = "cloud_driver_postgres"
KEPT_PASSWORD = "3f9c1d7e2a4b6c8d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f7"

#: What the redis-server package ships, before the step rewrites it.
SHIPPED_REDIS_CONF = "bind 127.0.0.1 -::1\nport 6379\nsupervised auto\nsave 900 1\n"


class FakeDebian(FakeRemote):
    """A throwaway Debian host that actually changes state when a step installs something.

    ``FakeRemote`` answers scripted commands; this subclass keeps the little bit of state the
    path depends on - which packages exist, which port the cluster is on, whether the role and
    database were created - so that a check really does see what the apply before it did.
    """

    def __init__(self) -> None:
        super().__init__(default_ok=True)
        self.cluster_port = ""   # "" until postgresql is installed
        self.role = False
        self.database = False
        self.files["/home/cloud/cloud-driver/postgres-database.json"] = (
            '{"address": "%s", "userName": "%s", "password": "%s", "port": %d, "database": "%s", "fileRepository": "Unknown"}'
            % (PUBLIC_IP, ROLE, KEPT_PASSWORD, RECORDED_PORT, DATABASE)
        )
        self.files["/home/cloud/cloud-driver/redis-database.json"] = (
            '{"address": "%s", "userName": "", "password": "%s", "port": 6379, "database": "0", "fileRepository": "Unknown"}'
            % (PUBLIC_IP, KEPT_PASSWORD)
        )
        self._script()

    # --- the host's own answers ------------------------------------------------------------------

    def _script(self) -> None:
        self.on("id -u", (0, "0"))
        self.on("ipify", (0, PUBLIC_IP))
        self.on("hostname -I", (0, f"10.0.0.7 {PUBLIC_IP}"))
        self.on("dpkg-query", lambda command, _input: (0, "install ok installed") if command.split()[-1] in self.apt_installed else (1, ""))
        self.on("pg_conftool set port", self._move_cluster)
        self.on("pg_lsclusters", self._clusters)
        self.on("runuser -u postgres -- psql", self._superuser_psql)
        self.on("psql -h", self._login_psql)
        # More specific first: the rules are scanned in order, and "command -v redis-cli" would
        # otherwise be answered by the PING rule below.
        self.on("command -v redis-cli", lambda *_: 0 if "redis-server" in self.apt_installed else 1)
        self.on("redis-cli", self._redis_ping)

    def mkdirs(self, *paths: str, mode: int | None = None) -> None:
        super().mkdirs(*paths, mode=mode)
        for path in paths:  # so a later exists() sees the layout this really created
            self.modes.setdefault(path, mode if mode is not None else 0o755)

    def apt_install(self, packages: list[str]) -> None:
        super().apt_install(packages)
        if "postgresql" in packages:
            self.cluster_port = "5432"          # what the package gives you
        if "redis-server" in packages:
            self.files.setdefault("/etc/redis/redis.conf", SHIPPED_REDIS_CONF)

    # --- responders --------------------------------------------------------------------------------

    def _move_cluster(self, command: str, _input: str | None):
        self.cluster_port = command.split()[-1]
        return 0

    def _clusters(self, _command: str, _input: str | None):
        if not self.cluster_port:
            return (127, "", "pg_lsclusters: command not found")
        return (0, f"17  main  {self.cluster_port}  online  postgres  /var/lib/postgresql/17/main  /var/log/postgresql/x.log")

    def _superuser_psql(self, command: str, sql: str | None):
        """psql as the postgres superuser, over the socket of the port the command names."""
        if f"-p {self.cluster_port}" not in command:
            return (2, "", "psql: error: connection to server on socket failed: No such file or directory")
        statement = sql or command
        if "CREATE ROLE" in statement:
            self.role = True
            return 0
        if "CREATE DATABASE" in statement:
            self.database = True
            return 0
        if "ALTER DATABASE" in statement:
            return 0
        if "pg_roles" in statement:
            return (0, "1" if self.role else "")
        if "pg_database" in statement:
            return (0, "1" if self.database else "")
        if "SHOW server_version" in statement:
            return (0, "17.4")
        return (0, "")

    def _login_psql(self, command: str, password: str | None):
        """The role's own TCP login - what verify uses, and what the backend will do next."""
        if f"-p {self.cluster_port}" not in command or f"-h 127.0.0.1" not in command:
            return (2, "", 'psql: error: connection to server failed: Connection refused')
        if not (self.role and self.database):
            return (2, "", f'psql: error: FATAL: role "{ROLE}" does not exist')
        if (password or "").strip() != KEPT_PASSWORD:
            return (2, "", "psql: error: FATAL: password authentication failed")
        if "pg_get_userbyid" in command or "has_schema_privilege" in command:
            return (0, "t")
        return (0, "1")

    def _redis_ping(self, command: str, password: str | None):
        config = self.files.get("/etc/redis/redis.conf", "")
        if "redis-server" not in self.apt_installed:
            return (1, "", "Could not connect to Redis: Connection refused")
        if "-h 127.0.0.1" not in command or "port 6379" not in config:
            return (1, "", "Could not connect to Redis: Connection refused")
        if f"requirepass {(password or '').strip()}" not in config:
            return (0, "NOAUTH Authentication required")
        return (0, "PONG")


@pytest.fixture
def bare_server(ctx: Context) -> Context:
    """The ``ctx`` fixture, but talking to a wiped Debian host with the two files left on it."""
    ctx.remote = FakeDebian()
    ctx.plan.ssh.host = PUBLIC_IP
    ctx.plan.server.install_dir = "/home/cloud"
    return ctx


def test_a_wiped_server_is_installed_again_in_one_pass(bare_server: Context) -> None:
    ctx, remote = bare_server, bare_server.remote
    plan: InstallPlan = ctx.plan

    # 1. The server step adopts the two files. Both name this host's public address, so both are
    #    taken over as stores to install here - at the loopback everything on that host dials.
    ServerStep().check(ctx)
    assert (plan.postgres.mode, plan.postgres.host, plan.postgres.port) == ("install", "127.0.0.1", RECORDED_PORT)
    assert (plan.redis.mode, plan.redis.host, plan.redis.port) == ("install", "127.0.0.1", 6379)

    # 2. Nothing is installed, so both steps have work to do.
    assert PostgresStep().check(ctx).status is StepStatus.NEEDS_APPLY
    assert RedisStep().check(ctx).status is StepStatus.NEEDS_APPLY

    # 3. PostgreSQL: the package, the recorded port, the role, the database - and a real login.
    PostgresStep().apply(ctx)
    assert "postgresql" in remote.apt_installed and "postgresql-contrib" in remote.apt_installed
    assert remote.ran(f"pg_conftool set port {RECORDED_PORT}") and remote.cluster_port == str(RECORDED_PORT)
    assert remote.role and remote.database
    verified = PostgresStep().verify(ctx)
    assert verified.ok, verified.detail

    # 4. Redis: the package, a loopback bind, a password, and a PONG.
    RedisStep().apply(ctx)
    assert "redis-server" in remote.apt_installed
    config = remote.files["/etc/redis/redis.conf"]
    assert "bind 127.0.0.1 -::1" in config and f"requirepass {KEPT_PASSWORD}" in config and "port 6379" in config
    verified = RedisStep().verify(ctx)
    assert verified.ok, verified.detail

    # 5. Both credentials files now point at the loopback, keeping the password and the port that
    #    were already on the server - the backend beside them reads exactly this.
    written = parse_json(remote.files["/home/cloud/cloud-driver/postgres-database.json"])
    assert written["address"] == "127.0.0.1" and written["port"] == RECORDED_PORT
    assert written["password"] == KEPT_PASSWORD and written["userName"] == ROLE and written["database"] == DATABASE
    assert parse_json(remote.files["/home/cloud/cloud-driver/redis-database.json"])["address"] == "127.0.0.1"

    # 6. And a second pass has nothing left to do - the run is idempotent, not just successful.
    assert PostgresStep().check(ctx).status is StepStatus.OK
    assert RedisStep().check(ctx).status is StepStatus.OK


def test_the_whole_thing_runs_through_the_runner_without_intervention(bare_server: Context) -> None:
    """The same path as the GUI's Install button drives it: check -> apply -> verify, per step."""
    from cloud_driver_installer.engine import Runner
    from cloud_driver_installer.steps import all_steps

    ctx = bare_server
    runner = Runner([step for step in all_steps() if step.id in ("server", "postgres", "redis")], lambda event: None)
    for step in runner.steps:
        assert runner.run_one(ctx, step), f"{step.title}: {runner.detail[step.id]}"
    assert [runner.status[step.id] for step in runner.steps] == [StepStatus.DONE] * 3

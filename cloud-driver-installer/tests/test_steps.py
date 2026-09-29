"""The steps, driven against a scripted server, and the pure helpers they build their files from."""

from __future__ import annotations

from pathlib import Path

import pytest

from cloud_driver_installer.engine import Context, StepStatus, StepError
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.steps import STEP_IDS, all_steps
from cloud_driver_installer.steps.application import (
    CRON_BEGIN,
    CRON_END,
    ApplicationStep,
    ConfigStep,
    SmokeStep,
    render_crontab,
    render_logrotate,
)
from cloud_driver_installer.steps.daemons import (
    CaddyStep,
    ClamAvStep,
    clamd_limit_values,
    drop_in_is_current,
    ensure_global_block,
    listen_address,
    parse_blocks,
    remove_site_block,
    render_clamd_dropin,
    render_site_block,
    replace_site_block,
    rewrite_clamd_conf,
    site_address,
    site_addresses,
    stale_limits,
)
from cloud_driver_installer.steps.datastores import PostgresStep, RedisStep, resolve_password, rewrite_redis_conf
from cloud_driver_installer.steps.intelligence import build_source_tar, merge_env, render_port_dropin
from cloud_driver_installer.steps.system import BASE_PACKAGES, FirewallStep, JavaStep, PackagesStep, ServerStep, missing_rules, parse_ufw_status

from fake_remote import FakeRemote


def step(step_id: str):
    """One step instance by id."""
    return next(candidate for candidate in all_steps() if candidate.id == step_id)


def no_secret_in_commands(ctx: Context, remote: FakeRemote, secret: str) -> bool:
    """A secret may travel over stdin, never on a command line or in the log."""
    assert secret, "the test needs a non-empty secret"
    in_commands = any(secret in command for command in remote.commands)
    in_log = any(secret in message for _level, message in ctx.captured_log)
    return not in_commands and not in_log


# --- catalog ------------------------------------------------------------------------------------


def test_the_catalog_is_complete_and_ordered() -> None:
    steps = all_steps()
    assert [item.id for item in steps] == list(STEP_IDS)
    assert all(item.title and item.__doc__ for item in steps)


def test_every_dependency_names_an_earlier_step() -> None:
    order = list(STEP_IDS)
    for item in all_steps():
        for dependency in item.depends_on:
            assert order.index(dependency) < order.index(item.id), f"{item.id} depends on the later {dependency}"


def test_disabled_features_switch_their_step_off(plan: InstallPlan) -> None:
    plan.redis.enabled = False
    plan.clamav.enabled = False
    plan.proxy.enabled = False
    plan.intelligence.enabled = False
    plan.email.mode = "none"
    off = {item.id for item in all_steps() if not item.enabled(plan)}
    assert off == {"redis", "clamav", "caddy", "intelligence", "email"}


# --- server -------------------------------------------------------------------------------------


def test_server_step_refuses_a_host_without_apt(ctx: Context, remote: FakeRemote) -> None:
    remote.ok("id -u", "0")
    remote.fail("command -v apt-get")
    with pytest.raises(StepError, match="apt"):
        ServerStep().check(ctx)


def test_server_step_refuses_a_non_root_login(ctx: Context, remote: FakeRemote) -> None:
    remote.ok("id -u", "1000").ok("command -v apt-get").ok("command -v systemctl")
    with pytest.raises(StepError, match="root"):
        ServerStep().check(ctx)


def test_server_step_discovers_and_keeps_the_existing_configuration(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.ok("id -u", "0")
    remote.ok("PRETTY_NAME", "Debian GNU/Linux 12 (bookworm)|debian")
    remote.ok("free -m | awk '/^Mem:/", "7884")
    remote.ok("nproc", "2")
    remote.ok("java -version", 'openjdk version "21.0.4" 2025-07-15')
    remote.ok("python3 --version", "Python 3.11.2")
    remote.ok("timedatectl show", "NTPSynchronized=yes\nTimezone=Etc/UTC")
    remote.files[f"{ctx.plan.config_dir}/configuration.json"] = '{"aws-kms-key-id": "alias/live", "jwt-signing-key": "super-secret-key-value"}'
    remote.on("cat ", lambda command, _input: (0, remote.files.get(command.split("cat ", 1)[1].strip("'"), "")) if command.split("cat ", 1)[1].strip("'") in remote.files else (1, ""))

    result = ServerStep().check(ctx)
    assert ctx.discovered.ram_mib == 7884 and ctx.discovered.java_version.startswith("21")
    assert ctx.plan.aws.kms_mode == "existing" and ctx.plan.aws.kms_key_id == "alias/live"
    assert ctx.plan.app.jvm_xmx  # suggested from the discovered RAM
    assert "super-secret-key-value" in ctx.redactor
    assert result.status in (StepStatus.OK, StepStatus.NEEDS_APPLY)


def test_packages_step_lists_what_is_missing(ctx: Context, remote: FakeRemote) -> None:
    remote.on("dpkg-query", lambda command, _input: (0, "install ok installed") if "screen" in command else (1, ""))
    result = PackagesStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY and "screen" not in result.detail.split(": ")[1]
    PackagesStep().apply(ctx)
    assert "cron" in remote.apt_installed and "logrotate" in remote.apt_installed and "screen" not in remote.apt_installed
    assert ("enable", "--now", "cron") in remote.systemctl_calls


def test_java_step_accepts_only_21(ctx: Context, remote: FakeRemote) -> None:
    ctx.discovered.java_version = "17.0.9"
    assert JavaStep().check(ctx).status is StepStatus.NEEDS_APPLY
    ctx.discovered.java_version = "21.0.4"
    assert JavaStep().check(ctx).status is StepStatus.OK


def test_firewall_opens_the_session_port_before_enabling(ctx: Context, remote: FakeRemote) -> None:
    ctx.plan.ssh.port = 2222
    remote.default_ok = True
    remote.ok("sshd -T", "2222")
    firewall = FirewallStep()
    assert firewall.rules(ctx) == ["2222/tcp", "80/tcp", "443/tcp"]
    firewall.apply(ctx)
    allow_index = next(index for index, command in enumerate(remote.commands) if "ufw allow 2222/tcp" in command)
    enable_index = next(index for index, command in enumerate(remote.commands) if "ufw --force enable" in command)
    assert allow_index < enable_index


UFW_STATUS = """Status: active

To                         Action      From
--                         ------      ----
22/tcp                     ALLOW       Anywhere
8080/tcp                   ALLOW       Anywhere
22/tcp (v6)                ALLOW       Anywhere (v6)
"""


def test_ufw_status_is_parsed_not_searched() -> None:
    """A host that allows 8080 does not thereby allow 80 - substring matching said it did."""
    active, allowed = parse_ufw_status(UFW_STATUS)
    assert active and allowed == {"22/tcp", "8080/tcp"}
    assert missing_rules(["22/tcp", "80/tcp", "443/tcp"], allowed) == ["80/tcp", "443/tcp"]
    assert missing_rules(["22/tcp"], {"22"}) == [], "ufw prints a bare port for the 'any protocol' rule"
    assert parse_ufw_status("Status: inactive\n") == (False, set())


def test_firewall_check_and_verify_read_the_rules_back(ctx: Context, remote: FakeRemote) -> None:
    """Both phases must notice a firewall that is up but does not allow what the plan needs."""
    remote.default_ok = True
    remote.ok("sshd -T", "22")
    remote.on("dpkg-query -W -f='${Status}' ufw", (0, "install ok installed"))
    remote.on("ufw status", (0, UFW_STATUS))
    check = FirewallStep().check(ctx)
    assert check.status is StepStatus.NEEDS_APPLY and "80/tcp" in check.detail and "443/tcp" in check.detail
    verified = FirewallStep().verify(ctx)
    assert not verified.ok and "does not allow" in verified.detail
    assert ctx.discovered.ufw_active is True


def test_firewall_opens_the_rest_port_when_there_is_no_proxy(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.ok("sshd -T", "22")
    ctx.plan.proxy.enabled = False
    ctx.plan.app.rest_bind_host = "0.0.0.0"
    assert FirewallStep().rules(ctx) == ["22/tcp", "80/tcp", "443/tcp", f"{ctx.plan.app.rest_port}/tcp"]
    ctx.plan.server.firewall_extra_ports = f"{ctx.plan.app.rest_port}/tcp"  # naming it twice is one rule
    assert FirewallStep().rules(ctx).count(f"{ctx.plan.app.rest_port}/tcp") == 1
    ctx.plan.server.firewall_extra_ports = ""
    ctx.plan.app.rest_bind_host = "127.0.0.1"  # loopback: only Caddy could reach it, and there is none
    assert FirewallStep().rules(ctx) == ["22/tcp", "80/tcp", "443/tcp"]
    ctx.plan.proxy.enabled = True
    ctx.plan.app.rest_bind_host = "0.0.0.0"
    assert FirewallStep().rules(ctx) == ["22/tcp", "80/tcp", "443/tcp"]


def test_firewall_refuses_to_lock_the_operator_out(ctx: Context, remote: FakeRemote) -> None:
    ctx.plan.ssh.port = 2222
    ctx.plan.server.firewall_extra_ports = ""
    remote.default_ok = True
    remote.ok("sshd -T", "22")
    firewall = FirewallStep()
    firewall.ssh_ports = lambda _ctx: ["22"]  # type: ignore[assignment]
    with pytest.raises(StepError, match="2222"):
        firewall.apply(ctx)


# --- data stores --------------------------------------------------------------------------------


def test_postgres_keeps_the_password_the_server_already_has(ctx: Context) -> None:
    ctx.discovered.existing_postgres = {"password": "a" * 48}
    password, kept = resolve_password(ctx, kind="postgres")
    assert kept and password == "a" * 48 and ctx.secrets.pg_password_kept


def test_postgres_rotation_generates_a_new_password(ctx: Context) -> None:
    ctx.discovered.existing_postgres = {"password": "a" * 48}
    ctx.plan.postgres.rotate = True
    password, kept = resolve_password(ctx, kind="postgres")
    assert not kept and password != "a" * 48 and len(password) == 48


def test_postgres_apply_writes_the_file_first_and_never_shows_the_password(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.ok("dpkg-query", "install ok installed")
    remote.on("psql -v ON_ERROR_STOP=1 -tAc", lambda command, _input: (0, "1") if "pg_database" in command else (0, ""))
    PostgresStep().apply(ctx)
    path = f"{ctx.plan.config_dir}/postgres-database.json"
    assert ctx.secrets.pg_password in remote.files[path] and remote.modes[path] == 0o600
    assert no_secret_in_commands(ctx, remote, ctx.secrets.pg_password)
    assert any(ctx.secrets.pg_password in (value or "") for value in remote.inputs)


def test_postgres_verify_requires_ownership_or_create(ctx: Context, remote: FakeRemote) -> None:
    ctx.secrets.pg_password = "x" * 48
    remote.on("SELECT 1", (0, "1"))
    remote.on("pg_get_userbyid", (0, "f"))
    remote.on("has_schema_privilege", (0, "f"))
    assert not PostgresStep().verify(ctx).ok
    remote.rules.clear()
    remote.on("SELECT 1", (0, "1")).on("pg_get_userbyid", (0, "t")).on("has_schema_privilege", (0, "t"))
    assert PostgresStep().verify(ctx).ok


def test_a_fresh_cluster_is_moved_to_the_port_the_plan_names(ctx: Context, remote: FakeRemote) -> None:
    """apt gives you 5432; a deployment recorded on 20411 needs the cluster to follow."""
    remote.default_ok = True
    remote.on("dpkg-query", (0, "install ok installed"))
    remote.on("pg_lsclusters", (0, "17  main  5432  online  postgres  /var/lib/postgresql/17/main  /var/log/x"))
    remote.on("SELECT 1", (0, "1"))
    ctx.plan.postgres.port = 20411
    PostgresStep().apply(ctx)
    assert remote.ran("pg_conftool set port 20411")
    assert ("restart", "postgresql") in remote.systemctl_calls


def test_a_cluster_already_on_the_right_port_is_left_alone(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.on("dpkg-query", (0, "install ok installed"))
    remote.on("pg_lsclusters", (0, "17  main  20411  online  postgres  /var/lib/postgresql/17/main  /var/log/x"))
    remote.on("SELECT 1", (0, "1"))
    ctx.plan.postgres.port = 20411
    PostgresStep().apply(ctx)
    assert not remote.ran("pg_conftool")


def test_two_clusters_are_refused_rather_than_guessed(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.on("dpkg-query", (0, "install ok installed"))
    remote.on("pg_lsclusters", (0, "16  main  5432  online  postgres  /a  /b\n17  main  5433  online  postgres  /c  /d"))
    ctx.plan.postgres.port = 20411
    with pytest.raises(StepError, match="2 PostgreSQL clusters"):
        PostgresStep().apply(ctx)


def test_redis_conf_carries_the_planned_port(ctx: Context) -> None:
    config = "port 6379\nbind 0.0.0.0\n"
    rewritten = rewrite_redis_conf(config, "hexpassword", 6380)
    assert "port 6380" in rewritten and "port 6379" not in rewritten


def test_redis_conf_is_rewritten_idempotently() -> None:
    config = "port 6379\nbind 0.0.0.0 ::1\n# requirepass foo\nsave 900 1\n"
    once = rewrite_redis_conf(config, "hexpassword")
    assert "bind 127.0.0.1 -::1" in once and "requirepass hexpassword" in once and "save 900 1" in once
    assert rewrite_redis_conf(once, "hexpassword") == once


def test_an_external_redis_gets_a_client_on_the_server_to_probe_it_with(ctx: Context, remote: FakeRemote) -> None:
    """The probe runs on the server, so an external store still needs redis-cli there."""
    remote.default_ok = True
    remote.on("command -v redis-cli", (1, ""))
    ctx.plan.redis.mode = "external"
    ctx.plan.redis.host = "cache.example.com"
    RedisStep().apply(ctx)
    assert "redis-tools" in remote.apt_installed
    assert "redis-server" not in remote.apt_installed, "an external store is never installed here"


def test_redis_apply_writes_credentials_without_leaking_them(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.ok("dpkg-query", "install ok installed")
    remote.files["/etc/redis/redis.conf"] = "bind 0.0.0.0\n"
    RedisStep().apply(ctx)
    path = f"{ctx.plan.config_dir}/redis-database.json"
    assert remote.modes[path] == 0o600 and ctx.secrets.redis_password in remote.files[path]
    assert no_secret_in_commands(ctx, remote, ctx.secrets.redis_password)


def test_an_external_postgres_on_this_host_is_told_it_will_be_installed(ctx: Context, remote: FakeRemote) -> None:
    """'external' + 127.0.0.1 with no server: name the cause, not the firewall psql blames."""
    remote.default_ok = True
    remote.on("dpkg-query", (1, ""))  # nothing installed
    remote.on("psql", (2, "", 'psql: error: connection to server at "127.0.0.1", port 5432 failed: Connection refused'))
    ctx.plan.postgres.mode = "external"
    ctx.plan.postgres.password = "secret"
    result = PostgresStep().verify(ctx)
    assert not result.ok
    assert "no PostgreSQL server is installed on this host yet" in result.detail
    assert "applying this step installs one" in result.detail
    assert "pg_hba.conf" not in result.detail, "the generic advice must not survive here"


def test_an_external_postgres_on_this_host_installs_the_server_anyway(ctx: Context, remote: FakeRemote) -> None:
    """The host decides, not the mode: 127.0.0.1 is this machine, so the server is ours to install.

    A mode that wrote a credentials file naming a host with no server behind it could never succeed,
    and the next run would read that same file and conclude the same thing again.
    """
    remote.default_ok = True
    remote.on("dpkg-query", (1, ""))  # no server yet
    remote.on("SELECT 1", (0, "1"))
    ctx.plan.postgres.mode, ctx.plan.postgres.password = "external", "secret"
    PostgresStep().apply(ctx)
    assert "postgresql" in remote.apt_installed and "postgresql-contrib" in remote.apt_installed
    assert ("enable", "--now", "postgresql") in remote.systemctl_calls
    assert any("CREATE ROLE" in (value or "") for value in remote.inputs), "the role is created here too"
    assert "postgresql-client" not in remote.apt_installed, "the server package brings its own client"
    assert ctx.secrets.pg_password in remote.files[f"{ctx.plan.config_dir}/postgres-database.json"]


def test_an_external_postgres_at_the_server_s_own_address_is_recorded_as_loopback(ctx: Context, remote: FakeRemote) -> None:
    """A locally installed server listens on loopback, so that is the address the file must carry."""
    remote.default_ok = True
    remote.on("dpkg-query", (1, ""))
    remote.on("SELECT 1", (0, "1"))
    ctx.plan.postgres.mode, ctx.plan.postgres.password = "external", "secret"
    ctx.plan.postgres.host, ctx.plan.postgres.port = ctx.discovered.public_ip, 20411
    PostgresStep().apply(ctx)
    assert ctx.plan.postgres.host == "127.0.0.1", "the public address can never answer a loopback bind"
    assert ctx.plan.postgres.port == 20411, "the port the operator chose is kept"
    assert "postgresql" in remote.apt_installed
    assert '"address": "127.0.0.1"' in remote.files[f"{ctx.plan.config_dir}/postgres-database.json"]


def test_an_external_postgres_on_this_host_gets_its_cluster_moved_too(ctx: Context, remote: FakeRemote) -> None:
    """The port in the credentials file is the one the backend dials, whichever mode recorded it."""
    remote.default_ok = True
    remote.on("dpkg-query", (1, ""))
    remote.on("pg_lsclusters", (0, "17  main  5432  online  postgres  /var/lib/postgresql/17/main  /var/log/x"))
    remote.on("SELECT 1", (0, "1"))
    ctx.plan.postgres.mode, ctx.plan.postgres.password = "external", "secret"
    ctx.plan.postgres.host, ctx.plan.postgres.port = ctx.discovered.public_ip, 20411
    PostgresStep().apply(ctx)
    assert remote.ran("pg_conftool set port 20411")


def test_an_external_postgres_somewhere_else_is_still_left_alone(ctx: Context, remote: FakeRemote) -> None:
    """Somebody else's machine is the one case that installs nothing - only a client to probe with."""
    remote.default_ok = True
    remote.on("command -v psql", (1, ""))
    ctx.plan.postgres.mode, ctx.plan.postgres.password = "external", "secret"
    ctx.plan.postgres.host = "db.example.com"
    PostgresStep().apply(ctx)
    assert remote.apt_installed == ["postgresql-client"]
    assert not remote.systemctl_calls, "nothing on this host is started for someone else's database"
    assert ctx.plan.postgres.host == "db.example.com"
    assert ctx.secrets.pg_password in remote.files[f"{ctx.plan.config_dir}/postgres-database.json"]


def test_removing_an_external_postgres_on_this_host_purges_the_server(ctx: Context, remote: FakeRemote) -> None:
    """Whatever this step installed, it also removes - and the dialog has to say which it is."""
    remote.default_ok = True
    ctx.plan.postgres.mode, ctx.plan.postgres.password = "external", "secret"
    ctx.secrets.pg_password = "secret"
    described = PostgresStep().describe_removal(ctx.plan)
    assert "purge postgresql" in described and "/var/lib/postgresql" in described
    PostgresStep().remove(ctx)
    assert "postgresql" in remote.apt_purged
    assert not remote.ran("dropdb"), "a local server is purged whole, not dropped database by database"


def test_removing_an_external_postgres_elsewhere_only_drops_the_database(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    ctx.plan.postgres.mode, ctx.plan.postgres.password = "external", "secret"
    ctx.plan.postgres.host = "db.example.com"
    ctx.secrets.pg_password = "secret"
    assert "the server itself are left alone" in PostgresStep().describe_removal(ctx.plan)
    PostgresStep().remove(ctx)
    assert remote.ran("dropdb") and "postgresql" not in remote.apt_purged


def test_a_stopped_local_postgres_is_named_as_stopped(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.on("dpkg-query", (0, "install ok installed"))
    remote.on("pg_lsclusters", (0, "17  main  5432  down   postgres  /var/lib/postgresql/17/main  /var/log/x"))
    remote.on("psql", (2, "", "psql: error: connection to server at \"127.0.0.1\", port 5432 failed: Connection refused"))
    assert "no cluster is online" in PostgresStep().verify(ctx).detail


def test_a_cluster_on_another_port_is_named_with_its_real_port(ctx: Context, remote: FakeRemote) -> None:
    """The plan's 5432 against a cluster the server actually runs on 20411 - psql just says refused."""
    remote.default_ok = True
    remote.on("dpkg-query", (0, "install ok installed"))
    remote.on("pg_lsclusters", (0, "17  main  20411  online  postgres  /var/lib/postgresql/17/main  /var/log/x"))
    remote.on("psql", (2, "", "psql: error: connection to server at \"127.0.0.1\", port 5432 failed: Connection refused"))
    detail = PostgresStep().verify(ctx).detail
    assert "port 20411, not 5432" in detail and "Check all" in detail


def test_the_server_s_own_public_address_is_explained_not_blamed_on_a_firewall(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    ctx.plan.postgres.mode, ctx.plan.postgres.password = "external", "secret"
    ctx.plan.postgres.host, ctx.plan.postgres.port = ctx.discovered.public_ip, 20411
    remote.on("dpkg-query", (0, "install ok installed"))
    remote.on("pg_lsclusters", (0, "17  main  20411  online  postgres  /var/lib/postgresql/17/main  /var/log/x"))
    remote.on("psql", (2, "", "psql: error: connection to server failed: Connection refused"))
    detail = PostgresStep().verify(ctx).detail
    assert "loopback interface only" in detail and "127.0.0.1 is the address you want" in detail


def test_the_superuser_psql_talks_to_the_port_the_plan_names(ctx: Context, remote: FakeRemote) -> None:
    """A cluster on 20411 answers nothing on the default socket, so every probe must carry -p."""
    remote.default_ok = True
    ctx.plan.postgres.port = 20411
    PostgresStep()._psql(ctx, "SELECT 1")
    assert any("runuser -u postgres" in command and "-p 20411" in command for command in remote.commands)


def test_an_uninstalled_redis_says_so_before_blaming_the_probe(ctx: Context, remote: FakeRemote) -> None:
    """"redis-cli is missing" is a fact about the probe; "nothing is installed here" is the cause."""
    remote.default_ok = True
    remote.on("dpkg-query", (1, ""))
    remote.on("command -v redis-cli", (1, ""))
    ctx.plan.redis.mode = "external"
    ctx.plan.redis.host = ctx.discovered.public_ip
    detail = RedisStep().verify(ctx).detail
    assert "no Redis is installed on this host" in detail and "Install on this server" in detail


def test_an_unreachable_external_server_keeps_the_general_advice(ctx: Context, remote: FakeRemote) -> None:
    """Somewhere else's server is one this step cannot look at, so the checklist is all it has."""
    remote.default_ok = True
    ctx.plan.postgres.mode = "external"
    ctx.plan.postgres.host = "db.example.com"
    ctx.plan.postgres.password = "secret"
    remote.on("psql", (2, "", "psql: error: connection to server at \"db.example.com\" failed: Connection refused"))
    assert "pg_hba.conf" in PostgresStep().verify(ctx).detail


def test_an_external_redis_on_this_host_says_nothing_installs_it(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.on("dpkg-query", (1, ""))
    remote.on("redis-cli", (0, ""))
    ctx.plan.redis.mode = "external"
    assert "never installs one" in RedisStep().verify(ctx).detail


def test_a_failed_ping_names_the_loopback_bind_rather_than_the_password(ctx: Context, remote: FakeRemote) -> None:
    """The usual cause of a silent PING is a host that is not loopback while Redis only binds it."""
    remote.default_ok = True
    remote.on("dpkg-query", (0, "install ok installed"))
    remote.on("redis-cli", (0, ""))  # answers, but with no PONG
    remote.files["/etc/redis/redis.conf"] = "bind 127.0.0.1 -::1\nrequirepass x\n"
    ctx.plan.redis.host = ctx.discovered.public_ip
    result = RedisStep().verify(ctx)
    assert not result.ok
    assert "listens on 127.0.0.1 only" in result.detail and ctx.discovered.public_ip in result.detail


def test_a_failed_ping_on_a_loopback_host_stays_about_the_password(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.on("dpkg-query", (0, "install ok installed"))
    remote.on("redis-cli", (0, ""))
    remote.files["/etc/redis/redis.conf"] = "bind 127.0.0.1 -::1\n"
    assert "password" in RedisStep().verify(ctx).detail


class _SesAnswer:
    """The one AWS call the e-mail step makes before it touches an identity."""

    def __init__(self, answer: "bool | None") -> None:
        self.answer = answer

    def ses_configuration_set_exists(self, name: str, *, region: str | None = None) -> "bool | None":
        return self.answer


def test_a_denied_ses_read_warns_and_lets_the_run_continue(ctx: Context) -> None:
    """A read permission the operator's own policy lacks must not strand a deployment."""
    from cloud_driver_installer.steps.awsresources import EmailStep

    ctx.plan.email.mode = "ses"
    ctx.plan.email.ses_configuration_set = "cloud-driver-transactional"
    ctx.aws_factory = lambda: _SesAnswer(None)
    assert EmailStep()._configuration_set_missing(ctx) is False
    assert any("cannot verify the SES configuration set" in message for _level, message in ctx.captured_log)


def test_a_configuration_set_that_is_really_absent_still_stops_the_step(ctx: Context) -> None:
    """Every send would fail against a name that does not exist, so that one is not a warning."""
    from cloud_driver_installer.steps.awsresources import EmailStep

    ctx.plan.email.mode = "ses"
    ctx.plan.email.ses_configuration_set = "typo-set"
    ctx.aws_factory = lambda: _SesAnswer(False)
    assert EmailStep()._configuration_set_missing(ctx) is True
    ctx.aws_factory = lambda: _SesAnswer(True)
    ctx._aws = None  # type: ignore[attr-defined]  - re-resolve against the new answer
    assert EmailStep()._configuration_set_missing(ctx) is False


# --- daemons ------------------------------------------------------------------------------------


def test_clamd_drop_in_has_exactly_one_ipv4_listener() -> None:
    dropin = render_clamd_dropin("127.0.0.1", 3310)
    assert dropin.count("ListenStream=") == 1 and "[::1]" not in dropin


def test_clamd_limits_rewrite_every_occurrence_and_append_the_missing_ones() -> None:
    """Like the provisioning script's ``sed -i "s/^Key .*/…/"``: every line for a key is rewritten.

    clamd reads the last occurrence, so leaving a stale earlier line would be the one risk here;
    rewriting them all makes the value right whichever one it takes, and applying twice is a no-op.
    """
    config = "StreamMaxLength 25M\nLogFile /var/log/clamav/clamav.log\nStreamMaxLength 30M\n"
    wanted = {"StreamMaxLength": "128M", "MaxFileSize": "128M"}
    rewritten = rewrite_clamd_conf(config, wanted)
    assert "25M" not in rewritten and "30M" not in rewritten
    assert rewritten.count("StreamMaxLength 128M") == 2 and "MaxFileSize 128M" in rewritten
    assert "LogFile /var/log/clamav/clamav.log" in rewritten
    assert rewrite_clamd_conf(rewritten, wanted) == rewritten


def test_clamd_limits_compare_by_value_not_by_spelling() -> None:
    """A conf that already says ``256m`` is current: 256m and 256M are the same size to clamd."""
    assert stale_limits("MaxFileSize 256m\n", {"MaxFileSize": "256M"}) == []
    assert stale_limits("MaxFileSize 128M\n", {"MaxFileSize": "256M"}) == ["MaxFileSize"]
    assert stale_limits("#MaxFileSize 256M\n", {"MaxFileSize": "256M"}) == ["MaxFileSize"], "a commented line sets nothing"
    assert clamd_limit_values("MaxFileSize 1M\nMaxFileSize 2M\n")["MaxFileSize"] == "2M", "clamd takes the last one"


def test_clamd_listener_is_one_ipv4_socket() -> None:
    """A second (IPv6) ListenStream crash-loops clamd 1.4, so the address is normalised or refused."""
    assert listen_address("localhost") == "127.0.0.1" and listen_address("") == "127.0.0.1"
    assert listen_address("10.0.0.5") == "10.0.0.5"
    with pytest.raises(StepError, match="IPv6"):
        listen_address("::1")
    assert drop_in_is_current(render_clamd_dropin("127.0.0.1", 3310), "127.0.0.1", 3310)
    assert not drop_in_is_current("[Socket]\nListenStream=127.0.0.1:3310\nListenStream=[::1]:3310\n", "127.0.0.1", 3310)


def test_clamav_verify_tolerates_a_signature_download(ctx: Context, remote: FakeRemote) -> None:
    remote.on("systemctl is-active --quiet clamav-daemon.socket", (0, ""))
    remote.on("/dev/tcp/", (1, ""))
    remote.on("ls /var/lib/clamav", (1, ""))
    result = ClamAvStep().verify(ctx)
    assert result.ok and "signatures" in result.detail


def test_caddy_block_replacement_leaves_other_sites_alone() -> None:
    caddyfile = (
        "cloud-driver.de {\n    root * /var/www/x\n    file_server\n}\n\n"
        "api.example.com {\n    reverse_proxy 127.0.0.1:9999\n}\n"
    )
    updated = replace_site_block(caddyfile, "api.example.com", render_site_block("api.example.com", 8080))
    assert "cloud-driver.de {" in updated and "127.0.0.1:8080" in updated and "9999" not in updated
    assert replace_site_block(updated, "api.example.com", render_site_block("api.example.com", 8080)) == updated
    added = replace_site_block("cloud-driver.de {\n}\n", "api.example.com", render_site_block("api.example.com", 8080))
    assert "api.example.com {" in added and "cloud-driver.de {" in added


def test_caddy_without_a_domain_serves_plain_http_on_port_80() -> None:
    assert site_address("") == ":80" and site_address("api.example.com") == "api.example.com"
    block = render_site_block("", 8080)
    assert block.startswith(":80 {") and "127.0.0.1:8080" in block


def test_caddy_without_a_domain_replaces_the_packaged_placeholder(ctx: Context, remote: FakeRemote) -> None:
    ctx.plan.proxy.api_domain = ""
    remote.default_ok = True
    remote.files["/etc/caddy/Caddyfile"] = ":80 {\n\troot * /usr/share/caddy\n\tfile_server\n}\n"
    CaddyStep().apply(ctx)
    written = remote.files["/etc/caddy/Caddyfile.new"]  # validated, then moved into place by mv
    assert "/usr/share/caddy" not in written and written.count(":80 {") == 1
    assert "127.0.0.1:8080" in written
    assert "replacing Caddy's packaged placeholder" not in " ".join(message for _level, message in ctx.captured_log)


def test_caddy_check_is_idempotent_without_a_domain(ctx: Context, remote: FakeRemote) -> None:
    ctx.plan.proxy.api_domain = ""
    remote.default_ok = True
    remote.files["/etc/caddy/Caddyfile"] = render_site_block("", ctx.plan.app.rest_port)
    assert CaddyStep().check(ctx).status is StepStatus.OK


TRICKY_CADDYFILE = """{
\temail ops@example.com
}

cloud-driver.de {
\t# a brace in a comment: {
\theader /x "a quoted { brace"
\troot * /var/www
}

api.example.com, www.api.example.com {
\treverse_proxy 127.0.0.1:8080 {
\t\theader_up X-Forwarded-For {remote_host}
\t}
}
"""


def test_caddyfile_blocks_are_parsed_not_brace_counted() -> None:
    """Braces in comments, quoted strings and {placeholders} must not move a block's boundary."""
    assert site_addresses(TRICKY_CADDYFILE) == ["cloud-driver.de", "api.example.com", "www.api.example.com"]
    assert site_addresses("(snippet) {\n\tfile_server\n}\n") == [], "a snippet is not a site"
    blocks = parse_blocks(TRICKY_CADDYFILE)
    assert blocks[0].is_global and [block.header for block in blocks[1:]] == ["cloud-driver.de", "api.example.com, www.api.example.com"]


def test_caddy_site_lookup_ignores_scheme_port_and_case() -> None:
    """The operator's ``https://API.example.com:443`` is the site written as ``api.example.com``."""
    removed = remove_site_block(TRICKY_CADDYFILE, "https://API.example.com:443")
    assert site_addresses(removed) == ["cloud-driver.de", "www.api.example.com"], "a shared header keeps its other name"
    assert "root * /var/www" in removed and "a quoted { brace" in removed, "the homepage block is untouched"


def test_caddy_removal_of_a_sole_site_takes_the_whole_block() -> None:
    caddyfile = "cloud-driver.de {\n\troot * /var/www\n}\n\n" + render_site_block("api.example.com", 8080)
    assert site_addresses(remove_site_block(caddyfile, "api.example.com")) == ["cloud-driver.de"]
    assert remove_site_block(render_site_block("", 8080), ":80") == "", "the last block leaves an empty file"
    assert remove_site_block(caddyfile, "nothing.example.com") == caddyfile, "an unknown site changes nothing"


def test_caddy_global_block_comes_first() -> None:
    result = ensure_global_block("api.example.com {\n}\n", "ops@example.com")
    assert result.splitlines()[0] == "{" and "email ops@example.com" in result
    twice = ensure_global_block(result, "ops@example.com")
    assert twice.count("email ops@example.com") == 1


def test_caddy_apply_validates_before_swapping(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.files["/etc/caddy/Caddyfile"] = "cloud-driver.de {\n}\n"
    remote.on("caddy validate", (1, "", "line 3: unknown directive"))
    with pytest.raises(StepError, match="unknown directive"):
        CaddyStep().apply(ctx)
    assert remote.files["/etc/caddy/Caddyfile"] == "cloud-driver.de {\n}\n"


@pytest.mark.parametrize(
    ("stderr", "mode", "expected"),
    [
        ('psql: error: FATAL:  password authentication failed for user "cloud_driver"', "install", "tick 'rotate'"),
        ('psql: error: FATAL:  password authentication failed for user "cloud_driver"', "external", "never creates or changes a role"),
        ('psql: error: connection to server at "db.example.com", port 5432 failed: Connection refused', "external", "nothing answers on"),
        ('psql: error: FATAL:  database "cloud_driver" does not exist', "install", "does not exist on that server"),
        ('psql: error: FATAL:  no pg_hba.conf entry for host "10.0.0.2"', "external", "pg_hba.conf entry"),
    ],
)
def test_postgres_verify_says_why_the_login_failed(ctx: Context, remote: FakeRemote, stderr: str, mode: str, expected: str) -> None:
    """"cannot log in" is not a diagnosis: each psql failure gets the answer that fits it."""
    ctx.plan.postgres.mode = mode
    ctx.secrets.pg_password = "pg-secret"
    remote.on("psql -h", (2, "", stderr))
    result = PostgresStep().verify(ctx)
    assert not result.ok
    assert expected in result.detail, result.detail
    assert "pg-secret" not in result.detail, "a failure message must never carry the password"


# --- smoke test --------------------------------------------------------------------------------


def test_smoke_reports_every_component_it_probed(ctx: Context, remote: FakeRemote) -> None:
    """The final verdict names the API, the metrics port, the daemons and the cron block."""
    remote.default_ok = True
    remote.on("curl -s -o /dev/null -w '%{http_code}'", (0, "401"))  # the API is up, JWT layer active
    remote.on("crontab -l", (0, f"{CRON_BEGIN}\n@reboot true\n{CRON_END}\n"))
    ctx.plan.intelligence.enabled = True
    result = SmokeStep().verify(ctx)
    assert result.ok
    for expected in ("API 127.0.0.1", "HTTP 401", "metrics", "clamd", "intelligence", "reboot autostart installed"):
        assert expected in result.detail, result.detail


def test_smoke_fails_when_the_api_does_not_answer(ctx: Context, remote: FakeRemote) -> None:
    """No HTTP code at all is the one result that makes the run a failure."""
    remote.default_ok = True
    remote.on("curl -s -o /dev/null -w '%{http_code}'", (7, ""))
    result = SmokeStep().verify(ctx)
    assert not result.ok and "no answer" in result.detail


def test_smoke_probes_the_public_url_only_when_there_is_one(ctx: Context, remote: FakeRemote, monkeypatch: pytest.MonkeyPatch) -> None:
    """With no domain and no known public address there is nothing outside to probe."""
    remote.default_ok = True
    remote.on("curl -s -o /dev/null -w '%{http_code}'", (0, "401"))
    ctx.plan.proxy.enabled, ctx.plan.proxy.api_domain = True, ""
    ctx.discovered.public_ip = ""
    assert "://" not in SmokeStep().verify(ctx).detail

    ctx.discovered.public_ip = "203.0.113.10"
    probed: list[str] = []
    monkeypatch.setattr(SmokeStep, "_public_probe", staticmethod(lambda url: probed.append(url) or f"{url} HTTP 401"))
    SmokeStep().verify(ctx)
    assert probed == ["http://203.0.113.10/auth/me"], "the domain-less deployment is plain HTTP on the address"


# --- configuration, application, intelligence ------------------------------------------------------


def test_config_step_writes_every_file_with_tight_modes(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    ctx.secrets.pg_password = "p" * 48
    ConfigStep().apply(ctx)
    config_path = f"{ctx.plan.config_dir}/configuration.json"
    assert remote.modes[config_path] == 0o600
    assert remote.modes[f"{ctx.plan.server.install_dir}/start-cloud.env"] == 0o600
    assert ctx.secrets.jwt_signing_key and ctx.secrets.jwt_signing_key in ctx.redactor
    assert "JAR_NAME" not in remote.files[f"{ctx.plan.server.install_dir}/start-cloud.env"]


def test_config_step_keeps_an_existing_jwt_key(ctx: Context) -> None:
    ctx.discovered.existing_config = {"jwt-signing-key": "k" * 44}
    ConfigStep().resolve_secrets(ctx)
    assert ctx.secrets.jwt_kept and ctx.secrets.jwt_signing_key == "k" * 44
    ctx.secrets.jwt_signing_key = ""
    ctx.plan.app.jwt_rotate = True
    ConfigStep().resolve_secrets(ctx)
    assert not ctx.secrets.jwt_kept and ctx.secrets.jwt_signing_key != "k" * 44


def test_crontab_region_is_replaced_not_duplicated() -> None:
    existing = f"0 5 * * * /usr/bin/other\n{CRON_BEGIN}\n@reboot old\n{CRON_END}\n"
    updated = render_crontab(existing, ["@reboot new", "17 * * * * sweep"])
    assert "/usr/bin/other" in updated and "@reboot old" not in updated and "@reboot new" in updated
    assert updated.count(CRON_BEGIN) == 1
    assert render_crontab(updated, []) .strip() == "0 5 * * * /usr/bin/other"


def test_logrotate_stanza_keeps_the_log_root_only() -> None:
    assert "create 0600 root root" in render_logrotate("/var/log/cloud-driver/cloud.log")


def test_application_cron_targets_the_backup_bucket(plan: InstallPlan) -> None:
    plan.aws.s3_bucket = "content-bucket"
    plan.app.backup_offsite = True
    lines = ApplicationStep().cron_lines(plan)
    assert any("s3://content-bucket-backups/" in line for line in lines)
    assert not any("s3://content-bucket/" in line for line in lines)
    assert any(line.startswith("@reboot") for line in lines)


def test_application_refuses_a_stale_launcher(ctx: Context) -> None:
    launcher = Path(ctx.plan.app.repo_root) / "shell" / "start-cloud.sh"
    launcher.write_text("#!/usr/bin/env bash\nJAR_NAME=cloud-driver-bootstrap-1.0.7.jar\n")
    with pytest.raises(StepError, match="update your checkout"):
        ApplicationStep()._check_launcher_source(ctx)


def test_application_requires_the_core_extensions_to_be_built(ctx: Context) -> None:
    jar = Path(ctx.plan.app.repo_root) / "cloud-driver-extensions" / "cloud-driver-extensions-rest" / "target" / "cloud-driver-extensions-rest-1.0.7.jar"
    jar.unlink()
    with pytest.raises(StepError, match="rest"):
        ApplicationStep().upload_set(ctx)


def test_application_upload_set_skips_features_that_are_off(ctx: Context) -> None:
    ctx.plan.clamav.enabled = False
    ctx.plan.intelligence.enabled = False
    names = {path.name for path in ApplicationStep().upload_set(ctx)}
    assert any(name.startswith("cloud-driver-bootstrap-") for name in names)
    assert not any("scan" in name or "intelligence" in name for name in names)


def test_application_prune_removes_an_extension_jar_from_an_older_build(ctx: Context) -> None:
    """A jar built against another bootstrap version goes, even with no replacement in this run."""
    uploads = ApplicationStep().upload_set(ctx)
    ctx.remote.ok(
        "ls -1",
        f"{ctx.plan.server.install_dir}/cloud-driver-bootstrap-1.0.7.jar\n"
        f"{ctx.plan.extensions_dir}/cloud-driver-extensions-scan-1.0.6.jar\n",
    )
    ctx.remote.ok("rm -f")
    ApplicationStep()._prune(ctx, uploads)
    assert ctx.remote.ran(f"rm -f {ctx.plan.extensions_dir}/cloud-driver-extensions-scan-1.0.6.jar")


def test_application_prune_keeps_a_current_version_jar_it_did_not_upload(ctx: Context) -> None:
    """Same version as the bootstrap jar, so it can load - a hand-deployed one stays."""
    ctx.plan.clamav.enabled = False
    uploads = ApplicationStep().upload_set(ctx)
    ctx.remote.ok(
        "ls -1",
        f"{ctx.plan.server.install_dir}/cloud-driver-bootstrap-1.0.7.jar\n"
        f"{ctx.plan.extensions_dir}/cloud-driver-extensions-scan-1.0.7.jar\n",
    )
    ctx.remote.ok("rm -f")
    ApplicationStep()._prune(ctx, uploads)
    assert not ctx.remote.ran("cloud-driver-extensions-scan-1.0.7.jar")


def test_application_prune_still_removes_a_superseded_same_stem_jar(ctx: Context) -> None:
    """The original behaviour, pinned: an older version of a module this run does upload goes."""
    uploads = ApplicationStep().upload_set(ctx)
    ctx.remote.ok(
        "ls -1",
        f"{ctx.plan.server.install_dir}/cloud-driver-bootstrap-1.0.6.jar\n"
        f"{ctx.plan.extensions_dir}/cloud-driver-extensions-rest-1.0.6.jar\n",
    )
    ctx.remote.ok("rm -f")
    ApplicationStep()._prune(ctx, uploads)
    assert ctx.remote.ran(f"rm -f {ctx.plan.extensions_dir}/cloud-driver-extensions-rest-1.0.6.jar")
    assert ctx.remote.ran(f"rm -f {ctx.plan.server.install_dir}/cloud-driver-bootstrap-1.0.6.jar")


def test_intelligence_env_merge_never_drops_the_encryption_key() -> None:
    existing = "CLOUD_DRIVER_INTELLIGENCE_SECRET=old\nCLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY=keep-me\nOTHER=1\n"
    merged = merge_env(existing, {"CLOUD_DRIVER_INTELLIGENCE_SECRET": "new"}, ("CLOUD_DRIVER_INTELLIGENCE_OCR",))
    assert "CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY=keep-me" in merged
    assert "CLOUD_DRIVER_INTELLIGENCE_SECRET=new" in merged and "old" not in merged and "OTHER=1" in merged


def test_intelligence_port_drop_in_overrides_the_unit() -> None:
    dropin = render_port_dropin(8700)
    assert dropin.count("ExecStart=") == 2 and "--port 8700" in dropin


def test_intelligence_source_tar_leaves_build_artefacts_out(tmp_path: Path) -> None:
    source = tmp_path / "module"
    (source / "src" / "pkg" / "__pycache__").mkdir(parents=True)
    (source / "src" / "pkg" / "app.py").write_text("x = 1\n")
    (source / "src" / "pkg" / "__pycache__" / "app.pyc").write_text("junk")
    (source / "tests").mkdir()
    (source / "tests" / "test_app.py").write_text("y = 2\n")
    (source / "pyproject.toml").write_text("[project]\n")
    import tarfile

    archive = build_source_tar(source, ["src", "tests", "pyproject.toml"], tmp_path / "out.tar.gz")
    with tarfile.open(archive) as handle:
        names = handle.getnames()
    assert "src/pkg/app.py" in names and "pyproject.toml" in names
    assert not any("__pycache__" in name for name in names)

    trimmed = build_source_tar(source, ["src", "tests"], tmp_path / "out2.tar.gz", skip_tests=True)
    with tarfile.open(trimmed) as handle:
        assert not any(name.startswith("tests") for name in handle.getnames())

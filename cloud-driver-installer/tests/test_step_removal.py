"""Every step's ``remove``: it deletes what that step installed, and nothing beyond it.

Removal is the one operation with no undo, so each test states the two halves explicitly: what
must be gone afterwards, and what must still be there.
"""

from __future__ import annotations

import pytest

from cloud_driver_installer.engine import Context, StepStatus
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.steps import all_steps
from cloud_driver_installer.steps.application import CRON_BEGIN, CRON_END, ApplicationStep, ConfigStep
from cloud_driver_installer.steps.awsresources import CREDENTIALS_FILE, AwsStep, EmailStep
from cloud_driver_installer.steps.daemons import CADDYFILE, CaddyStep, ClamAvStep, render_site_block
from cloud_driver_installer.steps.datastores import PostgresStep, RedisStep
from cloud_driver_installer.steps.intelligence import ENV_FILE, REMOTE_DIR, UNIT_PATH, IntelligenceStep
from cloud_driver_installer.steps.system import PURGEABLE_BASE_PACKAGES, FirewallStep, JavaStep, PackagesStep, PythonStep, SwapStep

from fake_remote import FakeRemote


def installed(remote: FakeRemote, *packages: str) -> None:
    """Make dpkg report these packages as installed."""
    for package in packages:
        remote.on(f"dpkg-query -W -f='${{Status}}' {package}", (0, "install ok installed"))


# --- the catalog ---------------------------------------------------------------------------------


def test_every_step_that_installs_something_can_remove_it_again() -> None:
    """Only the smoke test is irremovable - it probes, it installs nothing."""
    not_removable = {step.id for step in all_steps() if not step.removable}
    assert not_removable == {"smoke"}


def test_every_removal_describes_itself(plan: InstallPlan) -> None:
    """The confirmation dialog is only as good as this sentence, so every step must write one."""
    for step in all_steps():
        if not step.removable:
            continue
        text = step.describe_removal(plan)
        assert len(text) > 40 and step.describe_removal(plan) == text, step.id


def test_an_irremovable_step_says_so_instead_of_pretending(ctx: Context) -> None:
    """``remove_one`` refuses rather than silently doing nothing."""
    from cloud_driver_installer.engine import Runner

    runner = Runner(all_steps(), lambda event: None)
    assert runner.remove_one(ctx, runner.by_id["smoke"]) is False


# --- data stores ----------------------------------------------------------------------------------


def test_postgres_removal_purges_the_server_and_its_data(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    installed(remote, "postgresql", "postgresql-contrib")
    ctx.plan.postgres.mode = "install"
    remote.files[f"{ctx.plan.config_dir}/postgres-database.json"] = "{}"
    PostgresStep().remove(ctx)
    assert "postgresql" in remote.apt_purged
    assert "/var/lib/postgresql" in remote.deleted and "/etc/postgresql" in remote.deleted
    assert f"{ctx.plan.config_dir}/postgres-database.json" not in remote.files
    assert ctx.secrets.pg_password == ""


def test_postgres_removal_of_an_external_server_only_drops_the_database(ctx: Context, remote: FakeRemote) -> None:
    """Someone else's server: drop this deployment's database, never purge packages or the role."""
    remote.default_ok = True
    ctx.plan.postgres.mode = "external"
    ctx.plan.postgres.host = "db.example.com"
    ctx.secrets.pg_password = "secret"
    PostgresStep().remove(ctx)
    assert remote.apt_purged == []
    assert any("dropdb" in command and ctx.plan.postgres.database in command for command in remote.commands)
    assert any("role" in message and "left on the external server" in message for _level, message in ctx.captured_log)


def test_redis_removal_takes_the_cache_but_says_nothing_authoritative_is_lost(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    installed(remote, "redis-server")
    RedisStep().remove(ctx)
    assert "redis-server" in remote.apt_purged
    assert "/var/lib/redis" in remote.deleted
    assert "nothing authoritative" in RedisStep().describe_removal(ctx.plan)


# --- daemons --------------------------------------------------------------------------------------


def test_clamav_removal_deletes_the_signature_database(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    installed(remote, "clamav-daemon", "clamav-freshclam")
    ClamAvStep().remove(ctx)
    assert "clamav-daemon" in remote.apt_purged
    assert "/var/lib/clamav" in remote.deleted
    assert "/etc/systemd/system/clamav-daemon.socket.d" in remote.deleted


def test_caddy_removal_keeps_a_caddyfile_that_still_serves_the_homepage(ctx: Context, remote: FakeRemote) -> None:
    """The apex block deploy-homepage.sh maintains must survive removing the API site."""
    remote.default_ok = True
    installed(remote, "caddy")
    ctx.plan.proxy.api_domain = "api.example.com"
    remote.files[CADDYFILE] = "cloud-driver.de {\n\troot * /var/www\n}\n\n" + render_site_block("api.example.com", 8080)
    CaddyStep().remove(ctx)
    written = remote.files[CADDYFILE]
    assert "cloud-driver.de {" in written and "api.example.com" not in written
    assert remote.apt_purged == [], "Caddy still serves another site, so the package stays"


def test_caddy_removal_purges_when_nothing_else_is_served(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    installed(remote, "caddy")
    ctx.plan.proxy.api_domain = "api.example.com"
    remote.files[CADDYFILE] = render_site_block("api.example.com", 8080)
    CaddyStep().remove(ctx)
    assert "caddy" in remote.apt_purged
    assert "/etc/caddy" in remote.deleted and "/var/lib/caddy" in remote.deleted


# --- AWS and e-mail --------------------------------------------------------------------------------


def test_aws_removal_deletes_the_credentials_but_never_the_key_or_the_bucket(ctx: Context, remote: FakeRemote) -> None:
    """Deleting the KMS key would make every stored row unreadable - that stays a console decision."""
    remote.default_ok = True
    AwsStep().remove(ctx)
    assert CREDENTIALS_FILE in remote.deleted
    logged = " ".join(message for _level, message in ctx.captured_log)
    assert "still exists" in logged and "encrypted under it" in logged
    assert ctx.secrets.server_secret_access_key == ""
    described = AwsStep().describe_removal(ctx.plan)
    assert "NOT deleted" in described and "AWS console" in described


def test_email_removal_strips_only_the_mail_keys(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    path = f"{ctx.plan.config_dir}/configuration.json"
    remote.files[path] = '{\n  "smtp-host": "mail.example.com",\n  "smtp-password": "x",\n  "aws-kms-key-id": "alias/k",\n  "rest-server-port": 8080\n}'
    ctx.plan.email.mode = "smtp"
    EmailStep().remove(ctx)
    written = remote.files[path]
    assert "smtp-host" not in written and "smtp-password" not in written
    assert "aws-kms-key-id" in written and "rest-server-port" in written


# --- application ------------------------------------------------------------------------------------


def test_config_removal_backs_each_file_up_before_deleting_it(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    for name in ("configuration.json", "postgres-database.json", "redis-database.json"):
        remote.files[f"{ctx.plan.config_dir}/{name}"] = "{}"
    ConfigStep().remove(ctx)
    assert len(remote.backups) == 3
    assert all(f"{ctx.plan.config_dir}/{name}" not in remote.files for name in ("configuration.json", "postgres-database.json"))


def test_application_removal_stops_the_jvm_and_clears_the_managed_cron_region(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    install_dir = ctx.plan.server.install_dir.rstrip("/")
    remote.on("crontab -l", (0, f"0 5 * * * /usr/bin/other\n{CRON_BEGIN}\n@reboot cd {install_dir} && ./start-cloud.sh\n{CRON_END}\n"))
    ApplicationStep().remove(ctx)
    assert any("screen -S" in command and "-X quit" in command for command in remote.commands)
    assert ctx.plan.extensions_dir in remote.deleted
    assert any("cloud-driver-bootstrap-*.jar" in command for command in remote.commands)
    written = next(value for command, value in zip(remote.commands, remote.inputs) if command.startswith("crontab -") and value)
    assert CRON_BEGIN not in written and "/usr/bin/other" in written, "only the managed region goes"


def test_intelligence_removal_takes_the_unit_and_the_tree(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    IntelligenceStep().remove(ctx)
    assert REMOTE_DIR in remote.deleted and UNIT_PATH in remote.deleted and ENV_FILE in remote.deleted
    assert ("stop", "cloud-driver-intelligence.service") in remote.systemctl_calls


# --- OS level ----------------------------------------------------------------------------------------


def test_base_package_removal_keeps_what_the_system_needs(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    installed(remote, *PURGEABLE_BASE_PACKAGES, "cron", "curl", "ca-certificates")
    PackagesStep().remove(ctx)
    assert "screen" in remote.apt_purged and "unzip" in remote.apt_purged
    assert "cron" not in remote.apt_purged and "curl" not in remote.apt_purged and "ca-certificates" not in remote.apt_purged


def test_python_removal_never_purges_python3_itself(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    installed(remote, "python3", "python3-venv", "python3-pip")
    PythonStep().remove(ctx)
    assert "python3-venv" in remote.apt_purged and "python3" not in remote.apt_purged


def test_java_removal_purges_the_jdk(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    installed(remote, "openjdk-21-jdk-headless")
    JavaStep().remove(ctx)
    assert "openjdk-21-jdk-headless" in remote.apt_purged
    assert ctx.discovered.java_version == ""


def test_firewall_removal_resets_before_purging(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    installed(remote, "ufw")
    FirewallStep().remove(ctx)
    reset = next(index for index, command in enumerate(remote.commands) if "ufw --force reset" in command)
    purge = next(index for index, command in enumerate(remote.commands) if command.startswith("apt-get purge"))
    assert reset < purge
    assert ctx.discovered.ufw_active is False


def test_swap_removal_switches_the_file_off_and_drops_the_fstab_line(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.files["/swapfile"] = ""
    SwapStep().remove(ctx)
    assert any("swapoff /swapfile" in command for command in remote.commands)
    assert "/swapfile" in remote.deleted
    assert any("/etc/fstab" in command and "sed -i" in command for command in remote.commands)


# --- guard rails ---------------------------------------------------------------------------------------


@pytest.mark.parametrize("path", ["/", "/etc", "relative/path", "/var"])
def test_the_remote_refuses_to_delete_a_system_directory(path: str) -> None:
    """``delete`` is the sharpest tool here; it must reject anything that is not a step's own path."""
    from cloud_driver_installer.remote import RemoteError, RemoteHost

    host = RemoteHost.__new__(RemoteHost)
    with pytest.raises(RemoteError):
        RemoteHost.delete(host, path)

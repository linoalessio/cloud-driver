"""Tests for the server (preflight) step: discovery, refusals, NTP/timezone/dirs apply, ssh alias write-back."""

from __future__ import annotations

import os
import stat
from pathlib import Path
from typing import Any

import pytest

from cloud_driver_installer.engine import Context, StepError, StepStatus
from cloud_driver_installer.ssh import SshTarget
from cloud_driver_installer.steps.python import VENV_PROBE_COMMAND
from cloud_driver_installer.steps.server import (
    INTELLIGENCE_ENV_FILE,
    INTELLIGENCE_UVICORN,
    AWS_CREDENTIALS_FILE,
    ServerStep,
    parse_df_avail_gib,
    parse_free,
    parse_ipv6_global,
    parse_os_release,
    parse_route_source,
    render_ssh_alias_block,
    screen_session_running,
    ssh_alias_present,
)

OS_RELEASE = 'PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"\nNAME="Debian GNU/Linux"\nVERSION_ID="12"\nID=debian\n'
FREE = (
    "               total        used        free      shared  buff/cache   available\n"
    "Mem:            7884         612        5900          10        1372        7000\n"
    "Swap:           4095           0        4095\n"
)
FREE_NO_SWAP = FREE.replace("Swap:           4095           0        4095", "Swap:              0           0           0")
JAVA21 = 'openjdk version "21.0.4" 2024-07-16\nOpenJDK Runtime Environment (build 21.0.4+7-Debian-1deb12u1)\n'
SCREEN_RUNNING = "There is a screen on:\n\t1234.cloud\t(Detached)\n1 Socket in /run/screen/S-root.\n"
SCREEN_NONE = "No Sockets found in /run/screen/S-root.\n"
PG_PASSWORD = "0123456789abcdef0123456789abcdef0123456789abcdef"
REDIS_PASSWORD = "fedcba9876543210fedcba9876543210fedcba9876543210"
JWT = "c2VjcmV0LXNpZ25pbmcta2V5LWZvci10aGUtdGVzdC1zdWl0ZQ=="
INTEL_SECRET = "aW50ZWxsaWdlbmNlLXNoYXJlZC1zZWNyZXQtZm9yLXRlc3Rz"
INTEL_KEY = "ZW5jcnlwdGlvbi1rZXktZm9yLXRoZS12ZWN0b3Itc3RvcmU="


def script_box(
    remote: Any,
    *,
    ntp_synchronized: bool = False,
    ntp_enabled: bool | None = None,
    timezone: str = "Etc/UTC",
    java: bool = False,
    dirs: bool = False,
    jars: tuple[str, ...] = (),
    packages: tuple[str, ...] = (),
    screen: bool = False,
    uid: str = "0",
    free_gib: str = "38",
    apt: bool = True,
    systemd: bool = True,
) -> None:
    """Load the answers a fresh or provisioned Debian 12 box gives to the preflight probes."""
    ntp_flag = ntp_synchronized if ntp_enabled is None else ntp_enabled
    remote.files["/etc/os-release"] = OS_RELEASE
    remote.ok("uname -r", "6.1.0-25-amd64\n")
    remote.ok("nproc", "2\n")
    remote.ok("free -m", FREE if jars else FREE_NO_SWAP)
    remote.ok("df -BG", f"Avail\n  {free_gib}G\n")
    remote.ok("curl -4", "203.0.113.10\n")
    remote.ok("ip -6 addr", "    inet6 2001:db8::10/64 scope global\n")
    remote.ok("timedatectl show", f"NTPSynchronized={'yes' if ntp_synchronized else 'no'}\nNTP={'yes' if ntp_flag else 'no'}\nTimezone={timezone}\n")
    remote.ok("id -u", f"{uid}\n")
    if apt:
        remote.ok("command -v apt-get")
    if systemd:
        remote.ok("command -v systemctl")
    if java:
        remote.ok("java -version", JAVA21)
    else:
        remote.fail("java -version", "bash: java: command not found\n", code=127)
    remote.ok("python3 --version", "Python 3.11.2\n")
    remote.ok("python3 -m venv")
    if dirs:
        remote.ok("test -d")
    for package in packages:
        remote.ok(f"dpkg-query -W -f='${{Status}}' {package}", "install ok installed")
    if "ufw" in packages:
        remote.ok("ufw status", "Status: active\n")
    remote.ok("screen -list", SCREEN_RUNNING) if screen else remote.fail("screen -list", SCREEN_NONE)
    if jars:
        remote.ok("cloud-driver-bootstrap-*.jar", "".join(f"{jar}\n" for jar in jars))


def provisioned_box(remote: Any, plan: Any) -> None:
    """A box the installer already ran on: services, jars, config files, intelligence env."""
    script_box(
        remote,
        ntp_synchronized=True,
        timezone="Europe/Berlin",
        java=True,
        dirs=True,
        jars=("/home/cloud/cloud-driver-bootstrap-1.0.7.jar", "/home/cloud/extensions/cloud-driver-extensions-rest-1.0.7.jar"),
        packages=("postgresql", "redis-server", "clamav-daemon", "caddy", "ufw"),
        screen=True,
    )
    remote.files[f"{plan.config_dir}/configuration.json"] = '{"jwt-signing-key": "%s", "rest-server-port": "8080", "intelligence-shared-secret": "%s"}\n' % (JWT, INTEL_SECRET)
    remote.files[f"{plan.config_dir}/postgres-database.json"] = '{"address": "127.0.0.1", "userName": "cloud_driver", "password": "%s", "port": 5432, "database": "cloud_driver"}\n' % PG_PASSWORD
    remote.files[f"{plan.config_dir}/redis-database.json"] = '{"address": "127.0.0.1", "password": "%s", "port": 6379, "database": "0"}\n' % REDIS_PASSWORD
    remote.files[AWS_CREDENTIALS_FILE] = "[default]\naws_access_key_id = AKIA...\n"
    remote.files[INTELLIGENCE_UVICORN] = ""
    remote.files[INTELLIGENCE_ENV_FILE] = f"CLOUD_DRIVER_INTELLIGENCE_SECRET={INTEL_SECRET}\nCLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY={INTEL_KEY}\n"


def log_text(ctx: Context) -> str:
    return "\n".join(message for _, message in ctx.captured_log)  # type: ignore[attr-defined]


# --- check -----------------------------------------------------------------------------------------


def test_check_bare_box_needs_apply_and_fills_discovered(ctx: Context, remote: Any) -> None:
    script_box(remote)
    result = ServerStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "enable NTP time sync" in result.detail
    assert "create /home/cloud" in result.detail
    assert "fresh box" in result.detail
    assert "Debian GNU/Linux 12 (bookworm)" in result.detail
    d = ctx.discovered
    assert d.os_pretty == "Debian GNU/Linux 12 (bookworm)"
    assert d.os_id == "debian"
    assert d.kernel == "6.1.0-25-amd64"
    assert d.cpu_count == 2
    assert d.ram_mib == 7884
    assert d.swap_mib == 0
    assert d.disk_free_gib == 38.0
    assert d.public_ip == "203.0.113.10"
    assert d.ipv6 == "2001:db8::10"
    assert d.ntp_synchronized is False
    assert d.timezone == "Etc/UTC"
    assert d.is_root and d.has_sudo and d.has_apt and d.has_systemd
    assert d.java_version == ""
    assert d.python_version == "3.11.2"
    assert d.venv_works is True
    assert d.pg_installed is False and d.redis_installed is False and d.clamd_installed is False and d.caddy_installed is False
    assert d.ufw_active is None
    assert d.screen_running is False
    assert d.existing_jars == []
    assert d.existing_config == {} and d.existing_postgres == {} and d.existing_redis == {}
    assert d.aws_credentials_present is False and d.intelligence_installed is False
    assert VENV_PROBE_COMMAND in remote.commands


def test_check_is_read_only(ctx: Context, remote: Any) -> None:
    script_box(remote)
    ServerStep().check(ctx)
    assert remote.apt_installed == []
    assert remote.uploads == []
    assert not remote.ran("mkdir -p")
    assert not remote.ran("timedatectl set-")


def test_check_provisioned_box_is_ok_and_remembers_secrets(ctx: Context, remote: Any, plan: Any) -> None:
    provisioned_box(remote, plan)
    result = ServerStep().check(ctx)
    assert result.status is StepStatus.OK
    assert "already provisioned (jars: cloud-driver-bootstrap-1.0.7.jar, cloud-driver-extensions-rest-1.0.7.jar)" in result.detail
    d = ctx.discovered
    assert d.java_version == "21.0.4"
    assert d.pg_installed and d.redis_installed and d.clamd_installed and d.caddy_installed
    assert d.ufw_active is True
    assert d.screen_running is True
    assert d.swap_mib == 4095
    assert d.existing_postgres["password"] == PG_PASSWORD
    assert d.existing_config["jwt-signing-key"] == JWT
    assert d.aws_credentials_present and d.intelligence_installed
    assert d.intelligence_env["CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY"] == INTEL_KEY
    for secret in (PG_PASSWORD, REDIS_PASSWORD, JWT, INTEL_SECRET, INTEL_KEY):
        assert secret in ctx.redactor
        assert secret not in log_text(ctx)
        assert all(secret not in command for command in remote.commands)


def test_check_refuses_non_apt_host(ctx: Context, remote: Any) -> None:
    script_box(remote, apt=False)
    with pytest.raises(StepError, match="apt-get"):
        ServerStep().check(ctx)


def test_check_refuses_host_without_systemd(ctx: Context, remote: Any) -> None:
    script_box(remote, systemd=False)
    with pytest.raises(StepError, match="systemd"):
        ServerStep().check(ctx)


def test_check_refuses_non_root_without_sudo(ctx: Context, remote: Any) -> None:
    script_box(remote, uid="1000")
    with pytest.raises(StepError, match="sudo"):
        ServerStep().check(ctx)


def test_check_accepts_non_root_with_passwordless_sudo(ctx: Context, remote: Any) -> None:
    remote.ok("sudo -n true")
    script_box(remote, uid="1000")
    ServerStep().check(ctx)
    assert ctx.discovered.is_root is False
    assert ctx.discovered.has_sudo is True


def test_check_warns_on_low_disk(ctx: Context, remote: Any) -> None:
    script_box(remote, free_gib="5")
    ServerStep().check(ctx)
    assert any(level == "WARN" and "5 GiB free" in message for level, message in ctx.captured_log)  # type: ignore[attr-defined]


def test_check_low_disk_threshold_rises_with_intelligence(ctx: Context, remote: Any, plan: Any) -> None:
    script_box(remote, free_gib="12")
    plan.intelligence.enabled = True
    ServerStep().check(ctx)
    assert any(level == "WARN" and "15 GiB recommended" in message for level, message in ctx.captured_log)  # type: ignore[attr-defined]


def test_check_suggests_jvm_xmx_only_when_blank(ctx: Context, remote: Any, plan: Any) -> None:
    script_box(remote)
    plan.app.jvm_xmx = ""
    ServerStep().check(ctx)
    assert plan.app.jvm_xmx == "5g"  # 7884 MiB - 1 GiB JVM - 1 GiB Postgres - 1 GiB clamd, rounded
    plan.app.jvm_xmx = "3g"
    ServerStep().check(ctx)
    assert plan.app.jvm_xmx == "3g"


def test_check_needs_apply_for_timezone_change(ctx: Context, remote: Any, plan: Any) -> None:
    provisioned_box(remote, plan)
    plan.server.timezone = "Europe/Paris"
    result = ServerStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "set timezone Europe/Paris" in result.detail


def test_check_ok_when_ntp_enabled_but_not_yet_synchronized(ctx: Context, remote: Any, plan: Any) -> None:
    script_box(remote, ntp_synchronized=False, ntp_enabled=True, dirs=True)
    result = ServerStep().check(ctx)
    assert result.status is StepStatus.OK


def test_check_needs_apply_for_unattended_upgrades(ctx: Context, remote: Any, plan: Any) -> None:
    provisioned_box(remote, plan)
    plan.server.unattended_upgrades = True
    result = ServerStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "install unattended-upgrades" in result.detail


def test_check_survives_missing_probes(ctx: Context, remote: Any) -> None:
    """Only apt/systemd/root are hard requirements; every other probe may fail and leaves a blank."""
    remote.ok("id -u", "0\n")
    remote.ok("command -v apt-get")
    remote.ok("command -v systemctl")
    result = ServerStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert ctx.discovered.os_pretty == ""
    assert ctx.discovered.public_ip == ""
    assert ctx.discovered.ntp_synchronized is None


# --- apply -----------------------------------------------------------------------------------------


def test_apply_bare_box_enables_ntp_and_creates_dirs(ctx: Context, remote: Any) -> None:
    script_box(remote)
    remote.ok("timedatectl set-ntp")
    ServerStep().apply(ctx)
    assert remote.apt_installed == ["systemd-timesyncd"]
    assert remote.ran("timedatectl set-ntp true")
    assert remote.ran("mkdir -p /home/cloud /home/cloud/cloud-driver /home/cloud/extensions /home/cloud/upload-scratch")
    assert not remote.ran("timedatectl set-timezone")
    assert not remote.ran("dpkg-reconfigure")


def test_apply_skips_timesyncd_install_when_an_ntp_service_runs(ctx: Context, remote: Any) -> None:
    remote.ok("systemctl is-active --quiet chrony")
    script_box(remote)
    remote.ok("timedatectl set-ntp")
    ServerStep().apply(ctx)
    assert remote.apt_installed == []
    assert remote.ran("timedatectl set-ntp true")


def test_apply_sets_timezone_when_different(ctx: Context, remote: Any, plan: Any) -> None:
    script_box(remote, ntp_synchronized=True, timezone="Etc/UTC", dirs=True)
    remote.ok("timedatectl set-timezone")
    plan.server.timezone = "Europe/Berlin"
    ServerStep().apply(ctx)
    assert remote.ran("timedatectl set-timezone Europe/Berlin")


def test_apply_timezone_failure_is_a_step_error(ctx: Context, remote: Any, plan: Any) -> None:
    script_box(remote, ntp_synchronized=True, dirs=True)
    remote.fail("timedatectl set-timezone", "Failed to set time zone: Invalid time zone 'Mars/Olympus'")
    plan.server.timezone = "Mars/Olympus"
    with pytest.raises(StepError, match="server setup failed"):
        ServerStep().apply(ctx)


def test_apply_installs_unattended_upgrades_when_requested(ctx: Context, remote: Any, plan: Any) -> None:
    script_box(remote, ntp_synchronized=True, dirs=True)
    remote.ok("dpkg-reconfigure")
    plan.server.unattended_upgrades = True
    ServerStep().apply(ctx)
    assert remote.apt_installed == ["unattended-upgrades"]
    assert remote.ran("dpkg-reconfigure -f noninteractive unattended-upgrades")
    assert any("JVM is restarted" in message for level, message in ctx.captured_log if level == "WARN")  # type: ignore[attr-defined]


def test_apply_is_idempotent_on_provisioned_box(ctx: Context, remote: Any, plan: Any) -> None:
    provisioned_box(remote, plan)
    plan.server.timezone = "Europe/Berlin"
    plan.server.unattended_upgrades = True
    remote.ok("dpkg-query -W -f='${Status}' unattended-upgrades", "install ok installed")
    remote.files["/etc/apt/apt.conf.d/20auto-upgrades"] = 'APT::Periodic::Update-Package-Lists "1";\nAPT::Periodic::Unattended-Upgrade "1";\n'
    ServerStep().apply(ctx)
    ServerStep().apply(ctx)
    assert remote.apt_installed == []
    assert not remote.ran("timedatectl set-")
    assert not remote.ran("dpkg-reconfigure")
    assert remote.count("mkdir -p") == 2  # mkdir -p is the idempotent no-op it is


# --- ssh alias write-back (local) --------------------------------------------------------------------


@pytest.fixture
def home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    fake_home = tmp_path / "home"
    fake_home.mkdir()
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: fake_home))
    return fake_home


def alias_plan(plan: Any) -> None:
    plan.server.write_ssh_alias = True
    plan.server.ssh_alias_name = "cloud_driver"
    plan.ssh = SshTarget(host="203.0.113.10", port=2222, user="root", auth="key", key_path="/Users/op/.ssh/id_ed25519_cloud")


def test_ssh_alias_is_written_once_with_0600(ctx: Context, remote: Any, plan: Any, home: Path) -> None:
    script_box(remote, ntp_synchronized=True, dirs=True)
    alias_plan(plan)
    assert "add ssh alias cloud_driver" in ServerStep().check(ctx).detail
    ServerStep().apply(ctx)
    config = home / ".ssh" / "config"
    text = config.read_text()
    assert text == "Host cloud_driver\n    HostName 203.0.113.10\n    User root\n    Port 2222\n    IdentityFile /Users/op/.ssh/id_ed25519_cloud\n"
    assert stat.S_IMODE(os.stat(config).st_mode) == 0o600
    assert stat.S_IMODE(os.stat(config.parent).st_mode) == 0o700
    ServerStep().apply(ctx)
    assert config.read_text().count("Host cloud_driver") == 1
    assert ServerStep().check(ctx).status is StepStatus.OK
    assert ServerStep().verify(ctx).ok


def test_ssh_alias_existing_block_is_never_modified(ctx: Context, remote: Any, plan: Any, home: Path) -> None:
    script_box(remote, ntp_synchronized=True, dirs=True)
    alias_plan(plan)
    (home / ".ssh").mkdir()
    original = "Host cloud_driver\n  HostName 198.51.100.7\n  User admin\n"
    (home / ".ssh" / "config").write_text(original)
    assert ServerStep().check(ctx).status is StepStatus.OK
    ServerStep().apply(ctx)
    assert (home / ".ssh" / "config").read_text() == original


def test_ssh_alias_is_appended_after_existing_entries(ctx: Context, remote: Any, plan: Any, home: Path) -> None:
    script_box(remote, ntp_synchronized=True, dirs=True)
    alias_plan(plan)
    plan.ssh = SshTarget(host="203.0.113.10", port=22, user="root", auth="agent")
    (home / ".ssh").mkdir()
    (home / ".ssh" / "config").write_text("Host other\n  HostName 198.51.100.7")  # no trailing newline
    ServerStep().apply(ctx)
    text = (home / ".ssh" / "config").read_text()
    assert text == "Host other\n  HostName 198.51.100.7\n\nHost cloud_driver\n    HostName 203.0.113.10\n    User root\n"


def test_ssh_alias_not_written_unless_opted_in(ctx: Context, remote: Any, plan: Any, home: Path) -> None:
    script_box(remote, ntp_synchronized=True, dirs=True)
    plan.server.write_ssh_alias = False
    ServerStep().apply(ctx)
    assert not (home / ".ssh" / "config").exists()


# --- verify ----------------------------------------------------------------------------------------


def test_verify_ok_when_synchronized(ctx: Context, remote: Any) -> None:
    remote.ok("test -d")
    remote.ok("timedatectl show", "NTPSynchronized=yes\nNTP=yes\nTimezone=Etc/UTC\n")
    result = ServerStep().verify(ctx)
    assert result.ok
    assert "NTP synchronized" in result.detail


def test_verify_accepts_enabled_but_pending_ntp(ctx: Context, remote: Any) -> None:
    remote.ok("test -d")
    remote.ok("timedatectl show", "NTPSynchronized=no\nNTP=yes\nTimezone=Etc/UTC\n")
    result = ServerStep().verify(ctx)
    assert result.ok
    assert "pending" in result.detail


def test_verify_fails_when_ntp_not_accepted(ctx: Context, remote: Any) -> None:
    remote.ok("test -d")
    remote.ok("timedatectl show", "NTPSynchronized=no\nNTP=no\nTimezone=Etc/UTC\n")
    assert not ServerStep().verify(ctx).ok


def test_verify_fails_when_dirs_missing(ctx: Context, remote: Any) -> None:
    remote.ok("timedatectl show", "NTPSynchronized=yes\nNTP=yes\nTimezone=Etc/UTC\n")
    result = ServerStep().verify(ctx)
    assert not result.ok
    assert "/home/cloud" in result.detail


def test_verify_checks_timezone(ctx: Context, remote: Any, plan: Any) -> None:
    remote.ok("test -d")
    remote.ok("timedatectl show", "NTPSynchronized=yes\nNTP=yes\nTimezone=Etc/UTC\n")
    plan.server.timezone = "Europe/Berlin"
    assert not ServerStep().verify(ctx).ok
    plan.server.timezone = "Etc/UTC"
    assert ServerStep().verify(ctx).ok


def test_verify_without_ntp_only_needs_dirs(ctx: Context, remote: Any, plan: Any) -> None:
    plan.server.ntp = False
    remote.ok("test -d")
    assert ServerStep().verify(ctx).ok
    assert not remote.ran("timedatectl")


# --- describe & parsers ------------------------------------------------------------------------------


def test_describe_mirrors_the_plan(plan: Any) -> None:
    plan.server.timezone = "Europe/Berlin"
    plan.server.unattended_upgrades = True
    plan.server.write_ssh_alias = True
    line = ServerStep().describe(plan)
    assert "\n" not in line
    assert "NTP" in line and "Europe/Berlin" in line and "/home/cloud" in line
    assert "unattended-upgrades" in line and "cloud_driver" in line
    plan.server.ntp = False
    assert "NTP" not in ServerStep().describe(plan)


def test_parsers() -> None:
    assert parse_os_release(OS_RELEASE) == {"PRETTY_NAME": "Debian GNU/Linux 12 (bookworm)", "NAME": "Debian GNU/Linux", "VERSION_ID": "12", "ID": "debian"}
    assert parse_free(FREE) == (7884, 4095)
    assert parse_free("") == (0, 0)
    assert parse_df_avail_gib("Avail\n  38G\n") == 38.0
    assert parse_df_avail_gib("") == 0.0
    assert parse_route_source("1.1.1.1 via 203.0.113.1 dev eth0 src 203.0.113.10 uid 0\n") == "203.0.113.10"
    assert parse_route_source("garbage") == ""
    assert parse_ipv6_global("2: eth0: <UP>\n    inet6 2001:db8::10/64 scope global\n") == "2001:db8::10"
    assert screen_session_running(SCREEN_RUNNING, "cloud")
    assert not screen_session_running(SCREEN_RUNNING, "clou")
    assert not screen_session_running("\t1234.cloud2\t(Detached)\n", "cloud")
    assert not screen_session_running(SCREEN_NONE, "cloud")


def test_ssh_alias_present_variants() -> None:
    assert ssh_alias_present("Host cloud_driver\n  HostName x\n", "cloud_driver")
    assert ssh_alias_present("host cloud_driver other\n", "cloud_driver")
    assert ssh_alias_present("Host=cloud_driver\n", "cloud_driver")
    assert not ssh_alias_present("Host cloud_driver2\n", "cloud_driver")
    assert not ssh_alias_present("# Host cloud_driver\nMatch host cloud_driver\n", "cloud_driver")
    assert not ssh_alias_present("", "cloud_driver")


def test_render_ssh_alias_block_variants() -> None:
    assert render_ssh_alias_block("box", SshTarget(host="h", port=22, user="root", auth="password")) == "Host box\n    HostName h\n    User root\n"
    assert render_ssh_alias_block("box", SshTarget(host="h", port=22, user="", auth="agent")) == "Host box\n    HostName h\n    User root\n"
    assert "IdentityFile /k" in render_ssh_alias_block("box", SshTarget(host="h", auth="key", key_path="/k"))
    assert "Port 2222" in render_ssh_alias_block("box", SshTarget(host="h", port=2222, auth="key", key_path=""))
    assert "IdentityFile" not in render_ssh_alias_block("box", SshTarget(host="h", port=2222, auth="key", key_path=""))

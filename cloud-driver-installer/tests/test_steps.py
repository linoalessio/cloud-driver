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
    render_crontab,
    render_logrotate,
)
from cloud_driver_installer.steps.daemons import CaddyStep, ClamAvStep, ensure_global_block, render_clamd_dropin, render_site_block, replace_site_block, rewrite_clamd_conf
from cloud_driver_installer.steps.datastores import PostgresStep, RedisStep, resolve_password, rewrite_redis_conf
from cloud_driver_installer.steps.intelligence import build_source_tar, merge_env, render_port_dropin
from cloud_driver_installer.steps.system import BASE_PACKAGES, FirewallStep, JavaStep, PackagesStep, ServerStep

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


def test_redis_conf_is_rewritten_idempotently() -> None:
    config = "port 6379\nbind 0.0.0.0 ::1\n# requirepass foo\nsave 900 1\n"
    once = rewrite_redis_conf(config, "hexpassword")
    assert "bind 127.0.0.1 -::1" in once and "requirepass hexpassword" in once and "save 900 1" in once
    assert rewrite_redis_conf(once, "hexpassword") == once


def test_redis_apply_writes_credentials_without_leaking_them(ctx: Context, remote: FakeRemote) -> None:
    remote.default_ok = True
    remote.ok("dpkg-query", "install ok installed")
    remote.files["/etc/redis/redis.conf"] = "bind 0.0.0.0\n"
    RedisStep().apply(ctx)
    path = f"{ctx.plan.config_dir}/redis-database.json"
    assert remote.modes[path] == 0o600 and ctx.secrets.redis_password in remote.files[path]
    assert no_secret_in_commands(ctx, remote, ctx.secrets.redis_password)


# --- daemons ------------------------------------------------------------------------------------


def test_clamd_drop_in_has_exactly_one_ipv4_listener() -> None:
    dropin = render_clamd_dropin("127.0.0.1", 3310)
    assert dropin.count("ListenStream=") == 1 and "[::1]" not in dropin


def test_clamd_limits_replace_existing_lines_once() -> None:
    config = "StreamMaxLength 25M\nLogFile /var/log/clamav/clamav.log\nStreamMaxLength 30M\n"
    rewritten = rewrite_clamd_conf(config, {"StreamMaxLength": "128M", "MaxFileSize": "128M"})
    assert rewritten.count("StreamMaxLength") == 1 and "StreamMaxLength 128M" in rewritten
    assert "MaxFileSize 128M" in rewritten and "LogFile" in rewritten


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

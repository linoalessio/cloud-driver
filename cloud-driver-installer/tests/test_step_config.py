"""ConfigStep: rendering against the server's files, keep/rotate rules, 0600 modes, local write-back."""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from cloud_driver_installer.config_files import parse_json, render_start_env, to_json
from cloud_driver_installer.engine import Context, StepError, StepStatus
from cloud_driver_installer.steps.config import INTELLIGENCE_ENV_PATH, ConfigStep

from .fake_remote import FakeRemote

PG_PASSWORD = "0123456789abcdef0123456789abcdef0123456789abcdef"
REDIS_PASSWORD = "fedcba9876543210fedcba9876543210fedcba9876543210"
EXISTING_JWT = "ExistingJwtKeyValue0123456789ABCDEFGHIJKLMNO="

CONFIG_JSON = "/home/cloud/cloud-driver/configuration.json"
POSTGRES_JSON = "/home/cloud/cloud-driver/postgres-database.json"
REDIS_JSON = "/home/cloud/cloud-driver/redis-database.json"
START_ENV = "/home/cloud/start-cloud.env"


@pytest.fixture
def step() -> ConfigStep:
    return ConfigStep()


@pytest.fixture
def ready(ctx: Context) -> Context:
    """A context whose postgres/redis steps already resolved their passwords."""
    ctx.secrets.pg_password = PG_PASSWORD
    ctx.secrets.redis_password = REDIS_PASSWORD
    return ctx


def _assert_secret_hidden(ctx: Context, remote: FakeRemote, *values: str) -> None:
    for value in values:
        assert value, "test must pass a real value"
        assert all(value not in message for _, message in ctx.captured_log), f"{value[:6]}… leaked into the log"  # type: ignore[attr-defined]
        assert all(value not in command for command in remote.commands), f"{value[:6]}… leaked into a command line"


# --- check -----------------------------------------------------------------------------------------


def test_check_needs_apply_on_bare_box_and_is_read_only(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    result = step.check(ready)
    assert result.status is StepStatus.NEEDS_APPLY
    for label in ("configuration.json (new)", "postgres-database.json (new)", "redis-database.json (new)", "start-cloud.env (new)"):
        assert label in result.detail
    assert "JWT key generated" in result.detail
    assert ready.secrets.jwt_signing_key and not ready.secrets.jwt_kept
    assert remote.files == {} and remote.uploads == []
    _assert_secret_hidden(ready, remote, ready.secrets.jwt_signing_key, PG_PASSWORD, REDIS_PASSWORD)


def test_check_ok_on_provisioned_box(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    step.apply(ready)
    result = step.check(ready)
    assert result.status is StepStatus.OK, result.detail
    assert "up to date" in result.detail and "JWT key kept" in result.detail
    assert ready.secrets.jwt_kept


def test_check_generates_the_same_jwt_twice_in_one_run(ready: Context, step: ConfigStep) -> None:
    first = step.check(ready).status
    key = ready.secrets.jwt_signing_key
    step.check(ready)
    assert first is StepStatus.NEEDS_APPLY and ready.secrets.jwt_signing_key == key


# --- apply -------------------------------------------------------------------------------------------


def test_apply_writes_every_file_with_the_right_mode(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    step.apply(ready)
    assert remote.modes[CONFIG_JSON] == 0o600
    assert remote.modes[POSTGRES_JSON] == 0o600
    assert remote.modes[REDIS_JSON] == 0o600
    assert remote.modes[START_ENV] == 0o644
    config = parse_json(remote.files[CONFIG_JSON])
    assert config["jwt-signing-key"] == ready.secrets.jwt_signing_key
    assert config["rest-server-port"] == "8080"
    assert config["trust-proxy-headers"] is True
    assert config["trusted-proxy-addresses"] == "127.0.0.1,::1"
    assert config["aws-s3-bucket"] == "cloud-driver-test-bucket"
    assert "intelligence-shared-secret" not in config
    postgres = parse_json(remote.files[POSTGRES_JSON])
    assert postgres == {"address": "127.0.0.1", "userName": "cloud_driver", "password": PG_PASSWORD, "port": 5432, "database": "cloud_driver", "fileRepository": "Unknown"}
    assert parse_json(remote.files[REDIS_JSON])["password"] == REDIS_PASSWORD
    assert remote.files[START_ENV] == render_start_env(ready.plan, "cloud-driver-bootstrap-1.0.7.jar")
    assert "JVM_XMX=5g" in remote.files[START_ENV] and "JAR_NAME=cloud-driver-bootstrap-1.0.7.jar" in remote.files[START_ENV]
    assert remote.ran("mkdir -p /home/cloud/cloud-driver")
    _assert_secret_hidden(ready, remote, ready.secrets.jwt_signing_key, PG_PASSWORD, REDIS_PASSWORD)


def test_apply_twice_changes_nothing_and_rewrites_back_up(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    step.apply(ready)
    snapshot = dict(remote.files)
    step.apply(ready)
    assert remote.files == snapshot and remote.backups == []
    ready.plan.app.rest_port = 9090
    step.apply(ready)
    assert f"{CONFIG_JSON}.bak-TEST" in remote.files
    assert parse_json(remote.files[CONFIG_JSON])["rest-server-port"] == "9090"
    assert parse_json(remote.files[f"{CONFIG_JSON}.bak-TEST"])["rest-server-port"] == "8080"
    assert f"{POSTGRES_JSON}.bak-TEST" not in remote.files, "an unchanged file must not be rewritten"


def test_apply_merges_over_an_existing_configuration(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    remote.files[CONFIG_JSON] = to_json({"custom-key": "operator value", "web-panel-port": "1234", "jwt-signing-key": EXISTING_JWT, "aws-ses-region": "eu-west-1"})
    step.apply(ready)
    config = parse_json(remote.files[CONFIG_JSON])
    assert config["custom-key"] == "operator value"
    assert "web-panel-port" not in config
    assert "aws-ses-region" not in config, "a disabled feature's managed key is dropped"
    assert config["jwt-signing-key"] == EXISTING_JWT


# --- keep / rotate -----------------------------------------------------------------------------------


def test_rerun_keeps_the_existing_jwt_key(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    remote.files[CONFIG_JSON] = to_json({"jwt-signing-key": EXISTING_JWT})
    result = step.check(ready)
    assert result.status is StepStatus.NEEDS_APPLY and "JWT key kept" in result.detail
    assert ready.secrets.jwt_signing_key == EXISTING_JWT and ready.secrets.jwt_kept
    step.apply(ready)
    assert parse_json(remote.files[CONFIG_JSON])["jwt-signing-key"] == EXISTING_JWT
    _assert_secret_hidden(ready, remote, EXISTING_JWT)


def test_rotate_replaces_the_jwt_key_once(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    remote.files[CONFIG_JSON] = to_json({"jwt-signing-key": EXISTING_JWT})
    ready.plan.app.jwt_rotate = True
    result = step.check(ready)
    assert result.status is StepStatus.NEEDS_APPLY and "JWT key rotated" in result.detail
    new_key = ready.secrets.jwt_signing_key
    assert new_key != EXISTING_JWT and not ready.secrets.jwt_kept
    step.apply(ready)
    assert parse_json(remote.files[CONFIG_JSON])["jwt-signing-key"] == new_key
    assert f"{CONFIG_JSON}.bak-TEST" in remote.files
    verify = step.verify(ready)
    assert verify.ok, verify.detail
    assert step.check(ready).status is StepStatus.OK, "a second check must not rotate again"
    assert ready.secrets.jwt_signing_key == new_key
    _assert_secret_hidden(ready, remote, new_key, EXISTING_JWT)


def test_intelligence_secret_kept_from_the_env_file(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    ready.plan.intelligence.enabled = True
    env_secret = "EnvFileSharedSecretValue0123456789ABCDEFGHIJ="
    remote.files[INTELLIGENCE_ENV_PATH] = f"# service env\nCLOUD_DRIVER_INTELLIGENCE_SECRET={env_secret}\nCLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY=k\n"
    step.apply(ready)
    assert ready.secrets.intelligence_secret == env_secret and ready.secrets.intelligence_secret_kept
    assert parse_json(remote.files[CONFIG_JSON])["intelligence-shared-secret"] == env_secret
    _assert_secret_hidden(ready, remote, env_secret)


def test_intelligence_secret_configuration_wins_and_mismatch_warns(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    ready.plan.intelligence.enabled = True
    config_secret = "ConfigSharedSecretValue0123456789ABCDEFGHIJK="
    env_secret = "EnvFileSharedSecretValue0123456789ABCDEFGHIJ="
    remote.files[CONFIG_JSON] = to_json({"intelligence-shared-secret": config_secret})
    remote.files[INTELLIGENCE_ENV_PATH] = f"CLOUD_DRIVER_INTELLIGENCE_SECRET={env_secret}\n"
    step.check(ready)
    assert ready.secrets.intelligence_secret == config_secret
    assert any(level == "WARN" and "differs" in message for level, message in ready.captured_log)  # type: ignore[attr-defined]
    _assert_secret_hidden(ready, remote, config_secret, env_secret)


def test_intelligence_secret_generated_or_rotated(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    ready.plan.intelligence.enabled = True
    step.check(ready)
    generated = ready.secrets.intelligence_secret
    assert generated and not ready.secrets.intelligence_secret_kept
    step.check(ready)
    assert ready.secrets.intelligence_secret == generated
    remote.files[CONFIG_JSON] = to_json({"intelligence-shared-secret": "ConfigSharedSecretValue0123456789ABCDEFGHIJK="})
    ready.secrets.intelligence_secret = ""
    ready.plan.intelligence.secret_rotate = True
    step.check(ready)
    assert ready.secrets.intelligence_secret not in ("", "ConfigSharedSecretValue0123456789ABCDEFGHIJK=")


def test_pg_password_read_back_when_the_postgres_step_left_it_blank(ctx: Context, remote: FakeRemote, step: ConfigStep) -> None:
    ctx.plan.redis.enabled = False
    remote.files[POSTGRES_JSON] = to_json({"address": "127.0.0.1", "userName": "cloud_driver", "password": PG_PASSWORD, "port": 5432, "database": "cloud_driver", "fileRepository": "Unknown"})
    step.check(ctx)
    assert ctx.secrets.pg_password == PG_PASSWORD and ctx.secrets.pg_password_kept
    _assert_secret_hidden(ctx, remote, PG_PASSWORD)


def test_plan_password_wins_over_nothing(ctx: Context, step: ConfigStep) -> None:
    ctx.plan.redis.enabled = False
    ctx.plan.postgres.password = PG_PASSWORD
    step.check(ctx)
    assert ctx.secrets.pg_password == PG_PASSWORD and not ctx.secrets.pg_password_kept


def test_missing_pg_password_is_an_operator_readable_error(ctx: Context, step: ConfigStep) -> None:
    ctx.plan.redis.enabled = False
    with pytest.raises(StepError, match="PostgreSQL password"):
        step.check(ctx)


def test_missing_redis_password_is_an_operator_readable_error(ctx: Context, step: ConfigStep) -> None:
    ctx.secrets.pg_password = PG_PASSWORD
    with pytest.raises(StepError, match="Redis password"):
        step.check(ctx)


def test_redis_disabled_leaves_an_existing_file_alone_and_warns(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    ready.plan.redis.enabled = False
    ready.secrets.redis_password = ""
    remote.files[REDIS_JSON] = '{"password": "old"}\n'
    step.apply(ready)
    assert remote.files[REDIS_JSON] == '{"password": "old"}\n'
    assert REDIS_JSON not in remote.modes
    assert any(level == "WARN" and "redis-database.json" in message for level, message in ready.captured_log)  # type: ignore[attr-defined]
    assert "redis-database.json" not in step.describe(ready.plan)


def test_blank_jvm_xmx_is_suggested_from_ram(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    ready.plan.app.jvm_xmx = ""
    step.apply(ready)
    assert ready.plan.app.jvm_xmx == "5g"  # 7884 MiB - 1 GiB JVM - 1 GiB Postgres - 1 GiB clamd
    assert "JVM_XMX=5g" in remote.files[START_ENV]


# --- local write-back --------------------------------------------------------------------------------


def test_local_write_back_writes_0600_copies_and_backs_up(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    local_dir = Path(ready.plan.app.repo_root) / "cloud-driver"
    (local_dir / "configuration.json").write_text('{"jwt-signing-key": "local-old"}\n')
    step.apply(ready)
    for name in ("configuration.json", "postgres-database.json", "redis-database.json"):
        path = local_dir / name
        assert path.is_file(), name
        assert os.stat(path).st_mode & 0o777 == 0o600, name
        assert path.read_text() == remote.files[f"/home/cloud/cloud-driver/{name}"]
    assert not (local_dir / "start-cloud.env").exists()
    backups = list(local_dir.glob("configuration.json.bak-*"))
    assert len(backups) == 1 and backups[0].read_text() == '{"jwt-signing-key": "local-old"}\n'
    assert not list(local_dir.glob("postgres-database.json.bak-*")), "no backup without a previous file"
    step.apply(ready)
    assert len(list(local_dir.glob("configuration.json.bak-*"))) == 1, "an identical local copy is not rewritten"


def test_local_write_back_can_be_switched_off(ready: Context, step: ConfigStep) -> None:
    ready.plan.app.write_local_config = False
    step.apply(ready)
    assert not any((Path(ready.plan.app.repo_root) / "cloud-driver").iterdir())


# --- verify / describe ---------------------------------------------------------------------------------


def test_verify_reads_back_and_detects_drift(ready: Context, remote: FakeRemote, step: ConfigStep) -> None:
    step.apply(ready)
    assert step.verify(ready).ok
    remote.files[CONFIG_JSON] = to_json({"rest-server-port": "1"})
    verify = step.verify(ready)
    assert not verify.ok and "configuration.json (changed)" in verify.detail


def test_describe_mirrors_the_plan(plan) -> None:
    step = ConfigStep()
    line = step.describe(plan)
    assert "configuration.json, postgres-database.json, redis-database.json" in line
    assert "/home/cloud/cloud-driver" in line and "start-cloud.env" in line
    assert "kept when present" in line and f"{plan.app.repo_root}/cloud-driver/" in line
    plan.app.jwt_rotate = True
    plan.app.write_local_config = False
    line = step.describe(plan)
    assert "rotate the JWT signing key" in line and "copy" not in line

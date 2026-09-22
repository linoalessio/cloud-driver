"""The core modules: secrets, sizing, the plan, config rendering and profiles."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from cloud_driver_installer.config_files import (
    OBSOLETE_KEYS,
    changed_sensitive_keys,
    is_placeholder,
    parse_env_file,
    real_value,
    removed_keys,
    render_configuration,
    render_postgres_credentials,
    render_redis_credentials,
    render_start_env,
)
from cloud_driver_installer.model import Discovered, GeneratedSecrets, InstallPlan, apply_existing_config, to_dict, valid_bucket_name, valid_domain
from cloud_driver_installer.profiles import load_profile, save_profile
from cloud_driver_installer.credentials import MASK, Redactor, generate_base64, generate_hex
from cloud_driver_installer.sizing import format_bytes, heap_is_tight, parse_clamd_size, parse_size_to_bytes, parse_xmx_mib, suggest_jvm_xmx


def valid(plan: InstallPlan) -> InstallPlan:
    """A plan that passes validation."""
    plan.proxy.api_domain = "api.example.com"
    plan.aws.s3_bucket = "cloud-driver-test-bucket"
    plan.email.mode = "none"
    return plan


# --- secrets ------------------------------------------------------------------------------------


def test_generated_secrets_are_unique_and_shaped() -> None:
    assert len(generate_hex(24)) == 48 and generate_hex(24) != generate_hex(24)
    assert len(generate_base64(32)) == 44


def test_redactor_masks_longest_first_and_ignores_short_values() -> None:
    redactor = Redactor()
    redactor.add_all(["supersecret", "supersecretlonger", "ab"])
    assert redactor.redact("x supersecretlonger y") == f"x {MASK} y"
    assert redactor.redact("ab") == "ab"


# --- sizing -------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("ram", "clamav", "intelligence", "expected"),
    [(7884, True, False, "5g"), (7884, False, False, "6g"), (7884, True, True, "3g"), (2048, True, True, "2g")],
)
def test_heap_suggestion_follows_the_documented_budget(ram: int, clamav: bool, intelligence: bool, expected: str) -> None:
    assert suggest_jvm_xmx(ram, clamav_enabled=clamav, intelligence_enabled=intelligence) == expected


def test_size_helpers() -> None:
    assert parse_xmx_mib("6g") == 6144 and parse_xmx_mib("512m") == 512
    assert parse_size_to_bytes("256 GiB") == 274877906944
    assert parse_clamd_size("128M") == 134217728
    assert format_bytes(274877906944) == "256 GiB"
    assert heap_is_tight(7884, "6g", clamav_enabled=True, intelligence_enabled=True)
    with pytest.raises(ValueError):
        parse_xmx_mib("huge")


# --- the plan -----------------------------------------------------------------------------------


def test_a_default_plan_validates(plan: InstallPlan) -> None:
    assert valid(plan).validate() == []


def test_validation_catches_the_documented_mistakes(plan: InstallPlan) -> None:
    valid(plan)
    plan.app.rest_port = plan.app.metrics_port
    plan.redis.username = "someone"
    plan.aws.s3_key_prefix = "/leading"
    plan.app.user_max_bytes = plan.app.server_max_bytes * 2
    plan.clamav.stream_max_length = "10M"
    problems = " | ".join(plan.validate())
    assert "REST and metrics ports must differ" in problems
    assert "username empty" in problems
    assert "key prefix" in problems
    assert "per-user quota cannot exceed" in problems
    assert "StreamMaxLength" in problems


def test_rerun_guards_need_an_acknowledgement(plan: InstallPlan) -> None:
    valid(plan)
    discovered = Discovered(existing_config={"aws-s3-bucket": "live-bucket", "aws-kms-key-id": "alias/live-key"})
    plan.aws.s3_enabled = False
    plan.app.backup_offsite = False  # the off-site copy needs a bucket of its own
    problems = " | ".join(plan.validate(discovered))
    assert "disabling S3" in problems and "KMS key" in problems
    plan.aws.allow_disable_s3 = True
    plan.aws.allow_kms_change = True
    assert plan.validate(discovered) == []


def test_existing_server_config_is_taken_over(plan: InstallPlan) -> None:
    existing = {
        "aws-kms-key-id": "alias/live-key",
        "aws-kms-region": "eu-west-1",
        "aws-s3-bucket": "live-bucket",
        "aws-ses-from-address": "no-reply@example.com",
        "intelligence-shared-secret": "s" * 44,
        "rest-server-port": "9090",
    }
    taken = apply_existing_config(plan, existing, postgres={"address": "10.0.0.5", "port": 6000, "database": "cd", "userName": "cd"})
    assert plan.aws.kms_mode == "existing" and plan.aws.kms_key_id == "alias/live-key"
    assert plan.aws.s3_mode == "existing" and plan.aws.s3_bucket == "live-bucket"
    assert plan.email.mode == "ses" and plan.intelligence.enabled and plan.app.rest_port == 9090
    assert plan.postgres.mode == "external" and plan.postgres.host == "10.0.0.5"
    assert any("live-bucket" in line for line in taken)


def test_warnings_mention_the_client_hostname_and_a_missing_transport(plan: InstallPlan) -> None:
    valid(plan)
    warnings = " | ".join(plan.warnings())
    assert "only appear in the server log" in warnings and "api.cloud-driver.de" in warnings


def test_name_validators() -> None:
    assert valid_bucket_name("cloud-driver-content") and not valid_bucket_name("Bad_Name") and not valid_bucket_name("10.0.0.1")
    assert valid_domain("api.example.com") and not valid_domain("localhost")


# --- configuration.json --------------------------------------------------------------------------


def test_rendered_configuration_keeps_unknown_keys_and_drops_placeholders(plan: InstallPlan) -> None:
    valid(plan)
    existing = {"operator-tuned": 7, "aws-ses-region": "REPLACE-ME", OBSOLETE_KEYS[0]: "9", "jwt-signing-key": "old"}
    document = render_configuration(plan, GeneratedSecrets(jwt_signing_key="new", kms_key_id="alias/k"), existing)
    assert document["operator-tuned"] == 7
    assert OBSOLETE_KEYS[0] not in document
    assert "aws-ses-region" not in document  # e-mail is off in this plan
    assert document["jwt-signing-key"] == "new"
    assert document["trust-proxy-headers"] is True and document["trusted-proxy-addresses"] == "127.0.0.1"
    assert document["cloud-user-max-bytes-to-upload"] == str(plan.app.user_max_bytes)


def test_disabled_features_lose_their_keys(plan: InstallPlan) -> None:
    valid(plan)
    plan.clamav.enabled = False
    plan.aws.s3_enabled = False
    plan.intelligence.enabled = False
    existing = {"clamav-host": "127.0.0.1", "aws-s3-bucket": "old", "intelligence-shared-secret": "x" * 44}
    document = render_configuration(plan, GeneratedSecrets(jwt_signing_key="j"), existing)
    assert not {"clamav-host", "aws-s3-bucket", "intelligence-shared-secret"} & set(document)
    assert removed_keys(existing, document) == ["aws-s3-bucket", "clamav-host", "intelligence-shared-secret"]


def test_no_proxy_means_no_trusted_proxy_allowlist(plan: InstallPlan) -> None:
    valid(plan)
    plan.proxy.enabled = False
    document = render_configuration(plan, GeneratedSecrets(jwt_signing_key="j"))
    assert document["trust-proxy-headers"] is False and "trusted-proxy-addresses" not in document


def test_sensitive_changes_are_reported(plan: InstallPlan) -> None:
    valid(plan)
    existing = {"aws-kms-key-id": "alias/old"}
    document = render_configuration(plan, GeneratedSecrets(jwt_signing_key="j", kms_key_id="alias/new"), existing)
    assert changed_sensitive_keys(existing, document) == ["aws-kms-key-id"]


def test_credentials_files_have_the_shape_the_driver_expects(plan: InstallPlan) -> None:
    postgres = render_postgres_credentials(plan, "pw")
    assert postgres["fileRepository"] == "Unknown" and postgres["port"] == 5432 and postgres["password"] == "pw"
    assert render_redis_credentials(plan, "pw")["database"] == "0"


def test_start_env_never_pins_the_jar(plan: InstallPlan) -> None:
    plan.app.jvm_xmx = "5g"
    rendered = render_start_env(plan)
    assert "JVM_XMX=5g" in rendered and "SCREEN_SESSION=cloud" in rendered
    assert "JAR_NAME" not in rendered and "/var/log/cloud-driver/cloud.log" in rendered


def test_placeholder_helpers() -> None:
    assert is_placeholder("REPLACE-ME (bytes)") and is_placeholder("  ") and not is_placeholder("real")
    assert real_value({"a": "REPLACE-ME"}, "a") is None and real_value({"a": "x"}, "a") == "x"
    assert parse_env_file("A=1\n# c\n\nB=two") == {"A": "1", "B": "two"}


# --- profiles -----------------------------------------------------------------------------------


def test_profiles_carry_no_secret_and_no_one_shot_flag(plan: InstallPlan, tmp_path: Path) -> None:
    valid(plan)
    plan.postgres.password = "pw"
    plan.aws.secret_access_key = "secret"
    plan.postgres.rotate = True
    plan.app.jwt_rotate = True
    plan.aws.allow_kms_change = True
    plan.app.build_with_maven = True
    path = tmp_path / "profile.json"
    save_profile(plan, path)
    stored = json.loads(path.read_text())["plan"]
    assert stored["aws"]["secret_access_key"] == "" and stored["postgres"]["password"] == ""
    restored = load_profile(path)
    assert restored.postgres.password == "" and restored.aws.secret_access_key == ""
    assert not restored.postgres.rotate and not restored.app.jwt_rotate and not restored.aws.allow_kms_change and not restored.app.build_with_maven
    assert restored.proxy.api_domain == plan.proxy.api_domain


def test_to_dict_blanks_every_secret_field(plan: InstallPlan) -> None:
    plan.email.smtp_password = "hunter2"
    assert to_dict(plan)["email"]["smtp_password"] == ""

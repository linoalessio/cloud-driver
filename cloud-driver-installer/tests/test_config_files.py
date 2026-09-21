"""Tests for :mod:`cloud_driver_installer.config_files`: rendering, merge semantics and the helpers."""

from __future__ import annotations

import itertools
import json
from typing import Any

import pytest

from cloud_driver_installer.config_files import (
    DEFAULTED_KEYS,
    MANAGED_KEYS,
    OBSOLETE_KEYS,
    masked,
    parse_env_file,
    parse_json,
    render_configuration,
    render_postgres_credentials,
    render_redis_credentials,
    render_start_env,
    to_json,
)
from cloud_driver_installer.model import GeneratedSecrets, InstallPlan
from cloud_driver_installer.sizing import GIB

S3_KEYS = ("aws-s3-region", "aws-s3-bucket", "aws-s3-key-prefix")
SES_KEYS = ("aws-ses-region", "aws-ses-from-address")
SMTP_KEYS = ("smtp-host", "smtp-port", "smtp-username", "smtp-password", "smtp-from-address")
CLAMAV_KEYS = ("clamav-host", "clamav-port", "clamav-timeout-seconds", "content-scan-max-bytes")
INTELLIGENCE_KEYS = ("intelligence-shared-secret", "intelligence-host", "intelligence-port", "intelligence-timeout-seconds", "intelligence-max-bytes")
ALWAYS_KEYS = (
    "rest-server-port",
    "rest-server-bind-host",
    "metrics-port",
    "metrics-bind-host",
    "cloud-server-max-bytes-available",
    "cloud-user-max-bytes-to-upload",
    "jwt-signing-key",
    "trust-proxy-headers",
    "aws-kms-region",
    "aws-kms-key-id",
)

JWT = "jwt-signing-key-value-0123456789abcdef"
INTEL_SECRET = "intelligence-secret-value-0123456789"


@pytest.fixture
def filled_secrets() -> GeneratedSecrets:
    """Secrets as the run would have produced them."""
    return GeneratedSecrets(
        jwt_signing_key=JWT,
        kms_key_id="alias/cloud-driver-kms-key",
        kms_key_arn="arn:aws:kms:eu-central-1:123456789012:key/1234",
        s3_bucket="cloud-driver-test-bucket",
        intelligence_secret=INTEL_SECRET,
    )


def configure(plan: InstallPlan, *, s3: bool, email: str, clamav: bool, intelligence: bool, proxy: bool) -> None:
    """Switch the plan's optional features on or off (keeping it valid)."""
    plan.aws.s3_enabled = s3
    plan.app.backup_offsite = s3
    plan.email.mode = email
    if email == "ses":
        plan.email.ses_from_address = "noreply@example.com"
    if email == "smtp":
        plan.email.smtp_host = "smtp.example.com"
        plan.email.smtp_username = "mailer"
        plan.email.smtp_password = "smtp-secret-value"
        plan.email.smtp_from_address = "noreply@example.com"
    plan.clamav.enabled = clamav
    plan.intelligence.enabled = intelligence
    plan.proxy.enabled = proxy


def assert_group(doc: dict[str, Any], keys: tuple[str, ...], present: bool) -> None:
    """Every key of a feature group is present, or none is."""
    for key in keys:
        assert (key in doc) is present, f"{key} {'missing' if present else 'unexpected'} in {sorted(doc)}"


COMBINATIONS = list(itertools.product((True, False), ("none", "ses", "smtp"), (True, False), (True, False), (True, False)))


class TestRenderConfiguration:
    """``configuration.json`` for every feature combination, with the reference file's value shapes."""

    @pytest.mark.parametrize(("s3", "email", "clamav", "intelligence", "proxy"), COMBINATIONS)
    def test_every_feature_combination(self, plan: InstallPlan, filled_secrets: GeneratedSecrets, s3: bool, email: str, clamav: bool, intelligence: bool, proxy: bool) -> None:
        """Each feature contributes exactly its key group; disabled features contribute nothing."""
        configure(plan, s3=s3, email=email, clamav=clamav, intelligence=intelligence, proxy=proxy)
        doc = render_configuration(plan, filled_secrets)
        for key in ALWAYS_KEYS:
            assert key in doc
        assert_group(doc, S3_KEYS, s3)
        assert_group(doc, SES_KEYS, email == "ses")
        assert_group(doc, SMTP_KEYS, email == "smtp")
        assert_group(doc, CLAMAV_KEYS, clamav)
        assert_group(doc, INTELLIGENCE_KEYS, intelligence)
        assert "aws-ses-configuration-set" not in doc
        assert doc["trust-proxy-headers"] is proxy
        assert ("trusted-proxy-addresses" in doc) is proxy
        for key, value in DEFAULTED_KEYS.items():
            assert doc[key] == value
        assert set(doc) - set(DEFAULTED_KEYS) <= set(MANAGED_KEYS)
        json.dumps(doc)

    def test_reference_values_and_types(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """Ports and byte counts the backend reads with getString are strings; the rest keep their type."""
        doc = render_configuration(plan, filled_secrets)
        assert doc["rest-server-port"] == "8080"
        assert doc["rest-server-bind-host"] == "127.0.0.1"
        assert doc["metrics-port"] == 9404 and isinstance(doc["metrics-port"], int)
        assert doc["metrics-bind-host"] == "127.0.0.1"
        assert doc["cloud-server-max-bytes-available"] == str(256 * GIB)
        assert doc["cloud-user-max-bytes-to-upload"] == str(GIB)
        assert doc["jwt-signing-key"] == JWT
        assert doc["trust-proxy-headers"] is True
        assert doc["trusted-proxy-addresses"] == "127.0.0.1,::1"
        assert doc["aws-kms-region"] == "eu-central-1"
        assert doc["aws-kms-key-id"] == "alias/cloud-driver-kms-key"
        assert doc["aws-s3-region"] == "eu-central-1"
        assert doc["aws-s3-bucket"] == "cloud-driver-test-bucket"
        assert doc["aws-s3-key-prefix"] == ""
        assert doc["clamav-host"] == "127.0.0.1"
        assert doc["clamav-port"] == 3310
        assert doc["clamav-timeout-seconds"] == 30
        assert doc["content-scan-max-bytes"] == 104857600
        assert doc["trash-retention-days"] == 30
        assert doc["presigned-upload-ticket-retention-hours"] == 6

    def test_ses_values(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """SES region falls back to the KMS region; the configuration set only appears when named."""
        configure(plan, s3=True, email="ses", clamav=True, intelligence=False, proxy=True)
        doc = render_configuration(plan, filled_secrets)
        assert doc["aws-ses-region"] == "eu-central-1"
        assert doc["aws-ses-from-address"] == "noreply@example.com"
        assert "aws-ses-configuration-set" not in doc
        plan.email.ses_region = "eu-west-1"
        plan.email.ses_configuration_set = "cloud-driver-set"
        doc = render_configuration(plan, filled_secrets)
        assert doc["aws-ses-region"] == "eu-west-1"
        assert doc["aws-ses-configuration-set"] == "cloud-driver-set"

    def test_smtp_values(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """All five smtp-* keys, port as int, password included (the backend needs all five)."""
        configure(plan, s3=True, email="smtp", clamav=True, intelligence=False, proxy=True)
        plan.email.smtp_port = 2525
        doc = render_configuration(plan, filled_secrets)
        assert doc["smtp-host"] == "smtp.example.com"
        assert doc["smtp-port"] == 2525
        assert doc["smtp-username"] == "mailer"
        assert doc["smtp-password"] == "smtp-secret-value"
        assert doc["smtp-from-address"] == "noreply@example.com"

    def test_intelligence_values(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """The shared secret comes from the run, the rest from the plan."""
        configure(plan, s3=True, email="none", clamav=True, intelligence=True, proxy=True)
        doc = render_configuration(plan, filled_secrets)
        assert doc["intelligence-shared-secret"] == INTEL_SECRET
        assert doc["intelligence-host"] == "127.0.0.1"
        assert doc["intelligence-port"] == 8600
        assert doc["intelligence-timeout-seconds"] == 30
        assert doc["intelligence-max-bytes"] == 32 * 1024 * 1024

    def test_kms_key_id_precedence(self, plan: InstallPlan) -> None:
        """Resolved id from the run > existing id from the plan > the alias to be created."""
        plan.aws.kms_alias = "alias/planned"
        assert render_configuration(plan, GeneratedSecrets())["aws-kms-key-id"] == "alias/planned"
        plan.aws.kms_key_id = "1234-existing"
        assert render_configuration(plan, GeneratedSecrets())["aws-kms-key-id"] == "1234-existing"
        assert render_configuration(plan, GeneratedSecrets(kms_key_id="alias/resolved"))["aws-kms-key-id"] == "alias/resolved"

    def test_bucket_precedence(self, plan: InstallPlan) -> None:
        """The bucket the run created/adopted wins over the plan's name."""
        assert render_configuration(plan, GeneratedSecrets())["aws-s3-bucket"] == "cloud-driver-test-bucket"
        assert render_configuration(plan, GeneratedSecrets(s3_bucket="adopted-bucket"))["aws-s3-bucket"] == "adopted-bucket"

    def test_key_prefix_is_written(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """A non-empty prefix is passed through verbatim."""
        plan.aws.s3_key_prefix = "prod/"
        assert render_configuration(plan, filled_secrets)["aws-s3-key-prefix"] == "prod/"


class TestTrustedProxy:
    """``trust-proxy-headers``/``trusted-proxy-addresses`` only behind Caddy on a loopback bind."""

    @pytest.mark.parametrize("bind", ["127.0.0.1", "localhost", "::1"])
    def test_behind_caddy_on_loopback(self, plan: InstallPlan, filled_secrets: GeneratedSecrets, bind: str) -> None:
        """Every loopback spelling counts."""
        plan.app.rest_bind_host = bind
        doc = render_configuration(plan, filled_secrets)
        assert doc["trust-proxy-headers"] is True
        assert doc["trusted-proxy-addresses"] == "127.0.0.1,::1"

    def test_public_bind_never_trusts_headers(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """A public bind with Caddy enabled must not trust X-Forwarded-For."""
        plan.app.rest_bind_host = "0.0.0.0"
        doc = render_configuration(plan, filled_secrets)
        assert doc["trust-proxy-headers"] is False
        assert "trusted-proxy-addresses" not in doc

    def test_without_caddy(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """No proxy: headers are not trusted and no allowlist is written."""
        plan.proxy.enabled = False
        doc = render_configuration(plan, filled_secrets)
        assert doc["trust-proxy-headers"] is False
        assert "trusted-proxy-addresses" not in doc

    def test_stale_allowlist_removed_on_merge(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """An old allowlist does not survive disabling the proxy."""
        plan.proxy.enabled = False
        doc = render_configuration(plan, filled_secrets, {"trust-proxy-headers": True, "trusted-proxy-addresses": "10.0.0.1"})
        assert doc["trust-proxy-headers"] is False
        assert "trusted-proxy-addresses" not in doc


class TestMerge:
    """Rendering over an existing document."""

    def test_unknown_keys_survive(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """An operator-added key is passed through untouched."""
        doc = render_configuration(plan, filled_secrets, {"operator-added": {"nested": True}, "another": "value"})
        assert doc["operator-added"] == {"nested": True}
        assert doc["another"] == "value"

    def test_obsolete_keys_dropped(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """Legacy web-panel keys never come back."""
        existing = {key: "x" for key in OBSOLETE_KEYS}
        doc = render_configuration(plan, filled_secrets, existing)
        for key in OBSOLETE_KEYS:
            assert key not in doc

    def test_keys_of_disabled_features_removed(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """A stale aws-s3-bucket (or smtp/clamav/intelligence key) must not linger and re-activate the feature."""
        configure(plan, s3=False, email="none", clamav=False, intelligence=False, proxy=True)
        existing = {"aws-s3-bucket": "gone-bucket", "aws-s3-region": "eu-central-1", "smtp-host": "old", "clamav-port": 3310, "intelligence-host": "127.0.0.1", "aws-ses-region": "x"}
        doc = render_configuration(plan, filled_secrets, existing)
        for key in existing:
            assert key not in doc

    def test_managed_keys_are_rewritten(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """An existing managed value is replaced by the plan's."""
        doc = render_configuration(plan, filled_secrets, {"rest-server-port": "9999", "jwt-signing-key": "old-key", "aws-kms-key-id": "old"})
        assert doc["rest-server-port"] == "8080"
        assert doc["jwt-signing-key"] == JWT
        assert doc["aws-kms-key-id"] == "alias/cloud-driver-kms-key"

    def test_defaulted_keys_do_not_overwrite(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """A tuned retention/rate-limit value is kept; absent ones get the default."""
        doc = render_configuration(plan, filled_secrets, {"trash-retention-days": 7, "auth-rate-limit-max-requests": 0})
        assert doc["trash-retention-days"] == 7
        assert doc["auth-rate-limit-max-requests"] == 0
        assert doc["file-versioning-retention-days"] == DEFAULTED_KEYS["file-versioning-retention-days"]

    def test_existing_is_not_mutated(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """The caller's dict stays as it was."""
        existing = {"web-panel-port": "1", "custom": 2}
        render_configuration(plan, filled_secrets, existing)
        assert existing == {"web-panel-port": "1", "custom": 2}

    def test_none_and_empty_existing(self, plan: InstallPlan, filled_secrets: GeneratedSecrets) -> None:
        """``None`` and ``{}`` render identically."""
        assert render_configuration(plan, filled_secrets, None) == render_configuration(plan, filled_secrets, {})


class TestCredentialFiles:
    """The database-driver credential shape."""

    def test_postgres(self, plan: InstallPlan) -> None:
        """address/userName/password/port/database/fileRepository - port numeric."""
        plan.postgres.port = 5433
        assert render_postgres_credentials(plan, "pg-secret") == {
            "address": "127.0.0.1",
            "userName": "cloud_driver",
            "password": "pg-secret",
            "port": 5433,
            "database": "cloud_driver",
            "fileRepository": "Unknown",
        }

    def test_redis(self, plan: InstallPlan) -> None:
        """Same shape; ``database`` is the numeric index as a string."""
        plan.redis.database = "3"
        plan.redis.username = "app"
        assert render_redis_credentials(plan, "redis-secret") == {
            "address": "127.0.0.1",
            "userName": "app",
            "password": "redis-secret",
            "port": 6379,
            "database": "3",
            "fileRepository": "Unknown",
        }
        assert isinstance(render_redis_credentials(plan, "x")["database"], str)


class TestStartEnv:
    """``start-cloud.env`` as sourced by start-cloud.sh."""

    def test_with_persistent_log(self, plan: InstallPlan) -> None:
        """JVM_XMX, JAR_NAME, SCREEN_SESSION and SCREEN_LOG_FILE, trailing newline."""
        text = render_start_env(plan, "cloud-driver-bootstrap-1.0.7.jar")
        assert text.endswith("\n")
        assert text.splitlines()[0].startswith("#")
        assert parse_env_file(text) == {
            "JVM_XMX": "5g",
            "JAR_NAME": "cloud-driver-bootstrap-1.0.7.jar",
            "SCREEN_SESSION": "cloud",
            "SCREEN_LOG_FILE": "/home/cloud/cloud.log",
        }

    def test_without_persistent_log(self, plan: InstallPlan) -> None:
        """No SCREEN_LOG_FILE line when the log is not persisted."""
        plan.app.persist_log = False
        assert "SCREEN_LOG_FILE" not in render_start_env(plan, "x.jar")

    def test_install_dir_trailing_slash(self, plan: InstallPlan) -> None:
        """A trailing slash on the install dir does not produce a double slash."""
        plan.server.install_dir = "/srv/cloud/"
        plan.server.screen_session = "cloud2"
        env = parse_env_file(render_start_env(plan, "x.jar"))
        assert env["SCREEN_LOG_FILE"] == "/srv/cloud/cloud.log"
        assert env["SCREEN_SESSION"] == "cloud2"


class TestHelpers:
    """JSON and env-file helpers plus the preview masking."""

    def test_to_json_shape(self) -> None:
        """Two-space indent, trailing newline, non-ASCII preserved."""
        text = to_json({"b": 1, "ü": "ä"})
        assert text == '{\n  "b": 1,\n  "ü": "ä"\n}\n'

    @pytest.mark.parametrize("text", [None, "", "   \n", "not json", "[1, 2]", "42", '"str"'])
    def test_parse_json_lenient(self, text: str | None) -> None:
        """Anything that is not a JSON object parses to ``{}``."""
        assert parse_json(text) == {}

    def test_parse_json_object(self) -> None:
        """A real object comes back intact."""
        assert parse_json('{"a": 1, "b": [true]}') == {"a": 1, "b": [True]}

    def test_masked_hides_secret_values_only(self) -> None:
        """Secret keys with a value become bullets; blanks and other keys are untouched; the input is not mutated."""
        doc = {"jwt-signing-key": JWT, "smtp-password": "p", "intelligence-shared-secret": "s", "password": "pw", "rest-server-port": "8080", "smtp-username": "user", "empty-secret": ""}
        doc["smtp-password"] = ""
        out = masked(doc)
        assert out["jwt-signing-key"] == "••••••••"
        assert out["intelligence-shared-secret"] == "••••••••"
        assert out["password"] == "••••••••"
        assert out["smtp-password"] == ""
        assert out["rest-server-port"] == "8080"
        assert out["smtp-username"] == "user"
        assert doc["jwt-signing-key"] == JWT
        assert JWT not in json.dumps(out)

    def test_parse_env_file(self) -> None:
        """Comments, blanks and lines without ``=`` are skipped; the first ``=`` splits; whitespace stripped."""
        text = "# comment\n\nJVM_XMX=6g\n  JAR_NAME = cloud.jar  \nNOEQUALS\nKEY=a=b\n"
        assert parse_env_file(text) == {"JVM_XMX": "6g", "JAR_NAME": "cloud.jar", "KEY": "a=b"}
        assert parse_env_file(None) == {}
        assert parse_env_file("") == {}

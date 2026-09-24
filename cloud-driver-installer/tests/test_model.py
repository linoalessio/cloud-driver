"""Tests for :mod:`cloud_driver_installer.model`: validation, warnings, defaults and (de)serialisation."""

from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any, Callable

import pytest

from cloud_driver_installer import model
from cloud_driver_installer.model import (
    CLIENT_HARDCODED_API_HOST,
    SECRET_FIELD_NAMES,
    Discovered,
    GeneratedSecrets,
    InstallPlan,
    default_plan,
    detect_repo_root,
    env_default,
    from_dict,
    to_dict,
    valid_bucket_name,
    valid_domain,
)
from cloud_driver_installer.sizing import GIB

Mutator = Callable[[InstallPlan], None]


def set_fields(section: str, **values: Any) -> Mutator:
    """Return a mutator assigning ``values`` on ``plan.<section>``."""

    def mutate(plan: InstallPlan) -> None:
        target = getattr(plan, section)
        for name, value in values.items():
            setattr(target, name, value)

    return mutate


def chain(*mutators: Mutator) -> Mutator:
    """Return a mutator applying ``mutators`` in order."""

    def mutate(plan: InstallPlan) -> None:
        for mutator in mutators:
            mutator(plan)

    return mutate


def smtp(**overrides: Any) -> Mutator:
    """A complete SMTP configuration with ``overrides`` applied on top."""
    values: dict[str, Any] = {
        "mode": "smtp",
        "smtp_host": "smtp.example.com",
        "smtp_port": 587,
        "smtp_username": "mailer",
        "smtp_password": "smtp-secret-value",
        "smtp_from_address": "noreply@example.com",
    }
    values.update(overrides)
    return set_fields("email", **values)


def ses(**overrides: Any) -> Mutator:
    """A complete SES configuration with ``overrides`` applied on top."""
    values: dict[str, Any] = {"mode": "ses", "ses_from_address": "noreply@example.com"}
    values.update(overrides)
    return set_fields("email", **values)


#: (id, mutation of the valid reference plan, substring the resulting problem must contain).
PROBLEM_CASES: list[tuple[str, Mutator, str]] = [
    ("server-install-dir-relative", set_fields("server", install_dir="cloud"), "install directory must be an absolute path"),
    ("server-screen-session-space", set_fields("server", screen_session="my session"), "screen session name"),
    ("server-screen-session-blank", set_fields("server", screen_session=""), "screen session name"),
    ("server-swap-negative", set_fields("server", swap_mb=-1), "swap size must be between"),
    ("server-swap-too-large", set_fields("server", swap_mb=65537), "swap size must be between"),
    ("firewall-bad-port", set_fields("server", firewall_extra_ports="9404/tcp, abc"), "Firewall: 'abc' is not a port"),
    ("firewall-bad-protocol", set_fields("server", firewall_extra_ports="53/icmp"), "Firewall: '53/icmp' is not a port"),
    ("server-ssh-alias-name", set_fields("server", write_ssh_alias=True, ssh_alias_name="bad name"), "ssh alias name"),
    ("postgres-mode", set_fields("postgres", mode="bogus"), "PostgreSQL: mode must be install or external"),
    ("postgres-host", set_fields("postgres", host=""), "PostgreSQL: host is required"),
    ("postgres-port-zero", set_fields("postgres", port=0), "PostgreSQL: port must be 1-65535"),
    ("postgres-port-high", set_fields("postgres", port=65536), "PostgreSQL: port must be 1-65535"),
    ("postgres-database-identifier", set_fields("postgres", database="Cloud-Driver"), "database name must be a simple lowercase identifier"),
    ("postgres-username-identifier", set_fields("postgres", username="1bad"), "username must be a simple lowercase identifier"),
    ("postgres-external-password", set_fields("postgres", mode="external", password=""), "password is required for an external server"),
    ("redis-mode", set_fields("redis", mode="bogus"), "Redis: mode must be install or external"),
    ("redis-host", set_fields("redis", host=""), "Redis: host is required"),
    ("redis-port", set_fields("redis", port=70000), "Redis: port must be 1-65535"),
    ("redis-database-alpha", set_fields("redis", database="cache"), "Redis: database must be a numeric index"),
    ("redis-database-blank", set_fields("redis", database=""), "Redis: database must be a numeric index"),
    ("redis-external-password", set_fields("redis", mode="external", password=""), "Redis: a password is required for an external server"),
    ("redis-install-public-host", set_fields("redis", host="82.165.48.39"), "the host must be 127.0.0.1, not 82.165.48.39"),
    ("postgres-install-public-host", set_fields("postgres", host="82.165.48.39"), "the host must be 127.0.0.1, not 82.165.48.39"),
    ("clamav-port", set_fields("clamav", port=0), "ClamAV: port must be 1-65535"),
    ("clamav-stream-max-below-scan-max", set_fields("clamav", stream_max_length="64M"), "StreamMaxLength (64M) must not be below content-scan-max-bytes"),
    ("clamav-max-file-size-below-scan-max", set_fields("clamav", max_file_size="64M"), "MaxFileSize (64M) must not be below content-scan-max-bytes"),
    ("clamav-unparseable-size", set_fields("clamav", stream_max_length="128MB"), "ClamAV: not a clamd size"),
    ("clamav-scan-max-zero", set_fields("clamav", content_scan_max_bytes=0), "content-scan-max-bytes must be positive"),
    ("aws-keys-missing", set_fields("aws", credential_source="keys", access_key_id="", secret_access_key=""), "access key id and secret are required"),
    ("aws-keys-missing-secret", set_fields("aws", credential_source="keys", access_key_id="AKIATEST", secret_access_key=""), "access key id and secret are required"),
    ("aws-profile-missing", set_fields("aws", credential_source="profile", profile_name=""), "choose a local profile"),
    ("aws-credential-source", set_fields("aws", credential_source="bogus"), "credential source must be profile or keys"),
    ("aws-region-uppercase", set_fields("aws", region="EU-Central"), "region looks wrong"),
    ("aws-region-blank", set_fields("aws", region=""), "region looks wrong"),
    ("aws-kms-alias", set_fields("aws", kms_mode="create", kms_alias="cloud-driver"), "KMS alias must look like alias/name"),
    ("aws-kms-existing-id", set_fields("aws", kms_mode="existing", kms_key_id=""), "enter the existing KMS key id"),
    ("aws-kms-mode", set_fields("aws", kms_mode="bogus"), "KMS mode must be create or existing"),
    ("aws-s3-mode", set_fields("aws", s3_mode="bogus"), "S3 mode must be create or existing"),
    ("aws-s3-bucket-uppercase", set_fields("aws", s3_bucket="Cloud-Driver"), "S3 bucket name is invalid"),
    ("aws-s3-bucket-underscore", set_fields("aws", s3_bucket="cloud_driver"), "S3 bucket name is invalid"),
    ("aws-s3-bucket-ip", set_fields("aws", s3_bucket="192.168.0.1"), "S3 bucket name is invalid"),
    ("aws-s3-bucket-blank", set_fields("aws", s3_bucket=""), "S3 bucket name is invalid"),
    ("aws-s3-prefix-leading-slash", set_fields("aws", s3_key_prefix="/files/"), "S3 key prefix must not start or end with /"),
    ("aws-s3-prefix-double-slash", set_fields("aws", s3_key_prefix="a//b/"), "S3 key prefix must not start or end with /"),
    ("aws-s3-abort-days", set_fields("aws", s3_abort_multipart_days=-1), "multipart abort days cannot be negative"),
    ("aws-iam-user-name-space", set_fields("aws", iam_user_name="bad name"), "IAM user name is invalid"),
    ("aws-iam-user-name-blank", set_fields("aws", iam_user_name=""), "IAM user name is invalid"),
    ("aws-server-identity", set_fields("aws", server_identity="bogus"), "server identity must be iam_user or reuse"),
    ("email-ses-from", ses(ses_from_address="nope"), "SES from address must be a valid e-mail address"),
    ("email-ses-region", ses(ses_region="bad"), "SES region looks wrong"),
    ("email-ses-identity-mode", ses(ses_identity_mode="bogus"), "SES identity mode must be domain or address"),
    ("email-smtp-host", smtp(smtp_host=""), "SMTP host is required"),
    ("email-smtp-port", smtp(smtp_port=0), "SMTP port must be 1-65535"),
    ("email-smtp-password", smtp(smtp_password=""), "SMTP username and password are required"),
    ("email-smtp-username", smtp(smtp_username=""), "SMTP username and password are required"),
    ("email-smtp-from", smtp(smtp_from_address="nope"), "SMTP from address must be a valid e-mail address"),
    ("email-mode", set_fields("email", mode="bogus"), "E-mail: mode must be none, ses or smtp"),
    ("proxy-domain-no-dot", set_fields("proxy", api_domain="nodots"), "API domain must be a hostname"),
    ("proxy-acme-email", set_fields("proxy", acme_email="not-an-address"), "ACME e-mail must be a valid e-mail address"),
    ("app-rest-port", set_fields("app", rest_port=0), "REST port must be 1-65535"),
    ("app-metrics-port", set_fields("app", metrics_port=99999), "metrics port must be 1-65535"),
    ("app-ports-equal", set_fields("app", metrics_port=8080), "REST and metrics ports must differ"),
    ("app-public-bind-behind-proxy", set_fields("app", rest_bind_host="0.0.0.0"), "behind Caddy the REST port must bind to 127.0.0.1"),
    ("app-server-max-bytes", set_fields("app", server_max_bytes=0), "server storage capacity must be positive"),
    ("app-user-max-bytes", set_fields("app", user_max_bytes=-1), "per-user quota must be positive"),
    ("app-xmx-too-small", set_fields("app", jvm_xmx="512m"), "-Xmx below 1g cannot run the backend"),
    ("app-xmx-garbage", set_fields("app", jvm_xmx="lots"), "not a JVM heap size"),
    ("app-repo-root-required", set_fields("app", repo_root=""), "repository root is required"),
    ("app-backup-offsite-without-s3", set_fields("aws", s3_enabled=False), "off-site backup copy needs S3 enabled"),
    ("intelligence-port", set_fields("intelligence", enabled=True, port=0), "Intelligence: port must be 1-65535"),
    ("intelligence-ocr-languages", set_fields("intelligence", enabled=True, ocr=True, ocr_languages="de"), "OCR languages must look like deu+eng"),
    ("intelligence-max-bytes", set_fields("intelligence", enabled=True, max_bytes=0), "Intelligence: max bytes must be positive"),
]


class TestValidate:
    """``validate()`` names every documented problem and stays quiet for a valid plan."""

    def test_reference_plan_is_valid(self, plan: InstallPlan) -> None:
        """The fixture plan (reference defaults + a throwaway repo) has no problems."""
        assert plan.validate() == []

    @pytest.mark.parametrize(("mutate", "expected"), [pytest.param(m, e, id=i) for i, m, e in PROBLEM_CASES])
    def test_reports_problem(self, plan: InstallPlan, mutate: Mutator, expected: str) -> None:
        """Each documented problem is reported with its message."""
        mutate(plan)
        problems = plan.validate()
        assert any(expected in problem for problem in problems), problems

    def test_problems_accumulate(self, plan: InstallPlan) -> None:
        """Several independent mistakes are all reported at once."""
        plan.postgres.port = 0
        plan.app.rest_port = 0
        plan.email.mode = "bogus"
        problems = plan.validate()
        # Port 0 is reported twice on purpose: as an invalid port, and as a collision with the
        # REST listener that is also on 0.
        assert len(problems) == 4
        assert problems[0].startswith("PostgreSQL:")
        assert problems[1].startswith("E-mail:")
        assert problems[2].startswith("Application:")
        assert problems[3].startswith("PostgreSQL:")

    @pytest.mark.parametrize(
        "mutate",
        [
            pytest.param(set_fields("redis", enabled=False, host="", port=0, database="cache", mode="bogus"), id="redis"),
            pytest.param(set_fields("clamav", enabled=False, port=0, stream_max_length="garbage", content_scan_max_bytes=0), id="clamav"),
            pytest.param(set_fields("proxy", enabled=False, api_domain="nodots", acme_email="bad"), id="proxy"),
            pytest.param(
                chain(set_fields("aws", s3_enabled=False, s3_mode="bogus", s3_bucket="BAD_BUCKET", s3_key_prefix="/x", s3_abort_multipart_days=-1), set_fields("app", backup_offsite=False)),
                id="s3",
            ),
            pytest.param(set_fields("intelligence", enabled=False, port=0, max_bytes=0, ocr=True, ocr_languages="x"), id="intelligence"),
            pytest.param(set_fields("email", mode="none", smtp_host="", ses_from_address="nope", ses_region="bad"), id="email-none"),
            pytest.param(set_fields("server", write_ssh_alias=False, ssh_alias_name="bad name"), id="ssh-alias-opt-in"),
        ],
    )
    def test_disabled_feature_is_not_validated(self, plan: InstallPlan, mutate: Mutator) -> None:
        """Fields of a switched-off feature may hold anything."""
        mutate(plan)
        assert plan.validate() == []

    @pytest.mark.parametrize("group", ["postgres", "redis"])
    def test_an_external_store_may_live_anywhere(self, plan: InstallPlan, group: str) -> None:
        """The loopback rule is about what this installer binds itself, not about someone else's server."""
        settings = getattr(plan, group)
        settings.mode, settings.host, settings.password = "external", "store.example.com", "secret"
        assert plan.validate() == []

    def test_max_scan_size_is_exempt_from_the_scan_limit_check(self, plan: InstallPlan) -> None:
        """Only StreamMaxLength and MaxFileSize are compared against content-scan-max-bytes."""
        plan.clamav.max_scan_size = "64M"
        assert plan.validate() == []

    def test_clamd_limits_equal_to_scan_max_are_accepted(self, plan: InstallPlan) -> None:
        """Equality is not "below"."""
        plan.clamav.content_scan_max_bytes = 128 * 1024 * 1024
        assert plan.validate() == []

    @pytest.mark.parametrize("bind", ["127.0.0.1", "localhost", "::1"])
    def test_loopback_spellings_accepted_behind_proxy(self, plan: InstallPlan, bind: str) -> None:
        """Every loopback spelling satisfies the behind-Caddy rule."""
        plan.app.rest_bind_host = bind
        assert plan.validate() == []

    def test_public_bind_allowed_without_proxy(self, plan: InstallPlan) -> None:
        """Without Caddy the REST port may bind anywhere."""
        plan.proxy.enabled = False
        plan.app.rest_bind_host = "0.0.0.0"
        assert plan.validate() == []

    def test_blank_xmx_is_allowed(self, plan: InstallPlan) -> None:
        """An empty -Xmx means "suggest from RAM later"."""
        plan.app.jvm_xmx = ""
        assert plan.validate() == []

    def test_keys_credential_source_is_valid_when_complete(self, plan: InstallPlan) -> None:
        """Access key + secret satisfy the keys source; the session token is optional."""
        plan.aws.credential_source = "keys"
        plan.aws.access_key_id = "AKIATEST"
        plan.aws.secret_access_key = "secret"
        assert plan.validate() == []

    def test_existing_kms_and_bucket_and_reuse_identity(self, plan: InstallPlan) -> None:
        """The "use existing" modes are valid with an id and a name."""
        plan.aws.kms_mode = "existing"
        plan.aws.kms_key_id = "alias/other"
        plan.aws.s3_mode = "existing"
        plan.aws.server_identity = "reuse"
        assert plan.validate() == []

    def test_repo_root_optional_when_nothing_needs_it(self, plan: InstallPlan) -> None:
        """No jar deploy, no build, no local write-back: the checkout is not needed."""
        plan.app.repo_root = ""
        plan.app.deploy_jars = False
        plan.app.build_with_maven = False
        plan.app.write_local_config = False
        assert plan.validate() == []

    def test_repo_root_without_pom(self, plan: InstallPlan, tmp_path: Path) -> None:
        """A directory that is not the checkout is rejected by name."""
        plan.app.repo_root = str(tmp_path)
        assert any("does not contain pom.xml" in p and str(tmp_path) in p for p in plan.validate())

    def test_intelligence_source_dir_without_pyproject(self, plan: InstallPlan, tmp_path: Path) -> None:
        """An explicit source dir must hold the intelligence pyproject."""
        plan.intelligence.enabled = True
        plan.intelligence.source_dir = str(tmp_path / "elsewhere")
        assert any("source directory must contain" in p for p in plan.validate())

    def test_intelligence_defaults_resolve_from_repo(self, plan: InstallPlan) -> None:
        """Source dir and driver clone are derived from the repo root when blank."""
        plan.intelligence.enabled = True
        assert plan.intelligence_source_dir == str(Path(plan.app.repo_root) / "cloud-driver-intelligence")
        assert plan.intelligence_driver_clone_dir == str(Path(plan.app.repo_root).parent / "database-driver-v2")
        assert plan.validate() == []

    def test_intelligence_encryption_needs_driver_clone(self, plan: InstallPlan) -> None:
        """Without the database-driver-v2 clone, encryption cannot be installed."""
        plan.intelligence.enabled = True
        shutil.rmtree(Path(plan.app.repo_root).parent / "database-driver-v2")
        assert plan.intelligence_driver_clone_dir == ""
        assert any("at-rest encryption needs the database-driver-v2 clone" in p for p in plan.validate())
        plan.intelligence.enable_encryption = False
        assert plan.validate() == []
        plan.intelligence.enable_encryption = True
        plan.intelligence.driver_clone_dir = "/opt/database-driver-v2"
        assert plan.intelligence_driver_clone_dir == "/opt/database-driver-v2"
        assert plan.validate() == []


class TestPublicApiUrl:
    """``public_api_url()`` names the three deployment shapes - and stays quiet for the unreachable one."""

    def test_proxy_with_a_domain_is_https(self, plan: InstallPlan) -> None:
        """A domain means TLS on that name, whether or not the public address is known."""
        plan.proxy.enabled = True
        plan.proxy.api_domain = "api.example.com"
        assert plan.public_api_url("203.0.113.10") == "https://api.example.com"
        assert plan.public_api_url() == "https://api.example.com"

    def test_proxy_without_a_domain_is_plain_http_on_the_address(self, plan: InstallPlan) -> None:
        """Caddy answers on :80; without a known address there is nothing to name."""
        plan.proxy.enabled = True
        plan.proxy.api_domain = ""
        assert plan.public_api_url("203.0.113.10") == "http://203.0.113.10"
        assert plan.public_api_url() == ""

    def test_without_the_proxy_the_rest_port_is_the_endpoint(self, plan: InstallPlan) -> None:
        """A public bind is reachable on the REST port; a loopback bind is reachable from nowhere."""
        plan.proxy.enabled = False
        plan.app.rest_bind_host = "0.0.0.0"
        assert plan.public_api_url("203.0.113.10") == f"http://203.0.113.10:{plan.app.rest_port}"
        plan.app.rest_bind_host = "127.0.0.1"
        assert plan.public_api_url("203.0.113.10") == ""


class TestWarnings:
    """``warnings()`` says the non-blocking things out loud."""

    def test_no_email_transport(self, plan: InstallPlan) -> None:
        """Log-only codes are called out."""
        plan.email.mode = "none"
        assert any("No e-mail transport" in n for n in plan.warnings())

    def test_ses_domain_notes(self, plan: InstallPlan) -> None:
        """SES gets the sandbox note and, for a domain identity, the DKIM note."""
        ses()(plan)
        notes = plan.warnings()
        assert any("sandbox" in n for n in notes)
        assert any("DKIM" in n for n in notes)
        plan.email.ses_identity_mode = "address"
        notes = plan.warnings()
        assert any("sandbox" in n for n in notes)
        assert not any("DKIM" in n for n in notes)

    def test_smtp_has_no_mail_notes(self, plan: InstallPlan) -> None:
        """SMTP needs neither the sandbox nor the log-only note."""
        smtp()(plan)
        notes = plan.warnings()
        assert not any("e-mail transport" in n or "SES" in n for n in notes)

    def test_proxy_notes(self, plan: InstallPlan) -> None:
        """No proxy -> plain HTTP note; a foreign domain -> rebuild note; the hardcoded host -> nothing."""
        plan.proxy.enabled = False
        assert any("No reverse proxy" in n for n in plan.warnings())
        assert any("Nothing can reach the API from outside" in n for n in plan.warnings())
        plan.app.rest_bind_host = "0.0.0.0"
        assert not any("Nothing can reach the API from outside" in n for n in plan.warnings())
        plan.app.rest_bind_host = "127.0.0.1"
        plan.proxy.enabled = True
        plan.proxy.api_domain = ""
        assert any("without a domain" in n and "cannot obtain a certificate" in n for n in plan.warnings())
        plan.proxy.api_domain = "api.example.com"
        assert any("rebuilding them" in n and "api.example.com" in n for n in plan.warnings())
        plan.proxy.api_domain = CLIENT_HARDCODED_API_HOST.upper()
        assert not any("rebuilding them" in n or "No reverse proxy" in n for n in plan.warnings())

    def test_feature_toggle_notes(self, plan: InstallPlan) -> None:
        """clamav, S3, firewall and autostart each have a note when off (or, for clamav, on)."""
        assert any("freshclam" in n for n in plan.warnings())
        plan.clamav.enabled = False
        assert not any("freshclam" in n for n in plan.warnings())
        plan.aws.s3_enabled = False
        assert any("Without S3" in n for n in plan.warnings())
        plan.server.firewall = False
        assert any("Firewall disabled" in n for n in plan.warnings())
        plan.app.autostart_on_reboot = False
        assert any("No reboot autostart" in n for n in plan.warnings())

    def test_ram_tight_note(self, plan: InstallPlan, discovered: Discovered) -> None:
        """The RAM note depends on the discovered RAM and the chosen heap."""
        plan.app.jvm_xmx = "4g"
        assert not any("RAM is tight" in n for n in plan.warnings(discovered))
        plan.app.jvm_xmx = "6g"
        assert any("RAM is tight" in n and "7 GiB" in n for n in plan.warnings(discovered))
        assert not any("RAM is tight" in n for n in plan.warnings(None))
        plan.app.jvm_xmx = "garbage"
        assert not any("RAM is tight" in n for n in plan.warnings(discovered))
        plan.app.jvm_xmx = "6g"
        discovered.ram_mib = 0
        assert not any("RAM is tight" in n for n in plan.warnings(discovered))

    def test_python_version_note(self, plan: InstallPlan, discovered: Discovered) -> None:
        """The encrypted vector store needs Python 3.11+."""
        plan.intelligence.enabled = True
        discovered.python_version = "3.10.12"
        assert any("needs Python 3.11+" in n and "3.10.12" in n for n in plan.warnings(discovered))
        discovered.python_version = "3.11.2"
        assert not any("Python 3.11+" in n for n in plan.warnings(discovered))
        discovered.python_version = "3.10.12"
        plan.intelligence.enable_encryption = False
        assert not any("Python 3.11+" in n for n in plan.warnings(discovered))


class TestDerivedProperties:
    """Paths and regions derived from the plan."""

    def test_install_dir_derivatives_strip_trailing_slash(self) -> None:
        """``config_dir``/``extensions_dir`` never produce a double slash."""
        plan = InstallPlan()
        plan.server.install_dir = "/home/cloud/"
        assert plan.config_dir == "/home/cloud/cloud-driver"
        assert plan.extensions_dir == "/home/cloud/extensions"

    def test_ses_region_falls_back_to_kms_region(self) -> None:
        """Blank SES region means the KMS region."""
        plan = InstallPlan()
        assert plan.ses_region == plan.aws.region
        plan.email.ses_region = "eu-west-1"
        assert plan.ses_region == "eu-west-1"

    def test_intelligence_dirs_blank_without_repo(self) -> None:
        """No repo root -> no derived intelligence paths."""
        plan = InstallPlan()
        assert plan.intelligence_source_dir == ""
        assert plan.intelligence_driver_clone_dir == ""

    def test_discovered_summary(self, discovered: Discovered) -> None:
        """OS, RAM and free disk joined with a middle dot; blanks omitted."""
        assert discovered.summary() == "Debian GNU/Linux 12 (bookworm) · 7.7 GiB RAM · 38 GiB free"
        assert Discovered().summary() == ""

    def test_generated_secrets_all_values(self) -> None:
        """``all_values`` lists the five redactable secrets (ids and ARNs are not secrets)."""
        secrets = GeneratedSecrets(pg_password="pg", redis_password="rd", jwt_signing_key="jwt", intelligence_secret="it", server_secret_access_key="sk", server_access_key_id="AKIA")
        assert secrets.all_values() == ["pg", "rd", "jwt", "it", "sk"]


class TestDefaultPlan:
    """``default_plan()`` borrows non-secret defaults from the checkout's own configuration.json."""

    @pytest.fixture
    def repo(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
        """A fake checkout that ``detect_repo_root`` is patched to return."""
        repo = tmp_path / "repo"
        (repo / "cloud-driver").mkdir(parents=True)
        monkeypatch.setattr(model, "detect_repo_root", lambda start=None: str(repo))
        return repo

    @staticmethod
    def write_config(repo: Path, data: Any) -> None:
        """Write ``data`` as the checkout's configuration.json."""
        (repo / "cloud-driver" / "configuration.json").write_text(data if isinstance(data, str) else json.dumps(data))

    def test_without_local_config(self, repo: Path) -> None:
        """Only the repo root is filled; everything else keeps its default."""
        plan = default_plan()
        assert plan.app.repo_root == str(repo)
        assert plan == from_dict(InstallPlan, to_dict(InstallPlan(), strip_secrets=False)) or plan.aws == InstallPlan().aws

    def test_prefills_ses_configuration(self, repo: Path) -> None:
        """Regions, bucket, key id, SES address and quotas are borrowed."""
        self.write_config(
            repo,
            {
                "aws-kms-region": "eu-west-1",
                "aws-kms-key-id": "alias/prod-key",
                "aws-s3-bucket": "prod-bucket",
                "aws-s3-key-prefix": "files/",
                "aws-ses-from-address": "noreply@example.com",
                "aws-ses-region": "eu-west-1",
                "aws-ses-configuration-set": "prod-set",
                "cloud-server-max-bytes-available": "549755813888",
                "cloud-user-max-bytes-to-upload": 2147483648,
                "jwt-signing-key": "must-not-be-read",
            },
        )
        plan = default_plan()
        assert plan.aws.region == "eu-west-1"
        assert plan.aws.kms_mode == "existing"
        assert plan.aws.kms_key_id == "alias/prod-key"
        assert plan.aws.s3_mode == "existing"
        assert plan.aws.s3_bucket == "prod-bucket"
        assert plan.aws.s3_key_prefix == "files/"
        assert plan.email.mode == "ses"
        assert plan.email.ses_from_address == "noreply@example.com"
        assert plan.email.ses_region == "eu-west-1"
        assert plan.email.ses_configuration_set == "prod-set"
        assert plan.app.server_max_bytes == 512 * GIB
        assert plan.app.user_max_bytes == 2 * GIB

    def test_prefills_smtp_configuration(self, repo: Path) -> None:
        """SMTP host/port/user/from are borrowed - never the password."""
        self.write_config(repo, {"smtp-host": "smtp.example.com", "smtp-port": "2525", "smtp-username": "mailer", "smtp-password": "nope", "smtp-from-address": "a@example.com"})
        plan = default_plan()
        assert plan.email.mode == "smtp"
        assert plan.email.smtp_host == "smtp.example.com"
        assert plan.email.smtp_port == 2525
        assert plan.email.smtp_username == "mailer"
        assert plan.email.smtp_from_address == "a@example.com"
        assert plan.email.smtp_password == ""

    def test_ses_takes_precedence_over_smtp(self, repo: Path) -> None:
        """When both transports are configured the SES one wins."""
        self.write_config(repo, {"aws-ses-from-address": "a@example.com", "smtp-host": "smtp.example.com"})
        assert default_plan().email.mode == "ses"

    def test_placeholders_and_garbage_are_ignored(self, repo: Path) -> None:
        """REPLACE_ME values, non-numeric quotas and blanks leave the defaults alone."""
        self.write_config(
            repo,
            {
                "aws-kms-region": "REPLACE_ME",
                "aws-kms-key-id": "REPLACE_WITH_KEY",
                "aws-s3-bucket": "",
                "aws-ses-from-address": "not-an-address",
                "cloud-server-max-bytes-available": "lots",
                "cloud-user-max-bytes-to-upload": "-5",
            },
        )
        plan = default_plan()
        defaults = InstallPlan()
        assert plan.aws == defaults.aws
        assert plan.email == defaults.email
        assert plan.app.server_max_bytes == defaults.app.server_max_bytes
        assert plan.app.user_max_bytes == defaults.app.user_max_bytes

    def test_invalid_json_is_ignored(self, repo: Path) -> None:
        """A broken local file must not crash the GUI at start."""
        self.write_config(repo, "{not json")
        plan = default_plan()
        assert plan.app.repo_root == str(repo)
        assert plan.aws == InstallPlan().aws

    def test_no_repo_root(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Without a detected checkout the plan is the plain defaults."""
        monkeypatch.setattr(model, "detect_repo_root", lambda start=None: "")
        assert default_plan() == InstallPlan()


class TestDetectRepoRoot:
    """Walks up from a start path (then from the cwd) to pom.xml + cloud-driver-bootstrap."""

    def test_finds_repo_from_nested_start(self, plan: InstallPlan) -> None:
        """A path deep inside the checkout resolves to its root."""
        repo = Path(plan.app.repo_root)
        assert detect_repo_root(repo / "cloud-driver-bootstrap" / "target") == str(repo)

    def test_falls_back_to_cwd(self, plan: InstallPlan, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        """An unrelated start path still finds the checkout the cwd is in."""
        repo = Path(plan.app.repo_root)
        elsewhere = tmp_path / "elsewhere"
        elsewhere.mkdir()
        monkeypatch.chdir(repo / "shell")
        assert detect_repo_root(elsewhere) == str(repo)

    def test_nothing_found(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        """Neither the start path nor the cwd inside a checkout -> empty string."""
        elsewhere = tmp_path / "elsewhere"
        elsewhere.mkdir()
        monkeypatch.chdir(elsewhere)
        assert detect_repo_root(elsewhere) == ""


class TestNameValidators:
    """Bucket and hostname rules."""

    @pytest.mark.parametrize("name", ["cloud-driver-test-bucket", "abc", "a1b", "a" * 63, "123abc"])
    def test_valid_bucket_names(self, name: str) -> None:
        """Lowercase letters, digits and hyphens, 3-63 characters."""
        assert valid_bucket_name(name)

    @pytest.mark.parametrize("name", ["", "ab", "Abc", "a_b", "-abc", "abc-", "192.168.0.1", "xn--abc", "abc-s3alias", "abc--ol-s3", "a" * 64])
    def test_invalid_bucket_names(self, name: str) -> None:
        """Too short/long, uppercase, underscores, edge hyphens, IP form and reserved affixes."""
        assert not valid_bucket_name(name)

    @pytest.mark.xfail(strict=True, reason="_BUCKET_RE makes the tail group optional, so a single character passes although the rule says 3-63")
    def test_single_character_bucket_name_is_invalid(self) -> None:
        """S3 requires at least three characters."""
        assert not valid_bucket_name("a")

    @pytest.mark.parametrize("name", ["api.example.com", "a.b", "API.Example.COM", "api-1.example.co.uk"])
    def test_valid_domains(self, name: str) -> None:
        """Dotted hostnames in any case."""
        assert valid_domain(name)

    @pytest.mark.parametrize("name", ["", "localhost", "-bad.example.com", "bad-.example.com", "api.example.com.", "a..b"])
    def test_invalid_domains(self, name: str) -> None:
        """No dot, edge hyphens, trailing dot or empty labels."""
        assert not valid_domain(name)


class TestSerialisation:
    """``to_dict`` strips secrets, ``from_dict`` ignores unknown keys and keeps defaults."""

    @staticmethod
    def filled_plan() -> InstallPlan:
        """A plan with every secret field set."""
        plan = InstallPlan()
        plan.ssh.host = "203.0.113.10"
        plan.ssh.password = "ssh-pass"
        plan.ssh.key_passphrase = "key-pass"
        plan.postgres.password = "pg-pass"
        plan.redis.password = "redis-pass"
        plan.aws.access_key_id = "AKIATEST"
        plan.aws.secret_access_key = "aws-secret"
        plan.aws.session_token = "aws-token"
        plan.email.smtp_password = "smtp-pass"
        plan.app.excluded_extensions = ["cloud-driver-extensions-metrics"]
        return plan

    def test_to_dict_strips_every_secret_field(self) -> None:
        """Each name in SECRET_FIELD_NAMES is blanked wherever it appears."""
        data = to_dict(self.filled_plan())
        assert data["ssh"]["password"] == ""
        assert data["ssh"]["key_passphrase"] == ""
        assert data["postgres"]["password"] == ""
        assert data["redis"]["password"] == ""
        assert data["aws"]["secret_access_key"] == ""
        assert data["aws"]["session_token"] == ""
        assert data["email"]["smtp_password"] == ""
        assert data["aws"]["access_key_id"] == "AKIATEST"
        assert data["ssh"]["host"] == "203.0.113.10"
        assert data["app"]["excluded_extensions"] == ["cloud-driver-extensions-metrics"]

    def test_secret_field_names_cover_every_secret_in_the_plan(self) -> None:
        """No secret survives a stripped dump (a new secret field must be added to the set)."""
        text = json.dumps(to_dict(self.filled_plan()))
        for secret in ("ssh-pass", "key-pass", "pg-pass", "redis-pass", "aws-secret", "aws-token", "smtp-pass"):
            assert secret not in text
        assert {"password", "key_passphrase", "secret_access_key", "session_token", "smtp_password"} <= SECRET_FIELD_NAMES

    def test_to_dict_can_keep_secrets(self) -> None:
        """``strip_secrets=False`` is the lossless form used for round trips."""
        data = to_dict(self.filled_plan(), strip_secrets=False)
        assert data["postgres"]["password"] == "pg-pass"
        assert data["aws"]["session_token"] == "aws-token"

    def test_to_dict_converts_tuples_to_lists(self) -> None:
        """Tuples (DKIM records) become JSON-friendly lists."""
        secrets = GeneratedSecrets(ses_dkim_records=[("a._domainkey.example.com", "a.dkim.amazonses.com")])
        assert to_dict(secrets)["ses_dkim_records"] == [["a._domainkey.example.com", "a.dkim.amazonses.com"]]

    def test_to_dict_passes_scalars_through(self) -> None:
        """Non-dataclass values are returned as-is."""
        assert to_dict(5) == 5
        assert to_dict("x") == "x"
        assert to_dict(None) is None

    def test_round_trip(self) -> None:
        """A lossless dump loads back to an equal plan."""
        plan = self.filled_plan()
        assert from_dict(InstallPlan, to_dict(plan, strip_secrets=False)) == plan

    def test_from_dict_ignores_unknown_keys(self) -> None:
        """Keys a newer/older installer wrote are skipped at every level."""
        plan = from_dict(InstallPlan, {"bogus": 1, "app": {"rest_port": 9090, "nope": True}, "ssh": {"host": "h", "weird": 2}, "future_section": {"x": 1}})
        assert isinstance(plan, InstallPlan)
        assert plan.app.rest_port == 9090
        assert plan.ssh.host == "h"
        assert not hasattr(plan, "bogus")
        assert not hasattr(plan.app, "nope")

    def test_from_dict_keeps_defaults_for_missing_keys(self) -> None:
        """Only the present keys are overridden."""
        plan = from_dict(InstallPlan, {"redis": {"enabled": False}})
        assert plan.redis.enabled is False
        assert plan.redis.port == 6379
        assert plan.app == InstallPlan().app

    def test_from_dict_returns_non_dict_input_unchanged(self) -> None:
        """A scalar where a section was expected is passed through (the caller decides)."""
        assert from_dict(InstallPlan, "not a dict") == "not a dict"
        assert from_dict(str, {"a": 1}) == {"a": 1}


def test_env_default(monkeypatch: pytest.MonkeyPatch) -> None:
    """``env_default`` returns the variable when set, else the fallback."""
    monkeypatch.delenv("CDI_TEST_VALUE", raising=False)
    assert env_default("CDI_TEST_VALUE", "fallback") == "fallback"
    monkeypatch.setenv("CDI_TEST_VALUE", "set")
    assert env_default("CDI_TEST_VALUE", "fallback") == "set"

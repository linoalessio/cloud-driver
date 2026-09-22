"""Tests for :mod:`cloud_driver_installer.profiles`: profiles never carry secrets and load leniently."""

from __future__ import annotations

import json
import os
import stat
from pathlib import Path

import pytest

from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.profiles import FORMAT_VERSION, list_profiles, load_profile, save_profile


def filled_plan() -> InstallPlan:
    """A plan with distinctive non-secret values and every secret set."""
    plan = InstallPlan()
    plan.ssh.host = "cloud_driver"
    plan.ssh.port = 2222
    plan.ssh.user = "admin"
    plan.ssh.key_path = "~/.ssh/id_ed25519_strato"
    plan.ssh.password = "ssh-pass"
    plan.ssh.key_passphrase = "key-pass"
    plan.postgres.password = "pg-pass"
    plan.redis.enabled = False
    plan.redis.password = "redis-pass"
    plan.aws.credential_source = "keys"
    plan.aws.access_key_id = "AKIATEST"
    plan.aws.secret_access_key = "aws-secret"
    plan.aws.session_token = "aws-token"
    plan.aws.s3_bucket = "my-bucket"
    plan.email.mode = "smtp"
    plan.email.smtp_password = "smtp-pass"
    plan.app.jvm_xmx = "5g"
    plan.app.excluded_extensions = ["cloud-driver-extensions-metrics"]
    plan.intelligence.enabled = True
    return plan


SECRETS = ("ssh-pass", "key-pass", "pg-pass", "redis-pass", "aws-secret", "aws-token", "smtp-pass")


class TestSaveProfile:
    """Writing a profile."""

    def test_document_shape_and_stripped_secrets(self, tmp_path: Path) -> None:
        """``{"format": 1, "plan": {...}}`` with every secret blank and every non-secret intact."""
        path = tmp_path / "profiles" / "test.json"
        save_profile(filled_plan(), path)
        text = path.read_text()
        assert text.endswith("\n")
        for secret in SECRETS:
            assert secret not in text
        document = json.loads(text)
        assert document["format"] == FORMAT_VERSION
        plan = document["plan"]
        assert plan["ssh"]["host"] == "cloud_driver"
        assert plan["ssh"]["port"] == 2222
        assert plan["ssh"]["password"] == ""
        assert plan["ssh"]["key_passphrase"] == ""
        assert plan["postgres"]["password"] == ""
        assert plan["redis"]["password"] == ""
        assert plan["aws"]["secret_access_key"] == ""
        assert plan["aws"]["session_token"] == ""
        assert plan["aws"]["access_key_id"] == "AKIATEST"
        assert plan["email"]["smtp_password"] == ""
        assert plan["app"]["excluded_extensions"] == ["cloud-driver-extensions-metrics"]

    def test_creates_parent_directories_and_mode_0600(self, tmp_path: Path) -> None:
        """Missing parents are created; the file is private."""
        path = tmp_path / "deep" / "er" / "profile.json"
        save_profile(InstallPlan(), path)
        assert path.is_file()
        assert stat.S_IMODE(path.stat().st_mode) == 0o600

    def test_overwrites_existing(self, tmp_path: Path) -> None:
        """Saving twice replaces the document."""
        path = tmp_path / "p.json"
        plan = InstallPlan()
        plan.ssh.host = "first"
        save_profile(plan, path)
        plan.ssh.host = "second"
        save_profile(plan, path)
        assert load_profile(path).ssh.host == "second"


class TestLoadProfile:
    """Reading a profile."""

    def test_round_trip_keeps_non_secrets_and_blanks_secrets(self, tmp_path: Path) -> None:
        """What was saved comes back, minus the secrets."""
        path = tmp_path / "p.json"
        original = filled_plan()
        save_profile(original, path)
        loaded = load_profile(path)
        assert loaded.ssh.host == "cloud_driver"
        assert loaded.ssh.port == 2222
        assert loaded.ssh.user == "admin"
        assert loaded.ssh.key_path == "~/.ssh/id_ed25519_strato"
        assert loaded.redis.enabled is False
        assert loaded.aws.credential_source == "keys"
        assert loaded.aws.access_key_id == "AKIATEST"
        assert loaded.aws.s3_bucket == "my-bucket"
        assert loaded.email.mode == "smtp"
        assert loaded.app.jvm_xmx == "5g"
        assert loaded.app.excluded_extensions == ["cloud-driver-extensions-metrics"]
        assert loaded.intelligence.enabled is True
        for value in (loaded.ssh.password, loaded.ssh.key_passphrase, loaded.postgres.password, loaded.redis.password, loaded.aws.secret_access_key, loaded.aws.session_token, loaded.email.smtp_password):
            assert value == ""

    def test_hand_edited_secrets_are_blanked(self, tmp_path: Path) -> None:
        """Even a file someone edited to contain secrets loads without them."""
        path = tmp_path / "p.json"
        document = {
            "format": FORMAT_VERSION,
            "plan": {
                "ssh": {"host": "h", "password": "typed-in", "key_passphrase": "typed-in"},
                "postgres": {"password": "typed-in"},
                "redis": {"password": "typed-in"},
                "aws": {"secret_access_key": "typed-in", "session_token": "typed-in"},
                "email": {"smtp_password": "typed-in"},
            },
        }
        path.write_text(json.dumps(document))
        loaded = load_profile(path)
        assert loaded.ssh.host == "h"
        for value in (loaded.ssh.password, loaded.ssh.key_passphrase, loaded.postgres.password, loaded.redis.password, loaded.aws.secret_access_key, loaded.aws.session_token, loaded.email.smtp_password):
            assert value == ""

    def test_unknown_keys_ignored_and_missing_keys_default(self, tmp_path: Path) -> None:
        """Forward/backward compatible: extra keys are dropped, absent ones keep defaults."""
        path = tmp_path / "p.json"
        path.write_text(json.dumps({"format": 99, "plan": {"future": {"x": 1}, "app": {"rest_port": 9090, "unknown_flag": True}, "ssh": {"host": "h"}}}))
        loaded = load_profile(path)
        assert loaded.app.rest_port == 9090
        assert loaded.app.metrics_port == 9404
        assert loaded.postgres == InstallPlan().postgres
        assert not hasattr(loaded, "future")

    def test_bare_plan_document(self, tmp_path: Path) -> None:
        """A document without the ``plan`` wrapper is accepted."""
        path = tmp_path / "p.json"
        path.write_text(json.dumps({"ssh": {"host": "bare"}, "server": {"swap_mb": 2048}}))
        loaded = load_profile(path)
        assert loaded.ssh.host == "bare"
        assert loaded.server.swap_mb == 2048

    def test_non_object_document_gives_defaults(self, tmp_path: Path) -> None:
        """A JSON array or scalar loads as the default plan."""
        path = tmp_path / "p.json"
        path.write_text("[1, 2, 3]")
        assert load_profile(path) == InstallPlan()

    def test_invalid_json_raises(self, tmp_path: Path) -> None:
        """Broken JSON is an error the GUI shows, not silently a default plan."""
        path = tmp_path / "p.json"
        path.write_text("{not json")
        with pytest.raises(ValueError):
            load_profile(path)

    @pytest.mark.xfail(strict=True, raises=TypeError, reason="SshTarget.host has no default, so a profile whose ssh section lacks 'host' cannot be loaded although missing keys are documented to keep their defaults")
    def test_ssh_section_without_host(self, tmp_path: Path) -> None:
        """A partial ssh section should load with the host left blank."""
        path = tmp_path / "p.json"
        path.write_text(json.dumps({"format": FORMAT_VERSION, "plan": {"ssh": {"port": 2222}}}))
        assert load_profile(path).ssh.port == 2222


class TestListProfiles:
    """Enumerating saved profiles."""

    def test_missing_directory(self, tmp_path: Path) -> None:
        """No directory -> no profiles."""
        assert list_profiles(tmp_path / "nope") == []

    def test_newest_first_json_only(self, tmp_path: Path) -> None:
        """Sorted by mtime descending; non-JSON files ignored."""
        old = tmp_path / "old.json"
        new = tmp_path / "new.json"
        mid = tmp_path / "mid.json"
        for path in (old, new, mid):
            save_profile(InstallPlan(), path)
        (tmp_path / "notes.txt").write_text("ignored")
        os.utime(old, (1_000_000, 1_000_000))
        os.utime(mid, (2_000_000, 2_000_000))
        os.utime(new, (3_000_000, 3_000_000))
        assert list_profiles(tmp_path) == [new, mid, old]

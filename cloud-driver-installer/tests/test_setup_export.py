"""``Setup.md``: complete, honest about the secrets it carries, and written owner-only."""

from __future__ import annotations

import os
import stat
from pathlib import Path

from cloud_driver_installer.model import Discovered, GeneratedSecrets, InstallPlan
from cloud_driver_installer.setup_export import SECRET_WARNING, render_setup_markdown, write_setup_markdown


def filled_secrets() -> GeneratedSecrets:
    """One of each kind of secret the run can hold."""
    return GeneratedSecrets(
        pg_password="pg-secret-value",
        redis_password="redis-secret-value",
        jwt_signing_key="jwt-secret-value==",
        intelligence_secret="intelligence-secret-value==",
        server_access_key_id="AKIAEXAMPLE",
        server_secret_access_key="aws-secret-value",
        kms_key_id="alias/cloud-driver",
        s3_bucket="cloud-driver-storage",
        ses_dkim_records=[("abc._domainkey.example.com", "abc.dkim.amazonses.com")],
    )


def test_every_secret_of_the_run_is_in_the_document(plan: InstallPlan) -> None:
    """The file exists to be complete: a masked copy of a password is worth nothing."""
    secrets = filled_secrets()
    plan.email.mode = "smtp"
    plan.email.smtp_password = "smtp-secret-value"
    text = render_setup_markdown(plan, secrets, Discovered(public_ip="203.0.113.10"))
    for secret in (
        secrets.pg_password,
        secrets.redis_password,
        secrets.jwt_signing_key,
        secrets.intelligence_secret,
        secrets.server_access_key_id,
        secrets.server_secret_access_key,
        "smtp-secret-value",
    ):
        assert secret in text, secret


def test_the_warning_comes_before_the_first_secret(plan: InstallPlan) -> None:
    """Nobody may scroll past the passwords without having read what the file is."""
    text = render_setup_markdown(plan, filled_secrets())
    assert SECRET_WARNING in text
    assert text.index(SECRET_WARNING) < text.index("pg-secret-value")


def test_the_document_covers_every_part_of_the_deployment(plan: InstallPlan) -> None:
    text = render_setup_markdown(plan, filled_secrets(), Discovered(os_pretty="Debian 13", ram_mib=7884, public_ip="203.0.113.10"))
    for heading in (
        "## At a glance",
        "## Credentials",
        "## Server",
        "## PostgreSQL",
        "## Redis",
        "## AWS",
        "## E-mail",
        "## Reverse proxy",
        "## Application",
        "## Malware scanning (ClamAV)",
        "## Intelligence service",
        "## Running it",
    ):
        assert heading in text, heading
    assert f"screen -r {plan.server.screen_session}" in text
    assert plan.postgres.database in text and str(plan.app.rest_port) in text


def test_the_endpoint_follows_the_deployment_shape(plan: InstallPlan) -> None:
    """Domain, no domain and no proxy each name the URL a client would actually use."""
    found = Discovered(public_ip="203.0.113.10")
    plan.proxy.enabled, plan.proxy.api_domain = True, "api.example.com"
    assert "https://api.example.com" in render_setup_markdown(plan, GeneratedSecrets(), found)
    plan.proxy.api_domain = ""
    assert "http://203.0.113.10" in render_setup_markdown(plan, GeneratedSecrets(), found)
    plan.proxy.enabled, plan.app.rest_bind_host = False, "0.0.0.0"
    assert f"http://203.0.113.10:{plan.app.rest_port}" in render_setup_markdown(plan, GeneratedSecrets(), found)


def test_the_dkim_records_are_listed_when_there_are_any(plan: InstallPlan) -> None:
    text = render_setup_markdown(plan, filled_secrets())
    assert "abc._domainkey.example.com" in text and "abc.dkim.amazonses.com" in text


def test_an_export_before_the_first_check_says_what_it_does_not_know(plan: InstallPlan) -> None:
    """No discovered facts yet: the document must say so instead of inventing them."""
    text = render_setup_markdown(plan, GeneratedSecrets())
    assert "_not set_" in text
    assert "None" not in text.replace("None of", "")


def test_the_file_is_written_owner_only(plan: InstallPlan, tmp_path: Path) -> None:
    """It is as sensitive as the server itself, so nobody else on the machine may read it."""
    target = write_setup_markdown(tmp_path / "out" / "Setup.md", plan, filled_secrets(), Discovered())
    assert target.is_file()
    assert stat.S_IMODE(os.stat(target).st_mode) == 0o600
    assert "pg-secret-value" in target.read_text(encoding="utf-8")

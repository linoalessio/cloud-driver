"""Shared fixtures: a scripted remote, a plan with the reference defaults, and a ready Context."""

from __future__ import annotations

from pathlib import Path

import pytest

from cloud_driver_installer.engine import Context
from cloud_driver_installer.model import Discovered, GeneratedSecrets, InstallPlan
from cloud_driver_installer.secrets import Redactor

from fake_remote import FakeRemote


@pytest.fixture
def remote() -> FakeRemote:
    return FakeRemote()


@pytest.fixture
def plan(tmp_path: Path) -> InstallPlan:
    """A valid plan pointing at a throwaway 'repository' with the files the steps look for."""
    repo = tmp_path / "repo"
    (repo / "cloud-driver-bootstrap" / "target").mkdir(parents=True)
    (repo / "cloud-driver-bootstrap" / "target" / "cloud-driver-bootstrap-1.0.7.jar").write_bytes(b"PK-bootstrap")
    (repo / "cloud-driver-bootstrap" / "target" / "original-cloud-driver-bootstrap-1.0.7.jar").write_bytes(b"PK-original")
    for name in ("rest", "terminal", "watcher", "backup", "scan", "intelligence"):
        target = repo / "cloud-driver-extensions" / f"cloud-driver-extensions-{name}" / "target"
        target.mkdir(parents=True)
        (target / f"cloud-driver-extensions-{name}-1.0.7.jar").write_bytes(b"PK-" + name.encode())
    (repo / "pom.xml").write_text("<project><version>1.0.7</version></project>")
    (repo / "shell").mkdir()
    (repo / "shell" / "start-cloud.sh").write_text("#!/usr/bin/env bash\necho start\n")
    (repo / "cloud-driver").mkdir()
    intelligence = repo / "cloud-driver-intelligence"
    (intelligence / "src" / "cloud_driver_intelligence").mkdir(parents=True)
    (intelligence / "tests").mkdir()
    (intelligence / "deploy").mkdir()
    (intelligence / "pyproject.toml").write_text("[project]\nname='cloud-driver-intelligence'\n")
    (intelligence / "deploy" / "cloud-driver-intelligence.service").write_text("[Unit]\nDescription=test\n")
    driver = tmp_path / "database-driver-v2" / "python"
    (driver / "database-driver-api").mkdir(parents=True)
    (driver / "database-driver-plugin").mkdir(parents=True)

    plan = InstallPlan()
    plan.ssh.host = "203.0.113.10"
    plan.app.repo_root = str(repo)
    plan.app.jvm_xmx = "5g"
    plan.proxy.api_domain = "api.example.com"
    plan.aws.s3_bucket = "cloud-driver-test-bucket"
    plan.email.mode = "none"
    return plan


@pytest.fixture
def discovered() -> Discovered:
    return Discovered(
        os_pretty="Debian GNU/Linux 12 (bookworm)",
        os_id="debian",
        kernel="6.1.0-25-amd64",
        cpu_count=2,
        ram_mib=7884,
        disk_free_gib=38.2,
        public_ip="203.0.113.10",
        is_root=True,
        has_apt=True,
        has_systemd=True,
        python_version="3.11.2",
    )


@pytest.fixture
def secrets() -> GeneratedSecrets:
    return GeneratedSecrets()


@pytest.fixture
def ctx(plan: InstallPlan, discovered: Discovered, secrets: GeneratedSecrets, remote: FakeRemote) -> Context:
    log: list[tuple[str, str]] = []
    context = Context(
        plan=plan,
        discovered=discovered,
        secrets=secrets,
        remote=remote,
        redactor=Redactor(),
        log_fn=lambda level, message: log.append((level, message)),
    )
    context.captured_log = log  # type: ignore[attr-defined]
    return context

"""Tests for the base-packages step."""

from __future__ import annotations

from typing import Any

from cloud_driver_installer.engine import Context, StepStatus
from cloud_driver_installer.steps.packages import BASE_PACKAGES, PackagesStep, required_packages


def installed(remote: Any, *packages: str) -> None:
    for package in packages:
        remote.ok(f"dpkg-query -W -f='${{Status}}' {package}", "install ok installed")


def test_required_packages_add_awscli_only_for_offsite_backup(plan: Any) -> None:
    plan.app.backup_offsite = True
    assert required_packages(plan) == [*BASE_PACKAGES, "awscli"]
    plan.app.backup_offsite = False
    assert required_packages(plan) == list(BASE_PACKAGES)


def test_check_bare_box_lists_every_missing_package(ctx: Context, remote: Any, plan: Any) -> None:
    plan.app.backup_offsite = True
    result = PackagesStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert result.detail == "install " + ", ".join([*BASE_PACKAGES, "awscli"])
    assert remote.apt_installed == []


def test_check_partially_provisioned_box_lists_only_missing(ctx: Context, remote: Any, plan: Any) -> None:
    plan.app.backup_offsite = False
    installed(remote, *[p for p in BASE_PACKAGES if p not in ("screen", "unzip")])
    result = PackagesStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert result.detail == "install screen, unzip"


def test_check_provisioned_box_is_ok(ctx: Context, remote: Any, plan: Any) -> None:
    plan.app.backup_offsite = True
    installed(remote, *BASE_PACKAGES, "awscli")
    result = PackagesStep().check(ctx)
    assert result.status is StepStatus.OK
    assert result.detail == f"{len(BASE_PACKAGES) + 1} base packages installed"


def test_apply_installs_only_the_missing_packages(ctx: Context, remote: Any, plan: Any) -> None:
    plan.app.backup_offsite = False
    installed(remote, *[p for p in BASE_PACKAGES if p not in ("screen", "unzip")])
    PackagesStep().apply(ctx)
    assert remote.apt_installed == ["screen", "unzip"]


def test_apply_installs_everything_on_a_bare_box(ctx: Context, remote: Any, plan: Any) -> None:
    plan.app.backup_offsite = True
    PackagesStep().apply(ctx)
    assert remote.apt_installed == [*BASE_PACKAGES, "awscli"]
    assert any(level == "INFO" and "installing" in message for level, message in ctx.captured_log)  # type: ignore[attr-defined]


def test_apply_is_a_noop_when_everything_is_installed(ctx: Context, remote: Any, plan: Any) -> None:
    plan.app.backup_offsite = False
    installed(remote, *BASE_PACKAGES)
    PackagesStep().apply(ctx)
    PackagesStep().apply(ctx)
    assert remote.apt_installed == []
    assert not remote.ran("apt-get install")


def test_verify_requires_packages_and_screen_on_path(ctx: Context, remote: Any, plan: Any) -> None:
    plan.app.backup_offsite = False
    installed(remote, *BASE_PACKAGES)
    result = PackagesStep().verify(ctx)
    assert not result.ok
    assert "screen" in result.detail
    remote.ok("command -v screen")
    result = PackagesStep().verify(ctx)
    assert result.ok
    assert "screen available" in result.detail


def test_verify_reports_still_missing_packages(ctx: Context, remote: Any, plan: Any) -> None:
    plan.app.backup_offsite = False
    remote.ok("command -v screen")
    installed(remote, *[p for p in BASE_PACKAGES if p != "openssl"])
    result = PackagesStep().verify(ctx)
    assert not result.ok
    assert result.detail == "still missing after apt-get: openssl"


def test_describe_is_one_line_naming_the_packages(plan: Any) -> None:
    plan.app.backup_offsite = True
    line = PackagesStep().describe(plan)
    assert "\n" not in line
    assert "fonts-dejavu-core" in line and "awscli" in line
    assert PackagesStep().mandatory and PackagesStep().enabled(plan)

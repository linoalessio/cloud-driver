"""Tests for the Python 3 step (interpreter + venv/pip probe, the 3.11 rule for encryption)."""

from __future__ import annotations

from typing import Any

import pytest

from cloud_driver_installer.engine import Context, StepError, StepStatus
from cloud_driver_installer.steps.python import PYTHON_PACKAGES, VENV_PROBE_COMMAND, PythonStep, parse_python_version, version_tuple

EXPECTED_PROBE = 'd=$(mktemp -d); python3 -m venv "$d/v" >/dev/null 2>&1 && test -x "$d/v/bin/pip"; r=$?; rm -rf "$d"; exit $r'


def test_probe_command_is_exactly_the_contract_one() -> None:
    assert VENV_PROBE_COMMAND == EXPECTED_PROBE


def test_parse_helpers() -> None:
    assert parse_python_version("Python 3.11.2\n") == "3.11.2"
    assert parse_python_version("Python 3.12") == "3.12"
    assert parse_python_version("bash: python3: command not found") == ""
    assert version_tuple("3.11.2") == (3, 11, 2)
    assert version_tuple("") == (0,)
    assert version_tuple("3.10.12") < (3, 11)


def test_check_bare_box_needs_apply(ctx: Context, remote: Any) -> None:
    remote.fail("python3 --version", "bash: python3: command not found", code=127)
    result = PythonStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "no python3 found" in result.detail
    assert ctx.discovered.python_version == ""
    assert ctx.discovered.venv_works is False
    assert not remote.ran("python3 -m venv")
    assert remote.apt_installed == []


def test_check_provisioned_box_is_ok_and_runs_the_exact_probe(ctx: Context, remote: Any) -> None:
    remote.ok("python3 --version", "Python 3.11.2\n")
    remote.ok("python3 -m venv")
    result = PythonStep().check(ctx)
    assert result.status is StepStatus.OK
    assert result.detail == "Python 3.11.2, venv + pip work"
    assert EXPECTED_PROBE in remote.commands
    assert ctx.discovered.python_version == "3.11.2"
    assert ctx.discovered.venv_works is True


def test_check_broken_venv_needs_apply(ctx: Context, remote: Any) -> None:
    remote.ok("python3 --version", "Python 3.11.2\n")
    result = PythonStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "python3-venv" in result.detail
    assert ctx.discovered.venv_works is False


def test_check_refuses_python_310_with_encrypted_intelligence(ctx: Context, remote: Any, plan: Any) -> None:
    remote.ok("python3 --version", "Python 3.10.12\n")
    remote.ok("python3 -m venv")
    plan.intelligence.enabled = True
    plan.intelligence.enable_encryption = True
    with pytest.raises(StepError, match="3.11"):
        PythonStep().check(ctx)


def test_check_accepts_python_310_without_encryption_or_intelligence(ctx: Context, remote: Any, plan: Any) -> None:
    remote.ok("python3 --version", "Python 3.10.12\n")
    remote.ok("python3 -m venv")
    plan.intelligence.enabled = True
    plan.intelligence.enable_encryption = False
    assert PythonStep().check(ctx).status is StepStatus.OK
    plan.intelligence.enabled = False
    plan.intelligence.enable_encryption = True
    assert PythonStep().check(ctx).status is StepStatus.OK


def test_apply_installs_the_three_packages_on_a_bare_box(ctx: Context, remote: Any) -> None:
    remote.on("python3 --version", lambda cmd, inp: (0, "Python 3.11.2\n") if "python3" in remote.apt_installed else (127, "", "not found"))
    remote.on("python3 -m venv", lambda cmd, inp: 0 if "python3-venv" in remote.apt_installed else 1)
    PythonStep().apply(ctx)
    assert remote.apt_installed == list(PYTHON_PACKAGES)
    assert ctx.discovered.python_version == "3.11.2"
    assert ctx.discovered.venv_works is True


def test_apply_falls_back_to_the_versioned_venv_package(ctx: Context, remote: Any) -> None:
    remote.ok("python3 --version", "Python 3.10.12\n")
    remote.on("python3 -m venv", lambda cmd, inp: 0 if "python3.10-venv" in remote.apt_installed else 1)
    PythonStep().apply(ctx)
    assert remote.apt_installed == [*PYTHON_PACKAGES, "python3.10-venv"]
    assert ctx.discovered.venv_works is True


def test_apply_is_a_noop_when_the_probe_passes(ctx: Context, remote: Any) -> None:
    remote.ok("python3 --version", "Python 3.11.2\n")
    remote.ok("python3 -m venv")
    PythonStep().apply(ctx)
    PythonStep().apply(ctx)
    assert remote.apt_installed == []


def test_verify(ctx: Context, remote: Any, plan: Any) -> None:
    remote.ok("python3 --version", "Python 3.11.2\n")
    remote.ok("python3 -m venv")
    result = PythonStep().verify(ctx)
    assert result.ok and result.detail == "Python 3.11.2, venv + pip work"

    remote.rules.clear()
    remote.ok("python3 --version", "Python 3.11.2\n")
    result = PythonStep().verify(ctx)
    assert not result.ok and "bin/pip" in result.detail

    remote.rules.clear()
    remote.fail("python3 --version", "not found", code=127)
    assert not PythonStep().verify(ctx).ok

    remote.rules.clear()
    remote.ok("python3 --version", "Python 3.10.12\n")
    remote.ok("python3 -m venv")
    plan.intelligence.enabled = True
    plan.intelligence.enable_encryption = True
    result = PythonStep().verify(ctx)
    assert not result.ok and "3.11" in result.detail


def test_describe(plan: Any) -> None:
    line = PythonStep().describe(plan)
    assert "\n" not in line and "python3-venv" in line
    assert PythonStep().mandatory and PythonStep().enabled(plan)

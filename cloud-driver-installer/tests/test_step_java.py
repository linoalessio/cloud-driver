"""Tests for the Java 21 step."""

from __future__ import annotations

from typing import Any

from cloud_driver_installer.engine import Context, StepStatus
from cloud_driver_installer.steps.java import ALTERNATIVES_COMMAND, JAVA_PACKAGE, JavaStep, java_major, parse_java_version

JAVA21 = 'openjdk version "21.0.4" 2024-07-16\nOpenJDK Runtime Environment (build 21.0.4+7-Debian-1deb12u1)\nOpenJDK 64-Bit Server VM (build 21.0.4+7-Debian-1deb12u1, mixed mode, sharing)\n'
JAVA17 = 'openjdk version "17.0.12" 2024-07-16\nOpenJDK Runtime Environment (build 17.0.12+7-Debian-2deb12u1)\n'


def test_parse_helpers() -> None:
    assert parse_java_version(JAVA21) == "21.0.4"
    assert parse_java_version('java version "1.8.0_292"') == "1.8.0_292"
    assert parse_java_version("bash: java: command not found") == ""
    assert java_major("21.0.4") == 21
    assert java_major("1.8.0_292") == 8
    assert java_major("17") == 17
    assert java_major("") == 0


def test_check_bare_box_needs_apply(ctx: Context, remote: Any) -> None:
    remote.fail("java -version", "bash: java: command not found", code=127)
    result = JavaStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "no java found" in result.detail
    assert JAVA_PACKAGE in result.detail
    assert ctx.discovered.java_version == ""
    assert remote.apt_installed == []


def test_check_other_java_needs_apply(ctx: Context, remote: Any) -> None:
    remote.ok("java -version", JAVA17)
    result = JavaStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "17.0.12" in result.detail
    assert ctx.discovered.java_version == "17.0.12"


def test_check_provisioned_box_is_ok(ctx: Context, remote: Any) -> None:
    remote.ok("java -version", JAVA21)
    result = JavaStep().check(ctx)
    assert result.status is StepStatus.OK
    assert "21.0.4" in result.detail
    assert ctx.discovered.java_version == "21.0.4"


def test_apply_installs_the_package_on_a_bare_box(ctx: Context, remote: Any) -> None:
    remote.on("java -version", lambda cmd, inp: (0, JAVA21) if JAVA_PACKAGE in remote.apt_installed else (127, "", "not found"))
    JavaStep().apply(ctx)
    assert remote.apt_installed == [JAVA_PACKAGE]
    assert not remote.ran("update-alternatives")
    assert ctx.discovered.java_version == "21.0.4"


def test_apply_switches_the_alternative_when_another_jdk_stays_default(ctx: Context, remote: Any) -> None:
    remote.on("java -version", lambda cmd, inp: (0, JAVA21) if remote.ran("update-alternatives") else (0, JAVA17))
    remote.ok("update-alternatives")
    JavaStep().apply(ctx)
    assert remote.apt_installed == [JAVA_PACKAGE]
    assert remote.ran(ALTERNATIVES_COMMAND)
    assert ctx.discovered.java_version == "21.0.4"


def test_apply_alternatives_failure_only_warns(ctx: Context, remote: Any) -> None:
    remote.ok("java -version", JAVA17)
    remote.ok(f"dpkg-query -W -f='${{Status}}' {JAVA_PACKAGE}", "install ok installed")
    remote.fail("update-alternatives", "update-alternatives: error: alternative /usr/lib/jvm/java-21-openjdk-amd64/bin/java for java not registered")
    JavaStep().apply(ctx)
    assert remote.apt_installed == []
    assert any(level == "WARN" and "update-alternatives" in message for level, message in ctx.captured_log)  # type: ignore[attr-defined]


def test_apply_is_a_noop_when_java_21_is_present(ctx: Context, remote: Any) -> None:
    remote.ok("java -version", JAVA21)
    JavaStep().apply(ctx)
    JavaStep().apply(ctx)
    assert remote.apt_installed == []
    assert not remote.ran("update-alternatives")


def test_verify(ctx: Context, remote: Any) -> None:
    remote.ok("java -version", JAVA21)
    result = JavaStep().verify(ctx)
    assert result.ok and "21.0.4" in result.detail
    remote.rules.clear()
    remote.ok("java -version", JAVA17)
    result = JavaStep().verify(ctx)
    assert not result.ok and "17.0.12" in result.detail
    remote.rules.clear()
    remote.fail("java -version", "not found", code=127)
    assert not JavaStep().verify(ctx).ok


def test_describe(plan: Any) -> None:
    line = JavaStep().describe(plan)
    assert "\n" not in line and JAVA_PACKAGE in line
    assert JavaStep().mandatory

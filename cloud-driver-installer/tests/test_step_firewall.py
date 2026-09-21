"""Tests for the ufw firewall step (rule order, enable, idempotence)."""

from __future__ import annotations

from typing import Any

import pytest

from cloud_driver_installer.engine import Context, StepError, StepStatus
from cloud_driver_installer.steps.firewall import FirewallStep, parse_ufw_status, required_rules

ACTIVE = (
    "Status: active\n"
    "\n"
    "To                         Action      From\n"
    "--                         ------      ----\n"
    "OpenSSH                    ALLOW       Anywhere\n"
    "80/tcp                     ALLOW       Anywhere\n"
    "443/tcp                    ALLOW       Anywhere\n"
    "OpenSSH (v6)               ALLOW       Anywhere (v6)\n"
    "80/tcp (v6)                ALLOW       Anywhere (v6)\n"
    "443/tcp (v6)               ALLOW       Anywhere (v6)\n"
)
INACTIVE = "Status: inactive\n"


def ufw_installed(remote: Any) -> None:
    remote.ok("dpkg-query -W -f='${Status}' ufw", "install ok installed")


def test_enabled_follows_the_plan(plan: Any) -> None:
    plan.server.firewall = True
    assert FirewallStep().enabled(plan)
    plan.server.firewall = False
    assert not FirewallStep().enabled(plan)


def test_required_rules(plan: Any) -> None:
    assert required_rules(plan) == ["OpenSSH", "80/tcp", "443/tcp"]
    plan.server.firewall_extra_ports = "9404/tcp, 8443 443/tcp"
    plan.ssh.port = 2222
    assert required_rules(plan) == ["OpenSSH", "80/tcp", "443/tcp", "2222/tcp", "9404/tcp", "8443"]


def test_parse_ufw_status() -> None:
    active, allowed = parse_ufw_status(ACTIVE)
    assert active and allowed == {"OpenSSH", "80/tcp", "443/tcp"}
    active, allowed = parse_ufw_status(INACTIVE)
    assert not active and allowed == set()
    active, allowed = parse_ufw_status("Status: active\n\nTo   Action   From\n9404/tcp   DENY   Anywhere\n")
    assert active and allowed == set()


def test_check_bare_box_needs_apply(ctx: Context, remote: Any) -> None:
    result = FirewallStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert result.detail == "install ufw, allow OpenSSH, 80/tcp, 443/tcp, enable"
    assert ctx.discovered.ufw_active is None
    assert remote.apt_installed == [] and not remote.ran("ufw allow")


def test_check_installed_but_inactive_needs_apply(ctx: Context, remote: Any) -> None:
    ufw_installed(remote)
    remote.ok("ufw status", INACTIVE)
    result = FirewallStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert result.detail == "allow OpenSSH, 80/tcp, 443/tcp, enable ufw"
    assert ctx.discovered.ufw_active is False


def test_check_active_with_rules_is_ok(ctx: Context, remote: Any) -> None:
    ufw_installed(remote)
    remote.ok("ufw status", ACTIVE)
    result = FirewallStep().check(ctx)
    assert result.status is StepStatus.OK
    assert result.detail == "ufw active, allowing OpenSSH, 80/tcp, 443/tcp"
    assert ctx.discovered.ufw_active is True


def test_check_missing_extra_rule_needs_apply(ctx: Context, remote: Any, plan: Any) -> None:
    ufw_installed(remote)
    remote.ok("ufw status", ACTIVE)
    plan.server.firewall_extra_ports = "9404/tcp"
    result = FirewallStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert result.detail == "allow 9404/tcp"


def test_apply_bare_box_allows_ssh_first_and_enables_last(ctx: Context, remote: Any) -> None:
    remote.ok("ufw status", INACTIVE)
    remote.ok("ufw allow")
    remote.ok("ufw default")
    remote.ok("ufw --force enable")
    FirewallStep().apply(ctx)
    assert remote.apt_installed == ["ufw"]
    ufw = [cmd for cmd in remote.commands if cmd.startswith("ufw ") and cmd != "ufw status"]
    assert ufw == [
        "ufw allow OpenSSH",
        "ufw allow 80/tcp",
        "ufw allow 443/tcp",
        "ufw default deny incoming",
        "ufw default allow outgoing",
        "ufw --force enable",
    ]
    assert ctx.discovered.ufw_active is True


def test_apply_allows_a_non_standard_ssh_port_and_extras(ctx: Context, remote: Any, plan: Any) -> None:
    plan.ssh.port = 2222
    plan.server.firewall_extra_ports = "9404/tcp"
    ufw_installed(remote)
    remote.ok("ufw status", INACTIVE)
    remote.ok("ufw")
    FirewallStep().apply(ctx)
    allows = [cmd for cmd in remote.commands if cmd.startswith("ufw allow")]
    assert allows == ["ufw allow OpenSSH", "ufw allow 80/tcp", "ufw allow 443/tcp", "ufw allow 2222/tcp", "ufw allow 9404/tcp"]
    assert remote.commands.index("ufw allow 2222/tcp") < remote.commands.index("ufw --force enable")


def test_apply_is_idempotent_when_active(ctx: Context, remote: Any) -> None:
    ufw_installed(remote)
    remote.ok("ufw status", ACTIVE)
    remote.ok("ufw default")
    FirewallStep().apply(ctx)
    FirewallStep().apply(ctx)
    assert remote.apt_installed == []
    assert not remote.ran("ufw allow")
    assert not remote.ran("ufw --force enable")


def test_apply_adds_only_the_missing_rule_when_active(ctx: Context, remote: Any, plan: Any) -> None:
    plan.server.firewall_extra_ports = "9404/tcp"
    ufw_installed(remote)
    remote.ok("ufw status", ACTIVE)
    remote.ok("ufw allow")
    remote.ok("ufw default")
    FirewallStep().apply(ctx)
    assert [cmd for cmd in remote.commands if cmd.startswith("ufw allow")] == ["ufw allow 9404/tcp"]
    assert not remote.ran("ufw --force enable")


def test_apply_failure_is_a_step_error(ctx: Context, remote: Any) -> None:
    ufw_installed(remote)
    remote.ok("ufw status", INACTIVE)
    remote.fail("ufw allow", "ERROR: Could not find a profile matching 'OpenSSH'")
    with pytest.raises(StepError, match="ufw configuration failed"):
        FirewallStep().apply(ctx)
    assert not remote.ran("ufw --force enable")


def test_verify(ctx: Context, remote: Any, plan: Any) -> None:
    remote.ok("ufw status", ACTIVE)
    result = FirewallStep().verify(ctx)
    assert result.ok and result.detail == "ufw active, allowing OpenSSH, 80/tcp, 443/tcp"

    plan.server.firewall_extra_ports = "9404/tcp"
    result = FirewallStep().verify(ctx)
    assert not result.ok and "9404/tcp" in result.detail

    remote.rules.clear()
    remote.ok("ufw status", INACTIVE)
    result = FirewallStep().verify(ctx)
    assert not result.ok and "active" in result.detail


def test_describe(plan: Any) -> None:
    plan.server.firewall_extra_ports = "9404/tcp"
    line = FirewallStep().describe(plan)
    assert "\n" not in line
    assert "OpenSSH, 80/tcp, 443/tcp, 9404/tcp" in line and "deny incoming" in line

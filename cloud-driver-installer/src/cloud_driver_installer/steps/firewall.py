"""The firewall step: ``ufw`` allowing SSH, 80 and 443 (plus the operator's extras), default deny.

Mirrors ``shell/provision-root-server.sh`` step 3, including its one safety-critical ordering:
the SSH rule is added *before* the firewall is enabled, otherwise the session that is doing the
install is the first thing cut off. When the SSH login uses a port other than 22 that port is
allowed as well, because the ``OpenSSH`` application profile only covers 22/tcp.
"""

from __future__ import annotations

import re
import shlex

from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError

#: The rules every deployment needs, in the order they are added (SSH first).
BASE_RULES: tuple[str, ...] = ("OpenSSH", "80/tcp", "443/tcp")

STATUS_COMMAND = "ufw status"
ENABLE_COMMAND = "ufw --force enable"
DEFAULT_COMMANDS: tuple[str, ...] = ("ufw default deny incoming", "ufw default allow outgoing")

_RULE_LINE_RE = re.compile(r"^(?P<to>\S.*?)\s{2,}(?P<action>ALLOW|DENY|REJECT|LIMIT)(?:\s+(?:IN|OUT|FWD))?\s{2,}(?P<from>.+)$")


def required_rules(plan: InstallPlan) -> list[str]:
    """``OpenSSH, 80/tcp, 443/tcp`` plus a non-standard SSH port and the plan's extra ports, deduplicated."""
    rules = list(BASE_RULES)
    if plan.ssh.port and plan.ssh.port != 22:
        rules.append(f"{plan.ssh.port}/tcp")
    for extra in re.split(r"[,\s]+", plan.server.firewall_extra_ports or ""):
        if extra:
            rules.append(extra)
    seen: list[str] = []
    for rule in rules:
        if rule not in seen:
            seen.append(rule)
    return seen


def parse_ufw_status(text: str) -> tuple[bool, set[str]]:
    """``(active, {rule names with an ALLOW action})`` from ``ufw status`` output.

    The ``(v6)`` twins are folded into their IPv4 name so a rule counts once.
    """
    active = False
    allowed: set[str] = set()
    for line in (text or "").splitlines():
        stripped = line.strip()
        if stripped.lower().startswith("status:"):
            active = stripped.split(":", 1)[1].strip().lower() == "active"
            continue
        match = _RULE_LINE_RE.match(stripped)
        if match and match.group("action") == "ALLOW":
            allowed.add(match.group("to").replace(" (v6)", "").strip())
    return active, allowed


class FirewallStep(Step):
    """Installs and enables ufw with the required allow rules."""

    id = "firewall"
    title = "Firewall (ufw)"

    def enabled(self, plan: InstallPlan) -> bool:
        """Only when the plan wants the host firewall."""
        return bool(plan.server.firewall)

    def describe(self, plan: InstallPlan) -> str:
        """One line for the summary page."""
        return f"install ufw, allow {', '.join(required_rules(plan))}, default deny incoming / allow outgoing, enable"

    # --- phases ----------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """ufw installed, active and every required rule present -> OK."""
        rules = required_rules(ctx.plan)
        if not ctx.remote.dpkg_installed("ufw"):
            ctx.discovered.ufw_active = None
            return CheckResult.needs_apply(f"install ufw, allow {', '.join(rules)}, enable")
        active, allowed = self._status(ctx)
        ctx.discovered.ufw_active = active
        missing = [rule for rule in rules if rule not in allowed]
        if active and not missing:
            return CheckResult.ok(f"ufw active, allowing {', '.join(rules)}")
        parts: list[str] = []
        if missing:
            parts.append(f"allow {', '.join(missing)}")
        if not active:
            parts.append("enable ufw")
        return CheckResult.needs_apply(", ".join(parts))

    def apply(self, ctx: Context) -> None:
        """Add the missing rules (SSH first), set the defaults, then enable if inactive."""
        rules = required_rules(ctx.plan)
        try:
            if not ctx.remote.dpkg_installed("ufw"):
                ctx.info("installing ufw")
                ctx.remote.apt_install(["ufw"])
            ctx.check_cancelled()
            active, allowed = self._status(ctx)
            for rule in rules:
                if rule in allowed and active:
                    continue
                ctx.remote.run(f"ufw allow {shlex.quote(rule)}", check=True)
                ctx.info(f"ufw allow {rule}")
                ctx.check_cancelled()
            for command in DEFAULT_COMMANDS:
                ctx.remote.run(command, check=True)
            if not active:
                ctx.remote.run(ENABLE_COMMAND, check=True)
                ctx.info("ufw enabled (default deny incoming, allow outgoing)")
            else:
                ctx.debug("ufw already active - rules refreshed, not re-enabled")
        except RemoteError as exc:
            raise StepError(f"ufw configuration failed: {exc}") from exc
        ctx.discovered.ufw_active = True

    def verify(self, ctx: Context) -> VerifyResult:
        """``ufw status`` must say active and list every rule."""
        rules = required_rules(ctx.plan)
        active, allowed = self._status(ctx)
        ctx.discovered.ufw_active = active
        missing = [rule for rule in rules if rule not in allowed]
        if not active:
            return VerifyResult(False, "ufw status does not report active")
        if missing:
            return VerifyResult(False, f"ufw active but rules missing: {', '.join(missing)}")
        return VerifyResult(True, f"ufw active, allowing {', '.join(rules)}")

    # --- helpers ---------------------------------------------------------------------------------

    @staticmethod
    def _status(ctx: Context) -> tuple[bool, set[str]]:
        result = ctx.remote.run(STATUS_COMMAND, quiet=True)
        return parse_ufw_status(result.out) if result.ok else (False, set())

"""The ordered step catalog.

The order is the order the server needs: OS basics first, then the data stores and daemons, then
everything AWS-side (which runs on the operator's machine and needs nothing from the server), then
the reverse proxy, the config files that reference all of the above, the application itself, the
optional intelligence service, and a final end-to-end probe.

Step ids are stable: profiles, the GUI sidebar and ``depends_on`` declarations all use them.
"""

from __future__ import annotations

from cloud_driver_installer.engine import Step

#: ``(id, title)`` in run order - the single source of truth for the sidebar.
STEP_ORDER: tuple[tuple[str, str], ...] = (
    ("server", "Server"),
    ("packages", "Base packages"),
    ("java", "Java 21"),
    ("python", "Python 3"),
    ("postgres", "PostgreSQL"),
    ("redis", "Redis"),
    ("clamav", "ClamAV"),
    ("firewall", "Firewall (ufw)"),
    ("swap", "Swap"),
    ("aws", "AWS"),
    ("email", "E-mail"),
    ("caddy", "Reverse proxy (Caddy)"),
    ("config", "Configuration files"),
    ("application", "Application"),
    ("intelligence", "Intelligence service"),
    ("smoke", "Smoke test"),
)


def all_steps() -> list[Step]:
    """Instantiate every step in run order (imported lazily so a GUI can list ids without them)."""
    from cloud_driver_installer.steps.application import ApplicationStep
    from cloud_driver_installer.steps.aws import AwsStep
    from cloud_driver_installer.steps.caddy import CaddyStep
    from cloud_driver_installer.steps.clamav import ClamAvStep
    from cloud_driver_installer.steps.config import ConfigStep
    from cloud_driver_installer.steps.email import EmailStep
    from cloud_driver_installer.steps.firewall import FirewallStep
    from cloud_driver_installer.steps.intelligence import IntelligenceStep
    from cloud_driver_installer.steps.java import JavaStep
    from cloud_driver_installer.steps.packages import PackagesStep
    from cloud_driver_installer.steps.postgres import PostgresStep
    from cloud_driver_installer.steps.python import PythonStep
    from cloud_driver_installer.steps.redis import RedisStep
    from cloud_driver_installer.steps.server import ServerStep
    from cloud_driver_installer.steps.smoke import SmokeStep
    from cloud_driver_installer.steps.swap import SwapStep

    steps: list[Step] = [
        ServerStep(),
        PackagesStep(),
        JavaStep(),
        PythonStep(),
        PostgresStep(),
        RedisStep(),
        ClamAvStep(),
        FirewallStep(),
        SwapStep(),
        AwsStep(),
        EmailStep(),
        CaddyStep(),
        ConfigStep(),
        ApplicationStep(),
        IntelligenceStep(),
        SmokeStep(),
    ]
    expected = [step_id for step_id, _ in STEP_ORDER]
    actual = [step.id for step in steps]
    if actual != expected:
        raise RuntimeError(f"step catalog out of order: {actual} != {expected}")
    return steps

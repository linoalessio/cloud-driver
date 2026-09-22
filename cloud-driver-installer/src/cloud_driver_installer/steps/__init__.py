"""The ordered step catalog.

The order is the order the server needs: OS basics first, then the data stores and daemons, then
everything AWS-side (which runs on the operator's machine), then the reverse proxy, the config
files that reference all of the above, the application itself, the optional intelligence service,
and a final end-to-end probe.

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

#: Every step id, in run order.
STEP_IDS: tuple[str, ...] = tuple(step_id for step_id, _ in STEP_ORDER)


def all_steps() -> list[Step]:
    """Instantiate every step in run order."""
    from cloud_driver_installer.steps.application import ApplicationStep, ConfigStep, SmokeStep
    from cloud_driver_installer.steps.awsresources import AwsStep, EmailStep
    from cloud_driver_installer.steps.daemons import CaddyStep, ClamAvStep
    from cloud_driver_installer.steps.datastores import PostgresStep, RedisStep
    from cloud_driver_installer.steps.intelligence import IntelligenceStep
    from cloud_driver_installer.steps.system import FirewallStep, JavaStep, PackagesStep, PythonStep, ServerStep, SwapStep

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
    actual = [step.id for step in steps]
    if actual != list(STEP_IDS):
        raise RuntimeError(f"step catalog out of order: {actual} != {list(STEP_IDS)}")
    return steps

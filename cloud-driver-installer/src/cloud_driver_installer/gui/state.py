"""The window's shared state: the plan, what the server looks like, and every step's status.

Deliberately free of tkinter so the worker thread and the tests can use it without a display.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING

from cloud_driver_installer.engine import StepStatus
from cloud_driver_installer.model import Discovered, GeneratedSecrets, InstallPlan, default_plan
from cloud_driver_installer.credentials import Redactor
from cloud_driver_installer.steps import STEP_IDS, STEP_ORDER

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.remote import RemoteHost
    from cloud_driver_installer.ssh import SshSession

#: Flags that make a run destructive; the summary lists them and demands a confirmation.
DESTRUCTIVE_FLAGS: tuple[tuple[str, str], ...] = (
    ("postgres.rotate", "rotate the PostgreSQL password"),
    ("redis.rotate", "rotate the Redis password"),
    ("app.jwt_rotate", "rotate the JWT signing key (logs every client out until it refreshes)"),
    ("intelligence.secret_rotate", "rotate the semantic-search shared secret"),
    ("aws.rotate_server_key", "rotate the server's AWS access key"),
    ("aws.allow_kms_change", "replace the KMS key this server's data was encrypted with"),
    ("aws.allow_disable_s3", "disable S3 although the server stores content there"),
)


@dataclass
class AppState:
    """Everything the window renders and the worker acts on."""

    plan: InstallPlan = field(default_factory=default_plan)
    discovered: Discovered = field(default_factory=Discovered)
    secrets: GeneratedSecrets = field(default_factory=GeneratedSecrets)
    redactor: Redactor = field(default_factory=Redactor)
    included: set[str] = field(default_factory=lambda: set(STEP_IDS))
    statuses: dict[str, StepStatus] = field(default_factory=lambda: {step_id: StepStatus.PENDING for step_id in STEP_IDS})
    details: dict[str, str] = field(default_factory=lambda: {step_id: "" for step_id in STEP_IDS})
    elapsed: dict[str, float] = field(default_factory=dict)
    profile_path: Path | None = None
    session: "SshSession | None" = None
    remote: "RemoteHost | None" = None
    acknowledged: bool = False

    @property
    def titles(self) -> dict[str, str]:
        """``step id -> sidebar title``."""
        return dict(STEP_ORDER)

    def title_of(self, step_id: str) -> str:
        """The sidebar title of one step."""
        return dict(STEP_ORDER).get(step_id, step_id)

    def dependency_reasons(self) -> dict[str, str]:
        """Why a step cannot run: its feature is off, or a step it needs is not selected."""
        from cloud_driver_installer.steps import all_steps

        reasons: dict[str, str] = {}
        chosen: set[str] = set()
        for step in all_steps():
            if not step.enabled(self.plan):
                reasons[step.id] = "not part of this plan"
                continue
            if step.id not in self.included and not step.mandatory:
                reasons[step.id] = "not selected"
                continue
            missing = [dep for dep in step.depends_on if dep in reasons or dep not in chosen]
            if missing:
                reasons[step.id] = "needs " + ", ".join(self.title_of(dep) for dep in missing)
                continue
            chosen.add(step.id)
        return reasons

    def selected_count(self) -> tuple[int, int]:
        """``(steps that will run, steps in total)``."""
        reasons = self.dependency_reasons()
        return len(STEP_IDS) - len(reasons), len(STEP_IDS)

    def destructive_actions(self) -> list[str]:
        """Everything in this plan that changes something already in use."""
        actions: list[str] = []
        for path, text in DESTRUCTIVE_FLAGS:
            group, _, attribute = path.partition(".")
            if getattr(getattr(self.plan, group), attribute, False):
                actions.append(text)
        if self.plan.app.start_after_deploy and self.discovered.screen_running:
            actions.append(f"restart the running JVM (screen session '{self.plan.server.screen_session}')")
        if self.plan.app.deploy_jars and self.discovered.existing_jars:
            actions.append("replace the jars currently deployed (superseded versions are removed)")
        return actions

    def register_secrets(self) -> None:
        """Teach the redactor every secret this run knows, so no log line can leak one."""
        self.redactor.add_all(self.secrets.all_values())
        self.redactor.add_all(
            [
                self.plan.postgres.password,
                self.plan.redis.password,
                self.plan.email.smtp_password,
                self.plan.aws.secret_access_key,
                self.plan.aws.session_token,
                self.plan.ssh.password,
                self.plan.ssh.key_passphrase,
            ]
        )

    def reset_statuses(self) -> None:
        """Back to "not checked yet" - used after loading a profile."""
        for step_id in STEP_IDS:
            self.statuses[step_id] = StepStatus.PENDING
            self.details[step_id] = ""
        self.elapsed.clear()

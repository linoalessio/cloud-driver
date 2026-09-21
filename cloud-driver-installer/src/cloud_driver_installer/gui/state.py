"""Everything the window shows, in one display-free object.

:class:`AppState` is the single place the GUI keeps the plan being edited, the facts discovered on
the server, the secrets a run produced, the operator's step selection and the last known status
of every step. It has no tkinter import on purpose: the worker thread, the pages and the tests all
share it, and the tests drive it without a display.

The step catalog is loaded lazily through :func:`cloud_driver_installer.steps.all_steps` the first
time something needs a step's ``enabled``/``depends_on``/``mandatory`` declaration, so importing
this module never pulls in the step implementations.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Iterable

from cloud_driver_installer.engine import Step, StepEvent, StepStatus
from cloud_driver_installer.model import Discovered, GeneratedSecrets, InstallPlan, default_plan
from cloud_driver_installer.secrets import Redactor
from cloud_driver_installer.steps import STEP_ORDER

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.remote import Remote
    from cloud_driver_installer.ssh import SshSession

#: ``step_id -> sidebar title`` straight from :data:`~cloud_driver_installer.steps.STEP_ORDER`.
STEP_TITLES: dict[str, str] = dict(STEP_ORDER)

#: Every step id in run order.
STEP_IDS: tuple[str, ...] = tuple(step_id for step_id, _ in STEP_ORDER)

#: Reason text used when a step's ``enabled(plan)`` is false (same wording as the engine).
REASON_DISABLED = "disabled in the plan"

#: Reason text used when the operator unticked a step (same wording as the engine).
REASON_NOT_SELECTED = "not selected"


def step_title(step_id: str) -> str:
    """The sidebar title for ``step_id`` (the id itself when unknown, e.g. ``"summary"``)."""
    return STEP_TITLES.get(step_id, step_id)


def _all_ids() -> set[str]:
    return set(STEP_IDS)


def _pending() -> dict[str, StepStatus]:
    return {step_id: StepStatus.PENDING for step_id in STEP_IDS}


def _empty_details() -> dict[str, str]:
    return {step_id: "" for step_id in STEP_IDS}


def _zero_elapsed() -> dict[str, float]:
    return {step_id: 0.0 for step_id in STEP_IDS}


@dataclass
class AppState:
    """The shared state of one installer session (one connection, one plan).

    ``statuses``/``details``/``elapsed`` mirror what the engine's :class:`Runner` last reported;
    the GUI feeds every :class:`StepEvent` it drains from the worker into :meth:`record` so the
    sidebar, the pages and the summary render from one source of truth.
    """

    plan: InstallPlan
    discovered: Discovered = field(default_factory=Discovered)
    secrets: GeneratedSecrets = field(default_factory=GeneratedSecrets)
    redactor: Redactor = field(default_factory=Redactor)
    #: Step ids the operator ticked in the sidebar (default: every step).
    included: set[str] = field(default_factory=_all_ids)
    statuses: dict[str, StepStatus] = field(default_factory=_pending)
    details: dict[str, str] = field(default_factory=_empty_details)
    elapsed: dict[str, float] = field(default_factory=_zero_elapsed)
    profile_path: Path | None = None
    session: "SshSession | None" = None
    #: The connected server handle (a :class:`~cloud_driver_installer.remote.RemoteHost` in the
    #: app, the scripted fake in tests - anything implementing the ``Remote`` protocol).
    remote: "Remote | None" = None
    _steps: list[Step] | None = field(default=None, init=False, repr=False)

    # --- construction ----------------------------------------------------------------------------

    @classmethod
    def new(cls, plan: InstallPlan | None = None) -> "AppState":
        """A fresh state around ``plan`` (default: :func:`~cloud_driver_installer.model.default_plan`)."""
        return cls(plan=plan if plan is not None else default_plan())

    # --- step catalog ----------------------------------------------------------------------------

    def step_catalog(self) -> list[Step]:
        """The instantiated steps in run order, loaded on first use and cached on this state."""
        if self._steps is None:
            from cloud_driver_installer import steps as catalog  # lazy: the step modules are heavy

            self._steps = catalog.all_steps()
        return self._steps

    def use_steps(self, steps: Iterable[Step]) -> None:
        """Replace the cached catalog (tests hand in tiny fake steps; the worker shares them)."""
        self._steps = list(steps)

    def step(self, step_id: str) -> Step:
        """The step object for ``step_id``; ``KeyError`` when the catalog has no such step."""
        for step in self.step_catalog():
            if step.id == step_id:
                return step
        raise KeyError(step_id)

    # --- selection -------------------------------------------------------------------------------

    def set_included(self, step_id: str, included: bool) -> None:
        """Tick or untick a step; mandatory steps stay ticked whatever ``included`` says."""
        if not included:
            try:
                if self.step(step_id).mandatory:
                    self.included.add(step_id)
                    return
            except KeyError:
                pass
            self.included.discard(step_id)
        else:
            self.included.add(step_id)

    def dependency_reasons(self) -> dict[str, str]:
        """Why each excluded step will be skipped, keyed by step id (steps that will run are absent).

        Same rules, same wording as :meth:`Runner.effective_selection`, evaluated against the
        current plan and selection without needing a :class:`Context`:

        * ``enabled(plan)`` is false -> ``"disabled in the plan"``
        * not ticked (and not mandatory) -> ``"not selected"``
        * a ``depends_on`` step will not run -> ``"needs <title>[, <title>]"``
        """
        reasons: dict[str, str] = {}
        chosen: set[str] = set()
        by_id = {step.id: step for step in self.step_catalog()}
        for step in self.step_catalog():
            if not step.enabled(self.plan):
                reasons[step.id] = REASON_DISABLED
                continue
            if step.id not in self.included and not step.mandatory:
                reasons[step.id] = REASON_NOT_SELECTED
                continue
            missing = [dep for dep in step.depends_on if dep not in chosen and dep in by_id]
            if missing:
                reasons[step.id] = "needs " + ", ".join(by_id[dep].title for dep in missing)
                continue
            chosen.add(step.id)
        return reasons

    def selected_steps(self) -> list[Step]:
        """The steps that would run right now, in order (the complement of :meth:`dependency_reasons`)."""
        reasons = self.dependency_reasons()
        return [step for step in self.step_catalog() if step.id not in reasons]

    # --- statuses --------------------------------------------------------------------------------

    def record(self, event: StepEvent) -> None:
        """Fold one engine event into ``statuses``/``details``/``elapsed``."""
        self.statuses[event.step_id] = event.status
        self.details[event.step_id] = event.detail
        if event.elapsed:
            self.elapsed[event.step_id] = event.elapsed

    def reset_statuses(self, step_ids: Iterable[str] | None = None) -> None:
        """Back to ``PENDING`` with no detail (all steps, or just ``step_ids``)."""
        for step_id in step_ids if step_ids is not None else STEP_IDS:
            self.statuses[step_id] = StepStatus.PENDING
            self.details[step_id] = ""
            self.elapsed[step_id] = 0.0

    def status_of(self, step_id: str) -> StepStatus:
        """The last known status (``PENDING`` for anything never reported)."""
        return self.statuses.get(step_id, StepStatus.PENDING)

    def count_by_status(self) -> dict[StepStatus, int]:
        """How many steps are in each status (for the status bar / summary pill)."""
        counts: dict[StepStatus, int] = {}
        for status in self.statuses.values():
            counts[status] = counts.get(status, 0) + 1
        return counts

    # --- secrets ---------------------------------------------------------------------------------

    def secret_values(self) -> list[str]:
        """Every non-blank secret currently known: typed into the plan or produced by a run."""
        plan = self.plan
        candidates = [
            plan.ssh.password,
            plan.ssh.key_passphrase,
            plan.postgres.password,
            plan.redis.password,
            plan.aws.secret_access_key,
            plan.aws.session_token,
            plan.email.smtp_password,
            *self.secrets.all_values(),
        ]
        seen: set[str] = set()
        values: list[str] = []
        for value in candidates:
            if value and value not in seen:
                seen.add(value)
                values.append(value)
        return values

    def register_secrets(self) -> None:
        """Make sure the redactor masks every value :meth:`secret_values` knows."""
        self.redactor.add_all(self.secret_values())

    # --- connection ------------------------------------------------------------------------------

    @property
    def connected(self) -> bool:
        """True while an SSH session is attached and still active."""
        return self.session is not None and self.session.connected

    def connection_label(self) -> str:
        """``root@host`` (or ``"not connected"``) for the title bar and status bar."""
        if self.session is None:
            return "not connected"
        return self.session.target.label()

    def window_title(self) -> str:
        """``cloud-driver installer — root@host · <discovered summary>``."""
        parts = ["cloud-driver installer", self.connection_label()]
        summary = self.discovered.summary()
        title = " — ".join(parts)
        return f"{title} · {summary}" if summary else title

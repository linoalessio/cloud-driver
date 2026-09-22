"""The step engine: a fixed, ordered list of idempotent steps and a runner that drives them.

A :class:`Step` has three phases and the runner always calls them in this order:

``check``
    Read-only. Looks at the server (or AWS) and reports :attr:`StepStatus.OK` (already
    satisfied - nothing to do) or :attr:`StepStatus.NEEDS_APPLY`, with a one-line human detail
    ("PostgreSQL 15 installed, role exists, password kept"). Never changes anything.
``apply``
    Makes the change. Must be safe to call when ``check`` said OK (it may simply return), and
    safe to call twice.
``verify``
    Probes the real thing afterwards (a ``psql`` login, a Redis ``PING``, the API answering)
    instead of trusting ``apply``'s own exit codes.

The runner publishes :class:`StepEvent` objects through a callback so a GUI (or a headless
driver) can render progress without the engine knowing about either.
"""

from __future__ import annotations

import time
import traceback
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import TYPE_CHECKING, Callable, Iterable

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.aws import AwsProvisioner
    from cloud_driver_installer.model import Discovered, GeneratedSecrets, InstallPlan
    from cloud_driver_installer.remote import Remote
    from cloud_driver_installer.credentials import Redactor


class StepStatus(Enum):
    """Where a step is in its lifecycle; the GUI maps these to the six sidebar dots."""

    PENDING = "pending"        # not checked yet
    CHECKING = "checking"
    OK = "ok"                  # check found nothing to do
    NEEDS_APPLY = "needs_apply"
    RUNNING = "running"
    DONE = "done"              # applied and verified
    FAILED = "failed"
    SKIPPED = "skipped"        # excluded by the operator or by an unmet dependency


@dataclass
class CheckResult:
    """Outcome of :meth:`Step.check`."""

    status: StepStatus  # OK or NEEDS_APPLY
    detail: str = ""

    @staticmethod
    def ok(detail: str = "") -> "CheckResult":
        return CheckResult(StepStatus.OK, detail)

    @staticmethod
    def needs_apply(detail: str = "") -> "CheckResult":
        return CheckResult(StepStatus.NEEDS_APPLY, detail)


@dataclass
class VerifyResult:
    """Outcome of :meth:`Step.verify`."""

    ok: bool
    detail: str = ""


class StepError(Exception):
    """A step could not complete; the message is shown to the operator as-is."""


class Cancelled(Exception):
    """Raised inside a step when the operator pressed Stop."""


@dataclass
class StepEvent:
    """One progress notification."""

    step_id: str
    status: StepStatus
    detail: str = ""
    elapsed: float = 0.0


LogFn = Callable[[str, str], None]


@dataclass
class Context:
    """Everything a step may touch. Built once per run by the GUI worker."""

    plan: "InstallPlan"
    discovered: "Discovered"
    secrets: "GeneratedSecrets"
    remote: "Remote"
    redactor: "Redactor"
    log_fn: LogFn
    aws_factory: Callable[[], "AwsProvisioner"] | None = None
    cancel_flag: Callable[[], bool] = lambda: False
    progress_fn: Callable[[float, str], None] = lambda fraction, text: None
    _aws: "AwsProvisioner | None" = field(default=None, init=False, repr=False)

    def log(self, level: str, message: str) -> None:
        """Log ``message`` (redacted) at ``level`` (DEBUG/INFO/WARN/ERROR/OK)."""
        self.log_fn(level, self.redactor.redact(message))

    def info(self, message: str) -> None:
        self.log("INFO", message)

    def warn(self, message: str) -> None:
        self.log("WARN", message)

    def debug(self, message: str) -> None:
        self.log("DEBUG", message)

    def progress(self, fraction: float, text: str = "") -> None:
        """Report sub-step progress in ``[0, 1]`` (jar uploads, pip installs)."""
        self.progress_fn(max(0.0, min(1.0, fraction)), text)

    def check_cancelled(self) -> None:
        """Raise :class:`Cancelled` when the operator asked to stop."""
        if self.cancel_flag():
            raise Cancelled()

    @property
    def aws(self) -> "AwsProvisioner":
        """The lazily created AWS client bundle (built from the plan's credentials)."""
        if self._aws is None:
            if self.aws_factory is None:
                raise StepError("AWS access is not configured")
            self._aws = self.aws_factory()
        return self._aws

    def remember_secret(self, value: str | None) -> None:
        """Register a value so it is masked in every later log line."""
        self.redactor.add(value)


class Step(ABC):
    """Base class for every installer step; subclasses live under :mod:`cloud_driver_installer.steps`."""

    #: Stable identifier, also the profile/GUI key.
    id: str = ""
    #: Sidebar title.
    title: str = ""
    #: Step ids that must be included (and succeed) before this one may run.
    depends_on: tuple[str, ...] = ()
    #: Steps that cannot be excluded by the operator.
    mandatory: bool = False
    #: Whether :meth:`remove` is implemented - the GUI offers the button only for these.
    removable: bool = False

    def enabled(self, plan: "InstallPlan") -> bool:
        """Whether the plan wants this step at all (a disabled feature makes its step SKIPPED)."""
        return True

    @abstractmethod
    def check(self, ctx: Context) -> CheckResult:
        """Read-only state inspection."""

    @abstractmethod
    def apply(self, ctx: Context) -> None:
        """Make the change (idempotent)."""

    def verify(self, ctx: Context) -> VerifyResult:
        """Probe the result; default re-runs :meth:`check` and requires OK."""
        result = self.check(ctx)
        return VerifyResult(result.status is StepStatus.OK, result.detail)

    def describe(self, plan: "InstallPlan") -> str:
        """One line for the summary page: what ``apply`` will do with this plan."""
        return self.title

    # --- removal ---------------------------------------------------------------------------------

    def remove(self, ctx: Context) -> None:
        """Undo :meth:`apply`: delete everything this step put on the server.

        Only called for steps whose :attr:`removable` is true. Like ``apply`` it must be
        idempotent - removing twice, or removing something that was never installed, is a no-op,
        not an error - and it must never take anything with it that the step did not create.
        """
        raise StepError(f"{self.title} cannot be removed")

    def describe_removal(self, plan: "InstallPlan") -> str:
        """Exactly what :meth:`remove` will delete, shown in the confirmation dialog.

        This is the operator's last warning before data is gone, so it names the packages, files
        and directories, and says what removal cannot reach (AWS resources, an external database's
        role) instead of implying it did.
        """
        return f"remove everything the {self.title} step installed"


class Runner:
    """Drives an ordered list of steps and reports :class:`StepEvent` objects.

    The runner keeps the last known status and detail per step so the GUI can render the sidebar
    from a single source of truth.
    """

    def __init__(self, steps: Iterable[Step], on_event: Callable[[StepEvent], None]) -> None:
        self.steps: list[Step] = list(steps)
        self.by_id: dict[str, Step] = {step.id: step for step in self.steps}
        self.status: dict[str, StepStatus] = {step.id: StepStatus.PENDING for step in self.steps}
        self.detail: dict[str, str] = {step.id: "" for step in self.steps}
        self._on_event = on_event

    # --- reporting -------------------------------------------------------------------------------

    def _set(self, step: Step, status: StepStatus, detail: str = "", elapsed: float = 0.0) -> None:
        self.status[step.id] = status
        if detail:
            self.detail[step.id] = detail
        self._on_event(StepEvent(step.id, status, self.detail[step.id], elapsed))

    # --- selection -------------------------------------------------------------------------------

    def effective_selection(self, ctx: Context, included: set[str]) -> tuple[list[Step], dict[str, str]]:
        """Resolve ``included`` against ``enabled()`` and ``depends_on``.

        Returns the ordered steps to run and, per excluded step, the reason it is skipped.
        """
        selected: list[Step] = []
        reasons: dict[str, str] = {}
        chosen: set[str] = set()
        for step in self.steps:
            if not step.enabled(ctx.plan):
                reasons[step.id] = "disabled in the plan"
                continue
            if step.id not in included and not step.mandatory:
                reasons[step.id] = "not selected"
                continue
            missing = [dep for dep in step.depends_on if dep not in chosen and dep in self.by_id]
            if missing:
                names = ", ".join(self.by_id[dep].title for dep in missing)
                reasons[step.id] = f"needs {names}"
                continue
            selected.append(step)
            chosen.add(step.id)
        return selected, reasons

    # --- phases ----------------------------------------------------------------------------------

    def check_all(self, ctx: Context, steps: Iterable[Step] | None = None) -> None:
        """Run ``check`` for every step (or ``steps``), stopping on cancel."""
        for step in steps if steps is not None else self.steps:
            ctx.check_cancelled()
            if not step.enabled(ctx.plan):
                self._set(step, StepStatus.SKIPPED, "disabled in the plan")
                continue
            self.check_one(ctx, step)

    def check_one(self, ctx: Context, step: Step) -> CheckResult:
        """Run one step's ``check`` and record the outcome."""
        self._set(step, StepStatus.CHECKING)
        started = time.monotonic()
        try:
            result = step.check(ctx)
        except Cancelled:
            self._set(step, StepStatus.PENDING, "cancelled")
            raise
        except Exception as exc:  # noqa: BLE001 - every failure must land in the GUI
            ctx.log("ERROR", f"[{step.title}] check failed: {exc}")
            ctx.debug(traceback.format_exc())
            self._set(step, StepStatus.FAILED, f"check failed: {exc}", time.monotonic() - started)
            return CheckResult(StepStatus.NEEDS_APPLY, f"check failed: {exc}")
        ctx.log("INFO", f"[{step.title}] {result.detail}" if result.detail else f"[{step.title}] checked")
        self._set(step, result.status, result.detail, time.monotonic() - started)
        return result

    def run_one(self, ctx: Context, step: Step) -> bool:
        """check -> apply (if needed) -> verify for one step; returns success."""
        started = time.monotonic()
        try:
            check = self.check_one(ctx, step)
            if self.status[step.id] is StepStatus.FAILED:
                return False
            if check.status is StepStatus.OK:
                self._set(step, StepStatus.DONE, check.detail or "already satisfied", time.monotonic() - started)
                return True
            self._set(step, StepStatus.RUNNING, check.detail)
            ctx.log("INFO", f"==> {step.title}")
            step.apply(ctx)
            ctx.check_cancelled()
            verify = step.verify(ctx)
            if not verify.ok:
                ctx.log("ERROR", f"[{step.title}] verification failed: {verify.detail}")
                self._set(step, StepStatus.FAILED, f"verification failed: {verify.detail}", time.monotonic() - started)
                return False
            ctx.log("OK", f"[{step.title}] {verify.detail or 'done'}")
            self._set(step, StepStatus.DONE, verify.detail or "done", time.monotonic() - started)
            return True
        except Cancelled:
            self._set(step, StepStatus.PENDING, "stopped by operator", time.monotonic() - started)
            raise
        except StepError as exc:
            ctx.log("ERROR", f"[{step.title}] {exc}")
            self._set(step, StepStatus.FAILED, str(exc), time.monotonic() - started)
            return False
        except Exception as exc:  # noqa: BLE001
            ctx.log("ERROR", f"[{step.title}] {type(exc).__name__}: {exc}")
            ctx.debug(traceback.format_exc())
            self._set(step, StepStatus.FAILED, f"{type(exc).__name__}: {exc}", time.monotonic() - started)
            return False

    def remove_one(self, ctx: Context, step: Step) -> bool:
        """Delete one step's footprint, then re-check so the sidebar shows what is left.

        The re-check is the point: after a removal the step is normally NEEDS_APPLY again ("not
        installed"), which is exactly what the operator should see.
        """
        started = time.monotonic()
        if not step.removable:
            ctx.log("ERROR", f"[{step.title}] cannot be removed")
            self._set(step, self.status[step.id], "removal is not supported for this step")
            return False
        self._set(step, StepStatus.RUNNING, "removing")
        ctx.log("WARN", f"==> removing {step.title}")
        try:
            step.remove(ctx)
        except Cancelled:
            self._set(step, StepStatus.PENDING, "stopped by operator", time.monotonic() - started)
            raise
        except StepError as exc:
            ctx.log("ERROR", f"[{step.title}] {exc}")
            self._set(step, StepStatus.FAILED, str(exc), time.monotonic() - started)
            return False
        except Exception as exc:  # noqa: BLE001 - every failure must land in the GUI
            ctx.log("ERROR", f"[{step.title}] {type(exc).__name__}: {exc}")
            ctx.debug(traceback.format_exc())
            self._set(step, StepStatus.FAILED, f"{type(exc).__name__}: {exc}", time.monotonic() - started)
            return False
        ctx.log("OK", f"[{step.title}] removed")
        self.check_one(ctx, step)
        return True

    def run(self, ctx: Context, included: set[str], *, start_at: str | None = None) -> bool:
        """Run every selected step in order; stops at the first failure. ``start_at`` resumes."""
        selected, reasons = self.effective_selection(ctx, included)
        for step in self.steps:
            if step.id in reasons:
                self._set(step, StepStatus.SKIPPED, reasons[step.id])
        skipping = start_at is not None
        for step in selected:
            if skipping:
                if step.id == start_at:
                    skipping = False
                elif self.status[step.id] is StepStatus.DONE:
                    continue
                else:
                    skipping = False
            ctx.check_cancelled()
            if not self.run_one(ctx, step):
                return False
        return True

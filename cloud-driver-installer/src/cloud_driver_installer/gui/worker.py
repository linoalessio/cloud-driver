"""The worker thread: runs one job at a time and reports through a queue.

The window never blocks on SSH or AWS. Every long operation - checking, applying, validating the
operator's AWS credentials, resolving the API domain - is a *job* the :class:`Worker` runs on a
``threading.Thread``; what happens is published as small event dataclasses on
:attr:`Worker.events`, which the Tk side drains with ``root.after(100, …)``.

Contract with the rest of the GUI:

* One job at a time. Starting a second one while the first runs raises :class:`WorkerBusy`.
* Every job ends with exactly one :class:`JobDone`, cancelled or not, so the buttons can be
  re-enabled on that event alone.
* Every log line is passed through the state's :class:`~cloud_driver_installer.secrets.Redactor`
  before it is queued - and the plan's typed secrets are registered with it at the start of
  each job - so no secret reaches the log pane even from a step that forgets to redact.
* The step catalog is the state's (:meth:`AppState.step_catalog`) so the sidebar's dependency
  reasoning and the runner agree on the same step objects; tests hand in tiny fake steps.

This module imports no tkinter.
"""

from __future__ import annotations

import queue
import socket
import threading
import time
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Callable, Iterable, Union

from cloud_driver_installer.engine import Cancelled, Context, Runner, Step, StepEvent, StepStatus
from cloud_driver_installer.gui.state import AppState, step_title
from cloud_driver_installer.remote import RemoteHost

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.aws import AwsProvisioner
    from cloud_driver_installer.model import AwsSettings


# --- events ------------------------------------------------------------------------------------


@dataclass
class LogEvent:
    """One (already redacted) log line; ``level`` is DEBUG, INFO, WARN, ERROR or OK."""

    level: str
    message: str


@dataclass
class ProgressEvent:
    """Sub-step progress in ``[0, 1]`` (a jar upload, a pip install) with a short caption."""

    fraction: float
    text: str = ""


@dataclass
class JobDone:
    """The last event of every job. ``ok`` is the job's own verdict; ``error`` explains a failure."""

    job: str
    ok: bool
    error: str | None = None
    cancelled: bool = False
    elapsed: float = 0.0


@dataclass
class AwsIdentityEvent:
    """Result of ``validate_aws``: the caller identity, or ``error`` when the credentials failed."""

    account: str = ""
    arn: str = ""
    user_id: str = ""
    error: str | None = None

    @property
    def ok(self) -> bool:
        """True when the credentials were accepted by STS."""
        return self.error is None


@dataclass
class DnsEvent:
    """Result of ``check_dns``: what ``domain`` resolves to and whether that is this server."""

    domain: str
    resolved_ip: str = ""
    matches: bool = False
    addresses: list[str] = field(default_factory=list)
    error: str | None = None


#: Everything that can appear on :attr:`Worker.events`.
WorkerEvent = Union[LogEvent, StepEvent, ProgressEvent, JobDone, AwsIdentityEvent, DnsEvent]


# --- errors ------------------------------------------------------------------------------------


class WorkerError(Exception):
    """A job could not be started or run; the message is fit for the operator."""


class WorkerBusy(WorkerError):
    """A job is already running."""


# --- helpers -----------------------------------------------------------------------------------


def make_provisioner(settings: "AwsSettings") -> "AwsProvisioner":
    """Build the boto3 client bundle for ``settings`` (imported lazily - boto3 is slow to load)."""
    from cloud_driver_installer.aws import AwsProvisioner

    return AwsProvisioner(settings)


def resolve_domain(domain: str) -> list[str]:
    """Every IPv4/IPv6 address ``domain`` resolves to, IPv4 first, duplicates removed.

    Raises ``OSError`` (``socket.gaierror``) when the name does not resolve.
    """
    infos = socket.getaddrinfo(domain, None)
    v4: list[str] = []
    v6: list[str] = []
    for family, _type, _proto, _canon, sockaddr in infos:
        address = str(sockaddr[0])
        if family == socket.AF_INET and address not in v4:
            v4.append(address)
        elif family == socket.AF_INET6 and address not in v6:
            v6.append(address)
    return v4 + v6


# --- the worker --------------------------------------------------------------------------------


class Worker:
    """Runs one job at a time on a daemon thread and reports on :attr:`events`.

    Construct it once per connected session. When ``state.remote`` is a
    :class:`~cloud_driver_installer.remote.RemoteHost`, its ``log``/``redact`` callables are
    re-pointed at this worker so ``$ command`` and output lines flow into the log pane.
    """

    def __init__(self, state: AppState, *, steps: Iterable[Step] | None = None) -> None:
        self.state = state
        if steps is not None:
            state.use_steps(steps)
        self.runner = Runner(state.step_catalog(), self._on_step_event)
        self.events: "queue.Queue[WorkerEvent]" = queue.Queue()
        self._thread: threading.Thread | None = None
        self._cancel = threading.Event()
        self._lock = threading.Lock()
        self.job: str | None = None
        self.current_step: str | None = None
        self.started_at: float = 0.0
        self._attach_remote()

    # --- state -----------------------------------------------------------------------------------

    @property
    def busy(self) -> bool:
        """True while a job thread is alive."""
        thread = self._thread
        return thread is not None and thread.is_alive()

    @property
    def cancel_requested(self) -> bool:
        """True once :meth:`cancel` was called for the running job."""
        return self._cancel.is_set()

    def elapsed(self) -> float:
        """Seconds since the current (or last) job started."""
        return time.monotonic() - self.started_at if self.started_at else 0.0

    def cancel(self) -> None:
        """Ask the running job to stop at its next cancellation point (no-op when idle)."""
        if self.busy:
            self._cancel.set()

    def wait(self, timeout: float | None = None) -> bool:
        """Block until the running job ends; returns False when ``timeout`` elapsed first."""
        thread = self._thread
        if thread is None:
            return True
        thread.join(timeout)
        return not thread.is_alive()

    def drain(self) -> list[WorkerEvent]:
        """Every event queued so far, oldest first (what the Tk ``after`` loop calls)."""
        out: list[WorkerEvent] = []
        while True:
            try:
                out.append(self.events.get_nowait())
            except queue.Empty:
                return out

    # --- jobs ------------------------------------------------------------------------------------

    def check_all(self, step_ids: Iterable[str] | None = None) -> None:
        """Job ``check_all``: run every step's ``check`` (or only ``step_ids``), in run order."""
        wanted = None if step_ids is None else set(step_ids)

        def job() -> bool:
            ctx = self._context()
            steps = self.runner.steps if wanted is None else [s for s in self.runner.steps if s.id in wanted]
            self.runner.check_all(ctx, steps)
            self._log("OK", self._check_summary(steps))
            return not any(self.runner.status[s.id] is StepStatus.FAILED for s in steps)

        self._start("check_all", job)

    def check_one(self, step_id: str) -> None:
        """Job ``check_one``: one step's ``check`` (``SKIPPED`` when the plan disables it)."""
        step = self._step(step_id)

        def job() -> bool:
            self.runner.check_all(self._context(), [step])
            return self.runner.status[step.id] is not StepStatus.FAILED

        self._start("check_one", job)

    def run_steps(self, step_ids: Iterable[str], *, start_at: str | None = None) -> None:
        """Job ``run``: check -> apply -> verify every selected step in order; stops on failure.

        ``step_ids`` is the operator's selection; ``enabled``/``depends_on`` are resolved by the
        runner exactly as :meth:`AppState.dependency_reasons` predicted. ``start_at`` resumes a
        stopped run at that step, keeping earlier ``DONE`` results.
        """
        included = set(step_ids)

        def job() -> bool:
            ok = self.runner.run(self._context(), included, start_at=start_at)
            if ok:
                self._log("OK", "All selected steps finished")
            else:
                failed = [s for s in self.runner.steps if self.runner.status[s.id] is StepStatus.FAILED]
                where = failed[-1].title if failed else "a step"
                self._log("ERROR", f"Run stopped at {where}")
            return ok

        self._start("run", job)

    def run_one(self, step_id: str) -> None:
        """Job ``run_one``: check -> apply -> verify for a single step (the page's Apply button)."""
        step = self._step(step_id)

        def job() -> bool:
            ctx = self._context()
            if not step.enabled(ctx.plan):
                raise WorkerError(f"{step.title} is disabled in the plan")
            return self.runner.run_one(ctx, step)

        self._start("run_one", job)

    def validate_aws(self) -> None:
        """Job ``validate_aws``: STS ``GetCallerIdentity`` with the plan's credentials."""

        def job() -> bool:
            from cloud_driver_installer.aws import AwsError  # lazy: boto3

            self.state.register_secrets()
            try:
                identity = make_provisioner(self.state.plan.aws).whoami()
            except AwsError as exc:
                self._log("ERROR", f"[AWS] {exc}")
                self.events.put(AwsIdentityEvent(error=self._redact(str(exc))))
                return False
            self._log("INFO", f"[AWS] credentials valid: {identity.arn} (account {identity.account})")
            self.events.put(AwsIdentityEvent(account=identity.account, arn=identity.arn, user_id=identity.user_id))
            return True

        self._start("validate_aws", job)

    def check_dns(self, domain: str) -> None:
        """Job ``check_dns``: resolve ``domain`` and compare with the server's discovered public IP."""
        name = (domain or "").strip()

        def job() -> bool:
            title = step_title("caddy")
            if not name:
                self.events.put(DnsEvent(domain=name, error="no domain given"))
                return False
            try:
                addresses = resolve_domain(name)
            except OSError as exc:
                self._log("WARN", f"[{title}] {name} does not resolve: {exc}")
                self.events.put(DnsEvent(domain=name, error=f"{name} does not resolve ({exc})"))
                return False
            discovered = self.state.discovered
            known = {ip for ip in (discovered.public_ip, discovered.ipv6) if ip}
            matches = bool(known) and any(address in known for address in addresses)
            resolved = addresses[0] if addresses else ""
            if matches:
                self._log("INFO", f"[{title}] {name} → {resolved} matches the server's public IP")
            elif not known:
                self._log("WARN", f"[{title}] {name} → {resolved}; the server's public IP is not known yet (run Check all)")
            else:
                self._log("WARN", f"[{title}] {name} → {resolved} does not match the server's public IP {discovered.public_ip or discovered.ipv6}")
            self.events.put(DnsEvent(domain=name, resolved_ip=resolved, matches=matches, addresses=addresses))
            return matches

        self._start("check_dns", job)

    # --- internals -------------------------------------------------------------------------------

    def _step(self, step_id: str) -> Step:
        try:
            return self.runner.by_id[step_id]
        except KeyError:
            raise WorkerError(f"unknown step {step_id!r}") from None

    def _start(self, name: str, fn: Callable[[], bool]) -> None:
        with self._lock:
            if self.busy:
                raise WorkerBusy(f"cannot start {name}: {self.job} is still running")
            self._cancel.clear()
            self.job = name
            self.current_step = None
            self.started_at = time.monotonic()
            thread = threading.Thread(target=self._run_job, args=(name, fn), name=f"installer-{name}", daemon=True)
            self._thread = thread
            thread.start()

    def _run_job(self, name: str, fn: Callable[[], bool]) -> None:
        started = time.monotonic()
        try:
            ok = fn()
            done = JobDone(name, ok, None, elapsed=time.monotonic() - started)
        except Cancelled:
            self._log("WARN", "Stopped by operator")
            done = JobDone(name, False, "stopped by operator", cancelled=True, elapsed=time.monotonic() - started)
        except WorkerError as exc:
            self._log("ERROR", str(exc))
            done = JobDone(name, False, self._redact(str(exc)), elapsed=time.monotonic() - started)
        except Exception as exc:  # noqa: BLE001 - every failure must land in the GUI
            self._log("ERROR", f"{type(exc).__name__}: {exc}")
            done = JobDone(name, False, self._redact(f"{type(exc).__name__}: {exc}"), elapsed=time.monotonic() - started)
        finally:
            self.current_step = None
        self.events.put(done)

    def _context(self) -> Context:
        """A fresh :class:`Context` over the state (the plan's typed secrets registered first)."""
        state = self.state
        if state.remote is None:
            raise WorkerError("not connected to a server")
        state.register_secrets()
        plan = state.plan
        return Context(
            plan=plan,
            discovered=state.discovered,
            secrets=state.secrets,
            remote=state.remote,
            redactor=state.redactor,
            log_fn=self._log,
            aws_factory=lambda: make_provisioner(plan.aws),
            cancel_flag=self._cancel.is_set,
            progress_fn=self._progress,
        )

    def _attach_remote(self) -> None:
        """Point a real :class:`RemoteHost`'s log/redact at this worker (the fake keeps its own)."""
        remote = self.state.remote
        if isinstance(remote, RemoteHost):
            remote.log = self._log
            remote.redact = self.state.redactor.redact

    def _redact(self, text: str) -> str:
        return self.state.redactor.redact(text)

    def _log(self, level: str, message: str) -> None:
        self.events.put(LogEvent(level, self._redact(message)))

    def _progress(self, fraction: float, text: str) -> None:
        self.events.put(ProgressEvent(fraction, self._redact(text)))

    def _on_step_event(self, event: StepEvent) -> None:
        if event.status in (StepStatus.CHECKING, StepStatus.RUNNING):
            self.current_step = event.step_id
        self.events.put(StepEvent(event.step_id, event.status, self._redact(event.detail), event.elapsed))

    def _check_summary(self, steps: list[Step]) -> str:
        counts: dict[StepStatus, int] = {}
        for step in steps:
            status = self.runner.status[step.id]
            counts[status] = counts.get(status, 0) + 1
        parts = []
        if counts.get(StepStatus.NEEDS_APPLY):
            parts.append(f"{counts[StepStatus.NEEDS_APPLY]} need apply")
        if counts.get(StepStatus.OK):
            parts.append(f"{counts[StepStatus.OK]} already satisfied")
        if counts.get(StepStatus.SKIPPED):
            parts.append(f"{counts[StepStatus.SKIPPED]} skipped")
        if counts.get(StepStatus.FAILED):
            parts.append(f"{counts[StepStatus.FAILED]} failed to check")
        return "Check all finished: " + (", ".join(parts) if parts else "nothing to report")


__all__: list[str] = [
    "AwsIdentityEvent",
    "DnsEvent",
    "JobDone",
    "LogEvent",
    "ProgressEvent",
    "StepEvent",
    "Worker",
    "WorkerBusy",
    "WorkerError",
    "WorkerEvent",
    "make_provisioner",
    "resolve_domain",
]

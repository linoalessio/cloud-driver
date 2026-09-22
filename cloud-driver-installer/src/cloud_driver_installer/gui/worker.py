"""The background worker: one job at a time, every result delivered through a queue.

The Tk main loop never blocks on SSH or AWS - it drains :class:`Worker.events` on a timer. Local
probes (validating AWS credentials, resolving DNS) run in their own short-lived thread so they
still answer while a long step is running.
"""

from __future__ import annotations

import queue
import threading
import traceback
from dataclasses import dataclass, field
from typing import Any, Callable

from cloud_driver_installer.engine import Cancelled, Context, Runner, StepEvent, StepStatus
from cloud_driver_installer.gui.state import AppState
from cloud_driver_installer.steps import all_steps


@dataclass
class LogEvent:
    """One line for the log pane."""

    level: str
    message: str


@dataclass
class ProgressEvent:
    """Sub-step progress of the running job."""

    fraction: float
    text: str = ""


@dataclass
class JobDone:
    """A job finished (successfully or not)."""

    job: str
    ok: bool
    error: str | None = None


@dataclass
class ProbeResult:
    """Answer of a local probe (``aws`` or ``dns``)."""

    kind: str
    ok: bool
    text: str
    data: dict[str, Any] = field(default_factory=dict)


class Worker:
    """Runs installer jobs off the Tk thread."""

    def __init__(self, state: AppState) -> None:
        self.state = state
        self.events: "queue.Queue[object]" = queue.Queue()
        self.runner = Runner(all_steps(), self.events.put)
        self._thread: threading.Thread | None = None
        self._cancel = threading.Event()

    # --- lifecycle -------------------------------------------------------------------------------

    @property
    def busy(self) -> bool:
        """True while a job is running."""
        return self._thread is not None and self._thread.is_alive()

    def cancel(self) -> None:
        """Ask the running job to stop between commands."""
        self._cancel.set()

    def context(self) -> Context:
        """A fresh :class:`Context` over the current state."""
        state = self.state
        state.register_secrets()
        if state.remote is None:
            raise RuntimeError("not connected")
        from cloud_driver_installer.aws import AwsProvisioner

        return Context(
            plan=state.plan,
            discovered=state.discovered,
            secrets=state.secrets,
            remote=state.remote,
            redactor=state.redactor,
            log_fn=lambda level, message: self.events.put(LogEvent(level, message)),
            aws_factory=lambda: AwsProvisioner(state.plan.aws),
            cancel_flag=self._cancel.is_set,
            progress_fn=lambda fraction, text: self.events.put(ProgressEvent(fraction, text)),
        )

    # --- jobs ------------------------------------------------------------------------------------

    def check_all(self) -> None:
        """Run every step's read-only check."""
        self._start("check", lambda ctx: self.runner.check_all(ctx))

    def check_one(self, step_id: str) -> None:
        """Check a single step."""
        self._start("check", lambda ctx: self.runner.check_one(ctx, self.runner.by_id[step_id]))

    def run_one(self, step_id: str) -> None:
        """check -> apply -> verify for a single step."""
        self._start("apply", lambda ctx: self.runner.run_one(ctx, self.runner.by_id[step_id]))

    def remove_one(self, step_id: str) -> None:
        """Delete a single step's footprint from the server, then re-check it."""
        self._start("remove", lambda ctx: self.runner.remove_one(ctx, self.runner.by_id[step_id]))

    def run_all(self, *, start_at: str | None = None) -> None:
        """Run every selected step in order."""
        self._start("install", lambda ctx: self.runner.run(ctx, set(self.state.included), start_at=start_at))

    def _start(self, job: str, body: Callable[[Context], Any]) -> None:
        if self.busy:
            self.events.put(LogEvent("WARN", "a job is still running - wait for it to finish or press Stop"))
            return
        self._cancel.clear()

        def run() -> None:
            ok, error = True, None
            try:
                context = self.context()
                result = body(context)
                ok = result is not False
            except Cancelled:
                ok, error = False, "stopped by the operator"
                self.events.put(LogEvent("WARN", "stopped by the operator"))
            except Exception as exc:  # noqa: BLE001 - every failure belongs in the log, not in a traceback
                ok, error = False, f"{type(exc).__name__}: {exc}"
                self.events.put(LogEvent("ERROR", error))
                self.events.put(LogEvent("DEBUG", traceback.format_exc()))
            finally:
                self.events.put(JobDone(job, ok, error))

        self._thread = threading.Thread(target=run, name=f"installer-{job}", daemon=True)
        self._thread.start()

    # --- local probes ----------------------------------------------------------------------------

    def probe_aws(self) -> None:
        """Validate the AWS credentials (STS GetCallerIdentity) without touching the server."""

        def run() -> None:
            from cloud_driver_installer.aws import AwsError, AwsProvisioner

            try:
                identity = AwsProvisioner(self.state.plan.aws).whoami()
                self.events.put(ProbeResult("aws", True, f"{identity.arn} (account {identity.account})"))
            except (AwsError, Exception) as exc:  # noqa: BLE001
                self.events.put(ProbeResult("aws", False, str(exc)))

        threading.Thread(target=run, name="installer-probe-aws", daemon=True).start()

    def probe_dns(self, domain: str) -> None:
        """Resolve ``domain`` locally and compare it with the server's public address."""

        def run() -> None:
            import socket

            try:
                addresses = sorted({info[4][0] for info in socket.getaddrinfo(domain, None)})
            except OSError as exc:
                self.events.put(ProbeResult("dns", False, f"{domain} does not resolve ({exc.strerror or exc})"))
                return
            expected = self.state.discovered.public_ip
            matches = not expected or expected in addresses
            text = f"{domain} -> {', '.join(addresses)}" + ("" if matches else f" (this server is {expected})")
            self.events.put(ProbeResult("dns", matches, text, {"addresses": addresses}))

        threading.Thread(target=run, name="installer-probe-dns", daemon=True).start()

    # --- event application -------------------------------------------------------------------------

    def apply_event(self, event: object) -> None:
        """Fold a :class:`StepEvent` into the shared state (called on the Tk thread)."""
        if isinstance(event, StepEvent):
            self.state.statuses[event.step_id] = event.status
            if event.detail:
                self.state.details[event.step_id] = event.detail
            if event.elapsed:
                self.state.elapsed[event.step_id] = event.elapsed

    def first_failed(self) -> str | None:
        """The id of the first step that failed, for "Retry from here"."""
        for step in self.runner.steps:
            if self.state.statuses.get(step.id) is StepStatus.FAILED:
                return step.id
        return None

"""Smoke step: the end-to-end probe that runs last.

There is no ``/health`` route on the REST API, so "up" means ``GET /auth/me`` answers ``401`` on
the loopback port (the JWT layer is active, which implies KMS, the database and the REST extension
all came up). Everything else - the metrics port, the public HTTPS name through Caddy, clamd's
socket and the intelligence service - is reported in the same detail line but never fails the run:
a certificate Caddy is still requesting or a provider firewall is the operator's next step, not an
installer failure.

The HTTPS probe deliberately runs on the operator's machine (``urllib``), the way a real client
would reach the server - from the box itself the domain would resolve fine even when the outside
world cannot reach it.
"""

from __future__ import annotations

import time
import urllib.error
import urllib.request
from typing import Callable

from cloud_driver_installer.engine import CheckResult, Context, Step, VerifyResult
from cloud_driver_installer.model import InstallPlan

#: The loopback API probe is retried this often - the application step already waited for it, so
#: this only bridges a restart that happened between the two steps.
API_ATTEMPTS = 3
API_RETRY_SECONDS = 2.0
HTTPS_TIMEOUT_SECONDS = 10.0


def https_status(url: str, timeout: float = HTTPS_TIMEOUT_SECONDS) -> int:
    """HTTP status code of a ``GET`` on ``url`` from this machine.

    A 4xx/5xx answer is returned as the code (the API answering 401 is the success case here);
    connection, DNS and TLS failures propagate as :class:`urllib.error.URLError`/:class:`OSError`.
    """
    request = urllib.request.Request(url, headers={"User-Agent": "cloud-driver-installer"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return int(response.status)
    except urllib.error.HTTPError as exc:
        return int(exc.code)


class SmokeStep(Step):
    """Probe the deployed system once everything else is in place."""

    id = "smoke"
    title = "Smoke test"
    depends_on = ("application",)
    mandatory = True

    def __init__(self) -> None:
        #: Injection point so the retry loop is testable without real time passing.
        self.sleep: Callable[[float], None] = time.sleep

    def check(self, ctx: Context) -> CheckResult:
        """A probe has nothing to keep: it always runs."""
        return CheckResult.needs_apply("end-to-end probe (API, metrics port, HTTPS, clamd, intelligence)")

    def apply(self, ctx: Context) -> None:
        """Nothing to change - the work is :meth:`verify`."""

    def verify(self, ctx: Context) -> VerifyResult:
        """Run every probe; only the loopback API answering 401 decides ``ok``."""
        plan = ctx.plan
        remote = ctx.remote
        parts: list[str] = []
        ok = True

        # (a) the REST API on the loopback port
        port = plan.app.rest_port
        code = ""
        for attempt in range(API_ATTEMPTS):
            result = remote.run(f"curl -s -o /dev/null -w '%{{http_code}}' -m 3 http://127.0.0.1:{port}/auth/me", quiet=True)
            code = result.text if result.ok else ""
            if code == "401":
                break
            if attempt + 1 < API_ATTEMPTS:
                self.sleep(API_RETRY_SECONDS)
                ctx.check_cancelled()
        if code == "401":
            parts.append(f"API 401 on 127.0.0.1:{port}/auth/me")
        else:
            ok = False
            parts.append(f"FAIL: API on 127.0.0.1:{port}/auth/me answered {code or 'nothing'} (expected 401)")

        # (b) the metrics port
        metrics_port = plan.app.metrics_port
        if remote.run_ok(f"timeout 3 bash -c '</dev/tcp/127.0.0.1/{metrics_port}'"):
            parts.append(f"metrics port {metrics_port} open")
        else:
            parts.append(f"WARN: metrics port {metrics_port} not accepting connections")

        # (c) the public name through Caddy, from here
        if plan.proxy.enabled and plan.proxy.api_domain:
            url = f"https://{plan.proxy.api_domain}/auth/me"
            try:
                status = https_status(url)
            except (urllib.error.URLError, OSError, ValueError) as exc:
                reason = getattr(exc, "reason", None) or exc
                ctx.warn(f"{url} not reachable from this machine yet: {reason}")
                parts.append(f"WARN: {url} not reachable yet from here (certificate pending / DNS / provider firewall)")
            else:
                if status == 401:
                    parts.append(f"{url} -> 401")
                else:
                    parts.append(f"WARN: {url} answered {status} (expected 401 - is the Caddy site block pointing at port {port}?)")

        # (d) clamd's socket unit
        if plan.clamav.enabled:
            if remote.service_active("clamav-daemon.socket"):
                parts.append("clamav-daemon.socket active")
            else:
                parts.append("WARN: clamav-daemon.socket not active (uploads are not scanned until it is)")

        # (e) the intelligence service
        if plan.intelligence.enabled:
            health = remote.run(f"curl -fsS -m 3 http://127.0.0.1:{plan.intelligence.port}/health", quiet=True)
            if health.ok:
                parts.append(f"intelligence /health on {plan.intelligence.port} ok")
            else:
                parts.append(f"WARN: intelligence /health on 127.0.0.1:{plan.intelligence.port} not answering (semantic search degrades to keyword search)")

        return VerifyResult(ok, " · ".join(parts))

    def describe(self, plan: InstallPlan) -> str:
        """One summary line listing the probes this plan gets."""
        probes = [f"GET 127.0.0.1:{plan.app.rest_port}/auth/me expecting 401", f"TCP connect to the metrics port {plan.app.metrics_port}"]
        if plan.proxy.enabled and plan.proxy.api_domain:
            probes.append(f"https://{plan.proxy.api_domain}/auth/me from this machine (warning only)")
        if plan.clamav.enabled:
            probes.append("clamav-daemon.socket active")
        if plan.intelligence.enabled:
            probes.append(f"intelligence /health on port {plan.intelligence.port}")
        return "probe " + ", ".join(probes)

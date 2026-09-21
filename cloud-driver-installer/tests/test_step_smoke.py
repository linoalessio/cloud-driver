"""SmokeStep: the loopback API decides, everything else only colours the detail line."""

from __future__ import annotations

import urllib.error
import urllib.request
from typing import Any

import pytest

from cloud_driver_installer.engine import Context, StepStatus
from cloud_driver_installer.steps.smoke import SmokeStep, https_status

from .fake_remote import FakeRemote


@pytest.fixture
def step() -> SmokeStep:
    step = SmokeStep()
    step.sleep = lambda seconds: None
    return step


class _Response:
    """A minimal ``urlopen`` context-manager result."""

    def __init__(self, status: int) -> None:
        self.status = status

    def __enter__(self) -> "_Response":
        return self

    def __exit__(self, *exc: Any) -> None:
        return None


@pytest.fixture
def urlopen(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    """Replace the operator-side HTTPS probe; ``script["answer"]`` is an exception to raise or a status."""
    script: dict[str, Any] = {"answer": urllib.error.HTTPError("https://api.example.com/auth/me", 401, "Unauthorized", {}, None), "calls": []}  # type: ignore[arg-type]

    def fake(request: urllib.request.Request, timeout: float | None = None) -> _Response:
        script["calls"].append((request.full_url, timeout))
        answer = script["answer"]
        if isinstance(answer, BaseException):
            raise answer
        return _Response(int(answer))

    monkeypatch.setattr(urllib.request, "urlopen", fake)
    return script


def _green_server(remote: FakeRemote) -> None:
    remote.ok("/auth/me", "401")
    remote.ok("/dev/tcp/127.0.0.1/9404")
    remote.ok("is-active --quiet clamav-daemon.socket")
    remote.ok("/health", '{"status":"ok"}')


def test_check_always_needs_apply_and_apply_is_a_no_op(ctx: Context, remote: FakeRemote, step: SmokeStep) -> None:
    assert step.check(ctx).status is StepStatus.NEEDS_APPLY
    step.apply(ctx)
    assert remote.commands == []


def test_verify_all_green(ctx: Context, remote: FakeRemote, step: SmokeStep, urlopen: dict[str, Any]) -> None:
    _green_server(remote)
    result = step.verify(ctx)
    assert result.ok, result.detail
    assert "API 401 on 127.0.0.1:8080/auth/me" in result.detail
    assert "metrics port 9404 open" in result.detail
    assert "https://api.example.com/auth/me -> 401" in result.detail
    assert "clamav-daemon.socket active" in result.detail
    assert "intelligence" not in result.detail
    assert "WARN" not in result.detail
    assert urlopen["calls"] == [("https://api.example.com/auth/me", 10.0)]
    assert remote.count("/auth/me") == 1


def test_verify_fails_only_on_the_api(ctx: Context, remote: FakeRemote, step: SmokeStep, urlopen: dict[str, Any]) -> None:
    remote.ok("/auth/me", "000")
    remote.ok("/dev/tcp/127.0.0.1/9404")
    remote.ok("is-active --quiet clamav-daemon.socket")
    result = step.verify(ctx)
    assert not result.ok
    assert result.detail.startswith("FAIL: API on 127.0.0.1:8080/auth/me answered 000")
    assert remote.count("/auth/me") == 3, "the loopback probe is retried"


def test_verify_warns_when_https_is_not_reachable_from_here(ctx: Context, remote: FakeRemote, step: SmokeStep, urlopen: dict[str, Any]) -> None:
    _green_server(remote)
    urlopen["answer"] = urllib.error.URLError("certificate verify failed")
    result = step.verify(ctx)
    assert result.ok
    assert "WARN: https://api.example.com/auth/me not reachable yet from here (certificate pending / DNS / provider firewall)" in result.detail
    assert any(level == "WARN" and "certificate verify failed" in message for level, message in ctx.captured_log)  # type: ignore[attr-defined]
    urlopen["answer"] = TimeoutError("timed out")
    assert "not reachable yet from here" in step.verify(ctx).detail


def test_verify_warns_on_an_unexpected_https_status(ctx: Context, remote: FakeRemote, step: SmokeStep, urlopen: dict[str, Any]) -> None:
    _green_server(remote)
    urlopen["answer"] = 200
    result = step.verify(ctx)
    assert result.ok and "WARN: https://api.example.com/auth/me answered 200" in result.detail


def test_verify_skips_https_without_a_proxy(ctx: Context, remote: FakeRemote, step: SmokeStep, urlopen: dict[str, Any]) -> None:
    _green_server(remote)
    ctx.plan.proxy.enabled = False
    result = step.verify(ctx)
    assert result.ok and "https://" not in result.detail and urlopen["calls"] == []


def test_verify_warns_on_metrics_and_clamd(ctx: Context, remote: FakeRemote, step: SmokeStep, urlopen: dict[str, Any]) -> None:
    remote.ok("/auth/me", "401")
    result = step.verify(ctx)
    assert result.ok
    assert "WARN: metrics port 9404 not accepting connections" in result.detail
    assert "WARN: clamav-daemon.socket not active" in result.detail
    ctx.plan.clamav.enabled = False
    assert "clamav" not in step.verify(ctx).detail


def test_verify_probes_intelligence_health_when_enabled(ctx: Context, remote: FakeRemote, step: SmokeStep, urlopen: dict[str, Any]) -> None:
    _green_server(remote)
    ctx.plan.intelligence.enabled = True
    result = step.verify(ctx)
    assert result.ok and "intelligence /health on 8600 ok" in result.detail
    assert remote.ran("http://127.0.0.1:8600/health")
    remote.rules = [rule for rule in remote.rules if rule.needle != "/health"]
    remote.fail("/health", "connection refused", code=7)
    assert "WARN: intelligence /health on 127.0.0.1:8600 not answering" in step.verify(ctx).detail


def test_https_status_returns_error_codes_and_raises_on_transport(urlopen: dict[str, Any]) -> None:
    assert https_status("https://api.example.com/auth/me") == 401
    urlopen["answer"] = 204
    assert https_status("https://api.example.com/auth/me", timeout=3) == 204
    assert urlopen["calls"][-1] == ("https://api.example.com/auth/me", 3)
    urlopen["answer"] = urllib.error.URLError("nodename nor servname provided")
    with pytest.raises(urllib.error.URLError):
        https_status("https://api.example.com/auth/me")


def test_describe_lists_the_probes(ctx: Context) -> None:
    line = SmokeStep().describe(ctx.plan)
    assert "127.0.0.1:8080/auth/me expecting 401" in line and "metrics port 9404" in line
    assert "https://api.example.com/auth/me" in line and "clamav-daemon.socket" in line and "intelligence" not in line

"""ApplicationStep: artefact discovery, prune/upload, the managed crontab block, restart, API wait."""

from __future__ import annotations

import io
import subprocess
from pathlib import Path
from typing import Any

import pytest

from cloud_driver_installer.config_files import render_start_env
from cloud_driver_installer.engine import Context, StepError, StepStatus
from cloud_driver_installer.model import Discovered, InstallPlan
from cloud_driver_installer.steps.application import (
    CRON_BEGIN,
    CRON_END,
    DEFAULT_BOOTSTRAP_JAR_NAME,
    LOGROTATE_PATH,
    ApplicationStep,
    cron_lines,
    extension_short_name,
    extract_managed_block,
    render_crontab,
    render_logrotate,
)

from .fake_remote import FakeRemote

INSTALL_DIR = "/home/cloud"
EXTENSIONS_DIR = "/home/cloud/extensions"
BOOTSTRAP = "cloud-driver-bootstrap-1.0.7.jar"
DEPLOYED_EXTENSIONS = ("rest", "scan", "terminal")  # intelligence is disabled in the fixture plan


@pytest.fixture
def step() -> ApplicationStep:
    step = ApplicationStep()
    step.sleep = lambda seconds: None
    return step


def _state(remote: FakeRemote, *, running: bool = False, crontab: str = "") -> dict[str, Any]:
    """Script the stateful parts of the server: the screen session and root's crontab."""
    state: dict[str, Any] = {"running": running, "crontab": crontab, "quits": 0, "starts": 0}
    remote.on("screen -list", lambda cmd, inp: 0 if state["running"] else 1)

    def quit_session(cmd: str, inp: str | None) -> int:
        state["running"] = False
        state["quits"] += 1
        return 0

    def start_session(cmd: str, inp: str | None) -> int:
        state["running"] = True
        state["starts"] += 1
        return 0

    def install_crontab(cmd: str, inp: str | None) -> int:
        state["crontab"] = inp or ""
        return 0

    remote.on("-X quit", quit_session)
    remote.on("./start-cloud.sh", start_session)
    remote.on("crontab -l", lambda cmd, inp: (0, state["crontab"]) if state["crontab"] else (1, "", "no crontab for root"))
    remote.on("crontab -", install_crontab)
    remote.ok("grep -qxF")  # the prune script
    remote.ok("chmod +x")
    return state


def _provision(remote: FakeRemote, plan: InstallPlan) -> None:
    """Put everything a finished deploy leaves on the server into the fake."""
    remote.files[f"{INSTALL_DIR}/{BOOTSTRAP}"] = "PK-bootstrap"
    for name in DEPLOYED_EXTENSIONS:
        remote.files[f"{EXTENSIONS_DIR}/cloud-driver-extensions-{name}-1.0.7.jar"] = f"PK-{name}"
    remote.files[f"{INSTALL_DIR}/start-cloud.sh"] = (Path(plan.app.repo_root) / "shell" / "start-cloud.sh").read_text()
    remote.files[f"{INSTALL_DIR}/start-cloud.env"] = render_start_env(plan, BOOTSTRAP)
    remote.files[LOGROTATE_PATH] = render_logrotate(plan)
    remote.modes[f"{INSTALL_DIR}/upload-scratch"] = 0o755
    remote.ok("ls -1", "\n".join([f"{INSTALL_DIR}/{BOOTSTRAP}", *(f"{EXTENSIONS_DIR}/cloud-driver-extensions-{n}-1.0.7.jar" for n in DEPLOYED_EXTENSIONS)]) + "\n")


# --- static helpers ----------------------------------------------------------------------------------


def test_bootstrap_jar_skips_the_original_jar(plan: InstallPlan) -> None:
    jar = ApplicationStep.bootstrap_jar(plan)
    assert jar is not None and jar.name == BOOTSTRAP


def test_bootstrap_jar_name_fallbacks(plan: InstallPlan, discovered: Discovered) -> None:
    assert ApplicationStep.bootstrap_jar_name(plan, discovered) == BOOTSTRAP
    plan.app.repo_root = ""
    assert ApplicationStep.bootstrap_jar_name(plan, discovered) == DEFAULT_BOOTSTRAP_JAR_NAME
    discovered.existing_jars = [f"{INSTALL_DIR}/cloud-driver-bootstrap-1.0.6.jar", f"{EXTENSIONS_DIR}/cloud-driver-extensions-rest-1.0.6.jar"]
    assert ApplicationStep.bootstrap_jar_name(plan, discovered) == "cloud-driver-bootstrap-1.0.6.jar"
    assert ApplicationStep.bootstrap_jar_name(plan) == DEFAULT_BOOTSTRAP_JAR_NAME


def test_extension_jars_follow_the_plan(plan: InstallPlan) -> None:
    notes: list[str] = []
    names = [jar.name for jar in ApplicationStep.extension_jars(plan, log=notes.append)]
    assert names == [f"cloud-driver-extensions-{n}-1.0.7.jar" for n in DEPLOYED_EXTENSIONS]
    assert len(notes) == 1 and "intelligence" in notes[0]
    plan.clamav.enabled = False
    plan.intelligence.enabled = True
    plan.app.excluded_extensions = ["terminal"]
    notes.clear()
    names = [jar.name for jar in ApplicationStep.extension_jars(plan, log=notes.append)]
    assert names == ["cloud-driver-extensions-intelligence-1.0.7.jar", "cloud-driver-extensions-rest-1.0.7.jar"]
    assert len(notes) == 1 and "scan" in notes[0] and "ClamAV" in notes[0]
    plan.app.excluded_extensions = ["cloud-driver-extensions-rest-1.0.7.jar"]
    assert [jar.name for jar in ApplicationStep.extension_jars(plan)] == ["cloud-driver-extensions-intelligence-1.0.7.jar"]


def test_extension_short_name() -> None:
    assert extension_short_name("cloud-driver-extensions-scan-1.0.7.jar") == "scan"
    assert extension_short_name("cloud-driver-extensions-rest-1.0.8-SNAPSHOT.jar") == "rest"
    assert extension_short_name("cloud-driver-extensions-thumbnails-1.0.7") == "thumbnails"


# --- crontab (pure) ------------------------------------------------------------------------------------


def test_cron_lines_for_the_default_plan(plan: InstallPlan) -> None:
    lines = cron_lines(plan, "")
    assert lines == [
        "@reboot cd /home/cloud && ./start-cloud.sh",
        "17 * * * * find /home/cloud/upload-scratch -name 'upload-*.tmp' -mmin +180 -delete",
        "30 3 * * * aws s3 sync /home/cloud/cloud-driver/backup s3://cloud-driver-test-bucket/backups/ --exclude '.staging/*' --only-show-errors",
    ]
    plan.aws.s3_key_prefix = "prod/"
    assert "s3://cloud-driver-test-bucket/prod/backups/" in cron_lines(plan, "")[2]
    assert "s3://resolved-bucket/prod/backups/" in cron_lines(plan, "resolved-bucket")[2]
    plan.aws.s3_enabled = False
    assert len(cron_lines(plan)) == 2
    plan.app.autostart_on_reboot = False
    plan.app.scratch_sweep = False
    assert cron_lines(plan) == []


def test_render_crontab_on_an_empty_crontab() -> None:
    text = render_crontab("", ["@reboot echo hi"])
    assert text == f"{CRON_BEGIN}\n@reboot echo hi\n{CRON_END}\n"
    assert extract_managed_block(text) == ["@reboot echo hi"]
    assert render_crontab("", []) == ""


def test_render_crontab_keeps_unrelated_lines() -> None:
    existing = "MAILTO=root\n0 5 * * * /usr/local/bin/certcheck\n"
    text = render_crontab(existing, ["@reboot echo hi"])
    assert text.startswith(existing.rstrip("\n") + "\n\n" + CRON_BEGIN)
    assert text.endswith(f"{CRON_END}\n")
    assert extract_managed_block(text) == ["@reboot echo hi"]
    assert render_crontab(text, ["@reboot echo hi"]) == text, "re-rendering the same block is a fixed point"


def test_render_crontab_replaces_an_old_block() -> None:
    existing = f"MAILTO=root\n{CRON_BEGIN}\n@reboot old\n17 * * * * old sweep\n{CRON_END}\n0 5 * * * keep-me\n"
    text = render_crontab(existing, ["@reboot new"])
    assert "old" not in text and "0 5 * * * keep-me" in text and "MAILTO=root" in text
    assert extract_managed_block(text) == ["@reboot new"]
    assert text.count(CRON_BEGIN) == 1 and text.count(CRON_END) == 1
    removed = render_crontab(existing, [])
    assert removed == "MAILTO=root\n0 5 * * * keep-me\n"
    assert extract_managed_block(removed) is None


def test_render_crontab_drops_an_unterminated_block() -> None:
    assert render_crontab(f"MAILTO=root\n{CRON_BEGIN}\n@reboot old\n", []) == "MAILTO=root\n"


def test_render_logrotate(plan: InstallPlan) -> None:
    text = render_logrotate(plan)
    assert text.startswith("/home/cloud/cloud.log {\n") and "copytruncate" in text and "rotate 8" in text


# --- check -------------------------------------------------------------------------------------------


def test_check_needs_apply_on_bare_box(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    _state(remote)
    result = step.check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    for needle in (f"{BOOTSTRAP} (upload)", "3 of 3 extension jars", "start-cloud.sh (upload)", "start-cloud.env (rewrite)", "crontab block", LOGROTATE_PATH, "upload-scratch", "screen session 'cloud' not running"):
        assert needle in result.detail, needle
    assert remote.uploads == [] and remote.files == {}, "check must not write"
    assert not remote.ran("crontab -\n") and not remote.ran("-X quit")


def test_check_ok_on_provisioned_box(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    _provision(remote, ctx.plan)
    _state(remote, running=True, crontab=render_crontab("", cron_lines(ctx.plan, "")))
    result = step.check(ctx)
    assert result.status is StepStatus.OK, result.detail
    assert BOOTSTRAP in result.detail and "3 extension jars in place" in result.detail and "running" in result.detail
    assert ctx.discovered.screen_running


def test_check_flags_stale_jars_and_maven_build(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    _provision(remote, ctx.plan)
    _state(remote, running=True, crontab=render_crontab("", cron_lines(ctx.plan, "")))
    remote.rules = [rule for rule in remote.rules if rule.needle != "ls -1"]
    remote.ok("ls -1", f"{INSTALL_DIR}/{BOOTSTRAP}\n{INSTALL_DIR}/cloud-driver-bootstrap-1.0.6.jar\n")
    result = step.check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY and "stale jars to prune: cloud-driver-bootstrap-1.0.6.jar" in result.detail
    ctx.plan.app.build_with_maven = True
    assert "Maven build requested" in step.check(ctx).detail


def test_check_without_jar_deploy_tolerates_a_stopped_session(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    _provision(remote, ctx.plan)
    _state(remote, running=False, crontab=render_crontab("", cron_lines(ctx.plan, "")))
    ctx.plan.app.deploy_jars = False
    ctx.plan.app.start_after_deploy = False
    result = step.check(ctx)
    assert result.status is StepStatus.OK, result.detail
    assert "start disabled" in result.detail and "jar upload disabled" in result.detail


# --- apply -------------------------------------------------------------------------------------------


def test_apply_on_bare_box_deploys_everything(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    state = _state(remote)
    step.apply(ctx)

    uploaded = {remote_path: local for local, remote_path in remote.uploads}
    assert f"{INSTALL_DIR}/{BOOTSTRAP}" in uploaded
    for name in DEPLOYED_EXTENSIONS:
        assert f"{EXTENSIONS_DIR}/cloud-driver-extensions-{name}-1.0.7.jar" in uploaded
    assert f"{EXTENSIONS_DIR}/cloud-driver-extensions-intelligence-1.0.7.jar" not in uploaded
    assert f"{INSTALL_DIR}/start-cloud.sh" in uploaded and remote.modes[f"{INSTALL_DIR}/start-cloud.sh"] == 0o755
    assert remote.files[f"{INSTALL_DIR}/{BOOTSTRAP}"] == "PK-bootstrap"

    prune = [cmd for cmd in remote.commands if "grep -qxF" in cmd]
    assert len(prune) == 1
    assert f"{INSTALL_DIR}/cloud-driver-bootstrap-*.jar {EXTENSIONS_DIR}/*.jar" in prune[0]
    keep_input = remote.inputs[remote.commands.index(prune[0])]
    assert keep_input == BOOTSTRAP + "\n" + "".join(f"cloud-driver-extensions-{n}-1.0.7.jar\n" for n in DEPLOYED_EXTENSIONS)
    assert remote.commands.index(prune[0]) < len(remote.commands), "prune happens before uploads"

    assert remote.ran(f"mkdir -p {INSTALL_DIR}/upload-scratch")
    assert remote.files[f"{INSTALL_DIR}/start-cloud.env"] == render_start_env(ctx.plan, BOOTSTRAP)
    assert state["crontab"] == render_crontab("", cron_lines(ctx.plan, ""))
    assert state["crontab"].startswith(CRON_BEGIN) and "@reboot cd /home/cloud && ./start-cloud.sh" in state["crontab"]
    assert remote.files[LOGROTATE_PATH] == render_logrotate(ctx.plan) and remote.modes[LOGROTATE_PATH] == 0o644
    assert state["quits"] == 0 and state["starts"] == 1
    assert remote.ran(f"cd {INSTALL_DIR} && ./start-cloud.sh")
    assert ctx.discovered.screen_running and ctx.discovered.existing_jars[0] == BOOTSTRAP


def test_apply_restarts_a_running_session(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    state = _state(remote, running=True)
    step.apply(ctx)
    assert state["quits"] == 1 and state["starts"] == 1
    quit_index = next(i for i, cmd in enumerate(remote.commands) if "-X quit" in cmd)
    start_index = next(i for i, cmd in enumerate(remote.commands) if "./start-cloud.sh" in cmd and cmd.startswith("cd "))
    assert quit_index < start_index


def test_apply_fails_when_the_session_will_not_stop(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    _state(remote, running=True)
    remote.rules = [rule for rule in remote.rules if rule.needle != "-X quit"]
    remote.ok("-X quit")
    ticks = iter(range(0, 100, 5))
    step.clock = lambda: float(next(ticks))
    with pytest.raises(StepError, match="did not stop"):
        step.apply(ctx)


def test_apply_twice_uploads_nothing_the_second_time(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    state = _state(remote)
    step.apply(ctx)
    first_uploads = len(remote.uploads)
    crontab_installs = remote.count("crontab -\n") + sum(1 for cmd in remote.commands if cmd == "crontab -")
    step.apply(ctx)
    assert len(remote.uploads) == first_uploads, "matching SHA-256 means no re-upload"
    assert sum(1 for cmd in remote.commands if cmd == "crontab -") == crontab_installs, "an unchanged crontab is not reinstalled"
    assert remote.backups == [], "start-cloud.env and logrotate were identical"
    assert state["starts"] == 2, "start after deploy restarts on every apply"


def test_apply_without_jar_deploy_only_ships_the_script(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    _state(remote)
    ctx.plan.app.deploy_jars = False
    ctx.plan.app.start_after_deploy = False
    step.apply(ctx)
    assert [remote_path for _, remote_path in remote.uploads] == [f"{INSTALL_DIR}/start-cloud.sh"]
    assert not remote.ran("grep -qxF") and not remote.ran("cd /home/cloud && ./start-cloud.sh")


def test_apply_removes_the_block_when_every_cron_feature_is_off(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    old = f"MAILTO=root\n{CRON_BEGIN}\n@reboot old\n{CRON_END}\n"
    state = _state(remote, crontab=old)
    ctx.plan.app.autostart_on_reboot = False
    ctx.plan.app.scratch_sweep = False
    ctx.plan.app.backup_offsite = False
    ctx.plan.app.persist_log = False
    step.apply(ctx)
    assert state["crontab"] == "MAILTO=root\n"
    assert LOGROTATE_PATH not in remote.files


def test_apply_without_a_built_jar_is_an_operator_readable_error(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    _state(remote)
    (Path(ctx.plan.app.repo_root) / "cloud-driver-bootstrap" / "target" / BOOTSTRAP).unlink()
    with pytest.raises(StepError, match="mvn clean install"):
        step.apply(ctx)


# --- maven build (local subprocess, mocked) ------------------------------------------------------------


class FakePopen:
    """Stands in for ``subprocess.Popen``: records the call, streams two lines, exits with ``code``."""

    calls: list[dict[str, Any]] = []
    code: int = 0

    def __init__(self, args: list[str], **kwargs: Any) -> None:
        FakePopen.calls.append({"args": args, **kwargs})
        self.stdout = io.StringIO("[INFO] building\n[INFO] done\n")
        self.terminated = False

    def wait(self) -> int:
        return FakePopen.code

    def terminate(self) -> None:
        self.terminated = True


@pytest.fixture
def fake_popen(monkeypatch: pytest.MonkeyPatch) -> type[FakePopen]:
    FakePopen.calls = []
    FakePopen.code = 0
    monkeypatch.setattr(subprocess, "Popen", FakePopen)
    return FakePopen


def test_maven_build_runs_locally_before_the_upload(ctx: Context, remote: FakeRemote, step: ApplicationStep, fake_popen: type[FakePopen]) -> None:
    _state(remote)
    ctx.plan.app.build_with_maven = True
    step.apply(ctx)
    assert len(fake_popen.calls) == 1
    call = fake_popen.calls[0]
    assert call["args"] == ["mvn", "-q", "clean", "install", "-DskipTests"]
    assert call["cwd"] == ctx.plan.app.repo_root
    assert call["stdout"] is subprocess.PIPE and call["stderr"] is subprocess.STDOUT
    assert any("[INFO] done" in message for _, message in ctx.captured_log)  # type: ignore[attr-defined]
    assert remote.uploads, "the upload follows the build"


def test_maven_failure_stops_the_step_before_touching_the_server(ctx: Context, remote: FakeRemote, step: ApplicationStep, fake_popen: type[FakePopen]) -> None:
    _state(remote)
    ctx.plan.app.build_with_maven = True
    fake_popen.code = 1
    with pytest.raises(StepError, match="Maven build failed"):
        step.apply(ctx)
    assert remote.uploads == []


def test_missing_mvn_is_an_operator_readable_error(ctx: Context, remote: FakeRemote, step: ApplicationStep, monkeypatch: pytest.MonkeyPatch) -> None:
    _state(remote)
    ctx.plan.app.build_with_maven = True

    def missing(*args: Any, **kwargs: Any) -> Any:
        raise FileNotFoundError("mvn")

    monkeypatch.setattr(subprocess, "Popen", missing)
    with pytest.raises(StepError, match="could not start mvn"):
        step.apply(ctx)


# --- verify --------------------------------------------------------------------------------------------


def test_verify_ok_when_the_api_answers_401(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    _state(remote, running=True)
    answers = iter(["000", "000", "401"])
    remote.on("/auth/me", lambda cmd, inp: (0, next(answers)))
    result = step.verify(ctx)
    assert result.ok and "API answering on 127.0.0.1:8080" in result.detail
    assert remote.count("/auth/me") == 3


def test_verify_reports_a_dead_session_with_the_log_tail(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    _state(remote, running=False)
    remote.ok("/auth/me", "000")
    remote.files[f"{INSTALL_DIR}/cloud.log"] = "\n".join(f"line {i}" for i in range(60)) + "\n"
    with pytest.raises(StepError) as excinfo:
        step.verify(ctx)
    message = str(excinfo.value)
    assert "died" in message and "line 59" in message and "line 20" in message and "line 19" not in message


def test_verify_times_out(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    _state(remote, running=True)
    remote.ok("/auth/me", "000")
    ticks = iter(range(0, 1000, 30))
    step.clock = lambda: float(next(ticks))
    result = step.verify(ctx)
    assert not result.ok and "did not answer 401" in result.detail and "cloud.log" in result.detail


def test_verify_without_start_is_ok_when_stopped(ctx: Context, remote: FakeRemote, step: ApplicationStep) -> None:
    _state(remote, running=False)
    ctx.plan.app.start_after_deploy = False
    result = step.verify(ctx)
    assert result.ok and "not started" in result.detail
    assert not remote.ran("/auth/me")


# --- describe -------------------------------------------------------------------------------------------


def test_describe_mirrors_the_plan(plan: InstallPlan) -> None:
    line = ApplicationStep().describe(plan)
    assert f"upload {BOOTSTRAP} + 3 extension jars" in line
    assert "crontab block (autostart, scratch sweep, off-site backup)" in line
    assert "logrotate" in line and "restart screen session 'cloud'" in line
    plan.app.build_with_maven = True
    plan.app.deploy_jars = False
    plan.app.start_after_deploy = False
    line = ApplicationStep().describe(plan)
    assert line.startswith("build with Maven") and "jars untouched" in line and "restart" not in line

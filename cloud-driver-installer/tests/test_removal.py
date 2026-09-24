"""Removing the whole deployment: the order, the confirmation, and what a wipe leaves behind.

The per-step removals are covered by ``test_step_removal.py``; this file is about the operation
that chains them, which is the only one in the installer that cannot be re-run into safety.
"""

from __future__ import annotations

import pytest

from cloud_driver_installer.engine import CheckResult, Context, Runner, Step, StepError, StepStatus
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.removal import (
    confirmation_phrase,
    matches_confirmation,
    removal_items,
    removal_steps,
    retained_items,
)
from cloud_driver_installer.steps import STEP_IDS, all_steps


# --- the order ------------------------------------------------------------------------------------


def test_removal_runs_the_catalog_backwards() -> None:
    """The intelligence service before the application, the directory layout last of all."""
    order = [step.id for step in removal_steps()]
    installed = [step_id for step_id in STEP_IDS if step_id in set(order)]
    assert order == list(reversed(installed))
    assert order[0] == "intelligence" and order[-1] == "server"
    assert order.index("application") < order.index("config") < order.index("packages")


def test_only_the_smoke_test_is_left_out() -> None:
    """It probes and installs nothing, so it is the one step with nothing to undo."""
    assert "smoke" not in {step.id for step in removal_steps()}
    assert len(removal_steps()) == len(all_steps()) - 1


def test_the_selection_does_not_narrow_a_full_removal(plan: InstallPlan) -> None:
    """A step switched off in the plan may still be installed from an earlier run, so it still goes."""
    plan.redis.enabled = False
    plan.intelligence.enabled = False
    ids = {step.id for step in removal_steps()}
    assert {"redis", "intelligence"} <= ids


# --- the confirmation -----------------------------------------------------------------------------


def test_every_item_carries_the_step_s_own_description(plan: InstallPlan) -> None:
    items = removal_items(plan)
    assert [item.step_id for item in items] == [step.id for step in removal_steps()]
    for item in items:
        assert item.title and len(item.description) > 40, item.step_id


def test_the_operator_has_to_type_this_server_s_address(plan: InstallPlan) -> None:
    plan.ssh.host = "82.165.48.39"
    assert confirmation_phrase(plan) == "82.165.48.39"
    assert matches_confirmation(plan, " 82.165.48.39 ")
    assert not matches_confirmation(plan, "82.165.48.3")
    assert not matches_confirmation(plan, "")


def test_an_unnamed_server_can_never_be_confirmed(plan: InstallPlan) -> None:
    """An empty phrase must not turn an empty field into a confirmation."""
    plan.ssh.host = ""
    assert not matches_confirmation(plan, "")


def test_what_a_wipe_keeps_names_the_key_and_the_bucket(plan: InstallPlan) -> None:
    plan.aws.kms_key_id = "alias/cloud-driver"
    plan.aws.s3_bucket = "cloud-driver-content"
    text = " ".join(retained_items(plan))
    assert "alias/cloud-driver" in text and "cloud-driver-content" in text
    assert "cron" in text and "python3" in text, "the packages Debian needs must be named too"


def test_an_external_data_store_is_named_as_kept(plan: InstallPlan) -> None:
    plan.postgres.mode = "external"
    plan.postgres.host = "db.example.com"
    plan.redis.mode = "external"
    plan.redis.host = "cache.example.com"
    text = " ".join(retained_items(plan))
    assert "db.example.com" in text and "cache.example.com" in text


# --- the run --------------------------------------------------------------------------------------


class _Recorder(Step):
    """A step that records its own removal, or refuses it."""

    def __init__(self, step_id: str, log: list[str], *, fails: bool = False) -> None:
        self.id = step_id
        self.title = step_id.title()
        self.removable = True
        self._log = log
        self._fails = fails

    def check(self, ctx: Context) -> CheckResult:
        return CheckResult.needs_apply("not installed")

    def apply(self, ctx: Context) -> None:  # pragma: no cover - never applied in these tests
        raise AssertionError("apply must not run during a removal")

    def remove(self, ctx: Context) -> None:
        self._log.append(self.id)
        if self._fails:
            raise StepError("could not remove")

    def describe_removal(self, plan: InstallPlan) -> str:
        return f"remove {self.id}"


def test_a_failing_step_does_not_stop_the_wipe(ctx: Context) -> None:
    """Stopping halfway would leave a server nobody can reason about, so every step still runs."""
    log: list[str] = []
    steps = [_Recorder("first", log), _Recorder("second", log, fails=True), _Recorder("third", log)]
    runner = Runner(steps, lambda event: None)
    assert runner.remove_all(ctx) is False
    assert log == ["third", "second", "first"], "newest first, and the failure did not end the run"
    assert runner.status["second"] is StepStatus.FAILED
    assert any("failed step" in message for _level, message in ctx.captured_log)


def test_a_clean_wipe_reports_that_nothing_is_left(ctx: Context) -> None:
    log: list[str] = []
    runner = Runner([_Recorder("first", log), _Recorder("second", log)], lambda event: None)
    assert runner.remove_all(ctx) is True
    assert log == ["second", "first"]
    assert any("nothing this installer deployed is left" in message for _level, message in ctx.captured_log)


def test_an_irremovable_step_is_never_part_of_a_wipe(ctx: Context) -> None:
    """``remove_all`` filters by ``removable`` instead of letting ``remove_one`` refuse sixteen times."""
    runner = Runner(all_steps(), lambda event: None)
    assert [step.id for step in removal_steps(runner.steps)] == [step.id for step in removal_steps()]


def test_a_wipe_logs_what_it_cannot_reach(ctx: Context) -> None:
    """The AWS resources outlive the removal, so the log has to say so where the operator reads it."""
    log: list[str] = []
    Runner([_Recorder("first", log)], lambda event: None).remove_all(ctx)
    kept = [message for level, message in ctx.captured_log if message.startswith("kept:")]
    assert kept and any("KMS key" in message for message in kept)


@pytest.mark.parametrize("step_id", ["server", "postgres", "application"])
def test_the_real_steps_are_all_reachable_through_the_runner(step_id: str) -> None:
    runner = Runner(all_steps(), lambda event: None)
    assert runner.by_id[step_id].removable

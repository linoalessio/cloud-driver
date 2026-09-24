"""The window itself: it builds, every page round-trips the plan, and step events reach the sidebar.

Skipped wherever there is no display (CI runners, headless servers).
"""

from __future__ import annotations

import pytest

from cloud_driver_installer.engine import StepEvent, StepStatus
from cloud_driver_installer.gui.state import AppState
from cloud_driver_installer.gui.worker import LogEvent, Worker
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.steps import STEP_IDS

from fake_remote import FakeRemote

tk = pytest.importorskip("tkinter")


@pytest.fixture
def root():
    """A real Tk root, or a skip when the machine has no display."""
    try:
        instance = tk.Tk()
    except tk.TclError as exc:  # pragma: no cover - headless CI
        pytest.skip(f"no display: {exc}")
    from cloud_driver_installer.gui.widgets import init_styles

    init_styles(instance)
    instance.withdraw()
    yield instance
    instance.destroy()


@pytest.fixture
def window(root, plan: InstallPlan):
    """A main window over a scripted server."""
    from cloud_driver_installer.gui.app import MainWindow

    state = AppState(plan=plan)
    state.remote = FakeRemote(default_ok=True)
    state.discovered.ram_mib = 7884
    state.discovered.os_pretty = "Debian GNU/Linux 12 (bookworm)"
    return MainWindow(root, state)


def test_every_step_has_a_row_and_every_page_builds(window) -> None:
    assert set(window.rows) == set(STEP_IDS)
    for page_id in window.pages:
        window.show(page_id)
        window.root.update()
    assert window.current in window.pages


def test_pages_round_trip_the_plan(window) -> None:
    assert window.store_pages(), "the default plan must validate"
    window.state.plan.app.rest_port = 9090
    window.state.plan.intelligence.enabled = True
    window.load_pages()
    window.state.plan.app.rest_port = 1
    assert window.store_pages()
    assert window.state.plan.app.rest_port == 9090 and window.state.plan.intelligence.enabled


def test_a_bad_port_is_reported_and_does_not_reach_the_plan(window) -> None:
    window.pages["application"].rest_port.set("not-a-port")
    assert not window.store_pages()
    assert window.current == "application"
    assert "REST port" in window.pages["application"].error_label.cget("text")
    assert window.state.plan.app.rest_port != "not-a-port"


def test_step_events_paint_the_sidebar(window) -> None:
    window.worker.events.put(StepEvent("postgres", StepStatus.DONE, "PostgreSQL 15 · role created", 1.2))
    window.worker.events.put(LogEvent("INFO", "hello"))
    window.drain()
    assert window.state.statuses["postgres"] is StepStatus.DONE
    assert "role created" in window.rows["postgres"]["detail"].cget("text")


def test_optional_steps_can_be_excluded_and_mandatory_ones_cannot(window) -> None:
    window.rows["redis"]["included"].set(False)
    window.toggle("redis")
    reasons = window.state.dependency_reasons()
    assert reasons["redis"] == "not selected"
    assert "application" not in reasons  # every step it needs is mandatory and still selected
    window.state.plan.intelligence.enabled = False
    assert window.state.dependency_reasons()["intelligence"] == "not part of this plan"


def test_destructive_runs_need_the_confirmation(window) -> None:
    window.state.plan.app.jwt_rotate = True
    assert any("JWT" in action for action in window.state.destructive_actions())
    summary = window.pages["summary"]
    summary.refresh(window.state)
    assert str(summary.install_button.cget("state")) == "disabled"
    summary.acknowledge.set(True)
    summary._sync_button()
    assert str(summary.install_button.cget("state")) == "normal"


def test_summary_lists_every_step_and_the_next_steps(window) -> None:
    summary = window.pages["summary"]
    summary.refresh(window.state)
    assert len(summary.plan_table.get_children()) == len(STEP_IDS)
    assert "admin grant" in summary.next_steps.cget("text")


def test_the_window_offers_one_button_for_the_whole_deployment(window) -> None:
    assert str(window.remove_all_button.cget("text")).startswith("Remove everything")


def test_the_removal_dialog_arms_only_on_this_server_s_address(window) -> None:
    """The typed host is what separates a misplaced click from a wiped production server."""
    from cloud_driver_installer.gui.removal import RemoveEverythingDialog
    from cloud_driver_installer.removal import removal_steps

    window.state.plan.ssh.host = "203.0.113.10"
    dialog = RemoveEverythingDialog(window.root, window.state)
    try:
        assert len(dialog.items) == len(removal_steps())
        assert "disabled" in dialog.remove_button.state()
        dialog.typed.set("203.0.113.1")
        assert "disabled" in dialog.remove_button.state(), "a prefix of the host must not arm it"
        dialog.typed.set("203.0.113.10")
        assert "disabled" not in dialog.remove_button.state()
        dialog._accept()
        assert dialog.confirmed
    finally:
        if dialog.winfo_exists():
            dialog.destroy()


def test_an_unfinished_plan_still_lets_the_server_be_wiped(window, monkeypatch) -> None:
    """Removal must not need a plan that could be installed - only fields that still parse."""
    from cloud_driver_installer.gui import app as app_module

    removals: list[str] = []
    window.pages["application"].repo_root.set("")  # "repository root is required" blocks an install
    monkeypatch.setattr(window.worker, "remove_all", lambda: removals.append("wipe"))
    monkeypatch.setattr(app_module, "ask_remove_everything", lambda parent, state: True)
    window.remove_everything()
    assert removals == ["wipe"]
    assert not window.store_pages(), "the same plan is still refused for an install"


def test_a_cancelled_removal_deletes_nothing(window, monkeypatch) -> None:
    from cloud_driver_installer.gui import app as app_module

    removals: list[str] = []
    monkeypatch.setattr(window.worker, "remove_all", lambda: removals.append("wipe"))
    monkeypatch.setattr(app_module, "ask_remove_everything", lambda parent, state: False)
    window.remove_everything()
    assert removals == []
    monkeypatch.setattr(app_module, "ask_remove_everything", lambda parent, state: True)
    window.remove_everything()
    assert removals == ["wipe"]


def test_worker_redacts_before_the_log(plan: InstallPlan) -> None:
    state = AppState(plan=plan)
    state.remote = FakeRemote(default_ok=True)
    state.secrets.pg_password = "s3cret-password-value"
    state.register_secrets()
    worker = Worker(state)
    context = worker.context()
    context.info(f"connecting with {state.secrets.pg_password}")
    event = worker.events.get_nowait()
    assert isinstance(event, LogEvent) and state.secrets.pg_password not in event.message

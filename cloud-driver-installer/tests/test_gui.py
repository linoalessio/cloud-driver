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

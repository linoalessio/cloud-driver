"""Every pane scrolls, and one wheel event moves exactly the pane under the pointer.

The wheel arithmetic is a pure function and always runs; the widget tests need a display and skip
without one, like the rest of the GUI suite.
"""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from cloud_driver_installer.gui.state import AppState
from cloud_driver_installer.model import InstallPlan

from fake_remote import FakeRemote

tk = pytest.importorskip("tkinter")

from cloud_driver_installer.gui.widgets import ScrollFrame, init_styles, install_wheel_router, wheel_units


def event(*, delta: int = 0, num: int = 0) -> SimpleNamespace:
    """A stand-in for the wheel event of one windowing system."""
    return SimpleNamespace(delta=delta, num=num)


def pump(widget, times: int = 5) -> None:
    """Let Tk settle: a destroy re-lays out the body, which only then reports its new size."""
    for _ in range(times):
        widget.update_idletasks()
        widget.update()


@pytest.fixture
def root():
    """A real Tk root, or a skip when the machine has no display."""
    try:
        instance = tk.Tk()
    except tk.TclError as exc:  # pragma: no cover - headless CI
        pytest.skip(f"no display: {exc}")
    init_styles(instance)
    instance.geometry("600x300")
    instance.withdraw()
    yield instance
    instance.destroy()


def test_wheel_units_per_windowing_system() -> None:
    """X11 buttons, Windows' multiples of 120 and macOS' small counts all produce real movement."""
    assert wheel_units(event(num=4)) == -3 and wheel_units(event(num=5)) == 3
    assert wheel_units(event(delta=-120)) == 3 and wheel_units(event(delta=240)) == -6
    # The macOS case: dividing these (as this once did) floors to zero and nothing ever scrolls.
    assert wheel_units(event(delta=1)) == -1 and wheel_units(event(delta=-2)) == 2
    assert wheel_units(event(delta=0)) == 0


def test_a_tall_body_scrolls_and_a_short_one_hides_the_scrollbar(root) -> None:
    """The scrollbar tracks whether there is anything to scroll, and never parks content off-screen."""
    from tkinter import ttk

    root.deiconify()  # an unmapped canvas has no height, and then nothing can overflow it
    scroller = ScrollFrame(root)
    scroller.pack(fill="both", expand=True)
    labels = [ttk.Label(scroller.body, text=f"row {index}") for index in range(80)]
    for label in labels:
        label.pack(anchor="w")
    pump(root)
    assert scroller.scrollbar.winfo_manager(), "a body taller than the window needs its scrollbar"

    scroller.scroll(wheel_units(event(delta=-1)))
    root.update()
    assert scroller.canvas.yview()[0] > 0.0, "one macOS trackpad tick must move the page"

    for label in labels[2:]:
        label.destroy()
    pump(root)
    assert not scroller.scrollbar.winfo_manager(), "content that fits needs no scrollbar"
    assert scroller.canvas.yview()[0] == 0.0, "shrinking content must not leave the body scrolled away"


def test_the_scrollbar_survives_a_parent_that_cannot_grow(root) -> None:
    """The sidebar's case: a fixed-width parent, so the scrollbar must be packed before the canvas.

    Packed after it, the canvas (fill + expand) takes the whole cavity and the scrollbar ends up
    one pixel wide and unmapped - present to winfo_manager(), invisible to the operator, and the
    steps below the fold look simply gone.
    """
    from tkinter import ttk

    root.deiconify()
    holder = ttk.Frame(root, width=260)
    holder.pack(side="left", fill="y")
    holder.pack_propagate(False)
    scroller = ScrollFrame(holder)
    scroller.pack(fill="both", expand=True)
    for index in range(60):
        ttk.Label(scroller.body, text=f"step {index}").pack(anchor="w")
    pump(root)

    assert scroller.scrollbar.winfo_ismapped(), "the scrollbar must be visible, not merely packed"
    assert scroller.scrollbar.winfo_width() > 5, f"one pixel of scrollbar is none: {scroller.scrollbar.winfo_width()}"
    assert scroller.canvas.winfo_width() < holder.winfo_width(), "the canvas must leave room for it"
    holder.destroy()


def test_the_router_moves_only_the_pane_under_the_pointer(root) -> None:
    """Two scrollers, one wheel event: the other one must not move."""
    from tkinter import ttk

    install_wheel_router(root)
    install_wheel_router(root)  # idempotent: a second binding would double every scroll
    left, right = ScrollFrame(root), ScrollFrame(root)
    left.pack(side="left", fill="both", expand=True)
    right.pack(side="right", fill="both", expand=True)
    for scroller in (left, right):
        for index in range(80):
            ttk.Label(scroller.body, text=f"row {index}").pack(anchor="w")
    root.deiconify()
    root.update()

    x, y = left.canvas.winfo_rootx() + 5, left.canvas.winfo_rooty() + 5
    root.event_generate("<MouseWheel>", delta=-1, rootx=x, rooty=y, x=5, y=5)
    root.update()
    assert left.canvas.yview()[0] > 0.0, "the pane under the pointer scrolls"
    assert right.canvas.yview()[0] == 0.0, "the other pane stays put"


def test_the_log_transcript_keeps_its_own_wheel_events(root) -> None:
    """A Text scrolls itself; the page behind it must not scroll too."""
    from cloud_driver_installer.gui.log import LogPane

    install_wheel_router(root)
    scroller = ScrollFrame(root)
    scroller.pack(fill="both", expand=True)
    log = LogPane(scroller.body)
    log.pack(fill="both", expand=True)
    for index in range(200):
        log.append("INFO", f"line {index}")
    root.deiconify()
    root.update()

    x, y = log.text.winfo_rootx() + 5, log.text.winfo_rooty() + 5
    root.event_generate("<MouseWheel>", delta=-1, rootx=x, rooty=y, x=5, y=5)
    root.update()
    assert scroller.canvas.yview()[0] == 0.0


def test_the_sidebar_scrolls_in_a_short_window(root, plan: InstallPlan) -> None:
    """All sixteen step rows stay reachable when the window is too short to show them."""
    from cloud_driver_installer.gui.app import MainWindow

    state = AppState(plan=plan)
    state.remote = FakeRemote(default_ok=True)
    window = MainWindow(root, state)
    root.geometry("800x420")
    root.deiconify()
    root.update()

    sidebar_scrollers = [child for child in window.sidebar.winfo_children() if isinstance(child, ScrollFrame)]
    assert sidebar_scrollers, "the step list must live in a scroller"
    scroller = sidebar_scrollers[0]
    assert scroller.body.winfo_reqheight() > scroller.canvas.winfo_height(), "the sixteen rows must not fit, or this proves nothing"
    assert scroller.scrollbar.winfo_manager(), "a clipped step list must show its scrollbar"

    # The real path: a wheel event over the list itself, routed like the operator's trackpad.
    x = scroller.canvas.winfo_rootx() + scroller.canvas.winfo_width() // 2
    y = scroller.canvas.winfo_rooty() + scroller.canvas.winfo_height() // 2
    root.event_generate("<MouseWheel>", delta=-3, rootx=x, rooty=y, x=5, y=5)
    root.update()
    assert scroller.canvas.yview()[0] > 0.0, "the wheel over the step list must scroll it"

    # And the run buttons below it stay where they are - they are not part of the scrolled area.
    buttons = [child for child in window.sidebar.winfo_children() if not isinstance(child, ScrollFrame)]
    assert buttons and all(child.winfo_ismapped() for child in buttons)

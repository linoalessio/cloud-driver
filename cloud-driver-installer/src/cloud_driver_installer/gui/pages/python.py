"""Python 3 page: read-only - what the check found and the one note from the mockup."""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk
from typing import TYPE_CHECKING, Any

from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Field, Hint
from cloud_driver_installer.steps import STEP_ORDER

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.model import Discovered, InstallPlan

NOTE = (
    "The check does what the intelligence installer learned the hard way: it actually creates a venv in a "
    "temp dir and looks for bin/pip, instead of trusting import venv. Only needed by the intelligence "
    "service, but cheap to satisfy on every box."
)


def detected_python(discovered: "Discovered") -> str:
    """One line for the ``python3`` row: version plus the venv verdict."""
    if not discovered.os_pretty:
        return "—"
    if not discovered.python_version:
        return "not found"
    if discovered.venv_works is None:
        return discovered.python_version
    return f"{discovered.python_version} · " + ("venv ok" if discovered.venv_works else "venv broken (ensurepip)")


class PythonPage(Page):
    """The ``python`` step: no inputs."""

    step_id = "python"
    title = dict(STEP_ORDER)["python"]

    def build(self, body: tk.Misc) -> None:
        """A single read-only row plus the note."""
        form = ttk.Frame(body)
        form.pack(fill="x")
        form.columnconfigure(1, weight=1)
        self._detected = ttk.Label(form, text="—", font="TkFixedFont")
        Field(form, "python3", self._detected)
        Hint(form, "Installs python3-venv and python3-pip when python3 -m venv cannot produce a working venv.").grid(
            row=form.grid_size()[1], column=1, sticky="w", pady=(0, 8)
        )
        Hint(body, NOTE).pack(anchor="w", pady=(12, 0))

    def load(self, plan: "InstallPlan") -> None:
        """Nothing to load: the page has no inputs."""

    def store(self, plan: "InstallPlan") -> None:
        """Nothing to store: the page has no inputs."""

    def refresh(self, state: Any) -> None:
        """Update the header (base class) and the detected-version row."""
        try:
            super().refresh(state)
        except NotImplementedError:
            pass
        self._detected.configure(text=detected_python(state.discovered))

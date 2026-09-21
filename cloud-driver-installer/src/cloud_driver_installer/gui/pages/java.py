"""Java 21 page: read-only - what the check found and the one note from the mockup."""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk
from typing import TYPE_CHECKING, Any

from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Field, Hint
from cloud_driver_installer.steps import STEP_ORDER

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.model import Discovered, InstallPlan

NOTE = "Only JDK 21 is supported by the backend build; a newer default-jdk from the distro is not accepted by the check."


def detected_java(discovered: "Discovered") -> str:
    """One line for the ``java on PATH`` row."""
    if not discovered.os_pretty:
        return "—"
    return discovered.java_version or "not found"


class JavaPage(Page):
    """The ``java`` step: no inputs."""

    step_id = "java"
    title = dict(STEP_ORDER)["java"]

    def build(self, body: tk.Misc) -> None:
        """A single read-only row plus the note."""
        form = ttk.Frame(body)
        form.pack(fill="x")
        form.columnconfigure(1, weight=1)
        self._detected = ttk.Label(form, text="—", font="TkFixedFont")
        Field(form, "java on PATH", self._detected)
        Hint(form, "Installs openjdk-21-jdk-headless and verifies that java -version reports 21.").grid(
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
        self._detected.configure(text=detected_java(state.discovered))

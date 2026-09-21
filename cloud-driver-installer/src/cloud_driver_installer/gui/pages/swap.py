"""Swap page: just the swapfile size (``plan.server.swap_mb``)."""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk
from typing import TYPE_CHECKING, Any

from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Field, Hint
from cloud_driver_installer.sizing import MIB, format_bytes
from cloud_driver_installer.steps import STEP_ORDER

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.model import Discovered, InstallPlan

#: The plan's bounds for ``swap_mb`` (0 = no swapfile).
MAX_SWAP_MB = 65536


def current_swap(discovered: "Discovered") -> str:
    """``currently: none`` / ``currently: 4 GiB`` once the preflight has run, blank before."""
    if not discovered.os_pretty:
        return ""
    return "currently: " + (format_bytes(discovered.swap_mib * MIB) if discovered.swap_mib else "none")


class SwapPage(Page):
    """The ``swap`` step's page."""

    step_id = "swap"
    title = dict(STEP_ORDER)["swap"]

    def build(self, body: tk.Misc) -> None:
        """One spinbox row with the inline ``/swapfile`` hint."""
        self.swap_var = tk.StringVar(master=body, value="4096")
        form = ttk.Frame(body)
        form.pack(fill="x")
        form.columnconfigure(1, weight=1)
        row = ttk.Frame(form)
        ttk.Spinbox(
            row, from_=0, to=MAX_SWAP_MB, increment=512, textvariable=self.swap_var, width=8, font="TkFixedFont"
        ).pack(side="left")
        Hint(row, "/swapfile, added to /etc/fstab").pack(side="left", padx=(10, 0))
        self._current = ttk.Label(row, text="", font="TkFixedFont")
        self._current.pack(side="left", padx=(12, 0))
        Field(form, "Swapfile size (MB)", row)
        Hint(
            form,
            "The kernel-OOM safety net behind the JVM heap: the reference box (7.7 GiB RAM, -Xmx5g) uses 4096 MB. "
            "0 means no swapfile.",
        ).grid(row=form.grid_size()[1], column=1, sticky="w", pady=(0, 8))

    def load(self, plan: "InstallPlan") -> None:
        """Fill the spinbox from ``plan.server.swap_mb``."""
        self.swap_var.set(str(plan.server.swap_mb))

    def store(self, plan: "InstallPlan") -> None:
        """Write the spinbox back; raises ``ValueError`` outside ``0..65536``."""
        try:
            size = int(self.swap_var.get().strip())
        except ValueError:
            raise ValueError(f"Swapfile size: must be a whole number of MB between 0 and {MAX_SWAP_MB}") from None
        if not 0 <= size <= MAX_SWAP_MB:
            raise ValueError(f"Swapfile size: must be between 0 and {MAX_SWAP_MB} MB")
        plan.server.swap_mb = size

    def refresh(self, state: Any) -> None:
        """Update the header (base class) and the ``currently:`` label."""
        try:
            super().refresh(state)
        except NotImplementedError:
            pass
        self._current.configure(text=current_swap(state.discovered))

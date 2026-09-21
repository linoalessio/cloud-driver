"""Firewall page: install and enable ufw, the fixed allow list, additional ports."""

from __future__ import annotations

import re
import tkinter as tk
from tkinter import ttk
from typing import TYPE_CHECKING, Any

from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Field, Hint
from cloud_driver_installer.steps import STEP_ORDER

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.model import Discovered, InstallPlan

#: What the step always allows, in order (SSH first so the step can never lock the operator out).
ALWAYS_ALLOWED: tuple[str, ...] = ("OpenSSH", "80/tcp", "443/tcp")

_PORT_SPEC_RE = re.compile(r"\d{1,5}(/(tcp|udp))?")
_OK_COLOUR = "#2FA84F"


def split_ports(text: str) -> list[str]:
    """``"9404/tcp, 8443"`` -> ``["9404/tcp", "8443"]`` (comma or whitespace separated)."""
    return [item for item in re.split(r"[,\s]+", text or "") if item]


def ufw_summary(discovered: "Discovered") -> str:
    """One line about the server's current firewall state, blank before the first check."""
    if discovered.ufw_active is None:
        return ""
    return "ufw is active on the server" if discovered.ufw_active else "ufw is not active on the server"


def set_enabled(widget: tk.Misc, enabled: bool) -> None:
    """Enable or disable ``widget`` and every descendant that understands a ttk state."""
    for child in widget.winfo_children():
        set_enabled(child, enabled)
    if isinstance(widget, ttk.Widget):
        try:
            widget.state(["!disabled"] if enabled else ["disabled"])
        except tk.TclError:
            pass


class FirewallPage(Page):
    """The ``firewall`` step's page (fields live in ``plan.server``)."""

    step_id = "firewall"
    title = dict(STEP_ORDER)["firewall"]

    # --- construction ----------------------------------------------------------------------------

    def build(self, body: tk.Misc) -> None:
        """Enable checkbox, the allow-list pills, the additional-ports entry and its hint."""
        self.enabled_var = tk.BooleanVar(master=body, value=True)
        self.extra_ports_var = tk.StringVar(master=body)

        ttk.Checkbutton(body, text="Install and enable ufw", variable=self.enabled_var, command=self._sync_enabled).pack(
            anchor="w", pady=(0, 8)
        )
        self._form = ttk.Frame(body)
        self._form.pack(fill="x")
        self._form.columnconfigure(1, weight=1)

        allowed_row = ttk.Frame(self._form)
        for spec in ALWAYS_ALLOWED:
            ttk.Label(allowed_row, text=spec, foreground=_OK_COLOUR, font="TkFixedFont").pack(side="left", padx=(0, 12))
        Field(self._form, "Allowed inbound", allowed_row)

        extra_row = ttk.Frame(self._form)
        ttk.Entry(extra_row, textvariable=self.extra_ports_var, font="TkFixedFont").pack(side="left", fill="x", expand=True)
        Hint(extra_row, "e.g. 9404/tcp - leave empty").pack(side="left", padx=(10, 0))
        Field(self._form, "Additional ports", extra_row)
        Hint(
            self._form,
            "Default policy: deny incoming, allow outgoing. SSH is always allowed first so the step can never lock you out.",
        ).grid(row=self._form.grid_size()[1], column=1, sticky="w", pady=(0, 8))

        self._ufw_state = ttk.Label(body, text="")
        self._ufw_state.pack(anchor="w", pady=(12, 0))
        self._sync_enabled()

    def _sync_enabled(self) -> None:
        """Grey the form while the firewall step is disabled."""
        set_enabled(self._form, bool(self.enabled_var.get()))

    # --- plan <-> widgets ------------------------------------------------------------------------

    def load(self, plan: "InstallPlan") -> None:
        """Fill the widgets from ``plan.server.firewall`` / ``firewall_extra_ports``."""
        self.enabled_var.set(plan.server.firewall)
        self.extra_ports_var.set(plan.server.firewall_extra_ports)
        self._sync_enabled()

    def store(self, plan: "InstallPlan") -> None:
        """Write the widgets back; raises ``ValueError`` when an additional port is not ``<port>[/tcp|/udp]``."""
        specs = split_ports(self.extra_ports_var.get())
        for spec in specs:
            if not _PORT_SPEC_RE.fullmatch(spec) or not 1 <= int(spec.split("/")[0]) <= 65535:
                raise ValueError(f"Additional ports: '{spec}' is not a port (e.g. 9404/tcp)")
        plan.server.firewall = bool(self.enabled_var.get())
        plan.server.firewall_extra_ports = " ".join(specs)

    def refresh(self, state: Any) -> None:
        """Update the header (base class) and the ufw state line."""
        try:
            super().refresh(state)
        except NotImplementedError:
            pass
        self._ufw_state.configure(text=ufw_summary(state.discovered))

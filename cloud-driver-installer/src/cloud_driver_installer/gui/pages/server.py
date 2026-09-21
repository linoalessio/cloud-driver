"""Server page: the facts the preflight discovered plus the OS-level choices.

Mirrors the mockup's Server page (the Discovered grid, install directory, screen session) and adds
the timezone, unattended-upgrades and ssh-alias options. The swapfile size and the firewall have
pages of their own (:mod:`.swap`, :mod:`.firewall`). Nothing here talks to the server: the facts
arrive through :meth:`ServerPage.refresh`.
"""

from __future__ import annotations

import re
import tkinter as tk
from tkinter import ttk
from typing import TYPE_CHECKING, Any

from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Field, Hint, Section
from cloud_driver_installer.sizing import MIB, format_bytes
from cloud_driver_installer.steps import STEP_ORDER

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.model import Discovered, InstallPlan

#: Zones offered by the timezone combobox; the blank first entry keeps the host's own setting.
COMMON_TIMEZONES: tuple[str, ...] = (
    "",
    "UTC",
    "Europe/Berlin",
    "Europe/Vienna",
    "Europe/Zurich",
    "Europe/Amsterdam",
    "Europe/Brussels",
    "Europe/Paris",
    "Europe/London",
    "Europe/Dublin",
    "Europe/Madrid",
    "Europe/Rome",
    "Europe/Stockholm",
    "Europe/Warsaw",
    "America/New_York",
    "America/Chicago",
    "America/Denver",
    "America/Los_Angeles",
    "America/Sao_Paulo",
    "Asia/Tokyo",
    "Asia/Singapore",
    "Asia/Kolkata",
    "Australia/Sydney",
)

#: ``(key, caption)`` of the Discovered grid, in the mockup's order.
FACT_CAPTIONS: tuple[tuple[str, str], ...] = (
    ("os", "OS"),
    ("kernel", "Kernel"),
    ("cpu", "CPU"),
    ("ram", "RAM"),
    ("swap", "Swap"),
    ("disk", "Free on /home"),
    ("ip", "Public IP"),
    ("ntp", "Time sync"),
    ("screen", "Screen session"),
)

#: Shown in every fact cell before the first check.
NOT_CHECKED = "—"

_NAME_RE = re.compile(r"[A-Za-z0-9_.-]+")


def fact_values(discovered: "Discovered") -> dict[str, str]:
    """Render the discovered facts the way the mockup's grid shows them.

    Every cell reads :data:`NOT_CHECKED` until the preflight has filled the corresponding field.
    """
    values: dict[str, str] = {key: NOT_CHECKED for key, _ in FACT_CAPTIONS}
    if discovered.os_pretty:
        values["os"] = discovered.os_pretty
        values["screen"] = "running" if discovered.screen_running else "not running"
    if discovered.kernel:
        values["kernel"] = discovered.kernel
    if discovered.cpu_count:
        values["cpu"] = f"{discovered.cpu_count} vCPU"
    if discovered.ram_mib:
        values["ram"] = format_bytes(discovered.ram_mib * MIB)
        values["swap"] = format_bytes(discovered.swap_mib * MIB) if discovered.swap_mib else "none"
    if discovered.disk_free_gib:
        values["disk"] = f"{discovered.disk_free_gib:.1f} GiB"
    if discovered.public_ip:
        values["ip"] = discovered.public_ip
    if discovered.ntp_synchronized is not None:
        values["ntp"] = "ok" if discovered.ntp_synchronized else "not synchronised"
    return values


class ServerPage(Page):
    """The ``server`` step: discovered facts, install directory, screen session, timezone, upgrades, ssh alias."""

    step_id = "server"
    title = dict(STEP_ORDER)["server"]

    def __init__(self, parent: tk.Misc, state: Any, actions: Any) -> None:
        self._facts: dict[str, ttk.Label] = {}
        super().__init__(parent, state, actions)

    # --- construction ----------------------------------------------------------------------------

    def build(self, body: tk.Misc) -> None:
        """Create the Discovered grid and the form (see the module docstring for the field set)."""
        self.install_dir_var = tk.StringVar(master=body, value="/home/cloud")
        self.screen_var = tk.StringVar(master=body, value="cloud")
        self.timezone_var = tk.StringVar(master=body)
        self.unattended_var = tk.BooleanVar(master=body, value=False)
        self.ssh_alias_var = tk.BooleanVar(master=body, value=False)
        self.ssh_alias_name_var = tk.StringVar(master=body, value="cloud_driver")

        facts = Section(body, "Discovered")
        facts.pack(fill="x", pady=(0, 12))
        for index, (key, caption) in enumerate(FACT_CAPTIONS):
            row, column = divmod(index, 3)
            facts.columnconfigure(column, weight=1, uniform="facts")
            cell = ttk.Frame(facts)
            cell.grid(row=row, column=column, sticky="ew", padx=(0, 18), pady=(0, 8))
            Hint(cell, caption.upper()).pack(anchor="w")
            value = ttk.Label(cell, text=NOT_CHECKED, font="TkFixedFont")
            value.pack(anchor="w")
            self._facts[key] = value

        form = ttk.Frame(body)
        form.pack(fill="x")
        form.columnconfigure(1, weight=1)

        Field(form, "Install directory", ttk.Entry(form, textvariable=self.install_dir_var, font="TkFixedFont"))
        self._hint(
            form,
            "Becomes the JVM's working directory: cloud-driver/, extensions/, upload-scratch/ and "
            "start-cloud.sh live inside it. Matches shell/deploy-cloud.sh.",
        )
        Field(form, "Screen session", ttk.Entry(form, textvariable=self.screen_var, font="TkFixedFont", width=12))
        self._hint(
            form,
            "The operator terminal needs a real TTY, so the JVM runs inside screen, not systemd. "
            "A @reboot cron entry re-launches it after a reboot.",
        )

        timezone_row = ttk.Frame(form)
        ttk.Combobox(timezone_row, textvariable=self.timezone_var, values=COMMON_TIMEZONES, width=26).pack(side="left")
        self._timezone_current = ttk.Label(timezone_row, text="", font="TkFixedFont")
        self._timezone_current.pack(side="left", padx=(10, 0))
        Field(form, "Timezone", timezone_row)
        self._hint(form, "Blank keeps the server's current timezone. The list is a suggestion; any IANA zone name is accepted.")

        Field(form, "", ttk.Checkbutton(form, text="Enable unattended security upgrades", variable=self.unattended_var))
        self._hint(form, "Installs unattended-upgrades for the security suite only; the box is never rebooted automatically.")

        alias_row = ttk.Frame(form)
        ttk.Checkbutton(
            alias_row,
            text="Write a ~/.ssh/config alias for the shell scripts",
            variable=self.ssh_alias_var,
            command=self._sync_alias_state,
        ).pack(side="left")
        ttk.Label(alias_row, text="name").pack(side="left", padx=(12, 6))
        self._alias_name_entry = ttk.Entry(alias_row, textvariable=self.ssh_alias_name_var, font="TkFixedFont", width=16)
        self._alias_name_entry.pack(side="left")
        Field(form, "", alias_row)
        self._hint(
            form,
            "Adds a Host entry on this machine so shell/deploy-cloud.sh and install-on-server.sh can target "
            "this box unchanged. Only the alias, host, port, user and key path are written - never a secret.",
        )
        self._sync_alias_state()

    @staticmethod
    def _hint(form: ttk.Frame, text: str) -> None:
        """Grid a hint under the widget column of the last field row."""
        Hint(form, text).grid(row=form.grid_size()[1], column=1, sticky="w", pady=(0, 8))

    def _sync_alias_state(self) -> None:
        """The alias name only matters while the alias checkbox is ticked."""
        self._alias_name_entry.state(["!disabled"] if self.ssh_alias_var.get() else ["disabled"])

    # --- plan <-> widgets ------------------------------------------------------------------------

    def load(self, plan: "InstallPlan") -> None:
        """Fill the widgets from ``plan.server``."""
        server = plan.server
        self.install_dir_var.set(server.install_dir)
        self.screen_var.set(server.screen_session)
        self.timezone_var.set(server.timezone)
        self.unattended_var.set(server.unattended_upgrades)
        self.ssh_alias_var.set(server.write_ssh_alias)
        self.ssh_alias_name_var.set(server.ssh_alias_name)
        self._sync_alias_state()

    def store(self, plan: "InstallPlan") -> None:
        """Write the widgets back into ``plan.server``; raises ``ValueError`` on a malformed value."""
        install_dir = self.install_dir_var.get().strip()
        if not install_dir.startswith("/"):
            raise ValueError("Install directory: must be an absolute path such as /home/cloud")
        screen = self.screen_var.get().strip()
        if not _NAME_RE.fullmatch(screen):
            raise ValueError("Screen session: may only contain letters, digits, . _ -")
        alias_name = self.ssh_alias_name_var.get().strip()
        if self.ssh_alias_var.get() and not _NAME_RE.fullmatch(alias_name):
            raise ValueError("SSH alias name: may only contain letters, digits, . _ -")
        server = plan.server
        server.install_dir = install_dir
        server.screen_session = screen
        server.timezone = self.timezone_var.get().strip()
        server.unattended_upgrades = bool(self.unattended_var.get())
        server.write_ssh_alias = bool(self.ssh_alias_var.get())
        server.ssh_alias_name = alias_name or server.ssh_alias_name

    def refresh(self, state: Any) -> None:
        """Update the header (base class) and the Discovered grid from ``state.discovered``."""
        try:
            super().refresh(state)
        except NotImplementedError:
            pass
        discovered = state.discovered
        for key, value in fact_values(discovered).items():
            self._facts[key].configure(text=value)
        self._timezone_current.configure(text=f"server: {discovered.timezone}" if discovered.timezone else "")

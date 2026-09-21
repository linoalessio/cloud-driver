"""Redis page: optional; install locally (loopback, requirepass) or point at an external server.

Password handling mirrors :mod:`.postgres`: a value read back from the server is displayed once
the check resolved it but never copied into the plan by :meth:`RedisPage.store`; Generate puts a
fresh value into the plan and ticks rotate. Unticking "Enable Redis" greys the whole form.
"""

from __future__ import annotations

import re
import tkinter as tk
from tkinter import ttk
from typing import TYPE_CHECKING, Any

from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Field, Hint, PortEntry, RadioRow, SecretEntry
from cloud_driver_installer.steps import STEP_ORDER

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.model import InstallPlan

#: ``actions.generate_secret`` kind for this page's password.
SECRET_KIND = "redis_password"

KEPT_TEXT = "kept from server"
GENERATED_TEXT = "new password - rotate is on"
NOTE = (
    "Redis must never hold file content or names. Everything it stores is a counter, a lock or an "
    "identifier - losing it costs a rate-limit window, nothing more."
)

MODES: tuple[tuple[str, str], ...] = (
    ("install", "Install on this server (loopback only, password-protected)"),
    ("external", "Use an external server"),
)

_DB_INDEX_RE = re.compile(r"\d{1,3}")


def parse_port(label: str, text: str) -> int:
    """``"6379"`` -> ``6379``; raises ``ValueError("<label>: …")`` for anything outside 1-65535."""
    try:
        port = int(text.strip())
    except ValueError:
        raise ValueError(f"{label}: must be a whole number between 1 and 65535") from None
    if not 1 <= port <= 65535:
        raise ValueError(f"{label}: must be between 1 and 65535")
    return port


def set_enabled(widget: tk.Misc, enabled: bool) -> None:
    """Enable or disable ``widget`` and every descendant that understands a ttk state."""
    for child in widget.winfo_children():
        set_enabled(child, enabled)
    if isinstance(widget, ttk.Widget):
        try:
            widget.state(["!disabled"] if enabled else ["disabled"])
        except tk.TclError:
            pass


class RedisPage(Page):
    """The ``redis`` step's page."""

    step_id = "redis"
    title = dict(STEP_ORDER)["redis"]

    def __init__(self, parent: tk.Misc, state: Any, actions: Any) -> None:
        self._actions = actions
        self._autofilled: str | None = None
        super().__init__(parent, state, actions)

    # --- construction ----------------------------------------------------------------------------

    def build(self, body: tk.Misc) -> None:
        """Enable checkbox, mode radios, host/port, username, database, password, rotate, note."""
        self.enabled_var = tk.BooleanVar(master=body, value=True)
        self.mode_var = tk.StringVar(master=body, value="install")
        self.host_var = tk.StringVar(master=body, value="127.0.0.1")
        self.port_var = tk.StringVar(master=body, value="6379")
        self.username_var = tk.StringVar(master=body)
        self.database_var = tk.StringVar(master=body, value="0")
        self.password_var = tk.StringVar(master=body)
        self.rotate_var = tk.BooleanVar(master=body, value=False)

        ttk.Checkbutton(body, text="Enable Redis", variable=self.enabled_var, command=self._sync_enabled).pack(
            anchor="w", pady=(0, 8)
        )
        self._form = ttk.Frame(body)
        self._form.pack(fill="x")
        RadioRow(self._form, variable=self.mode_var, options=list(MODES)).pack(anchor="w", pady=(0, 10))

        form = ttk.Frame(self._form)
        form.pack(fill="x")
        form.columnconfigure(1, weight=1)

        host_row = ttk.Frame(form)
        ttk.Entry(host_row, textvariable=self.host_var, font="TkFixedFont").pack(side="left", fill="x", expand=True)
        ttk.Label(host_row, text="Port").pack(side="left", padx=(12, 6))
        PortEntry(host_row, textvariable=self.port_var, width=8).pack(side="left")
        Field(form, "Host", host_row)

        user_row = ttk.Frame(form)
        ttk.Entry(user_row, textvariable=self.username_var, font="TkFixedFont").pack(side="left", fill="x", expand=True)
        Hint(user_row, "(none - requirepass only)").pack(side="left", padx=(10, 0))
        Field(form, "Username", user_row)

        Field(form, "Database", ttk.Entry(form, textvariable=self.database_var, font="TkFixedFont", width=8))
        self._hint(form, "A numeric index: the backend parses it with Integer.parseInt.")

        password_row = ttk.Frame(form)
        SecretEntry(password_row, textvariable=self.password_var, on_generate=self.generate_password).pack(
            side="left", fill="x", expand=True
        )
        self._password_note = ttk.Label(password_row, text="")
        self._password_note.pack(side="left", padx=(10, 0))
        Field(form, "Password", password_row)
        Field(form, "", ttk.Checkbutton(form, text="Rotate the existing password", variable=self.rotate_var))

        Hint(body, NOTE).pack(anchor="w", pady=(12, 0))
        self._sync_enabled()

    @staticmethod
    def _hint(form: ttk.Frame, text: str) -> None:
        """Grid a hint under the widget column of the last field row."""
        Hint(form, text).grid(row=form.grid_size()[1], column=1, sticky="w", pady=(0, 8))

    def _sync_enabled(self) -> None:
        """Grey the form while Redis is disabled."""
        set_enabled(self._form, bool(self.enabled_var.get()))

    # --- actions ---------------------------------------------------------------------------------

    def generate_password(self) -> str:
        """Put a fresh password into the field, tick rotate, and return it (for the Generate button)."""
        value = str(self._actions.generate_secret(SECRET_KIND))
        self.password_var.set(value)
        self.rotate_var.set(True)
        self._autofilled = None
        self._password_note.configure(text=GENERATED_TEXT)
        return value

    # --- plan <-> widgets ------------------------------------------------------------------------

    def load(self, plan: "InstallPlan") -> None:
        """Fill the widgets from ``plan.redis``; a displayed server password survives a blank plan value."""
        rd = plan.redis
        self.enabled_var.set(rd.enabled)
        self.mode_var.set(rd.mode)
        self.host_var.set(rd.host)
        self.port_var.set(str(rd.port))
        self.username_var.set(rd.username)
        self.database_var.set(rd.database)
        self.rotate_var.set(rd.rotate)
        if rd.password:
            self._autofilled = None
            self.password_var.set(rd.password)
            self._password_note.configure(text="")
        elif self._autofilled:
            self.password_var.set(self._autofilled)
        else:
            self.password_var.set("")
            self._password_note.configure(text="")
        self._sync_enabled()

    def store(self, plan: "InstallPlan") -> None:
        """Write the widgets back into ``plan.redis``; raises ``ValueError`` on a malformed value."""
        port = parse_port("Redis port", self.port_var.get())
        database = self.database_var.get().strip()
        if not _DB_INDEX_RE.fullmatch(database):
            raise ValueError("Redis database: must be a numeric index such as 0")
        rd = plan.redis
        rd.enabled = bool(self.enabled_var.get())
        rd.mode = self.mode_var.get() or "install"
        rd.host = self.host_var.get().strip()
        rd.port = port
        rd.username = self.username_var.get().strip()
        rd.database = database
        rd.rotate = bool(self.rotate_var.get())
        password = self.password_var.get()
        if self._autofilled is None or password != self._autofilled:
            rd.password = password

    def refresh(self, state: Any) -> None:
        """Update the header (base class) and show a password the check read back from the server."""
        try:
            super().refresh(state)
        except NotImplementedError:
            pass
        secrets = state.secrets
        if secrets.redis_password and not self.password_var.get():
            self._autofilled = secrets.redis_password
            self.password_var.set(secrets.redis_password)
        if self._autofilled and self.password_var.get() == self._autofilled:
            self._password_note.configure(text=KEPT_TEXT if secrets.redis_password_kept else "generated")

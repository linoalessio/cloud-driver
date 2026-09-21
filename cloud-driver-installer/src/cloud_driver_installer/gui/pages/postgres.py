"""PostgreSQL page: install locally or point at an external server, role, database, password.

Password semantics follow the plan: blank + install mode means "generate (or keep the server's
existing one)". A password the step read back from the server is *displayed* once the check has
resolved it ("kept from server") but never copied into the plan by :meth:`PostgresPage.store`, so
the step keeps deciding; Generate puts a fresh value into the plan and ticks rotate.
"""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk
from typing import TYPE_CHECKING, Any

from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Field, Hint, PortEntry, RadioRow, SecretEntry
from cloud_driver_installer.steps import STEP_ORDER

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.model import InstallPlan

#: ``actions.generate_secret`` kind for this page's password.
SECRET_KIND = "pg_password"

KEPT_TEXT = "kept from server"
GENERATED_TEXT = "new password - rotate is on: the role and postgres-database.json are rewritten together"

MODES: tuple[tuple[str, str], ...] = (
    ("install", "Install on this server"),
    ("external", "Use an external server (only write the credentials file)"),
)


def parse_port(label: str, text: str) -> int:
    """``"5432"`` -> ``5432``; raises ``ValueError("<label>: …")`` for anything outside 1-65535."""
    try:
        port = int(text.strip())
    except ValueError:
        raise ValueError(f"{label}: must be a whole number between 1 and 65535") from None
    if not 1 <= port <= 65535:
        raise ValueError(f"{label}: must be between 1 and 65535")
    return port


class PostgresPage(Page):
    """The ``postgres`` step's page."""

    step_id = "postgres"
    title = dict(STEP_ORDER)["postgres"]

    def __init__(self, parent: tk.Misc, state: Any, actions: Any) -> None:
        self._actions = actions
        #: The value shown because the server already had it (display only, see the module docstring).
        self._autofilled: str | None = None
        super().__init__(parent, state, actions)

    # --- construction ----------------------------------------------------------------------------

    def build(self, body: tk.Misc) -> None:
        """Mode radios, host/port, database, username, password (+ Show/Generate), rotate, Test connection."""
        self.mode_var = tk.StringVar(master=body, value="install")
        self.host_var = tk.StringVar(master=body, value="127.0.0.1")
        self.port_var = tk.StringVar(master=body, value="5432")
        self.database_var = tk.StringVar(master=body, value="cloud_driver")
        self.username_var = tk.StringVar(master=body, value="cloud_driver")
        self.password_var = tk.StringVar(master=body)
        self.rotate_var = tk.BooleanVar(master=body, value=False)

        RadioRow(body, variable=self.mode_var, options=list(MODES)).pack(anchor="w", pady=(0, 10))

        form = ttk.Frame(body)
        form.pack(fill="x")
        form.columnconfigure(1, weight=1)

        host_row = ttk.Frame(form)
        ttk.Entry(host_row, textvariable=self.host_var, font="TkFixedFont").pack(side="left", fill="x", expand=True)
        ttk.Label(host_row, text="Port").pack(side="left", padx=(12, 6))
        PortEntry(host_row, textvariable=self.port_var, width=8).pack(side="left")
        Field(form, "Host", host_row)

        Field(form, "Database", ttk.Entry(form, textvariable=self.database_var, font="TkFixedFont"))
        Field(form, "Username", ttk.Entry(form, textvariable=self.username_var, font="TkFixedFont"))
        self._hint(
            form,
            "The role becomes the database owner, so no grant can be missing later "
            "(a missing grant fails silently to stderr at runtime).",
        )

        password_row = ttk.Frame(form)
        SecretEntry(password_row, textvariable=self.password_var, on_generate=self.generate_password).pack(
            side="left", fill="x", expand=True
        )
        self._password_note = ttk.Label(password_row, text="")
        self._password_note.pack(side="left", padx=(10, 0))
        Field(form, "Password", password_row)
        self._hint(
            form,
            "Generated with 48 hex characters. On a re-run the value from the server's existing "
            "postgres-database.json is shown as “kept from server” unless you rotate.",
        )
        Field(
            form,
            "",
            ttk.Checkbutton(
                form,
                text="Rotate the existing password (rewrites the role and the file together)",
                variable=self.rotate_var,
            ),
        )

        test_row = ttk.Frame(form)
        ttk.Button(test_row, text="Test connection", command=self.test_connection).pack(side="left")
        Hint(
            test_row,
            "Runs psql on the server as this role, password passed through the environment, never on a command line.",
        ).pack(side="left", padx=(10, 0))
        Field(form, "", test_row)

    @staticmethod
    def _hint(form: ttk.Frame, text: str) -> None:
        """Grid a hint under the widget column of the last field row."""
        Hint(form, text).grid(row=form.grid_size()[1], column=1, sticky="w", pady=(0, 8))

    # --- actions ---------------------------------------------------------------------------------

    def generate_password(self) -> str:
        """Put a fresh password into the field, tick rotate, and return it (for the Generate button)."""
        value = str(self._actions.generate_secret(SECRET_KIND))
        self.password_var.set(value)
        self.rotate_var.set(True)
        self._autofilled = None
        self._password_note.configure(text=GENERATED_TEXT)
        return value

    def test_connection(self) -> None:
        """The mockup's Test connection button: re-run this step's check (a psql login for the role)."""
        self._actions.check(self.step_id)

    # --- plan <-> widgets ------------------------------------------------------------------------

    def load(self, plan: "InstallPlan") -> None:
        """Fill the widgets from ``plan.postgres``; a displayed server password survives a blank plan value."""
        pg = plan.postgres
        self.mode_var.set(pg.mode)
        self.host_var.set(pg.host)
        self.port_var.set(str(pg.port))
        self.database_var.set(pg.database)
        self.username_var.set(pg.username)
        self.rotate_var.set(pg.rotate)
        if pg.password:
            self._autofilled = None
            self.password_var.set(pg.password)
            self._password_note.configure(text="")
        elif self._autofilled:
            self.password_var.set(self._autofilled)
        else:
            self.password_var.set("")
            self._password_note.configure(text="")

    def store(self, plan: "InstallPlan") -> None:
        """Write the widgets back into ``plan.postgres``; raises ``ValueError`` on a malformed value."""
        port = parse_port("PostgreSQL port", self.port_var.get())
        host = self.host_var.get().strip()
        if not host:
            raise ValueError("PostgreSQL host: is required")
        pg = plan.postgres
        pg.mode = self.mode_var.get() or "install"
        pg.host = host
        pg.port = port
        pg.database = self.database_var.get().strip()
        pg.username = self.username_var.get().strip()
        pg.rotate = bool(self.rotate_var.get())
        password = self.password_var.get()
        if self._autofilled is None or password != self._autofilled:
            pg.password = password

    def refresh(self, state: Any) -> None:
        """Update the header (base class) and show a password the check read back from the server."""
        try:
            super().refresh(state)
        except NotImplementedError:
            pass
        secrets = state.secrets
        if secrets.pg_password and not self.password_var.get():
            self._autofilled = secrets.pg_password
            self.password_var.set(secrets.pg_password)
        if self._autofilled and self.password_var.get() == self._autofilled:
            self._password_note.configure(text=KEPT_TEXT if secrets.pg_password_kept else "generated")

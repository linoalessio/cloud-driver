"""Configuration-files page: JWT key handling, local write-back and a masked preview of every file.

The preview is rendered from the *plan* (so it reflects every other page) with
:func:`~cloud_driver_installer.config_files.render_configuration` merged over the server's
existing ``configuration.json`` (``discovered.existing_config``) and passed through
:func:`~cloud_driver_installer.config_files.masked`, exactly what the step will write minus the
secrets. Secrets the run has not resolved yet are shown as bullets too.
"""

from __future__ import annotations

import copy
import tkinter as tk
from pathlib import Path
from tkinter import ttk
from typing import Any

from cloud_driver_installer.config_files import (
    masked,
    render_configuration,
    render_postgres_credentials,
    render_redis_credentials,
    render_start_env,
    to_json,
)
from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Hint, Section
from cloud_driver_installer.model import GeneratedSecrets, InstallPlan

__all__ = ["ConfigPage", "preview_documents"]

MUTED_COLOUR = "#6E6E73"
LABEL_COLUMN_PX = 170

#: Placeholder for a secret the run will generate; ``masked()`` turns it into bullets.
PENDING_SECRET = "<generated at apply>"
#: Bootstrap jar name when neither a built jar nor a remote one is known (the reference release).
DEFAULT_BOOTSTRAP_JAR = "cloud-driver-bootstrap-1.0.7.jar"


def preview_documents(plan: InstallPlan, secrets: GeneratedSecrets, existing_config: dict[str, Any] | None, jar_name: str) -> dict[str, str]:
    """Render every file the config step writes, secrets masked, keyed by file name."""
    preview = copy.copy(secrets)
    if not preview.jwt_signing_key:
        preview.jwt_signing_key = PENDING_SECRET
    if plan.intelligence.enabled and not preview.intelligence_secret:
        preview.intelligence_secret = PENDING_SECRET
    documents = {
        "configuration.json": to_json(masked(render_configuration(plan, preview, existing=existing_config))),
        "postgres-database.json": to_json(masked(render_postgres_credentials(plan, "••••"))),
    }
    if plan.redis.enabled:
        documents["redis-database.json"] = to_json(masked(render_redis_credentials(plan, "••••")))
    documents["start-cloud.env"] = render_start_env(plan, jar_name)
    return documents


def bootstrap_jar_name(plan: InstallPlan, existing_jars: list[str]) -> str:
    """The jar name ``start-cloud.env`` will carry: the application step's helper, else a local/remote/default guess."""
    try:
        from cloud_driver_installer.steps.application import ApplicationStep

        name = ApplicationStep.bootstrap_jar_name(plan)
        if name:
            return str(name)
    except Exception:  # noqa: BLE001 - the step module may still be missing; the preview must not crash
        pass
    if plan.app.repo_root:
        target = Path(plan.app.repo_root) / "cloud-driver-bootstrap" / "target"
        candidates = [p for p in target.glob("cloud-driver-bootstrap-*.jar") if not p.name.startswith("original-")] if target.is_dir() else []
        if candidates:
            return max(candidates, key=lambda p: p.stat().st_mtime).name
    for name in existing_jars:
        base = Path(name).name
        if base.startswith("cloud-driver-bootstrap-") and base.endswith(".jar"):
            return base
    return DEFAULT_BOOTSTRAP_JAR


def _form(parent: tk.Misc) -> ttk.Frame:
    """A grid form with the mockup's 170 px right-aligned label column."""
    frame = ttk.Frame(parent)
    frame.columnconfigure(0, minsize=LABEL_COLUMN_PX)
    frame.columnconfigure(1, weight=1)
    return frame


def _row(form: ttk.Frame, row: int, label: str, widget: tk.Misc, hint: str | None = None) -> int:
    """Grid ``label`` and ``widget`` (plus an optional hint); returns the next free row."""
    ttk.Label(form, text=label, anchor="e").grid(row=row, column=0, sticky="e", padx=(0, 14), pady=3)
    widget.grid(row=row, column=1, sticky="ew", pady=3)
    row += 1
    if hint:
        Hint(form, hint).grid(row=row, column=1, sticky="w", pady=(0, 4))
        row += 1
    return row


def _check_row(form: ttk.Frame, row: int, text: str, variable: tk.BooleanVar, hint: str | None = None) -> int:
    """Grid a checkbutton in the value column."""
    ttk.Checkbutton(form, text=text, variable=variable).grid(row=row, column=1, sticky="w", pady=2)
    row += 1
    if hint:
        Hint(form, hint).grid(row=row, column=1, sticky="w", pady=(0, 4))
        row += 1
    return row


def _set_text(widget: tk.Text, content: str) -> None:
    """Replace the contents of a read-only ``tk.Text``."""
    widget.configure(state="normal")
    widget.delete("1.0", "end")
    widget.insert("1.0", content)
    widget.configure(state="disabled")


def _preview_box(parent: tk.Misc, title: str, height: int) -> tk.Text:
    """A captioned read-only monospace box (the mockup's ``.jsonprev``)."""
    ttk.Label(parent, text=title, foreground=MUTED_COLOUR, font="TkFixedFont").pack(anchor="w")
    box = tk.Text(parent, height=height, font="TkFixedFont", wrap="none", relief="flat", borderwidth=1, highlightthickness=1)
    box.pack(fill="x", pady=(2, 8))
    box.configure(state="disabled")
    return box


class ConfigPage(Page):
    """JWT key, local write-back and the masked preview of the four files."""

    step_id = "config"
    title = "Configuration files"
    #: The mockup labels this page's header check button "Preview".
    check_label = "Preview"

    def build(self, body: tk.Misc) -> None:
        """Key rows, the write-back box, then the previews in two columns."""
        self.intro = Hint(body, "Writes configuration.json, postgres-database.json, redis-database.json and start-cloud.env on the server.")
        self.intro.pack(anchor="w", fill="x", pady=(0, 8))

        form = _form(body)
        form.pack(fill="x")
        row = 0
        jwt_line = ttk.Frame(form)
        self.jwt_label = ttk.Label(jwt_line, text="generated · 32 random bytes, base64", foreground=MUTED_COLOUR, font="TkFixedFont")
        self.jwt_label.pack(side="left")
        self.jwt_rotate = tk.BooleanVar(value=False)
        ttk.Checkbutton(jwt_line, text="rotate", variable=self.jwt_rotate).pack(side="left", padx=(12, 0))
        row = _row(form, row, "JWT signing key", jwt_line, "Kept from the server on a re-run; rotating logs every client out.")

        secret_line = ttk.Frame(form)
        self.intelligence_label = ttk.Label(secret_line, text="generated · 32 random bytes, base64", foreground=MUTED_COLOUR, font="TkFixedFont")
        self.intelligence_label.pack(side="left")
        self.secret_rotate = tk.BooleanVar(value=False)
        self.secret_rotate_box = ttk.Checkbutton(secret_line, text="rotate", variable=self.secret_rotate)
        self.secret_rotate_box.pack(side="left", padx=(12, 0))
        row = _row(form, row, "Intelligence secret", secret_line, "Shared between configuration.json and the service's env file; only written when the intelligence service is enabled.")

        self.write_local_config = tk.BooleanVar(value=True)
        self.local_hint_text = "shell/deploy-cloud.sh and the intelligence installer read the local copies. Skipping this means their next run would overwrite the server with stale files."
        self.write_local_box = ttk.Checkbutton(form, text="Also write the three files to …/cloud-driver/ in this checkout (gitignored)", variable=self.write_local_config)
        self.write_local_box.grid(row=row, column=1, sticky="w", pady=2)
        row += 1
        Hint(form, self.local_hint_text).grid(row=row, column=1, sticky="w", pady=(0, 4))
        row += 1
        ttk.Button(form, text="Refresh preview", command=self._refresh_preview).grid(row=row, column=1, sticky="w", pady=(4, 0))

        two = ttk.Frame(body)
        two.pack(fill="both", expand=True, pady=(10, 0))
        two.columnconfigure(0, weight=1, uniform="two")
        two.columnconfigure(1, weight=1, uniform="two")
        left = Section(two, "configuration.json")
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 6))
        self.configuration_box = tk.Text(left, height=30, font="TkFixedFont", wrap="none", relief="flat", borderwidth=1, highlightthickness=1)
        self.configuration_box.pack(fill="both", expand=True)
        self.configuration_box.configure(state="disabled")
        right = Section(two, "Credentials and start-cloud.env")
        right.grid(row=0, column=1, sticky="nsew", padx=(6, 0))
        self.postgres_box = _preview_box(right, "# postgres-database.json", 8)
        self.redis_box = _preview_box(right, "# redis-database.json", 8)
        self.env_box = _preview_box(right, "# start-cloud.env  (sourced by start-cloud.sh)", 5)
        Hint(right, "Existing files on the server are merged, never replaced: keys the installer does not manage stay, every file gets a .bak-<timestamp> first.").pack(anchor="w", fill="x")
        self._last_state: Any = None

    # --- plan <-> widgets ------------------------------------------------------------------------

    def load(self, plan: InstallPlan) -> None:
        """Copy the two rotate flags and the write-back choice into the widgets."""
        self.jwt_rotate.set(plan.app.jwt_rotate)
        self.secret_rotate.set(plan.intelligence.secret_rotate)
        self.write_local_config.set(plan.app.write_local_config)
        self._apply_plan_labels(plan)

    def store(self, plan: InstallPlan) -> None:
        """Copy the widgets into the plan (``app.jwt_rotate``, ``intelligence.secret_rotate``, ``app.write_local_config``)."""
        plan.app.jwt_rotate = bool(self.jwt_rotate.get())
        plan.intelligence.secret_rotate = bool(self.secret_rotate.get())
        plan.app.write_local_config = bool(self.write_local_config.get())

    def refresh(self, state: Any) -> None:
        """Header via the base class; key labels from the resolved secrets; previews from the plan."""
        _refresh_base(self, state)
        self._last_state = state
        plan: InstallPlan = state.plan
        secrets: GeneratedSecrets = state.secrets
        self._apply_plan_labels(plan)
        self.jwt_label.configure(text=_key_state("32 random bytes, base64", secrets.jwt_signing_key, secrets.jwt_kept, self.jwt_rotate.get()))
        if plan.intelligence.enabled:
            self.intelligence_label.configure(text=_key_state("32 random bytes, base64", secrets.intelligence_secret, secrets.intelligence_secret_kept, self.secret_rotate.get()))
            self.secret_rotate_box.state(["!disabled"])
        else:
            self.intelligence_label.configure(text="not written — intelligence service disabled")
            self.secret_rotate_box.state(["disabled"])
        existing = getattr(state.discovered, "existing_config", None) or {}
        jar_name = bootstrap_jar_name(plan, list(getattr(state.discovered, "existing_jars", []) or []))
        documents = preview_documents(plan, secrets, existing, jar_name)
        _set_text(self.configuration_box, documents["configuration.json"])
        _set_text(self.postgres_box, documents["postgres-database.json"])
        _set_text(self.redis_box, documents.get("redis-database.json", "# Redis disabled — an existing redis-database.json on the server is left alone.\n"))
        _set_text(self.env_box, documents["start-cloud.env"])

    def _apply_plan_labels(self, plan: InstallPlan) -> None:
        self.intro.configure(text=f"Writes configuration.json, postgres-database.json, redis-database.json into {plan.config_dir} and start-cloud.env into {plan.server.install_dir}.")
        repo = plan.app.repo_root or "…"
        self.write_local_box.configure(text=f"Also write the three files to {repo.rstrip('/')}/cloud-driver/ in this checkout (gitignored)")

    def _refresh_preview(self) -> None:
        """Re-render the previews from the current state (the plan may have changed on other pages)."""
        if self._last_state is not None:
            self.refresh(self._last_state)
        elif getattr(self, "state", None) is not None:
            self.refresh(self.state)


def _key_state(how: str, value: str, kept: bool, rotate: bool) -> str:
    """``generated · …`` / ``kept from server`` / ``will be rotated`` for the key labels."""
    if rotate:
        return f"will be rotated · {how}"
    if kept:
        return "kept from server"
    if value:
        return f"generated · {how} (resolved)"
    return f"generated · {how}"


def _refresh_base(page: Page, state: Any) -> None:
    """Run the base class's header refresh when it provides one."""
    base_refresh = getattr(Page, "refresh", None)
    if base_refresh is None:
        return
    try:
        base_refresh(page, state)
    except NotImplementedError:
        pass

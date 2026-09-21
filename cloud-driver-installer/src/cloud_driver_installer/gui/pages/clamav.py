"""ClamAV page: enable malware scanning, the loopback listener and clamd's size limits."""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk
from typing import TYPE_CHECKING, Any

from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Field, Hint, PortEntry, Section
from cloud_driver_installer.sizing import format_bytes, parse_clamd_size
from cloud_driver_installer.steps import STEP_ORDER

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.model import InstallPlan

LISTENER_NOTE = (
    "IPv4 only, on purpose: a second (IPv6) ListenStream crash-loops clamd 1.4. clamd has no "
    "authentication, so it is never bound beyond loopback."
)
LIMITS_NOTE = "Kept above the app's own ceiling so nothing in the gap is silently marked clean without a scan."
FRESHCLAM_NOTE = (
    "freshclam's first signature download runs in the background and takes minutes; the summary reminds "
    "you before trusting scan results."
)

#: ``(plan attribute, label)`` of the three clamd.conf limits, in the mockup's order.
SIZE_LIMITS: tuple[tuple[str, str], ...] = (
    ("stream_max_length", "StreamMaxLength"),
    ("max_file_size", "MaxFileSize"),
    ("max_scan_size", "MaxScanSize"),
)


def parse_int(label: str, text: str, low: int, high: int | None = None) -> int:
    """Parse a whole number in ``[low, high]``; raises ``ValueError("<label>: …")`` otherwise."""
    bounds = f"between {low} and {high}" if high is not None else f"of at least {low}"
    try:
        value = int(text.strip())
    except ValueError:
        raise ValueError(f"{label}: must be a whole number {bounds}") from None
    if value < low or (high is not None and value > high):
        raise ValueError(f"{label}: must be {bounds}")
    return value


def set_enabled(widget: tk.Misc, enabled: bool) -> None:
    """Enable or disable ``widget`` and every descendant that understands a ttk state."""
    for child in widget.winfo_children():
        set_enabled(child, enabled)
    if isinstance(widget, ttk.Widget):
        try:
            widget.state(["!disabled"] if enabled else ["disabled"])
        except tk.TclError:
            pass


class ClamavPage(Page):
    """The ``clamav`` step's page."""

    step_id = "clamav"
    title = dict(STEP_ORDER)["clamav"]

    # --- construction ----------------------------------------------------------------------------

    def build(self, body: tk.Misc) -> None:
        """Enable checkbox, the Listener and Size limits groups side by side, the freshclam note."""
        self.enabled_var = tk.BooleanVar(master=body, value=True)
        self.host_var = tk.StringVar(master=body, value="127.0.0.1")
        self.port_var = tk.StringVar(master=body, value="3310")
        self.timeout_var = tk.StringVar(master=body, value="30")
        self.limit_vars: dict[str, tk.StringVar] = {
            "stream_max_length": tk.StringVar(master=body, value="128M"),
            "max_file_size": tk.StringVar(master=body, value="128M"),
            "max_scan_size": tk.StringVar(master=body, value="300M"),
        }
        self.content_max_var = tk.StringVar(master=body, value="104857600")

        ttk.Checkbutton(
            body,
            text="Enable malware scanning (deploys the scan extension)",
            variable=self.enabled_var,
            command=self._sync_enabled,
        ).pack(anchor="w", pady=(0, 8))

        self._two = ttk.Frame(body)
        self._two.pack(fill="x")
        self._two.columnconfigure(0, weight=1, uniform="two")
        self._two.columnconfigure(1, weight=1, uniform="two")

        listener = Section(self._two, "Listener")
        listener.grid(row=0, column=0, sticky="nsew", padx=(0, 6))
        form = ttk.Frame(listener)
        form.pack(fill="x")
        form.columnconfigure(1, weight=1)
        Field(form, "Host", ttk.Entry(form, textvariable=self.host_var, font="TkFixedFont"))
        Field(form, "Port", PortEntry(form, textvariable=self.port_var, width=8))
        Field(form, "Timeout (s)", ttk.Entry(form, textvariable=self.timeout_var, font="TkFixedFont", width=8))
        Hint(listener, LISTENER_NOTE).pack(anchor="w", pady=(8, 0))

        limits = Section(self._two, "Size limits")
        limits.grid(row=0, column=1, sticky="nsew", padx=(6, 0))
        form = ttk.Frame(limits)
        form.pack(fill="x")
        form.columnconfigure(1, weight=1)
        for attr, label in SIZE_LIMITS:
            Field(form, label, ttk.Entry(form, textvariable=self.limit_vars[attr], font="TkFixedFont", width=8))
        max_row = ttk.Frame(form)
        ttk.Entry(max_row, textvariable=self.content_max_var, font="TkFixedFont", width=14).pack(side="left")
        self._content_max_pill = ttk.Label(max_row, text="")
        self._content_max_pill.pack(side="left", padx=(10, 0))
        Field(form, "content-scan-max-bytes", max_row)
        Hint(limits, LIMITS_NOTE).pack(anchor="w", pady=(8, 0))

        Hint(body, FRESHCLAM_NOTE).pack(anchor="w", pady=(12, 0))

        self.content_max_var.trace_add("write", lambda *_: self._sync_content_max_pill())
        self._sync_content_max_pill()
        self._sync_enabled()

    def _sync_content_max_pill(self) -> None:
        """Mirror the byte count as ``100 MiB`` next to the entry."""
        try:
            self._content_max_pill.configure(text=format_bytes(int(self.content_max_var.get().strip())))
        except ValueError:
            self._content_max_pill.configure(text="?")

    def _sync_enabled(self) -> None:
        """Grey both groups while scanning is disabled."""
        set_enabled(self._two, bool(self.enabled_var.get()))

    # --- plan <-> widgets ------------------------------------------------------------------------

    def load(self, plan: "InstallPlan") -> None:
        """Fill the widgets from ``plan.clamav``."""
        cl = plan.clamav
        self.enabled_var.set(cl.enabled)
        self.host_var.set(cl.host)
        self.port_var.set(str(cl.port))
        self.timeout_var.set(str(cl.timeout_seconds))
        for attr, _ in SIZE_LIMITS:
            self.limit_vars[attr].set(getattr(cl, attr))
        self.content_max_var.set(str(cl.content_scan_max_bytes))
        self._sync_enabled()

    def store(self, plan: "InstallPlan") -> None:
        """Write the widgets back into ``plan.clamav``; raises ``ValueError`` on a malformed value."""
        port = parse_int("ClamAV port", self.port_var.get(), 1, 65535)
        timeout = parse_int("ClamAV timeout", self.timeout_var.get(), 1, 3600)
        limits: dict[str, str] = {}
        for attr, label in SIZE_LIMITS:
            text = self.limit_vars[attr].get().strip()
            try:
                parse_clamd_size(text)
            except ValueError as exc:
                raise ValueError(f"ClamAV {label}: {exc}") from None
            limits[attr] = text
        content_max = parse_int("ClamAV content-scan-max-bytes", self.content_max_var.get(), 1)
        host = self.host_var.get().strip()
        if not host:
            raise ValueError("ClamAV host: is required")
        cl = plan.clamav
        cl.enabled = bool(self.enabled_var.get())
        cl.host = host
        cl.port = port
        cl.timeout_seconds = timeout
        for attr, text in limits.items():
            setattr(cl, attr, text)
        cl.content_scan_max_bytes = content_max

    def refresh(self, state: Any) -> None:
        """Update the header (base class); the page has no other derived labels."""
        try:
            super().refresh(state)
        except NotImplementedError:
            pass

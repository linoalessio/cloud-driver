"""E-mail page: the transport for registration, password-reset and e-mail-change codes.

Three modes as in the mockup - none (log only), AWS SES and SMTP - with the SES and SMTP groups
greyed out when not chosen. Edits :class:`~cloud_driver_installer.model.EmailSettings` only; the
identity's verification state comes back through the step status the base header shows and is
mirrored by a small label next to the from address.
"""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk
from typing import Any

from cloud_driver_installer.aws import COMMON_REGIONS
from cloud_driver_installer.engine import StepStatus
from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Hint, PortEntry, SecretEntry, Section
from cloud_driver_installer.model import InstallPlan

__all__ = ["EmailPage"]

OK_COLOUR = "#2FA84F"
WARN_COLOUR = "#D9840A"
FAIL_COLOUR = "#E5342A"
MUTED_COLOUR = "#6E6E73"
LABEL_COLUMN_PX = 170

SANDBOX_NOTE = (
    "A new SES account is in the sandbox: 200 mails/day and every recipient must be verified too, "
    "until AWS grants production access. Add the DKIM CNAME records SES shows for your domain, or mail lands in spam."
)


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


def _radios(parent: tk.Misc, variable: tk.StringVar, options: list[tuple[str, str]]) -> ttk.Frame:
    """A horizontal row of radio buttons bound to ``variable``."""
    frame = ttk.Frame(parent)
    for value, label in options:
        ttk.Radiobutton(frame, text=label, value=value, variable=variable).pack(side="left", padx=(0, 18))
    return frame


def _value(widget: Any) -> str:
    """Text of a tk variable, composite widget or entry."""
    return str(widget.get())


def _assign(widget: Any, value: Any) -> None:
    """Write ``value`` into a tk variable, composite widget or plain entry."""
    setter = getattr(widget, "set", None)
    if callable(setter):
        setter(value)
        return
    widget.delete(0, "end")
    widget.insert(0, str(value))


def _set_enabled(widget: tk.Misc, enabled: bool) -> None:
    """Enable or disable ``widget`` and every descendant."""
    for target in [widget, *_descendants(widget)]:
        try:
            target.state(["!disabled" if enabled else "disabled"])  # type: ignore[attr-defined]
        except (AttributeError, tk.TclError):
            try:
                target.configure(state="normal" if enabled else "disabled")  # type: ignore[call-arg]
            except tk.TclError:
                pass


def _descendants(widget: tk.Misc) -> list[tk.Misc]:
    """Every widget below ``widget``."""
    found: list[tk.Misc] = []
    for child in widget.winfo_children():
        found.append(child)
        found.extend(_descendants(child))
    return found


def _int_field(widget: Any, label: str) -> int:
    """Parse an integer field, naming it in the error the header shows."""
    text = _value(widget).strip()
    try:
        return int(text)
    except ValueError as exc:
        raise ValueError(f"{label}: must be a whole number, not {text!r}") from exc


class EmailPage(Page):
    """None / AWS SES / SMTP transport for the verification codes."""

    step_id = "email"
    title = "E-mail"

    def build(self, body: tk.Misc) -> None:
        """Mode radios, the SES group and the SMTP group."""
        Hint(body, "Registration, password-reset and e-mail-change codes go out this way. Without a transport they are only printed to the server log.").pack(anchor="w", fill="x", pady=(0, 8))
        self.mode = tk.StringVar(value="ses")
        _radios(body, self.mode, [("none", "None (log only — not for production)"), ("ses", "AWS SES"), ("smtp", "SMTP")]).pack(anchor="w", pady=(0, 6))
        self._build_ses(body)
        self._build_smtp(body)
        self.mode.trace_add("write", lambda *_args: self._sync_enabled())
        self._sync_enabled()

    def _build_ses(self, body: tk.Misc) -> None:
        self.ses_section = Section(body, "AWS SES")
        self.ses_section.pack(fill="x")
        form = _form(self.ses_section)
        form.pack(fill="x")
        row = 0
        self.ses_region = ttk.Combobox(form, values=["", *COMMON_REGIONS])
        self.ses_region_hint = Hint(form, "blank = same as the KMS region")
        ttk.Label(form, text="Region", anchor="e").grid(row=row, column=0, sticky="e", padx=(0, 14), pady=3)
        self.ses_region.grid(row=row, column=1, sticky="ew", pady=3)
        row += 1
        self.ses_region_hint.grid(row=row, column=1, sticky="w", pady=(0, 4))
        row += 1

        from_line = ttk.Frame(form)
        self.ses_from_address = ttk.Entry(from_line)
        self.ses_from_address.pack(side="left", fill="x", expand=True)
        self.verification_label = ttk.Label(from_line, text="not checked", foreground=MUTED_COLOUR)
        self.verification_label.pack(side="left", padx=(8, 0))
        row = _row(form, row, "From address", from_line)

        self.ses_identity_mode = tk.StringVar(value="domain")
        row = _row(form, row, "Identity", _radios(form, self.ses_identity_mode, [("domain", "Verify the domain (Easy DKIM — deliverable)"), ("address", "Verify only this address")]),
                   "Domain verification publishes three DKIM CNAME records (shown on the summary page after the run); address verification sends a confirmation mail to the address instead.")
        self.ses_verify_identity = tk.BooleanVar(value=True)
        row = _check_row(form, row, "Request identity verification through the SES API", self.ses_verify_identity)
        self.ses_configuration_set = ttk.Entry(form)
        row = _row(form, row, "Configuration set", self.ses_configuration_set, "(none) — only set this once the set exists in that account and region, or every send fails.")
        ttk.Label(self.ses_section, text=SANDBOX_NOTE, foreground=WARN_COLOUR, wraplength=760, justify="left").pack(anchor="w", fill="x", pady=(6, 0))

    def _build_smtp(self, body: tk.Misc) -> None:
        self.smtp_section = Section(body, "SMTP")
        self.smtp_section.pack(fill="x", pady=(10, 0))
        form = _form(self.smtp_section)
        form.pack(fill="x")
        row = 0
        host_line = ttk.Frame(form)
        self.smtp_host = ttk.Entry(host_line)
        self.smtp_host.pack(side="left", fill="x", expand=True)
        ttk.Label(host_line, text="Port", foreground=MUTED_COLOUR).pack(side="left", padx=(10, 6))
        self.smtp_port = PortEntry(host_line)
        self.smtp_port.pack(side="left")
        row = _row(form, row, "Host", host_line, "STARTTLS on 587 is what the backend's mailer expects; 465 (implicit TLS) is not supported.")
        self.smtp_username = ttk.Entry(form)
        row = _row(form, row, "Username", self.smtp_username)
        self.smtp_password = SecretEntry(form)
        row = _row(form, row, "Password", self.smtp_password, "Written to configuration.json (0600) as smtp-password; never saved in a profile.")
        self.smtp_from_address = ttk.Entry(form)
        row = _row(form, row, "From address", self.smtp_from_address, "The backend needs all five smtp-* keys: host, port, username, password and from address.")

    # --- plan <-> widgets ------------------------------------------------------------------------

    def load(self, plan: InstallPlan) -> None:
        """Copy :attr:`InstallPlan.email` into the widgets."""
        email = plan.email
        self.mode.set(email.mode)
        _assign(self.ses_region, email.ses_region)
        _assign(self.ses_from_address, email.ses_from_address)
        self.ses_identity_mode.set(email.ses_identity_mode)
        self.ses_verify_identity.set(email.ses_verify_identity)
        _assign(self.ses_configuration_set, email.ses_configuration_set)
        _assign(self.smtp_host, email.smtp_host)
        _assign(self.smtp_port, email.smtp_port)
        _assign(self.smtp_username, email.smtp_username)
        _assign(self.smtp_password, email.smtp_password)
        _assign(self.smtp_from_address, email.smtp_from_address)
        self.ses_region_hint.configure(text=f"blank = same as the KMS region ({plan.aws.region})")
        self._sync_enabled()

    def store(self, plan: InstallPlan) -> None:
        """Copy the widgets into :attr:`InstallPlan.email`; ``ValueError`` names an unparsable field."""
        email = plan.email
        email.mode = self.mode.get()
        email.ses_region = _value(self.ses_region).strip()
        email.ses_from_address = _value(self.ses_from_address).strip()
        email.ses_identity_mode = self.ses_identity_mode.get()
        email.ses_verify_identity = bool(self.ses_verify_identity.get())
        email.ses_configuration_set = _value(self.ses_configuration_set).strip()
        email.smtp_host = _value(self.smtp_host).strip()
        if email.mode == "smtp" or _value(self.smtp_port).strip():
            email.smtp_port = _int_field(self.smtp_port, "SMTP port")
        email.smtp_username = _value(self.smtp_username).strip()
        email.smtp_password = _value(self.smtp_password)
        email.smtp_from_address = _value(self.smtp_from_address).strip()

    def refresh(self, state: Any) -> None:
        """Header via the base class; the verification label mirrors the step's last check."""
        _refresh_base(self, state)
        self.ses_region_hint.configure(text=f"blank = same as the KMS region ({state.plan.aws.region})")
        status = state.statuses.get(self.step_id)
        if state.plan.email.mode != "ses":
            self.verification_label.configure(text="", foreground=MUTED_COLOUR)
        elif status in (StepStatus.OK, StepStatus.DONE):
            self.verification_label.configure(text="verified", foreground=OK_COLOUR)
        elif status is StepStatus.NEEDS_APPLY:
            self.verification_label.configure(text="verification pending", foreground=WARN_COLOUR)
        elif status is StepStatus.FAILED:
            self.verification_label.configure(text="check failed", foreground=FAIL_COLOUR)
        else:
            self.verification_label.configure(text="not checked", foreground=MUTED_COLOUR)

    def _sync_enabled(self) -> None:
        """Only the chosen transport's group is editable."""
        mode = self.mode.get()
        _set_enabled(self.ses_section, mode == "ses")
        _set_enabled(self.smtp_section, mode == "smtp")


def _refresh_base(page: Page, state: Any) -> None:
    """Run the base class's header refresh when it provides one."""
    base_refresh = getattr(Page, "refresh", None)
    if base_refresh is None:
        return
    try:
        base_refresh(page, state)
    except NotImplementedError:
        pass

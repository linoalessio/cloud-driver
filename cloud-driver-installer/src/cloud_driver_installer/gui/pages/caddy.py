"""Reverse-proxy page: Caddy in front of the loopback REST port.

The mockup's "Reverse proxy (Caddy)" page: the enable box, the API domain with a DNS pill, the
optional ACME e-mail and a live preview of the site block the step will write. ``Check DNS`` goes
through ``actions.check_dns(domain)``; the worker's ``DnsEvent`` is rendered by
:meth:`CaddyPage.show_dns`.
"""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk
from typing import Any

from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Hint
from cloud_driver_installer.model import CLIENT_HARDCODED_API_HOST, InstallPlan

__all__ = ["CaddyPage", "render_site_block"]

OK_COLOUR = "#2FA84F"
WARN_COLOUR = "#D9840A"
FAIL_COLOUR = "#E5342A"
MUTED_COLOUR = "#6E6E73"
LABEL_COLUMN_PX = 170


def render_site_block(domain: str, rest_port: int) -> str:
    """The Caddyfile site block the step writes (the mockup's preview), for ``domain`` -> loopback ``rest_port``."""
    name = domain.strip() or "<api domain>"
    return (
        f"{name} {{\n"
        f"    reverse_proxy 127.0.0.1:{rest_port} {{\n"
        "        header_up X-Forwarded-For {remote_host}\n"
        "    }\n"
        "}"
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


def _set_text(widget: tk.Text, content: str) -> None:
    """Replace the contents of a read-only ``tk.Text``."""
    widget.configure(state="normal")
    widget.delete("1.0", "end")
    widget.insert("1.0", content)
    widget.configure(state="disabled")


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


class CaddyPage(Page):
    """TLS termination and the API site block."""

    step_id = "caddy"
    title = "Reverse proxy (Caddy)"
    #: The mockup labels this page's header check button "Check DNS".
    check_label = "Check DNS"

    def build(self, body: tk.Misc) -> None:
        """Enable box, domain + DNS pill, ACME e-mail, Caddyfile preview and the trust-proxy note."""
        Hint(body, "Adds the cloudsmith apt repo, installs Caddy, writes the API site block, validates and reloads. TLS is issued by Caddy's own ACME client.").pack(anchor="w", fill="x", pady=(0, 8))
        self.enabled = tk.BooleanVar(value=True)
        ttk.Checkbutton(body, text="Terminate TLS with Caddy and proxy to the REST port on loopback", variable=self.enabled).pack(anchor="w", pady=(0, 6))

        self.form = _form(body)
        self.form.pack(fill="x")
        row = 0
        domain_line = ttk.Frame(self.form)
        self.api_domain_var = tk.StringVar(value="")
        self.api_domain = ttk.Entry(domain_line, textvariable=self.api_domain_var)
        self.api_domain.pack(side="left", fill="x", expand=True)
        self.dns_label = ttk.Label(domain_line, text="DNS not checked", foreground=MUTED_COLOUR)
        self.dns_label.pack(side="left", padx=(8, 0))
        ttk.Button(domain_line, text="Check DNS", command=self._check_dns).pack(side="left", padx=(8, 0))
        row = _row(self.form, row, "API domain", domain_line,
                   "Must already resolve to this server before Caddy can obtain a certificate. The check compares the A/AAAA record with the server's public IP. "
                   f"The shipped desktop and iOS apps are built for https://{CLIENT_HARDCODED_API_HOST}.")
        self.acme_email = ttk.Entry(self.form)
        row = _row(self.form, row, "ACME e-mail", self.acme_email, "optional — expiry notices from Let's Encrypt")

        self.preview = tk.Text(body, height=6, font="TkFixedFont", wrap="none", relief="flat", borderwidth=1, highlightthickness=1)
        self.preview.pack(fill="x", pady=(8, 0))
        Hint(body, "trust-proxy-headers is switched on in the configuration only when this step is enabled and the REST port stays on loopback — the one topology where the header is trustworthy.").pack(anchor="w", fill="x", pady=(8, 0))

        self._rest_port = 8080
        self.api_domain_var.trace_add("write", lambda *_args: self._render_preview())
        self.enabled.trace_add("write", lambda *_args: self._sync_enabled())
        self._render_preview()
        self._sync_enabled()

    # --- plan <-> widgets ------------------------------------------------------------------------

    def load(self, plan: InstallPlan) -> None:
        """Copy :attr:`InstallPlan.proxy` (and the REST port for the preview) into the widgets."""
        self.enabled.set(plan.proxy.enabled)
        self.api_domain_var.set(plan.proxy.api_domain)
        self.acme_email.delete(0, "end")
        self.acme_email.insert(0, plan.proxy.acme_email)
        self._rest_port = plan.app.rest_port
        self._render_preview()
        self._sync_enabled()

    def store(self, plan: InstallPlan) -> None:
        """Copy the widgets into :attr:`InstallPlan.proxy`."""
        plan.proxy.enabled = bool(self.enabled.get())
        plan.proxy.api_domain = self.api_domain_var.get().strip().lower()
        plan.proxy.acme_email = self.acme_email.get().strip()

    def refresh(self, state: Any) -> None:
        """Header via the base class; the preview follows the plan's REST port."""
        _refresh_base(self, state)
        self._rest_port = state.plan.app.rest_port
        self._render_preview()

    # --- actions -----------------------------------------------------------------------------------

    def show_dns(self, domain: str, resolved_ip: str, matches: bool) -> None:
        """Render a ``DnsEvent`` next to the domain field (``A → 82.165.48.39`` in the mockup)."""
        if not resolved_ip:
            self.dns_label.configure(text=f"{domain or 'domain'}: no A/AAAA record", foreground=FAIL_COLOUR)
        elif matches:
            self.dns_label.configure(text=f"A → {resolved_ip}", foreground=OK_COLOUR)
        else:
            self.dns_label.configure(text=f"A → {resolved_ip} (not this server)", foreground=WARN_COLOUR)

    def _check_dns(self) -> None:
        """Ask the app to resolve the domain currently typed and compare it with the server's public IP."""
        domain = self.api_domain_var.get().strip()
        if not domain:
            self.dns_label.configure(text="enter a domain first", foreground=WARN_COLOUR)
            return
        self.dns_label.configure(text="resolving…", foreground=MUTED_COLOUR)
        self.actions.check_dns(domain)

    def _render_preview(self) -> None:
        _set_text(self.preview, render_site_block(self.api_domain_var.get(), self._rest_port))

    def _sync_enabled(self) -> None:
        _set_enabled(self.form, bool(self.enabled.get()))


def _refresh_base(page: Page, state: Any) -> None:
    """Run the base class's header refresh when it provides one."""
    base_refresh = getattr(Page, "refresh", None)
    if base_refresh is None:
        return
    try:
        base_refresh(page, state)
    except NotImplementedError:
        pass

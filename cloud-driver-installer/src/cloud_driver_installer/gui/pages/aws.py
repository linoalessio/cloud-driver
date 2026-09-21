"""AWS page: the operator's credentials, the KMS key, the S3 bucket and the identity the server runs as.

Reproduces the mockup's "AWS — KMS, S3, server identity" page. The page only edits
:class:`~cloud_driver_installer.model.AwsSettings`; validating the credentials goes through
``actions.validate_aws()`` (the worker runs STS on a thread) and the answer is pushed back with
:meth:`AwsPage.show_identity`. Nothing here talks to AWS directly.
"""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk
from typing import Any

from cloud_driver_installer.aws import COMMON_REGIONS, list_local_profiles
from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Hint, Section, SecretEntry
from cloud_driver_installer.model import InstallPlan

__all__ = ["AwsPage"]

#: Mockup colours for the inline status labels (the shared palette lives in ``gui/widgets.py``).
OK_COLOUR = "#2FA84F"
WARN_COLOUR = "#D9840A"
FAIL_COLOUR = "#E5342A"
MUTED_COLOUR = "#6E6E73"

#: Width of the label column, as in the mockup's ``.field`` grid.
LABEL_COLUMN_PX = 170


# --- small layout helpers (kept module-local so the page has no dependency beyond the contract) ---


def _form(parent: tk.Misc) -> ttk.Frame:
    """A grid form with the mockup's 170 px right-aligned label column and a stretching value column."""
    frame = ttk.Frame(parent)
    frame.columnconfigure(0, minsize=LABEL_COLUMN_PX)
    frame.columnconfigure(1, weight=1)
    return frame


def _row(form: ttk.Frame, row: int, label: str, widget: tk.Misc, hint: str | None = None) -> int:
    """Grid ``label`` and ``widget`` on ``row`` (plus an optional hint below); returns the next free row."""
    ttk.Label(form, text=label, anchor="e").grid(row=row, column=0, sticky="e", padx=(0, 14), pady=3)
    widget.grid(row=row, column=1, sticky="ew", pady=3)
    row += 1
    if hint:
        Hint(form, hint).grid(row=row, column=1, sticky="w", pady=(0, 4))
        row += 1
    return row


def _check_row(form: ttk.Frame, row: int, text: str, variable: tk.BooleanVar, hint: str | None = None) -> int:
    """Grid a checkbutton in the value column (the mockup's ``<label></label><label class=check>`` rows)."""
    ttk.Checkbutton(form, text=text, variable=variable).grid(row=row, column=1, sticky="w", pady=2)
    row += 1
    if hint:
        Hint(form, hint).grid(row=row, column=1, sticky="w", pady=(0, 4))
        row += 1
    return row


def _radios(parent: tk.Misc, variable: tk.StringVar, options: list[tuple[str, str]]) -> ttk.Frame:
    """A horizontal row of radio buttons (``value``, ``label`` pairs) bound to ``variable``."""
    frame = ttk.Frame(parent)
    for value, label in options:
        ttk.Radiobutton(frame, text=label, value=value, variable=variable).pack(side="left", padx=(0, 18))
    return frame


def _value(widget: Any) -> str:
    """Read a tk variable, a composite widget with ``get()`` or a plain entry as text."""
    return str(widget.get())


def _assign(widget: Any, value: Any) -> None:
    """Write ``value`` into a tk variable, a composite widget with ``set()`` or a plain entry."""
    setter = getattr(widget, "set", None)
    if callable(setter):
        setter(value)
        return
    widget.delete(0, "end")
    widget.insert(0, str(value))


def _set_enabled(widget: tk.Misc, enabled: bool) -> None:
    """Enable or disable ``widget`` and every descendant (composite widgets are frames)."""
    for target in [widget, *_descendants(widget)]:
        try:
            target.state(["!disabled" if enabled else "disabled"])  # type: ignore[attr-defined]
        except (AttributeError, tk.TclError):
            try:
                target.configure(state="normal" if enabled else "disabled")  # type: ignore[call-arg]
            except tk.TclError:
                pass


def _descendants(widget: tk.Misc) -> list[tk.Misc]:
    """Every widget below ``widget`` in the tree."""
    found: list[tk.Misc] = []
    for child in widget.winfo_children():
        found.append(child)
        found.extend(_descendants(child))
    return found


# --- the page ----------------------------------------------------------------------------------


class AwsPage(Page):
    """KMS key, S3 bucket, server identity and the credentials the installer itself uses."""

    step_id = "aws"
    title = "AWS — KMS, S3, server identity"
    #: The mockup labels this page's header check button "Validate" (an STS ``whoami``).
    check_label = "Validate"

    # --- construction ----------------------------------------------------------------------------

    def build(self, body: tk.Misc) -> None:
        """Create the three groups of the mockup: credentials, KMS + S3 side by side, server identity."""
        Hint(body, "Runs on this machine with your admin credentials. The server only ever receives the least-privilege key created here.").pack(anchor="w", fill="x", pady=(0, 8))

        self._build_credentials(body)
        two = ttk.Frame(body)
        two.pack(fill="x", pady=(10, 0))
        two.columnconfigure(0, weight=1, uniform="two")
        two.columnconfigure(1, weight=1, uniform="two")
        self._build_kms(two)
        self._build_s3(two)
        self._build_identity(body)

        self.resolved_label = ttk.Label(body, text="", foreground=MUTED_COLOUR, wraplength=760, justify="left")
        self.resolved_label.pack(anchor="w", fill="x", pady=(10, 0))

        for variable in (self.credential_source, self.kms_mode, self.s3_enabled, self.s3_mode, self.server_identity):
            variable.trace_add("write", lambda *_args: self._sync_enabled())
        self._sync_enabled()

    def _build_credentials(self, body: tk.Misc) -> None:
        section = Section(body, "Your credentials (used only by the installer)")
        section.pack(fill="x")
        self.credential_source = tk.StringVar(value="profile")
        _radios(section, self.credential_source, [("profile", "Local profile"), ("keys", "Enter an access key")]).pack(anchor="w", pady=(0, 4))

        form = _form(section)
        form.pack(fill="x")
        row = 0
        profile_line = ttk.Frame(form)
        self.profile = ttk.Combobox(profile_line, values=list_local_profiles())
        self.profile.pack(side="left", fill="x", expand=True)
        self.identity_label = ttk.Label(profile_line, text="not validated", foreground=MUTED_COLOUR)
        self.identity_label.pack(side="left", padx=(8, 0))
        row = _row(form, row, "Profile", profile_line, "Profiles from ~/.aws/credentials and ~/.aws/config; the installer never copies these to the server unless you choose so below.")

        self.access_key_id = ttk.Entry(form)
        row = _row(form, row, "Access key id", self.access_key_id)
        self.secret_access_key = SecretEntry(form)
        row = _row(form, row, "Secret access key", self.secret_access_key)
        self.session_token = SecretEntry(form)
        row = _row(form, row, "Session token", self.session_token, "optional — only for temporary (STS) credentials")

        region_line = ttk.Frame(form)
        self.region = ttk.Combobox(region_line, values=list(COMMON_REGIONS))
        self.region.pack(side="left", fill="x", expand=True)
        ttk.Button(region_line, text="Validate credentials", command=self._validate).pack(side="left", padx=(8, 0))
        row = _row(form, row, "Region", region_line, "KMS, S3 and (unless overridden on the E-mail page) SES all live in this region. Free text is accepted.")

    def _build_kms(self, two: ttk.Frame) -> None:
        section = Section(two, "KMS key (required — the process will not boot without it)")
        section.grid(row=0, column=0, sticky="nsew", padx=(0, 6))
        self.kms_mode = tk.StringVar(value="create")
        _radios(section, self.kms_mode, [("create", "Create a symmetric key + alias"), ("existing", "Use an existing key")]).pack(anchor="w", pady=(0, 4))
        form = _form(section)
        form.pack(fill="x")
        row = 0
        self.kms_alias = ttk.Entry(form)
        row = _row(form, row, "Alias", self.kms_alias, "Idempotent: an alias that already exists is reused, never re-created. The alias name is what goes into aws-kms-key-id.")
        self.kms_key_id = ttk.Entry(form)
        row = _row(form, row, "Existing key", self.kms_key_id, "Key id, ARN or alias of a symmetric key the runtime identity may Encrypt/Decrypt with.")
        self.kms_enable_rotation = tk.BooleanVar(value=True)
        row = _check_row(form, row, "Enable automatic yearly key rotation", self.kms_enable_rotation, "Transparent to Decrypt: every stored row keeps decrypting under the key version that wrapped it.")
        self.kms_form = form

    def _build_s3(self, two: ttk.Frame) -> None:
        section = Section(two, "S3 bucket (file content)")
        section.grid(row=0, column=1, sticky="nsew", padx=(6, 0))
        self.s3_enabled = tk.BooleanVar(value=True)
        ttk.Checkbutton(section, text="Store file content in S3 (otherwise inline in Postgres)", variable=self.s3_enabled).pack(anchor="w", pady=(0, 4))
        self.s3_mode = tk.StringVar(value="create")
        self.s3_radios = _radios(section, self.s3_mode, [("create", "Create bucket"), ("existing", "Use existing")])
        self.s3_radios.pack(anchor="w", pady=(0, 4))
        form = _form(section)
        form.pack(fill="x")
        row = 0
        self.s3_bucket = ttk.Entry(form)
        row = _row(form, row, "Bucket", self.s3_bucket, "Created private: public access blocked, SSE-S3 default encryption, bucket-owner-enforced ownership. Name validated against the S3 rules before anything runs.")
        self.s3_key_prefix = ttk.Entry(form)
        row = _row(form, row, "Key prefix", self.s3_key_prefix, "(none) — leave empty unless the bucket is shared with something else.")
        self.s3_abort_multipart_days = ttk.Spinbox(form, from_=0, to=365, width=6)
        row = _row(form, row, "Abort multipart after", self.s3_abort_multipart_days, "Days after which an abandoned multipart upload is discarded by a lifecycle rule (0 = no rule). Only applied to a bucket this installer creates.")
        self.s3_versioning = tk.BooleanVar(value=False)
        row = _check_row(form, row, "Enable bucket versioning", self.s3_versioning, "Off by default: every replaced file would keep its old ciphertext and cost storage. Only applied to a bucket this installer creates.")
        self.s3_form = form

    def _build_identity(self, body: tk.Misc) -> None:
        section = Section(body, "Identity the server runs as")
        section.pack(fill="x", pady=(10, 0))
        self.server_identity = tk.StringVar(value="iam_user")
        _radios(section, self.server_identity, [("iam_user", "Create a least-privilege IAM user"), ("reuse", "Put the credentials above on the server")]).pack(anchor="w", pady=(0, 4))
        form = _form(section)
        form.pack(fill="x")
        row = 0
        self.iam_user_name = ttk.Entry(form)
        row = _row(form, row, "IAM user", self.iam_user_name, "Inline policy with exactly kms:Encrypt/Decrypt/DescribeKey on this key, the five S3 actions on this bucket, and ses:SendEmail if SES is chosen. Its access key is written to /root/.aws/credentials on the server, 0600.")
        self.rotate_server_key = tk.BooleanVar(value=False)
        row = _check_row(form, row, "Rotate the server's access key (an existing key's secret cannot be read back)", self.rotate_server_key)
        self.reuse_warning = ttk.Label(form, text="Your personal credential (with every permission it has) is copied to the server. Prefer the IAM user unless this is a throwaway account.", foreground=WARN_COLOUR, wraplength=700, justify="left")
        self.reuse_warning.grid(row=row, column=1, sticky="w", pady=(2, 0))
        self.identity_form = form

    # --- plan <-> widgets ------------------------------------------------------------------------

    def load(self, plan: InstallPlan) -> None:
        """Copy :attr:`InstallPlan.aws` into the widgets."""
        aws = plan.aws
        self.credential_source.set(aws.credential_source)
        _assign(self.profile, aws.profile_name)
        _assign(self.access_key_id, aws.access_key_id)
        _assign(self.secret_access_key, aws.secret_access_key)
        _assign(self.session_token, aws.session_token)
        _assign(self.region, aws.region)
        self.kms_mode.set(aws.kms_mode)
        _assign(self.kms_alias, aws.kms_alias)
        _assign(self.kms_key_id, aws.kms_key_id)
        self.kms_enable_rotation.set(aws.kms_enable_rotation)
        self.s3_enabled.set(aws.s3_enabled)
        self.s3_mode.set(aws.s3_mode)
        _assign(self.s3_bucket, aws.s3_bucket)
        _assign(self.s3_key_prefix, aws.s3_key_prefix)
        _assign(self.s3_abort_multipart_days, aws.s3_abort_multipart_days)
        self.s3_versioning.set(aws.s3_versioning)
        self.server_identity.set(aws.server_identity)
        _assign(self.iam_user_name, aws.iam_user_name)
        self.rotate_server_key.set(aws.rotate_server_key)
        self._sync_enabled()

    def store(self, plan: InstallPlan) -> None:
        """Copy the widgets into :attr:`InstallPlan.aws`; raises ``ValueError`` for an unparsable value."""
        aws = plan.aws
        aws.credential_source = self.credential_source.get()
        aws.profile_name = _value(self.profile).strip()
        aws.access_key_id = _value(self.access_key_id).strip()
        aws.secret_access_key = _value(self.secret_access_key).strip()
        aws.session_token = _value(self.session_token).strip()
        aws.region = _value(self.region).strip()
        aws.kms_mode = self.kms_mode.get()
        aws.kms_alias = _value(self.kms_alias).strip()
        aws.kms_key_id = _value(self.kms_key_id).strip()
        aws.kms_enable_rotation = bool(self.kms_enable_rotation.get())
        aws.s3_enabled = bool(self.s3_enabled.get())
        aws.s3_mode = self.s3_mode.get()
        aws.s3_bucket = _value(self.s3_bucket).strip()
        aws.s3_key_prefix = _value(self.s3_key_prefix).strip()
        days_text = _value(self.s3_abort_multipart_days).strip()
        try:
            aws.s3_abort_multipart_days = int(days_text or "0")
        except ValueError as exc:
            raise ValueError(f"S3 multipart abort days: must be a whole number of days, not {days_text!r}") from exc
        aws.s3_versioning = bool(self.s3_versioning.get())
        aws.server_identity = self.server_identity.get()
        aws.iam_user_name = _value(self.iam_user_name).strip()
        aws.rotate_server_key = bool(self.rotate_server_key.get())

    def refresh(self, state: Any) -> None:
        """Header status via the base class, plus what the last check/apply resolved on the AWS side."""
        _refresh_base(self, state)
        secrets = state.secrets
        resolved: list[str] = []
        if secrets.kms_key_arn or secrets.kms_key_id:
            resolved.append(f"KMS key: {secrets.kms_key_arn or secrets.kms_key_id}")
        if secrets.s3_bucket:
            resolved.append(f"bucket: {secrets.s3_bucket}")
        if secrets.server_access_key_id:
            kept = " (kept — already on the server)" if secrets.server_key_kept else ""
            resolved.append(f"server access key: {secrets.server_access_key_id}{kept}")
        self.resolved_label.configure(text=("Resolved: " + " · ".join(resolved)) if resolved else "Nothing resolved yet — Validate the credentials or run Check all.")

    # --- actions -----------------------------------------------------------------------------------

    def show_identity(self, arn: str | None, error: str | None = None) -> None:
        """Render the STS answer next to the profile field (called by the app for ``AwsIdentityEvent``)."""
        if error:
            self.identity_label.configure(text=error, foreground=FAIL_COLOUR)
        elif arn:
            self.identity_label.configure(text=_shorten_arn(arn), foreground=OK_COLOUR)
        else:
            self.identity_label.configure(text="not validated", foreground=MUTED_COLOUR)

    def _validate(self) -> None:
        """Ask the app to run STS ``GetCallerIdentity`` with the credentials currently entered."""
        self.identity_label.configure(text="validating…", foreground=MUTED_COLOUR)
        self.actions.validate_aws()

    def _sync_enabled(self) -> None:
        """Grey out the inputs the current radio choices make irrelevant."""
        by_profile = self.credential_source.get() == "profile"
        _set_enabled(self.profile, by_profile)
        for widget in (self.access_key_id, self.secret_access_key, self.session_token):
            _set_enabled(widget, not by_profile)
        create_key = self.kms_mode.get() == "create"
        _set_enabled(self.kms_alias, create_key)
        _set_enabled(self.kms_key_id, not create_key)
        s3_on = bool(self.s3_enabled.get())
        _set_enabled(self.s3_radios, s3_on)
        _set_enabled(self.s3_form, s3_on)
        if s3_on:
            create_bucket = self.s3_mode.get() == "create"
            _set_enabled(self.s3_abort_multipart_days, create_bucket)
        iam = self.server_identity.get() == "iam_user"
        _set_enabled(self.iam_user_name, iam)
        if iam:
            self.reuse_warning.grid_remove()
        else:
            self.reuse_warning.grid()


def _shorten_arn(arn: str) -> str:
    """``arn:aws:iam::123456789012:user/lino`` -> ``arn:aws:iam::1234…:user/lino`` like the mockup pill."""
    parts = arn.split(":")
    if len(parts) >= 6 and len(parts[4]) > 4:
        parts[4] = parts[4][:4] + "…"
    return ":".join(parts)


def _refresh_base(page: Page, state: Any) -> None:
    """Run the base class's header refresh when it provides one (it may be declared abstract)."""
    base_refresh = getattr(Page, "refresh", None)
    if base_refresh is None:
        return
    try:
        base_refresh(page, state)
    except NotImplementedError:
        pass

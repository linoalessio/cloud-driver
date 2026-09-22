"""One page per step, plus the summary.

Every page is a thin editor over the :class:`~cloud_driver_installer.model.InstallPlan`: ``load``
copies the plan into the widgets, ``store`` copies them back (raising :class:`ValueError` with a
message the window shows), and ``refresh`` re-renders whatever the last check discovered. No page
talks to SSH or AWS - it asks the window through ``actions``.
"""

from __future__ import annotations

import tkinter as tk
from dataclasses import dataclass
from tkinter import ttk
from typing import Callable

from cloud_driver_installer.aws import COMMON_REGIONS, list_local_profiles
from cloud_driver_installer.config_files import SCREEN_LOG_FILE, masked, render_configuration, to_json
from cloud_driver_installer.engine import StepStatus
from cloud_driver_installer.gui.state import AppState
from cloud_driver_installer.gui.widgets import COLORS, FactGrid, Form, ScrollFrame, note, section
from cloud_driver_installer.model import CLIENT_HARDCODED_API_HOST, InstallPlan
from cloud_driver_installer.secrets import generate_base64, generate_hex
from cloud_driver_installer.sizing import GIB, format_bytes, suggest_jvm_xmx
from cloud_driver_installer.steps import STEP_ORDER


@dataclass
class PageActions:
    """What a page may ask the window to do."""

    check: Callable[[str], None]
    apply: Callable[[str], None]
    probe_aws: Callable[[], None]
    probe_dns: Callable[[str], None]
    install: Callable[[], None]
    stop: Callable[[], None]


def _int(variable: tk.Variable, field: str, minimum: int = 1, maximum: int = 65535) -> int:
    try:
        value = int(str(variable.get()).strip())
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{field}: '{variable.get()}' is not a number") from exc
    if not minimum <= value <= maximum:
        raise ValueError(f"{field}: must be between {minimum} and {maximum}")
    return value


def _bytes(variable: tk.Variable, field: str) -> int:
    try:
        value = float(str(variable.get()).strip())
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{field}: '{variable.get()}' is not a number of GiB") from exc
    if value <= 0:
        raise ValueError(f"{field}: must be greater than zero")
    return int(value * GIB)


class Page(ttk.Frame):
    """Base class: header (title, status, detail, buttons) plus a scrollable body."""

    step_id = ""
    title = ""

    def __init__(self, parent: tk.Misc, state: AppState, actions: PageActions) -> None:
        super().__init__(parent)
        self.state = state
        self.actions = actions

        header = ttk.Frame(self, padding=(14, 12, 14, 6))
        header.pack(fill="x")
        ttk.Label(header, text=self.title, style="Head.TLabel").pack(side="left")
        self.status_label = ttk.Label(header, text="not checked", style="Muted.TLabel")
        self.status_label.pack(side="left", padx=12)
        if self.step_id:
            ttk.Button(header, text="Apply this step", command=lambda: actions.apply(self.step_id)).pack(side="right")
            ttk.Button(header, text="Check", width=8, command=lambda: actions.check(self.step_id)).pack(side="right", padx=4)
        self.detail_label = ttk.Label(self, text="", style="Hint.TLabel", wraplength=720, justify="left")
        self.detail_label.pack(anchor="w", padx=14)
        self.error_label = ttk.Label(self, text="", foreground=COLORS["fail"], wraplength=720, justify="left")
        self.error_label.pack(anchor="w", padx=14)

        scroller = ScrollFrame(self)
        scroller.pack(fill="both", expand=True, padx=8, pady=6)
        self.body = scroller.body
        self.build(self.body)

    # --- to implement ----------------------------------------------------------------------------

    def build(self, body: ttk.Frame) -> None:
        """Create the widgets."""

    def load(self, plan: InstallPlan) -> None:
        """Copy the plan into the widgets."""

    def store(self, plan: InstallPlan) -> None:
        """Copy the widgets into the plan, raising ``ValueError`` on a bad value."""

    def refresh(self, state: AppState) -> None:
        """Re-render anything derived from the discovered state."""

    # --- shared ----------------------------------------------------------------------------------

    def refresh_header(self, state: AppState) -> None:
        """Update the status pill and the detail line from the last check/run."""
        if not self.step_id:
            return
        status = state.statuses.get(self.step_id, StepStatus.PENDING)
        style = {
            StepStatus.OK: "Ok.TLabel",
            StepStatus.DONE: "Ok.TLabel",
            StepStatus.NEEDS_APPLY: "Warn.TLabel",
            StepStatus.FAILED: "Fail.TLabel",
        }.get(status, "Muted.TLabel")
        self.status_label.configure(text=status.value.replace("_", " "), style=style)
        self.detail_label.configure(text=state.details.get(self.step_id, ""))

    def show_error(self, message: str) -> None:
        """Show a validation problem above the form."""
        self.error_label.configure(text=message)


# --- individual pages ------------------------------------------------------------------------


class ServerPage(Page):
    """Discovered facts and the host-level options."""

    step_id, title = "server", "Server"

    def build(self, body: ttk.Frame) -> None:
        self.facts = FactGrid(section(body, "Discovered"))
        self.facts.pack(fill="x", padx=8)
        form = Form(section(body, "Options"))
        form.pack(fill="x", padx=8)
        self.install_dir = tk.StringVar()
        self.screen_session = tk.StringVar()
        self.timezone = tk.StringVar()
        self.unattended = tk.BooleanVar()
        self.install_key = tk.BooleanVar()
        self.public_key = tk.StringVar()
        self.write_alias = tk.BooleanVar()
        self.alias_name = tk.StringVar()
        form.entry("Install directory", self.install_dir, "The JVM's working directory: cloud-driver/, extensions/, upload-scratch/ and start-cloud.sh live in it.")
        form.entry("Screen session", self.screen_session, "The operator terminal needs a real pty, so the JVM runs inside screen, not systemd.", width=16)
        form.combo("Timezone", self.timezone, ["", "Europe/Berlin", "Etc/UTC"], "Empty keeps the host's setting. Clock synchronisation is always enforced - AWS rejects a drifted clock.")
        form.check("Enable unattended security upgrades", self.unattended, "A new JDK only takes effect on the next JVM restart.")
        form.check("Install my public key for root", self.install_key, "The shell scripts (deploy-cloud.sh, install-on-server.sh) need key-based root login.")
        form.path("Public key", self.public_key)
        form.check("Write a ~/.ssh/config alias for the shell scripts", self.write_alias, "Only added when no Host with that name exists; an existing entry is never edited.")
        form.entry("Alias name", self.alias_name, width=18)

    def load(self, plan: InstallPlan) -> None:
        self.install_dir.set(plan.server.install_dir)
        self.screen_session.set(plan.server.screen_session)
        self.timezone.set(plan.server.timezone)
        self.unattended.set(plan.server.unattended_upgrades)
        self.install_key.set(plan.server.install_public_key)
        self.public_key.set(plan.server.public_key_path)
        self.write_alias.set(plan.server.write_ssh_alias)
        self.alias_name.set(plan.server.ssh_alias_name)

    def store(self, plan: InstallPlan) -> None:
        value = self.install_dir.get().strip()
        if not value.startswith("/"):
            raise ValueError("Install directory: must be an absolute path")
        plan.server.install_dir = value
        plan.server.screen_session = self.screen_session.get().strip() or "cloud"
        plan.server.timezone = self.timezone.get().strip()
        plan.server.unattended_upgrades = self.unattended.get()
        plan.server.install_public_key = self.install_key.get()
        plan.server.public_key_path = self.public_key.get().strip()
        plan.server.write_ssh_alias = self.write_alias.get()
        plan.server.ssh_alias_name = self.alias_name.get().strip() or "cloud_driver"

    def refresh(self, state: AppState) -> None:
        found = state.discovered
        self.facts.set_facts(
            [
                ("OS", found.os_pretty),
                ("Kernel", found.kernel),
                ("CPU", f"{found.cpu_count} vCPU" if found.cpu_count else ""),
                ("RAM", f"{found.ram_mib / 1024:.1f} GiB" if found.ram_mib else ""),
                ("Swap", f"{found.swap_mib} MiB" if found.swap_mib else "none"),
                ("Free disk", f"{found.disk_free_gib} GiB" if found.disk_free_gib else ""),
                ("Public IP", found.public_ip),
                ("Time sync", {True: "synchronised", False: "NOT synchronised", None: ""}[found.ntp_synchronized]),
                (f"Screen '{state.plan.server.screen_session}'", "running" if found.screen_running else "not running"),
            ]
        )
        # After a password login the shell scripts would have no key to use, so offer it by default.
        if state.plan.ssh.auth == "password" and not state.plan.server.install_public_key:
            self.install_key.set(True)


class PackagesPage(Page):
    """Read-only: which base packages are present."""

    step_id, title = "packages", "Base packages"

    def build(self, body: ttk.Frame) -> None:
        note(
            body,
            "screen (the JVM's pty), curl/gnupg/ca-certificates (Caddy's apt repository and the health probes), "
            "openssl, unzip, cron and logrotate (the managed jobs and the console log) and fonts-dejavu-core "
            "(PDF thumbnails of documents without embedded fonts). awscli is added when off-site backups are on.",
        )
        self.listing = ttk.Label(body, text="", style="Mono.TLabel", justify="left")
        self.listing.pack(anchor="w", padx=10, pady=6)

    def refresh(self, state: AppState) -> None:
        from cloud_driver_installer.steps.system import BASE_PACKAGES

        packages = list(BASE_PACKAGES) + (["awscli"] if state.plan.app.backup_offsite else [])
        self.listing.configure(text="\n".join(packages))


class _InfoPage(Page):
    """A page with no inputs - the step explains itself in its detail line."""

    text = ""

    def build(self, body: ttk.Frame) -> None:
        note(body, self.text)


class JavaPage(_InfoPage):
    """JDK 21."""

    step_id, title = "java", "Java 21"
    text = (
        "Installs openjdk-21-jdk-headless. Only JDK 21 is supported: the backend is compiled against it, "
        "and an older runtime refuses the class files outright."
    )


class PythonPage(_InfoPage):
    """Python 3 with a working venv."""

    step_id, title = "python", "Python 3"
    text = (
        "Installs python3, python3-venv and python3-pip. The check actually creates a virtual environment in a "
        "temporary directory and looks for bin/pip, because on Debian the venv module ships without ensurepip. "
        "Only the semantic-search service needs it, but it is cheap to satisfy everywhere."
    )


class PostgresPage(Page):
    """The system of record."""

    step_id, title = "postgres", "PostgreSQL"

    def build(self, body: ttk.Frame) -> None:
        form = Form(section(body, "Connection"))
        form.pack(fill="x", padx=8)
        self.mode = tk.StringVar()
        self.host = tk.StringVar()
        self.port = tk.StringVar()
        self.database = tk.StringVar()
        self.username = tk.StringVar()
        self.password = tk.StringVar()
        self.rotate = tk.BooleanVar()
        form.radios("Mode", self.mode, [("install", "Install on this server"), ("external", "Use an external server")])
        form.entry("Host", self.host, width=24)
        form.entry("Port", self.port, width=8)
        form.entry("Database", self.database, width=24)
        form.entry("Username", self.username, "The role becomes the database owner, so no missing grant can surface later as a silent failure.", width=24)
        form.secret("Password", self.password, "Left empty, the value already on the server is kept; otherwise a 48-character hex secret is generated.", generate=lambda: generate_hex(24))
        form.check("Rotate the existing password (rewrites the role and the file together)", self.rotate)

    def load(self, plan: InstallPlan) -> None:
        self.mode.set(plan.postgres.mode)
        self.host.set(plan.postgres.host)
        self.port.set(str(plan.postgres.port))
        self.database.set(plan.postgres.database)
        self.username.set(plan.postgres.username)
        self.password.set(plan.postgres.password)
        self.rotate.set(plan.postgres.rotate)

    def store(self, plan: InstallPlan) -> None:
        plan.postgres.mode = self.mode.get()
        plan.postgres.host = self.host.get().strip()
        plan.postgres.port = _int(self.port, "PostgreSQL port")
        plan.postgres.database = self.database.get().strip()
        plan.postgres.username = self.username.get().strip()
        plan.postgres.password = self.password.get()
        plan.postgres.rotate = self.rotate.get()

    def refresh(self, state: AppState) -> None:
        if state.secrets.pg_password and not self.password.get():
            self.password.set(state.secrets.pg_password)


class RedisPage(Page):
    """Optional coordination store - never a source of truth."""

    step_id, title = "redis", "Redis"

    def build(self, body: ttk.Frame) -> None:
        form = Form(section(body, "Connection"))
        form.pack(fill="x", padx=8)
        self.enabled = tk.BooleanVar()
        self.mode = tk.StringVar()
        self.host = tk.StringVar()
        self.port = tk.StringVar()
        self.database = tk.StringVar()
        self.username = tk.StringVar()
        self.password = tk.StringVar()
        self.rotate = tk.BooleanVar()
        form.check("Enable Redis (rate-limit counters, webhook history, scheduler locks)", self.enabled)
        form.radios("Mode", self.mode, [("install", "Install on this server"), ("external", "Use an external server")], command=self._toggle_username)
        form.entry("Host", self.host, width=24)
        form.entry("Port", self.port, width=8)
        form.entry("Database", self.database, "A numeric index - the backend parses it as an integer.", width=8)
        self.username_row = form.entry("Username (external only)", self.username, "A local install uses requirepass only; a username would make AUTH fail and switch Redis off silently.", width=24)
        form.secret("Password", self.password, generate=lambda: generate_hex(24))
        form.check("Rotate the existing password", self.rotate)
        note(body, "Redis must never hold file content or names: everything in it is a counter, a lock or an identifier.")

    def _toggle_username(self) -> None:
        self.username_row.configure(state="normal" if self.mode.get() == "external" else "disabled")

    def load(self, plan: InstallPlan) -> None:
        self.enabled.set(plan.redis.enabled)
        self.mode.set(plan.redis.mode)
        self.host.set(plan.redis.host)
        self.port.set(str(plan.redis.port))
        self.database.set(str(plan.redis.database))
        self.username.set(plan.redis.username)
        self.password.set(plan.redis.password)
        self.rotate.set(plan.redis.rotate)
        self._toggle_username()

    def store(self, plan: InstallPlan) -> None:
        plan.redis.enabled = self.enabled.get()
        plan.redis.mode = self.mode.get()
        plan.redis.host = self.host.get().strip()
        plan.redis.port = _int(self.port, "Redis port")
        plan.redis.database = self.database.get().strip() or "0"
        plan.redis.username = self.username.get().strip() if plan.redis.mode == "external" else ""
        plan.redis.password = self.password.get()
        plan.redis.rotate = self.rotate.get()

    def refresh(self, state: AppState) -> None:
        if state.secrets.redis_password and not self.password.get():
            self.password.set(state.secrets.redis_password)


class ClamavPage(Page):
    """Malware scanning through a loopback clamd."""

    step_id, title = "clamav", "ClamAV"

    def build(self, body: ttk.Frame) -> None:
        form = Form(section(body, "Listener"))
        form.pack(fill="x", padx=8)
        self.enabled = tk.BooleanVar()
        self.port = tk.StringVar()
        self.timeout = tk.StringVar()
        form.check("Enable malware scanning (deploys the scan extension)", self.enabled)
        form.readonly("Host", "127.0.0.1", "clamd has no authentication of its own, so it is never bound beyond loopback. IPv4 only: a second (IPv6) listener crash-loops clamd.")
        form.entry("Port", self.port, width=8)
        form.entry("Timeout (s)", self.timeout, width=8)

        limits = Form(section(body, "Size limits"))
        limits.pack(fill="x", padx=8)
        self.stream_max = tk.StringVar()
        self.max_file = tk.StringVar()
        self.max_scan = tk.StringVar()
        self.scan_max_bytes = tk.StringVar()
        limits.entry("StreamMaxLength", self.stream_max, width=10)
        limits.entry("MaxFileSize", self.max_file, width=10)
        limits.entry("MaxScanSize", self.max_scan, width=10)
        limits.entry("content-scan-max-bytes", self.scan_max_bytes, "Kept below clamd's own limits so nothing in the gap is silently marked clean without a scan.", width=14)
        note(body, "freshclam's first signature download runs in the background and takes minutes; scans fail open until it finishes.")

    def load(self, plan: InstallPlan) -> None:
        self.enabled.set(plan.clamav.enabled)
        self.port.set(str(plan.clamav.port))
        self.timeout.set(str(plan.clamav.timeout_seconds))
        self.stream_max.set(plan.clamav.stream_max_length)
        self.max_file.set(plan.clamav.max_file_size)
        self.max_scan.set(plan.clamav.max_scan_size)
        self.scan_max_bytes.set(str(plan.clamav.content_scan_max_bytes))

    def store(self, plan: InstallPlan) -> None:
        plan.clamav.enabled = self.enabled.get()
        plan.clamav.host = "127.0.0.1"
        plan.clamav.port = _int(self.port, "ClamAV port")
        plan.clamav.timeout_seconds = _int(self.timeout, "ClamAV timeout", 1, 3600)
        plan.clamav.stream_max_length = self.stream_max.get().strip()
        plan.clamav.max_file_size = self.max_file.get().strip()
        plan.clamav.max_scan_size = self.max_scan.get().strip()
        plan.clamav.content_scan_max_bytes = _int(self.scan_max_bytes, "content-scan-max-bytes", 1, 1 << 40)


class FirewallPage(Page):
    """ufw - the application manages no firewall of its own."""

    step_id, title = "firewall", "Firewall (ufw)"

    def build(self, body: ttk.Frame) -> None:
        form = Form(section(body, "Rules"))
        form.pack(fill="x", padx=8)
        self.enabled = tk.BooleanVar()
        self.extra = tk.StringVar()
        form.check("Install and enable ufw", self.enabled)
        form.readonly("Always allowed", "the SSH port(s) sshd listens on, 80/tcp, 443/tcp")
        form.entry("Additional ports", self.extra, "Comma separated, e.g. 9404/tcp. SSH is allowed first, so enabling can never lock you out.", width=24)
        note(body, "Default policy: deny incoming, allow outgoing. Everything else on this box (Postgres, Redis, clamd, metrics, the API) stays on loopback.")

    def load(self, plan: InstallPlan) -> None:
        self.enabled.set(plan.server.firewall)
        self.extra.set(plan.server.firewall_extra_ports)

    def store(self, plan: InstallPlan) -> None:
        plan.server.firewall = self.enabled.get()
        plan.server.firewall_extra_ports = self.extra.get().strip()


class SwapPage(Page):
    """The kernel-OOM safety net."""

    step_id, title = "swap", "Swap"

    def build(self, body: ttk.Frame) -> None:
        form = Form(section(body, "Swapfile"))
        form.pack(fill="x", padx=8)
        self.size = tk.StringVar()
        form.entry("Size (MB)", self.size, "/swapfile plus an /etc/fstab entry. An already active swap is kept as it is - never switched off under a running JVM.", width=10)

    def load(self, plan: InstallPlan) -> None:
        self.size.set(str(plan.server.swap_mb))

    def store(self, plan: InstallPlan) -> None:
        plan.server.swap_mb = _int(self.size, "Swap size", 0, 65536)


class AwsPage(Page):
    """KMS, S3, the runtime identity - all provisioned from this machine."""

    step_id, title = "aws", "AWS"

    def build(self, body: ttk.Frame) -> None:
        credentials = Form(section(body, "Your credentials (used only by the installer)"))
        credentials.pack(fill="x", padx=8)
        self.source = tk.StringVar()
        self.profile = tk.StringVar()
        self.access_key = tk.StringVar()
        self.secret_key = tk.StringVar()
        self.region = tk.StringVar()
        credentials.radios("Credentials", self.source, [("profile", "Local profile"), ("keys", "Enter an access key")])
        credentials.combo("Profile", self.profile, list_local_profiles())
        credentials.entry("Access key id", self.access_key, width=26)
        credentials.secret("Secret access key", self.secret_key)
        credentials.combo("Region", self.region, list(COMMON_REGIONS))
        holder = ttk.Frame(credentials)
        ttk.Button(holder, text="Validate", command=self.actions.probe_aws).pack(side="left")
        self.identity = ttk.Label(holder, text="", style="Hint.TLabel")
        self.identity.pack(side="left", padx=8)
        credentials.row("", holder)

        kms = Form(section(body, "KMS key (required - the backend does not boot without it)"))
        kms.pack(fill="x", padx=8)
        self.kms_mode = tk.StringVar()
        self.kms_alias = tk.StringVar()
        self.kms_key_id = tk.StringVar()
        self.kms_rotation = tk.BooleanVar()
        self.allow_kms_change = tk.BooleanVar()
        kms.radios("Key", self.kms_mode, [("create", "Create a symmetric key + alias"), ("existing", "Use an existing key")])
        kms.entry("Alias", self.kms_alias, "An alias that already exists is adopted, never re-created.", width=34)
        kms.entry("Existing key", self.kms_key_id, "Key id, ARN or alias.", width=34)
        kms.check("Enable automatic yearly key rotation", self.kms_rotation)
        kms.check("I understand replacing the key makes existing rows unreadable", self.allow_kms_change)

        storage = Form(section(body, "S3"))
        storage.pack(fill="x", padx=8)
        self.s3_enabled = tk.BooleanVar()
        self.s3_mode = tk.StringVar()
        self.s3_bucket = tk.StringVar()
        self.s3_prefix = tk.StringVar()
        self.backup_bucket = tk.StringVar()
        self.backup_days = tk.StringVar()
        self.allow_disable_s3 = tk.BooleanVar()
        storage.check("Store file content in S3 (otherwise inline in Postgres rows)", self.s3_enabled)
        storage.radios("Bucket", self.s3_mode, [("create", "Create"), ("existing", "Use existing")])
        storage.entry("Bucket", self.s3_bucket, "Created private: public access blocked, SSE-S3, bucket-owner-enforced.", width=34)
        storage.entry("Key prefix", self.s3_prefix, width=24)
        storage.entry("Backup bucket", self.backup_bucket, "Off-site copies never go into the content bucket: the terminal's 's3 purge' deletes unreferenced objects there.", width=34)
        storage.entry("Backup retention (days)", self.backup_days, width=8)
        storage.check("I understand disabling S3 makes content already stored there unreadable", self.allow_disable_s3)

        identity = Form(section(body, "Identity the server runs as"))
        identity.pack(fill="x", padx=8)
        self.server_identity = tk.StringVar()
        self.iam_user = tk.StringVar()
        self.rotate_key = tk.BooleanVar()
        identity.radios("Identity", self.server_identity, [("iam_user", "Create a least-privilege IAM user"), ("reuse", "Copy the credentials above")])
        identity.entry("IAM user", self.iam_user, "Its inline policy grants exactly kms:Encrypt/Decrypt/DescribeKey on this key, the six S3 actions on this bucket, and ses:SendEmail when SES is used.", width=28)
        identity.check("Rotate the server's access key", self.rotate_key, "The new key is written and proven before the old one is deleted.")

    def load(self, plan: InstallPlan) -> None:
        aws = plan.aws
        self.source.set(aws.credential_source)
        self.profile.set(aws.profile_name)
        self.access_key.set(aws.access_key_id)
        self.secret_key.set(aws.secret_access_key)
        self.region.set(aws.region)
        self.kms_mode.set(aws.kms_mode)
        self.kms_alias.set(aws.kms_alias)
        self.kms_key_id.set(aws.kms_key_id)
        self.kms_rotation.set(aws.kms_enable_rotation)
        self.allow_kms_change.set(aws.allow_kms_change)
        self.s3_enabled.set(aws.s3_enabled)
        self.s3_mode.set(aws.s3_mode)
        self.s3_bucket.set(aws.s3_bucket)
        self.s3_prefix.set(aws.s3_key_prefix)
        self.backup_bucket.set(aws.backup_bucket)
        self.backup_days.set(str(aws.backup_retention_days))
        self.allow_disable_s3.set(aws.allow_disable_s3)
        self.server_identity.set(aws.server_identity)
        self.iam_user.set(aws.iam_user_name)
        self.rotate_key.set(aws.rotate_server_key)

    def store(self, plan: InstallPlan) -> None:
        aws = plan.aws
        aws.credential_source = self.source.get()
        aws.profile_name = self.profile.get().strip()
        aws.access_key_id = self.access_key.get().strip()
        aws.secret_access_key = self.secret_key.get()
        aws.region = self.region.get().split(" ")[0].strip()
        aws.kms_mode = self.kms_mode.get()
        aws.kms_alias = self.kms_alias.get().strip()
        aws.kms_key_id = self.kms_key_id.get().strip()
        aws.kms_enable_rotation = self.kms_rotation.get()
        aws.allow_kms_change = self.allow_kms_change.get()
        aws.s3_enabled = self.s3_enabled.get()
        aws.s3_mode = self.s3_mode.get()
        aws.s3_bucket = self.s3_bucket.get().strip()
        aws.s3_key_prefix = self.s3_prefix.get().strip().strip("/")
        aws.backup_bucket = self.backup_bucket.get().strip()
        aws.backup_retention_days = _int(self.backup_days, "Backup retention", 1, 3650)
        aws.allow_disable_s3 = self.allow_disable_s3.get()
        aws.server_identity = self.server_identity.get()
        aws.iam_user_name = self.iam_user.get().strip()
        aws.rotate_server_key = self.rotate_key.get()

    def show_identity(self, ok: bool, text: str) -> None:
        """Render the answer of the Validate button."""
        self.identity.configure(text=text, style="Ok.TLabel" if ok else "Fail.TLabel")


class EmailPage(Page):
    """Where verification codes go."""

    step_id, title = "email", "E-mail"

    def build(self, body: ttk.Frame) -> None:
        form = Form(section(body, "Transport"))
        form.pack(fill="x", padx=8)
        self.mode = tk.StringVar()
        form.radios("Mode", self.mode, [("none", "None (log only)"), ("ses", "AWS SES"), ("smtp", "SMTP")])

        ses = Form(section(body, "AWS SES"))
        ses.pack(fill="x", padx=8)
        self.ses_region = tk.StringVar()
        self.ses_from = tk.StringVar()
        self.ses_identity_mode = tk.StringVar()
        self.ses_verify = tk.BooleanVar()
        self.ses_set = tk.StringVar()
        ses.combo("Region", self.ses_region, list(COMMON_REGIONS), "Empty uses the KMS region.")
        ses.entry("From address", self.ses_from, width=34)
        ses.radios("Verify", self.ses_identity_mode, [("domain", "The whole domain (Easy DKIM)"), ("address", "This address only")])
        ses.check("Request verification through the SES API", self.ses_verify)
        ses.entry("Configuration set", self.ses_set, "Only set this once the set exists in that account and region, or every send fails.", width=28)
        note(
            body,
            "A new SES account is in the sandbox: 200 mails a day, and every recipient must be verified too, "
            "until AWS grants production access. Publish the DKIM records the run prints, or mail lands in spam.",
        )

        smtp = Form(section(body, "SMTP"))
        smtp.pack(fill="x", padx=8)
        self.smtp_host = tk.StringVar()
        self.smtp_port = tk.StringVar()
        self.smtp_user = tk.StringVar()
        self.smtp_password = tk.StringVar()
        self.smtp_from = tk.StringVar()
        smtp.entry("Host", self.smtp_host, width=28)
        smtp.entry("Port", self.smtp_port, width=8)
        smtp.entry("Username", self.smtp_user, width=28)
        smtp.secret("Password", self.smtp_password)
        smtp.entry("From address", self.smtp_from, width=34)

    def load(self, plan: InstallPlan) -> None:
        email = plan.email
        self.mode.set(email.mode)
        self.ses_region.set(email.ses_region)
        self.ses_from.set(email.ses_from_address)
        self.ses_identity_mode.set(email.ses_identity_mode)
        self.ses_verify.set(email.ses_verify_identity)
        self.ses_set.set(email.ses_configuration_set)
        self.smtp_host.set(email.smtp_host)
        self.smtp_port.set(str(email.smtp_port))
        self.smtp_user.set(email.smtp_username)
        self.smtp_password.set(email.smtp_password)
        self.smtp_from.set(email.smtp_from_address)

    def store(self, plan: InstallPlan) -> None:
        email = plan.email
        email.mode = self.mode.get()
        email.ses_region = self.ses_region.get().split(" ")[0].strip()
        email.ses_from_address = self.ses_from.get().strip()
        email.ses_identity_mode = self.ses_identity_mode.get()
        email.ses_verify_identity = self.ses_verify.get()
        email.ses_configuration_set = self.ses_set.get().strip()
        email.smtp_host = self.smtp_host.get().strip()
        email.smtp_port = _int(self.smtp_port, "SMTP port") if email.mode == "smtp" else email.smtp_port
        email.smtp_username = self.smtp_user.get().strip()
        email.smtp_password = self.smtp_password.get()
        email.smtp_from_address = self.smtp_from.get().strip()


class CaddyPage(Page):
    """TLS termination in front of the loopback REST port."""

    step_id, title = "caddy", "Reverse proxy (Caddy)"

    def build(self, body: ttk.Frame) -> None:
        form = Form(section(body, "Site"))
        form.pack(fill="x", padx=8)
        self.enabled = tk.BooleanVar()
        self.domain = tk.StringVar()
        self.acme_email = tk.StringVar()
        form.check("Terminate TLS with Caddy and proxy to the REST port on loopback", self.enabled)
        form.entry("API domain", self.domain, "Must already resolve to this server before Caddy can obtain a certificate.", width=30)
        form.entry("ACME e-mail", self.acme_email, "Optional: expiry notices and a fallback certificate authority.", width=30)
        holder = ttk.Frame(form)
        ttk.Button(holder, text="Check DNS", command=lambda: self.actions.probe_dns(self.domain.get().strip())).pack(side="left")
        self.dns_label = ttk.Label(holder, text="", style="Hint.TLabel")
        self.dns_label.pack(side="left", padx=8)
        form.row("", holder)
        self.preview = ttk.Label(body, text="", style="Mono.TLabel", justify="left", background=COLORS["sunken"])
        self.preview.pack(anchor="w", fill="x", padx=10, pady=8)
        note(body, "trust-proxy-headers and trusted-proxy-addresses are written only with this step on and the REST port on loopback - the one topology where the forwarded header is trustworthy.")

    def load(self, plan: InstallPlan) -> None:
        self.enabled.set(plan.proxy.enabled)
        self.domain.set(plan.proxy.api_domain)
        self.acme_email.set(plan.proxy.acme_email)

    def store(self, plan: InstallPlan) -> None:
        plan.proxy.enabled = self.enabled.get()
        plan.proxy.api_domain = self.domain.get().strip().rstrip(".")
        plan.proxy.acme_email = self.acme_email.get().strip()

    def refresh(self, state: AppState) -> None:
        from cloud_driver_installer.steps.daemons import render_site_block

        if state.plan.proxy.api_domain:
            self.preview.configure(text=render_site_block(state.plan.proxy.api_domain, state.plan.app.rest_port))

    def show_dns(self, ok: bool, text: str) -> None:
        """Render the answer of the Check DNS button."""
        self.dns_label.configure(text=text, style="Ok.TLabel" if ok else "Warn.TLabel")


class ConfigPage(Page):
    """The files the JVM reads at boot."""

    step_id, title = "config", "Configuration files"

    def build(self, body: ttk.Frame) -> None:
        form = Form(section(body, "Keys and write-back"))
        form.pack(fill="x", padx=8)
        self.jwt_rotate = tk.BooleanVar()
        self.write_local = tk.BooleanVar()
        self.write_local_db = tk.BooleanVar()
        form.check("Rotate the JWT signing key", self.jwt_rotate, "Every client's access token becomes invalid until it refreshes (up to 12 hours of re-logins).")
        form.check("Write configuration.json back to the checkout's cloud-driver/ directory", self.write_local, "shell/deploy-cloud.sh ships the local copy on every run; off means the next deploy reverts the server.")
        form.check("Also write postgres-/redis-database.json locally", self.write_local_db, "Nothing deploys them - they only matter for running a backend locally against this box.")
        self.preview = tk.Text(body, height=18, font="TkFixedFont", wrap="none", background=COLORS["sunken"], borderwidth=0)
        self.preview.pack(fill="both", expand=True, padx=10, pady=8)
        note(body, f"start-cloud.env carries JVM_XMX, SCREEN_SESSION and SCREEN_LOG_FILE ({SCREEN_LOG_FILE}); the jar name is never pinned - start-cloud.sh finds the one bootstrap jar itself.")

    def load(self, plan: InstallPlan) -> None:
        self.jwt_rotate.set(plan.app.jwt_rotate)
        self.write_local.set(plan.app.write_local_config)
        self.write_local_db.set(plan.app.write_local_db_config)

    def store(self, plan: InstallPlan) -> None:
        plan.app.jwt_rotate = self.jwt_rotate.get()
        plan.app.write_local_config = self.write_local.get()
        plan.app.write_local_db_config = self.write_local_db.get()

    def refresh(self, state: AppState) -> None:
        document = render_configuration(state.plan, state.secrets, state.discovered.existing_config)
        self.preview.configure(state="normal")
        self.preview.delete("1.0", "end")
        self.preview.insert("1.0", to_json(masked(document)))
        self.preview.configure(state="disabled")


class ApplicationPage(Page):
    """Listeners, capacity, heap, jars and the scheduled jobs."""

    step_id, title = "application", "Application"

    def build(self, body: ttk.Frame) -> None:
        listeners = Form(section(body, "Listeners"))
        listeners.pack(fill="x", padx=8)
        self.rest_port = tk.StringVar()
        self.rest_bind = tk.StringVar()
        self.metrics_port = tk.StringVar()
        self.metrics_bind = tk.StringVar()
        listeners.entry("REST port", self.rest_port, width=8)
        listeners.entry("REST bind host", self.rest_bind, "Anything but loopback logs a startup warning: the JVM speaks plain HTTP.", width=16)
        listeners.entry("Metrics port", self.metrics_port, width=8)
        listeners.entry("Metrics bind host", self.metrics_bind, "The metrics endpoint has no authentication of its own.", width=16)

        capacity = Form(section(body, "Capacity and heap"))
        capacity.pack(fill="x", padx=8)
        self.server_capacity = tk.StringVar()
        self.user_quota = tk.StringVar()
        self.xmx = tk.StringVar()
        capacity.bytes_row("Server storage", self.server_capacity, "The terminal's stats and quota commands fail without it.")
        capacity.bytes_row("Per-user quota", self.user_quota, "Left unset the backend applies a strict 1 MiB, so this is always written.")
        capacity.entry("JVM heap -Xmx", self.xmx, width=8)
        self.xmx_hint = ttk.Label(capacity, text="", style="Hint.TLabel", wraplength=560, justify="left")
        capacity.row("", self.xmx_hint)

        artefacts = Form(section(body, "Artefacts from this checkout"))
        artefacts.pack(fill="x", padx=8)
        self.repo_root = tk.StringVar()
        self.build_maven = tk.BooleanVar()
        self.deploy_jars = tk.BooleanVar()
        self.start_after = tk.BooleanVar()
        self.autostart = tk.BooleanVar()
        self.persist_log = tk.BooleanVar()
        self.scratch_sweep = tk.BooleanVar()
        self.backup_offsite = tk.BooleanVar()
        artefacts.path("Repository root", self.repo_root, directory=True)
        artefacts.check("Run mvn clean install first", self.build_maven, "Needs the GitHub Packages token in ~/.m2/settings.xml.")
        artefacts.check("Upload the jars", self.deploy_jars)
        artefacts.check("Start (or restart) the JVM afterwards", self.start_after)
        artefacts.check("Re-launch after a reboot (cron @reboot)", self.autostart)
        artefacts.check(f"Keep the console log ({SCREEN_LOG_FILE}, rotated weekly)", self.persist_log)
        artefacts.check("Sweep abandoned upload scratch files hourly", self.scratch_sweep)
        artefacts.check("Copy database backups off-site daily (to the backup bucket)", self.backup_offsite)

        self.jars = ttk.Treeview(section(body, "Modules"), columns=("state", "size"), height=13)
        self.jars.heading("#0", text="Module")
        self.jars.heading("state", text="State")
        self.jars.heading("size", text="Size")
        self.jars.column("#0", width=300)
        self.jars.column("state", width=200)
        self.jars.column("size", width=90, anchor="e")
        self.jars.pack(fill="x", padx=8, pady=4)
        note(body, "Jars already on the server that are not part of this plan are kept, never deleted; only superseded versions of what is uploaded are removed.")

    def load(self, plan: InstallPlan) -> None:
        app = plan.app
        self.rest_port.set(str(app.rest_port))
        self.rest_bind.set(app.rest_bind_host)
        self.metrics_port.set(str(app.metrics_port))
        self.metrics_bind.set(app.metrics_bind_host)
        self.server_capacity.set(f"{app.server_max_bytes / GIB:g}")
        self.user_quota.set(f"{app.user_max_bytes / GIB:g}")
        self.xmx.set(app.jvm_xmx)
        self.repo_root.set(app.repo_root)
        self.build_maven.set(app.build_with_maven)
        self.deploy_jars.set(app.deploy_jars)
        self.start_after.set(app.start_after_deploy)
        self.autostart.set(app.autostart_on_reboot)
        self.persist_log.set(app.persist_log)
        self.scratch_sweep.set(app.scratch_sweep)
        self.backup_offsite.set(app.backup_offsite)

    def store(self, plan: InstallPlan) -> None:
        app = plan.app
        app.rest_port = _int(self.rest_port, "REST port")
        app.rest_bind_host = self.rest_bind.get().strip()
        app.metrics_port = _int(self.metrics_port, "Metrics port")
        app.metrics_bind_host = self.metrics_bind.get().strip()
        app.server_max_bytes = _bytes(self.server_capacity, "Server storage")
        app.user_max_bytes = _bytes(self.user_quota, "Per-user quota")
        app.jvm_xmx = self.xmx.get().strip()
        app.repo_root = self.repo_root.get().strip()
        app.build_with_maven = self.build_maven.get()
        app.deploy_jars = self.deploy_jars.get()
        app.start_after_deploy = self.start_after.get()
        app.autostart_on_reboot = self.autostart.get()
        app.persist_log = self.persist_log.get()
        app.scratch_sweep = self.scratch_sweep.get()
        app.backup_offsite = self.backup_offsite.get()

    def refresh(self, state: AppState) -> None:
        from cloud_driver_installer.steps.application import ApplicationStep

        plan = state.plan
        if state.discovered.ram_mib:
            suggestion = suggest_jvm_xmx(
                state.discovered.ram_mib,
                postgres_local=plan.postgres.mode == "install",
                clamav_enabled=plan.clamav.enabled,
                intelligence_enabled=plan.intelligence.enabled,
            )
            budget = ["1 GiB JVM off-heap"]
            if plan.postgres.mode == "install":
                budget.append("1 GiB Postgres")
            if plan.clamav.enabled:
                budget.append("1 GiB clamd")
            if plan.intelligence.enabled:
                budget.append("2 GiB semantic search")
            self.xmx_hint.configure(
                text=f"{state.discovered.ram_mib / 1024:.1f} GiB RAM minus " + ", ".join(budget) + f" → suggested {suggestion}"
            )
            if not self.xmx.get():
                self.xmx.set(suggestion)

        for item in self.jars.get_children():
            self.jars.delete(item)
        bootstrap = ApplicationStep.bootstrap_jar(plan)
        self.jars.insert(
            "",
            "end",
            text=bootstrap.name if bootstrap else "cloud-driver-bootstrap",
            values=("built" if bootstrap else "NOT BUILT", format_bytes(bootstrap.stat().st_size) if bootstrap else "—"),
        )
        selected = ApplicationStep.selected_modules(plan)
        for module, jar in ApplicationStep.module_jars(plan).items():
            if jar is None:
                state_text = "NOT BUILT" if module in selected else "not built, not selected"
            else:
                state_text = "selected" if module in selected else "not part of this plan"
            self.jars.insert(
                "",
                "end",
                text=jar.name if jar else f"cloud-driver-extensions-{module}",
                values=(state_text, format_bytes(jar.stat().st_size) if jar else "—"),
            )


class IntelligencePage(Page):
    """The optional semantic-search service."""

    step_id, title = "intelligence", "Intelligence service"

    def build(self, body: ttk.Frame) -> None:
        form = Form(section(body, "Service"))
        form.pack(fill="x", padx=8)
        self.enabled = tk.BooleanVar()
        self.source_dir = tk.StringVar()
        self.clone_dir = tk.StringVar()
        self.encryption = tk.BooleanVar()
        self.cpu_only = tk.BooleanVar()
        self.ocr = tk.BooleanVar()
        self.ocr_languages = tk.StringVar()
        self.clip = tk.BooleanVar()
        self.port = tk.StringVar()
        self.timeout = tk.StringVar()
        self.max_bytes = tk.StringVar()
        form.check("Install the semantic-search service", self.enabled)
        form.path("Source directory", self.source_dir, directory=True)
        form.path("database-driver-v2 clone", self.clone_dir, "Vendored onto the server for the encrypted vector store.", directory=True)
        form.check("Encrypt the vector store at rest", self.encryption, "The key is generated on the server and never rotated by a re-run.")
        form.check("Install the CPU-only PyTorch build", self.cpu_only, "Several gigabytes smaller - the box has no GPU.")
        form.check("OCR for images and scanned PDFs", self.ocr, "Installs tesseract and poppler on the server.")
        form.entry("OCR languages", self.ocr_languages, width=12)
        form.check("CLIP image embeddings", self.clip, "A second model, roughly 600 MB of extra RAM; the unit's memory cap is raised to 3G.")
        form.entry("Port", self.port, "A port other than 8600 is applied through a systemd drop-in.", width=8)
        form.entry("Timeout (s)", self.timeout, width=8)
        form.entry("intelligence-max-bytes", self.max_bytes, "Content travels base64-encoded, so a low tens-of-MiB value is more proportionate than the 100 MiB default.", width=14)

    def load(self, plan: InstallPlan) -> None:
        it = plan.intelligence
        self.enabled.set(it.enabled)
        self.source_dir.set(it.source_dir or plan.intelligence_source_dir)
        self.clone_dir.set(it.driver_clone_dir or plan.intelligence_driver_clone_dir)
        self.encryption.set(it.enable_encryption)
        self.cpu_only.set(it.cpu_only_torch)
        self.ocr.set(it.ocr)
        self.ocr_languages.set(it.ocr_languages)
        self.clip.set(it.clip)
        self.port.set(str(it.port))
        self.timeout.set(str(it.timeout_seconds))
        self.max_bytes.set(str(it.max_bytes))

    def store(self, plan: InstallPlan) -> None:
        it = plan.intelligence
        it.enabled = self.enabled.get()
        it.source_dir = self.source_dir.get().strip()
        it.driver_clone_dir = self.clone_dir.get().strip()
        it.enable_encryption = self.encryption.get()
        it.cpu_only_torch = self.cpu_only.get()
        it.ocr = self.ocr.get()
        it.ocr_languages = self.ocr_languages.get().strip()
        it.clip = self.clip.get()
        it.port = _int(self.port, "Intelligence port")
        it.timeout_seconds = _int(self.timeout, "Intelligence timeout", 1, 3600)
        it.max_bytes = _int(self.max_bytes, "intelligence-max-bytes", 1, 1 << 40)


class SummaryPage(Page):
    """What will happen, the destructive actions, the run itself, and what comes after."""

    step_id, title = "", "Summary & install"

    def build(self, body: ttk.Frame) -> None:
        self.warnings = ttk.Label(body, text="", style="Warn.TLabel", wraplength=720, justify="left")
        self.warnings.pack(anchor="w", padx=10, pady=(4, 8))

        destructive = section(body, "Destructive actions in this run")
        self.destructive = ttk.Label(destructive, text="", wraplength=700, justify="left")
        self.destructive.pack(anchor="w", padx=8)
        self.acknowledge = tk.BooleanVar()
        self.acknowledge_box = ttk.Checkbutton(destructive, text="I have read the list above", variable=self.acknowledge, command=self._sync_button)
        self.acknowledge_box.pack(anchor="w", padx=8, pady=4)

        plan_box = section(body, "Plan")
        self.plan_table = ttk.Treeview(plan_box, columns=("does",), height=16)
        self.plan_table.heading("#0", text="Step")
        self.plan_table.heading("does", text="Will do")
        self.plan_table.column("#0", width=200)
        self.plan_table.column("does", width=620)
        self.plan_table.pack(fill="x", padx=8, pady=4)

        buttons = ttk.Frame(body)
        buttons.pack(fill="x", padx=10, pady=8)
        self.install_button = ttk.Button(buttons, text="Install selected steps", style="Primary.TButton", command=self.actions.install)
        self.install_button.pack(side="left")
        ttk.Button(buttons, text="Stop", command=self.actions.stop).pack(side="left", padx=6)
        self.counter = ttk.Label(buttons, text="", style="Hint.TLabel")
        self.counter.pack(side="left", padx=8)

        secrets_box = section(body, "Generated secrets - shown once")
        self.secrets = ttk.Label(secrets_box, text="", style="Mono.TLabel", justify="left")
        self.secrets.pack(anchor="w", padx=8)
        ttk.Button(secrets_box, text="Copy", width=7, command=self._copy_secrets).pack(anchor="w", padx=8, pady=4)

        next_box = section(body, "Next steps")
        self.next_steps = ttk.Label(next_box, text="", wraplength=700, justify="left")
        self.next_steps.pack(anchor="w", padx=8)

    def refresh(self, state: AppState) -> None:
        from cloud_driver_installer.steps import all_steps

        warnings = state.plan.warnings(state.discovered)
        self.warnings.configure(text="\n".join(f"• {line}" for line in warnings))
        actions = state.destructive_actions()
        self.destructive.configure(text="\n".join(f"• {line}" for line in actions) or "nothing in this run replaces something already in use")
        self.acknowledge_box.configure(state="normal" if actions else "disabled")
        # A changed list has not been read yet, so the confirmation starts over.
        if actions != getattr(self, "_last_actions", None):
            self._last_actions = list(actions)
            self.acknowledge.set(not actions)
        state.acknowledged = self.acknowledge.get()

        reasons = state.dependency_reasons()
        for item in self.plan_table.get_children():
            self.plan_table.delete(item)
        for step in all_steps():
            does = reasons.get(step.id) or step.describe(state.plan)
            self.plan_table.insert("", "end", text=state.title_of(step.id), values=(does,))
        will_run, total = state.selected_count()
        self.counter.configure(text=f"{will_run} of {total} steps")
        self.secrets.configure(text=self._secret_lines(state) or "nothing generated yet")
        self.next_steps.configure(text="\n".join(f"{index}. {line}" for index, line in enumerate(self._next_steps(state), start=1)))
        self._sync_button()

    def _sync_button(self) -> None:
        self.install_button.configure(state="normal" if self.acknowledge.get() else "disabled")
        self.state.acknowledged = self.acknowledge.get()

    def _secret_lines(self, state: AppState) -> str:
        secrets = state.secrets
        rows = [
            ("PostgreSQL password", secrets.pg_password, secrets.pg_password_kept),
            ("Redis password", secrets.redis_password, secrets.redis_password_kept),
            ("JWT signing key", secrets.jwt_signing_key, secrets.jwt_kept),
            ("Semantic-search secret", secrets.intelligence_secret, secrets.intelligence_secret_kept),
            ("Server access key id", secrets.server_access_key_id, secrets.server_key_kept),
        ]
        lines = [f"{name:<24} {value}" + ("   (kept from the server)" if kept else "") for name, value, kept in rows if value]
        if secrets.ses_dkim_records:
            lines.append("")
            lines.extend(f"DKIM  {name} CNAME {value}" for name, value in secrets.ses_dkim_records)
        return "\n".join(lines)

    def _copy_secrets(self) -> None:
        self.clipboard_clear()
        self.clipboard_append(self._secret_lines(self.state))

    @staticmethod
    def _next_steps(state: AppState) -> list[str]:
        plan = state.plan
        steps = [
            f"Register the first account from a client, then: screen -r {plan.server.screen_session} → admin grant <email> → Ctrl-A d.",
        ]
        if plan.email.mode == "none":
            steps.append("No mail transport: verification codes appear only in the server console and its log file.")
        if plan.email.mode == "ses":
            steps.append("Publish the DKIM records above, add a _dmarc TXT record, and request SES production access.")
        if plan.clamav.enabled:
            steps.append("Wait for freshclam's first signature download (systemctl status clamav-freshclam) before trusting scan verdicts.")
        if not plan.proxy.enabled:
            steps.append("Without a reverse proxy there is no HTTPS endpoint - the shipped desktop and iOS apps cannot connect.")
        elif plan.proxy.api_domain and plan.proxy.api_domain != CLIENT_HARDCODED_API_HOST:
            steps.append(f"The shipped apps are built for https://{CLIENT_HARDCODED_API_HOST} - rebuild them for {plan.proxy.api_domain} or point that name here.")
        if plan.intelligence.enabled and plan.intelligence.enable_encryption:
            steps.append("A freshly encrypted vector store starts empty: run 'intelligence backfill all --content' in the operator terminal.")
        steps.append("Later deploys: shell/deploy-cloud.sh ships the configuration.json this run wrote back into the checkout.")
        return steps


#: ``step id -> page class``; the summary is keyed by ``"summary"``.
PAGES: dict[str, type[Page]] = {
    "server": ServerPage,
    "packages": PackagesPage,
    "java": JavaPage,
    "python": PythonPage,
    "postgres": PostgresPage,
    "redis": RedisPage,
    "clamav": ClamavPage,
    "firewall": FirewallPage,
    "swap": SwapPage,
    "aws": AwsPage,
    "email": EmailPage,
    "caddy": CaddyPage,
    "config": ConfigPage,
    "application": ApplicationPage,
    "intelligence": IntelligencePage,
    "summary": SummaryPage,
}


def page_ids() -> list[str]:
    """Every page in sidebar order (the smoke step is reported on the summary page)."""
    return [step_id for step_id, _ in STEP_ORDER if step_id in PAGES] + ["summary"]

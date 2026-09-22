"""The install plan: every setting the operator can make, with the reference deployment's defaults.

The plan is plain dataclasses so it can be rendered into the GUI, saved as a profile (secrets
stripped - see :mod:`cloud_driver_installer.profiles`), validated before a run, and handed to the
steps. Nothing here talks to the network.

Defaults follow the reference deployment (``shell/provision-root-server.sh``,
``shell/start-cloud.sh``, docs/requirements.md): ``/home/cloud`` as the working directory, a
loopback-bound REST port 8080 behind Caddy, loopback Postgres/Redis/clamd, a 4 GiB swapfile, and a
whole-gigabyte ``-Xmx`` derived from the box's RAM.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field, fields, is_dataclass
from pathlib import Path
from typing import Any

from cloud_driver_installer.sizing import GIB, parse_xmx_mib
from cloud_driver_installer.ssh import SshTarget

#: The API hostname both shipped client apps hardcode (desktop ``Main.kt`` ``DEFAULT_SERVER_URL``,
#: Swift ``APIClient.shared``); a different API domain means those apps must be rebuilt.
CLIENT_HARDCODED_API_HOST = "api.cloud-driver.de"

#: Bind addresses that are only reachable from the server itself.
LOOPBACK_HOSTS = ("127.0.0.1", "localhost", "::1")

#: Field names whose values are secrets: never written to a profile, always redacted in the log.
SECRET_FIELD_NAMES = frozenset(
    {
        "key_passphrase",
        "password",
        "secret_access_key",
        "session_token",
        "smtp_password",
    }
)

_DOMAIN_RE = re.compile(r"^(?=.{1,253}$)(?!-)[a-z0-9-]{1,63}(?<!-)(\.(?!-)[a-z0-9-]{1,63}(?<!-))+$", re.IGNORECASE)
_BUCKET_RE = re.compile(r"^(?!\d+\.\d+\.\d+\.\d+$)[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])?$")
_BUCKET_BAD_RE = re.compile(r"\.\.|^xn--|-s3alias$|--ol-s3$")
_IAM_NAME_RE = re.compile(r"^[\w+=,.@-]{1,64}$")
_KMS_ALIAS_RE = re.compile(r"^alias/[A-Za-z0-9/_-]{1,250}$")
_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
_PG_IDENT_RE = re.compile(r"^[a-z_][a-z0-9_]{0,62}$")


# --- settings groups ---------------------------------------------------------------------------


@dataclass
class ServerSettings:
    """Preflight/OS-level choices."""

    install_dir: str = "/home/cloud"
    screen_session: str = "cloud"
    swap_mb: int = 4096
    firewall: bool = True
    firewall_extra_ports: str = ""  # comma/space separated, e.g. "9404/tcp"
    ntp: bool = True                # ensure clock synchronisation (AWS SigV4 + JWT expiry depend on it)
    timezone: str = ""              # "" = leave the host's timezone alone
    unattended_upgrades: bool = False
    #: Opt-in: add a ``Host <alias>`` entry to the operator's ~/.ssh/config so the existing shell
    #: scripts (deploy-cloud.sh, install-on-server.sh, …) can target this box unchanged.
    write_ssh_alias: bool = False
    ssh_alias_name: str = "cloud_driver"
    #: Opt-in: append a public key to the server's /root/.ssh/authorized_keys, so key-based root
    #: login (which every shell script assumes) works after a password-authenticated first run.
    install_public_key: bool = False
    public_key_path: str = ""


@dataclass
class PostgresSettings:
    """PostgreSQL: installed locally or an external server; the file is written either way."""

    mode: str = "install"  # install | external
    host: str = "127.0.0.1"
    port: int = 5432
    database: str = "cloud_driver"
    username: str = "cloud_driver"
    password: str = ""     # blank + install mode = generated (or kept from the server's file)
    rotate: bool = False


@dataclass
class RedisSettings:
    """Redis is optional; ``database`` must be a numeric index (parsed with Integer.parseInt)."""

    enabled: bool = True
    mode: str = "install"  # install | external
    host: str = "127.0.0.1"
    port: int = 6379
    username: str = ""     # the shipped config uses requirepass only (no ACL user)
    database: str = "0"
    password: str = ""
    rotate: bool = False


@dataclass
class ClamAvSettings:
    """clamd on a loopback TCP socket via the systemd socket drop-in."""

    enabled: bool = True
    host: str = "127.0.0.1"
    port: int = 3310
    timeout_seconds: int = 30
    stream_max_length: str = "128M"
    max_file_size: str = "128M"
    max_scan_size: str = "300M"
    content_scan_max_bytes: int = 104857600


@dataclass
class AwsSettings:
    """Local (operator-side) AWS provisioning and the identity the server runs as."""

    credential_source: str = "profile"  # profile | keys
    profile_name: str = "default"
    access_key_id: str = ""
    secret_access_key: str = ""
    session_token: str = ""
    region: str = "eu-central-1"

    kms_mode: str = "create"            # create | existing
    kms_alias: str = "alias/cloud-driver-kms-key"
    kms_key_id: str = ""                # existing mode: key id / ARN / alias
    kms_enable_rotation: bool = True    # automatic yearly rotation, transparent to Decrypt

    s3_enabled: bool = True
    s3_mode: str = "create"             # create | existing
    s3_bucket: str = ""
    s3_key_prefix: str = ""
    s3_abort_multipart_days: int = 7    # lifecycle rule for abandoned multipart uploads (0 = none)
    s3_versioning: bool = False
    #: One-shot acknowledgement: the server's configuration.json names a bucket but the plan
    #: disables S3 - every S3-backed file would become unreadable. Never saved in a profile.
    allow_disable_s3: bool = False

    #: Off-site backup copies go to a SEPARATE bucket: the operator terminal's ``s3 purge`` deletes
    #: every object in the content bucket no file references, and the runtime key may delete
    #: there - a dedicated bucket gets only put/get/list from the server. "" = ``<s3_bucket>-backups``.
    backup_bucket: str = ""
    backup_retention_days: int = 60

    server_identity: str = "iam_user"   # iam_user | reuse
    iam_user_name: str = "cloud-driver-server"
    rotate_server_key: bool = False
    #: One-shot acknowledgement that the KMS key named in the server's existing configuration.json
    #: may be replaced (existing rows stay decryptable only while the old key exists and the
    #: runtime identity may still decrypt with it). Never saved in a profile.
    allow_kms_change: bool = False

    @property
    def effective_backup_bucket(self) -> str:
        return self.backup_bucket or (f"{self.s3_bucket}-backups" if self.s3_bucket else "")


@dataclass
class EmailSettings:
    """Verification-code transport: SES (preferred), SMTP, or log-only."""

    mode: str = "ses"  # none | ses | smtp
    ses_region: str = ""              # "" = same as aws.region
    ses_from_address: str = ""
    ses_identity_mode: str = "domain" # domain (Easy DKIM, deliverable) | address
    ses_verify_identity: bool = True
    ses_configuration_set: str = ""
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_username: str = ""
    smtp_password: str = ""
    smtp_from_address: str = ""


@dataclass
class ProxySettings:
    """Caddy in front of the loopback REST port.

    ``api_domain`` is optional. With a domain Caddy obtains a Let's Encrypt certificate for it and
    the API is reachable over HTTPS; left empty, Caddy serves the reverse proxy as plain HTTP on
    port 80 for whatever address the request arrived on (the server's IP), which is the only thing
    it can do without a name to put on a certificate. Adding the domain later and re-running the
    step upgrades that site block in place.
    """

    enabled: bool = True
    api_domain: str = ""
    acme_email: str = ""


@dataclass
class AppSettings:
    """The JVM process, its listeners, capacity, heap, and how it is deployed."""

    rest_port: int = 8080
    rest_bind_host: str = "127.0.0.1"
    metrics_port: int = 9404
    metrics_bind_host: str = "127.0.0.1"
    server_max_bytes: int = 256 * GIB
    user_max_bytes: int = 1 * GIB
    jvm_xmx: str = ""                  # "" = suggest from RAM at check time
    jwt_rotate: bool = False

    repo_root: str = ""                # auto-detected
    build_with_maven: bool = False
    deploy_jars: bool = True
    excluded_extensions: list[str] = field(default_factory=list)  # jar base names to leave out
    start_after_deploy: bool = True
    #: Write configuration.json back to <repo>/cloud-driver/ - the file shell/deploy-cloud.sh ships
    #: on every run, so leaving it stale would revert the server on the next deploy.
    write_local_config: bool = True
    #: Also write postgres-/redis-database.json locally (nothing deploys them; they only matter
    #: for running a local backend against this box).
    write_local_db_config: bool = False

    autostart_on_reboot: bool = True   # cron @reboot -> start-cloud.sh (inside screen, for the pty)
    persist_log: bool = True           # screen -L into <install_dir>/cloud.log + logrotate
    scratch_sweep: bool = True         # hourly cron removing upload-*.tmp older than 3 h
    backup_offsite: bool = True        # daily cron: aws s3 sync backup/ -> s3://bucket/<prefix>backups/
    backup_offsite_prefix: str = "backups/"


@dataclass
class IntelligenceSettings:
    """The optional Python semantic-search service."""

    enabled: bool = False
    host: str = "127.0.0.1"
    port: int = 8600
    timeout_seconds: int = 30
    max_bytes: int = 32 * 1024 * 1024
    source_dir: str = ""               # defaults to <repo_root>/cloud-driver-intelligence
    driver_clone_dir: str = ""         # defaults to <repo_root>/../database-driver-v2 when present
    enable_encryption: bool = True
    cpu_only_torch: bool = True        # --extra-index-url for CPU wheels (no GPU on the box)
    ocr: bool = False                  # tesseract + poppler + the `ocr` extra
    ocr_languages: str = "deu+eng"
    clip: bool = False                 # second (~600 MB) model; off by default
    secret_rotate: bool = False


@dataclass
class InstallPlan:
    """Everything the operator decided."""

    ssh: SshTarget = field(default_factory=lambda: SshTarget(host=""))
    server: ServerSettings = field(default_factory=ServerSettings)
    postgres: PostgresSettings = field(default_factory=PostgresSettings)
    redis: RedisSettings = field(default_factory=RedisSettings)
    clamav: ClamAvSettings = field(default_factory=ClamAvSettings)
    aws: AwsSettings = field(default_factory=AwsSettings)
    email: EmailSettings = field(default_factory=EmailSettings)
    proxy: ProxySettings = field(default_factory=ProxySettings)
    app: AppSettings = field(default_factory=AppSettings)
    intelligence: IntelligenceSettings = field(default_factory=IntelligenceSettings)

    # --- derived paths -------------------------------------------------------------------------

    @property
    def config_dir(self) -> str:
        return f"{self.server.install_dir.rstrip('/')}/cloud-driver"

    @property
    def extensions_dir(self) -> str:
        return f"{self.server.install_dir.rstrip('/')}/extensions"

    @property
    def intelligence_source_dir(self) -> str:
        return self.intelligence.source_dir or (str(Path(self.app.repo_root) / "cloud-driver-intelligence") if self.app.repo_root else "")

    @property
    def intelligence_driver_clone_dir(self) -> str:
        if self.intelligence.driver_clone_dir:
            return self.intelligence.driver_clone_dir
        if self.app.repo_root:
            candidate = Path(self.app.repo_root).parent / "database-driver-v2"
            if (candidate / "python" / "database-driver-api").is_dir():
                return str(candidate)
        return ""

    @property
    def ses_region(self) -> str:
        return self.email.ses_region or self.aws.region

    # --- validation ----------------------------------------------------------------------------

    def validate(self, discovered: "Discovered | None" = None) -> list[str]:
        """Return every problem that must be fixed before a run (empty = valid).

        With ``discovered`` (the server's current state) the re-run hazards are checked too:
        disabling S3 while the server still stores content there, or replacing the KMS key.
        """
        problems: list[str] = []
        s = self.server
        if not s.install_dir.startswith("/"):
            problems.append("Server: install directory must be an absolute path")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", s.screen_session or ""):
            problems.append("Server: screen session name may only contain letters, digits, . _ -")
        if s.swap_mb < 0 or s.swap_mb > 65536:
            problems.append("Server: swap size must be between 0 and 65536 MB")
        for port_spec in _split_list(s.firewall_extra_ports):
            if not re.fullmatch(r"\d{1,5}(/(tcp|udp))?", port_spec):
                problems.append(f"Firewall: '{port_spec}' is not a port (e.g. 9404/tcp)")
        if s.write_ssh_alias and not re.fullmatch(r"[A-Za-z0-9_.-]+", s.ssh_alias_name or ""):
            problems.append("Server: the ssh alias name may only contain letters, digits, . _ -")

        pg = self.postgres
        if pg.mode not in ("install", "external"):
            problems.append("PostgreSQL: mode must be install or external")
        if not pg.host:
            problems.append("PostgreSQL: host is required")
        if not _valid_port(pg.port):
            problems.append("PostgreSQL: port must be 1-65535")
        if not _PG_IDENT_RE.match(pg.database):
            problems.append("PostgreSQL: database name must be a simple lowercase identifier")
        if not _PG_IDENT_RE.match(pg.username):
            problems.append("PostgreSQL: username must be a simple lowercase identifier")
        if pg.mode == "external" and not pg.password:
            problems.append("PostgreSQL: a password is required for an external server")

        rd = self.redis
        if rd.enabled:
            if rd.mode not in ("install", "external"):
                problems.append("Redis: mode must be install or external")
            if rd.mode == "install" and rd.username:
                problems.append("Redis: leave the username empty for a local install - the installer configures requirepass only, and a username makes AUTH fail so Redis silently switches off")
            if not rd.host:
                problems.append("Redis: host is required")
            if not _valid_port(rd.port):
                problems.append("Redis: port must be 1-65535")
            if not re.fullmatch(r"\d{1,3}", rd.database or ""):
                problems.append("Redis: database must be a numeric index (the backend parses it as an integer)")
            if rd.mode == "external" and not rd.password:
                problems.append("Redis: a password is required for an external server")

        cl = self.clamav
        if cl.enabled:
            if not _valid_port(cl.port):
                problems.append("ClamAV: port must be 1-65535")
            from cloud_driver_installer.sizing import parse_clamd_size  # local import keeps sizing free of model

            try:
                for label, value in (("StreamMaxLength", cl.stream_max_length), ("MaxFileSize", cl.max_file_size), ("MaxScanSize", cl.max_scan_size)):
                    if parse_clamd_size(value) < cl.content_scan_max_bytes and label != "MaxScanSize":
                        problems.append(f"ClamAV: {label} ({value}) must not be below content-scan-max-bytes ({cl.content_scan_max_bytes}) or scans in that range fail open")
            except ValueError as exc:
                problems.append(f"ClamAV: {exc}")
            if cl.content_scan_max_bytes <= 0:
                problems.append("ClamAV: content-scan-max-bytes must be positive")

        aws = self.aws
        if aws.credential_source == "keys":
            if not aws.access_key_id or not aws.secret_access_key:
                problems.append("AWS: access key id and secret are required")
        elif aws.credential_source == "profile":
            if not aws.profile_name:
                problems.append("AWS: choose a local profile")
        else:
            problems.append("AWS: credential source must be profile or keys")
        if not re.fullmatch(r"[a-z]{2}-[a-z]+-\d", aws.region or ""):
            problems.append("AWS: region looks wrong (expected e.g. eu-central-1)")
        if aws.kms_mode == "create":
            if not _KMS_ALIAS_RE.match(aws.kms_alias or ""):
                problems.append("AWS: KMS alias must look like alias/name")
        elif aws.kms_mode == "existing":
            if not aws.kms_key_id:
                problems.append("AWS: enter the existing KMS key id, ARN or alias")
        else:
            problems.append("AWS: KMS mode must be create or existing")
        if aws.s3_enabled:
            if aws.s3_mode not in ("create", "existing"):
                problems.append("AWS: S3 mode must be create or existing")
            if not valid_bucket_name(aws.s3_bucket):
                problems.append("AWS: S3 bucket name is invalid (3-63 chars, lowercase letters, digits, dots, hyphens; no IP form)")
            if aws.s3_key_prefix and (aws.s3_key_prefix.startswith("/") or aws.s3_key_prefix.endswith("/") or "//" in aws.s3_key_prefix):
                problems.append("AWS: S3 key prefix must not start or end with / or contain // (the backend joins it with a single /)")
            if aws.s3_abort_multipart_days < 0:
                problems.append("AWS: multipart abort days cannot be negative")
            if self.app.backup_offsite:
                if not valid_bucket_name(aws.effective_backup_bucket):
                    problems.append("AWS: backup bucket name is invalid")
                if aws.effective_backup_bucket == aws.s3_bucket:
                    problems.append("AWS: the backup bucket must differ from the content bucket (the terminal's 's3 purge' would delete the backups)")
                if aws.backup_retention_days < 1:
                    problems.append("AWS: backup retention must be at least 1 day")
        if aws.server_identity == "iam_user":
            if not _IAM_NAME_RE.match(aws.iam_user_name or ""):
                problems.append("AWS: IAM user name is invalid")
        elif aws.server_identity != "reuse":
            problems.append("AWS: server identity must be iam_user or reuse")

        em = self.email
        if em.mode == "ses":
            if not _EMAIL_RE.match(em.ses_from_address or ""):
                problems.append("E-mail: SES from address must be a valid e-mail address")
            if em.ses_region and not re.fullmatch(r"[a-z]{2}-[a-z]+-\d", em.ses_region):
                problems.append("E-mail: SES region looks wrong")
            if em.ses_identity_mode not in ("domain", "address"):
                problems.append("E-mail: SES identity mode must be domain or address")
        elif em.mode == "smtp":
            if not em.smtp_host:
                problems.append("E-mail: SMTP host is required")
            if not _valid_port(em.smtp_port):
                problems.append("E-mail: SMTP port must be 1-65535")
            if not em.smtp_username or not em.smtp_password:
                problems.append("E-mail: SMTP username and password are required (the backend needs all five smtp-* keys)")
            if not _EMAIL_RE.match(em.smtp_from_address or ""):
                problems.append("E-mail: SMTP from address must be a valid e-mail address")
        elif em.mode != "none":
            problems.append("E-mail: mode must be none, ses or smtp")

        px = self.proxy
        if px.enabled:
            # An empty domain is a deliberate deployment shape, not a mistake: Caddy then serves
            # plain HTTP on :80 for whatever address the request arrived on (see ProxySettings).
            if px.api_domain and not valid_domain(px.api_domain):
                problems.append("Reverse proxy: API domain must be a hostname such as api.example.com, or empty to serve this server's address over plain HTTP")
            if px.acme_email and not _EMAIL_RE.match(px.acme_email):
                problems.append("Reverse proxy: ACME e-mail must be a valid e-mail address")

        app = self.app
        if not _valid_port(app.rest_port):
            problems.append("Application: REST port must be 1-65535")
        if not _valid_port(app.metrics_port):
            problems.append("Application: metrics port must be 1-65535")
        used_ports: dict[int, str] = {app.rest_port: "REST", app.metrics_port: "metrics"}
        if self.intelligence.enabled:
            used_ports.setdefault(self.intelligence.port, "intelligence")
            if self.intelligence.port in (app.rest_port, app.metrics_port):
                problems.append("Application: the intelligence port must differ from the REST and metrics ports")
        if app.rest_port == app.metrics_port:
            problems.append("Application: REST and metrics ports must differ")
        if pg.mode == "install" and pg.port in used_ports:
            problems.append(f"PostgreSQL: port {pg.port} is already used by the {used_ports[pg.port]} listener")
        if rd.enabled and rd.mode == "install" and rd.port in used_ports:
            problems.append(f"Redis: port {rd.port} is already used by the {used_ports[rd.port]} listener")
        if px.enabled and (app.rest_port in (80, 443) or app.metrics_port in (80, 443)):
            problems.append("Application: ports 80 and 443 belong to Caddy when the reverse proxy is enabled")
        if app.user_max_bytes > app.server_max_bytes:
            problems.append("Application: the per-user quota cannot exceed the server storage capacity")
        if px.enabled and app.rest_bind_host not in LOOPBACK_HOSTS:
            problems.append("Application: behind Caddy the REST port must bind to 127.0.0.1 (the JVM speaks plain HTTP)")
        if app.server_max_bytes <= 0:
            problems.append("Application: server storage capacity must be positive")
        if app.user_max_bytes <= 0:
            problems.append("Application: per-user quota must be positive")
        if app.jvm_xmx:
            try:
                if parse_xmx_mib(app.jvm_xmx) < 1024:
                    problems.append("Application: -Xmx below 1g cannot run the backend")
            except ValueError as exc:
                problems.append(f"Application: {exc}")
        if (app.deploy_jars or app.build_with_maven or app.write_local_config) and not app.repo_root:
            problems.append("Application: repository root is required to deploy jars or write local config")
        if app.repo_root and not (Path(app.repo_root) / "pom.xml").is_file():
            problems.append(f"Application: {app.repo_root} does not contain pom.xml")
        if app.backup_offsite and not aws.s3_enabled:
            problems.append("Application: the off-site backup copy needs S3 enabled (it syncs into a dedicated backup bucket)")
        if s.install_public_key:
            if not s.public_key_path or not Path(os.path.expanduser(s.public_key_path)).is_file():
                problems.append("Server: choose the public key file (.pub) to install on the server")
            elif not Path(os.path.expanduser(s.public_key_path)).read_text().strip().startswith(("ssh-", "ecdsa-", "sk-")):
                problems.append("Server: the chosen file does not look like an OpenSSH public key")
        if discovered is not None:
            existing = discovered.existing_config or {}
            from cloud_driver_installer.config_files import real_value  # local import: config_files imports model

            existing_bucket = real_value(existing, "aws-s3-bucket")
            if existing_bucket and not aws.s3_enabled and not aws.allow_disable_s3:
                problems.append(f"AWS: the server's configuration.json uses S3 bucket {existing_bucket}; disabling S3 makes every S3-backed file unreadable - keep S3 (use existing) or tick the acknowledgement")
            existing_kms = real_value(existing, "aws-kms-key-id")
            planned_kms = aws.kms_key_id if aws.kms_mode == "existing" else aws.kms_alias
            if existing_kms and str(existing_kms) != planned_kms and not aws.allow_kms_change:
                problems.append(f"AWS: the server's configuration.json encrypts with KMS key {existing_kms}, the plan names {planned_kms}; existing rows stay readable only with the original key - select it under 'Use existing key' or tick the acknowledgement")

        it = self.intelligence
        if it.enabled:
            if not _valid_port(it.port):
                problems.append("Intelligence: port must be 1-65535")
            src = self.intelligence_source_dir
            if not src or not (Path(src) / "pyproject.toml").is_file():
                problems.append("Intelligence: source directory must contain the cloud-driver-intelligence pyproject.toml")
            if it.enable_encryption and not self.intelligence_driver_clone_dir:
                problems.append("Intelligence: at-rest encryption needs the database-driver-v2 clone (python/database-driver-api + -plugin) - point to it or disable encryption")
            if it.ocr and not re.fullmatch(r"[a-z]{3}(\+[a-z]{3})*", it.ocr_languages or ""):
                problems.append("Intelligence: OCR languages must look like deu+eng")
            if it.max_bytes <= 0:
                problems.append("Intelligence: max bytes must be positive")
        return problems

    def public_api_url(self, public_ip: str = "") -> str:
        """The base URL a client outside this server would use, or ``""`` when there is none.

        Three shapes: the reverse proxy with a domain (HTTPS on that name), the reverse proxy
        without one (plain HTTP on the server's address, port 80), and no proxy at all (the JVM's
        own REST port, only when it is not bound to loopback). Without a known public address the
        last two cannot be named, so the answer is empty.
        """
        if self.proxy.enabled and self.proxy.api_domain:
            return f"https://{self.proxy.api_domain}"
        if not public_ip:
            return ""
        if self.proxy.enabled:
            return f"http://{public_ip}"
        if self.app.rest_bind_host in LOOPBACK_HOSTS:
            return ""
        return f"http://{public_ip}:{self.app.rest_port}"

    def warnings(self, discovered: "Discovered | None" = None) -> list[str]:
        """Non-blocking things the summary page should say out loud."""
        notes: list[str] = []
        if self.email.mode == "none":
            notes.append("No e-mail transport: registration, password-reset and e-mail-change codes will only appear in the server log.")
        if self.email.mode == "ses":
            notes.append("SES: a new account is in the sandbox (200 mails/day, every recipient must be verified) until AWS grants production access.")
            if self.email.ses_identity_mode == "domain":
                notes.append("SES: publish the three DKIM CNAME records shown after the run, or mail from this domain lands in spam.")
        if not self.proxy.enabled:
            notes.append("No reverse proxy: the API is served as plain HTTP on the bind address; the shipped clients only speak https://.")
            if self.app.rest_bind_host in LOOPBACK_HOSTS:
                notes.append(f"Nothing can reach the API from outside: without the reverse proxy the REST port is bound to {self.app.rest_bind_host}. Bind 0.0.0.0 or turn the reverse proxy back on.")
        elif not self.proxy.api_domain:
            notes.append("Reverse proxy without a domain: Caddy answers on port 80 for this server's address and cannot obtain a certificate, so passwords and tokens travel unencrypted and the shipped clients (https:// only) cannot connect. Enter a domain and re-run this step to get TLS.")
        elif self.proxy.api_domain.lower() != CLIENT_HARDCODED_API_HOST:
            notes.append(f"The desktop and iOS apps are built for https://{CLIENT_HARDCODED_API_HOST}; serving {self.proxy.api_domain} means rebuilding them (or repointing that DNS name here).")
        if self.clamav.enabled:
            notes.append("ClamAV: freshclam downloads its signatures after install - uploads in the first minutes are scanned against an empty database.")
        if not self.aws.s3_enabled:
            notes.append("Without S3, file content is stored inline in Postgres rows - the database and its backups grow with every upload.")
        if not self.server.firewall:
            notes.append("Firewall disabled: make sure the provider firewall only exposes 22, 80 and 443.")
        if not self.app.autostart_on_reboot:
            notes.append("No reboot autostart: after a reboot the API stays down until someone runs start-cloud.sh.")
        if self.app.jwt_rotate:
            notes.append("Rotating the JWT signing key invalidates every client's access token until it refreshes (up to 12 h of re-logins); the JVM is restarted to apply it.")
        if not self.app.write_local_config:
            notes.append("Config write-back is off: the next shell/deploy-cloud.sh run would overwrite the server's configuration.json with the local copy - copy the generated file into <repo>/cloud-driver/ yourself.")
        if self.ssh.auth == "password" and not self.server.install_public_key:
            notes.append("You connected with a password: the shell scripts (deploy-cloud.sh, install-on-server.sh) need key-based root login - tick 'Install my public key' or add it by hand.")
        if discovered is not None:
            from cloud_driver_installer.config_files import FEATURE_KEY_GROUPS, real_value

            existing = discovered.existing_config or {}
            disabled = {
                "s3": not self.aws.s3_enabled,
                "ses": self.email.mode != "ses",
                "smtp": self.email.mode != "smtp",
                "clamav": not self.clamav.enabled,
                "intelligence": not self.intelligence.enabled,
            }
            for group, off in disabled.items():
                present = [k for k in FEATURE_KEY_GROUPS[group] if real_value(existing, k) is not None]
                if off and present:
                    notes.append(f"{group}: the server's configuration.json still carries {', '.join(present)} - these keys will be removed because the feature is disabled in this plan.")
        if discovered and discovered.ram_mib and self.app.jvm_xmx:
            from cloud_driver_installer.sizing import heap_is_tight

            try:
                if heap_is_tight(discovered.ram_mib, self.app.jvm_xmx, clamav_enabled=self.clamav.enabled, intelligence_enabled=self.intelligence.enabled):
                    notes.append(f"RAM is tight: -Xmx{self.app.jvm_xmx} plus Postgres, clamd and the intelligence service leave little headroom on {discovered.ram_mib // 1024} GiB; the swapfile is the safety net.")
            except ValueError:
                pass
        if self.intelligence.enabled and discovered and discovered.python_version and _version_tuple(discovered.python_version) < (3, 11) and self.intelligence.enable_encryption:
            notes.append(f"Intelligence: the encrypted vector store needs Python 3.11+, the server has {discovered.python_version}.")
        return notes


# --- state discovered on the server ------------------------------------------------------------


@dataclass
class Discovered:
    """Read-only facts gathered by the preflight step (and refreshed by later checks)."""

    os_pretty: str = ""
    os_id: str = ""            # debian | ubuntu
    kernel: str = ""
    cpu_count: int = 0
    ram_mib: int = 0
    swap_mib: int = 0
    disk_free_gib: float = 0.0
    public_ip: str = ""
    ipv6: str = ""
    ntp_synchronized: bool | None = None
    timezone: str = ""
    is_root: bool = False
    has_sudo: bool = False
    has_apt: bool = False
    has_systemd: bool = False
    java_version: str = ""
    python_version: str = ""
    venv_works: bool | None = None
    pg_installed: bool = False
    pg_version: str = ""
    redis_installed: bool = False
    clamd_installed: bool = False
    caddy_installed: bool = False
    ufw_active: bool | None = None
    screen_running: bool = False
    existing_jars: list[str] = field(default_factory=list)
    existing_config: dict[str, Any] = field(default_factory=dict)      # parsed configuration.json
    existing_postgres: dict[str, Any] = field(default_factory=dict)    # parsed postgres-database.json
    existing_redis: dict[str, Any] = field(default_factory=dict)       # parsed redis-database.json
    aws_credentials_present: bool = False
    intelligence_installed: bool = False
    intelligence_env: dict[str, str] = field(default_factory=dict)     # parsed env file (secret redacted later)

    def summary(self) -> str:
        parts = [p for p in (self.os_pretty, f"{self.ram_mib / 1024:.1f} GiB RAM" if self.ram_mib else "", f"{self.disk_free_gib:.0f} GiB free" if self.disk_free_gib else "") if p]
        return " · ".join(parts)


@dataclass
class GeneratedSecrets:
    """Every secret the run created or read back; shown once, never stored in a profile."""

    pg_password: str = ""
    pg_password_kept: bool = False
    redis_password: str = ""
    redis_password_kept: bool = False
    jwt_signing_key: str = ""
    jwt_kept: bool = False
    intelligence_secret: str = ""
    intelligence_secret_kept: bool = False
    intelligence_encryption_key_present: bool = False
    server_access_key_id: str = ""
    server_secret_access_key: str = ""
    server_key_kept: bool = False
    kms_key_id: str = ""            # the resolved id/ARN written to configuration.json (alias for created keys)
    kms_key_arn: str = ""
    s3_bucket: str = ""
    ses_dkim_records: list[tuple[str, str]] = field(default_factory=list)  # (cname name, value)

    def all_values(self) -> list[str]:
        return [self.pg_password, self.redis_password, self.jwt_signing_key, self.intelligence_secret, self.server_secret_access_key]


# --- helpers -----------------------------------------------------------------------------------


def detect_repo_root(start: Path | None = None) -> str:
    """Walk up from ``start`` (default: this package's location) to the directory holding ``pom.xml`` + ``cloud-driver-bootstrap``."""
    here = start or Path(__file__).resolve()
    for candidate in [here, *here.parents]:
        if (candidate / "pom.xml").is_file() and (candidate / "cloud-driver-bootstrap").is_dir():
            return str(candidate)
    cwd = Path.cwd().resolve()
    for candidate in [cwd, *cwd.parents]:
        if (candidate / "pom.xml").is_file() and (candidate / "cloud-driver-bootstrap").is_dir():
            return str(candidate)
    return ""


def default_plan() -> InstallPlan:
    """A plan with every default filled, the repo root auto-detected."""
    plan = InstallPlan()
    plan.app.repo_root = detect_repo_root()
    if plan.app.repo_root:
        local_config = Path(plan.app.repo_root) / "cloud-driver" / "configuration.json"
        if local_config.is_file():
            _prefill_from_local_config(plan, local_config)
    return plan


def _prefill_from_local_config(plan: InstallPlan, path: Path) -> None:
    """Borrow non-secret defaults (regions, bucket, domain-ish values) from the checkout's own config."""
    import json

    try:
        data = json.loads(path.read_text())
    except (OSError, ValueError):
        return
    if isinstance(data.get("aws-kms-region"), str) and data["aws-kms-region"] and "REPLACE" not in data["aws-kms-region"]:
        plan.aws.region = data["aws-kms-region"]
    if isinstance(data.get("aws-s3-bucket"), str) and data["aws-s3-bucket"] and "REPLACE" not in data["aws-s3-bucket"]:
        plan.aws.s3_bucket = data["aws-s3-bucket"]
        plan.aws.s3_mode = "existing"
    if isinstance(data.get("aws-s3-key-prefix"), str):
        plan.aws.s3_key_prefix = data["aws-s3-key-prefix"]
    if isinstance(data.get("aws-kms-key-id"), str) and data["aws-kms-key-id"] and "REPLACE" not in data["aws-kms-key-id"]:
        plan.aws.kms_mode = "existing"
        plan.aws.kms_key_id = data["aws-kms-key-id"]
    if isinstance(data.get("aws-ses-from-address"), str) and "@" in data["aws-ses-from-address"]:
        plan.email.mode = "ses"
        plan.email.ses_from_address = data["aws-ses-from-address"]
        plan.email.ses_region = data.get("aws-ses-region", "") or ""
        plan.email.ses_configuration_set = data.get("aws-ses-configuration-set", "") or ""
    elif isinstance(data.get("smtp-host"), str) and data["smtp-host"]:
        plan.email.mode = "smtp"
        plan.email.smtp_host = data["smtp-host"]
        plan.email.smtp_port = int(data.get("smtp-port", 587) or 587)
        plan.email.smtp_username = data.get("smtp-username", "") or ""
        plan.email.smtp_from_address = data.get("smtp-from-address", "") or ""
    for key, attr in (("cloud-server-max-bytes-available", "server_max_bytes"), ("cloud-user-max-bytes-to-upload", "user_max_bytes")):
        try:
            value = int(str(data.get(key, "")).strip())
            if value > 0:
                setattr(plan.app, attr, value)
        except ValueError:
            pass


def apply_existing_config(plan: InstallPlan, existing: dict[str, Any], *, postgres: dict[str, Any] | None = None, redis: dict[str, Any] | None = None) -> list[str]:
    """Pre-fill ``plan`` from the files already on the server, so a re-run keeps what is there.

    Only non-secret settings are copied (regions, ids, bucket, ports, addresses, capacities); a
    value that is a scaffold placeholder is ignored. Returns one line per setting taken over.
    """
    from cloud_driver_installer.config_files import real_value

    taken: list[str] = []

    def take(key: str) -> Any:
        return real_value(existing, key)

    if (value := take("aws-kms-key-id")) is not None:
        plan.aws.kms_mode = "existing"
        plan.aws.kms_key_id = str(value)
        taken.append(f"KMS key {value} (existing)")
    if (value := take("aws-kms-region")) is not None:
        plan.aws.region = str(value)
    if (value := take("aws-s3-bucket")) is not None:
        plan.aws.s3_enabled = True
        plan.aws.s3_mode = "existing"
        plan.aws.s3_bucket = str(value)
        plan.aws.s3_key_prefix = str(take("aws-s3-key-prefix") or "")
        taken.append(f"S3 bucket {value} (existing)")
    if (value := take("aws-ses-from-address")) is not None and "@" in str(value):
        plan.email.mode = "ses"
        plan.email.ses_from_address = str(value)
        plan.email.ses_region = str(take("aws-ses-region") or "")
        plan.email.ses_configuration_set = str(take("aws-ses-configuration-set") or "")
        taken.append(f"SES sender {value}")
    elif (value := take("smtp-host")) is not None:
        plan.email.mode = "smtp"
        plan.email.smtp_host = str(value)
        plan.email.smtp_username = str(take("smtp-username") or "")
        plan.email.smtp_from_address = str(take("smtp-from-address") or "")
        try:
            plan.email.smtp_port = int(take("smtp-port") or 587)
        except (TypeError, ValueError):
            pass
        taken.append(f"SMTP relay {value}")
    for key, attr in (("rest-server-port", "rest_port"), ("metrics-port", "metrics_port")):
        if (value := take(key)) is not None:
            try:
                setattr(plan.app, attr, int(str(value)))
            except ValueError:
                pass
    if (value := take("rest-server-bind-host")) is not None:
        plan.app.rest_bind_host = str(value)
    for key, attr in (("cloud-server-max-bytes-available", "server_max_bytes"), ("cloud-user-max-bytes-to-upload", "user_max_bytes")):
        if (value := take(key)) is not None:
            try:
                setattr(plan.app, attr, int(str(value)))
            except ValueError:
                pass
    if (value := take("intelligence-shared-secret")) is not None:
        plan.intelligence.enabled = True
        taken.append("intelligence service (secret present)")
        for key, attr, kind in (("intelligence-port", "port", int), ("intelligence-max-bytes", "max_bytes", int), ("intelligence-timeout-seconds", "timeout_seconds", int)):
            if (v := take(key)) is not None:
                try:
                    setattr(plan.intelligence, attr, kind(v))
                except (TypeError, ValueError):
                    pass
    for key, attr, kind in (("clamav-port", "port", int), ("clamav-timeout-seconds", "timeout_seconds", int), ("content-scan-max-bytes", "content_scan_max_bytes", int)):
        if (v := take(key)) is not None:
            try:
                setattr(plan.clamav, attr, kind(v))
            except (TypeError, ValueError):
                pass
    if postgres:
        for key, attr, kind in (("address", "host", str), ("port", "port", int), ("database", "database", str), ("userName", "username", str)):
            if postgres.get(key) not in (None, ""):
                try:
                    setattr(plan.postgres, attr, kind(postgres[key]))
                except (TypeError, ValueError):
                    pass
        if plan.postgres.host not in ("127.0.0.1", "localhost", "::1"):
            plan.postgres.mode = "external"
        taken.append(f"PostgreSQL {plan.postgres.username}@{plan.postgres.host}:{plan.postgres.port}/{plan.postgres.database}")
    if redis:
        plan.redis.enabled = True
        for key, attr, kind in (("address", "host", str), ("port", "port", int), ("database", "database", str), ("userName", "username", str)):
            if redis.get(key) not in (None,):
                try:
                    setattr(plan.redis, attr, kind(redis[key]))
                except (TypeError, ValueError):
                    pass
        if plan.redis.host not in ("127.0.0.1", "localhost", "::1"):
            plan.redis.mode = "external"
        taken.append(f"Redis {plan.redis.host}:{plan.redis.port}")
    return taken


def valid_bucket_name(name: str) -> bool:
    """S3 bucket naming rules (the subset that matters: no uppercase, no underscores, no IP form)."""
    return bool(name) and bool(_BUCKET_RE.match(name)) and not _BUCKET_BAD_RE.search(name)


def valid_domain(name: str) -> bool:
    """A hostname with at least one dot."""
    return bool(name) and bool(_DOMAIN_RE.match(name))


def _valid_port(port: int) -> bool:
    return isinstance(port, int) and 1 <= port <= 65535


def _split_list(text: str) -> list[str]:
    return [item for item in re.split(r"[,\s]+", text or "") if item]


def _version_tuple(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", version)[:3]) or (0,)


# --- (de)serialisation shared by profiles and tests --------------------------------------------


def to_dict(obj: Any, *, strip_secrets: bool = True) -> Any:
    """Recursively convert dataclasses to plain dicts (secret fields blanked when ``strip_secrets``)."""
    if is_dataclass(obj) and not isinstance(obj, type):
        result: dict[str, Any] = {}
        for f in fields(obj):
            value = getattr(obj, f.name)
            if strip_secrets and f.name in SECRET_FIELD_NAMES:
                result[f.name] = ""
            else:
                result[f.name] = to_dict(value, strip_secrets=strip_secrets)
        return result
    if isinstance(obj, list):
        return [to_dict(v, strip_secrets=strip_secrets) for v in obj]
    if isinstance(obj, tuple):
        return [to_dict(v, strip_secrets=strip_secrets) for v in obj]
    return obj


def from_dict(cls: type, data: Any) -> Any:
    """Inverse of :func:`to_dict`: unknown keys are ignored, missing keys keep their defaults."""
    if not is_dataclass(cls) or not isinstance(data, dict):
        return data
    kwargs: dict[str, Any] = {}
    for f in fields(cls):
        if f.name not in data:
            continue
        value = data[f.name]
        target = f.type if isinstance(f.type, type) else None
        if target is None:
            # dataclass fields declared with `from __future__ import annotations` are strings
            target = _resolve_type(cls, f.name)
        if target is not None and is_dataclass(target):
            kwargs[f.name] = from_dict(target, value)
        else:
            kwargs[f.name] = value
    return cls(**kwargs)


def _resolve_type(cls: type, field_name: str) -> type | None:
    import typing

    try:
        hints = typing.get_type_hints(cls)
    except Exception:
        return None
    hint = hints.get(field_name)
    return hint if isinstance(hint, type) else None


def env_default(name: str, fallback: str) -> str:
    """Environment override for a default (used by tests and headless runs)."""
    return os.environ.get(name, fallback)

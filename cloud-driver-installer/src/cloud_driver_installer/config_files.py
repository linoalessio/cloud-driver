"""Renders the files the JVM reads at boot, plus ``start-cloud.env``.

* ``configuration.json`` - every key in docs/configuration.md that the plan decides, with the
  reference deployment's defaults for the rest. Rendering **merges** into an existing file:
  keys the installer does not manage are preserved (an operator-added key must survive a
  re-run), keys of a feature that is switched off are removed (a stale ``aws-s3-bucket`` would
  activate S3 mode and crash-loop boot on a bucket that no longer exists), and every managed key
  is written fresh.
* ``postgres-database.json`` / ``redis-database.json`` - the credential shape the external
  ``database-driver`` expects, ``fileRepository`` included.
* ``start-cloud.env`` - sourced by ``shell/start-cloud.sh``; carries ``JVM_XMX`` (the RAM
  allocation this installer computes), ``SCREEN_SESSION`` and ``SCREEN_LOG_FILE``. The jar name
  is never pinned: the script resolves the single bootstrap jar in its directory itself.

Values that ``configuration.json`` stores as strings (ports, byte counts) are written as strings
too, exactly like the reference file, since ``JsonDocument#getString`` is what reads them.
"""

from __future__ import annotations

import json
from typing import Any

from cloud_driver_installer.model import GeneratedSecrets, InstallPlan

#: Keys this installer owns. Everything else in an existing configuration.json is passed through.
MANAGED_KEYS: tuple[str, ...] = (
    "rest-server-port",
    "rest-server-bind-host",
    "metrics-port",
    "metrics-bind-host",
    "cloud-server-max-bytes-available",
    "cloud-user-max-bytes-to-upload",
    "jwt-signing-key",
    "trust-proxy-headers",
    "trusted-proxy-addresses",
    "aws-kms-region",
    "aws-kms-key-id",
    "aws-s3-region",
    "aws-s3-bucket",
    "aws-s3-key-prefix",
    "aws-ses-region",
    "aws-ses-from-address",
    "aws-ses-configuration-set",
    "smtp-host",
    "smtp-port",
    "smtp-username",
    "smtp-password",
    "smtp-from-address",
    "clamav-host",
    "clamav-port",
    "clamav-timeout-seconds",
    "content-scan-max-bytes",
    "intelligence-shared-secret",
    "intelligence-host",
    "intelligence-port",
    "intelligence-timeout-seconds",
    "intelligence-max-bytes",
)

#: Keys with a code default that the reference file also spells out; written only when absent so an
#: operator's tuned value is never reset.
DEFAULTED_KEYS: dict[str, Any] = {
    "trash-retention-days": 30,
    "auth-rate-limit-max-requests": 10,
    "auth-rate-limit-window-seconds": 300,
    "api-rate-limit-read-max-requests": 300,
    "api-rate-limit-read-window-seconds": 60,
    "file-versioning-max-versions-per-file": 10,
    "file-versioning-retention-days": 30,
    "presigned-upload-ticket-retention-hours": 6,
}

#: Legacy keys nothing in the backend reads any more; dropped when merging.
OBSOLETE_KEYS: tuple[str, ...] = ("web-panel-port", "web-panel-bind-host")

#: ``provision-root-server.sh`` scaffolds these placeholders; a value starting with it is "unset".
PLACEHOLDER_PREFIX = "REPLACE-ME"

#: Key groups of features that can be switched off. When a group's feature is disabled in the plan
#: the merge REMOVES these keys: a leftover ``aws-s3-bucket`` would keep S3 mode active against a
#: bucket the plan no longer knows, and a leftover ``REPLACE-ME`` crash-loops boot.
FEATURE_KEY_GROUPS: dict[str, tuple[str, ...]] = {
    "s3": ("aws-s3-region", "aws-s3-bucket", "aws-s3-key-prefix"),
    "ses": ("aws-ses-region", "aws-ses-from-address", "aws-ses-configuration-set"),
    "smtp": ("smtp-host", "smtp-port", "smtp-username", "smtp-password", "smtp-from-address"),
    "clamav": ("clamav-host", "clamav-port", "clamav-timeout-seconds", "content-scan-max-bytes"),
    "intelligence": ("intelligence-shared-secret", "intelligence-host", "intelligence-port", "intelligence-timeout-seconds", "intelligence-max-bytes"),
}

#: Keys whose existing value must never be replaced silently on a re-run (see ``changed_managed_keys``).
SENSITIVE_KEYS: tuple[str, ...] = ("aws-kms-key-id", "aws-kms-region", "aws-s3-bucket", "aws-s3-region", "aws-s3-key-prefix", "jwt-signing-key", "intelligence-shared-secret")

#: Default location of the persistent console log (root-only directory, see start-cloud.sh).
SCREEN_LOG_FILE = "/var/log/cloud-driver/cloud.log"


def is_placeholder(value: Any) -> bool:
    """True for the scaffold's ``REPLACE-ME…`` strings (and blank strings)."""
    return isinstance(value, str) and (not value.strip() or value.strip().startswith(PLACEHOLDER_PREFIX))


def real_value(document: dict[str, Any], key: str) -> Any:
    """``document[key]`` unless absent, blank or a placeholder (then ``None``)."""
    value = document.get(key)
    return None if value is None or is_placeholder(value) else value


def render_configuration(plan: InstallPlan, secrets: GeneratedSecrets, existing: dict[str, Any] | None = None) -> dict[str, Any]:
    """Build the ``configuration.json`` document for ``plan`` (merged over ``existing``).

    Merge rules: unmanaged keys pass through untouched; obsolete keys and placeholder values are
    dropped; the keys of a disabled feature group are removed; everything the installer manages
    is written fresh.
    """
    doc: dict[str, Any] = {
        k: v
        for k, v in (existing or {}).items()
        if k not in MANAGED_KEYS and k not in OBSOLETE_KEYS and not is_placeholder(v)
    }
    app = plan.app
    doc["rest-server-port"] = str(app.rest_port)
    doc["rest-server-bind-host"] = app.rest_bind_host
    doc["metrics-port"] = app.metrics_port
    doc["metrics-bind-host"] = app.metrics_bind_host
    doc["cloud-server-max-bytes-available"] = str(app.server_max_bytes)
    doc["cloud-user-max-bytes-to-upload"] = str(app.user_max_bytes)
    doc["jwt-signing-key"] = secrets.jwt_signing_key

    behind_proxy = plan.proxy.enabled and app.rest_bind_host in ("127.0.0.1", "localhost", "::1")
    doc["trust-proxy-headers"] = bool(behind_proxy)
    if behind_proxy:
        # Caddy upstreams over IPv4 loopback; the backend matches this allowlist by exact string
        # against the peer address, so the one spelling that can ever occur is enough.
        doc["trusted-proxy-addresses"] = "127.0.0.1"

    doc["aws-kms-region"] = plan.aws.region
    doc["aws-kms-key-id"] = secrets.kms_key_id or plan.aws.kms_key_id or plan.aws.kms_alias

    if plan.aws.s3_enabled:
        doc["aws-s3-region"] = plan.aws.region
        doc["aws-s3-bucket"] = secrets.s3_bucket or plan.aws.s3_bucket
        doc["aws-s3-key-prefix"] = plan.aws.s3_key_prefix

    if plan.email.mode == "ses":
        doc["aws-ses-region"] = plan.ses_region
        doc["aws-ses-from-address"] = plan.email.ses_from_address
        if plan.email.ses_configuration_set:
            doc["aws-ses-configuration-set"] = plan.email.ses_configuration_set
    elif plan.email.mode == "smtp":
        doc["smtp-host"] = plan.email.smtp_host
        doc["smtp-port"] = plan.email.smtp_port
        doc["smtp-username"] = plan.email.smtp_username
        doc["smtp-password"] = plan.email.smtp_password
        doc["smtp-from-address"] = plan.email.smtp_from_address

    if plan.clamav.enabled:
        doc["clamav-host"] = plan.clamav.host
        doc["clamav-port"] = plan.clamav.port
        doc["clamav-timeout-seconds"] = plan.clamav.timeout_seconds
        doc["content-scan-max-bytes"] = plan.clamav.content_scan_max_bytes

    if plan.intelligence.enabled:
        doc["intelligence-shared-secret"] = secrets.intelligence_secret
        doc["intelligence-host"] = plan.intelligence.host
        doc["intelligence-port"] = plan.intelligence.port
        doc["intelligence-timeout-seconds"] = plan.intelligence.timeout_seconds
        doc["intelligence-max-bytes"] = plan.intelligence.max_bytes

    for key, value in DEFAULTED_KEYS.items():
        doc.setdefault(key, value)
    return doc


def render_postgres_credentials(plan: InstallPlan, password: str) -> dict[str, Any]:
    """``postgres-database.json`` in the exact shape ``database-driver`` reads."""
    return {
        "address": plan.postgres.host,
        "userName": plan.postgres.username,
        "password": password,
        "port": plan.postgres.port,
        "database": plan.postgres.database,
        "fileRepository": "Unknown",
    }


def render_redis_credentials(plan: InstallPlan, password: str) -> dict[str, Any]:
    """``redis-database.json`` - same shape; ``database`` stays a numeric *string*."""
    return {
        "address": plan.redis.host,
        "userName": plan.redis.username,
        "password": password,
        "port": plan.redis.port,
        "database": str(plan.redis.database),
        "fileRepository": "Unknown",
    }


def render_start_env(plan: InstallPlan, jar_name: str | None = None) -> str:
    """``start-cloud.env`` contents.

    The jar name is deliberately NOT pinned here: ``start-cloud.sh`` resolves the single
    ``cloud-driver-bootstrap-*.jar`` in its directory itself, so a later ``shell/deploy-cloud.sh``
    release bump keeps working. ``jar_name`` is accepted for callers that still pass it and ignored.
    """
    lines = [
        "# Written by cloud-driver-installer - sourced by start-cloud.sh. Re-run the installer to change.",
        f"JVM_XMX={plan.app.jvm_xmx}",
        f"SCREEN_SESSION={plan.server.screen_session}",
    ]
    if plan.app.persist_log:
        lines.append(f"SCREEN_LOG_FILE={SCREEN_LOG_FILE}")
    return "\n".join(lines) + "\n"


def removed_keys(existing: dict[str, Any] | None, rendered: dict[str, Any]) -> list[str]:
    """Keys with a real (non-placeholder) value in ``existing`` that ``rendered`` no longer carries."""
    return sorted(k for k, v in (existing or {}).items() if k not in rendered and not is_placeholder(v) and k not in OBSOLETE_KEYS)


def changed_sensitive_keys(existing: dict[str, Any] | None, rendered: dict[str, Any]) -> list[str]:
    """Sensitive keys whose existing real value differs from the rendered one (a re-run hazard)."""
    changed: list[str] = []
    for key in SENSITIVE_KEYS:
        old = real_value(existing or {}, key)
        if old is not None and key in rendered and str(rendered[key]) != str(old):
            changed.append(key)
    return changed


def to_json(document: dict[str, Any]) -> str:
    """Pretty JSON with a trailing newline (what the reference files look like)."""
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"


def parse_json(text: str | None) -> dict[str, Any]:
    """Parse a config file leniently: ``None``/blank/invalid -> ``{}``."""
    if not text or not text.strip():
        return {}
    try:
        data = json.loads(text)
    except ValueError:
        return {}
    return data if isinstance(data, dict) else {}


def masked(document: dict[str, Any]) -> dict[str, Any]:
    """A copy with secret-carrying values replaced by bullets (for previews)."""
    hidden = {"jwt-signing-key", "smtp-password", "intelligence-shared-secret", "password"}
    return {k: ("••••••••" if k in hidden and v else v) for k, v in document.items()}


def parse_env_file(text: str | None) -> dict[str, str]:
    """``KEY=value`` lines -> dict (comments and blanks skipped)."""
    result: dict[str, str] = {}
    for line in (text or "").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        result[key.strip()] = value.strip()
    return result

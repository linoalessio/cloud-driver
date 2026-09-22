"""Profiles: the plan saved as JSON, every secret blanked.

Profiles live under ``~/.config/cloud-driver-installer/profiles/<name>.json`` (or any path the
operator picks). They never contain the SSH password/passphrase, AWS secret keys, SMTP passwords
or any generated credential: those are typed again or, on a re-run, read back from the server.
"""

from __future__ import annotations

import json
from pathlib import Path

from cloud_driver_installer.model import InstallPlan, from_dict, to_dict

PROFILE_DIR = Path.home() / ".config" / "cloud-driver-installer" / "profiles"
FORMAT_VERSION = 1


def save_profile(plan: InstallPlan, path: Path) -> None:
    """Write ``plan`` to ``path`` with secrets stripped (mode 0600)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    document = {"format": FORMAT_VERSION, "plan": to_dict(plan, strip_secrets=True)}
    path.write_text(json.dumps(document, indent=2) + "\n")
    try:
        path.chmod(0o600)
    except OSError:
        pass


def load_profile(path: Path) -> InstallPlan:
    """Read a profile; unknown keys are ignored, missing ones keep their defaults."""
    document = json.loads(path.read_text())
    data = document.get("plan", document) if isinstance(document, dict) else {}
    plan = from_dict(InstallPlan, data)
    # One-shot, destructive or action flags never survive a save: an operator who rotated a
    # credential once must not re-rotate it weeks later just by loading the profile.
    plan.postgres.rotate = False
    plan.redis.rotate = False
    plan.aws.rotate_server_key = False
    plan.aws.allow_kms_change = False
    plan.aws.allow_disable_s3 = False
    plan.app.jwt_rotate = False
    plan.app.build_with_maven = False
    plan.intelligence.secret_rotate = False
    # A profile can never legitimately carry a secret; blank them even if someone edited the file.
    plan.ssh.password = ""
    plan.ssh.key_passphrase = ""
    plan.postgres.password = ""
    plan.redis.password = ""
    plan.aws.secret_access_key = ""
    plan.aws.session_token = ""
    plan.email.smtp_password = ""
    return plan


def list_profiles(directory: Path = PROFILE_DIR) -> list[Path]:
    """Every ``*.json`` under ``directory``, newest first."""
    if not directory.is_dir():
        return []
    return sorted(directory.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)

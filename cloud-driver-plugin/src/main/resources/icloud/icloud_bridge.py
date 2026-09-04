#!/usr/bin/env python3
"""
Bridge process invoked by cloud-driver-plugin's PythonIcloudBridge (Java) to talk to a real
Apple iCloud account via the unofficial `pyicloud` library - there is no public Apple API for
iCloud Drive, so this necessarily goes through the same private-API approach tools like
`pyicloud`/`rclone` use.

Protocol: one JSON object read from stdin, one JSON object written to stdout, per invocation.

Request shapes (by "action"):
  {"action": "login", "apple_id": "...", "password": "...", "session_dir": "..."}
  {"action": "confirm2fa", "apple_id": "...", "code": "...", "session_dir": "..."}
  {"action": "list_tree", "apple_id": "...", "session_dir": "..."}
  {"action": "download_file", "apple_id": "...", "session_dir": "...",
   "remote_path": "...", "destination": "..."}

Response shapes:
  {"status": "ok", ...}
  {"status": "error", "error_type": "auth" | "bridge", "message": "..."}

"error_type": "auth" means Apple itself rejected the credentials/code (mapped by the Java side
to IcloudAuthenticationException / HTTP 401); "bridge" means this script/pyicloud itself failed
(mapped to IcloudBridgeException).

`session_dir` is a per-import-job scratch directory; pyicloud persists its session cookie there
(via the `cookie_directory` constructor argument) so a later call in the same job resumes the
partially-or-fully authenticated session without the password being supplied again.

NOTE: pyicloud's exact API surface has shifted across versions in the past (constructor argument
names, exception types). This script targets the interface documented at
https://github.com/picklepete/pyicloud as of writing - re-verify against the installed version if
this starts failing with an AttributeError/TypeError rather than a clean "error" response.
"""

import json
import sys


def respond(payload):
    sys.stdout.write(json.dumps(payload))
    sys.stdout.flush()


def error(message, error_type="bridge"):
    respond({"status": "error", "error_type": error_type, "message": str(message)})


def classify_exception(exc):
    """pyicloud raises distinct exception classes for a rejected login/2FA code; anything else
    is treated as a bridge-level failure rather than an authentication one."""
    name = type(exc).__name__
    if "Login" in name or "Authentication" in name or "2FA" in name or "2SA" in name:
        return "auth"
    return "bridge"


def walk_drive(node, path, entries):
    for name in node.dir():
        child = node[name]
        child_path = f"{path}/{name}" if path else name
        if getattr(child, "type", None) == "folder":
            entries.append({"path": child_path, "directory": True, "size_bytes": 0})
            walk_drive(child, child_path, entries)
        else:
            size_bytes = getattr(child, "size", None) or 0
            entries.append({"path": child_path, "directory": False, "size_bytes": size_bytes})


def resolve_node(drive_root, remote_path):
    node = drive_root
    for part in remote_path.strip("/").split("/"):
        if part:
            node = node[part]
    return node


def main():
    try:
        payload = json.loads(sys.stdin.read())
    except Exception as parse_error:
        error(f"invalid request JSON: {parse_error}")
        return

    action = payload.get("action")

    try:
        from pyicloud import PyiCloudService
    except ImportError:
        error("pyicloud is not installed - run 'pip install pyicloud'")
        return

    apple_id = payload.get("apple_id")
    session_dir = payload.get("session_dir")

    try:
        if action == "login":
            password = payload.get("password")
            api = PyiCloudService(apple_id, password, cookie_directory=session_dir)
            respond({"status": "ok", "requires_two_factor": bool(getattr(api, "requires_2fa", False))})

        elif action == "confirm2fa":
            code = payload.get("code")
            api = PyiCloudService(apple_id, cookie_directory=session_dir)
            if not api.validate_2fa_code(code):
                error("the two-factor code was rejected", error_type="auth")
                return
            if not api.is_trusted_session:
                api.trust_session()
            respond({"status": "ok"})

        elif action == "list_tree":
            api = PyiCloudService(apple_id, cookie_directory=session_dir)
            entries = []
            walk_drive(api.drive, "", entries)
            respond({"status": "ok", "entries": entries})

        elif action == "download_file":
            api = PyiCloudService(apple_id, cookie_directory=session_dir)
            remote_path = payload.get("remote_path")
            destination = payload.get("destination")
            node = resolve_node(api.drive, remote_path)
            download = node.open(stream=True)
            with open(destination, "wb") as out_file:
                for chunk in download.iter_content(chunk_size=1024 * 64):
                    out_file.write(chunk)
            respond({"status": "ok"})

        else:
            error(f"unknown action '{action}'")

    except Exception as exc:
        error(str(exc), error_type=classify_exception(exc))


if __name__ == "__main__":
    main()

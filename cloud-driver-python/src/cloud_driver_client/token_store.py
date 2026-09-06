"""Session-token persistence, mirroring cloud-driver-platforms-rest's `api.session` package.

A microservice usually has no interactive OS keychain session at all, so `InMemoryTokenStore` (no
persistence - a fresh login every process start) is the default. `FileTokenStore`/`KeyringTokenStore`
exist for a long-running worker process that should survive a restart without re-authenticating.
"""

from __future__ import annotations

import json
import os
import stat
from pathlib import Path
from typing import Protocol


class TokenStore(Protocol):
    def load(self) -> tuple[str, str] | None:
        """Returns (access_token, refresh_token), or None if no session is stored."""

    def save(self, access_token: str, refresh_token: str) -> None: ...

    def clear(self) -> None: ...


class InMemoryTokenStore:
    """The default - no persistence across process restarts."""

    def __init__(self) -> None:
        self._tokens: tuple[str, str] | None = None

    def load(self) -> tuple[str, str] | None:
        return self._tokens

    def save(self, access_token: str, refresh_token: str) -> None:
        self._tokens = (access_token, refresh_token)

    def clear(self) -> None:
        self._tokens = None


class FileTokenStore:
    """A plain, permission-restricted file - the same fallback role
    cloud-driver-platforms-rest's own FileTokenStore plays there.

    Fixed a real finding in that Java class (see CLAUDE.md, 2026-09-05): a token file must never
    exist at a world/group-readable permission, even for the instant between creation and a
    later chmod - so the file is created with 0600 baked into the open() call itself, not applied
    as a separate step afterward.
    """

    def __init__(self, path: str | os.PathLike[str]) -> None:
        self._path = Path(path)

    def load(self) -> tuple[str, str] | None:
        try:
            data = json.loads(self._path.read_text("utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            return None
        access_token = data.get("access_token")
        refresh_token = data.get("refresh_token")
        if not access_token or not refresh_token:
            return None
        return access_token, refresh_token

    def save(self, access_token: str, refresh_token: str) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps({"access_token": access_token, "refresh_token": refresh_token}).encode("utf-8")
        fd = os.open(self._path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, stat.S_IRUSR | stat.S_IWUSR)
        try:
            os.write(fd, payload)
        finally:
            os.close(fd)

    def clear(self) -> None:
        self._path.unlink(missing_ok=True)


class KeyringTokenStore:
    """Backed by the OS keychain (macOS Keychain / Windows Credential Manager / Linux Secret
    Service) via the optional `keyring` package (`pip install cloud-driver-client[keyring]`) -
    for an interactive tool, not a headless microservice."""

    def __init__(self, service_name: str = "de.lino.cloud.python-client", username: str = "default") -> None:
        try:
            import keyring  # noqa: F401
        except ImportError as exc:  # pragma: no cover - exercised only without the extra installed
            raise ImportError(
                "KeyringTokenStore requires the 'keyring' extra: pip install cloud-driver-client[keyring]"
            ) from exc
        self._service_name = service_name
        self._username = username

    def load(self) -> tuple[str, str] | None:
        import keyring

        raw = keyring.get_password(self._service_name, self._username)
        if not raw:
            return None
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            return None
        access_token = data.get("access_token")
        refresh_token = data.get("refresh_token")
        if not access_token or not refresh_token:
            return None
        return access_token, refresh_token

    def save(self, access_token: str, refresh_token: str) -> None:
        import keyring

        keyring.set_password(
            self._service_name,
            self._username,
            json.dumps({"access_token": access_token, "refresh_token": refresh_token}),
        )

    def clear(self) -> None:
        import keyring
        from keyring.errors import PasswordDeleteError

        try:
            keyring.delete_password(self._service_name, self._username)
        except PasswordDeleteError:
            pass

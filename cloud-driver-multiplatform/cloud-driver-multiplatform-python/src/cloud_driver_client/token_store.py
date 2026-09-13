"""Session-token persistence, mirroring cloud-driver-platforms-rest's `api.session` package.

A microservice usually has no interactive OS keychain session at all, so `InMemoryTokenStore` (no
persistence - a fresh login every process start) is the default. `FileTokenStore`/`KeyringTokenStore`
exist for a long-running worker process that should survive a restart without re-authenticating,
and `DatabaseTokenStore` for a service that already keeps its state in a database-driver section
and wants its session in the same store rather than a separate file.
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
        if not isinstance(data, dict):
            # Well-formed JSON that is not an object (a list, a bare string ...) is the same
            # "no usable session" case as unparseable JSON, not a crash in the constructor of
            # whatever client eagerly load()s this store.
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
            if hasattr(os, "fchmod"):
                # os.open's mode only applies on *creation* - a token file that already exists
                # with looser permissions (restored from a backup, say) must be tightened too,
                # or the class docstring's promise silently stops holding.
                os.fchmod(fd, stat.S_IRUSR | stat.S_IWUSR)
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
        if not isinstance(data, dict):
            # Same reasoning as FileTokenStore.load: a non-object payload is "no session".
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


class DatabaseTokenStore:
    """Backed by a `DatabaseSection` of the database-driver Python edition (the
    `lino-database-driver-*` packages) - for a service that already runs its state through that
    driver and wants its cloud-driver session in the same store, whichever backend that is (the
    JSON file store, SQLite, Redis, Postgres, ...), instead of a second, separate token file.

    The section is *handed in*, never opened here: which database holds the session - its
    credentials, cache mode and lifecycle - stays the owning application's decision, exactly like
    every other section that application manages. Requires the `driver` extra
    (`pip install cloud-driver-client[driver]`, Python 3.11+; while the driver packages are not
    on an index yet, install them from the database-driver-v2 clone first).

    The session is one entry - id `"session"` unless overridden, which several clients sharing
    one section should do - shaped `{"data": {"accessToken": ..., "refreshToken": ...}}`, the
    camelCase document convention the driver ecosystem uses throughout.

    Choose the backing store deliberately: a refresh token is a credential, and unlike
    `FileTokenStore`'s 0600-restricted file, the entry's protection here is exactly whatever the
    chosen backend provides.
    """

    def __init__(self, section, entry_id: str = "session") -> None:  # noqa: ANN001 - driver types are an optional extra
        try:
            import database_driver.api  # noqa: F401
        except ImportError as exc:  # pragma: no cover - exercised only without the extra installed
            raise ImportError(
                "DatabaseTokenStore requires the 'driver' extra: pip install cloud-driver-client[driver] "
                "(the lino-database-driver packages are not on an index yet - install them from the "
                "database-driver-v2 clone first)"
            ) from exc
        self._section = section
        self._entry_id = entry_id

    def load(self) -> tuple[str, str] | None:
        entry = self._section.find_entry_by_id(self._entry_id)
        if entry is None:
            return None
        data = entry.get_meta_data()
        if data is None:
            return None
        access_token = data.get("accessToken")
        refresh_token = data.get("refreshToken")
        if not access_token or not refresh_token:
            return None
        return access_token, refresh_token

    def save(self, access_token: str, refresh_token: str) -> None:
        from database_driver.api import DatabaseEntry, JsonDocument

        entry = DatabaseEntry(
            self._entry_id,
            JsonDocument("data", {"accessToken": access_token, "refreshToken": refresh_token}),
        )
        # The driver's sections split insert/update rather than exposing one upsert; the check
        # is not atomic, but a single client owns its session entry, so no two writers race it.
        if self._section.exists(self._entry_id):
            self._section.update(entry)
        else:
            self._section.insert(entry)

    def clear(self) -> None:
        # delete() raises on a missing entry (the driver's NoSuchEntryFound contract); clearing
        # an already-empty session must stay a no-op like every other TokenStore's clear.
        if self._section.exists(self._entry_id):
            self._section.delete(self._entry_id)

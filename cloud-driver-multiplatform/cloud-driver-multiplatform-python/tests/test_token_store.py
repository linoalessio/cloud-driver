"""Tests for the token stores - previously the SDK's one untested module.

`FileTokenStore` is pinned down to the on-disk contract callers already depend on (shape,
permissions, corruption fallback), and `DatabaseTokenStore` is exercised twice: against a
minimal stub section (pinning exactly which section methods it may touch) and, when the driver
packages are installed, against a real database-driver JSON store across a provider restart.
`KeyringTokenStore` stays untested here - it is a thin shim over the `keyring` package, and a
test would only exercise a mock of that package.
"""

from __future__ import annotations

import json
import os
import stat

import pytest

from cloud_driver_client import DatabaseTokenStore, FileTokenStore, InMemoryTokenStore

# ------------------------------------------------------------------ InMemoryTokenStore


def test_in_memory_store_round_trips_and_clears() -> None:
    store = InMemoryTokenStore()
    assert store.load() is None

    store.save("access-1", "refresh-1")
    assert store.load() == ("access-1", "refresh-1")

    store.clear()
    assert store.load() is None


# -------------------------------------------------------------------- FileTokenStore


def test_file_store_round_trips_and_clears(tmp_path) -> None:
    store = FileTokenStore(tmp_path / "session.token")
    assert store.load() is None

    store.save("access-1", "refresh-1")
    assert store.load() == ("access-1", "refresh-1")

    store.clear()
    assert store.load() is None
    # clear() of an already-cleared store stays a no-op, like every other TokenStore.
    store.clear()


def test_file_store_creates_missing_parent_directories(tmp_path) -> None:
    store = FileTokenStore(tmp_path / "deep" / "nested" / "session.token")
    store.save("access-1", "refresh-1")
    assert store.load() == ("access-1", "refresh-1")


def test_file_store_keeps_the_documented_on_disk_shape(tmp_path) -> None:
    """The flat snake_case object is the shape existing deployments' token files carry - a
    change here silently logs every one of them out."""
    path = tmp_path / "session.token"
    FileTokenStore(path).save("access-1", "refresh-1")
    assert json.loads(path.read_text("utf-8")) == {"access_token": "access-1", "refresh_token": "refresh-1"}


@pytest.mark.skipif(os.name != "posix", reason="POSIX permission bits")
def test_file_store_creates_the_file_owner_readable_only(tmp_path) -> None:
    path = tmp_path / "session.token"
    FileTokenStore(path).save("access-1", "refresh-1")
    assert stat.S_IMODE(path.stat().st_mode) == stat.S_IRUSR | stat.S_IWUSR


@pytest.mark.skipif(os.name != "posix", reason="POSIX permission bits")
def test_file_store_tightens_a_preexisting_overpermissive_file(tmp_path) -> None:
    """os.open's mode argument only applies on creation - saving over a file restored at 0644
    must re-tighten it, or the class docstring's never-world-readable promise stops holding."""
    path = tmp_path / "session.token"
    path.write_text("{}")
    path.chmod(0o644)

    FileTokenStore(path).save("access-1", "refresh-1")
    assert stat.S_IMODE(path.stat().st_mode) == stat.S_IRUSR | stat.S_IWUSR


@pytest.mark.parametrize(
    "content",
    [
        "not json at all",
        "[1, 2, 3]",  # well-formed JSON, but not an object
        '"just a string"',
        '{"access_token": "only-half-a-session"}',
        '{"access_token": "", "refresh_token": ""}',
    ],
)
def test_file_store_treats_unusable_content_as_no_session(tmp_path, content) -> None:
    """load() runs eagerly inside CloudDriverClient.__init__, so every unusable-file shape must
    read as "no session", never raise out of client construction."""
    path = tmp_path / "session.token"
    path.write_text(content)
    assert FileTokenStore(path).load() is None


# ---------------------------------------------------------------- DatabaseTokenStore


class _RecordingSection:
    """The minimal `DatabaseSection` surface DatabaseTokenStore is allowed to touch, over a
    plain dict - a method used beyond these five is a contract change this stub surfaces."""

    def __init__(self) -> None:
        self.entries = {}

    def find_entry_by_id(self, id):
        return self.entries.get(id)

    def exists(self, id):
        return id in self.entries

    def insert(self, entry):
        assert entry.id not in self.entries
        self.entries[entry.id] = entry

    def update(self, entry):
        assert entry.id in self.entries
        self.entries[entry.id] = entry

    def delete(self, id):
        del self.entries[id]


def test_database_store_round_trips_through_a_section() -> None:
    pytest.importorskip("database_driver.api")
    section = _RecordingSection()
    store = DatabaseTokenStore(section)
    assert store.load() is None

    store.save("access-1", "refresh-1")
    assert store.load() == ("access-1", "refresh-1")

    # A second save must update the one entry, not accumulate a second.
    store.save("access-2", "refresh-2")
    assert store.load() == ("access-2", "refresh-2")
    assert list(section.entries) == ["session"]

    store.clear()
    assert store.load() is None
    store.clear()  # idempotent, like every other TokenStore


def test_database_store_writes_the_data_enveloped_camel_case_document() -> None:
    pytest.importorskip("database_driver.api")
    section = _RecordingSection()
    DatabaseTokenStore(section, entry_id="worker-7").save("access-1", "refresh-1")

    entry = section.entries["worker-7"]
    assert entry.document.as_map() == {"data": {"accessToken": "access-1", "refreshToken": "refresh-1"}}


def test_database_store_persists_across_a_provider_restart(tmp_path) -> None:
    """The actual point of the store: against a real driver backend (the JSON file store), a
    session written before a process restart is loadable after it."""
    pytest.importorskip("database_driver.plugin")
    from database_driver.api import Credentials
    from database_driver.plugin import DefaultFileProvider
    from database_driver.plugin.database.nosql.json.json_database_provider import JsonDatabaseProvider

    # The file stores resolve their filesystem operations through the FileProvider singleton,
    # normally installed as a side effect of constructing the application's repository registry.
    DefaultFileProvider()

    credentials = Credentials(tmp_path / "credentials.json", file_repository=tmp_path / "state")

    first = JsonDatabaseProvider(credentials)
    DatabaseTokenStore(first.create_section("sessions")).save("access-1", "refresh-1")

    reopened = JsonDatabaseProvider(credentials)
    assert DatabaseTokenStore(reopened.create_section("sessions")).load() == ("access-1", "refresh-1")

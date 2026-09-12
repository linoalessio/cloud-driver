"""Tests for embedding-model versioning (``architecture/4. INTELLIGENCE MODEL VERSIONING.md``).

Every stored vector records the model id that produced it; reads only ever compare vectors from
the *currently configured* model, and ``/health`` reports how many stored vectors are stale.
Legacy rows (written before the ``model_id`` column existed) follow Option A - assumed to belong
to the currently configured model - signed off by Lino, 2026-09-12.
"""

from __future__ import annotations

import base64
import json
import sqlite3

import pytest
from fastapi.testclient import TestClient

from cloud_driver_intelligence import app as app_module
from cloud_driver_intelligence.config import settings
from cloud_driver_intelligence.store import KIND_IMAGE, KIND_TEXT, InMemoryVectorStore, SqliteVectorStore

SECRET = "test-secret"
AUTH = {"X-Internal-Secret": SECRET}


class _SwappableModel:
    """Stub model whose ``model_id`` a test reassigns mid-flight to simulate a config change."""

    available = True
    model_id = "model-a"

    def encode(self, texts):
        return [[1.0, 0.0, 0.0] for _ in texts]


@pytest.fixture(autouse=True)
def _isolated_service(monkeypatch):
    monkeypatch.setattr(settings, "shared_secret", SECRET)
    monkeypatch.setattr(app_module, "store", InMemoryVectorStore())
    monkeypatch.setattr(app_module, "embedding_model", _SwappableModel())
    monkeypatch.setattr(app_module, "clip_model", None)
    yield


@pytest.fixture()
def client():
    return TestClient(app_module.app)


def _index(client, file_id: str, text: str) -> None:
    response = client.post(
        "/index",
        headers=AUTH,
        json={
            "fileId": file_id,
            "ownerUserId": "user-1",
            "fileName": f"{file_id}.txt",
            "contentType": "text/plain",
            "contentBase64": base64.b64encode(text.encode()).decode(),
        },
    )
    assert response.status_code == 204


def _search(client, query: str, candidates: list[str]):
    return client.post(
        "/search", headers=AUTH, json={"queryText": query, "candidateFileIds": candidates}
    ).json()


def test_a_vector_from_a_previous_model_stops_surfacing(client):
    """The correctness bug this feature removes: cross-model comparison must never happen."""
    _index(client, "file-old", "anything")
    assert _search(client, "anything", ["file-old"]) != []

    app_module.embedding_model.model_id = "model-b"
    assert _search(client, "anything", ["file-old"]) == []


def test_reindexing_under_the_new_model_restores_the_file(client):
    _index(client, "file-1", "anything")
    app_module.embedding_model.model_id = "model-b"
    _index(client, "file-1", "anything")
    assert [hit["fileId"] for hit in _search(client, "anything", ["file-1"])] == ["file-1"]


def test_health_reports_stale_vectors_after_a_model_change(client):
    _index(client, "file-1", "anything")
    assert client.get("/health").json()["staleTextVectors"] == 0

    app_module.embedding_model.model_id = "model-b"
    health = client.get("/health").json()
    assert health["staleTextVectors"] == 1
    assert health["staleImageVectors"] == 0


class _JsonCipher:
    """Trivial stand-in for :class:`~cloud_driver_intelligence.crypto.VectorCipher`."""

    @staticmethod
    def encrypt(vector: list[float]) -> bytes:
        return json.dumps(vector).encode()

    @staticmethod
    def decrypt(blob: bytes) -> list[float]:
        return json.loads(blob.decode())


def test_sqlite_migration_backfills_legacy_rows_with_the_current_model(tmp_path, monkeypatch):
    """A store created before ``model_id`` existed migrates on open, Option-A-backfilled."""
    monkeypatch.setattr(settings, "store_path", str(tmp_path))
    monkeypatch.setattr(settings, "embedding_model", "configured-text-model")
    monkeypatch.setattr(settings, "clip_model", "configured-clip-model")

    # Hand-construct the pre-model_id schema with one legacy row per modality.
    database_path = tmp_path / "vectors.sqlite3"
    connection = sqlite3.connect(str(database_path))
    connection.execute(
        """
        CREATE TABLE vectors (
            entry_key     TEXT PRIMARY KEY,
            file_id       TEXT NOT NULL,
            kind          TEXT NOT NULL,
            owner_user_id TEXT NOT NULL,
            vector        BLOB NOT NULL
        )
        """
    )
    cipher = _JsonCipher()
    connection.execute(
        "INSERT INTO vectors VALUES (?, ?, ?, ?, ?)",
        (f"{KIND_TEXT}:legacy-file", "legacy-file", KIND_TEXT, "user-1", cipher.encrypt([1.0, 0.0])),
    )
    connection.execute(
        "INSERT INTO vectors VALUES (?, ?, ?, ?, ?)",
        (f"{KIND_IMAGE}:legacy-file", "legacy-file", KIND_IMAGE, "user-1", cipher.encrypt([0.0, 1.0])),
    )
    connection.commit()
    connection.close()

    store = SqliteVectorStore.try_create(cipher)
    assert store is not None

    # The column exists and each legacy row landed on its modality's configured model.
    assert store.vectors_for(["legacy-file"], "configured-text-model", KIND_TEXT) == {"legacy-file": [1.0, 0.0]}
    assert store.vectors_for(["legacy-file"], "configured-clip-model", KIND_IMAGE) == {"legacy-file": [0.0, 1.0]}
    assert store.count_stale("configured-text-model", KIND_TEXT) == 0

    # Under any *other* model id, the legacy row is invisible and counted stale.
    assert store.vectors_for(["legacy-file"], "some-new-model", KIND_TEXT) == {}
    assert store.count_stale("some-new-model", KIND_TEXT) == 1


def test_sqlite_migration_is_idempotent(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "store_path", str(tmp_path))
    cipher = _JsonCipher()

    first = SqliteVectorStore.try_create(cipher)
    assert first is not None
    first.upsert("file-1", "user-1", [1.0], "model-x", KIND_TEXT)

    # Re-opening (a process restart) must not disturb an already-stamped row.
    second = SqliteVectorStore.try_create(cipher)
    assert second is not None
    assert second.vectors_for(["file-1"], "model-x", KIND_TEXT) == {"file-1": [1.0]}
    assert second.count_stale("model-x", KIND_TEXT) == 0

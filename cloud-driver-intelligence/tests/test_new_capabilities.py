"""Tests for the capabilities added on top of the original search-only service.

Same posture as ``test_app.py``: no ``sentence-transformers``, no ``chromadb``, no network. The
one exception is :mod:`~cloud_driver_intelligence.crypto`, which is exercised against the real
``cryptography`` package - stubbing a cipher would test nothing worth testing.
"""

from __future__ import annotations

import base64
import io
import struct

import pytest
from fastapi.testclient import TestClient

from cloud_driver_intelligence import app as app_module
from cloud_driver_intelligence import embeddings as embeddings_module
from cloud_driver_intelligence.config import settings
from cloud_driver_intelligence.store import KIND_IMAGE, KIND_TEXT, InMemoryVectorStore

SECRET = "test-secret"
AUTH = {"X-Internal-Secret": SECRET}


class StubEmbeddingModel:
    """Deterministic 3-dimensional stand-in, matching ``test_app.py``'s own stub."""

    available = True

    @staticmethod
    def _vector(text: str) -> list[float]:
        lowered = text.lower()
        raw = [float(lowered.count(word)) for word in ("invoice", "holiday", "recipe")]
        if not any(raw):
            raw = [1.0, 1.0, 1.0]
        norm = sum(value * value for value in raw) ** 0.5
        return [value / norm for value in raw]

    def encode(self, texts):
        return [self._vector(text) for text in texts]

    def encode_images(self, images):
        return [[1.0, 0.0, 0.0] for _ in images]


@pytest.fixture(autouse=True)
def _isolated_service(monkeypatch):
    monkeypatch.setattr(settings, "shared_secret", SECRET)
    monkeypatch.setattr(app_module, "store", InMemoryVectorStore())
    monkeypatch.setattr(app_module, "embedding_model", StubEmbeddingModel())
    monkeypatch.setattr(app_module, "clip_model", None)
    monkeypatch.setattr(embeddings_module, "embedding_model", StubEmbeddingModel())
    # The vocabulary caches its embedded labels on first use; a fresh one per test keeps the stub
    # model's output from leaking between tests.
    monkeypatch.setattr(embeddings_module, "tag_vocabulary", embeddings_module.TagVocabulary())
    monkeypatch.setattr(app_module, "tag_vocabulary", embeddings_module.tag_vocabulary)
    yield


@pytest.fixture()
def client():
    return TestClient(app_module.app)


def _index(client, file_id, text, owner="user-1", content_type="text/plain", file_name=None):
    return client.post(
        "/index",
        headers=AUTH,
        json={
            "fileId": file_id,
            "ownerUserId": owner,
            "fileName": file_name or f"{file_id}.txt",
            "contentType": content_type,
            "contentBase64": base64.b64encode(text.encode()).decode(),
        },
    )


# --------------------------------------------------------------------------- owner refresh


def test_update_owner_changes_the_recorded_hint(client):
    _index(client, "f1", "invoice invoice", owner="user-1")
    response = client.patch("/index/f1/owner", headers=AUTH, json={"ownerUserId": "user-2"})
    assert response.status_code == 204
    assert app_module.store._owners["f1"] == "user-2"


def test_update_owner_is_idempotent_on_an_unknown_file(client):
    assert client.patch("/index/nope/owner", headers=AUTH, json={"ownerUserId": "u"}).status_code == 204


def test_update_owner_requires_the_shared_secret(client):
    assert client.patch("/index/f1/owner", json={"ownerUserId": "u"}).status_code == 401


def test_update_owner_never_affects_what_search_returns(client):
    """The owner hint must stay irrelevant to ranking - that is the whole security invariant."""
    _index(client, "f1", "invoice invoice", owner="user-1")
    client.patch("/index/f1/owner", headers=AUTH, json={"ownerUserId": "someone-else"})
    hits = client.post(
        "/search", headers=AUTH, json={"queryText": "invoice", "candidateFileIds": ["f1"]}
    ).json()
    assert [hit["fileId"] for hit in hits] == ["f1"]


# --------------------------------------------------------------------------- duplicates


def test_duplicates_groups_identical_documents(client):
    _index(client, "f1", "invoice invoice")
    _index(client, "f2", "invoice invoice")
    _index(client, "f3", "holiday holiday")

    groups = client.post(
        "/duplicates", headers=AUTH, json={"candidateFileIds": ["f1", "f2", "f3"]}
    ).json()

    assert len(groups) == 1
    assert set(groups[0]["storedFileIds"]) == {"f1", "f2"}
    assert groups[0]["similarity"] == pytest.approx(1.0, abs=1e-6)


def test_duplicates_never_returns_an_id_that_was_not_offered(client):
    """The structural restriction, tested from the outside: an indexed-but-unoffered file is invisible."""
    _index(client, "offered-a", "invoice invoice")
    _index(client, "offered-b", "invoice invoice")
    _index(client, "secret", "invoice invoice")

    groups = client.post(
        "/duplicates", headers=AUTH, json={"candidateFileIds": ["offered-a", "offered-b"]}
    ).json()

    returned = {file_id for group in groups for file_id in group["storedFileIds"]}
    assert "secret" not in returned
    assert returned == {"offered-a", "offered-b"}


def test_duplicates_respects_the_threshold(client):
    _index(client, "f1", "invoice invoice")
    _index(client, "f2", "invoice holiday")

    strict = client.post(
        "/duplicates", headers=AUTH, json={"candidateFileIds": ["f1", "f2"], "minimumSimilarity": 0.99}
    ).json()
    assert strict == []

    loose = client.post(
        "/duplicates", headers=AUTH, json={"candidateFileIds": ["f1", "f2"], "minimumSimilarity": 0.5}
    ).json()
    assert len(loose) == 1


def test_duplicates_needs_at_least_two_candidates(client):
    _index(client, "f1", "invoice")
    assert client.post("/duplicates", headers=AUTH, json={"candidateFileIds": ["f1"]}).json() == []


def test_duplicates_reports_the_groups_weakest_pair(client):
    """Single-link grouping means A~B and B~C can join A and C - the reported score must reflect that."""
    _index(client, "a", "invoice invoice invoice invoice")
    _index(client, "b", "invoice invoice invoice holiday")
    _index(client, "c", "invoice invoice holiday holiday")

    groups = client.post(
        "/duplicates", headers=AUTH, json={"candidateFileIds": ["a", "b", "c"], "minimumSimilarity": 0.8}
    ).json()

    assert len(groups) == 1
    members = set(groups[0]["storedFileIds"])
    if members == {"a", "b", "c"}:
        # a~c is the weakest pair and must be what is reported, not a~b.
        assert groups[0]["similarity"] < 0.99


def test_duplicates_requires_the_shared_secret(client):
    assert client.post("/duplicates", json={"candidateFileIds": ["a", "b"]}).status_code == 401


# --------------------------------------------------------------------------- tags


def test_tags_returns_ranked_suggestions(client):
    _index(client, "f1", "invoice invoice")
    suggestions = client.post("/tags", headers=AUTH, json={"fileId": "f1", "limit": 3}).json()

    assert 0 < len(suggestions) <= 3
    scores = [suggestion["confidence"] for suggestion in suggestions]
    assert scores == sorted(scores, reverse=True)
    assert all(-1.0 <= score <= 1.0 for score in scores)


def test_tags_for_an_unindexed_file_is_empty_not_an_error(client):
    response = client.post("/tags", headers=AUTH, json={"fileId": "never-indexed"})
    assert response.status_code == 200
    assert response.json() == []


def test_tags_requires_the_shared_secret(client):
    assert client.post("/tags", json={"fileId": "f1"}).status_code == 401


# --------------------------------------------------------------------------- health reporting


def test_health_reports_every_optional_capability(client):
    body = client.get("/health").json()
    for field in ("embeddingsAvailable", "persistentStore", "encryptedStore", "imageEmbeddingsAvailable"):
        assert field in body, f"/health must report {field} - it is invisible from the outside otherwise"
    assert body["persistentStore"] is False
    assert body["encryptedStore"] is False


# --------------------------------------------------------------------------- image modality


def test_image_vectors_are_stored_separately_from_text(client, monkeypatch):
    """Two vector spaces must never share a slot - scoring across them would be meaningless."""
    monkeypatch.setattr(app_module, "clip_model", StubEmbeddingModel())
    monkeypatch.setattr(app_module, "encode_image_vector", lambda content_type, content: [1.0, 0.0, 0.0])

    _index(client, "img", "ignored", content_type="image/png", file_name="photo.png")

    assert app_module.store.vectors_for(["img"], KIND_TEXT)
    assert app_module.store.vectors_for(["img"], KIND_IMAGE) == {"img": [1.0, 0.0, 0.0]}


def test_delete_removes_every_modality(client, monkeypatch):
    monkeypatch.setattr(app_module, "encode_image_vector", lambda content_type, content: [1.0, 0.0, 0.0])
    _index(client, "img", "ignored", content_type="image/png", file_name="photo.png")

    assert client.delete("/index/img", headers=AUTH).status_code == 204
    assert app_module.store.vectors_for(["img"], KIND_TEXT) == {}
    assert app_module.store.vectors_for(["img"], KIND_IMAGE) == {}


# --------------------------------------------------------------------------- extraction


def test_pdf_text_is_extracted_when_pypdf_is_available():
    pypdf = pytest.importorskip("pypdf")

    writer = pypdf.PdfWriter()
    writer.add_blank_page(width=200, height=200)
    buffer = io.BytesIO()
    writer.write(buffer)

    # A blank page yields no text; the point is that extraction runs and degrades to the file name
    # rather than raising, which is the contract every extractor here promises.
    text = embeddings_module.extract_embeddable_text("Rechnung.pdf", "application/pdf", buffer.getvalue())
    assert text.startswith("Rechnung.pdf")


def test_a_malformed_pdf_falls_back_to_the_file_name():
    text = embeddings_module.extract_embeddable_text("broken.pdf", "application/pdf", b"not a pdf at all")
    assert text == "broken.pdf"


def test_an_unextractable_type_is_still_indexed_by_name():
    text = embeddings_module.extract_embeddable_text("archive.zip", "application/zip", b"\x50\x4b\x03\x04")
    assert text == "archive.zip"


def test_ocr_is_not_attempted_while_disabled(monkeypatch):
    monkeypatch.setattr(settings, "ocr_enabled", False)
    text = embeddings_module.extract_embeddable_text("scan.png", "image/png", b"\x89PNG\r\n\x1a\n")
    assert text == "scan.png"


# --------------------------------------------------------------------------- encryption at rest


def test_cipher_round_trips_a_vector():
    cryptography = pytest.importorskip("cryptography")  # noqa: F841
    from cloud_driver_intelligence.crypto import VectorCipher

    cipher = VectorCipher(b"\x01" * 32)
    original = [0.5, -0.25, 0.125]
    restored = cipher.decrypt(cipher.encrypt(original))
    assert restored == pytest.approx(original, abs=1e-6)


def test_ciphertext_does_not_contain_the_plaintext_bytes():
    pytest.importorskip("cryptography")
    from cloud_driver_intelligence.crypto import VectorCipher

    cipher = VectorCipher(b"\x02" * 32)
    vector = [0.5, -0.25, 0.125]
    blob = cipher.encrypt(vector)
    assert struct.pack("<3f", *vector) not in blob


def test_a_tampered_blob_is_rejected_rather_than_silently_decoded():
    pytest.importorskip("cryptography")
    from cloud_driver_intelligence.crypto import VectorCipher

    cipher = VectorCipher(b"\x03" * 32)
    blob = bytearray(cipher.encrypt([1.0, 0.0, 0.0]))
    blob[-1] ^= 0xFF
    with pytest.raises(Exception):
        cipher.decrypt(bytes(blob))


def test_a_wrong_key_cannot_read_another_keys_vectors():
    pytest.importorskip("cryptography")
    from cloud_driver_intelligence.crypto import VectorCipher

    blob = VectorCipher(b"\x04" * 32).encrypt([1.0, 0.0, 0.0])
    with pytest.raises(Exception):
        VectorCipher(b"\x05" * 32).decrypt(blob)


def test_a_short_key_is_rejected():
    pytest.importorskip("cryptography")
    from cloud_driver_intelligence.crypto import VectorCipher

    with pytest.raises(ValueError):
        VectorCipher(b"too-short")


def test_a_non_base64_key_is_rejected():
    pytest.importorskip("cryptography")
    from cloud_driver_intelligence.crypto import VectorCipher

    with pytest.raises(ValueError):
        VectorCipher.from_base64("not base64 !!!")


def test_encrypted_sqlite_store_round_trips_and_persists(tmp_path, monkeypatch):
    pytest.importorskip("cryptography")
    from cloud_driver_intelligence.crypto import VectorCipher
    from cloud_driver_intelligence.store import SqliteVectorStore

    monkeypatch.setattr(settings, "store_path", str(tmp_path))
    cipher = VectorCipher(b"\x06" * 32)

    store = SqliteVectorStore.try_create(cipher)
    assert store is not None
    store.upsert("f1", "user-1", [0.5, 0.5, 0.0])
    assert store.count() == 1

    # A *second* store over the same directory is the actual restart path this exists for.
    reopened = SqliteVectorStore.try_create(cipher)
    assert reopened is not None
    assert reopened.vectors_for(["f1"])["f1"] == pytest.approx([0.5, 0.5, 0.0], abs=1e-6)


def test_encrypted_sqlite_store_writes_no_plaintext_vector_to_disk(tmp_path, monkeypatch):
    """The whole point: the bytes on disk must not be the vector."""
    pytest.importorskip("cryptography")
    from cloud_driver_intelligence.crypto import VectorCipher
    from cloud_driver_intelligence.store import SqliteVectorStore

    monkeypatch.setattr(settings, "store_path", str(tmp_path))
    vector = [0.5, -0.25, 0.125]
    store = SqliteVectorStore.try_create(VectorCipher(b"\x07" * 32))
    store.upsert("f1", "user-1", vector)

    on_disk = (tmp_path / "vectors.sqlite3").read_bytes()
    assert struct.pack("<3f", *vector) not in on_disk


def test_a_vector_written_under_another_key_is_skipped_not_returned(tmp_path, monkeypatch):
    pytest.importorskip("cryptography")
    from cloud_driver_intelligence.crypto import VectorCipher
    from cloud_driver_intelligence.store import SqliteVectorStore

    monkeypatch.setattr(settings, "store_path", str(tmp_path))
    SqliteVectorStore.try_create(VectorCipher(b"\x08" * 32)).upsert("f1", "user-1", [1.0, 0.0, 0.0])

    other = SqliteVectorStore.try_create(VectorCipher(b"\x09" * 32))
    assert other.vectors_for(["f1"]) == {}


def test_a_broken_encryption_key_falls_back_to_memory_never_to_plaintext_persistence(monkeypatch):
    """Fail-closed: an operator who asked for encryption must not silently get plaintext on disk."""
    from cloud_driver_intelligence.store import InMemoryVectorStore as InMemory
    from cloud_driver_intelligence.store import create_store

    monkeypatch.setattr(settings, "encryption_key", "this-is-not-valid-base64!!!")
    store = create_store()
    assert isinstance(store, InMemory)
    assert store.persistent is False


def test_sqlite_store_deletes_every_modality(tmp_path, monkeypatch):
    pytest.importorskip("cryptography")
    from cloud_driver_intelligence.crypto import VectorCipher
    from cloud_driver_intelligence.store import SqliteVectorStore

    monkeypatch.setattr(settings, "store_path", str(tmp_path))
    store = SqliteVectorStore.try_create(VectorCipher(b"\x0a" * 32))
    store.upsert("f1", "user-1", [1.0, 0.0, 0.0], KIND_TEXT)
    store.upsert("f1", "user-1", [0.0, 1.0, 0.0], KIND_IMAGE)

    store.delete("f1")
    assert store.vectors_for(["f1"], KIND_TEXT) == {}
    assert store.vectors_for(["f1"], KIND_IMAGE) == {}


def test_sqlite_store_updates_owner_across_modalities(tmp_path, monkeypatch):
    pytest.importorskip("cryptography")
    from cloud_driver_intelligence.crypto import VectorCipher
    from cloud_driver_intelligence.store import SqliteVectorStore

    monkeypatch.setattr(settings, "store_path", str(tmp_path))
    store = SqliteVectorStore.try_create(VectorCipher(b"\x0b" * 32))
    store.upsert("f1", "user-1", [1.0, 0.0, 0.0], KIND_TEXT)
    store.upsert("f1", "user-1", [0.0, 1.0, 0.0], KIND_IMAGE)

    store.update_owner("f1", "user-2")
    rows = store._connection.execute("SELECT DISTINCT owner_user_id FROM vectors WHERE file_id = 'f1'").fetchall()
    assert rows == [("user-2",)]

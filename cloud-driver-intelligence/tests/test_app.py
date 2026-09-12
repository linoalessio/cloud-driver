"""Tests for the FastAPI service.

Runs entirely without ``sentence-transformers``, ``chromadb`` or a network: a deterministic stub
embedding model is installed in place of the real one, so these exercise the service's own logic
(auth, decoding, the candidate restriction, ranking, idempotency) rather than a model's output.
"""

from __future__ import annotations

import base64

import pytest
from fastapi.testclient import TestClient

from cloud_driver_intelligence import app as app_module
from cloud_driver_intelligence.config import settings
from cloud_driver_intelligence.store import InMemoryVectorStore

SECRET = "test-secret"
AUTH = {"X-Internal-Secret": SECRET}


class StubEmbeddingModel:
    """Deterministic, dependency-free stand-in for the real model.

    Maps text onto a 3-dimensional unit vector by counting occurrences of three marker words, so a
    test can state "this document is about invoices" and "this query is about invoices" and get a
    predictable similarity, with no model download involved.
    """

    available = True

    #: Plain attribute (the real model exposes a property) so a test can reassign it to simulate
    #: an operator changing the configured model between indexing and querying.
    model_id = "stub-model-v1"

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


@pytest.fixture(autouse=True)
def _isolated_service(monkeypatch):
    """Fresh store + stub model + known secret for every test."""
    monkeypatch.setattr(settings, "shared_secret", SECRET)
    monkeypatch.setattr(app_module, "store", InMemoryVectorStore())
    monkeypatch.setattr(app_module, "embedding_model", StubEmbeddingModel())
    yield


@pytest.fixture()
def client():
    return TestClient(app_module.app)


def _index(client, file_id, file_name, text, owner="user-1"):
    return client.post(
        "/index",
        headers=AUTH,
        json={
            "fileId": file_id,
            "ownerUserId": owner,
            "fileName": file_name,
            "contentType": "text/plain",
            "contentBase64": base64.b64encode(text.encode()).decode(),
        },
    )


# --------------------------------------------------------------------------- auth


def test_health_needs_no_secret(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


@pytest.mark.parametrize(
    ("method", "path", "body"),
    [
        ("post", "/index", {"fileId": "f", "ownerUserId": "u", "fileName": "n", "contentType": "text/plain"}),
        ("post", "/search", {"queryText": "x", "candidateFileIds": ["f"]}),
        ("delete", "/index/f", None),
    ],
)
def test_every_other_endpoint_requires_the_secret(client, method, path, body):
    response = getattr(client, method)(path, json=body) if body else getattr(client, method)(path)
    assert response.status_code == 401


def test_wrong_secret_is_rejected(client):
    response = client.post("/search", headers={"X-Internal-Secret": "wrong"},
                           json={"queryText": "x", "candidateFileIds": ["f"]})
    assert response.status_code == 401


def test_unconfigured_secret_fails_closed(client, monkeypatch):
    """A service with no secret configured must reject everything, not accept everything."""
    monkeypatch.setattr(settings, "shared_secret", None)
    response = client.post("/search", headers=AUTH, json={"queryText": "x", "candidateFileIds": ["f"]})
    assert response.status_code == 503


# --------------------------------------------------------------------------- indexing


def test_index_then_search_finds_the_relevant_document(client):
    _index(client, "file-invoice", "invoice.txt", "invoice invoice invoice")
    _index(client, "file-recipe", "recipe.txt", "recipe recipe recipe")

    response = client.post("/search", headers=AUTH, json={
        "queryText": "invoice", "candidateFileIds": ["file-invoice", "file-recipe"], "limit": 10})

    assert response.status_code == 200
    hits = response.json()
    assert hits[0]["fileId"] == "file-invoice"
    assert hits[0]["score"] > hits[1]["score"]


def test_index_is_idempotent_per_file_id(client):
    _index(client, "file-1", "a.txt", "invoice")
    _index(client, "file-1", "a.txt", "invoice")
    assert client.get("/health").json()["indexedDocuments"] == 1


def test_index_accepts_a_file_with_no_content(client):
    """A presigned upload's bytes never reach this server - name-only embedding must still work."""
    response = client.post("/index", headers=AUTH, json={
        "fileId": "file-presigned", "ownerUserId": "user-1",
        "fileName": "invoice.pdf", "contentType": "application/pdf", "contentBase64": None})
    assert response.status_code == 204

    hits = client.post("/search", headers=AUTH, json={
        "queryText": "invoice", "candidateFileIds": ["file-presigned"]}).json()
    assert [hit["fileId"] for hit in hits] == ["file-presigned"]


def test_malformed_base64_is_a_400(client):
    response = client.post("/index", headers=AUTH, json={
        "fileId": "f", "ownerUserId": "u", "fileName": "n",
        "contentType": "text/plain", "contentBase64": "not!base64!"})
    assert response.status_code == 400


def test_oversized_text_is_truncated_not_rejected(client, monkeypatch):
    monkeypatch.setattr(settings, "max_embedded_chars", 10)
    assert _index(client, "file-big", "big.txt", "invoice " * 10_000).status_code == 204


# --------------------------------------------------------------------------- deletion


def test_delete_removes_the_document(client):
    _index(client, "file-1", "a.txt", "invoice")
    assert client.delete("/index/file-1", headers=AUTH).status_code == 204
    assert client.get("/health").json()["indexedDocuments"] == 0


def test_delete_is_idempotent_on_absence(client):
    assert client.delete("/index/never-existed", headers=AUTH).status_code == 204


def test_a_deleted_document_stops_matching(client):
    _index(client, "file-1", "a.txt", "invoice")
    client.delete("/index/file-1", headers=AUTH)
    hits = client.post("/search", headers=AUTH, json={
        "queryText": "invoice", "candidateFileIds": ["file-1"]}).json()
    assert hits == []


# ------------------------------------------------------- the candidate restriction (§6)


def test_search_never_returns_an_id_outside_the_candidate_set(client):
    """The core security property: an indexed document not offered as a candidate is invisible.

    Indexes a document owned by a *different* account and then searches with a candidate set that
    excludes it - exactly the shape of a leak, if the restriction were merely a ranking hint.
    """
    _index(client, "victim-file", "invoice.txt", "invoice invoice", owner="user-victim")
    _index(client, "own-file", "invoice.txt", "invoice invoice", owner="user-1")

    hits = client.post("/search", headers=AUTH, json={
        "queryText": "invoice", "candidateFileIds": ["own-file"], "limit": 50}).json()

    assert [hit["fileId"] for hit in hits] == ["own-file"]


def test_search_ignores_owner_metadata_entirely(client):
    """Ownership recorded at index time must not influence results either way.

    A candidate owned by someone else is still ranked when offered - because this service is not
    the component that decides access, and quietly second-guessing the caller here would mask a
    genuine bug in the caller's own pre-filter rather than prevent one.
    """
    _index(client, "shared-file", "invoice.txt", "invoice", owner="user-other")
    hits = client.post("/search", headers=AUTH, json={
        "queryText": "invoice", "candidateFileIds": ["shared-file"]}).json()
    assert [hit["fileId"] for hit in hits] == ["shared-file"]


def test_empty_candidate_set_returns_nothing(client):
    _index(client, "file-1", "invoice.txt", "invoice")
    hits = client.post("/search", headers=AUTH, json={
        "queryText": "invoice", "candidateFileIds": []}).json()
    assert hits == []


def test_unknown_candidate_ids_are_simply_not_matched(client):
    hits = client.post("/search", headers=AUTH, json={
        "queryText": "invoice", "candidateFileIds": ["never-indexed"]}).json()
    assert hits == []


def test_blank_query_returns_nothing(client):
    _index(client, "file-1", "invoice.txt", "invoice")
    hits = client.post("/search", headers=AUTH, json={
        "queryText": "   ", "candidateFileIds": ["file-1"]}).json()
    assert hits == []


def test_limit_caps_the_result_count(client):
    for index in range(5):
        _index(client, f"file-{index}", "invoice.txt", "invoice")
    hits = client.post("/search", headers=AUTH, json={
        "queryText": "invoice", "candidateFileIds": [f"file-{i}" for i in range(5)], "limit": 2}).json()
    assert len(hits) == 2


# --------------------------------------------------------------------------- degradation


def test_search_returns_nothing_when_no_embedding_backend_is_available(client, monkeypatch):
    class Unavailable:
        available = False
        model_id = "unavailable-model"

        def encode(self, texts):
            return None

    _index(client, "file-1", "invoice.txt", "invoice")
    monkeypatch.setattr(app_module, "embedding_model", Unavailable())

    assert client.post("/search", headers=AUTH, json={
        "queryText": "invoice", "candidateFileIds": ["file-1"]}).json() == []
    assert client.get("/health").json()["embeddingsAvailable"] is False


def test_index_still_succeeds_without_an_embedding_backend(client, monkeypatch):
    """A missing optional extra must not make every upload's index call retry and log SEVERE."""
    class Unavailable:
        available = False
        model_id = "unavailable-model"

        def encode(self, texts):
            return None

    monkeypatch.setattr(app_module, "embedding_model", Unavailable())
    assert _index(client, "file-1", "a.txt", "invoice").status_code == 204

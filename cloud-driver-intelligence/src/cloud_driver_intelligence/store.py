"""The vector store - Chroma when available, an in-memory equivalent otherwise.

Completely separate from ``cloud-driver``'s own Postgres: nothing here ever touches that database,
which is what keeps this service from becoming the "second persistence path" the project rules
forbid. What it *is*, unavoidably, is a second **data store**, holding vectors derived from real
file content, unencrypted at rest - see this module's README for that trade-off in full.
"""

from __future__ import annotations

import logging
import threading
from typing import Protocol

from .config import settings

_LOGGER = logging.getLogger(__name__)

#: Chroma collection name - a constant; this service holds exactly one collection.
_COLLECTION_NAME = "cloud_driver_files"


class VectorStore(Protocol):
    """The narrow surface :mod:`.app` needs, satisfied by both implementations below."""

    persistent: bool

    def upsert(self, file_id: str, owner_user_id: str, vector: list[float]) -> None: ...

    def delete(self, file_id: str) -> None: ...

    def vectors_for(self, file_ids: list[str]) -> dict[str, list[float]]: ...

    def count(self) -> int: ...


class InMemoryVectorStore:
    """Dict-backed fallback - functionally complete, lost on restart.

    The same "fully derived, so losing it is survivable" trade-off ``InMemorySearchIndexService``
    already accepts on the Java side: every vector can be rebuilt by re-uploading or replacing a
    file's content. Adequate for tests and a first local run; a real deployment should install the
    ``store`` extra so Chroma is used instead.
    """

    persistent = False

    def __init__(self) -> None:
        self._vectors: dict[str, list[float]] = {}
        self._owners: dict[str, str] = {}
        self._lock = threading.Lock()

    def upsert(self, file_id: str, owner_user_id: str, vector: list[float]) -> None:
        with self._lock:
            self._vectors[file_id] = vector
            self._owners[file_id] = owner_user_id

    def delete(self, file_id: str) -> None:
        with self._lock:
            self._vectors.pop(file_id, None)
            self._owners.pop(file_id, None)

    def vectors_for(self, file_ids: list[str]) -> dict[str, list[float]]:
        with self._lock:
            return {file_id: self._vectors[file_id] for file_id in file_ids if file_id in self._vectors}

    def count(self) -> int:
        with self._lock:
            return len(self._vectors)


class ChromaVectorStore:
    """Chroma-backed store, persisting to :attr:`~.config.Settings.store_path`.

    Chosen over FAISS or Qdrant for v1 (the choice the handoff document itself leaned toward):
    embedded, so no extra server process to operate, and persistent, unlike the fallback above.

    Note this class only ever *fetches* vectors by id and ranks them here in Python, rather than
    using Chroma's own nearest-neighbour query. That is deliberate: a similarity query filtered
    down to an allowed id set afterwards would be a filter, and this service must never be the
    thing that filters - see :func:`~.app.search`. Fetching exactly the candidate ids and ranking
    only those makes it structurally impossible to return an id that was not offered.
    """

    persistent = True

    def __init__(self, collection) -> None:  # noqa: ANN001 - chromadb types are optional at import time
        self._collection = collection

    @classmethod
    def try_create(cls) -> "ChromaVectorStore | None":
        """Build a Chroma-backed store, or ``None`` if chromadb is unavailable/unusable."""
        try:
            import chromadb  # noqa: PLC0415 - deliberately lazy, this is an optional extra
        except ImportError:
            _LOGGER.warning(
                "chromadb is not installed - falling back to an in-memory vector store, which is "
                "lost on restart. Install this service with the 'store' extra for persistence."
            )
            return None
        try:
            client = chromadb.PersistentClient(path=settings.store_path)
            collection = client.get_or_create_collection(name=_COLLECTION_NAME)
            _LOGGER.info("Using persistent Chroma store at %s", settings.store_path)
            return cls(collection)
        except Exception:  # noqa: BLE001 - degrade to in-memory rather than fail to start
            _LOGGER.exception("Failed to open the Chroma store at %s", settings.store_path)
            return None

    def upsert(self, file_id: str, owner_user_id: str, vector: list[float]) -> None:
        self._collection.upsert(
            ids=[file_id], embeddings=[vector], metadatas=[{"ownerUserId": owner_user_id}]
        )

    def delete(self, file_id: str) -> None:
        self._collection.delete(ids=[file_id])

    def vectors_for(self, file_ids: list[str]) -> dict[str, list[float]]:
        if not file_ids:
            return {}
        result = self._collection.get(ids=file_ids, include=["embeddings"])
        found_ids = result.get("ids") or []
        embeddings = result.get("embeddings")
        if embeddings is None:
            return {}
        return {
            file_id: list(map(float, vector))
            for file_id, vector in zip(found_ids, embeddings)
            if vector is not None
        }

    def count(self) -> int:
        return self._collection.count()


def create_store() -> VectorStore:
    """Chroma if it loads, the in-memory fallback otherwise."""
    return ChromaVectorStore.try_create() or InMemoryVectorStore()

"""The vector store - encrypted SQLite, Chroma, or an in-memory equivalent.

Completely separate from ``cloud-driver``'s own Postgres: nothing here ever touches that database,
which is what keeps this service from becoming the "second persistence path" the project rules
forbid. What it *is*, unavoidably, is a second **data store**, holding vectors derived from real
file content - see :mod:`.crypto` for the at-rest encryption that now covers exactly that.

Three implementations, selected by :func:`create_store` in a strict order of preference:

======================  ==========  ===========  ================================================
Implementation          Persistent  Encrypted    Selected when
======================  ==========  ===========  ================================================
``SqliteVectorStore``   yes         yes          an encryption key is configured
``ChromaVectorStore``   yes         no           no key, and ``chromadb`` is installed
``InMemoryVectorStore`` no          n/a          neither of the above, or either failed to open
======================  ==========  ===========  ================================================

A configured-but-unusable encryption key deliberately falls all the way through to the *in-memory*
store rather than to Chroma: an operator who asked for encryption must never silently get
unencrypted persistence instead. Losing durability is recoverable (the store is fully derived, and
a backfill rebuilds it); writing derived plaintext to disk after being told not to is not.

**Modalities.** A file may hold more than one vector - text (always) and, when CLIP is enabled, an
image vector in a *different* vector space. Those must never be compared with each other, so every
entry is namespaced by a ``kind`` and a query only ever ranks within one namespace at a time.
"""

from __future__ import annotations

import logging
import sqlite3
import threading
from pathlib import Path
from typing import Iterable, Protocol

from .config import settings
from .crypto import VectorCipher, build_cipher

_LOGGER = logging.getLogger(__name__)

#: Chroma collection name - a constant; this service holds exactly one collection.
_COLLECTION_NAME = "cloud_driver_files"

#: The text modality - every indexed file has one of these.
KIND_TEXT = "text"

#: The image modality - only present for image files, and only when CLIP is enabled.
KIND_IMAGE = "image"


def _namespaced(kind: str, file_id: str) -> str:
    """The storage key for one (modality, file) pair.

    Namespacing in the *key* rather than as a side table is what lets all three implementations
    below share one storage shape without any of them growing modality-specific branches.
    """
    return f"{kind}:{file_id}"


class VectorStore(Protocol):
    """The surface :mod:`.app` needs, satisfied by all three implementations below.

    **Every vector records the model that produced it** (``model_id``, added 2026-09-12): reads
    only ever return vectors written under the *same* model id they are queried for, so a vector
    from a previously configured model behaves like "not indexed" rather than being silently
    compared in an incompatible vector space - see this service's model-versioning handoff
    (``architecture/4. INTELLIGENCE MODEL VERSIONING.md``). ``model_id`` is deliberately a
    required, explicit argument on both ``upsert`` and ``vectors_for`` - a caller must always
    decide which model's space it is operating in, the same way ``kind`` is never allowed to
    silently default when it matters.
    """

    persistent: bool
    encrypted: bool

    def upsert(self, file_id: str, owner_user_id: str, vector: list[float], model_id: str, kind: str = KIND_TEXT) -> None: ...

    def delete(self, file_id: str) -> None: ...

    def update_owner(self, file_id: str, owner_user_id: str) -> None: ...

    def vectors_for(self, file_ids: Iterable[str], model_id: str, kind: str = KIND_TEXT) -> dict[str, list[float]]: ...

    def count_stale(self, model_id: str, kind: str = KIND_TEXT) -> int: ...

    def count(self) -> int: ...


class InMemoryVectorStore:
    """Dict-backed fallback - functionally complete, lost on restart.

    The same "fully derived, so losing it is survivable" trade-off ``InMemorySearchIndexService``
    already accepts on the Java side: every vector can be rebuilt by re-uploading a file or by
    running the backfill. Adequate for tests and a first local run.
    """

    persistent = False
    encrypted = False

    def __init__(self) -> None:
        #: ``entry_key -> (vector, model_id)`` - the model id travels with every vector.
        self._vectors: dict[str, tuple[list[float], str]] = {}
        self._owners: dict[str, str] = {}
        self._lock = threading.Lock()

    def upsert(self, file_id: str, owner_user_id: str, vector: list[float], model_id: str, kind: str = KIND_TEXT) -> None:
        with self._lock:
            self._vectors[_namespaced(kind, file_id)] = (vector, model_id)
            self._owners[file_id] = owner_user_id

    def delete(self, file_id: str) -> None:
        with self._lock:
            for kind in (KIND_TEXT, KIND_IMAGE):
                self._vectors.pop(_namespaced(kind, file_id), None)
            self._owners.pop(file_id, None)

    def update_owner(self, file_id: str, owner_user_id: str) -> None:
        with self._lock:
            if any(_namespaced(kind, file_id) in self._vectors for kind in (KIND_TEXT, KIND_IMAGE)):
                self._owners[file_id] = owner_user_id

    def vectors_for(self, file_ids: Iterable[str], model_id: str, kind: str = KIND_TEXT) -> dict[str, list[float]]:
        with self._lock:
            found = {}
            for file_id in file_ids:
                entry = self._vectors.get(_namespaced(kind, file_id))
                if entry is not None and entry[1] == model_id:
                    found[file_id] = entry[0]
            return found

    def count_stale(self, model_id: str, kind: str = KIND_TEXT) -> int:
        with self._lock:
            prefix = f"{kind}:"
            return sum(
                1
                for key, (_, stored_model) in self._vectors.items()
                if key.startswith(prefix) and stored_model != model_id
            )

    def count(self) -> int:
        with self._lock:
            return len(self._owners)


class SqliteVectorStore:
    """Persistent store backed by stdlib ``sqlite3``, with every vector encrypted at rest.

    **Why SQLite rather than encrypting inside Chroma.** This service never issues an
    approximate-nearest-neighbour query - it only ever *fetches vectors by id* and ranks them in
    Python (see :func:`~.app.search` for why that is a security property, not an optimisation).
    A vector database's entire reason for existing is therefore unused here, which leaves a plain
    keyed blob store as the honest fit - and one that can hold ciphertext, which Chroma's
    embedding column cannot, since it would have to index values it is not allowed to read.

    Small enough to be a net simplification: one table, one index, no server, no extra dependency
    beyond ``cryptography``.
    """

    persistent = True
    encrypted = True

    def __init__(self, connection: sqlite3.Connection, cipher: VectorCipher) -> None:
        self._connection = connection
        self._cipher = cipher
        # sqlite3 connections are not safe to share across threads without serialising access, and
        # uvicorn will absolutely call this from several at once.
        self._lock = threading.Lock()

    @classmethod
    def try_create(cls, cipher: VectorCipher) -> "SqliteVectorStore | None":
        """Open (creating if needed) the encrypted store, or ``None`` if it cannot be opened."""
        try:
            directory = Path(settings.store_path)
            directory.mkdir(parents=True, exist_ok=True)
            database_path = directory / "vectors.sqlite3"
            connection = sqlite3.connect(str(database_path), check_same_thread=False)
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS vectors (
                    entry_key     TEXT PRIMARY KEY,
                    file_id       TEXT NOT NULL,
                    kind          TEXT NOT NULL,
                    owner_user_id TEXT NOT NULL,
                    vector        BLOB NOT NULL,
                    model_id      TEXT NOT NULL DEFAULT ''
                )
                """
            )
            connection.execute("CREATE INDEX IF NOT EXISTS vectors_file_id ON vectors (file_id)")
            # Migration for stores created before model_id existed - SQLite has no portable
            # "ADD COLUMN IF NOT EXISTS", so check PRAGMA table_info first. Option A (signed off
            # by Lino, 2026-09-12): legacy rows are backfilled with the *currently configured*
            # model id - exactly right for a deployment that never changed the model, and no
            # worse than today for one that already did. Only rows the migration itself just
            # defaulted to '' are touched, so re-running on a migrated store is a no-op.
            existing_columns = {row[1] for row in connection.execute("PRAGMA table_info(vectors)").fetchall()}
            if "model_id" not in existing_columns:
                connection.execute("ALTER TABLE vectors ADD COLUMN model_id TEXT NOT NULL DEFAULT ''")
            connection.execute(
                "UPDATE vectors SET model_id = ? WHERE kind = ? AND model_id = ''",
                (settings.embedding_model, KIND_TEXT),
            )
            connection.execute(
                "UPDATE vectors SET model_id = ? WHERE kind = ? AND model_id = ''",
                (settings.clip_model, KIND_IMAGE),
            )
            connection.commit()
            _LOGGER.info("Using encrypted SQLite vector store at %s", database_path)
            return cls(connection, cipher)
        except Exception:  # noqa: BLE001 - degrade rather than fail to start
            _LOGGER.exception("Failed to open the encrypted SQLite store at %s", settings.store_path)
            return None

    def upsert(self, file_id: str, owner_user_id: str, vector: list[float], model_id: str, kind: str = KIND_TEXT) -> None:
        blob = self._cipher.encrypt(vector)
        with self._lock:
            self._connection.execute(
                "INSERT INTO vectors (entry_key, file_id, kind, owner_user_id, vector, model_id) "
                "VALUES (?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(entry_key) DO UPDATE SET owner_user_id = excluded.owner_user_id, "
                "vector = excluded.vector, model_id = excluded.model_id",
                (_namespaced(kind, file_id), file_id, kind, owner_user_id, blob, model_id),
            )
            self._connection.commit()

    def delete(self, file_id: str) -> None:
        with self._lock:
            self._connection.execute("DELETE FROM vectors WHERE file_id = ?", (file_id,))
            self._connection.commit()

    def update_owner(self, file_id: str, owner_user_id: str) -> None:
        with self._lock:
            self._connection.execute(
                "UPDATE vectors SET owner_user_id = ? WHERE file_id = ?", (owner_user_id, file_id)
            )
            self._connection.commit()

    def vectors_for(self, file_ids: Iterable[str], model_id: str, kind: str = KIND_TEXT) -> dict[str, list[float]]:
        ids = list(file_ids)
        if not ids:
            return {}
        found: dict[str, list[float]] = {}
        with self._lock:
            # Chunked because SQLite caps a statement's host parameters (999 on older builds), and
            # a caller's candidate set is the size of an entire account's file list.
            for start in range(0, len(ids), 500):
                chunk = ids[start : start + 500]
                placeholders = ",".join("?" * len(chunk))
                rows = self._connection.execute(
                    f"SELECT file_id, vector FROM vectors WHERE kind = ? AND model_id = ? AND file_id IN ({placeholders})",  # noqa: S608 - placeholders only
                    (kind, model_id, *chunk),
                ).fetchall()
                for file_id, blob in rows:
                    try:
                        found[file_id] = self._cipher.decrypt(blob)
                    except Exception:  # noqa: BLE001
                        # A blob written under a different key, or tampered with. Skipping it drops
                        # one file from the ranking; treating it as usable would poison every score
                        # it takes part in.
                        _LOGGER.warning("Undecryptable vector for %s - skipping it", file_id)
        return found

    def count_stale(self, model_id: str, kind: str = KIND_TEXT) -> int:
        with self._lock:
            return int(
                self._connection.execute(
                    "SELECT COUNT(*) FROM vectors WHERE kind = ? AND model_id != ?", (kind, model_id)
                ).fetchone()[0]
            )

    def count(self) -> int:
        with self._lock:
            return int(self._connection.execute("SELECT COUNT(DISTINCT file_id) FROM vectors").fetchone()[0])


class ChromaVectorStore:
    """Chroma-backed store, persisting to :attr:`~.config.Settings.store_path`. **Not encrypted.**

    Retained as the default for deployments that have not opted into encryption, so enabling it
    stays a deliberate choice rather than a silent storage-format migration.

    Note this class only ever *fetches* vectors by id and ranks them in Python, rather than using
    Chroma's own nearest-neighbour query - see :class:`SqliteVectorStore`'s docstring and
    :func:`~.app.search` for why that is load-bearing.
    """

    persistent = True
    encrypted = False

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

    @staticmethod
    def _legacy_default_model_id(kind: str) -> str:
        """The model id a pre-``modelId`` entry is assumed to carry (Option A, signed off).

        Unlike SQLite's one-shot ``UPDATE`` backfill, Chroma has no cheap ALTER-TABLE-style
        migration - rewriting every row just to stamp a metadata key is a far heavier operation -
        so the Option-A assumption is applied *at read time* instead: a missing ``modelId`` key
        is treated as matching the currently configured model for its modality. Observable
        behavior is identical to the SQLite backfill.
        """
        return settings.embedding_model if kind == KIND_TEXT else settings.clip_model

    def upsert(self, file_id: str, owner_user_id: str, vector: list[float], model_id: str, kind: str = KIND_TEXT) -> None:
        self._collection.upsert(
            ids=[_namespaced(kind, file_id)],
            embeddings=[vector],
            metadatas=[{"ownerUserId": owner_user_id, "fileId": file_id, "kind": kind, "modelId": model_id}],
        )

    def delete(self, file_id: str) -> None:
        self._collection.delete(ids=[_namespaced(kind, file_id) for kind in (KIND_TEXT, KIND_IMAGE)])

    def update_owner(self, file_id: str, owner_user_id: str) -> None:
        # Chroma has no partial-metadata update that leaves the embedding alone, so each existing
        # entry is re-upserted with its own vector read back first. Cheap: at most two entries.
        # modelId is read back alongside the embedding and carried forward - re-upserting without
        # it would silently reset every owner-updated entry to "legacy, no recorded model".
        for kind in (KIND_TEXT, KIND_IMAGE):
            entry_key = _namespaced(kind, file_id)
            result = self._collection.get(ids=[entry_key], include=["embeddings", "metadatas"])
            embeddings = result.get("embeddings")
            if not result.get("ids") or embeddings is None or len(embeddings) == 0:
                continue
            metadatas = result.get("metadatas") or [{}]
            model_id = (metadatas[0] or {}).get("modelId", self._legacy_default_model_id(kind))
            self._collection.upsert(
                ids=[entry_key],
                embeddings=[list(map(float, embeddings[0]))],
                metadatas=[{"ownerUserId": owner_user_id, "fileId": file_id, "kind": kind, "modelId": model_id}],
            )

    def vectors_for(self, file_ids: Iterable[str], model_id: str, kind: str = KIND_TEXT) -> dict[str, list[float]]:
        ids = list(file_ids)
        if not ids:
            return {}
        result = self._collection.get(
            ids=[_namespaced(kind, file_id) for file_id in ids], include=["embeddings", "metadatas"]
        )
        found_keys = result.get("ids") or []
        embeddings = result.get("embeddings")
        if embeddings is None:
            return {}
        metadatas = result.get("metadatas") or [{}] * len(found_keys)
        prefix = f"{kind}:"
        return {
            entry_key[len(prefix) :]: list(map(float, vector))
            for entry_key, vector, metadata in zip(found_keys, embeddings, metadatas)
            if vector is not None
            and entry_key.startswith(prefix)
            and (metadata or {}).get("modelId", self._legacy_default_model_id(kind)) == model_id
        }

    def count_stale(self, model_id: str, kind: str = KIND_TEXT) -> int:
        prefix = f"{kind}:"
        result = self._collection.get(where={"kind": kind}, include=["metadatas"])
        return sum(
            1
            for key, metadata in zip(result.get("ids") or [], result.get("metadatas") or [])
            if key.startswith(prefix)
            and (metadata or {}).get("modelId", self._legacy_default_model_id(kind)) != model_id
        )

    def count(self) -> int:
        return self._collection.count()


def create_store() -> VectorStore:
    """The best store this deployment's configuration allows - see this module's own docstring."""
    try:
        cipher = build_cipher(settings.encryption_key)
    except (ValueError, RuntimeError):
        # Fail closed. An operator asked for encryption and it cannot be provided, so persistence
        # is withdrawn rather than silently downgraded to plaintext-on-disk.
        _LOGGER.exception(
            "An encryption key is configured but unusable - refusing to persist vectors "
            "unencrypted. Falling back to an in-memory store; fix the key (or the missing "
            "'encryption' extra) and re-index."
        )
        return InMemoryVectorStore()

    if cipher is not None:
        return SqliteVectorStore.try_create(cipher) or InMemoryVectorStore()
    return ChromaVectorStore.try_create() or InMemoryVectorStore()

"""The vector store - an encrypted database-driver store, Chroma, or an in-memory equivalent.

Completely separate from ``cloud-driver``'s own Postgres: nothing here ever touches that database,
which is what keeps this service from becoming the "second persistence path" the project rules
forbid. What it *is*, unavoidably, is a second **data store**, holding vectors derived from real
file content - see :mod:`.crypto` for the at-rest encryption that now covers exactly that.

Three implementations, selected by :func:`create_store` in a strict order of preference:

=============================  ==========  ===========  =========================================
Implementation                 Persistent  Encrypted    Selected when
=============================  ==========  ===========  =========================================
``DatabaseDriverVectorStore``  yes         yes          an encryption key is configured
``ChromaVectorStore``          yes         no           no key, and ``chromadb`` is installed
``InMemoryVectorStore``        no          n/a          neither of the above, or either failed
=============================  ==========  ===========  =========================================

A configured-but-unusable encryption key deliberately falls all the way through to the *in-memory*
store rather than to Chroma: an operator who asked for encryption must never silently get
unencrypted persistence instead. Losing durability is recoverable (the store is fully derived, and
a backfill rebuilds it); writing derived plaintext to disk after being told not to is not. The
same posture covers a missing storage layer: the encrypted store is built on the
``lino-database-driver-api``/``-plugin`` packages (the ``driver`` extra), and an encryption key
configured while those are not installed also falls through to in-memory rather than persisting
in the clear through some other path.

**Storage layout of the encrypted store.** One database-driver SQLite section named ``vectors``
(the driver's uniform ``id TEXT, data BLOB`` shape - the exact layout the Java backend's own
tables use), one entry per (modality, file) pair, the AES-GCM ciphertext carried base64-encoded
inside the entry's document. A pre-existing hand-rolled ``vectors.sqlite3`` from earlier versions
of this service is migrated into the driver store once, on first open, and then renamed so the
migration never re-runs; the ciphertext moves as-is, so the same key keeps decrypting it.

**Modalities.** A file may hold more than one vector - text (always) and, when CLIP is enabled, an
image vector in a *different* vector space. Those must never be compared with each other, so every
entry is namespaced by a ``kind`` and a query only ever ranks within one namespace at a time.
"""

from __future__ import annotations

import base64
import logging
import sqlite3
import threading
from pathlib import Path
from typing import Iterable, Protocol

from .config import settings
from .crypto import VectorCipher, build_cipher

# The storage layer of the encrypted store. Guarded because the packages are an opt-in extra
# (``driver``) not yet published to any index - a deployment without them must still import this
# module and run on Chroma or in-memory, exactly like every other optional capability here.
try:
    from database_driver.api import DatabaseEntry, JsonDocument

    _DRIVER_IMPORT_ERROR: ImportError | None = None
except ImportError as _missing:  # pragma: no cover - exercised only without the extra
    _DRIVER_IMPORT_ERROR = _missing

_LOGGER = logging.getLogger(__name__)

#: Chroma collection name - a constant; this service holds exactly one collection.
_COLLECTION_NAME = "cloud_driver_files"

#: The database-driver section (i.e. SQLite table) the encrypted store keeps its entries in.
_SECTION_NAME = "vectors"

#: The file name of the hand-rolled SQLite store earlier versions of this service wrote - only
#: ever read (and then renamed) by the one-time migration in ``DatabaseDriverVectorStore``.
_LEGACY_DATABASE_NAME = "vectors.sqlite3"

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

    **Every vector records the model that produced it** (``model_id``): reads
    only ever return vectors written under the *same* model id they are queried for, so a vector
    from a previously configured model behaves like "not indexed" rather than being silently
    compared in an incompatible vector space. ``model_id`` is deliberately a
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


class DatabaseDriverVectorStore:
    """Persistent store backed by the database-driver Python edition's SQLite backend, with
    every vector encrypted at rest.

    **Why the database driver, and why its SQLite backend.** This service never issues an
    approximate-nearest-neighbour query - it only ever *fetches vectors by id* and ranks them in
    Python (see :func:`~.app.search` for why that is a security property, not an optimisation).
    A vector database's entire reason for existing is therefore unused here, which leaves a plain
    keyed document store as the honest fit - exactly the shape ``database-driver`` provides, in
    the same ``id TEXT, data BLOB`` layout the Java backend's own tables use, through the same
    engine the rest of the ecosystem runs on instead of a second hand-rolled SQLite layer here.
    SQLite specifically because this store is embedded and server-less by design: the stdlib
    driver, one file next to the service.

    Entries are held in the driver's ``LAZY`` cache mode: process start stays independent of
    the store's size (the open-time disk probe below deliberately runs on the section's storage
    primitives, so nothing at open forces a load), and the first data access - typically the
    first ``/health`` count, or an ``/index`` write - pays a single full load that every later
    point read is answered from, the same reads the previous implementation answered with one
    SQL query each. What sits in that view is the *stored* document, i.e. ciphertext; a vector
    is only ever decrypted on its way out of :meth:`vectors_for`.

    **Encryption stays this service's job, not the driver's.** The driver persists documents
    as-is; the AES-GCM ciphertext (see :mod:`.crypto`) is carried base64-encoded in the entry's
    document, so nothing readable ever reaches the file regardless of storage layer - and a blob
    written under a different key is skipped on read, exactly as before.

    **One-time legacy migration.** Earlier versions of this service kept a hand-rolled
    ``vectors.sqlite3``. On open, its rows - both the pre-``model_id`` schema and the stamped one
    - are copied into the driver store (a missing model id is backfilled with the currently
    configured model per modality, the same deliberate assumption the old open-time backfill
    made), and the legacy file is renamed to ``vectors.sqlite3.migrated`` so this never re-runs.
    The ciphertext is moved untouched: the configured key keeps decrypting it. A migration
    failure is logged and leaves the legacy file in place for the next boot to retry - it never
    costs the (already opened) driver store.
    """

    persistent = True
    encrypted = True

    def __init__(self, provider, section, cipher: VectorCipher) -> None:  # noqa: ANN001 - driver types are optional at import time
        self._provider = provider
        self._section = section
        self._cipher = cipher
        # The engine claims inserted ids atomically, but this store's upsert is a two-step
        # exists-then-write - serialize writers so two concurrent first-time upserts of the same
        # entry cannot race each other into a DataAlreadyExist, which uvicorn's threading would
        # otherwise make possible. Reads stay lock-free: they are answered from the engine's own
        # view, exactly as the previous implementation's per-statement locking allowed.
        self._write_lock = threading.Lock()

    @classmethod
    def try_create(cls, cipher: VectorCipher) -> "DatabaseDriverVectorStore | None":
        """Open (creating if needed) the encrypted store, or ``None`` if it cannot be opened.

        "Cannot be opened" includes the storage packages simply not being installed - the
        ``driver`` extra is optional like every other capability here, and :func:`create_store`
        turns ``None`` into the fail-closed in-memory fallback.
        """
        if _DRIVER_IMPORT_ERROR is not None:
            _LOGGER.error(
                "An encryption key is configured but the lino-database-driver packages are not "
                "installed - install this service with the 'driver' extra (from the "
                "database-driver-v2 clone while unpublished). Falling back to an in-memory "
                "store rather than persisting through any other path."
            )
            return None

        try:
            from database_driver.api import Credentials, SectionConfig
            from database_driver.plugin.database.sql.sqlite.sqlite_database_provider import (
                SQLiteDatabaseProvider,
            )
        except ImportError:
            # The api package alone can be importable while the plugin distribution is absent
            # (other consumers' extras install just the api) - the same fail-closed answer as
            # the module-level guard, never a crash out of create_store at import time.
            _LOGGER.error(
                "An encryption key is configured but the lino-database-driver-plugin package is "
                "not importable - install this service with the 'driver' extra (from the "
                "database-driver-v2 clone while unpublished). Falling back to an in-memory "
                "store rather than persisting through any other path."
            )
            return None

        try:
            directory = Path(settings.store_path)
            directory.mkdir(parents=True, exist_ok=True)

            # The environment is this service's single source of configuration truth (see
            # config.py), but a Credentials file on disk would win over the constructor
            # arguments on every later boot - so it is recreated from the environment each
            # open instead of being allowed to go stale.
            config_file = directory / "database-credentials.json"
            config_file.unlink(missing_ok=True)
            credentials = Credentials(config_file, file_repository=directory / "vectors")

            provider = SQLiteDatabaseProvider(credentials)
            section = provider.create_section(_SECTION_NAME, SectionConfig.lazy())

            store = cls(provider, section, cipher)
            store._verify_disk_round_trip()
            store._migrate_legacy_database(directory)

            _LOGGER.info(
                "Using the encrypted database-driver SQLite store at %s", directory / "vectors.sqlite"
            )
            return store
        except Exception:  # noqa: BLE001 - degrade rather than fail to start
            _LOGGER.exception("Failed to open the database-driver store at %s", settings.store_path)
            return None

    def upsert(self, file_id: str, owner_user_id: str, vector: list[float], model_id: str, kind: str = KIND_TEXT) -> None:
        self._write(file_id, owner_user_id, self._cipher.encrypt(vector), model_id, kind)

    def delete(self, file_id: str) -> None:
        with self._write_lock:
            for kind in (KIND_TEXT, KIND_IMAGE):
                key = _namespaced(kind, file_id)
                if self._section.exists(key):
                    self._section.delete(key)

    def update_owner(self, file_id: str, owner_user_id: str) -> None:
        """Rewrites each existing modality entry with the new owner, leaving its ciphertext and
        recorded model untouched. A fresh document is built rather than the fetched entry's own
        being mutated - the fetched document *is* the engine's cached instance, and editing it in
        place would corrupt the cache if the write then failed."""
        with self._write_lock:
            for kind in (KIND_TEXT, KIND_IMAGE):
                key = _namespaced(kind, file_id)
                entry = self._section.find_entry_by_id(key)
                if entry is None:
                    continue
                meta = entry.get_meta_data()
                if meta is None:
                    continue
                fields = meta.as_map()
                fields["ownerUserId"] = owner_user_id
                self._section.update(DatabaseEntry(key, JsonDocument("data", fields)))

    def vectors_for(self, file_ids: Iterable[str], model_id: str, kind: str = KIND_TEXT) -> dict[str, list[float]]:
        found: dict[str, list[float]] = {}
        for file_id in file_ids:
            entry = self._section.find_entry_by_id(_namespaced(kind, file_id))
            if entry is None:
                continue
            meta = entry.get_meta_data()
            if meta is None or meta.get("modelId") != model_id:
                continue
            try:
                found[file_id] = self._cipher.decrypt(base64.b64decode(meta.get_string("vector")))
            except Exception:  # noqa: BLE001
                # A blob written under a different key, or tampered with. Skipping it drops
                # one file from the ranking; treating it as usable would poison every score
                # it takes part in.
                _LOGGER.warning("Undecryptable vector for %s - skipping it", file_id)
        return found

    def count_stale(self, model_id: str, kind: str = KIND_TEXT) -> int:
        prefix = f"{kind}:"
        stale = 0
        for entry in self._section.get_entries():
            if not entry.id.startswith(prefix):
                continue
            meta = entry.get_meta_data()
            if meta is not None and meta.get("modelId") != model_id:
                stale += 1
        return stale

    def count(self) -> int:
        return len({entry.id.split(":", 1)[1] for entry in self._section.get_entries() if ":" in entry.id})

    # ------------------------------------------------------------------ internals

    def _write(self, file_id: str, owner_user_id: str, blob: bytes, model_id: str, kind: str) -> None:
        """The single write path :meth:`upsert` and the legacy migration share: one entry per
        (modality, file) pair, the already-encrypted blob base64-encoded into the document."""
        key = _namespaced(kind, file_id)
        entry = DatabaseEntry(
            key,
            JsonDocument(
                "data",
                {
                    "fileId": file_id,
                    "kind": kind,
                    "ownerUserId": owner_user_id,
                    "modelId": model_id,
                    "vector": base64.b64encode(blob).decode("ascii"),
                },
            ),
        )
        with self._write_lock:
            if self._section.exists(key):
                self._section.update(entry)
            else:
                self._section.insert(entry)

    def _verify_disk_round_trip(self) -> None:
        """Proves at open time that a write actually reaches the file, by running a probe entry
        through the section's *storage primitives* (``persist_insert`` / ``fetch_one`` /
        ``persist_delete``), which bypass the engine's in-memory view entirely. Necessary
        because the driver's execution layer deliberately logs-and-swallows failed statements
        (its Java-parity convention), so an unwritable store would otherwise look healthy in
        memory and lose every vector on restart - the one failure mode this store exists to
        rule out. Staying below the engine is also what keeps open cheap: the probe must not be
        the thing that forces the ``LAZY`` section's one-time full load.

        The probe id carries no ``kind:`` prefix, so even an orphan left by a crash between the
        two deletes is invisible to every read path; it is deleted both before the check
        (clearing any such orphan - the table has no unique constraint to prevent a duplicate)
        and after it.

        Raises:
            OSError: If the probe entry does not survive the round trip to disk.
        """
        probe_id = "__probe__"
        self._section.persist_delete(probe_id)
        self._section.persist_insert(DatabaseEntry(probe_id, JsonDocument("data", {"probe": True})))
        try:
            if self._section.fetch_one(probe_id) is None:
                raise OSError(f"the store at {settings.store_path} did not persist a probe entry")
        finally:
            self._section.persist_delete(probe_id)

    def _migrate_legacy_database(self, directory: Path) -> None:
        """Copies every row of a pre-database-driver ``vectors.sqlite3`` into this store, then
        renames the legacy file so the migration runs exactly once - see the class docstring.
        Because the driver's execution layer swallows failed statements, "the loop finished"
        proves nothing about the disk: every migrated id is read back off the file (one table
        scan, once) and the legacy file is only renamed when all of them are present. Anything
        else - including a mid-way crash - leaves it in place for the next boot to retry, which
        is safe: the writes are idempotent upserts."""
        legacy_path = directory / _LEGACY_DATABASE_NAME
        if not legacy_path.exists():
            return

        try:
            connection = sqlite3.connect(str(legacy_path))
            try:
                columns = {row[1] for row in connection.execute("PRAGMA table_info(vectors)").fetchall()}
                if "model_id" in columns:
                    rows = connection.execute(
                        "SELECT file_id, kind, owner_user_id, vector, model_id FROM vectors"
                    ).fetchall()
                else:
                    # The pre-model_id schema: stamp each row with the currently configured
                    # model for its modality - the same deliberate assumption the old
                    # open-time backfill made for exactly these rows.
                    rows = [
                        (file_id, kind, owner, blob, "")
                        for file_id, kind, owner, blob in connection.execute(
                            "SELECT file_id, kind, owner_user_id, vector FROM vectors"
                        ).fetchall()
                    ]
            finally:
                connection.close()

            written: set[str] = set()
            for file_id, kind, owner_user_id, blob, model_id in rows:
                if not model_id:
                    model_id = settings.embedding_model if kind == KIND_TEXT else settings.clip_model
                self._write(file_id, owner_user_id, bytes(blob), model_id, kind)
                written.add(_namespaced(kind, file_id))

            persisted: set[str] = set()
            self._section.load_all(lambda entry: persisted.add(entry.id))
            missing = written - persisted
            if missing:
                raise OSError(f"{len(missing)} of {len(written)} migrated vector rows did not reach disk")

            legacy_path.rename(directory / f"{_LEGACY_DATABASE_NAME}.migrated")
            _LOGGER.info("Migrated %d legacy vector rows out of %s", len(rows), legacy_path)
        except Exception:  # noqa: BLE001 - a failed migration must not cost the opened store
            _LOGGER.exception(
                "Could not migrate the legacy store at %s - it is left in place and the "
                "migration will retry on the next start",
                legacy_path,
            )


class ChromaVectorStore:
    """Chroma-backed store, persisting to :attr:`~.config.Settings.store_path`. **Not encrypted.**

    Retained as the default for deployments that have not opted into encryption, so enabling it
    stays a deliberate choice rather than a silent storage-format migration.

    Note this class only ever *fetches* vectors by id and ranks them in Python, rather than using
    Chroma's own nearest-neighbour query - see :class:`DatabaseDriverVectorStore`'s docstring and
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
        """The model id a pre-``modelId`` entry is assumed to carry (the deliberate legacy default).

        Unlike the driver store's one-shot migration stamping, Chroma has no cheap
        ALTER-TABLE-style migration - rewriting every row just to stamp a metadata key is a far
        heavier operation - so the assumption is applied *at read time* instead: a missing
        ``modelId`` key is treated as matching the currently configured model for its modality.
        Observable behavior is identical to the driver store's open-time backfill.
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
        return DatabaseDriverVectorStore.try_create(cipher) or InMemoryVectorStore()
    return ChromaVectorStore.try_create() or InMemoryVectorStore()

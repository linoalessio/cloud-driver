# cloud-driver-extensions-search

A derived, per-account filename-plus-text-content search index over `StoredFile`, backing `GET /search`. Section 5 of `architecture/MICRO.md`.

## Project structure

Reactor position: a child of the `cloud-driver-extensions` aggregator (`packaging=pom`), sibling of `cloud-driver-extensions-backup`/`-metrics`/`-rest`/`-terminal`/`-thumbnails`/`-versioning`/`-watcher`. This module's own `pom.xml` sets `packaging=jar` and declares one dependency: `cloud-driver-plugin` (`1.0.6`, in-repo). No third-party dependencies - v1's index is a plain in-memory Java `Map`, not a real search library.

`extension.json` (`src/main/resources`):

```json
{
  "name": "cloud-driver-search",
  "version": "1.0.6",
  "description": "Per-account filename/text-content search index over StoredFile",
  "authors": ["Lino Alessio Kauschinger"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

Package layout: `de.lino.cloud.extensions.search` - `CloudSearchExtension` (public, the extension itself) plus `InMemorySearchIndexService` (public - it implements a `cloud-driver-api` contract, so it isn't package-private the way `cloud-driver-extensions-versioning`'s own service implementation is).

**A real architectural question that had to be resolved before this section could be built at all**: `architecture/MICRO.md`'s own "Reconciliation Notes" flagged that `EventFactory` only ever supports one handler per event class, so multiple addons (thumbnails, search, sync, push, webhooks...) can't each independently `registerEvent(FileUploaded.class)`. Section 1 (thumbnails) answered this for its own needs with `FileChangeListener`/`FileChangeListenerRegistry`, an async fan-out point. This module deliberately does **not** use that mechanism - it only ever fires on an `INSERT`/`UPDATE` of the `StoredFile` table, which can't see a soft-delete/restore (an `UPDATE` of the *different* `StoredFileOwnership` table) or a genuine hard delete (no Postgres trigger fires for a `DELETE` at all) - both of which a correct search index must react to (a trashed file must vanish from search; a hard-deleted one must never linger). Instead, `de.lino.cloud.auth.CloudUserService` (`cloud-driver-auth`, a core module) calls into this index **synchronously**, directly from its own `uploadFile`/`renameFile`/`moveFile`/`replaceFileContent`/`deleteFile`/`restoreFile`/`hardDeleteFile` methods - the same "reach the optional service directly via `IServiceContainer`, no-op if unpublished, never throw" shape `captureFileVersion` (section 2) already established.

## Performance

No background thread of its own at all - `CloudSearchExtension#onLoading` just constructs and publishes an `InMemorySearchIndexService`; there is nothing to tick, unlike `cloud-driver-extensions-versioning`'s purge scheduler.

- **Every write (`indexFile`/`updateMetadata`/`removeFile`) is O(1)** - a plain `ConcurrentHashMap` keyed by `authUserId`, then `storedFileId`.
- **`search` is a linear scan over the querying account's own documents** - not a real inverted index. The same accepted per-account full-scan trade-off `StoredFileOwnership`'s own listing methods already make elsewhere in this codebase, chosen deliberately over adding a search library dependency for a v1 the doc itself says not to over-engineer. `SearchIndexService` is an interface specifically so a real inverted-index/persistent implementation can replace this one later with zero call-site changes.
- **Text extraction is capped at 64 KiB per file** (`CloudUserService#MAX_INDEXED_TEXT_BYTES`) - a bounded prefix is enough for a substring match, and holding an entire large text file's content in an in-memory index for no benefit would be wasteful.

## Data handling

- **Fully derived, never a second source of truth** - every field in a `SearchDocument` is copied from data `CloudUserService` already owns (`StoredFile#fileName()`/`contentType()`, the file's own raw content, `StoredFileOwnership#getFolderId()`). Nothing here can diverge from reality in a way that isn't recoverable by re-indexing.
- **Not persisted - lost on process restart**, the same "not for production at scale" trade-off `InMemoryKeyEncryptionService`/`InMemoryPendingUploadCache` already carry elsewhere in this codebase. Safe specifically because the index is fully derived (see above) - a restart just means recently-uploaded files are briefly unsearchable until re-uploaded/re-indexed by some other trigger, never *wrongly* searchable. There is no reindex-everything command yet - a natural, flagged follow-up, not required for this pass.
- **v1 content-indexing scope**: filename is always indexed; file *content* is only extracted for text-ish content types (`text/*`, plus `application/json`/`xml`/`yaml`/`toml` - the same classification `cloud-driver-platforms-desktop`'s own `PreviewSupport.kt#previewKindFor` `TEXT` case already uses). PDF text extraction and image OCR are explicit, deliberately out-of-scope follow-ups, per the doc's own instruction.
- **Scoped to a single account, never across accounts** - a `search(authUserId, ...)` call only ever matches documents indexed under that same `authUserId`. A grantee's own search never surfaces a file merely shared with them (out of scope for v1, not a bug) - the same per-account boundary section 4's (deduplication) matching already established.

## Safety & security

- **`GET /search` is bearer-gated** like every other `/files`/`/folders`/`/activity` route, and inherently scoped to the caller's own account - there is no id-based lookup here to leak existence information about, unlike most other routes in this codebase.
- **Every `CloudUserService` hook is best-effort** - wrapped in its own try/catch, so a broken/misbehaving search index can never fail a real upload, rename, move, content replacement, delete, or restore. The same defensive shape `captureFileVersion`/`recordMetric` already use for their own optional facets.
- **A known, deliberately unaddressed gap, flagged rather than silently left undocumented**: sharing/revoke-sharing don't touch the index at all (a shared file is never indexed under the grantee's own account, matching the "scoped to a single account" design above, so there's nothing to update on a share/revoke either way) - and a deduplicated (section 4) file is indexed exactly like any other file, under its own `storedFileId`, independent of whatever canonical file its content aliases; renaming/deleting one alias never touches another's search entry, matching how dedup already keeps every alias's own metadata independent.

## Scalability

Single process, in-memory. Per-account document count is bounded by however many files that account owns - no artificial cap. `search`'s cost scales linearly with the querying account's own file count, not the deployment's total - acceptable at this codebase's actual scale (thousands of files per account, not millions); revisit with a real inverted-index implementation if that ever changes.

## API surface

- **`CloudSearchExtension`** (public) - the extension itself: `onLoading()` publishes an `InMemorySearchIndexService` into `IServiceContainer#setSearchIndexService`; `onRunning` logs a confirmation; `onEnding()`/`onException(RuntimeException)` are no-ops beyond logging - there's no background resource to release.
- **`InMemorySearchIndexService`** (public) - the one `SearchIndexService` (`cloud-driver-api`) implementation: `indexFile`/`updateMetadata`/`removeFile`/`restoreFile` (inherited default, delegates to `indexFile`)/`search`.

## API usage

This module exposes no library API meant for external callers - loaded as a jar dropped into `Constraints.EXTENSIONS_PATH` (or picked up the same way by `shell/test-bootstrap.sh`). Build it via:

```
mvn -pl cloud-driver-extensions/cloud-driver-extensions-search -am package
```

Once running, a signed-in client can:

```
GET /search?q=<query>&limit=<n>   (bearer-gated -> 200 + [SearchResult...], scoped to the caller's own files)
```

`503` if this extension isn't running on the connected deployment. A missing/blank `?q=` returns an empty array immediately rather than a `400` - an empty query is simply zero results. `?limit=` defaults to 25.

Client integration (a search bar) is explicitly deferred to a later pass, per this section's own implementation decision - see `architecture/MICRO.md`'s "Reconciliation Notes".

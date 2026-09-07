# cloud-driver-extensions-versioning

Keeps prior versions of a file's content on overwrite, with a restore path - Drive/iCloud-style version history. Section 2 of `architecture/MICRO.md`.

## Project structure

Reactor position: a child of the `cloud-driver-extensions` aggregator (`packaging=pom`), sibling of `cloud-driver-extensions-backup`/`-metrics`/`-rest`/`-terminal`/`-thumbnails`/`-watcher`. This module's own `pom.xml` sets `packaging=jar` and declares one dependency: `cloud-driver-plugin` (`1.0.6`, in-repo). No third-party dependencies - unlike `cloud-driver-extensions-thumbnails`, everything this module does is plain entity/scheduling work.

`extension.json` (`src/main/resources`):

```json
{
  "name": "cloud-driver-versioning",
  "version": "1.0.6",
  "description": "Keeps prior versions of a file on content replacement, with a restore path",
  "authors": ["Lino Alessio Kauschinger"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

Package layout: `de.lino.cloud.extensions.versioning` - `CloudVersioningExtension` (public, the extension itself) plus three package-private classes: `FileVersion` (the linking entity), `DefaultFileVersioningService` (the one `FileVersioningService` implementation), `FileVersionPurgeScheduler` (retention enforcement).

**A real, non-obvious prerequisite this section needed before it could be built at all**: this app had no "overwrite a file's content in place" operation anywhere before this section - every upload always minted a fresh file id. `de.lino.cloud.auth.CloudUserService` (`cloud-driver-auth`, a core module) gained a new `replaceFileContent(authUserId, storedFileId, newContent)` method, plus a matching `PUT /files/{id}/content` REST route in `DefaultRestFactory` (`cloud-driver-plugin`) - purely additive, no existing route's behavior changed. This was confirmed as the right approach (over reusing the existing upload route with same-name-overwrites-in-place semantics, which would have changed existing behavior) before implementation started - see `architecture/MICRO.md`'s "Reconciliation Notes" for the decision record.

## Performance

No background thread of its own beyond `FileVersionPurgeScheduler`'s single daemon-threaded `ScheduledExecutorService` (mirroring `cloud-driver-plugin`'s own `TrashPurgeScheduler` exactly), ticking once a day.

- **Version capture is synchronous, inline with the content-replacement call it's part of** - `CloudUserService#replaceFileContent` calls `FileVersioningService#captureVersion` (via `IServiceContainer`, best-effort/null-checked) *before* writing the new content, since that's the only point at which the about-to-be-superseded content still exists. This adds real latency to every content replacement (one extra `StoredFile` upload + one small entity write), not just an async side effect - an accepted cost, since versioning without this ordering would simply lose data.
- **Listing a file's version history is a full scan-and-filter** over every `FileVersion` in the whole system (`DataFactory#getEntities(FileVersion.class)`, filtered to one `sourceFileId`) - the same accepted trade-off `StoredFileOwnership`/`SharedFileGrant` already make for their own non-primary-key lookups elsewhere in this codebase. Cheap in practice because retention keeps the per-file count small (default 10) and the purge scheduler keeps the total count bounded.
- **The purge scheduler's own sweep is O(total retained versions across every account)**, once a day - trivial at any scale this codebase's other schedulers already operate at.

## Data handling

- **A version's own bytes are stored as an ordinary `StoredFile`** - envelope-encrypted, DEFLATE-compressed if smaller, exactly like any other file. There is no separate, less-protected storage path for historical content.
- **`FileVersion` (the linking row) carries a copy of the source file's `fileName`/`contentType`/`sizeBytes`/checksum at capture time** - not content itself - so listing/download-header construction never needs to touch the versioned `StoredFile`'s own content unless actually downloading it.
- **No content ever leaves this process** - capture, listing, restore, and purge are all local database/`StoredFile` operations.

## Safety & security

- **`GET /files/{id}/versions` and `GET /files/{id}/versions/{n}/content` are owner-or-share access-checked** (`CloudUserService#checkFileAccess`), the same as `GET /files/{id}/content` - whoever can currently view a file can see and download its version history too, matching this codebase's existing "share is read-only" model.
- **`POST /files/{id}/versions/{n}/restore` is owner-only**, enforced entirely inside `CloudUserService#replaceFileContent`'s own ownership check - the REST handler deliberately does **not** perform a separate `checkFileAccess` call first: resolving a version's content is a plain id-keyed lookup with no user-scoping of its own, but a non-owner reaches exactly the same `404` either way (whether the version exists or not, whether they're the owner or not), so there is nothing about a version's existence this route could leak to a non-owner regardless.
- **Restoring never mutates history in place** - per `architecture/MICRO.md` section 2's own instruction, `handleRestoreFileVersion` just calls `replaceFileContent` with the old version's content, which itself always captures whatever was live immediately beforehand as a brand-new version first. Nothing is ever destroyed by a restore.
- **A known, deliberately unaddressed gap, flagged rather than silently left undocumented**: a version's own underlying `StoredFile` is not cleaned up if the *source* file is permanently (hard-)deleted outside the normal purge path (`CloudUserService#hardDeleteFile`/`resetCloudUser`/`deleteCloudUser`) - mirrors the exact same accepted gap this codebase's own `architecture/AWS_S3_IMPL.md` writeup documents for S3 objects on `clear()`/`deleteSection()` (see `CLAUDE.md`'s "S3-backed `StoredFile` content" section). Out of scope for this pass; a future pass wanting this closed would hook the relevant hard-delete call sites the same way S3 cleanup eventually might.
- **`FileVersionPurgeScheduler` is started automatically**, unlike `TrashPurgeScheduler`'s own deliberately-manual-opt-in precedent - see that class's own Javadoc for why this is safe here specifically (a brand-new capability with zero pre-existing version data on any deployment, so no initial default retention window can surprise-delete anything that already existed).

## Scalability

Single process, in-memory scheduling state (the executor itself) - a version-count/age cap enforced independently on each process if more than one bootstrap process ever ran against the same database, though nothing in this codebase's actual deployment does that today. Retention defaults (10 versions / 30 days) bound both per-file storage growth and the full-scan cost `listVersions`/the purge sweep pay - configurable via `configuration.json`'s `"file-versioning-max-versions-per-file"`/`"file-versioning-retention-days"` keys without a code change.

## API surface

- **`CloudVersioningExtension`** (public) - the extension itself: `onLoading()` publishes a `DefaultFileVersioningService` into `IServiceContainer#setFileVersioningService` and builds+starts a `FileVersionPurgeScheduler` (daily tick); `onRunning` logs a confirmation; `onEnding()`/`onException(RuntimeException)` shut the scheduler down.
- **`FileVersion`** (package-private entity) - `sourceFileId`/`versionNumber`/`versionedFileId`/`fileName`/`contentType`/`sizeBytes`/`contentHash`/`capturedAtEpochMillis`, primary-keyed on `compositeKey(sourceFileId, versionNumber)`.
- **`DefaultFileVersioningService`** (package-private) - the one `FileVersioningService` (`cloud-driver-api`) implementation: `captureVersion`/`listVersions`/`getVersionContent`.
- **`FileVersionPurgeScheduler`** (package-private) - `start(Duration)`/`stop()`/`shutdown()`; `withConfiguredRetention(...)` reads both retention knobs from `configuration.json`.

## API usage

This module exposes no library API meant for external callers - loaded as a jar dropped into `Constraints.EXTENSIONS_PATH` (or picked up the same way by `shell/test-bootstrap.sh`). Build it via:

```
mvn -pl cloud-driver-extensions/cloud-driver-extensions-versioning -am package
```

Once running, a signed-in client can:

```
PUT /files/{id}/content            (owner-only, raw application/octet-stream body -> 200 + StoredFileSummary)
GET /files/{id}/versions           (owner-or-share -> 200 + [FileVersionSummary...])
GET /files/{id}/versions/{n}/content   (owner-or-share -> 200 + raw content, or 404)
POST /files/{id}/versions/{n}/restore  (owner-only -> 200 + StoredFileSummary, or 404)
```

`503` on any of the three versioning-specific routes if this extension isn't running on the connected deployment; `PUT /files/{id}/content` itself never 503s (content replacement works even with this extension off, it just doesn't retain history in that case).

`"file-versioning-max-versions-per-file"` (int, default `10`) / `"file-versioning-retention-days"` (int, default `30`) - both optional `configuration.json` keys the purge scheduler reads at startup.

Client integration (a version-history panel, restore/download-this-version actions) is explicitly deferred to a later pass, per this section's own implementation decision - see `architecture/MICRO.md`'s "Reconciliation Notes".

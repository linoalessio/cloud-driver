# cloud-driver-extensions-thumbnails

Generates and serves preview thumbnails for uploaded JPEG/PNG images and PDFs, so a client can render a grid view without downloading every full file - section 1 of `architecture/MICRO.md` (the first item in a much larger, still-mostly-unimplemented microservices/extensions roadmap; see that document's own "Reconciliation Notes" for how it was adapted to this repo's actual architecture before being built).

## Project structure

Reactor position: a child of the `cloud-driver-extensions` aggregator (`packaging=pom`), sibling of `cloud-driver-extensions-backup`/`-metrics`/`-rest`/`-terminal`/`-watcher`. This module's own `pom.xml` sets `packaging=jar` and declares two dependencies: `cloud-driver-plugin` (`1.0.6`, in-repo, transitively brings in `cloud-driver-api`/`cloud-driver-auth`) and `org.apache.pdfbox:pdfbox:2.0.29` (first-page PDF rendering only - JPEG/PNG thumbnails use the JDK's own built-in `javax.imageio`, no dependency needed).

`extension.json` (`src/main/resources`):

```json
{
  "name": "cloud-driver-thumbnails",
  "version": "1.0.6",
  "description": "Generates and serves preview thumbnails for uploaded images and PDFs",
  "authors": ["Lino Alessio Kauschinger"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

Deliberately depends on `"cloud-driver-bootstrap"` only - **not** `"cloud-driver-watcher"`, even though generation is entirely driven by watcher-fed notifications. If the watcher extension isn't running on a given deployment, this extension still starts (its `ThumbnailService` is still published, so `GET /files/{id}/thumbnail` works and correctly 404s), it simply never receives a notification to act on - a graceful no-op, not a hard dependency failure.

Package layout: `de.lino.cloud.extensions.thumbnails` - `CloudThumbnailsExtension` (public, the extension itself) plus five package-private classes: `FileThumbnail` (the linking entity), `DefaultThumbnailService` (the one `ThumbnailService` implementation), `ThumbnailGenerator` (the per-content-type strategy interface), `ImageThumbnailGenerator`/`PdfThumbnailGenerator` (its two implementations), and `ImageScaling` (a shared scale-and-JPEG-encode helper both generators call). No sub-packages.

**A real, load-bearing gotcha this module's own `pom.xml`/`cloud-driver-bootstrap`'s `pom.xml` both carry a comment about:** every extension jar is loaded unshaded via its own `URLClassLoader` (parent = the host bootstrap jar's own classloader, parent-first for classes) - so `org.apache.pdfbox` classes are only resolvable at runtime because `cloud-driver-bootstrap` also declares that same dependency directly (shaded into the host jar), even though no class in that module ever references PDFBox itself. This is the exact same fix `cloud-driver-extensions-metrics`' Micrometer dependency already needed - see that module's own `CLAUDE.md` incident writeup (a real boot-crashing `ClassNotFoundException`, fixed the same way) before adding a new third-party dependency to this module without mirroring it in `cloud-driver-bootstrap`.

## Performance

No background thread of its own beyond a small, fixed, daemon-threaded `ExecutorService` (`THUMBNAIL_EXECUTOR_THREADS = 2`) generation work is dispatched onto - never the Postgres `LISTEN`/`NOTIFY` listener thread notifications originate from. Per `architecture/MICRO.md` section 1's own instruction ("start in-process with a bounded executor; document how to swap in a real queue later - do not over-engineer this in v1"): this executor is purely in-memory and process-local - a generation task queued when the process exits mid-generation is simply lost (the file itself is unaffected; it just never gets a thumbnail until some other trigger re-fires, which nothing currently does). A future revision wanting durability across a restart would replace this with a real persisted queue, mirroring `PendingUploadCache`'s own shape - not attempted here.

Generation cost is paid once per file, asynchronously, off the upload path entirely - `POST /files` returns as soon as the upload itself completes; a thumbnail becoming available is a separate, later event a client discovers by polling/re-requesting `GET /files/{id}/thumbnail` (no push notification for "your thumbnail is ready" exists yet).

## Data handling

- **A thumbnail's own bytes are stored as an ordinary `StoredFile`** - envelope-encrypted (AES-256-GCM), DEFLATE-compressed if smaller, exactly like any other uploaded file. There is no separate, less-protected storage path for thumbnail content.
- **`FileThumbnail` (the linking row) carries no content of its own** - only two ids (source file, thumbnail file), a size-variant name, and a timestamp. It is itself envelope-encrypted like every other entity in this codebase, though the ids it carries are already opaque UUIDs.
- **No new PII/content ever leaves this process** - PDF rendering and image scaling both happen in-process, synchronously within the generation task; nothing is sent to an external service.

## Safety & security

- **`GET /files/{id}/thumbnail` is access-checked identically to `GET /files/{id}/content`** - `DefaultRestFactory`'s handler calls `CloudUserService#checkFileAccess` (owner-or-share) *before* ever asking `ThumbnailService` anything, so a caller with no access to the source file gets the same `404` a nonexistent file would, never a "no thumbnail, but yes you can see this file exists" signal.
- **A deliberate, documented deviation from the handoff doc's literal design**: rather than adding a `parentFileId`/`thumbnailOf` field directly onto `StoredFile` (this codebase's single most heavily-scrutinized entity), thumbnail linkage lives entirely in `FileThumbnail`, a small separate row - `StoredFile`'s own schema is untouched by this module. Removing/disabling this extension leaves `StoredFile` completely unaffected either way.
- **WebP is not supported in v1**, despite being named in the handoff doc - the JDK's `ImageIO` has no built-in WebP reader, and adding one would need the same cross-module dependency-shading fix PDFBox already needed, for a format this deployment has no evidence of needing yet. Video thumbnails are likewise out of scope for v1, per the doc's own explicit allowance. Both are documented follow-ups, not silent gaps.
- Every failure inside a generation task (an undecodable/corrupt file, an unsupported content type, a persistence error) is caught and logged, never propagated - a broken thumbnail generation must never be visible anywhere the original upload itself would be.

## Scalability

Single process, in-memory queue (see "Performance" above) - does not scale across multiple bootstrap processes; each would run its own independent generation pipeline with no coordination, so the same file uploaded via two different processes' own upload paths could theoretically be thumbnailed twice (harmless - the second attempt's `FileThumbnail#compositeKey` lookup finds the first attempt's row already there and skips, assuming the first has already committed; a narrow race if both run concurrently is accepted, not fixed here, matching this module's own "do not over-engineer v1" scope). Generation cost scales with image resolution/PDF page complexity, not file count directly - bounded per-task by `ThumbnailSize.SMALL`'s fixed 256px output regardless of source size.

## API surface

- **`CloudThumbnailsExtension`** (public) - the extension itself: `onLoading()` builds the bounded executor, publishes a `DefaultThumbnailService` into `IServiceContainer#setThumbnailService`, and registers a `FileChangeListener` against `IFactoryContainer#getFileChangeListenerRegistry()`; `onRunning` logs a confirmation; `onEnding()`/`onException(RuntimeException)` both unregister the listener and shut the executor down (bounded await, then force).
- **`FileThumbnail`** (package-private entity) - `sourceFileId`/`size`/`thumbnailFileId`/`generatedAtEpochMillis`, primary-keyed on `compositeKey(sourceFileId, size)`.
- **`DefaultThumbnailService`** (package-private) - the one `ThumbnailService` (`cloud-driver-api`) implementation: `getThumbnail(storedFileId, size)` looks up the `FileThumbnail` row, then the thumbnail `StoredFile`'s content - `Optional.empty()` for any miss or failure.
- **`ThumbnailGenerator`**/**`ImageThumbnailGenerator`**/**`PdfThumbnailGenerator`**/**`ImageScaling`** (package-private) - the generation strategy; not reachable from outside this module.

## API usage

This module exposes no library API meant for external callers - `CloudThumbnailsExtension` is loaded as a jar dropped into `Constraints.EXTENSIONS_PATH` (or picked up the same way by `shell/test-bootstrap.sh`), not called directly from Java. Build it via:

```
mvn -pl cloud-driver-extensions/cloud-driver-extensions-thumbnails -am package
```

Client integration (a shared data-layer method, on-device caching, grid views requesting thumbnails instead of full content) is explicitly deferred to a later pass, per this section's own implementation decision - see `architecture/MICRO.md`'s "Reconciliation Notes" for why. Today, once this extension is running, a signed-in client can fetch a thumbnail directly:

```
GET /files/{id}/thumbnail
Authorization: Bearer <jwt>
```

`200` with `Content-Type: image/jpeg` if one exists (already generated, and the caller owns or has been shared the source file), `404` if not (no access, source file doesn't exist, thumbnail not yet generated/unsupported/failed - all indistinguishable from the outside, by design), `503` if this extension isn't running on the connected deployment at all.

# Troubleshooting

Real issues encountered running this system, and their actual fixes — kept here so they aren't
rediscovered the hard way a second time.

| Symptom | Cause | Fix |
|---|---|---|
| Server crashes with an out-of-memory error while persisting a large upload | Several full in-memory copies of a file's content exist simultaneously on the persist path (compressed bytes → encoded string → JSON → re-encoded bytes → encrypted) | Give the process an explicit, sufficiently large heap size at startup; a true streaming fix (chunked encryption) is a larger, separately-tracked change |
| A route silently returns a bare `500` with nothing printed anywhere | Framework request-logging is intentionally silenced, and an unmapped exception on that route wasn't being printed before being translated into a response | Every route's failure-translation path now logs the full exception to the server's own console before responding |
| Uploads succeed but the file can't be opened/downloaded immediately afterward | Under a burst of concurrent uploads, the outbound network probe used to detect "offline" can spuriously report unavailable, silently deferring a real upload into a retry queue instead of persisting it right away | Where the database is co-located with the application server, connectivity is treated as always available rather than probed per upload |
| A freshly uploaded file reports a `0` byte size, or downloading it 404s | The running backend jar predates the code that captures file size/metadata, or predates the download route entirely | Rebuild and redeploy the backend jar — this is a stale-deployment symptom, not a client bug |
| The whole server crashes on startup with a class-loading error the moment a particular feature module is present | A feature module needs a third-party library nothing else in the process already provides, and feature-module jars only resolve shared classes off the host jar's own classpath | Add that library as a dependency of the main runnable jar itself, so it's present on the shared classpath every feature module falls back to |
| Deleting a large folder tree (or a similar bulk operation) fails with a "too many concurrent streams" error | Each level of a recursive operation independently capped its own concurrency, so the real total concurrency multiplied with tree depth/width instead of ever being bounded | List the whole operation's items first, then run exactly one bounded-concurrency batch over the flattened list |
| A signed-in session stops surviving an app restart, even though it worked minutes earlier | A session token was rotated during use but never re-persisted to local storage, and/or the value being stored contained characters a backing credential store silently mangled on read-back | Persist the session on every token rotation, not only at initial sign-in, and store composite values in a plain-text-safe encoding |
| Extracting/uploading a folder from a cloud-synced location fails with a generic filesystem error | A shallow check only guarantees the top-level picked item is materialized locally, not every nested file several levels down | Recurse with a real per-item existence check, not a resource-value property a given storage provider might not populate |
| A desktop window can be resized down until it effectively disappears | The window had no enforced minimum size | Enforce a floor, but do it through the UI framework's own window-state API — mutating the underlying native window object directly can corrupt rendering across the whole app |

## If a fix here contradicts current source

Prefer the source itself. This page describes root causes and fixes that were true at the time
they were written; if a later change altered the underlying mechanism, update this table rather
than leaving a stale explanation in place.

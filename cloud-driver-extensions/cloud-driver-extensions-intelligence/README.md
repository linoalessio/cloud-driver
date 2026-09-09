# cloud-driver-extensions-intelligence

The Java half of semantic search: a bridge between `cloud-driver` and the standalone
[`cloud-driver-intelligence`](../../cloud-driver-intelligence) Python service, which owns the
embedding model and the vector store.

Complements, never replaces, `cloud-driver-extensions-search` (keyword matching over file names and
extracted text). Both are independently optional — a deployment may run either, both, or neither.

## What it does

Publishes an `IntelligenceService` into `IServiceContainer`. From there:

- **Indexing** — `CloudUserService` hands every uploaded, content-replaced and restored file to it,
  and tells it about every trashed/deleted file. Each call is queue-and-return; the HTTP request to
  the Python service happens on this extension's own background worker.
- **Searching** — `CloudUserService#semanticSearch` uses it to rank the caller's accessible files,
  behind `GET /search/semantic`.

## The one thing to understand before changing anything here

**The Python service never decides who may see what.** Its record of ownership is a stale hint,
invalidated by any share, revocation, move or deletion that happens afterwards. Every search is
two-staged, and both stages live in `CloudUserService#semanticSearch`:

1. **Pre-filter** — the complete set of file ids the caller currently has access to is resolved
   from authoritative ownership/sharing data and passed as `candidateFileIds`. The Python service
   ranks only within it.
2. **Post-check** — every returned id is required to be a member of that same freshly-resolved set,
   *and* is then run through `checkFileAccess`, the same check every other `/files` response
   performs. That second half also enforces the content-scan gate, so a still-scanning or flagged
   file cannot leak into a result list.

With both in place, a compromised or out-of-date Python service can at worst return **nothing**.
This is verified, not assumed: a harness runs the real `CloudUserService` against a deliberately
malicious `IntelligenceService` that returns another account's real file ids, and confirms they are
rejected.

Note that `IntelligenceHttpClient` deliberately does *not* verify the returned ids itself. Adding
that check there would invite the belief that the caller's own re-check is therefore unnecessary.

## Why no `FileChangeListener`

A deliberate deviation from the handoff document's §5, which specified one on `"INSERT"` by analogy
with `cloud-driver-extensions-scan`. That mechanism is a poor fit here:

- **It cannot observe a deletion at all.** The backing Postgres trigger is `AFTER INSERT OR UPDATE`
  on the `StoredFile` table only. A soft delete is an `UPDATE` of `StoredFileOwnership` (a different
  table); a hard delete fires nothing. §4 nonetheless requires `DELETE /index/{fileId}` on
  deletion — which a listener simply could not do.
- **It would misread a content replacement.** Reacting to `"INSERT"` alone ignores
  `PUT /files/{id}/content`; reacting to every `"UPDATE"` would re-fetch and re-embed a file's full
  content on every rename and move.

So indexing is driven from `CloudUserService`'s own methods, exactly as search indexing and webhook
dispatch already are. Content scanning is the one facet the listener genuinely suits, because it
only ever needs to react to a brand-new row.

## Configuration

Read from `cloud-driver`'s own `configuration.json`:

| Key | Default | Purpose |
|---|---|---|
| `intelligence-shared-secret` | *(none — **required**)* | Sent as `X-Internal-Secret`. This extension refuses to load without it: there is no safe default, and falling back to an empty secret would either break every call silently or invite the Python service to accept an empty one. |
| `intelligence-host` | `127.0.0.1` | Python service host. |
| `intelligence-port` | `8600` | Python service port. |
| `intelligence-timeout-seconds` | `30` | Per-request HTTP timeout. |
| `intelligence-max-bytes` | `104857600` (100 MiB) | Files larger than this are skipped, un-indexed. |

Failing to load disables only this extension, exactly as any other extension's load failure does.

**`intelligence-max-bytes` is worth lowering deliberately.** 100 MiB is the value the handoff
document specifies, kept rather than silently changed — but content travels base64-encoded in a JSON
body (~1.37x, so this default permits a ~137 MiB request), and a large binary is overwhelmingly
likely to yield nothing embeddable anyway: the service falls back to a name-only embedding for it,
having been sent the whole file to reach that conclusion. A value in the low tens of MiB is more
proportionate in practice.

## Fail-open

An unreachable or unhappy Python service is retried three times (fixed 3s/15s delays, the same
non-exponential precedent `DefaultWebhookService`/`DefaultContentScanService` set), then given up on
with a `SEVERE` log. The file is simply left un-indexed, and no state for that is persisted on
`StoredFile` at all.

This is materially less consequential than `DefaultContentScanService`'s own fail-open decision:
that one trades away real malware protection, whereas this only costs a file its semantic
discoverability until it is next re-uploaded or its content replaced. Nothing about access, quota or
integrity depends on it.

**Searching is never retried** — a user is waiting on it, a retry would push the request past every
client's timeout, and "no semantic results" is a serviceable answer the route can fall back to
keyword search from.

## Dependencies

Only `cloud-driver-plugin`. `IntelligenceHttpClient` is built on the JDK's own `java.net.http`, and
Gson is already on the host bootstrap jar's shaded classpath.

That is deliberate. Every extension jar is loaded unshaded, parent-first against the host classpath,
so a library nothing else in this repo already pulls in must *also* be declared by
`cloud-driver-bootstrap` to be resolvable at runtime — the `NoClassDefFoundError` trap
`cloud-driver-extensions-metrics` (micrometer) and `cloud-driver-extensions-thumbnails` (pdfbox)
each had to be fixed for. Adding nothing new sidesteps it entirely.

### HTTP/1.1 is pinned — do not "modernise" this

`IntelligenceHttpClient` explicitly sets `HttpClient.Version.HTTP_1_1`. The JDK default is `HTTP_2`,
which over plaintext `http://` attempts an h2c upgrade (`Connection: Upgrade, HTTP2-Settings`).
uvicorn does not implement h2c and drops the request body rather than declining cleanly — so FastAPI
answered every `POST` with `422 {"detail":[{"type":"missing","loc":["body"]}]}` while the body was
demonstrably being written (correct `Content-Type`, `Content-Length: 106`, 106 bytes received).

Found only by running the bridge against the real Python service. A stub HTTP server reading the
body raw never reproduces it.

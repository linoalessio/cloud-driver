# cloud-driver-extensions-metrics

Exposes operational metrics (upload counts by outcome, pending-upload queue depth, per-extension status, quota-exceeded rejections) via a Prometheus-scrapeable `GET /metrics` endpoint on its own, unauthenticated HTTP port - item 13 of `architecture/SERVICES.md`, and the newest module in the `cloud-driver-extensions` tree. Nothing before this exposed the running process's operational state beyond scattered log lines, relevant given `shell/start-cloud.sh` runs it unattended with an auto-restart-on-crash loop.

## Project structure

Reactor position: a child of the `cloud-driver-extensions` aggregator (`packaging=pom`), sibling of `cloud-driver-extensions-backup`/`-rest`/`-terminal`/`-watcher`. This module's own `pom.xml` sets `packaging=jar` and declares three dependencies: `cloud-driver-plugin` (`1.0.1`, in-repo - needed for `DefaultFileFactory#getPendingUploadCache()`, and transitively brings in `cloud-driver-api`/`cloud-driver-auth`), `io.micrometer:micrometer-registry-prometheus:1.13.6` (pulls in `micrometer-core` transitively), and `io.javalin:javalin:7.2.3` (pinned to the exact same version `cloud-driver-plugin` already uses, so only one Javalin version ever lands on the classpath).

`extension.json` (`src/main/resources`):

```json
{
  "name": "cloud-driver-metrics",
  "version": "1.0.0",
  "description": "Exposes operational metrics (uploads, pending-upload queue depth, extension status, quota rejections) via a Prometheus-scrapeable /metrics endpoint",
  "authors": ["Lino Alessio Kauschinger"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

Deliberately depends on `"cloud-driver-bootstrap"` only - **not** `"cloud-driver-rest"`/`"cloud-driver-watcher"` - so this extension (and the metrics it exposes) comes up regardless of whether either of those does.

Package layout: `de.lino.cloud.extensions.metrics` - three classes, `CloudMetricsExtension` (public) plus `MicrometerMetricsRecorder`/`MetricsHttpServer` (both package-private), no sub-packages.

## Performance

No background thread of its own beyond what `MetricsHttpServer`'s embedded Jetty instance already runs:

- **Gauges are evaluated synchronously, on the scraping thread, once per scrape.** `cloud_driver_pending_upload_queue_depth` and `cloud_driver_extensions{status=...}` (one per `ExtensionStatus`) are both registered as a Micrometer `Gauge` backed by a live supplier that reads straight off `CloudDriver` (`DefaultFileFactory#getPendingUploadCache()#size()`, `ExtensionFactory#getExtensions()` filtered/counted by status) - no separate polling thread or loop, the value is only ever computed when Prometheus actually asks for it.
- **Counters are cheap, thread-safe increments on the calling thread itself.** The four `Counter`s (`cloud_driver_uploads_total{outcome="success"|"failure"|"queued"}`, `cloud_driver_upload_quota_rejections_total`) are incremented directly by `DefaultFileFactory#upload` (`cloud-driver-plugin`) and `CloudUserService#uploadFile` (`cloud-driver-auth`) via `IServiceContainer#getMetricsRecorder()` - a plain `Counter#increment()` call, no queueing, no batching.
- **Every counter is registered at construction, not lazily on first use** - so it already exists (at zero) from this extension's very first scrape onward, rather than only appearing in `/metrics` once its first real event happens to fire.
- Both call sites reaching into `MetricsRecorder` null-check first (it's `null` until this extension has actually run) and never throw, so a deployment not running this extension behaves exactly as before.

## Data handling

Only aggregate counts and gauges flow through this module - no file content, no decrypted entity data, no user-identifying information. What actually passes through:

- Four upload-outcome/quota-rejection counters, labelled only by a fixed `outcome` tag (`success`/`failure`/`queued`) or none at all.
- A pending-upload queue depth (an integer count, read from `PendingUploadCache#size()` - never the queued files' own content or ids).
- Per-`ExtensionStatus` extension counts (how many registered extensions are `RUNNING`/`LOADING`/`ENDING`/`ERROR`, not which ones by name).

## Safety & security

- **Deliberately unauthenticated.** `GET /metrics` has no credential check of its own - access control is network placement (the loopback-only default bind host below, plus firewalling/a reverse-proxy IP allowlist for anything wider), not a token/key check. `MetricsHttpServer`'s own Javadoc flags this explicitly as a decision worth revisiting with the maintainer if the deployment's threat model changes, rather than silently deciding it.
- **Loopback-only bind by default.** `onLoading()` reads `"metrics-bind-host"` from `configuration.json`, defaulting to `127.0.0.1` if unset - unlike `CloudRestExtension`'s own `"0.0.0.0"` default, since this endpoint (unlike the JWT-gated REST API) has no authentication of its own to fall back on. Widening it is an explicit, deliberate opt-in.
- **A dedicated Javalin instance, not a route mounted on the JWT-gated `RestFactory`.** A Prometheus scraper is not a logged-in end user; reusing `DefaultRestFactory`'s bearer-token-gated instance would mean either exempting `/metrics` from every auth filter there (fragile - one misordered filter away from leaking metrics or breaking real auth) or handing a scraper a real account/token for no reason. A separate instance on its own configurable port keeps that boundary structural instead of filter-order-dependent.
- `MicrometerMetricsRecorder`/`MetricsHttpServer` are both package-private - the only way anything outside this module can reach the recorder is through `IServiceContainer#getMetricsRecorder()`, which is `null` until this extension has actually published one.

## Scalability

Single process, single port; scrape cost is O(number of registered meters) - trivial at this codebase's scale (four counters, two gauge families). Every meter is process-local, in-memory `Micrometer` state: nothing here is persisted, so every counter resets to zero on process restart (matching the general "process-local unless explicitly DB-backed" trade-off other in-memory caches in this codebase already accept, e.g. `InMemoryPendingUploadCache`). Gauges recompute fresh on every single scrape rather than being cached between scrapes - cheap at the current scale (a handful of extensions, one queue), but would need revisiting if either grew by orders of magnitude. Running more than one bootstrap process would give each its own independent `/metrics` endpoint with its own independent counters - nothing here aggregates metrics across processes.

## API surface

- **`CloudMetricsExtension`** (public) - the extension itself: `onLoading()` reads `"metrics-port"`/`"metrics-bind-host"` from `configuration.json` (defaults `9404`/`127.0.0.1`), builds a `PrometheusMeterRegistry`, registers the two gauge families, publishes a `MicrometerMetricsRecorder` into `IServiceContainer#setMetricsRecorder`, and starts `MetricsHttpServer`; `onRunning` logs a confirmation once the endpoint is listening; `onEnding()`/`onException(RuntimeException)` both stop the HTTP server (null-guarded).
- **`MicrometerMetricsRecorder`** (package-private) - the one `MetricsRecorder` (`cloud-driver-api`) implementation: four `Counter`s registered once at construction against the shared registry, exposed via `recordUploadSuccess()`/`recordUploadFailure()`/`recordUploadQueued()`/`recordUploadQuotaRejected()`.
- **`MetricsHttpServer`** (package-private) - a small, standalone Javalin instance; `start(String host, int port)` (throws `IllegalStateException` if already started) mounts `GET /metrics` serving the registry's Prometheus text-format scrape (`text/plain; version=0.0.4; charset=utf-8`); `stop()` is a no-op if never started.

## API usage

This module exposes no library API meant for external callers - `CloudMetricsExtension` is loaded as a jar dropped into `Constraints.EXTENSIONS_PATH` (or picked up the same way by `shell/test-bootstrap.sh`), not called directly from Java. In place of a caller-facing usage example, here is the build command and the relevant `configuration.json` keys this extension reads at startup:

```
mvn -pl cloud-driver-extensions/cloud-driver-extensions-metrics -am package
```

- `"metrics-port"` (int, default `9404`) - the port `MetricsHttpServer` listens on.
- `"metrics-bind-host"` (string, default `"127.0.0.1"`) - the interface it binds to; widen only deliberately (see "Safety & security" above).

Once deployed, point a Prometheus `scrape_configs` job at `http://<host>:<metrics-port>/metrics`.

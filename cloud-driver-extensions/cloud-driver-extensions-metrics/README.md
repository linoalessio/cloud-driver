# cloud-driver-extensions-metrics

Exposes operational metrics (upload counts by outcome, pending-upload queue depth, extension status,
quota-exceeded rejections) via a Prometheus-scrapeable `GET /metrics` endpoint on its own,
unauthenticated HTTP port - item 13 of `architecture/SERVICES.md`.

## Project structure

Reactor position: a child of the `cloud-driver-extensions` aggregator (`packaging=pom`), sibling of
`cloud-driver-extensions-backup`/`-rest`/`-terminal`/`-watcher`. Its `pom.xml` depends on
`cloud-driver-plugin` (for `DefaultFileFactory#getPendingUploadCache()`, and transitively for
`cloud-driver-api`/`cloud-driver-auth`), `io.micrometer:micrometer-registry-prometheus:1.13.6`
(brings `micrometer-core` transitively), and `io.javalin:javalin:7.2.3` (pinned to the exact same
version `cloud-driver-plugin` already uses, so only one Javalin version ever lands on the classpath).
Package: `de.lino.cloud.extensions.metrics`.

## Why this exists

Nothing before this exposed `cloud-driver`'s operational state beyond scattered log lines -
`shell/start-cloud.sh` runs the process unattended with an auto-restart-on-crash loop, but there was
no way to observe upload volume/failure rate, how deep the offline pending-upload queue is, or
whether every extension actually came up, short of reading logs by hand.

## `extension.json`

```json
{
  "name": "cloud-driver-metrics",
  "version": "1.0.0",
  "description": "Exposes operational metrics (uploads, pending-upload queue depth, extension status, quota rejections) via a Prometheus-scrapeable /metrics endpoint",
  "authors": ["Lino Alessio Kauschinger"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

Deliberately depends on `"cloud-driver-bootstrap"` only - not `"cloud-driver-rest"`/`"cloud-driver-watcher"` -
so this extension (and the metrics it exposes) comes up regardless of whether either of those does,
matching the minimal-dependency precedent other extensions set where possible.

## Code structure

- **`CloudMetricsExtension`** - the extension itself. `onLoading()` reads `"metrics-port"`/`"metrics-bind-host"`
  from `configuration.json` (defaults: port `9404`, bind host `127.0.0.1` - loopback-only by default,
  unlike `CloudRestExtension`'s `"0.0.0.0"`, since this endpoint has no authentication of its own),
  builds a `PrometheusMeterRegistry`, registers every gauge, publishes a `MicrometerMetricsRecorder`
  into `IServiceContainer#setMetricsRecorder`, and starts `MetricsHttpServer`. `onEnding()`/`onException`
  stop the HTTP server.
- **`MicrometerMetricsRecorder`** (package-private) - the one `MetricsRecorder` (`cloud-driver-api`)
  implementation: four `Counter`s (`cloud_driver_uploads_total{outcome="success"|"failure"|"queued"}`,
  `cloud_driver_upload_quota_rejections_total`), incremented by `DefaultFileFactory#upload`
  (`cloud-driver-plugin`) and `CloudUserService#uploadFile` (`cloud-driver-auth`) via
  `IServiceContainer#getMetricsRecorder()` - both of those call sites null-check first and never throw,
  so a deployment not running this extension behaves exactly as before.
- **`MetricsHttpServer`** (package-private) - a small, standalone Javalin instance serving `GET /metrics`
  as the registry's Prometheus text-format scrape. A dedicated instance rather than a route mounted on
  `DefaultRestFactory`'s JWT-gated instance, on purpose - see its own Javadoc for the reasoning.
- **Two gauges are poll-based, not pushed through `MetricsRecorder`** - `cloud_driver_pending_upload_queue_depth`
  (reads `DefaultFileFactory#getPendingUploadCache()#size()`) and `cloud_driver_extensions{status=...}`
  (one gauge per `ExtensionStatus`, counting `ExtensionFactory#getExtensions()`) - both read fresh off
  `CloudDriver` on every scrape via a Micrometer `Gauge` backed by a live supplier, so no separate
  polling thread/loop was needed for either.

## Scalability / concurrency

No background thread of its own beyond what `MetricsHttpServer`'s embedded Jetty already runs - every
gauge is evaluated synchronously, on the scraping thread, once per scrape; every counter increment is
a cheap, thread-safe `Counter#increment()` call on the uploading/rejecting thread itself, no queueing.

## API usage

This module exposes no library API - `CloudMetricsExtension` is loaded as a jar dropped into
`Constraints.EXTENSIONS_PATH` (or picked up the same way by `shell/test-bootstrap.sh`), not called
directly from Java. Build it alongside a bootstrap jar built from the same commit:

```
mvn -pl cloud-driver-extensions/cloud-driver-extensions-metrics -am package
```

Point a Prometheus `scrape_configs` job at `http://<host>:9404/metrics` (or whatever `"metrics-port"`
is configured to) once deployed.

# Configuration

All environment-specific values live in two gitignored JSON files under a configuration directory
next to the running backend process — never commit real values found in either file.

| File | Holds |
|---|---|
| `postgres-database.json` | Live Postgres connection credentials |
| `configuration.json` | JWT signing key, REST API bind settings, SMTP credentials, feature-specific limits (see table below) |

## `configuration.json` keys

| Key | Type | Default if unset | Required for |
|---|---|---|---|
| `jwt-signing-key` | string | REST API start is skipped (with a warning) if unset | JWT-authenticated REST API |
| `rest-server-port` | int | **required** — startup fails if unset | REST API |
| `rest-server-bind-host` | string | `0.0.0.0` (every interface) | REST API — set to `127.0.0.1` when fronted by a TLS-terminating reverse proxy |
| `smtp-host` | string | Falls back to logging verification codes instead of e-mailing them | Outgoing verification e-mails |
| `smtp-port` | int | — | SMTP sending |
| `smtp-username` | string | — | SMTP sending |
| `smtp-password` | string | — | SMTP sending (secret) |
| `smtp-from-address` | string | — | SMTP sending |
| `cloud-user-max-bytes-to-upload` | long | **1 MiB (strict)** if unset — not unlimited | Per-account upload quota |
| `metrics-port` | int | `9404` | Metrics endpoint |
| `metrics-bind-host` | string | `127.0.0.1` (loopback-only) | Metrics endpoint |
| `trust-proxy-headers` | boolean | `false` | Rate-limit identity resolution behind a reverse proxy |
| `auth-rate-limit-max-requests` | int | `10` | Per-IP auth rate limit |
| `auth-rate-limit-window-seconds` | long | `300` | Per-IP auth rate limit window |
| `trash-retention-days` | int | `30` | Trash purge scheduler |
| `aws-kms-region` | string | — | Production key encryption (AWS KMS) |
| `aws-kms-key-id` | string | — | Production key encryption (AWS KMS) |
| `aws-s3-region` | string | — | S3-backed file content storage |
| `aws-s3-bucket` | string | Unset → S3-backed storage disabled, files stored inline | S3-backed file content storage |
| `aws-s3-key-prefix` | string | `""` | S3-backed file content storage (optional) |
| `intelligence-shared-secret` | string | **required** — the extension refuses to load without it | Semantic search (secret) |
| `intelligence-host` | string | `127.0.0.1` | Semantic search |
| `intelligence-port` | int | `8600` | Semantic search |
| `intelligence-timeout-seconds` | long | `30` | Semantic search |
| `intelligence-max-bytes` | long | 100 MiB | Semantic search — files above this are left un-indexed |

## Notes

- `cloud-user-max-bytes-to-upload` is the one key here that does **not** fail safe when left
  unset — every account gets a strict 1 MiB quota until it's set explicitly. Set it deliberately on
  first deploy if a different limit (or effectively no limit) is intended.
- AWS credentials themselves (for both KMS and S3) are never read from `configuration.json` — they
  resolve through the AWS SDK's own default credential provider chain (environment, shared config
  file, instance role, etc.), deliberately keeping a third place a secret could be committed by
  mistake out of the picture.
- `trust-proxy-headers` is only safe to enable behind a deployment topology where exactly one
  trusted reverse-proxy hop sits in front of the backend and no client can reach it directly.
  Enabling it otherwise lets a client spoof its own rate-limit identity.
- Widening `metrics-bind-host` beyond loopback is a real access-control decision — the metrics
  endpoint carries no authentication of its own.
- `intelligence-shared-secret` must match `CLOUD_DRIVER_INTELLIGENCE_SECRET` on the Python
  service. It is the one `intelligence-*` key with no default, deliberately: an extension that
  authenticated with a well-known constant would be worse than one that refuses to load. A
  missing or blank value disables only semantic search, exactly as any other extension's load
  failure disables only itself.
- `intelligence-max-bytes` defaults to the value the design document specifies, but is worth
  lowering deliberately: content travels base64-encoded in a JSON body (~1.37x), so the default
  permits a ~137 MiB request for a large binary that will almost certainly yield nothing
  embeddable anyway. A value in the low tens of MiB is more proportionate.
- This table does not yet cover every key the backend reads — the `clamav-*`,
  `content-scan-max-bytes`, `api-rate-limit-read-*`, `file-versioning-*`, `aws-ses-*` and
  `presigned-upload-ticket-retention-hours` keys are documented in `CLAUDE.md` but not here.

# CloudDriver — System Requirements

Everything an operator needs to have provisioned/configured before `cloud-driver-bootstrap` will
start and run correctly.

```text
<working-dir>/
├── cloud-driver-bootstrap-<version>.jar
├── cloud-driver/                  <- Constraints.CONFIGURATION_PATH
│   ├── postgres-database.json     <- required
│   ├── configuration.json         <- required
│   ├── redis-database.json        <- optional (absent = single-instance, in-process fallbacks)
│   └── backup/                    <- created by cloud-driver-extensions-backup, holds its archives
├── extensions/                    <- Constraints.EXTENSIONS_PATH (drop *.jar here)
└── upload-scratch/                <- created automatically, scratch space for in-flight uploads
```

The JVM must be started **from this directory** (`cd` into it first) — extension discovery and
every config-file path are resolved relative to `user.dir`, not the jar's own location.

It must also be started **with a real TTY on stdin**. `DefaultCloudDriver.setInstance`
unconditionally builds the operator console with jline (`TerminalBuilder.builder().system(true)
.dumb(false)`), which throws — killing the process during startup — when stdin is not a terminal.
That is why `shell/start-cloud.sh` launches the jar inside a detached `screen` session: a plain
`nohup java -jar …`, a pipe, a cron job, or a bare `systemd` unit with no pty will not start.

---

## 1. Build-time requirements

| Requirement | Version | Notes |
|---|---|---|
| JDK | **21** | Every module's `maven.compiler.source`/`target`. No newer/older version is supported. |
| Maven | any recent 3.x | No Maven wrapper (`mvnw`) exists in this repo — use a locally installed `mvn`. |
| GitHub Packages **read** access to `linoalessio/database-driver-v2` | `database-driver-api`/`database-driver-plugin` `1.3.16` | **Every module depends on this external artifact group.** It is not on Maven Central — a fresh `~/.m2` with no cached copy will 404 without a GitHub Personal Access Token (`read:packages` scope) configured as a `<server>` entry in `~/.m2/settings.xml` under id `database-driver-github` (matching the `<repositories>` block in the root `pom.xml`). Reading from GitHub Packages requires authentication even though the package itself isn't private-in-the-usual-sense. |
| Recommended IntelliJ/local setup | — | Root `pom.xml` also declares a `github` `<distributionManagement>` target for *publishing* this repo's own artifacts — irrelevant unless cutting a release (`shell/release-and-package.sh`), not needed just to build/run. |

Client modules (only needed if building those specific pieces — not needed to run the server):

| Module | Toolchain |
|---|---|
| `cloud-driver-platforms-desktop` | Gradle (wrapper bundled, pinned 9.7.1), Kotlin 2.1.0, JDK 21. Resolves `cloud-driver-multiplatform-java` via `mavenLocal()` — build/`mvn install` that module first. |
| `cloud-driver-platforms-mobile` | Full Xcode (not just Command Line Tools) + `xcodegen` (`brew install xcodegen`). iOS 17.0+ target. |
| `cloud-driver-multiplatform-python` | Python 3.10+, `pip install -e ".[dev]"`. |
| `cloud-driver-installer` | Python 3.10+ **with `tkinter`** (Debian/Ubuntu: `apt install python3-tk` — the GUI prints that hint and exits 1 if `import tkinter` fails), then `pip install -e ".[dev]"`. Runtime dependencies are `paramiko>=3.4,<4` and `boto3>=1.34,<2`. It runs on the **operator's** machine, never on the server, and needs the reactor already built (`mvn clean install`) because it uploads the jars from the checkout. Launch with `cloud-driver-installer` or `python -m cloud_driver_installer`. |

---

## 2. Databases

### 2.1 PostgreSQL — **required**, the system of record

The entire persistence layer (`database-driver-plugin`, an external artifact — not part of this
repo) is Postgres-only. There is no supported alternative database backend as currently shipped.

- **Version**: no hard-enforced minimum. The schema this driver creates per entity type is
  intentionally trivial (`CREATE TABLE "<EntityName>" (id TEXT, data BYTEA)`, one table per
  `Serialized` entity class, auto-created on first use) — any reasonably modern PostgreSQL (10+)
  will work. No extensions required.
- **`LISTEN`/`NOTIFY`** support is used by `cloud-driver-extensions-watcher` for change
  notifications — this is a core, always-available Postgres feature, not a special grant.
- **Full-text search** uses built-in `tsvector`/GIN, so no Postgres extension needs installing.
  `cloud-driver-extensions-search` creates and owns one non-entity table,
  `cloud_driver_search_index`, itself — the single table in the database that is not
  `id TEXT, data BYTEA`-shaped and the single one holding plaintext (file names and
  content-derived lexemes; a deliberate, documented trade — see
  [security.md](security.md)). It is rebuildable derived state and is skipped by the backup job.
- **Schema/table creation is fully automatic** — do **not** hand-create tables. The application
  creates every table itself the first time an entity of that type is stored. What you *do* need
  to create by hand:
  1. A dedicated database (any name — the connection string's `database` field points at it).
  2. A dedicated role/user with, at minimum: `CONNECT` on the database, and `CREATE`/`SELECT`/
     `INSERT`/`UPDATE`/`DELETE` on its `public` schema. Simplest safe option: make this role the
     **owner** of the database, so there's no risk of a missing grant surfacing later as a runtime
     `SQLExecution` failure (which fails *silently to stderr*, not as a thrown exception, so a
     missing grant can otherwise go unnoticed).
- **Network reachability**: the application host must be able to reach this database's host:port.
  On the reference deployment (`cloud_driver`) Postgres is co-located on the same box as the app — this
  matters in code: `CloudBootstrap` deliberately wires an always-`true` `ConnectivityChecker`
  instead of the real internet-probing default, on the assumption the DB is local. If your
  Postgres instance is **not** co-located, revert that (`CloudBootstrap.ALWAYS_AVAILABLE_CONNECTIVITY_CHECKER`)
  or uploads may silently misreport success during a real network blip.
- **Credentials file**: `<working-dir>/cloud-driver/postgres-database.json` — **required**, no
  fallback; `CloudBootstrap.initiateCloudDriver()` calls `.orElseThrow()` on a missing/unparsable
  file, crashing the whole process at startup. Exact shape (field names confirmed against the live
  deployment):

  ```json
  {
    "address": "<hostname-or-ip>",
    "userName": "<postgres-role-name>",
    "password": "<postgres-role-password>",
    "port": 5432,
    "database": "<database-name>",
    "fileRepository": "Unknown"
  }
  ```

  `fileRepository` is a field the external `database-driver` library expects present — the
  reference deployment leaves it as the literal string `"Unknown"`; its exact purpose isn't
  documented in this repo (it belongs to the external `database-driver-api` artifact).
  **Never commit this file** — `cloud-driver/postgres-database.json` is gitignored.

### 2.2 Redis — **optional**, and never a source of truth

Redis is the only other data store the application talks to, and it is entirely optional: absent,
malformed credentials, or an unreachable server all degrade to in-process, single-instance
behavior rather than failing.

- **Credentials file**: `<working-dir>/cloud-driver/redis-database.json` — the same
  `address`/`userName`/`password`/`port`/`database`/`fileRepository` shape as
  `postgres-database.json`. Missing file → Redis support simply stays off, silently and by
  design. **Never commit this file** — it is gitignored.
- **What it holds**: rate-limit counters, webhook delivery history, once-per-window scheduler
  locks, and pending-upload metadata for cross-instance visibility.
- **What it must never hold**: file content, file names, or any other user data. Everything in
  Redis is either a counter, a lock, or an identifier — losing the whole instance costs a rate
  limit window and some delivery history, nothing more.
- **Deployment**: bind it to loopback and set a password (`provision-root-server.sh` does both).
  It is not reached across the network in the reference deployment.

### 2.3 No other database is used

No MongoDB, MySQL/MariaDB, SQLite, Elasticsearch, etc. is a dependency of this application, even
if one happens to be running alongside it on a shared host.

---

## 3. `configuration.json` — every key the running application reads

Location: `<working-dir>/cloud-driver/configuration.json` — **required** (a completely empty
`{}` is technically loadable, but see below for which keys are then effectively mandatory).
Re-read from disk on every access (`CloudDriver#getConfiguration()`), never cached — a value can be
changed without restarting, for whatever reads it fresh each time.

The key-by-key reference — type, default and notes for every key the backend reads — is
[configuration.md](configuration.md). This section covers the same keys from the operator's angle:
what has to be decided **before** first start, and what breaks when it is missing.

**⚠️ Never commit this file** — gitignored (`cloud-driver/configuration.json`) since it holds real
secrets (JWT signing key, SMTP password if SMTP is used).

### 3.1 Required — startup fails or a core feature silently breaks without these

| Key | Type | Consumed by | Effect if missing |
|---|---|---|---|
| `aws-kms-region` | string | `CloudBootstrap.initiateCloudDriver()` | **Crashes the whole process at boot** (`NullPointerException` from `JsonDocument#getString` on a missing key) — `AwsKmsKeyEncryptionService` is unconditionally constructed, no fallback exists in current code. |
| `aws-kms-key-id` | string | same | same — crashes at boot. |
| `jwt-signing-key` | string, `openssl rand -base64 32` | `CloudRestExtension.startRestApi` | Not fatal to the process, but the entire REST API/JWT auth layer is skipped (logged warning) — every client-facing route stays down. |
| `cloud-server-max-bytes-available` | long (bytes) | `CloudUserCommand`/`StatisticsCommand` (terminal) | Not checked at boot, but **`cloudUser limit`** and **`statistics`** (alias `stats`) throw `NullPointerException` the moment they're run without it set — no default exists. |
| `rest-server-port` | int | `CloudRestExtension.onLoading()` | No default and no guard: a missing key throws `NullPointerException` out of the extension's load, so the whole REST API (and with it every client-facing route) fails to start. The rest of the process boots normally — `CloudRestExtension.onException` stops the factory and logs `SEVERE`. Reference deployment uses `8080`. |

### 3.2 Optional, with a real default

| Key | Default | Consumed by |
|---|---|---|
| `rest-server-bind-host` | **`0.0.0.0`** (every interface) when the value is blank — *not* loopback | `CloudRestExtension` — Javalin serves plain HTTP, so a non-loopback bind exposes every request, JWTs included, unencrypted to whatever network can reach it. A non-loopback value only logs a startup **warning**; it never refuses to start. Production shape is `"127.0.0.1"` behind Caddy (§6) |
| `cloud-user-max-bytes-to-upload` | `1048576` (1 MiB) — **strict, not unlimited** | `CloudUser` |
| `trash-retention-days` | `30` | `TrashPurgeScheduler`, `CloudUserService` (purge-eligibility timestamps) |
| `auth-rate-limit-max-requests` | `10` | `DefaultRestFactory` (`/auth/*` limiter) |
| `auth-rate-limit-window-seconds` | `300` | same |
| `trust-proxy-headers` | `false` | `DefaultRestFactory` (rate-limit identity via `X-Forwarded-For`) — only enable behind a genuinely trusted single reverse-proxy hop |
| `trusted-proxy-addresses` | unset → falls back to `trust-proxy-headers` | `DefaultRestFactory` — comma-separated list of reverse-proxy peer addresses whose `X-Forwarded-For` may be believed. **Authoritative when present and non-blank**; only when it is absent/blank does the older boolean `trust-proxy-headers` apply, and then only for loopback peers (`127.0.0.1`, `::1`, `0:0:0:0:0:0:0:1`). Neither key set means forwarded-for headers are never believed |
| `api-rate-limit-read-max-requests` | `300` | `DefaultRestFactory` (general API limiter, `GET`/`HEAD` only — there is no separate write limiter; writes are bounded by the upload quota and the request-size ceiling instead) |
| `api-rate-limit-read-window-seconds` | `60` | same |
| `public-download-rate-limit-max-requests` | `30` | `DefaultRestFactory` (anonymous public-link download limiter, keyed on client address + link token) |
| `public-download-rate-limit-window-seconds` | `60` | same |
| `metrics-port` | `9404` | `CloudMetricsExtension` |
| `metrics-bind-host` | `127.0.0.1` | `CloudMetricsExtension` |
| `clamav-host` | `"localhost"` | `CloudScanExtension` |
| `clamav-port` | `3310` | `CloudScanExtension` |
| `clamav-timeout-seconds` | `30` | `CloudScanExtension` |
| `content-scan-max-bytes` | `104857600` (100 MiB) | `CloudScanExtension` |
| `thumbnail-max-source-bytes` | `67108864` (64 MiB) | `CloudThumbnailsExtension` (largest file a preview is generated from) |
| `thumbnail-max-decoded-pixels` | `50000000` | `CloudThumbnailsExtension` (raster budget shared by the image decoder and the PDF renderer) |
| `thumbnail-render-timeout-seconds` | `20` | `CloudThumbnailsExtension` (a longer decode/render is abandoned) |
| `file-versioning-max-versions-per-file` | `10` | `FileVersionPurgeScheduler` |
| `file-versioning-retention-days` | `30` | `FileVersionPurgeScheduler` |
| `aws-s3-region` | unset → S3 disabled | `CloudBootstrap`/`CloudRestExtension` |
| `aws-s3-bucket` | unset → S3 disabled | same |
| `aws-s3-key-prefix` | `""` | same |
| `aws-ses-region` | unset → SES not tried | `CloudRestExtension` (**checked first**, before SMTP) |
| `aws-ses-from-address` | required alongside `aws-ses-region`, else falls through to SMTP/log-only | same |
| `smtp-host` | unset → falls through to log-only (only reached if SES isn't configured) | `CloudRestExtension` |
| `smtp-port` | required alongside `smtp-host`, else log-only | same |
| `smtp-username` | required alongside `smtp-host`, else log-only | same |
| `smtp-password` | required alongside `smtp-host`, else log-only | same |
| `smtp-from-address` | required alongside `smtp-host`, else log-only | same |
| `aws-ses-configuration-set` | unset → no configuration set named on sends | `CloudRestExtension` — SES bounce/complaint event routing; only set once the set actually exists in that account/region, or every send fails |
| `aws-s3-max-concurrency` | `50` | `S3ObjectStorageService` — concurrent-connection cap of the shared async S3 client. With several instances against one bucket, size it per instance, not per process |
| `presigned-upload-ticket-retention-hours` | `6` | `PendingPresignedUploadPurgeScheduler` — how long an unfinished **single-`PUT` presigned ticket** survives before it is dropped and its orphaned object deleted. Comfortably longer than the presigned URL's own 15-minute expiry. S3 deployments only |
| `resumable-upload-session-retention-hours` | `72` | `PendingPresignedUploadPurgeScheduler` — how long an idle resumable multipart **session** survives, measured from its last activity rather than its creation, before the row is aged out and its multipart upload aborted (S3 bills for uploaded parts until then). Deliberately a separate, longer window than `presigned-upload-ticket-retention-hours`: a session can legitimately span days of a large file moving over a slow link. S3 deployments only |
| `webhook-dispatch-pool-size` | `4` | `DefaultWebhookService` — first-attempt delivery concurrency; queue depth is observable as the `cloud_driver_webhook_dispatch_queue_depth` gauge |
| `intelligence-shared-secret` | **no default** — `cloud-driver-extensions-intelligence` refuses to load without it | The semantic-search bridge (secret) |
| `intelligence-host` | `127.0.0.1` | same |
| `intelligence-port` | `8600` | same |
| `intelligence-timeout-seconds` | `30` | same |
| `intelligence-max-bytes` | `104857600` (100 MiB) | same — files above this are left un-indexed. Worth lowering: content travels base64-encoded (~1.37x), so the default permits a ~137 MiB request body |

Example, using AWS SES for email delivery (the current setup):

```json
{
  "rest-server-port": "8080",
  "rest-server-bind-host": "127.0.0.1",

  "metrics-port": 9404,
  "metrics-bind-host": "127.0.0.1",

  "cloud-server-max-bytes-available": "274877906944",
  "cloud-user-max-bytes-to-upload": "1073741824",

  "aws-ses-region": "eu-central-1",
  "aws-ses-from-address": "webmaster@example.com",

  "aws-kms-region": "eu-central-1",
  "aws-kms-key-id": "alias/REPLACE-ME",

  "aws-s3-region": "eu-central-1",
  "aws-s3-bucket": "your-bucket-name",

  "jwt-signing-key": "REPLACE-ME (openssl rand -base64 32)"
}
```

No `smtp-*` keys are needed at all once SES is configured — `smtp-host` simply stays unset and the
SMTP path is never reached (see §4.2 for the exact fallback order).

---

## 4. External services

### 4.1 AWS — **required** (KMS), optional (S3), optional but now preferred (SES)

An AWS account is mandatory as currently shipped: `CloudBootstrap` unconditionally builds
`AwsKmsKeyEncryptionService` with no alternative wired in (the codebase has three other
`KeyEncryptionService` implementations — in-memory, file-backed, database-backed — but all three
are explicitly documented "not for production," and none is what `CloudBootstrap` actually
constructs today).

- **AWS KMS** (required)
  - Create a symmetric Customer Master Key (CMK).
  - IAM permissions the running identity needs on that key: `kms:Encrypt`, `kms:Decrypt` (always),
    plus `kms:CreateKey` only if `KeyEncryptionService#rotate()` will ever be invoked — `rotate()`
    provisions a genuinely new symmetric CMK rather than rotating the existing key's backing
    material. `cloud-driver-installer` additionally grants `kms:DescribeKey` in the least-privilege
    policy it writes for the runtime identity, so a mistyped key id fails loudly rather than on the
    first wrap; the backend itself never calls it. Nothing else is granted — no
    `ScheduleKeyDeletion`, no IAM — and that absence is the deletion guard for the KEK every stored
    row depends on.
  - Credentials are resolved via the **AWS SDK's own default credential provider chain**
    (`~/.aws/credentials`/`~/.aws/config`, environment variables, an EC2/ECS instance role, etc.)
    — **deliberately never read from `configuration.json`**, only the region/key-id are. Set these
    up on the host the JVM actually runs on.
  - Config: `aws-kms-region`, `aws-kms-key-id` (both required, see §3.1).

- **AWS S3** (optional — only activates once `aws-s3-bucket`/`aws-s3-region` are both set)
  - Powers S3-backed `StoredFile` content, presigned direct-to-client upload/download, and the
    `MigrateToS3Command`/`TrashPurgeScheduler` codepaths that clean up S3 objects.
  - Create a bucket. Required IAM permissions, split by resource:
    - on `arn:aws:s3:::<bucket>/*` — `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`,
      `s3:AbortMultipartUpload`, `s3:ListMultipartUploadParts` (the resumable-upload service pages
      through `ListParts` to resume a session — without it every resume is `AccessDenied`).
    - on `arn:aws:s3:::<bucket>` — `s3:ListBucket`, `s3:ListBucketMultipartUploads`.
  - Same credential-resolution rule as KMS — default provider chain, not `configuration.json`.
  - Config: `aws-s3-region`, `aws-s3-bucket`, optional `aws-s3-key-prefix` (default `""`).
  - Presigned uploads sign with SSE-S3 (`AES256`), not SSE-KMS — no extra `kms:GenerateDataKey`
    grant needed for that specific path.

- **AWS SES v2** (optional — the preferred email transport, checked before SMTP; see §4.2)
  - Backed by `SesEmailSender` (`cloud-driver-auth`, `software.amazon.awssdk:sesv2`) — sends via
    SES's `SendEmail` raw-message API, so the outgoing message (HTML + plain-text + inline logo) is
    byte-for-byte identical to what `SmtpEmailSender` would have sent; only the transport differs.
  - Required IAM permissions for the running identity: `ses:SendEmail` **and `ses:SendRawEmail`** —
    `SesEmailSender` calls SESv2 `SendEmail` with *raw-message* content (the MIME message
    `SmtpEmailSender` would have sent, serialized via `MimeMessage#writeTo`), which IAM authorizes
    under `ses:SendRawEmail`.
  - The `configuration.json` `aws-ses-from-address` value **must already be a verified sending
    identity** (a verified email address, or a verified domain) in that AWS account/region — SES
    rejects `SendEmail` outright otherwise.
  - **SES sandbox mode**: a brand-new SES account starts in the sandbox — capped at 200 emails/day,
    1 email/second, and **every recipient address must also be individually verified**, not just
    the sender. Registration/password-reset/email-change codes will only reach verified test
    addresses until AWS grants "production access" (a support-case request, usually approved within
    a day). Don't be surprised if real users can't receive mail until this is done.
  - Same credential-resolution rule as KMS/S3 — the SDK's default provider chain, never
    `configuration.json`.
  - Config: `aws-ses-region`, `aws-ses-from-address` (both required together, see §3.2).

### 4.2 Email delivery — three-tier fallback: AWS SES → SMTP → log-only

Verification codes for registration, password reset, and email change go through whichever of
these `CloudRestExtension.buildEmailSender()` resolves, tried **in this order**:

1. **AWS SES**, if `aws-ses-region`/`aws-ses-from-address` are both set — see §4.1 for the AWS-side
   setup. Preferred: no long-lived SMTP credential sits in `configuration.json` at all, only a
   region and a verified sending address.
2. **SMTP**, if SES isn't configured but `smtp-host`/`smtp-port`/`smtp-username`/`smtp-password`/
   `smtp-from-address` are all set and non-blank (`jakarta.mail`/Angus Mail, STARTTLS). Any
   standard SMTP relay works here — this is what the reference deployment used before switching to
   SES.
3. **`LoggingEmailSender`**, if neither is configured — prints the verification code to the
   server's own log instead of emailing it. The process still starts and every auth flow still
   technically works if you can read the server log, but this is explicitly "not suitable for
   production" per the code's own warning.

Only one of these is ever active per deployment — SES, once correctly configured, is never
downgraded to SMTP even if `smtp-*` keys also happen to be present in `configuration.json`.

### 4.3 ClamAV (`clamd`) — optional extension, required only if `cloud-driver-extensions-scan` is deployed

**A genuinely separate operating-system process, not a library dependency** — install `clamd` (and
`freshclam` to keep its virus-definition database current) on the same host, or a host reachable
over TCP from it. If the `cloud-driver-extensions-scan-*.jar` isn't dropped into `extensions/` at
all, none of this applies and every upload is simply never scanned.

Setup notes (all confirmed by actually installing and testing this against a real `clamd` on the
reference deployment, 2026-09-08):

1. Install `clamav-daemon` + `clamav-freshclam` (e.g. `apt install clamav-daemon
   clamav-freshclam` on Debian/Ubuntu). Let `freshclam` finish its first virus-database download
   before expecting real scans to succeed.
2. **`clamd.conf`'s `TCPSocket`/`TCPAddr` directives alone do nothing under a systemd
   socket-activated `clamav-daemon.socket` unit** (Debian/Ubuntu's own packaging) — `clamd` only
   opens sockets systemd actually hands it. A TCP listener must be added to the **socket unit
   itself** via a drop-in:
   ```ini
   # /etc/systemd/system/clamav-daemon.socket.d/tcp.conf
   [Socket]
   ListenStream=127.0.0.1:3310
   ```
   then `systemctl daemon-reload && systemctl restart clamav-daemon.socket clamav-daemon`.
3. **Do not also add an IPv6 (`[::1]:PORT`) `ListenStream` alongside the IPv4 one** — confirmed
   empirically that `clamd` 1.4.3 crash-loops (`"ERROR: TCP: Received more than two file
   descriptors from systemd"`) if given two TCP sockets via activation at once. IPv4-only
   (`127.0.0.1`) is sufficient: the default `clamav-host` value is the string `"localhost"`
   (`CloudScanExtension.DEFAULT_CLAMD_HOST`), and Java's `InetSocketAddress`/`InetAddress` default resolution behavior
   (`preferIPv6Addresses=false`) prefers the IPv4 record for it regardless of what the OS's own
   `/etc/hosts`/NSS order says — confirmed by compiling and running a one-off test with the exact
   JVM in use. If in doubt, pin `"clamav-host": "127.0.0.1"` explicitly in `configuration.json`.
4. **Raise `clamd`'s own size limits above this application's scan ceiling.** Debian's default
   `StreamMaxLength`/`MaxFileSize` (25M) sit well under the app's own `content-scan-max-bytes`
   default (100 MiB) — a file in that gap would be sent to `clamd` and rejected as a scan-level
   error, triggering the app's retry-then-fail-open path (3 attempts, then the file is marked
   `CLEAN` with a `WARNING` log without ever actually having been scanned) for a size range the app
   itself considers scannable — a loud line in the server log is the only signal, so it is easy to
   miss rather than genuinely silent. Set
   `StreamMaxLength 128M`, `MaxFileSize 128M`, `MaxScanSize 300M` (or proportionally higher/lower
   if `content-scan-max-bytes` is overridden).
5. **Bind loopback-only, and mean it.** `clamd` has no authentication of its own — a `TCPSocket`
   with no `TCPAddr` (or a systemd `ListenStream=3310` with no address) binds every interface. On
   any host without its own firewall (confirmed to be the case on the reference deployment on that
   date — see §6 below), that exposes an unauthenticated malware scanner to the whole internet.
6. `systemctl enable clamav-daemon clamav-freshclam` so both survive a reboot.
7. Config: `clamav-host`/`clamav-port`/`clamav-timeout-seconds`/`content-scan-max-bytes`, all
   optional with sane defaults (see §3.2) — set `clamav-host` explicitly if `clamd` isn't reachable
   at literal `127.0.0.1`.
8. **Resource cost**: expect `clamd` to hold ~700 MB–1 GB resident once its signature database is
   loaded — factor this into RAM sizing (see §7) if scanning is enabled.

### 4.4 GitHub Packages (build/release only, not a runtime dependency)

Covered in §1 — needed to *build* the project (resolving `database-driver-api`/`-plugin`), and
separately (write access) to *publish* a release via `shell/release-and-package.sh`. The running
application never talks to GitHub Packages itself.

### 4.5 `cloud-driver-intelligence` (Python/FastAPI) — optional, required only if `cloud-driver-extensions-intelligence` is deployed

**A genuinely separate operating-system process, not a library dependency** — like `clamd`. It owns
the embedding model and the vector store; the backend only speaks HTTP to it.

- Python 3.10+ in its own virtualenv. Install with `pip install -e ".[embeddings,store]"` for a
  real deployment (the embedding backend pulls PyTorch, ~2 GB). The bare install still starts and
  still answers every call *successfully*: `POST /index` returns `204 No Content` without storing a
  vector and `POST /search` returns an empty list, so semantic search silently yields nothing
  rather than erroring (the 204 is deliberate — the Java bridge would otherwise retry every upload
  three times and log a give-up). `GET /health` — `embeddingsAvailable`, `persistentStore`,
  `encryptedStore`, `imageEmbeddingsAvailable` — is the only place that tells "the extra is not
  installed" apart from "nothing matched".
- Reachable at `intelligence-host`/`intelligence-port` (defaults `127.0.0.1`/`8600`).
  `cloud-driver-intelligence/deploy/cloud-driver-intelligence.service` binds `uvicorn` to
  `127.0.0.1:8600` deliberately: the service has no transport security of its own.
- **The shared secret must match on both sides**: `intelligence-shared-secret` in
  `configuration.json` must equal `CLOUD_DRIVER_INTELLIGENCE_SECRET` in the Python process's
  environment (the unit reads it from a root-owned `0600` `/etc/cloud-driver-intelligence.env`).
  The backend sends it as the `X-Internal-Secret` header; a mismatch is a 401 on every call. The
  extension refuses to load at all if the key is absent or blank.
- The service is configured **entirely from environment variables**, never from `cloud-driver`'s
  own JSON files — see [configuration.md](configuration.md) and [deployment.md](deployment.md).
- Budget 2 GB of RAM for it (its unit sets `MemoryMax=2G`) — see §7.

---

## 5. Filesystem / disk

| Path (relative to `user.dir`) | Purpose | Created by |
|---|---|---|
| `cloud-driver/` | Config + credentials files | Operator (must exist before first start) |
| `cloud-driver/backup/` | Database backup archives (`cloud-driver-backup-<yyyyMMdd_HHmmss>.zip`) plus a `.staging/` working directory during a run | Application, automatically — only if `cloud-driver-extensions-backup` is deployed |
| `extensions/` | Extension jars, scanned on boot | Application, automatically if absent; the operator drops `cloud-driver-extensions-*.jar` files into it |
| `upload-scratch/` | Per-upload scratch files, streamed-then-deleted | Application, automatically |

Every extension jar must come from the same build as the bootstrap jar beside it, and the folder
must hold exactly **one** jar per extension: two jars claiming the same extension name abort
startup before any extension runs, so the whole process comes up dead. That is why
`shell/deploy-cloud.sh` prunes stale release jars from the remote folder before uploading, and why
`cloud-driver-installer` refuses to upload an extension jar whose file name does not carry the
bootstrap's version.

No fixed minimum disk size is documented, but note: uploads are streamed through
`upload-scratch/` rather than buffered in heap, so scratch-disk headroom should comfortably exceed
the largest single expected upload (`MAX_REQUEST_SIZE_BYTES`, 256 MB by default) at any given
concurrency level.

Size the disk for backups too when that extension is deployed: a cycle runs every 3 days, exports
every `id`/`data`-shaped table into a compressed archive, and the seven most recent archives are
retained (older ones are deleted), so the steady state is roughly seven compressed copies of the
encrypted corpus plus one cycle's staging directory. Off-site copying of those archives to a
dedicated bucket is a cron line outside the JVM — see [deployment.md](deployment.md).

---

## 6. Networking & security

### Ports

| Port | Default bind | What | Public? |
|---|---|---|---|
| `rest-server-port` (reference deployment: `8080`) | `rest-server-bind-host` — **`0.0.0.0` in code** when left blank | Javalin REST + WebSocket API, plain HTTP | **No** — set `127.0.0.1` and front it with the reverse proxy |
| `80`/`443` | all interfaces | Caddy: TLS termination, reverse-proxied to the REST port | Yes |
| `metrics-port` (`9404`) | `metrics-bind-host`, `127.0.0.1` | Prometheus text exposition, on a second Javalin instance | **No** — deliberately unauthenticated |
| `clamav-port` (`3310`) | `127.0.0.1`, via the systemd socket drop-in (§4.3) | `clamd` scan socket | **No** — `clamd` has no authentication of its own |
| `intelligence-port` (`8600`) | `127.0.0.1` (`uvicorn --host 127.0.0.1`) | `cloud-driver-intelligence` FastAPI service (§4.5) | **No** — guarded only by the shared secret |
| `5432` (`postgres-database.json`) | as configured | PostgreSQL | **No** |
| `6379` (`redis-database.json`) | `127.0.0.1` | Redis | **No** |
| `22` | all interfaces | SSH — required by every deploy script below | Restricted |

- **This application does not manage its own firewall.** Confirmed the hard way on the reference
  deployment (2026-09-08): the box had **no firewall at all** (`ufw` not installed, `iptables`
  chains empty, default-`ACCEPT`) — meaning anything bound to `0.0.0.0` is reachable from the
  entire internet by default. The *application* still manages no firewall, but both provisioning
  paths now install and enable one: `shell/provision-root-server.sh` installs `ufw` with its base
  packages (step 1/9) and configures it in step 3/9, while `cloud-driver-installer`'s Firewall step
  `apt install`s it itself when it is absent. Both then allow SSH plus `80/tcp` and `443/tcp`, set
  `default deny incoming`/`default allow outgoing`, and enable it. The installer
  additionally opens the REST port when no reverse proxy is configured and the REST bind host is
  not loopback (otherwise a proxy-less deployment is firewalled off from its own clients), and it
  refuses to enable `ufw` at all if this session's SSH port is not in the allow list. Bind every
  service that has no authentication of its own (`clamd`'s TCP socket, the metrics endpoint, the
  intelligence service) strictly to `127.0.0.1`/loopback regardless — the firewall is the second
  line, not the first.
- **SSH access for deployment**: `shell/deploy-cloud.sh`, `shell/deploy-homepage.sh`,
  `shell/provision-root-server.sh`, and `cloud-driver-intelligence/deploy/install-on-server.sh` all
  shell out to `ssh`/`scp` against the `cloud_driver` host alias — a passwordless, key-based root
  login must already exist in `~/.ssh/config` (`HostName`, `User root`, `IdentityFile` pointing at a
  private key whose public half is in the server's `/root/.ssh/authorized_keys`) before any of
  these scripts will work; none of them prompt for a password or provision the key itself. The
  alias has been renamed before (`strato` → `netcup` → `cloud_driver`) — if it's renamed again,
  update the hardcoded `REMOTE_HOST` in `shell/deploy-cloud.sh`, `shell/deploy-homepage.sh` and
  `cloud-driver-intelligence/deploy/install-on-server.sh`. `shell/provision-root-server.sh` needs
  no edit: it takes the host or alias as its first argument (`REMOTE_HOST="${1:-}"`), so it is run
  as `./provision-root-server.sh cloud_driver [api-domain]`. `cloud-driver-installer` is the
  alternative for a box that has neither yet: it authenticates with a password if need be, and can
  install your public key and write the alias itself (see
  [deployment.md](deployment.md#gui-installer-cloud-driver-installer)).
- **`rest-server-bind-host`** is typically `127.0.0.1` on the reference deployment — a reverse
  proxy (the reference deployment uses **Caddy**) terminates TLS on 80/443 and forwards to it. This
  is an operational choice, not a hard code requirement, but is the realistic way to expose the API
  safely over HTTPS to real clients (both `cloud-driver-platforms-desktop`/`-mobile` default to
  `https://` URLs).
- **`trust-proxy-headers`** (see §3.2) must stay `false` unless that reverse proxy is the *only*
  way to reach the app — enabling it without a genuinely trusted single hop lets a client spoof its
  own rate-limit identity via `X-Forwarded-For`.
- **AWS credentials, SMTP password (if used), JWT signing key, and Postgres password** are all real
  secrets — none of `cloud-driver/*.json` is ever committed to git (`.gitignore` excludes the whole
  `cloud-driver/` directory by individual filename).

---

## 7. System resources (observed on the reference deployment)

Not a hard requirement, but grounded in two real incidents hit on the reference deployment worth
planning around:

- **JVM heap**: launch with an explicit `-Xmx` (the reference deployment uses `-Xmx6g` on a 7.7 GB
  box). `shell/start-cloud.sh` passes `JVM_XMX` (default `6g`) as `-Xmx`; the per-box value is
  overridden in a sibling `start-cloud.env` — written by `cloud-driver-installer` alongside
  `SCREEN_SESSION` and `SCREEN_LOG_FILE` — which the script sources before anything else, so the
  setting survives `deploy-cloud.sh` re-uploading the script. See
  [configuration.md](configuration.md) for that file. Without an explicit `-Xmx`, JVM ergonomics
  can cap the heap far below what's actually free — a real `OutOfMemoryError` was hit on exactly
  that gap persisting a ~195 MB file with no `-Xmx` set. Both root causes behind the two historical incidents have since
  been fixed, but neither fix removes the need to size the heap:
  - Uploads no longer hold several full copies of a file in heap. Above 32 MiB the request body
    lands in a scratch file and checksumming, encryption and the S3 write all stream off it, so
    heap use is O(chunk size). Below that threshold the in-memory path is kept deliberately (it is
    what powers compression and text extraction), so a burst of concurrent mid-size uploads still
    needs headroom.
  - The persistence layer no longer caches every table's rows for the process's lifetime: table
    discovery at boot reads names only, and `StoredFile` — the one type whose rows can be large —
    is pinned to `CacheMode.NONE`. Every other type still defaults to `FULL`, so the resident
    floor grows with the size of the *metadata* corpus, just far more slowly than the ~3 GB that
    crash-looped boot under `-Xmx4g` in 2026-09-09 (see [troubleshooting.md](troubleshooting.md)).

  Re-check `-Xmx` as the corpus grows rather than assuming a one-time setting holds.
- **Swap**: the reference deployment adds a 4 GB swapfile as a kernel-OOM safety net — with the
  heap floor above, an unswapped box this size has little headroom left for spikes.
- **`clamav-daemon`**, once its signature database is loaded, resides at roughly 700 MB–1 GB —
  budget for this on top of the JVM's own `-Xmx` if content scanning is enabled.
- **Total**: the reference deployment runs on 7.7 GB RAM + 4 GB swap with `-Xmx6g` + `clamd` +
  Postgres all co-located, and headroom is genuinely tight — don't add further memory-hungry
  services to the same box without re-checking.
- **Sizing rule of thumb**: budget `-Xmx` above the total encrypted payload the database holds,
  then add ~1 GB for the JVM outside the heap, ~1 GB for a co-located Postgres, ~1 GB for `clamd`
  if content scanning is enabled, and **2 GB for `cloud-driver-intelligence` if it runs on the same
  box** — its systemd unit caps it at `MemoryMax=2G`, and PyTorch alone settles in the gigabyte
  range. Never go below `-Xmx2g`: under that, the in-memory upload path for files below 32 MiB
  cannot serve even a handful of concurrent uploads. Re-check as the corpus grows — this floor
  moves with the data, which is exactly what caused the 2026-09-09 boot crash-loop.
  (`cloud-driver-installer` derives its suggested `-Xmx` from exactly these overheads.)

---

## 8. Extension-by-extension requirement summary

Only `cloud-driver-bootstrap` itself (Postgres + AWS KMS, §2.1/§4.1) is non-optional to the
*backend*: it registers whatever jars `ExtensionFolderScanner` finds under `extensions/` and starts
cleanly with none present at all. In practice three are treated as required — `rest`, `watcher` and
`terminal`: `cloud-driver-installer` aborts the install when any of those three jars is missing
from the checkout and only warns about the other eight (and skips `scan`/`intelligence` entirely
unless ClamAV / the intelligence service are enabled). Everything else is an independent jar
dropped into `extensions/` — omit any of these entirely if the feature isn't wanted:

| Extension | Extra requirement beyond core |
|---|---|
| `cloud-driver-extensions-rest` | `jwt-signing-key`; AWS SES (preferred) or SMTP for real email delivery |
| `cloud-driver-extensions-watcher` | none beyond Postgres `LISTEN`/`NOTIFY` (always available) |
| `cloud-driver-extensions-terminal` | none |
| `cloud-driver-extensions-backup` | none (writes to local disk) |
| `cloud-driver-extensions-metrics` | none (self-contained Prometheus exporter) |
| `cloud-driver-extensions-scan` | **`clamd`**, see §4.3 |
| `cloud-driver-extensions-thumbnails` | none (uses the already-configured storage path) |
| `cloud-driver-extensions-versioning` | none |
| `cloud-driver-extensions-search` | none beyond Postgres — the index is a `tsvector`/GIN table the extension creates itself (see §2.1), falling back to an in-memory index if that fails |
| `cloud-driver-extensions-webhooks` | outbound internet access only (no third-party account) |
| `cloud-driver-extensions-intelligence` | the `cloud-driver-intelligence` Python service reachable at `intelligence-host`/`-port`, plus a matching `intelligence-shared-secret` on both sides — the extension refuses to load without the secret |

---

## 9. Quick checklist for a fresh deployment

`cloud-driver-installer` (a GUI, see
[deployment.md](deployment.md#gui-installer-cloud-driver-installer)) covers every box below,
including the AWS, DNS-verification and deploy steps. `shell/provision-root-server.sh` (see
[deployment.md](deployment.md#provisioning-a-new-root-server)) automates every OS-level box below (JDK 21, PostgreSQL, firewall, swap, clamd, Redis, Caddy,
directory layout, config-file scaffolding) in one idempotent run against a fresh root server — the
unchecked boxes are exactly what it deliberately leaves for you (AWS, DNS, the jar itself).

- [ ] JDK 21 + Maven installed; GitHub PAT with `read:packages` configured for `database-driver-v2`
- [ ] PostgreSQL database + dedicated owner role created
- [ ] AWS account: KMS CMK created, IAM credentials with `kms:Encrypt`/`kms:Decrypt` on the host
- [ ] (optional) S3 bucket + IAM permissions, if S3-backed storage is wanted
- [ ] (optional) AWS SES: sending identity verified, `ses:SendEmail`/`ses:SendRawEmail` IAM
      permissions, sandbox mode
      lifted (or test recipients individually verified) — **or** SMTP credentials as a fallback, if
      real email delivery is wanted
- [ ] (optional) `clamd` installed and TCP-reachable, if content scanning is wanted — remember the
      systemd socket-activation drop-in and the raised size limits (§4.3)
- [ ] `cloud-driver/postgres-database.json` written, gitignored, never committed
- [ ] `cloud-driver/configuration.json` written with at minimum `aws-kms-region`/`aws-kms-key-id`/
      `jwt-signing-key`/`cloud-server-max-bytes-available`
- [ ] `extensions/` populated with whichever extension jars are wanted
- [ ] Firewall/security group confirmed — do not assume the host has one by default
- [ ] Reverse proxy (TLS termination) in front of `rest-server-bind-host` for anything public-facing
- [ ] Passwordless root SSH key configured under the `cloud_driver` host alias in `~/.ssh/config` —
      required before any of `shell/deploy-cloud.sh`, `shell/deploy-homepage.sh`,
      `shell/provision-root-server.sh`, or `install-on-server.sh` (§6) will work
- [ ] `-Xmx` set explicitly when launching the jar

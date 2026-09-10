# CloudDriver — System Requirements

Everything an operator needs to have provisioned/configured before `cloud-driver-bootstrap` will
start and run correctly.

```
<working-dir>/
├── cloud-driver-bootstrap-<version>.jar
├── cloud-driver/                  <- Constraints.CONFIGURATION_PATH
│   ├── postgres-database.json     <- required
│   └── configuration.json         <- required
├── extensions/                    <- Constraints.EXTENSIONS_PATH (drop *.jar here)
└── upload-scratch/                <- created automatically, scratch space for in-flight uploads
```

The JVM must be started **from this directory** (`cd` into it first) — extension discovery and
every config-file path are resolved relative to `user.dir`, not the jar's own location.

---

## 1. Build-time requirements

| Requirement | Version | Notes |
|---|---|---|
| JDK | **21** | Every module's `maven.compiler.source`/`target`. No newer/older version is supported. |
| Maven | any recent 3.x | No Maven wrapper (`mvnw`) exists in this repo — use a locally installed `mvn`. |
| GitHub Packages **read** access to `linoalessio/database-driver-v2` | `database-driver-api`/`database-driver-plugin` `1.3.13` | **Every module depends on this external artifact group.** It is not on Maven Central — a fresh `~/.m2` with no cached copy will 404 without a GitHub Personal Access Token (`read:packages` scope) configured as a `<server>` entry in `~/.m2/settings.xml` under id `database-driver-github` (matching the `<repositories>` block in the root `pom.xml`). Reading from GitHub Packages requires authentication even though the package itself isn't private-in-the-usual-sense. |
| Recommended IntelliJ/local setup | — | Root `pom.xml` also declares a `github` `<distributionManagement>` target for *publishing* this repo's own artifacts — irrelevant unless cutting a release (`shell/release-and-package.sh`), not needed just to build/run. |

Client modules (only needed if building those specific pieces — not needed to run the server):

| Module | Toolchain |
|---|---|
| `cloud-driver-platforms-desktop` | Gradle (wrapper bundled, pinned 9.7.1), Kotlin 2.1.0, JDK 21. Resolves `cloud-driver-multiplatform-java` via `mavenLocal()` — build/`mvn install` that module first. |
| `cloud-driver-platforms-mobile` | Full Xcode (not just Command Line Tools) + `xcodegen` (`brew install xcodegen`). iOS 17.0+ target. |
| `cloud-driver-multiplatform-python` | Python 3.10+, `pip install -e ".[dev]"`. |

---

## 2. Databases

### 2.1 PostgreSQL — **required**, the only database the application itself talks to

The entire persistence layer (`database-driver-plugin`, an external artifact — not part of this
repo) is Postgres-only. There is no supported alternative database backend as currently shipped.

- **Version**: no hard-enforced minimum. The schema this driver creates per entity type is
  intentionally trivial (`CREATE TABLE "<EntityName>" (id TEXT, data BYTEA)`, one table per
  `Serialized` entity class, auto-created on first use) — any reasonably modern PostgreSQL (10+)
  will work. No extensions required.
- **`LISTEN`/`NOTIFY`** support is used by `cloud-driver-extensions-watcher` for change
  notifications — this is a core, always-available Postgres feature, not a special grant.
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
  On the reference deployment (`strato`) Postgres is co-located on the same box as the app — this
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

### 2.2 No other database is used

No MongoDB, MySQL/MariaDB, SQLite, Elasticsearch, etc. is a dependency of this application, even
if one happens to be running alongside it on a shared host.

---

## 3. `configuration.json` — every key the running application reads

Location: `<working-dir>/cloud-driver/configuration.json` — **required** (a completely empty
`{}` is technically loadable, but see below for which keys are then effectively mandatory).
Re-read from disk on every access (`CloudDriver#getConfiguration()`), never cached — a value can be
changed without restarting, for whatever reads it fresh each time.

**⚠️ Never commit this file** — gitignored (`cloud-driver/configuration.json`) since it holds real
secrets (JWT signing key, SMTP password if SMTP is used).

### 3.1 Required — startup fails or a core feature silently breaks without these

| Key | Type | Consumed by | Effect if missing |
|---|---|---|---|
| `aws-kms-region` | string | `CloudBootstrap.initiateCloudDriver()` | **Crashes the whole process at boot** (`NullPointerException` from `JsonDocument#getString` on a missing key) — `AwsKmsKeyEncryptionService` is unconditionally constructed, no fallback exists in current code. |
| `aws-kms-key-id` | string | same | same — crashes at boot. |
| `jwt-signing-key` | string, `openssl rand -base64 32` | `CloudRestExtension.startRestApi` | Not fatal to the process, but the entire REST API/JWT auth layer is skipped (logged warning) — every client-facing route stays down. |
| `cloud-server-max-bytes-available` | long (bytes) | `CloudUserCommand`/`StatisticsCommand` (terminal) | Not checked at boot, but **`cloudUser update`/`stats`/`ab`** throw `NullPointerException` the moment they're run without it set — no default exists. |

### 3.2 Optional, with a real default

| Key | Default | Consumed by |
|---|---|---|
| `rest-server-port` | — (effectively required if the REST API is wanted) | `CloudRestExtension` |
| `rest-server-bind-host` | — | `CloudRestExtension` |
| `cloud-user-max-bytes-to-upload` | `1048576` (1 MiB) — **strict, not unlimited** | `CloudUser` |
| `trash-retention-days` | `30` | `TrashPurgeScheduler`, `CloudUserService` (purge-eligibility timestamps) |
| `auth-rate-limit-max-requests` | `10` | `DefaultRestFactory` (`/auth/*` limiter) |
| `auth-rate-limit-window-seconds` | `300` | same |
| `trust-proxy-headers` | `false` | `DefaultRestFactory` (rate-limit identity via `X-Forwarded-For`) — only enable behind a genuinely trusted single reverse-proxy hop |
| `api-rate-limit-read-max-requests` | `300` | `DefaultRestFactory` (general API limiter, `GET`/`HEAD`) |
| `api-rate-limit-read-window-seconds` | `60` | same |
| `api-rate-limit-write-max-requests` | `60` | same |
| `api-rate-limit-write-window-seconds` | `60` | same |
| `metrics-port` | `9404` | `CloudMetricsExtension` |
| `metrics-bind-host` | `127.0.0.1` | `CloudMetricsExtension` |
| `clamav-host` | `"localhost"` | `CloudScanExtension` |
| `clamav-port` | `3310` | `CloudScanExtension` |
| `clamav-timeout-seconds` | `30` | `CloudScanExtension` |
| `content-scan-max-bytes` | `104857600` (100 MiB) | `CloudScanExtension` |
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
  "aws-kms-key-id": "alias/cloud-driver-kms-key",

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
    plus `kms:CreateKey` only if `KeyEncryptionService#rotate()` will ever be invoked.
  - Credentials are resolved via the **AWS SDK's own default credential provider chain**
    (`~/.aws/credentials`/`~/.aws/config`, environment variables, an EC2/ECS instance role, etc.)
    — **deliberately never read from `configuration.json`**, only the region/key-id are. Set these
    up on the host the JVM actually runs on.
  - Config: `aws-kms-region`, `aws-kms-key-id` (both required, see §3.1).

- **AWS S3** (optional — only activates once `aws-s3-bucket`/`aws-s3-region` are both set)
  - Powers S3-backed `StoredFile` content, presigned direct-to-client upload/download, and the
    `MigrateToS3Command`/`TrashPurgeScheduler` codepaths that clean up S3 objects.
  - Create a bucket. Required IAM permissions on it: `s3:PutObject`, `s3:GetObject`,
    `s3:DeleteObject`, `s3:AbortMultipartUpload`, `s3:ListBucket`.
  - Same credential-resolution rule as KMS — default provider chain, not `configuration.json`.
  - Config: `aws-s3-region`, `aws-s3-bucket`, optional `aws-s3-key-prefix` (default `""`).
  - Presigned uploads sign with SSE-S3 (`AES256`), not SSE-KMS — no extra `kms:GenerateDataKey`
    grant needed for that specific path.

- **AWS SES v2** (optional — the preferred email transport, checked before SMTP; see §4.2)
  - Backed by `SesEmailSender` (`cloud-driver-auth`, `software.amazon.awssdk:sesv2`) — sends via
    SES's `SendEmail` raw-message API, so the outgoing message (HTML + plain-text + inline logo) is
    byte-for-byte identical to what `SmtpEmailSender` would have sent; only the transport differs.
  - Required IAM permission for the running identity: `ses:SendEmail`.
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

Setup notes (all confirmed by actually installing and testing this against a real `clamd` on
`strato`, 2026-09-08):

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
   (`127.0.0.1`) is sufficient: `ClamAvClient`'s default `clamav-host` value is the string
   `"localhost"`, and Java's `InetSocketAddress`/`InetAddress` default resolution behavior
   (`preferIPv6Addresses=false`) prefers the IPv4 record for it regardless of what the OS's own
   `/etc/hosts`/NSS order says — confirmed by compiling and running a one-off test with the exact
   JVM in use. If in doubt, pin `"clamav-host": "127.0.0.1"` explicitly in `configuration.json`.
4. **Raise `clamd`'s own size limits above this application's scan ceiling.** Debian's default
   `StreamMaxLength`/`MaxFileSize` (25M) sit well under the app's own `content-scan-max-bytes`
   default (100 MiB) — a file in that gap would be sent to `clamd` and rejected as a scan-level
   error, silently triggering the app's retry-then-fail-open path (marks the file `CLEAN` without
   ever actually scanning it) for a size range the app itself considers scannable. Set
   `StreamMaxLength 128M`, `MaxFileSize 128M`, `MaxScanSize 300M` (or proportionally higher/lower
   if `content-scan-max-bytes` is overridden).
5. **Bind loopback-only, and mean it.** `clamd` has no authentication of its own — a `TCPSocket`
   with no `TCPAddr` (or a systemd `ListenStream=3310` with no address) binds every interface. On
   any host without its own firewall (confirmed to be the case on `strato` — see §6 below), that
   exposes an unauthenticated malware scanner to the whole internet.
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

---

## 5. Filesystem / disk

| Path (relative to `user.dir`) | Purpose | Created by |
|---|---|---|
| `cloud-driver/` | Config + credentials files | Operator (must exist before first start) |
| `extensions/` | Extension jars, scanned on boot | Operator (drop `cloud-driver-extensions-*.jar` files here) |
| `upload-scratch/` | Per-upload scratch files, streamed-then-deleted | Application, automatically |

No fixed minimum disk size is documented, but note: uploads are streamed through
`upload-scratch/` rather than buffered in heap, so scratch-disk headroom should comfortably exceed
the largest single expected upload (`MAX_REQUEST_SIZE_BYTES`, 256 MB by default) at any given
concurrency level.

---

## 6. Networking & security

- **This application does not manage its own firewall.** Confirmed the hard way on `strato`
  (2026-09-08): the box had **no firewall at all** (`ufw` not installed, `iptables` chains empty,
  default-`ACCEPT`) — meaning anything bound to `0.0.0.0` is reachable from the entire internet by
  default. Bind every service that has no authentication of its own (`clamd`'s TCP socket) strictly
  to `127.0.0.1`/loopback, and put a real firewall or cloud-provider security group in front of the
  host regardless.
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
  box, via `JVM_XMX` in `shell/start-cloud.sh`). Without one, JVM ergonomics can cap the heap far
  below what's actually free, and a single large upload needs several simultaneous in-memory copies
  of its content before it reaches the database — a real `OutOfMemoryError` was hit on this exact
  gap persisting a ~195 MB file with no `-Xmx` set. Separately, the persistence layer caches every
  table's rows in memory for the process's whole lifetime, so the **resident heap floor grows with
  total database size** — a second real `OutOfMemoryError` boot crash-loop was hit at ~3 GB of
  stored payload under `-Xmx4g` (2026-09-09; see [troubleshooting.md](troubleshooting.md)). Size
  `-Xmx` above the database's total payload size, and re-check as data grows.
- **Swap**: the reference deployment adds a 4 GB swapfile as a kernel-OOM safety net — with the
  heap floor above, an unswapped box this size has little headroom left for spikes.
- **`clamav-daemon`**, once its signature database is loaded, resides at roughly 700 MB–1 GB —
  budget for this on top of the JVM's own `-Xmx` if content scanning is enabled.
- **Total**: the reference deployment runs on 7.7 GB RAM + 4 GB swap with `-Xmx6g` + `clamd` +
  Postgres all co-located, and headroom is genuinely tight — don't add further memory-hungry
  services to the same box without re-checking.
- **Recommendation**: the cloud-driver needs x-GB RAM depending on how much data will be scaled.

---

## 8. Extension-by-extension requirement summary

Only `cloud-driver-bootstrap` itself (Postgres + AWS KMS, §2.1/§4.1) is non-optional. Everything
else is an independent jar dropped into `extensions/` — omit any of these entirely if the feature
isn't wanted:

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
| `cloud-driver-extensions-search` | none (in-memory index, no external dependency) |
| `cloud-driver-extensions-webhooks` | outbound internet access only (no third-party account) |

---

## 9. Quick checklist for a fresh deployment

- [ ] JDK 21 + Maven installed; GitHub PAT with `read:packages` configured for `database-driver-v2`
- [ ] PostgreSQL database + dedicated owner role created
- [ ] AWS account: KMS CMK created, IAM credentials with `kms:Encrypt`/`kms:Decrypt` on the host
- [ ] (optional) S3 bucket + IAM permissions, if S3-backed storage is wanted
- [ ] (optional) AWS SES: sending identity verified, `ses:SendEmail` IAM permission, sandbox mode
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
- [ ] `-Xmx` set explicitly when launching the jar

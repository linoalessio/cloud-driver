# cloud-driver-extensions-rest

Hosts `CloudRestExtension`, the extension that actually calls `RestFactory#start` on the
JWT-authenticated `DefaultRestFactory` - as of this writing, the only place in this repo that
does so (not `CloudBootstrap`; that responsibility moved out of the bootstrap module a while
back). Loading this extension is what turns a running `cloud-driver-bootstrap` process into a
reachable end-user-facing HTTP API: login, self-registration, two-factor auth, per-account file/
folder management (including trash and cross-account sharing), an admin surface, and a live-push
WebSocket - all gated by a validated JWT rather than the static `X-API-Key` mechanism
`RestFactory`'s other constructor supports.

## Project structure

Reactor position: a child of the `cloud-driver-extensions` aggregator (`packaging=pom`), sibling
of `cloud-driver-extensions-backup`/`-terminal`/`-watcher`/`-metrics`. `pom.xml` (`packaging=jar`)
declares exactly one in-repo dependency, `cloud-driver-plugin`; `cloud-driver-auth` (needed for
`AuthService`/`CloudUserService`/`JjwtSigner`/`Argon2idPasswordHasher`) arrives transitively
through it, not as a direct declaration - putting this module at the far end of the repo's
`api ← auth ← plugin ← bootstrap`/extensions dependency chain, alongside every other extension
that needs the full plugin stack (unlike `cloud-driver-extensions-terminal`, which only needs
`cloud-driver-api`).

Its `<groupId>` (`de.lino.cloud.extensions.web`) still disagrees with its actual Java package
(`de.lino.cloud.extensions.rest`) and artifact name - a leftover from before this functionality
moved out of the now-removed `cloud-driver-extensions-web` module. Harmless for the reactor
build, just a stale coordinate worth knowing about if you go looking for it under the "expected"
groupId.

`extension.json` declares `"name": "cloud-driver-rest-server"` and, as of the current source, a
single dependency: `["cloud-driver-bootstrap"]` - `ExtensionFactory#start` requires that
extension be registered and `RUNNING` first. (Older notes elsewhere in this repo describe an
additional dependency on `"cloud-driver-watcher"` for this extension; that is **not** present in
the `extension.json` actually on disk - re-verify against the file itself before relying on
either claim.)

Package layout: one Java package, `de.lino.cloud.extensions.rest`, holding the single class
`CloudRestExtension`.

## Performance

- **Every route handler runs off the Jetty worker thread.** `DefaultRestFactory` (the class this
  extension configures and starts, in `cloud-driver-plugin`) wires every handler through
  Javalin's `Context#future` rather than calling `DataFactory`/`AuthService` synchronously, so a
  worker thread is never blocked on envelope-encryption/database I/O, or on Argon2id's
  deliberately slow hashing at login - the same `MultiTaskingFactory`-backed virtual-thread
  reasoning applied everywhere else in this codebase, applied here at the one place this module's
  own wiring does request-time I/O.
- **`ExtensionFactory#start` runs this extension's `onLoading`/`onRunning` on their own dedicated,
  named `Thread`** (`"extension-cloud-driver-rest-server"`), not on `MultiTaskingFactory`'s shared
  pool - and that thread is a **daemon** thread (`ExtensionFactory` calls `thread.setDaemon(true)`
  unconditionally for every extension), so it never by itself keeps the JVM alive; what actually
  keeps the process running once this extension calls `restFactory.start(...)` is Javalin's own
  embedded Jetty server holding non-daemon threads of its own for as long as it's listening.
- **`CloudUserService#listFiles`/`#listFileSummaries`** (reached via `GET /files`) remain an O(n)
  scan over every `StoredFileOwnership` row system-wide (`DataFactory#getEntities`), filtered to
  the caller's own `authUserId` in memory - a deliberate, documented trade-off (each row is tiny
  and decrypted concurrently via virtual threads, and this path runs far less often than
  upload/delete) that this extension inherits as-is; `uploadFile`/`deleteFile` stay true O(1).

## Data handling

This module defines no entities of its own - it is the wiring point, not a data owner. What it
constructs and starts (`Argon2idPasswordHasher`, `JjwtSigner`, an `EmailSender`,
`AuditLogServiceImpl`, `CloudUserService`, `AuthService`, `DefaultRestFactory`) is what actually
moves `AuthUser`, `CloudUser`, `StoredFile`, `Folder`, `StoredFileOwnership`,
`SharedFileGrant`/`SharedFolderGrant`, `RefreshToken`, `PendingRegistration`/
`PendingPasswordReset`/`PendingEmailChange`/`PendingTwoFactorSetup`/`PendingTwoFactorLogin`, and
`AuditEvent` rows (all defined in `cloud-driver-api`/`cloud-driver-auth`) between an HTTP request
and the encrypted database, but every one of those types is owned by the module that declares it,
not by this one. `startRestApi()` mounts exactly one *generic* route itself -
`restFactory.fetch("/cloudUsers", CloudUser.class)`, `GET`-only - deliberately never `register`/
`update`/`delete`, since `CloudUser`'s owner field serializes as `"authUserId"`, not `"ownerId"`,
so `DefaultRestFactory`'s generic owner-spoof protection would be a no-op for it. Every other
route this extension causes to exist (login/register/2FA, `/files`, `/folders`, sharing, trash,
`/admin/*`, `/ws/updates`) is business logic mounted by `DefaultRestFactory`'s JWT-gated
constructor itself, not by a `register`/`fetch`/`update`/`delete` call this extension makes.

## Safety & security

- **This is the one place in the repo that decides to stand up the JWT-gated `RestFactory`
  variant instead of the unauthenticated or static-`ApiKey`-gated ones.** `CloudDriver.getInstance()
  .getRestFactory()` (built by `FactoryContainer`) is always the unauthenticated constructor,
  local-development/trusted-network use only; this extension is what builds and starts a
  *separate* `DefaultRestFactory(dataFactory, authService, cloudUserService)` instance instead,
  and publishes `authService`/`cloudUserService`/`auditLogService` into
  `IServiceContainer` so every other in-process caller (terminal commands included) reaches the
  same instances. `ApiKey`-gated and JWT-gated auth are never combined on one instance.
- **A missing signing key degrades gracefully, not fatally.** `startRestApi()` reads
  `"jwt-signing-key"` from `configuration.json`; if blank, it logs a warning and returns without
  starting the server at all, leaving every other subsystem (terminal, other extensions,
  pending-upload scheduler) unaffected - the same "don't hard-fail the whole process over one
  missing secret" posture `CloudBootstrap` used to apply directly.
- **`buildEmailSender()` degrades the same way** for `"smtp-host"`/`"smtp-port"`/
  `"smtp-username"`/`"smtp-password"`/`"smtp-from-address"`: any of them missing/blank falls back
  to `LoggingEmailSender` (logs the verification code instead of sending it - not for production)
  rather than failing the whole extension to start.
- **`AuthUser` and `StoredFile` are never mounted generically**, and never will be through this
  extension's own wiring - neither implements `Owned` in a way a generic `register`/`update`
  could safely police (see "Data handling" above for `CloudUser`'s own, related reason). Account
  creation only ever happens through the `/auth/register` + `/auth/register/confirm` flow;
  `StoredFile` access only ever goes through the `/files` routes, which enforce ownership via
  `CloudUserService`.
- **`AuditLogServiceImpl` is constructed here, not in `cloud-driver-auth`, specifically because
  redacting `AuditEvent#getMetadata()` needs `SecretRedactor` (`cloud-driver-plugin`)** - a
  dependency `cloud-driver-auth` must never take on directly. This extension already depends on
  both modules, so it is the one place that can close that gap via constructor injection
  (`new AuditLogServiceImpl(dataFactory, SecretRedactor::redact)`).
- **`DefaultRestFactory` itself implements `LiveUpdatePublisher`** - this extension publishes the
  very same instance it just built into `IServiceContainer#setLiveUpdatePublisher`, so
  `DatabaseWatchEvent#handle` (in `cloud-driver-api`, which cannot depend on Javalin) can push a
  change notification to a connected client's WebSocket session purely through that interface,
  with no compile-time dependency in either direction.
- The growing route surface `DefaultRestFactory`'s JWT-gated constructor mounts (self-registration,
  password reset, e-mail change, TOTP two-factor auth, refresh-token rotation, an admin-gated
  `/admin/*` surface, file/folder sharing, trash/restore, and `GET /ws/updates`) is all secured
  through the one bearer-token gate and rate limiter this extension's `startRestApi()` call
  causes to be installed - the route-by-route mechanics live in `DefaultRestFactory` itself
  (`cloud-driver-plugin`), not in this module's own source, so consult that class directly rather
  than treating this README as a route reference.

## Scalability

- The unbounded `listFiles`/`listFileSummaries` scan (see "Performance" above) is this deployment's
  one identified scaling limit as total `StoredFileOwnership` row count grows system-wide; every
  other path scales with the caller's own request rate, not total system size, thanks to the
  future-based Javalin wiring and O(1) per-file ownership operations.
- **The live-update WebSocket's session registry is process-local, in-memory state** - `Default
  RestFactory` tracks connected sessions in a plain `ConcurrentHashMap` keyed by `authUserId`, held
  by the one JVM this extension is running in. Running more than one `cloud-driver-bootstrap`
  instance behind a load balancer would mean a push notification only reaches whichever
  instance(s) a given client happens to be connected to - not a concern for today's single-process
  `strato` deployment, but a real constraint if this is ever horizontally scaled.
- Everything this extension constructs (`AuthService`, `CloudUserService`, `AuditLogServiceImpl`)
  is stateless itself, backed by the same `DataFactory`/`FileFactory` every other facet of
  `CloudDriver` shares - no additional in-process cache beyond what those already provide
  (`EntityDatabaseClient`'s own 30s/1000-entry decrypted-entity cache).

## API surface

- **`CloudRestExtension`** (`de.lino.cloud.extensions.rest`) - the only public class this module
  declares, extending `cloud-driver-api`'s `Extension`.
  - `onLoading()` - reads `"rest-server-port"` from `configuration.json` and calls the private
    `startRestApi()`.
  - `onRunning(String[])` - prints a confirmation once the server is listening.
  - `onException(RuntimeException)` / `onEnding()` - both stop the `RestFactory` this extension
    started, if it was ever actually built.
  - `startRestApi()` (private) - builds `Argon2idPasswordHasher` + `JjwtSigner` + an
    `EmailSender` + `AuditLogServiceImpl` + `CloudUserService` + `AuthService`, publishes the
    latter three into `IServiceContainer`, constructs the JWT-gated `DefaultRestFactory`,
    publishes it as the `LiveUpdatePublisher`, mounts the one generic `/cloudUsers` fetch route,
    and starts the server.
  - `buildEmailSender()` (private) - resolves an `EmailSender` from `configuration.json`'s SMTP
    keys, falling back to `LoggingEmailSender` if any are missing.
  - `configString(JsonDocument, String)` (private) - reads an optional config key as `""` rather
    than letting `JsonDocument#getString` throw on a missing key.

## API usage

This module exposes no library API of its own - `CloudRestExtension` is loaded as a jar dropped
into `Constraints.EXTENSIONS_PATH` (or assembled there by `shell/test-bootstrap.sh`), never
constructed directly from Java, so the closest substitute for a code sample is the build command
plus an HTTP call against the routes it stands up once running:

```
mvn -pl cloud-driver-extensions/cloud-driver-extensions-rest -am package
```

Always rebuild and redeploy this jar together with a `cloud-driver-bootstrap` jar built from the
same commit - an extension jar is unshaded and resolves shared types off the host bootstrap jar's
own classpath (see the root `CLAUDE.md`'s "Deployment" section).

Once registered and running, a client talks to it purely over HTTP - see
`cloud-driver-platforms-rest`'s `ApiClient` for a full Java HTTP client built against these exact
routes, or call the two-step registration flow directly:

```
curl -X POST https://api.cloud-driver.de/auth/register \
     -H "Content-Type: application/json" \
     -d '{"username":"user@example.com","password":"correct horse battery staple9!"}'
# -> 202 Accepted, {"message": "..."}; check your inbox for the 6-digit code, then:

curl -X POST https://api.cloud-driver.de/auth/register/confirm \
     -H "Content-Type: application/json" \
     -d '{"username":"user@example.com","code":"123456"}'
# -> 201 Created, {"token": "<jwt>", "refreshToken": "<opaque>"}
```

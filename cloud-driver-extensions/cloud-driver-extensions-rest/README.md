# cloud-driver-extensions-rest

Hosts the JWT-authenticated `RestFactory` - as of this writing, the actual place `RestFactory#start` is called from in this repo (not `CloudBootstrap`, despite older comments/docs elsewhere possibly still saying otherwise). Exposes end-user-facing HTTP routes (login, per-user `CloudUser` data, per-user file upload/list/delete) over Javalin, gated by a validated JWT rather than the static `X-API-Key` mechanism `RestFactory`'s other constructor supports.

**Note on staleness:** the root `CLAUDE.md` flags its own "RestFactory"/"JWT authentication" sections covering this extension as "stale, not yet fully re-verified" as of a later refactor (the `FactoryContainer`/`getConfiguration()` additions). This README was written by reading the actual current source (`CloudRestExtension`, `DefaultRestFactory`, `CloudUserService`, `AuthService`) directly rather than trusting that document, but re-verify against source again if it drifts further.

## Why this exists

`RestFactory` (`cloud-driver-api`) is a thin, Javalin-free contract; `DefaultRestFactory` (`cloud-driver-plugin`) is its one implementation, with three constructors - unauthenticated, static-API-key-gated, and JWT-gated. `CloudDriver.getInstance().getRestFactory()` is always wired to the unauthenticated constructor (local development / trusted-network use only). This extension is what actually stands up the JWT-gated variant for real end-user clients (iOS/web/macOS), which authenticate with a username+password-derived JWT rather than holding a static key.

## `extension.json`

```json
{
  "name": "cloud-driver-rest-server",
  "version": "1.0.0",
  "description": "CloudDriver rest api server for authorizing and maintaining data",
  "authors": ["Lino Alessio Kauschinger"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

Depends only on `"cloud-driver-bootstrap"` being registered and `RUNNING` first (as of this writing - `CLAUDE.md` describes an additional dependency on `"cloud-driver-watcher"` for this extension, which is **not** present in the actual `extension.json` on disk; verify again before relying on either claim if this file changes).

Note also: this module's `pom.xml` currently declares `<groupId>de.lino.cloud.extensions.web</groupId>` rather than `de.lino.cloud.extensions.rest` - a leftover from before this functionality moved out of the removed `cloud-driver-extensions-web` module (see the top-level `cloud-driver-extensions/README.md`). It does not affect the Maven artifact's actual identity in this reactor build, but is worth knowing about if you go looking for it under the "expected" groupId.

## What registering this extension actually wires up

`CloudRestExtension#onLoading` reads `"rest-server-port"` from `configuration.json` and calls its own `startRestApi()`, which:

1. Reads `"jwt-signing-key"` from `configuration.json` - if blank, logs a warning and returns **without starting the server**, leaving every other subsystem (terminal, other extensions, pending-upload scheduler) unaffected. This mirrors `CloudBootstrap`'s original `startRestApi` behavior of not hard-failing the whole process over a missing signing key.
2. Reads `"rest-api-bind-host"` (falling back to `0.0.0.0` if blank/absent) - a production deployment fronted by a TLS-terminating reverse proxy should set this to `127.0.0.1` so the plain-HTTP Javalin listener is only reachable from the proxy, never directly from the internet.
3. Builds `Argon2idPasswordHasher` + `JjwtSigner` + an `EmailSender` (see below) + `AuthService` + `CloudUserService`, then constructs `new DefaultRestFactory(dataFactory, authService, cloudUserService)`.
4. Mounts `restFactory.fetch("/cloudUsers", CloudUser.class)` - **and nothing else generic** - then calls `restFactory.start(bindHost, port)`.

The `EmailSender` (`AuthService#register`'s e-mail-verification code is sent through it) is chosen by `CloudRestExtension#buildEmailSender`, reading `"smtp-host"`/`"smtp-port"`/`"smtp-username"`/`"smtp-password"`/`"smtp-from-address"` from `configuration.json`: a blank or absent `"smtp-host"` - including on a `configuration.json` written before this feature existed - logs a warning and falls back to a `LoggingEmailSender` (`cloud-driver-auth`, logs instead of sending - not suitable for production) rather than failing the whole REST API to start; a configured host builds a real `SmtpEmailSender` (SMTP+STARTTLS via Jakarta Mail/Angus Mail).

The JWT-gated `DefaultRestFactory` constructor itself (not this extension) is what actually mounts the fixed routes: `POST /auth/login`, `POST /auth/register`, `POST /auth/register/confirm`, `POST /files`/`GET /files`/`DELETE /files/{id}` (backed by `CloudUserService`), plus whatever `register`/`fetch`/`update`/`delete` calls were made against it beforehand (here, only the one `fetch("/cloudUsers", ...)` call). Registration is a two-step, e-mail-verified flow: `POST /auth/register` (`{"username": "<email>", "password": "..."}`) validates the address and e-mails a 6-digit code valid for 10 minutes, without creating the account yet; `POST /auth/register/confirm` (`{"username": "<email>", "code": "..."}`) verifies that code and only then creates the `AuthUser`, returning a JWT the same shape `/auth/login` does. Every route except `/auth/login`/`/auth/register`/`/auth/register/confirm` requires a valid `Authorization: Bearer <jwt>` header (or a `?token=` query parameter fallback, intended for pasting a URL directly into a browser).

### What is deliberately *not* mounted, and why

- **`AuthUser` and `StoredFile` are never mounted generically.** Neither implements `Owned` (`AuthUser` *is* the account, with no separate ownership concept; `StoredFile`'s ownership lives entirely outside itself, in per-(user, file) `StoredFileOwnership` rows). A generic `register`/`update` route would let any authenticated caller overwrite an arbitrary existing record by id, since `EntityDatabaseClient#store` falls back to update-on-collision - an account-takeover vector for `AuthUser` (a spoofed `passwordHash` under a victim's id), and a way to silently overwrite another user's file content for `StoredFile`, bypassing the ownership tracking `CloudUserService`/the `/files` routes provide. `AuthUser` accounts are only ever created via the `POST /auth/register`/`POST /auth/register/confirm` flow above - there is no separate operator-run account-creation tool.
- **`CloudUser`'s `register`/`update` are never mounted either**, despite `CloudUser` implementing `Owned`. Its primary key (`authUserId`) doubles as its own `Owned#ownerId()`, but Gson serializes that field as `"authUserId"`, not `"ownerId"` - so `DefaultRestFactory`'s owner-spoof protection (which only overwrites a JSON `"ownerId"` property before deserializing) is a no-op for this type, and a caller could otherwise send an arbitrary `authUserId` and overwrite another user's `CloudUser` record. `CloudUser` is created/mutated exclusively through `CloudUserService` (backing the `/files` routes), never through generic REST writes - only `GET /cloudUsers/{authUserId}` (owner-scoped) and `GET /cloudUsers` (filtered to the caller's own record) are reachable.

## Javalin wiring, the JWT gate, and `Owned`-based scoping

- **Off the Jetty worker thread, always.** Every route handler is wired through Javalin's `Context#future` rather than calling `DataFactory`/`AuthService` synchronously, so a Jetty worker thread is never blocked on the encryption/database I/O (or Argon2id's deliberately slow hashing, for `/auth/login`) those calls perform - the same reasoning `MultiTaskingFactory` exists for everywhere else in this codebase, applied at the one place `DefaultRestFactory` does I/O inside a request handler.
- **The `before` filter (`requireValidBearerToken`) runs once per request**, letting `/auth/login` through unchecked and requiring every other route to carry a valid bearer token, storing the validated user id as a Javalin request attribute (`ctx.attribute("userId", ...)`) for downstream handlers to read.
- **`Owned` scoping is enforced per verb, not once centrally:** `register`/`update` overwrite the request body's `"ownerId"` field with the authenticated caller's id (via a `Gson`-parsed `JsonObject`, not reflection) before deserializing, so a client can never write a record under someone else's ownership even by sending a spoofed `ownerId`; `fetch`'s single-entity route 404s (not 403, to avoid confirming the record exists at all) if the fetched entity's owner doesn't match the caller, and its list route silently filters non-owned entities out; `update`/`delete` both fetch the existing record first and 404 the same way before applying the mutation.
- **Token resolution has a documented trade-off.** `resolveBearerToken` tries the `Authorization` header first, then falls back to a `?token=` query parameter - useful for reaching a JWT-gated `GET` route directly from a browser address bar, but a token passed this way ends up in browser history, this server's own access logs, and any `Referer` header the page later sends onward. Prefer the header whenever the caller can set one.

## Performance / concurrency characteristics

- See the "Javalin wiring" section above for the future-based, non-blocking-worker-thread design - this is the module's primary performance characteristic.
- `CloudUserService#listFiles` (reached via `GET /files`) is an O(n) scan over **every** `StoredFileOwnership` row system-wide (via `DataFactory#getEntities`), filtered to the caller's `authUserId` in memory - there is no indexed non-primary-key lookup available from the underlying `database-driver`. This is a deliberate, documented trade-off (each row is tiny, decrypted concurrently via virtual threads, and this method is called far less often than upload/delete) - but it does mean this one read scales with total system-wide file count, not per-user file count.
- `CloudUserService#uploadFile`/`#deleteFile`, by contrast, are true O(1): a single small `StoredFileOwnership` row insert/delete rather than a rewrite of a larger structure - this is exactly why ownership was pulled out of an earlier `CloudUser`-embedded `Set<String>` design in the first place (see `StoredFileOwnership`'s own Javadoc in `cloud-driver-auth`).

## Data handling and safety

- Postgres credentials never leave the server - the client only ever sees a signed JWT, never database connection details.
- A generated JWT expires after 12 hours; there is no refresh-token mechanism by design - the client simply re-authenticates via `/auth/login` once it expires.
- `ApiKey`-gated and JWT-gated auth are never combined on one `DefaultRestFactory` instance; this extension uses the JWT-gated constructor exclusively.

## Scalability

The unbounded `listFiles` scan (above) is this module's one identified scaling limit as the total number of `StoredFileOwnership` rows across all users grows; every other path here scales with the caller's own request rate, not with total system size, thanks to the future-based Javalin wiring and O(1) per-file ownership operations.

## Javadoc conventions

`CloudRestExtension`'s Javadoc had drifted noticeably out of date prior to this pass: `onLoading`/`onRunning` were documented as "Prints a diagnostic message; no real loading/running behavior yet" despite both doing substantial, real work (building and starting the entire JWT-authenticated REST stack), and `onException`/`onEnding` were both marked "No-op" despite each calling `REST_FACTORY.stop()`. All four were rewritten to accurately describe current behavior, following the same Google Java Style Guide conventions used elsewhere in this tree; the two static fields (`REST_SERVER_PORT`, `REST_FACTORY`) also gained one-line field Javadoc they previously lacked.

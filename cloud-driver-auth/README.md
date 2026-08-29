# cloud-driver-auth

The username/password → JWT authentication engine for end-user clients (iOS/web/macOS), as a
second, independent auth mechanism alongside the static `ApiKey`/`X-API-Key` check
`cloud-driver-plugin` also offers. Login is verified against an `AuthUser` entity persisted
through a `DataFactory` like any other entity - Postgres credentials never leave the server, and
the client never sees them.

This module is deliberately **framework-agnostic**: it has no Javalin dependency of its own, so
the whole engine (account storage, password hashing delegation, JWT signing/verification,
login/registration) stays usable independently of whatever HTTP layer happens to front it.
Today that's `cloud-driver-plugin`'s `DefaultRestFactory` (the JWT-gated constructor) and
`cloud-driver-extensions-rest`'s `CloudRestExtension`, both of which depend on this module
directly - this module never depends back on either.

## Coordinates

```xml
<dependency>
    <groupId>de.lino.cloud.auth</groupId>
    <artifactId>cloud-driver-auth</artifactId>
    <version>1.0.1</version>
</dependency>
```

Depends only on `cloud-driver-api` (contracts: `DataFactory`, `PasswordHasher`, `JwtSigner`,
`EmailSender`, `AuthUser`, `Owned`, `ICloudUser`/`ICloudUserService`, ...), `database-driver-api`,
`jjwt-api` (+ `jjwt-impl`/`jjwt-jackson` at runtime), `jakarta.mail-api` (+ `angus-mail` at
runtime, backing `SmtpEmailSender`), and `org.jetbrains:annotations`/Lombok. **Never** depends on
`cloud-driver-plugin` - a caller supplies a concrete `DataFactory`/`PasswordHasher` (e.g.
`cloud-driver-plugin`'s `EntityDatabaseClient`-backed `DefaultDataFactory` and
`Argon2idPasswordHasher`) from the outside.

## Structure

Most of the actual contracts this module implements now live in `cloud-driver-api` (a later
refactor moved them out of `cloud-driver-auth` itself, see the package column below) - this
module supplies the concrete, stateful pieces: the auth engine's real logic, plus the entities it
persists.

| Class | Package | Role |
|---|---|---|
| `AuthService` | `de.lino.cloud.auth` | The only `IAuthService` implementation: `register`/`confirmRegistration`/`login`/`validate` |
| `PendingRegistration` | `de.lino.cloud.auth` | A not-yet-created account waiting on e-mail verification, keyed by e-mail address |
| `SmtpEmailSender` | `de.lino.cloud.auth.mail` | The only production `EmailSender`: SMTP+STARTTLS via Jakarta Mail/Angus Mail |
| `LoggingEmailSender` | `de.lino.cloud.auth.mail` | Dev-only `EmailSender` fallback: logs instead of actually sending |
| `CloudUser` | `de.lino.cloud.auth` | One end user's identifying record (`ICloudUser`, `Owned`) |
| `CloudUserService` | `de.lino.cloud.auth` | The only `ICloudUserService` implementation: ties a user to their `StoredFile`s |
| `StoredFileOwnership` | `de.lino.cloud.auth` | One (user, file) ownership row (`Owned`) |
| `JjwtSigner` | `de.lino.cloud.auth.jwt` | The only `JwtSigner` implementation, HMAC-SHA256 via jjwt |
| `AuthUser` | `de.lino.cloud.api.jwt.user` *(cloud-driver-api)* | The persisted account entity |
| `JwtSigner`/`InvalidJwtException` | `de.lino.cloud.api.jwt` *(cloud-driver-api)* | Sign/verify contract + its failure exception |
| `InvalidCredentialsException` | `de.lino.cloud.api.jwt` *(cloud-driver-api)* | `login`'s failure exception |
| `InvalidVerificationCodeException` | `de.lino.cloud.api.jwt` *(cloud-driver-api)* | `confirmRegistration`'s failure exception |
| `IAuthService` | `de.lino.cloud.api.jwt.auth` *(cloud-driver-api)* | `AuthService`'s contract |
| `EmailSender`/`EmailDeliveryException` | `de.lino.cloud.api.mail` *(cloud-driver-api)* | Send contract + its failure exception |
| `Owned` | `de.lino.cloud.api.jwt.rest` *(cloud-driver-api)* | Marks an entity as scoped to one end user |
| `ICloudUser`/`ICloudUserService` | `de.lino.cloud.api.user` *(cloud-driver-api)* | `CloudUser`/`CloudUserService`'s contracts |

### `AuthUser` - why not just `User`

A `Serialized` entity: `id` (primary key, a random UUID minted by `AuthService#register`),
`emailAddress` (the login identifier - this module authenticates by **email address**, not an
arbitrary username; `AuthService#register` also runs a live MX-record lookup against the
address's domain before persisting it, see "Safety" below), and `passwordHash` (a PHC-style
Argon2id string). It never retains the raw password in any field - unlike `ApiKey`
(`cloud-driver-api`'s static, machine-generated key, which *does* keep its raw value since it
must be handed back once), a user-chosen password never needs to be read back.

Named `AuthUser` rather than plain `User` deliberately: `EntityDatabaseClient` derives the SQL
table name from `getSimpleName()`, and `USER` is a reserved keyword in PostgreSQL/standard SQL.
An entity actually named `User` produces an unquoted `CREATE TABLE User (...)` that Postgres
rejects with a syntax error - and the underlying `SQLExecution` logs that failure rather than
throwing, so the failure is silent unless stderr is being watched. `AuthUser` sidesteps the
collision entirely rather than relying on identifier quoting this driver stack doesn't do.

### `JwtSigner`/`JjwtSigner`/`InvalidJwtException`

`JwtSigner` (`cloud-driver-api`) is a two-method contract: `sign(subject, ttlSeconds)` issues a
token, `verify(token)` validates it and returns the embedded subject, throwing
`InvalidJwtException` on a bad signature, malformed token, or expiry. `JjwtSigner` is the only
implementation, backed by [jjwt](https://github.com/jwtk/jjwt) with HMAC-SHA256:

```java
JwtSigner signer = new JjwtSigner(signingKeySecret); // >= 32 bytes/256 bits, or IllegalArgumentException

String token = signer.sign(authUser.getId(), Duration.ofHours(12).getSeconds());
String userId = signer.verify(token); // throws InvalidJwtException on bad signature/malformed/expired
```

The constructor takes the raw signing key material directly rather than reading an environment
variable or config file itself - the caller resolves it (today, from the `"jwt-signing-key"`
field of `configuration.json` under `Constraints.CONFIGURATION_PATH` - see
`cloud-driver-bootstrap`'s README for where that file lives) and passes the value in, keeping
`JjwtSigner` itself free of any I/O or environment-coupling.

### `AuthService`

The only `IAuthService` implementation, constructed with the four collaborators it delegates
to - no other state, no mutable fields, so one instance is safe to share across concurrent
callers:

```java
AuthService authService = new AuthService(dataFactory, passwordHasher, jwtSigner, emailSender);

authService.register("jane@example.com", rawPassword); // syntax + MX-record check, then e-mails a verification code
String token = authService.confirmRegistration("jane@example.com", code); // persists the real AuthUser, returns a JWT
String loginToken = authService.login("jane@example.com", rawPassword); // throws InvalidCredentialsException on any mismatch
String userId = authService.validate(token); // throws InvalidJwtException
```

Registration is a two-step, e-mail-verified flow, not a single call:

- **`register`** validates the address against a permissive email-syntax regex, then performs a
  live DNS MX-record lookup against its domain (rejecting an obviously fake/typo'd domain like
  `@gmial.com` without sending any mail). It does **not** create the `AuthUser` yet: it hashes
  the password (`PasswordHasher#hash`), generates a random 6-digit code valid for 10 minutes,
  persists both as a `PendingRegistration` keyed by the e-mail address (a repeat call for the
  same address just overwrites the previous attempt), and e-mails the code via `EmailSender`.
- **`confirmRegistration`** looks up the `PendingRegistration` for the given address, rejects it
  (via `InvalidVerificationCodeException`, the same message either way) if it doesn't exist, has
  expired, or the supplied code doesn't match, otherwise creates the real `AuthUser` from the
  pending row's already-hashed password, deletes the pending row, and returns a signed JWT the
  same way `login` does. This - not `register` - is what actually creates the account.
- **`login`** looks the account up by scanning every `AuthUser` via `DataFactory#getEntities` and
  filtering by `emailAddress` in memory (there is no keyed-by-email lookup - `emailAddress` isn't
  this entity's primary key), verifies the password via `PasswordHasher#verify`, and signs a JWT
  valid for 12 hours. Deliberately throws the exact same `InvalidCredentialsException` message
  whether the account doesn't exist or the password is wrong.
- **`validate`** is a thin pass-through to `JwtSigner#verify`.

Both HTTP routes this module is wired up behind (`cloud-driver-plugin`'s `DefaultRestFactory(DataFactory,
AuthService)`) - `POST /auth/register` and `POST /auth/register/confirm` - are this deployment's
open, e-mail-verified self-registration flow; there is no separate operator-run account-creation
tool.

### `CloudUser`/`Owned`/`StoredFileOwnership`/`CloudUserService`

Ties an `AuthUser` account to the `StoredFile`s it has uploaded, without exposing every user's
files to every other logged-in user:

- **`Owned`** (`cloud-driver-api`) - one method, `ownerId()`. An entity opts in by implementing
  it and (de)serializing its owner id under the JSON field `"ownerId"`; a JWT-authenticated
  `DefaultRestFactory` route uses this to scope reads/writes to the caller's own data. Only takes
  effect on the JWT-gated `RestFactory` constructor - the unauthenticated and `ApiKey`-gated ones
  have no per-request user identity to scope by.
- **`CloudUser`** - one record per end user, keyed by (and identical in value to) their own
  `AuthUser#getId()`. Implements `Owned` (`ownerId()` returns the same id as its primary key) and
  `ICloudUser`. **Does not track file ownership itself** - see the next bullet for why - and is
  never mounted on `RestFactory#register`/`#update`: `DefaultRestFactory`'s spoof-protection for
  `Owned` entities only overwrites a JSON `"ownerId"` property, but `CloudUser`'s Gson field is
  named `"authUserId"`, so that protection is a no-op for this type; only `RestFactory#fetch` is
  ever mounted for it. `getStoredFiles()` is a convenience accessor that resolves
  `CloudDriver.getInstance().getFactoryContainer().getRestFactory().getCloudUserService()` and
  delegates to `listFiles` on demand - it holds no file list as state on the entity itself.
- **`StoredFileOwnership`** - one row per (user, file) pair, primary-keyed on
  `authUserId + ":" + storedFileId` (safe to concatenate unquoted since `storedFileId` is always
  a random UUID). Replaces an earlier design where `CloudUser` embedded every owned file id in
  one `Set<String>`: with up to 10,000 files per user, tracking or untracking a single file meant
  decrypting, mutating, and re-encrypting that *entire* set on every upload/delete - an O(n)
  rewrite for what should be O(1). Implements `Owned` but is never mounted on `RestFactory` at
  all; it's pure internal bookkeeping `CloudUserService` reads/writes directly.
- **`CloudUserService`** - the only `ICloudUserService` implementation:

  ```java
  CloudUserService cloudUserService = new CloudUserService(dataFactory, fileFactory);

  CloudUser user = cloudUserService.getOrCreate(authUserId); // looks up, creates on first use
  StoredFile uploaded = cloudUserService.uploadFile(authUserId, "report.pdf", bytes); // O(1) insert + one StoredFileOwnership row
  List<StoredFile> files = cloudUserService.listFiles(authUserId); // see the scan trade-off below
  cloudUserService.deleteFile(authUserId, uploaded.fileId()); // 404-equivalent IllegalArgumentException if not owned
  ```

  `uploadFile`/`deleteFile` are true O(1) operations - a single small row insert/delete, never
  touching any other file id the user (or any other user) owns. `listFiles`, however, has no
  keyed-by-owner lookup available from `DataFactory`/the underlying database-driver, so it scans
  and decrypts **every** `StoredFileOwnership` row system-wide (`DataFactory#getEntities`) and
  filters to the caller's `authUserId` in memory - see "Scalability" below.

## Performance

This module does almost no I/O of its own - every real cost (encryption, database round-trips)
is paid by whatever `DataFactory`/`FileFactory` the caller injects; `AuthService`/`CloudUserService`
are thin orchestration on top. Two things worth knowing:

- **Argon2id is deliberately slow**, and that cost is paid synchronously, on whatever thread
  calls `AuthService#register`/`#login` - there is no async variant on `IAuthService`. A caller
  wiring this behind an HTTP endpoint must dispatch the call itself (e.g.
  `DefaultRestFactory`'s `handleLogin` dispatches through `MultiTaskingFactory.supplyAsync` so a
  Jetty worker thread is never blocked on it) rather than calling `login`/`register` directly on
  a request-handling thread. Note that dispatching onto a virtual thread does **not** turn
  Argon2id's cost from CPU-bound into something concurrency-friendly - virtual threads help most
  with I/O-bound blocking, not CPU/memory-hard hashing, so a burst of concurrent logins can still
  saturate available cores the same way it would on platform threads.
- **`register`'s MX-record check is a genuinely blocking, synchronous DNS lookup** (via
  `javax.naming.directory.InitialDirContext`) with no explicit timeout configured - see the
  findings list this documentation pass produced for the concrete hazard and fix.
- **`login`'s account lookup is O(n) in the total number of registered `AuthUser`s** - every
  login decrypts and scans the *entire* `AuthUser` section to find one row by email, since email
  isn't the entity's primary key. Unlike `CloudUserService#listFiles`'s similar, already-accepted
  scan trade-off (called comparatively rarely), this runs on every single login - see the
  findings list for the scalability implication as the user base grows.

## Data handling

- **Never persisted:** the raw password (`AuthUser` has no field for it at all, only
  `passwordHash`); the raw JWT signing key (only ever held in memory as a `SecretKey` inside
  `JjwtSigner`).
- **Persisted, envelope-encrypted like any other entity:** `AuthUser` (`id`, `emailAddress`,
  Argon2id `passwordHash`), `CloudUser` (`authUserId`), `StoredFileOwnership` (`authUserId`,
  `storedFileId`). None of this module's entities open a second persistence path of their own -
  everything goes through the injected `DataFactory`, the same AES-256-GCM envelope encryption
  every other entity in this codebase gets.
- A JWT itself carries only the subject (`AuthUser#getId()`) plus standard `iat`/`exp` claims -
  no email address, password hash, or other PII is embedded in the token.

## Safety

- **`login` never distinguishes "no such account" from "wrong password"** in either its
  exception type or message (`InvalidCredentialsException("invalid credentials")` in both
  cases) - this is deliberate, so a caller can never use failed-login timing/messaging to
  enumerate valid email addresses.
- **No refresh-token mechanism.** A JWT simply expires after 12 hours
  (`AuthService.ACCESS_TOKEN_TTL_SECONDS`) and the client re-authenticates via `login` again;
  there is no long-lived refresh token to steal or revoke.
- **`JjwtSigner` enforces a minimum 32-byte (256-bit) signing key** at construction, throwing
  `IllegalArgumentException` immediately on anything shorter - HMAC-SHA256 with a short key is a
  known weakness, so this is checked eagerly rather than left to fail unpredictably later.
- **`ApiKey`/`X-API-Key` auth and this module's JWT auth are never combined** on one
  `DefaultRestFactory` instance (see `cloud-driver-plugin`'s docs) - a deployment picks one
  mechanism per REST server instance.
- **No public self-registration endpoint.** `AuthService#register` is not wired to any HTTP
  route anywhere in this codebase today; the only way to create an account is
  `cloud-driver-bootstrap`'s `CreateUserCli`, an operator-run CLI that reads the password from a
  real interactive console, never a command-line argument.

## Scalability

- `CloudUserService#uploadFile`/`#deleteFile` are O(1) regardless of how many files a user (or
  the system as a whole) already has, by design - see the `StoredFileOwnership` bullet above.
- `CloudUserService#listFiles` is an accepted, documented O(n) trade-off (n = total ownership
  rows system-wide) - cheap per row and called far less often than upload/delete, but the fix if
  it ever becomes a bottleneck is a real indexed query (`WHERE authUserId = ?`) exposed from the
  underlying database-driver up through `DataFactory`, which doesn't exist today.
- `AuthService#login`'s O(n) full-`AuthUser`-table scan on every single login is **not** a
  deliberately-accepted trade-off in the same sense - it sits on the hottest path in this module
  and has no equivalent "called rarely" mitigation. See the findings list for a concrete fix
  direction (a secondary email→id index entity, mirroring `StoredFileOwnership`'s own
  composite-key pattern).

## Javadoc conventions

Every public/protected class, method, and field in this module now carries Google-style Javadoc:
a short summary fragment ending in a period, a blank line, then `@param`/`@return`/`@throws` as
applicable. Two conventions carried over from the rest of this codebase apply here too:

- Concrete method parameters use Lombok's `@NonNull` (runtime-checked, generates the null check);
  interface/abstract method parameters (in `cloud-driver-api`) use `@NotNull`
  (`org.jetbrains.annotations`, documentation-only, since there's no method body to inject a
  check into).
- Instance-field access is always qualified `this.field`, never a bare `field` reference.

# cloud-driver-auth

The end-user account engine: username(email)/password → JWT authentication, per-account file/folder
ownership, sharing between accounts, soft-delete/trash, and the security audit trail - a second,
independent auth mechanism alongside the static `ApiKey`/`X-API-Key` check `cloud-driver-plugin`
also offers. Login is verified against an `AuthUser` entity persisted through a `DataFactory` like
any other entity - Postgres credentials never leave the server, and the client never sees them.

This module is deliberately **framework-agnostic**: it has no Javalin dependency of its own, so
the whole engine (account storage, password hashing delegation, JWT signing/verification, TOTP
two-factor auth, email verification/notification, file/folder ownership and sharing) stays usable
independently of whatever HTTP layer happens to front it. Today that's `cloud-driver-plugin`'s
`DefaultRestFactory` (the JWT-gated constructor) and `cloud-driver-extensions-rest`'s
`CloudRestExtension`, both of which depend on this module directly - this module never depends
back on either.

## Coordinates

```xml
<dependency>
    <groupId>de.lino.cloud.auth</groupId>
    <artifactId>cloud-driver-auth</artifactId>
    <version>1.0.1</version>
</dependency>
```

## Project structure

Reactor position: `cloud-driver-api ← cloud-driver-auth ← cloud-driver-plugin ← cloud-driver-bootstrap`
(see the root README/`CLAUDE.md`'s "Module layout and dependency direction" for the authoritative
picture). This module depends **only** on `cloud-driver-api` (contracts: `DataFactory`,
`FileFactory`, `PasswordHasher`, `JwtSigner`, `EmailSender`, `AuthUser`, `Owned`,
`ICloudUser`/`ICloudUserService`, `IAuthService`, `AuditLogService`, `Folder`/`StoredFile`/
`StoredFileSummary`/`SharedFileSummary`/`SharedFolderSummary`/`SharedFolderContents`/
`TrashedFileSummary`/`TrashedFolderSummary`, ...), plus `database-driver-api`, `jjwt-api` (+
`jjwt-impl`/`jjwt-jackson` at runtime), `jakarta.mail-api` (+ `angus-mail` at runtime, backing
`SmtpEmailSender`), [`dev.samstevens.totp:totp`](https://github.com/samdjstevens/java-totp) (TOTP
two-factor codes), and `org.jetbrains:annotations`/Lombok. **Never** depends on
`cloud-driver-plugin` - a caller supplies a concrete `DataFactory`/`FileFactory`/`PasswordHasher`
(e.g. `cloud-driver-plugin`'s `EntityDatabaseClient`-backed `DefaultDataFactory`/`DefaultFileFactory`
and `Argon2idPasswordHasher`) from the outside.

Package layout (`src/main/java/de/lino/cloud/auth/`):

| Package | Contents |
|---|---|
| `de.lino.cloud.auth` | `AuthService`, `CloudUserService` - the two service implementations |
| `de.lino.cloud.auth.entity` | `CloudUser`, `StoredFileOwnership`, `SharedFileGrant`, `SharedFolderGrant`, `RefreshToken` |
| `de.lino.cloud.auth.pending` | `PendingRegistration`, `PendingPasswordReset`, `PendingEmailChange`, `PendingTwoFactorSetup`, `PendingTwoFactorLogin` - short-lived, not-yet-committed state for every two-step verification flow |
| `de.lino.cloud.auth.jwt` | `JjwtSigner` - the one `JwtSigner` implementation |
| `de.lino.cloud.auth.mail` | `SmtpEmailSender`, `LoggingEmailSender`, `EmailTemplates` |
| `de.lino.cloud.auth.audit` | `AuditLogServiceImpl` - the one `AuditLogService` implementation |

Most of the contracts this module implements live in `cloud-driver-api` (`AuthUser`, `IAuthService`,
`JwtSigner`, `EmailSender`, `Owned`, `ICloudUser`/`ICloudUserService`, `AuditLogService`/
`AuditEvent`, every `Folder`/`StoredFile*`/`Shared*`/`Trashed*` value type, and every
`AuthService`/`CloudUserService` failure exception) - this module supplies the concrete, stateful
logic and the entities it persists.

### `AuthUser` - why not just `User`

Defined in `cloud-driver-api` (`de.lino.cloud.api.jwt.user`), not here. A `Serialized` entity: `id`
(primary key, a random UUID minted by `AuthService#register`... actually by `#confirmRegistration`,
which is what creates the real account), `emailAddress` (the login identifier - this module
authenticates by **email address**, not an arbitrary username; `AuthService#register` also runs a
live MX-record lookup against the address's domain before persisting anything, see "Safety &
security" below), `passwordHash` (a PHC-style Argon2id string - never the raw password), `isAdmin`
(a single boolean admin flag, settable only via `AuthService#setAdmin` - never through any REST
route, see "Safety & security"), and `totpSecretBase32` (nullable - non-null once two-factor
authentication is enabled for that account).

Named `AuthUser` rather than plain `User` deliberately: `EntityDatabaseClient` derives the SQL
table name from `getSimpleName()`, and `USER` is a reserved keyword in PostgreSQL/standard SQL. An
entity actually named `User` produces an unquoted `CREATE TABLE User (...)` that Postgres rejects
with a syntax error - and the underlying `SQLExecution` logs that failure rather than throwing, so
it fails silently unless stderr is being watched. `AuthUser` sidesteps the collision entirely.

### `JwtSigner`/`JjwtSigner`/`InvalidJwtException`

`JwtSigner` (`cloud-driver-api`) is a two-method contract: `sign(subject, ttlSeconds)` issues a
token, `verify(token)` validates it and returns the embedded subject, throwing `InvalidJwtException`
on a bad signature, malformed token, or expiry. `JjwtSigner` is the only implementation, backed by
[jjwt](https://github.com/jwtk/jjwt) with HMAC-SHA256:

```java
JwtSigner signer = new JjwtSigner(signingKeySecret); // >= 32 bytes/256 bits, or IllegalArgumentException

String token = signer.sign(authUser.getId(), Duration.ofHours(12).getSeconds());
String userId = signer.verify(token); // throws InvalidJwtException on bad signature/malformed/expired
```

The constructor takes the raw signing key material directly rather than reading an environment
variable or config file itself - the caller resolves it (today, from the `"jwt-signing-key"` field
of `configuration.json` under `Constraints.CONFIGURATION_PATH` - see `cloud-driver-bootstrap`'s
README for where that file lives) and passes the value in, keeping `JjwtSigner` free of any I/O or
environment-coupling.

### `AuthService`

The only `IAuthService` implementation, constructed with six collaborators:

```java
AuthService authService = new AuthService(
    dataFactory, passwordHasher, jwtSigner, emailSender, cloudUserService, auditLogService
);

authService.register("jane@example.com", rawPassword); // format check + syntax/MX-record check, then e-mails a verification code
AuthTokens tokens = authService.confirmRegistration("jane@example.com", code); // persists the real AuthUser + a CloudUser row, returns {accessToken, refreshToken}
LoginResult result = authService.login("jane@example.com", rawPassword); // completed tokens, or a pending 2FA token
String userId = authService.validate(tokens.accessToken()); // throws InvalidJwtException
```

Registration/password-reset/email-change are all two-step, e-mail-verified flows - `register`/
`requestPasswordReset`/`requestEmailChange` only e-mail a code and persist a short-lived
`Pending*` row, never applying the real change; `confirmRegistration`/`confirmPasswordReset`/
`confirmEmailChange` apply it once the correct code comes back within its validity window
(`InvalidVerificationCodeException`, same message for "no such pending row"/"expired"/"wrong code",
so a caller can never distinguish which). `confirmRegistration` also eagerly creates the new
account's `CloudUser` row via the injected `ICloudUserService#getOrCreate`, so `stats`/`cu list`-style
tooling never sees a JWT-holding account with no `CloudUser` record.

`login` verifies the password via `PasswordHasher#verify` and branches on whether the matched
account has two-factor authentication enabled: a disabled account gets a completed `LoginResult`
(real `AuthTokens`) immediately; an enabled one gets a `LoginResult` carrying only a pending token,
which the caller must present, together with a current TOTP code, to `completeTwoFactorLogin`.
`beginTwoFactorSetup`/`confirmTwoFactorSetup`/`disableTwoFactor` manage enabling/disabling 2FA
itself (`disableTwoFactor` re-verifies the account's password first - a stolen bearer token alone
must not be enough to turn off a second factor). `refresh`/`revokeRefreshToken` exchange/invalidate
the longer-lived, single-use, rotate-on-every-use `RefreshToken` every token-issuing call also
returns, so a long-running client can stay signed in past the 12-hour access-token lifetime without
asking for the password again. `setAdmin` is the only writer of `AuthUser#isAdmin` anywhere in this
codebase - see "Safety & security".

### `CloudUser`/`Owned`/`StoredFileOwnership`/`CloudUserService`

Ties an `AuthUser` account to the `StoredFile`s/`Folder`s it owns, the accounts it shares with or
that share with it, and its own trash - without exposing any of that to another logged-in user:

- **`Owned`** (`cloud-driver-api`) - one method, `ownerId()`. An entity opts in by implementing it
  and (de)serializing its owner id under the JSON field `"ownerId"`; a JWT-authenticated
  `DefaultRestFactory` route uses this to scope reads/writes to the caller's own data. Only takes
  effect on the JWT-gated `RestFactory` constructor.
- **`CloudUser`** - one record per end user, keyed by (and identical in value to) their own
  `AuthUser#getId()`. Implements `Owned` and `ICloudUser`. Carries `timeStamp` (set once, at
  creation - doubles as the account's join date), `maxBytesToUpload` (the account's upload quota,
  read once from `configuration.json`'s `"cloud-user-max-bytes-to-upload"` key, defaulting to a
  strict 1 MiB if unset - see "Safety & security"), and `currentUploadedBytes` (an incrementally
  tracked running total, not recomputed by scanning owned files on every check). **Does not track
  file/folder membership itself** - see the next bullet. Never mounted on `RestFactory#register`/
  `#update`: `DefaultRestFactory`'s spoof-protection for `Owned` entities only overwrites a JSON
  `"ownerId"` property, but `CloudUser`'s Gson field is named `"authUserId"`, so that protection
  is a no-op for this type; only `RestFactory#fetch` is ever mounted for it.
- **`StoredFileOwnership`** - one row per (user, file) pair, primary-keyed on
  `authUserId + ":" + storedFileId`. Also carries the file's current `folderId` placement,
  descriptive metadata captured once at upload time (`fileName`/`contentType`/`sizeBytes`/
  timestamps, so a listing never needs to decrypt the file itself), and a nullable
  `deletedAtEpochMillis` (soft delete - see below). Replaces an earlier design where `CloudUser`
  embedded every owned file id in one `Set<String>`, which would have meant decrypting/rewriting
  that entire set on every single upload or delete.
- **`SharedFileGrant`/`SharedFolderGrant`** - one read-only sharing grant each, primary-keyed on
  `granteeAuthUserId + ":" + fileOrFolderId`. A folder grant covers everything nested inside it, at
  any depth. Both implement `Owned` (owner = the file/folder's actual owner, the only account that
  can create/revoke the grant) but are never mounted on `RestFactory` directly, only through
  bespoke `/files|folders/{id}/share` routes elsewhere in the stack.
- **`RefreshToken`** - a 48-random-byte, base64url token (its own primary key), an `authUserId`, a
  30-day `expiresAtEpochMillis`, and a `revoked` flag. Rotated on every `AuthService#refresh` call -
  the presented token is deleted as part of the same call, and the returned pair carries a freshly
  generated replacement.
- **`CloudUserService`** - the only `ICloudUserService` implementation, constructed with three
  collaborators:

  ```java
  CloudUserService cloudUserService = new CloudUserService(dataFactory, fileFactory, auditLogService);

  ICloudUser user = cloudUserService.getOrCreate(authUserId); // looks up, creates on first use
  StoredFile uploaded = cloudUserService.uploadFile(authUserId, "report.pdf", bytes, folderId); // O(1) insert + one StoredFileOwnership row, quota-checked first
  List<StoredFileSummary> files = cloudUserService.listFileSummaries(authUserId, folderId); // descriptive fields only, no content decrypted
  cloudUserService.deleteFile(authUserId, uploaded.fileId()); // soft delete (trash) - idempotent, revokes any outstanding shares on it
  cloudUserService.shareFile(authUserId, uploaded.fileId(), "colleague@example.com"); // grants read-only access
  ```

  `uploadFile`/`deleteFile`/`shareFile`/`revokeFileShare` are true O(1) operations - a single small
  row insert/update/delete, never touching any other file id the user (or any other user) owns.
  `listFileSummaries`/`listSharedWithMe`/`listFileShares`/`getCloudUserByEmail`/
  `resolveOwnerAuthUserId` all have no keyed lookup available from `DataFactory`/the underlying
  database-driver for what they need, so they scan and decrypt **every** row of the relevant entity
  type system-wide (`DataFactory#getEntities`) and filter in memory - see "Scalability" below.

  Beyond plain CRUD, this class also implements: **soft delete/trash** (`deleteFile`/`deleteFolder`
  flip a `deletedAtEpochMillis` flag instead of removing anything; `restoreFile`/`restoreFolder`
  reverse it; `listDeletedFiles`/`listDeletedFolders` return `TrashedFileSummary`/
  `TrashedFolderSummary`, each paired with when the item becomes eligible for permanent removal
  under the configured retention window; `emptyTrash` permanently purges everything currently
  trashed for one account, bypassing that window); **sharing** (`shareFile`/`shareFolder`/
  `revokeFileShare`/`revokeFolderShare`/`listSharedWithMe`/`listSharedFoldersWithMe`/
  `listFileShares`/`listFolderShares`/`listSharedFolderContents` - the last two added to let a
  grantee browse/download a shared folder's contents, not just see that it exists; deleting a
  file/folder always revokes every outstanding grant on it, so a later restore never silently
  re-shares it); and **folder organization** (`createFolder`/`listFolders`/`updateFolder`/
  `deleteFolder`, cycle-checked moves, cursor-paginated listings).

## Performance

This module does almost no I/O of its own - every real cost (encryption, database round-trips,
file content transfer) is paid by whatever `DataFactory`/`FileFactory` the caller injects;
`AuthService`/`CloudUserService` are thin orchestration on top. Worth knowing:

- **Argon2id is deliberately slow**, and that cost is paid synchronously on whatever thread calls
  `AuthService#register`/`#login`/`#confirmPasswordReset`/`#disableTwoFactor` - there is no async
  variant on `IAuthService`. A caller wiring this behind an HTTP endpoint must dispatch the call
  itself (e.g. `DefaultRestFactory`'s handlers dispatch through `MultiTaskingFactory` so a Jetty
  worker thread is never blocked on it). Dispatching onto a virtual thread does **not** turn
  Argon2id's cost from CPU-bound into something concurrency-friendly - a burst of concurrent
  logins can still saturate available cores.
- **`register`'s MX-record check is a genuinely blocking, synchronous DNS lookup** (via
  `javax.naming.directory.InitialDirContext`), paid on every registration/email-change attempt.
- **`login`'s account lookup is O(n) in the total number of registered `AuthUser`s** - it decrypts
  and scans the *entire* `AuthUser` section to find one row by email, since email isn't the
  entity's primary key. Runs on every single login.
- **`CloudUserService#listFileSummaries`/`listSharedWithMe`/`listFileShares`/
  `getCloudUserByEmail`/`resolveOwnerAuthUserId`/`requireSharedFileAccess`/
  `requireSharedFolderAccess` are all O(n) full-section scans**, n = total rows of the relevant
  entity type system-wide, decrypted and filtered in memory - an accepted trade-off for the ones
  called comparatively rarely (e.g. per-listing), a real cost for the ones on a hotter path
  (e.g. every shared-file/-folder access check).
- **`EntityDatabaseClient`'s 30s/1000-entry decrypted-entity cache** (in `cloud-driver-plugin`,
  not this module) softens repeated reads of the same row, but every full-section scan above still
  pays the decrypt cost for every row on a cache miss.

## Data handling

- **Never persisted:** the raw password (`AuthUser` has no field for it, only `passwordHash`); a
  raw refresh/verification-code value never sits anywhere longer than its own `Pending*` row's
  short TTL (10 minutes for e-mail codes, 5 for a pending two-factor login); the raw JWT signing
  key (only ever held in memory as a `SecretKey` inside `JjwtSigner`).
- **Persisted, envelope-encrypted like any other entity, all via the injected `DataFactory`:**
  `AuthUser` (`id`, `emailAddress`, Argon2id `passwordHash`, `isAdmin`, `totpSecretBase32`),
  `CloudUser`, `StoredFileOwnership`, `SharedFileGrant`/`SharedFolderGrant`, `RefreshToken`, and
  every `Pending*` row (each carrying a plaintext verification code for up to its own short TTL -
  protected at rest by the same AES-256-GCM envelope encryption every other entity gets, per
  `architecture/SECURITY_REQUIREMENTS.md`). None of this module's entities open a second
  persistence path of their own.
- A JWT access token carries only the subject (`AuthUser#getId()`) plus standard `iat`/`exp`
  claims - no email address, password hash, or other PII embedded in the token. A refresh token
  and a pending-two-factor-login token are both opaque, random, `DataFactory`-backed values, never
  JWTs themselves.

## Safety & security

- **`login` never distinguishes "no such account" from "wrong password"** in either exception
  type or message (`InvalidCredentialsException`, same message both ways) - deliberate, so failed-
  login timing/messaging can never be used to enumerate valid email addresses.
- **`requestPasswordReset` never confirms whether an account exists** under the given address -
  responds identically either way, for the same enumeration-resistance reason. `register` and
  `requestEmailChange`, by contrast, *do* confirm existence (`EmailAlreadyRegisteredException`) -
  intentional, since both act on behalf of an address the caller is actively trying to claim/move
  to, not an anonymous probe of someone else's account.
- **Password format is validated before any hashing/DB work**: at least 8 characters, a digit, a
  lowercase letter, an uppercase letter, a symbol, and none of `; , : \`` (which could collide with
  delimiter/quoting conventions elsewhere in the system).
- **Two-factor authentication (TOTP, RFC 6238)** is opt-in per account (`totpSecretBase32`,
  null = disabled). The pending-setup secret is never committed to the live account until a real
  code from it is confirmed. A completed second-factor login uses its own opaque, single-use,
  short-TTL `PendingTwoFactorLogin` token rather than a distinguishing JWT claim - it isn't a JWT
  at all, so `JjwtSigner#verify` rejects it immediately if presented as a bearer token.
- **`AuthUser#isAdmin` is written *only* by `AuthService#setAdmin`**, and that method is never
  reachable from any REST route in this codebase - only from an operator-run terminal command
  (`cloud-driver-extensions-terminal`) - specifically to avoid a privilege-escalation hole.
- **`JjwtSigner` enforces a minimum 32-byte (256-bit) signing key** at construction, throwing
  `IllegalArgumentException` immediately on anything shorter.
- **Refresh tokens are rotated on every use** - the presented token is invalidated as part of the
  same `refresh` call (success or not), and a stolen-but-unused token remains exploitable only
  within its 30-day TTL, bounded independently of the 12-hour access token.
- **A per-account upload quota is enforced before any content is compressed/encrypted** -
  `uploadFile` checks first, so a rejected upload never pays that cost. An unset
  `"cloud-user-max-bytes-to-upload"` config key defaults to a strict 1 MiB, not unlimited.
- **Deleting a file/folder always revokes every outstanding share on it** (added 2026-09-02, a
  real fixed bug) - previously, restoring a soft-deleted, previously-shared item silently re-
  granted every old recipient access again, since only the item's own `deletedAtEpochMillis` flag
  blocked access, and the grant row itself was left dangling.
- **`ApiKey`/`X-API-Key` auth and this module's JWT auth are never combined** on one
  `DefaultRestFactory` instance - a deployment picks one mechanism per REST server instance.
- **Self-registration is open and public, deliberately.** `POST /auth/register`/
  `POST /auth/register/confirm` are this deployment's only way to create an `AuthUser` account -
  there is no separate operator-run account-creation tool.

## Scalability

- `CloudUserService#uploadFile`/`#deleteFile`/`#shareFile`/`#revokeFileShare` are O(1) regardless
  of how many files a user (or the system as a whole) already has, by design.
- Every full-section scan listed under "Performance" above (`listFileSummaries`,
  `listSharedWithMe`, `getCloudUserByEmail`, `AuthService#login`, ...) is O(n) in a system-wide
  entity count, not per-user - the fix if any of these becomes a bottleneck is a real indexed
  query (`WHERE emailAddress = ?`/`WHERE authUserId = ?`) exposed from the underlying
  database-driver up through `DataFactory`, which doesn't exist today. `AuthService#login`'s scan
  is the least "accepted" of these - it sits on the single hottest path in this module (every
  login) with no "called rarely" mitigation the way `listFileSummaries` has.
- `TrashPurgeScheduler` (in `cloud-driver-plugin`, not this module) is the only thing that would
  ever reduce trash-table size automatically, and it is deliberately never started by default - see
  the root README/`CLAUDE.md` for why. Left unwired, a deployment's trash only shrinks via
  `emptyTrash` calls or individual restores.
- Every entity this module defines is stateless/shareable across processes via the shared
  database - nothing here is held in process-local memory beyond `EntityDatabaseClient`'s own
  decrypted-entity cache (in `cloud-driver-plugin`), so this module places no constraint of its
  own on running multiple server instances against the same database.

## API surface

| Class | Package | Role |
|---|---|---|
| `AuthService` | `de.lino.cloud.auth` | The only `IAuthService` implementation - registration, login, 2FA, password/email reset, refresh tokens, admin flag |
| `CloudUserService` | `de.lino.cloud.auth` | The only `ICloudUserService` implementation - file/folder ownership, sharing, trash |
| `CloudUser` | `de.lino.cloud.auth.entity` | One end user's identifying record (`ICloudUser`, `Owned`) |
| `StoredFileOwnership` | `de.lino.cloud.auth.entity` | One (user, file) ownership row, including folder placement and trash state |
| `SharedFileGrant`/`SharedFolderGrant` | `de.lino.cloud.auth.entity` | One read-only sharing grant each |
| `RefreshToken` | `de.lino.cloud.auth.entity` | One long-lived, single-use, rotate-on-use refresh token |
| `PendingRegistration`/`PendingPasswordReset`/`PendingEmailChange`/`PendingTwoFactorSetup`/`PendingTwoFactorLogin` | `de.lino.cloud.auth.pending` | Short-lived state for each two-step verification flow |
| `JjwtSigner` | `de.lino.cloud.auth.jwt` | The only `JwtSigner` implementation, HMAC-SHA256 via jjwt |
| `SmtpEmailSender` | `de.lino.cloud.auth.mail` | The only production `EmailSender`: SMTP+STARTTLS via Jakarta Mail/Angus Mail |
| `LoggingEmailSender` | `de.lino.cloud.auth.mail` | Dev-only `EmailSender` fallback: logs instead of actually sending |
| `EmailTemplates` | `de.lino.cloud.auth.mail` | Builds the shared HTML/plain-text verification-email body |
| `AuditLogServiceImpl` | `de.lino.cloud.auth.audit` | The only `AuditLogService` implementation - persists `AuditEvent` rows |

Contracts this module implements, defined in `cloud-driver-api`: `AuthUser`, `IAuthService`,
`JwtSigner`/`InvalidJwtException`, `InvalidCredentialsException`/`InvalidVerificationCodeException`/
`InvalidPasswordFormatException`/`InvalidRefreshTokenException`/`EmailAlreadyRegisteredException`/
`GranteeAccountNotFoundException`, `EmailSender`/`EmailDeliveryException`, `Owned`,
`ICloudUser`/`ICloudUserService`, `AuditLogService`/`AuditEvent`/`AuditAction`,
`Folder`/`StoredFile`/`StoredFileSummary`/`FileWithFolder`/`SharedFileSummary`/
`SharedFolderSummary`/`SharedFolderContents`/`TrashedFileSummary`/`TrashedFolderSummary`.

## API usage

```java
// Wiring (normally done once, e.g. in an extension's startup):
AuditLogService auditLogService = new AuditLogServiceImpl(dataFactory, SecretRedactor::redact);
ICloudUserService cloudUserService = new CloudUserService(dataFactory, fileFactory, auditLogService);
IAuthService authService = new AuthService(
    dataFactory, passwordHasher, jwtSigner, emailSender, cloudUserService, auditLogService
);

// Registration (two-step, e-mail-verified):
authService.register("jane@example.com", "Str0ng!Pass".toCharArray());
AuthTokens tokens = authService.confirmRegistration("jane@example.com", codeFromEmail);

// Everyday use:
LoginResult result = authService.login("jane@example.com", "Str0ng!Pass".toCharArray());
String userId = authService.validate(result.tokens().accessToken());

StoredFile uploaded = cloudUserService.uploadFile(userId, "report.pdf", bytes, null);
cloudUserService.shareFile(userId, uploaded.fileId(), "colleague@example.com");
List<SharedFileSummary> sharedWithColleague = cloudUserService.listSharedWithMe(colleagueAuthUserId);

cloudUserService.deleteFile(userId, uploaded.fileId());     // moves to trash, revokes the share above
cloudUserService.restoreFile(userId, uploaded.fileId());    // back out of trash - stays unshared
```

## Javadoc conventions

Every public/protected class, method, and field in this module carries Google-style Javadoc: a
short summary fragment ending in a period, a blank line, then `@param`/`@return`/`@throws` as
applicable. Two conventions carried over from the rest of this codebase apply here too:

- Concrete method parameters use Lombok's `@NonNull` (runtime-checked, generates the null check);
  interface/abstract method parameters (in `cloud-driver-api`) use `@NotNull`
  (`org.jetbrains.annotations`, documentation-only, since there's no method body to inject a check
  into).
- Instance-field access is always qualified `this.field`, never a bare `field` reference.

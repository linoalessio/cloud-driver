# cloud-driver-bootstrap

The runnable entry point that wires a real Postgres-backed `CloudDriver` together and starts
every subsystem a deployment needs: encrypted persistence, offline-safe file uploads, the
extension framework (which is also how the JWT-authenticated REST API gets started today - see
below), event registration, and the interactive terminal. Packaged as a single self-contained,
runnable jar via `maven-shade-plugin`.

## Coordinates

Not a library other modules depend on - this is the module that produces the shipped artifact.
`cloud-driver-bootstrap-1.0.1.jar` (shaded, runnable) is built via:

```
mvn -pl cloud-driver-bootstrap -am package
java -jar cloud-driver-bootstrap-1.0.1.jar
```

Depends on `cloud-driver-plugin` (every concrete implementation this module wires together) and
`cloud-driver-auth` directly (not just transitively - `CreateUserCli`/`LoginSample` import its
classes to construct an `AuthService` themselves).

## Structure

### `CloudBootstrap` - the real entry point

`CloudBootstrap.main` boots the `CloudDriver` singleton, then starts several independent
subsystems concurrently, each on its own thread via its own `startX()` method, before blocking
only the real main thread on one shared shutdown latch:

```java
public static void main(String[] args) throws IOException {
    CLOUD_DRIVER = initiateCloudDriver().orElseThrow();

    MultiTaskingFactory.getInstance().runTaskInMainSafety(() -> {
        loadSecurityRequirements();

        Runnable[] runnable = {
            startTerminalBootstrap(),
            startPendingUploadScheduler(),
            startEventScheduler(DatabaseWatchEvent.class, ExtensionRegisterEvent.class, ExtensionUnregisterEvent.class),
            startExtensionsBootstrapScheduler(args),
            stopTerminal(),
        };

        prepareShutdownLatch(runnable).orElseThrow().await();
    });
}
```

- **`initiateCloudDriver()`** (package-private, not `public` - reused by `CreateUserCli`/
  `LoginSample` for the same wiring without pulling in the rest of `main`'s subsystem startup)
  resolves `Credentials` from `Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")`,
  registers a Postgres `DatabaseProvider`, builds a `DatabaseKeyEncryptionService`/
  `EnvelopeEncryptionService` backed by a `"kek"` database section, and installs the `CloudDriver`
  singleton via `DefaultCloudDriver.setInstance`.
- **Each `startX()` method** starts its subsystem's real work on its own thread (or hands it off
  to `MultiTaskingFactory`'s shared virtual-thread executor) and returns a `Runnable` shutdown
  action, rather than blocking the caller:
  - `startTerminalBootstrap()` starts the interactive terminal (`CloudDriver.getInstance().getTerminal().start()`).
  - `startPendingUploadScheduler()` starts a `PendingUploadScheduler` on its own ticker thread
    (1-minute tick), retrying anything queued while offline once connectivity returns.
  - `startEventScheduler(...)` registers `DatabaseWatchEvent`/`ExtensionRegisterEvent`/
    `ExtensionUnregisterEvent` via `EventFactory#registerEventAsync`.
  - `startExtensionsBootstrapScheduler(args)` scans both `Constraints.WORKING_DIRECTORY` (where
    the placeholder `CloudBootstrapExtension` lives - see below) and `Constraints.EXTENSIONS_PATH`
    for `Extension`s via `ExtensionFolderScanner`, registers everything found, fires an
    `ExtensionRegisterEvent` per registration, then starts all of them via
    `ExtensionFactory#startAllAsync`. **This is also how the JWT-authenticated REST API gets
    started today** - `cloud-driver-extensions-rest`'s `CloudRestExtension` is just another
    `Extension` discovered and started this same way; `CloudBootstrap` itself no longer has a
    dedicated `startRestApi` method of its own.
  - `stopTerminal()` doesn't start anything at call time - it only builds the shutdown action
    (interrupting the terminal's reading thread) that runs later.
- **`prepareShutdownLatch(Runnable... tasks)`** collects every returned shutdown action into one
  list and wires a single `Runtime.addShutdownHook` that runs all of them (so an in-flight flush
  isn't cut off mid-upload by an abrupt kill) before counting down the shared latch `main` awaits.
- **`loadSecurityRequirements()`** uploads `architecture/SECURITY_REQUIREMENTS.md` as a
  `StoredFile` under a fixed id (`Constraints.REQUIREMENTS_UUID`), if not already present -
  guarantees the `storedfile` table exists before any extension (e.g. a Postgres
  change-notification watcher) tries to watch it. Runs synchronously, before any subsystem
  starts.
- **`loadDummyFileUpload()`** is a ready-made manual smoke-test upload of the repo's own root
  `pom.xml` under a fresh random id - currently **not called** from `main`'s `runnable[]`
  sequence or anywhere else; kept as a one-off to invoke ad hoc rather than deleted.

### `CloudBootstrapExtension`

A no-op placeholder `Extension` representing `cloud-driver-bootstrap` itself, registered under
the name `"cloud-driver-bootstrap"` (from its `extension.json`) purely so other extensions can
declare a dependency on the host bootstrap via `ExtensionFactory`'s ordinary dependency-ordering
mechanism (`extension.json`'s `dependencies` list). All four lifecycle hooks are empty.

### `CreateUserCli`/`LoginSample` - operator-run account tools

Both live under `src/test` (the repo's "runnable worked example with a `main` method, not an
`mvn test` target" convention) but are real, hand-run tools, not disposable samples:

- **`CreateUserCli`** (`java -cp cloud-driver-bootstrap-*.jar de.lino.cloud.bootstrap.CreateUserCli <email>`)
  reuses `CloudBootstrap.initiateCloudDriver()` for the same Postgres/key-service wiring `main`
  itself uses, reads a password via `System.console().readPassword(...)`, constructs an
  `Argon2idPasswordHasher` + `JjwtSigner` (keyed from `configuration.json`'s `"jwt-signing-key"`)
  + `AuthService`, calls `register` then immediately `login`, and prints the resulting JWT - one
  run produces both a working account and a token ready to test against a JWT-gated route.
  Deliberately **not** a public HTTP endpoint - see `cloud-driver-auth`'s README for why.
- **`LoginSample`** is the same shape minus the `register` call - obtains a **fresh** token for
  an account that already exists (e.g. once a previous token has expired), without recreating it.

Both require a real, interactive terminal (`System.console()` returns `null` in an IDE's Run tool
window or a piped/non-interactive process, matching the same restriction the `terminal` package's
`jline`-based `Terminal` has) and zero the password `char[]` in a `finally` block once used.

### Packaging - the shaded jar

`cloud-driver-bootstrap`'s `pom.xml` runs `maven-shade-plugin` at `package`, producing one
self-contained, runnable `cloud-driver-bootstrap-1.0.1.jar` with every dependency shaded in and a
`Main-Class: de.lino.cloud.bootstrap.CloudBootstrap` manifest entry (via
`ManifestResourceTransformer`). Two extra transformers matter here:

- `ServicesResourceTransformer` merges `META-INF/services` entries across every merged jar, so
  e.g. the Postgres JDBC driver's `java.sql.Driver` SPI registration survives the merge instead of
  being silently overwritten by another dependency's copy of the same file.
- A filter strips `META-INF/*.SF`/`.DSA`/`.RSA` from every merged jar - `bcprov-jdk18on` ships
  signed, and leaving its signature files in a re-packaged jar throws "Invalid signature file
  digest" at runtime since the merged jar no longer matches what was originally signed.

The plain `maven-jar-plugin` output (`original-cloud-driver-bootstrap-1.0.1.jar`) is kept
alongside but is not runnable on its own - `java -jar` on it fails with "no main manifest
attribute", and even with a manifest added, it would still be missing every dependency.

## Performance and async design

`main` starts every subsystem, then blocks **only the real main thread**, never any subsystem's
own thread:

```java
MultiTaskingFactory.getInstance().runTaskInMainSafety(() -> {
    // ... start every startX() subsystem ...
    shutdownLatch.await();
});
```

`runTaskInMainSafety` - **not** `runAsync`/`supplyAsync` - is deliberate, not a stylistic choice:
`PendingUploadScheduler`'s ticker thread is a daemon thread, and virtual threads (what
`runAsync`/`supplyAsync` dispatch onto) are *also* always daemon threads. If the shutdown latch
were awaited via `runAsync` instead, the process would be left with nothing but daemon threads
running once `main` itself returned - and the JVM exits the instant only daemon threads remain,
before a scheduler tick or an extension's long-running `onRunning` ever gets a real chance to do
anything. `runTaskInMainSafety` blocking the actual (non-daemon) main thread is what keeps the
process alive; it additionally shuts `MultiTaskingFactory`'s shared executor down and awaits its
termination once the latch releases, as its own final action - so it must be the very last thing
`main` does, since nothing here submits further tasks afterward.

Consequences of this shape for anyone adding a new subsystem:

- Add another `startX()` method that starts its real work on its own thread (a dedicated
  `Thread`, `*Async` dispatch, or similar) and returns a shutdown `Runnable` - never a method
  that blocks the calling thread itself.
- Include that method's call in `main`'s `runnable[]` array so its shutdown action is registered.
- **Never** add another blocking loop (`while(true)`, or otherwise) directly inside `main` - only
  one thread (the real main thread, via the one shared latch) should ever be blocked there.

## Data handling and safety

- **Live secrets are never inlined in source.** `CloudBootstrap.main`/`initiateCloudDriver()`
  resolves Postgres credentials from `Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")`
  at runtime; the JWT signing key (`CreateUserCli`/`LoginSample`, and
  `cloud-driver-extensions-rest`'s `CloudRestExtension`) comes from the `"jwt-signing-key"` field
  of a sibling `configuration.json` in the same directory. Both files live under
  `Constraints.CONFIGURATION_PATH` (a `cloud-driver` subdirectory of the JVM's working
  directory), which is gitignored - never commit real credentials found there.
- **`CreateUserCli`/`LoginSample` never accept a password as a CLI argument.** Both read it via
  `System.console().readPassword(...)`, so it never lands in shell history or a process listing
  (`ps`), and both zero the password `char[]` in a `finally` block once it's no longer needed.
- **The KEK (key-encryption key) is itself persisted through the same database**, via
  `DatabaseKeyEncryptionService` backed by a `"kek"` `DatabaseSection` - shared across every
  process talking to the same Postgres instance, rather than bound to one machine's filesystem
  (contrast `cloud-driver-plugin`'s `FileKeyEncryptionService`/`InMemoryKeyEncryptionService`,
  neither of which this module uses).
- **`loadSecurityRequirements()` runs before any extension starts**, specifically so the
  `storedfile` table is guaranteed to exist by the time an extension that watches it for change
  notifications (e.g. `cloud-driver-extensions-watcher`'s `CloudWatcherExtension`) installs its
  trigger - a `CREATE TRIGGER` against a table that doesn't exist yet would otherwise fail
  (silently logged, not thrown, by the underlying `SQLExecution`).

## Scalability

This module itself holds no state that grows with load - every subsystem it starts either
delegates to `cloud-driver-plugin`'s own scalability characteristics (`EntityDatabaseClient`'s
per-type sections/caches, `MultiTaskingFactory`'s virtual-thread executor) or is a fixed-cost,
one-time startup step (`loadSecurityRequirements`, extension registration). The one thing that
does scale with the *number of extensions* dropped into `Constraints.EXTENSIONS_PATH` is
`startExtensionsBootstrapScheduler`'s per-extension `ExtensionRegisterEvent` dispatch loop - see
this module's findings list (produced alongside this documentation pass) for a concrete note on
that loop's current blocking-dispatch behavior.

## Javadoc conventions

Every public/protected class, method, and field in this module now carries Google-style Javadoc:
a short summary fragment ending in a period, a blank line, then `@param`/`@return`/`@throws` as
applicable - including `CreateUserCli`/`LoginSample`'s `main` methods, documented here as real
worked examples rather than left bare. The same conventions used throughout the rest of this
codebase apply: Lombok's `@NonNull` on concrete method parameters, `this.field` (never a bare
`field`) for instance-variable access.

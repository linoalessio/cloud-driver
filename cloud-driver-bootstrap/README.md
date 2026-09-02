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
`cloud-driver-auth` directly (not just transitively - declared explicitly on this module's own
`pom.xml` since account creation/login now happens exclusively over HTTP, through
`cloud-driver-extensions-rest`'s `CloudRestExtension`, which itself constructs an `AuthService`
from `cloud-driver-auth` types). Also declares `io.micrometer:micrometer-registry-prometheus`
directly, even though no class in this module imports it - every extension jar
`ExtensionFolderScanner`/`ExtensionJarLoader` loads runs off its own `URLClassLoader`, parent-first
against *this* shaded jar's own classpath, so a third-party library an extension needs (here,
`cloud-driver-extensions-metrics`) must already be shaded in here for that extension's classes to
resolve it at runtime.

Dependency chain this module sits at the end of: `cloud-driver-api` ← `cloud-driver-auth` ←
`cloud-driver-plugin` ← `cloud-driver-bootstrap`. Nothing in this repo depends on
`cloud-driver-bootstrap` - it is the runnable end of the chain, not a library.

## Structure

### `CloudBootstrap` - the real entry point

`CloudBootstrap.main` boots the `CloudDriver` singleton, then starts several independent
subsystems concurrently, each on its own thread via its own `startX()` method, before blocking
only the real main thread on one shared shutdown latch:

```java
public static void main(String[] args) throws IOException {
    CLOUD_DRIVER = initiateCloudDriver().orElseThrow();

    MultiTaskingFactory.getInstance().runTaskInMainSafety(() -> {
        initDefaultFile();

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

- **`initiateCloudDriver()`** (package-private, not `public`, since nothing outside this package
  needs it) resolves `Credentials` from `Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")`,
  registers a Postgres `DatabaseProvider`, builds an `AwsKmsKeyEncryptionService` (reading
  `"aws-kms-region"`/`"aws-kms-key-id"` from a sibling `configuration.json` - KEK material never
  leaves AWS's own KMS/HSMs; the older `DatabaseKeyEncryptionService` line is left in as a
  commented-out `// TODO: remove` in case of rollback, not actually used) wrapped in an
  `EnvelopeEncryptionService`, and installs the `CloudDriver` singleton via
  `DefaultCloudDriver.setInstance(databaseProvider, envelopeEncryptionService,
  ALWAYS_AVAILABLE_CONNECTIVITY_CHECKER)`. That third argument is this module's own
  `ConnectivityChecker` - a fixed `() -> true`, deliberately replacing the default
  `InternetConnectivityChecker` (which probes public DNS resolvers to answer "is there a network
  connection at all"). This deployment's Postgres instance runs on the same machine as this
  process, so that question is irrelevant here and, per a real incident, actively harmful: under a
  burst of concurrent uploads, several of those probes could spuriously time out and report
  "offline" even though the local database connection never wavered, silently deferring real
  uploads into the pending-upload queue while the caller was told the upload succeeded. See
  `CloudBootstrap`'s own field-level Javadoc on `ALWAYS_AVAILABLE_CONNECTIVITY_CHECKER` for the
  full writeup.
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
- **`initDefaultFile()`** uploads a placeholder `StoredFile` (`"init.txt"`, empty
  content) under a fixed id (`Constraints.REQUIREMENTS_UUID`), if not already present -
  guarantees the `storedfile` table exists before `startExtensionsBootstrapScheduler` starts
  `cloud-driver-extensions-watcher`'s `CloudWatcherExtension`, which watches that table for
  change notifications and would otherwise try to install a trigger on a table that doesn't
  exist yet. Runs synchronously, before any subsystem starts.

### `CloudBootstrapExtension`

A no-op placeholder `Extension` representing `cloud-driver-bootstrap` itself, registered under
the name `"cloud-driver-bootstrap"` (from its `extension.json`) purely so other extensions can
declare a dependency on the host bootstrap via `ExtensionFactory`'s ordinary dependency-ordering
mechanism (`extension.json`'s `dependencies` list). All four lifecycle hooks are empty.

### Account creation/login - no CLI, HTTP only

There is no operator-run CLI for creating or logging in accounts in this module -
`find cloud-driver-bootstrap -name '*.java'` returns only `CloudBootstrap.java`/
`CloudBootstrapExtension.java`. An earlier `CreateUserCli`/`LoginSample` pair (both under
`src/test`) has been removed: `POST /auth/register` + `POST /auth/register/confirm` (self-service,
e-mail-verified signup) and `POST /auth/login`, both mounted by `cloud-driver-extensions-rest`'s
`CloudRestExtension` once it starts as part of `startExtensionsBootstrapScheduler` above, are now
the only way to create an account or obtain a JWT.

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

## Data handling

The only entity this module itself creates is the placeholder `StoredFile` `initDefaultFile()`
uploads at startup (see above) - everything else it persists (`AuthUser`, `PendingRegistration`,
`CloudUser`, `StoredFileOwnership`, user-uploaded `StoredFile`s) flows through `cloud-driver-plugin`/
`cloud-driver-auth` classes this module only wires together, not code it defines itself.

## Safety & security

- **Live secrets are never inlined in source.** `CloudBootstrap.main`/`initiateCloudDriver()`
  resolves Postgres credentials from `Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")`
  at runtime; the JWT signing key (read by `cloud-driver-extensions-rest`'s `CloudRestExtension`,
  not by this module) comes from the `"jwt-signing-key"` field of a sibling `configuration.json`
  in the same directory. Both files live under `Constraints.CONFIGURATION_PATH` (a `cloud-driver`
  subdirectory of the JVM's working directory), which is gitignored - never commit real
  credentials found there.
- **The KEK (key-encryption key) never leaves AWS's own KMS/HSMs.** `initiateCloudDriver()` wires
  an `AwsKmsKeyEncryptionService` (region/key id read from `configuration.json`'s
  `"aws-kms-region"`/`"aws-kms-key-id"`, credentials resolved by the AWS SDK's own default
  credential provider chain - deliberately not read from `configuration.json` itself, a third
  place a secret could be committed by mistake); this process only ever holds an unwrapped DEK and
  KMS's own opaque wrapped-key blob, never the KEK material itself. An earlier
  `DatabaseKeyEncryptionService`-backed line (KEK persisted as a `"kek"` `DatabaseSection` in the
  same Postgres instance - contrast `cloud-driver-plugin`'s `FileKeyEncryptionService`/
  `InMemoryKeyEncryptionService`, neither of which this module uses either) is left commented out
  in source (`// TODO: remove`) in case of rollback, but is not the active code path.
- **`initDefaultFile()` runs before any extension starts**, specifically so the
  `storedfile` table is guaranteed to exist by the time an extension that watches it for change
  notifications (e.g. `cloud-driver-extensions-watcher`'s `CloudWatcherExtension`) installs its
  trigger - a `CREATE TRIGGER` against a table that doesn't exist yet would otherwise fail
  (silently logged, not thrown, by the underlying `SQLExecution`).

## Scalability

This module itself holds no state that grows with load - every subsystem it starts either
delegates to `cloud-driver-plugin`'s own scalability characteristics (`EntityDatabaseClient`'s
per-type sections/caches, `MultiTaskingFactory`'s virtual-thread executor) or is a fixed-cost,
one-time startup step (`initDefaultFile`, extension registration). The one thing that scales with
the *number of extensions* dropped into `Constraints.EXTENSIONS_PATH` is
`startExtensionsBootstrapScheduler`'s per-extension `ExtensionRegisterEvent` dispatch loop
(`extensionFactory.getExtensions().forEach(...)`) - a plain synchronous `forEach` on the calling
thread (still inside `runTaskInMainSafety`'s call, before the shutdown latch is even constructed),
so a very large number of registered extensions would delay reaching `shutdownLatch.await()`
proportionally; not a concern at this deployment's current extension count (six, per
`cloud-driver-extensions`), but worth knowing if that count grows substantially.

## API surface

This module produces a runnable jar, not a library - nothing else in the repo depends on it. Its
public surface is effectively just the entry point:

- **`CloudBootstrap`** - `public static void main(String[] args)`, the shaded jar's `Main-Class`.
  No other public members.
- **`CloudBootstrapExtension`** - a no-op `Extension` subclass other extensions declare an
  `extension.json` dependency on (`"cloud-driver-bootstrap"`) to require the host bootstrap be
  present before they start.

With no library API to demonstrate a caller using, the "Coordinates" section's `mvn package`/
`java -jar` snippet above stands in as this README's usage example - the actual way anyone
"uses" this module.

## Javadoc conventions

Every public/protected class, method, and field in this module carries Google-style Javadoc: a
short summary fragment ending in a period, a blank line, then `@param`/`@return`/`@throws` as
applicable. The same conventions used throughout the rest of this codebase apply: Lombok's
`@NonNull` on concrete method parameters, `this.field` (never a bare `field`) for instance-variable
access.

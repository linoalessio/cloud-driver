# cloud-driver-extensions

Parent aggregator (`packaging=pom`, no Java source of its own) for `cloud-driver`'s runtime extensions - the concrete plugins built on the `Extension`/`ExtensionFactory` framework `cloud-driver-api` defines and `cloud-driver-bootstrap` loads at startup. This module only exists to group them under one Maven reactor build; every behavior described below lives in one of the submodules.

## Current submodules

| Submodule | `extension.json` name | State | See |
|---|---|---|---|
| [`cloud-driver-extensions-watcher`](cloud-driver-extensions-watcher/README.md) | `cloud-driver-watcher` | real source | Postgres `LISTEN`/`NOTIFY` change notification |
| [`cloud-driver-extensions-terminal`](cloud-driver-extensions-terminal/README.md) | `cloud-driver-terminal` | real source | interactive `jline` console + built-in commands |
| [`cloud-driver-extensions-backup`](cloud-driver-extensions-backup/README.md) | `cloud-driver-backup` | real source | keyset-paginated, streaming Postgres backup job |
| [`cloud-driver-extensions-rest`](cloud-driver-extensions-rest/README.md) | `cloud-driver-rest-server` | real source | JWT-authenticated REST API over `RestFactory` |

All four are declared as `<module>`s in this aggregator's `pom.xml` and depend, directly or transitively, on `cloud-driver-plugin` and/or `cloud-driver-api`.

### `cloud-driver-extensions-web` - not a module

CLAUDE.md describes a `cloud-driver-extensions-web` module as "removed". The actual state on disk today is more precisely: the directory `cloud-driver-extensions/cloud-driver-extensions-web/` still exists, containing empty `src/main/java`, `src/main/resources`, and `src/test/java` directory trees with **zero files** in them - no `pom.xml`, no `.java` sources, no `extension.json`. It is **not** listed in this aggregator's `<modules>`, so Maven never touches it during a build. It is not a placeholder submodule in the sense `app`/`mobile` might once have been (those aren't present here at all) - it is an orphaned, empty directory left behind after the module's real content (an `AuthPanelServer`/`CloudWebExtension` static register/login browser panel, per CLAUDE.md) was deleted. Nothing currently reserves this name for future work; if it's revived, it needs a fresh `pom.xml` and a `<module>` entry here before Maven will build it at all.

## The `Extension`/`ExtensionFactory` framework, at a glance

Every submodule above is a concrete `Extension` subclass - this section covers only the shared mechanics; see each submodule's own README for what its extension actually *does*. Full details live in `cloud-driver-api`'s own Javadoc/README (`de.lino.cloud.api.extension`, `de.lino.cloud.api.factory.ExtensionFactory`) and in the root `CLAUDE.md`; this is a map, not a restatement.

- **An extension is a class, not a service description.** Subclass `Extension`, implement four lifecycle hooks (`onLoading`, `onRunning(String[])`, `onEnding`, `onException(RuntimeException)`), and ship an `extension.json` resource alongside it. `Extension`'s own constructor only loads that `extension.json` (via `ExtensionPropertiesLoader`) and detects the build tool that produced the class's jar - it does **not** register the instance anywhere.
- **`extension.json` is the identity + dependency declaration.** Required fields: `name`, `version`. Optional: `authors`, `dependencies` (a list of other extensions' own `name` values). Every submodule here depends on `"cloud-driver-bootstrap"` - the name `CloudBootstrapExtension` registers under - so none of them can start before the host application itself is up and running.
- **Registration is manual, discovery is automatic.** `CloudBootstrap`'s `startExtensionsBootstrapScheduler` scans two locations via `ExtensionFolderScanner`: the JVM's own working directory (`user.dir`, non-recursively, for `CloudBootstrapExtension` bundled in the running jar) and `Constraints.EXTENSIONS_PATH` (a sibling `extensions/` folder, for every third-party extension jar dropped there). For each `*.jar` found, `ExtensionJarLoader` gives it its own `URLClassLoader` (parent = `Extension`'s own classloader, so `cloud-driver-api` types resolve identically on both sides of the boundary), scans its `.class` entries for a concrete `Extension` subclass, and reflectively constructs it via its no-arg constructor - a jar only yields an extension if it both declares such a subclass *and* ships a valid `extension.json`. Every extension found this way (plus any explicitly-passed instances) is registered via `ExtensionFactory#register`.
- **Startup is dependency-ordered.** `ExtensionFactory#startAll` topologically sorts every registered extension over its own `getDependencies()` before starting any of them, throwing on a cycle. `start` additionally requires - for a direct call, not just as part of `startAll` - that every declared dependency is already registered **and** `RUNNING`.
- **Each extension gets its own dedicated, non-daemon thread.** `start` spawns a `Thread` named `"extension-" + extensionName` for `onLoading`+`onRunning`, deliberately *not* a task on `MultiTaskingFactory`'s shared virtual-thread pool - an extension's `onRunning` may itself run indefinitely (e.g. `cloud-driver-extensions-terminal`'s reading loop, or `cloud-driver-extensions-watcher`'s blocking `LISTEN` read), and isolating that on its own thread keeps it from ever starving the shared pool's carrier threads. This is in-process isolation only: a crash in one extension's thread can still take the whole JVM down, and a thread blocked on I/O can't be force-stopped, unlike true process-per-extension isolation.
- **Failure in one extension doesn't abort the others.** A `RuntimeException` from `onLoading`/`onRunning` is caught, the extension's status flips to `ERROR`, and it's routed to that same extension's `onException` - `startAll`/`stopAll` keep going for every other registered extension regardless. `stop` calls `onEnding()` then interrupts the tracked thread in a `finally` block, so a throwing `onEnding()` still gets its thread untracked/interrupted rather than leaked.
- **`Extension#cloudDriver()`** is the standard way an extension reaches the host's `CloudDriver` singleton from inside a lifecycle hook (`Asserts.requireNonNull(CloudDriver.getInstance())` under the hood) - every submodule here uses it rather than caching a reference itself.

## Shared conventions across these submodules

- **Javadoc.** Google Java Style Guide conventions throughout: a short summary fragment ending in a period, a blank line, then `@param`/`@return`/`@throws` as applicable. English only.
- **Field access.** Instance fields are always accessed as `this.field`, never bare `field` - a repo-wide convention, not specific to extensions.
- **Logging.** An extension reaches the shared, process-wide logger via `this.cloudDriver().getLogger()` (or the `Extension#getLogger()` shortcut) rather than constructing its own `Logger`.
- **No test framework.** None of these modules wire up JUnit; `src/test` trees under this aggregator are currently empty across the board (verify with `find cloud-driver-extensions -path '*/src/test/*' -type f` before assuming otherwise, since that can change).

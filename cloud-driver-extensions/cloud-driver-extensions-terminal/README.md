# cloud-driver-extensions-terminal

Registers every built-in `Command` on the host `CloudDriver`'s `jline`-based interactive
`Terminal` and starts its reading loop - the first, and currently only, real consumer of
`cloud-driver-api`'s `terminal` package (`de.lino.cloud.api.terminal`). That package deliberately
implements only the engine (`Command`/`CommandService` exist so the reading loop has something to
dispatch into, not as a catalog of real commands); this module is the catalog. Without this
extension (or something equivalent) registered, a `Terminal` is constructed and receives log
output, but never actually reads a line of input, no matter how many `Command`s exist elsewhere.

## Project structure

Reactor position: a child of the `cloud-driver-extensions` aggregator (`packaging=pom`), sibling
of `cloud-driver-extensions-backup`/`-rest`/`-watcher`/`-metrics`. `pom.xml` (`packaging=jar`)
declares exactly one in-repo dependency, `cloud-driver-api` - notably **not**
`cloud-driver-plugin`, unlike this module's sibling extensions: every type this module touches
beyond the `terminal` package itself (`ICloudUserService`, `IAuthService`, `FileFactory`,
`ExtensionFactory`, `DataFactory`, `AuditEvent`) is a `cloud-driver-api` contract, reached via
`CloudDriver.getInstance().getFactoryContainer()`/`.getServiceContainer()`, so it never needs a
concrete `cloud-driver-plugin`/`cloud-driver-auth` type at compile time.

`extension.json` declares `"name": "cloud-driver-terminal"` and a single dependency,
`["cloud-driver-bootstrap"]` - `ExtensionFactory#start` requires that extension be registered and
`RUNNING` first, matching the repo-wide convention that every extension depends on the bootstrap
placeholder it's hosted by.

Package layout:
- `de.lino.cloud.extensions.terminal` - `CloudTerminalExtension` itself.
- `de.lino.cloud.extensions.terminal.command` - the general-purpose commands: `ExitCommand`,
  `HelpCommand`, `ClearCommand`, `ExtensionCommand`, `LeaveCommand`, `CloudUserCommand`,
  `RecomputeStorageCommand`.
- `de.lino.cloud.extensions.terminal.command.system` - the operator/system-level commands:
  `StatisticsCommand`, `DispatchCommand`, `HardResetCommand`, `AdminCommand`, `AuditLogCommand`.

## Performance

- **`ReadingThread` is non-daemon by design**, inherited from whichever thread constructed the
  `Terminal` instance (typically the real JVM main thread, via `DefaultCloudDriver.setInstance`) -
  fixed at construction time regardless of which thread later calls `.start()` on it. Starting it
  is what keeps the process alive for as long as the terminal is open, mirroring `PoloCloud`'s own
  reading-thread design.
- **This extension's own `onLoading`/`onRunning` run on `ExtensionFactory#start`'s dedicated,
  named `"extension-cloud-driver-terminal"` thread - which is a *daemon* thread**
  (`ExtensionFactory` calls `thread.setDaemon(true)` unconditionally for every extension). It is
  `readingThread().start()` - called from inside that daemon thread's own `onRunning` - that is
  what actually keeps the JVM alive afterward, not the daemon dispatch thread itself; the two are
  independent threads with independent daemon status.
- **Every dispatched command runs on `MultiTaskingFactory`'s shared virtual-thread executor**, not
  the reading thread itself - `ReadingThread` always calls `CommandService#dispatchAsync`, so a
  slow command (e.g. `DispatchCommand` waiting on a long-running child process) never delays the
  next line of input being read.
- **`DispatchCommand`/`LeaveCommand` block their own dispatched virtual thread on
  `Process#waitFor()`** while relaying output line-by-line via a blocking `BufferedReader` read
  loop - acceptable given they already run off the reading thread, but a genuinely long-running
  dispatched process still pins one virtual thread for its entire duration.
- **`ExtensionCommand`'s `start`/`stop` actions run synchronously** on whatever virtual thread
  dispatched the command - `ExtensionFactory#start`/`#stop` themselves return promptly (they
  spawn/interrupt the target extension's own dedicated thread rather than blocking on its
  `onRunning`), so this is not a practical concern.
- **`StatisticsCommand`/`AuditLogCommand` each read the relevant section in full** (`FileFactory
  #getEntitiesAsync()` for every `StoredFile`, `DataFactory#getEntities(AuditEvent.class)` for
  the whole audit trail) rather than any indexed/paginated query - acceptable for an operator
  console invoked on demand, not something either command runs repeatedly or under load.
  `StatisticsCommand` was fixed (2026-09-02) to call `getEntitiesAsync()` only once and reuse the
  result for both its file-count and total-bytes figures, after that duplicate call was found to
  be one of the contributors to a real `OutOfMemoryError` incident on the live deployment.

## Data handling

This module defines no `Serialized` entities of its own - every command here only reads (and, in
`HardResetCommand`/`CloudUserCommand`/`AdminCommand`, occasionally mutates) entities owned by
other modules, reached exclusively through `cloud-driver-api` contracts:

- `CloudUserCommand`/`RecomputeStorageCommand` read/mutate `ICloudUser` (`currentUploadedBytes`,
  `maxBytesToUpload`, owned files) via `ICloudUserService`, resolved from
  `CloudDriver.getInstance().getServiceContainer().getCloudUserService()`.
- `AdminCommand` reads/mutates `AuthUser#isAdmin()` via `IAuthService#setAdmin` - **the only
  writer of that flag anywhere in this codebase** (see "Safety & security" below).
- `AuditLogCommand` reads `AuditEvent` rows (read-only - it never writes one) via
  `DataFactory#getEntities(AuditEvent.class)`, and resolves an actor id back to an e-mail address
  via `IAuthService`.
- `StatisticsCommand` reads `StoredFile` (via `FileFactory`), `ICloudUser` (via
  `ICloudUserService`), and `Extension`/`ExtensionProperties` (via `ExtensionFactory`) - all
  read-only.
- `HardResetCommand` triggers a full wipe of every `Serialized` entity section via
  `CloudDriver#reset()` - the one command in this module capable of destroying data outright.
- `ExtensionCommand` reads/mutates `Extension`/`ExtensionStatus` state via `ExtensionFactory`, and
  dispatches `ExtensionRegisterEvent`/`ExtensionUnregisterEvent` on `start`/`stop`.
- Several commands (`CloudUserCommand`, `AdminCommand`, `RecomputeStorageCommand`,
  `AuditLogCommand`) resolve `ICloudUserService`/`IAuthService` off `IServiceContainer` and must
  handle it still being `null` - both are only published once
  `cloud-driver-extensions-rest`'s `CloudRestExtension` has actually run, and nothing declares a
  dependency from this module's `extension.json` onto that one, so `cloud-driver-terminal` can
  legitimately start before, after, or entirely without it running.

## Safety & security

- **`HardResetCommand`'s two-confirmation guard.** A first invocation only arms the reset (a
  5-second confirmation window) and prints a warning; a second invocation within that window
  actually calls `CloudDriver#reset()` (wiping every entity section, no undo) and then
  `CloudDriver#shutdown()`. An expired window resets the arming state, requiring the sequence to
  be restarted rather than silently applying a much older confirmation.
- **`AdminCommand` is deliberately the only place in the entire codebase that can grant/revoke
  `AuthUser#isAdmin()`.** `IAuthService#setAdmin` is never reachable via any REST route - exposing
  it over HTTP, even behind a check, would be a privilege-escalation hole the moment that check
  itself had a bug. `admin grant <email>`/`admin revoke <email>` resolve the target account by a
  case-insensitive e-mail scan over `IAuthService#getAuthUsers()`.
- **`ExtensionCommand` refuses to `start`/`stop` `"cloud-driver-bootstrap"` itself** - special-
  cased explicitly, to prevent an operator from accidentally tearing down the very extension that
  hosts the whole running process (and, transitively, this terminal).
- **`DispatchCommand` runs any shell command the terminal operator types, with the process's own
  privileges** - by design (this is an operator console, not a public-facing surface), but worth
  flagging explicitly: there is no allowlist, sandboxing, or confirmation step before execution.
- **`LeaveCommand` only acts if the `STY` environment variable is set** (i.e. the process is
  actually running inside a `screen` session) - a no-op with a message otherwise, never a failed
  shell invocation against a session that doesn't exist.
- **`RecomputeStorageCommand`/`AuditLogCommand` are both explicitly operator-triggered, never
  wired to run automatically** on startup or on a schedule - the same "run it deliberately"
  precedent `HardResetCommand` set first; `AuditLogCommand` is strictly read-only regardless.

## Scalability

This module has no meaningful data-volume scalability dimension of its own - it's an interactive,
single-operator console, not a component processing bulk/concurrent client traffic. Its only
genuine scaling concern is command-dispatch latency, already addressed by dispatching every
command on a virtual thread rather than the reading thread itself (see "Performance" above).
`StatisticsCommand`/`AuditLogCommand`/`CloudUserCommand list` each do scale linearly with total
system size (every uploaded file, every audit entry, every registered account, respectively) since
none of them page or filter server-side beyond what `cloud-driver-api`'s own contracts already
provide - acceptable for an on-demand operator command, not something this module optimizes for.

## API surface

- **`CloudTerminalExtension`** (`de.lino.cloud.extensions.terminal`) - the extension itself.
  `onLoading()` is a no-op; `onRunning(String[])` registers all twelve commands below and starts
  `this.cloudDriver().getTerminal().readingThread()` - **the only place in the whole codebase
  that starts the terminal's reading loop**; `onEnding()` is a no-op; `onException` logs the
  failure. Neither hook unregisters any command on the way out.
- **`ExitCommand`** (`exit`/`quit`/`q`) - calls `CloudDriver#shutdown()`.
- **`HelpCommand`** (`help`/`?`/`h`) - lists every registered command, its aliases, and
  description.
- **`ClearCommand`** (`clear`/`clc`) - clears the screen and reprints the banner.
- **`ExtensionCommand`** (`extensions`/`extension`/`ext`) - `list`/`info <name>`/`start
  <name>`/`stop <name>` against `ExtensionFactory`; the one command with real sub-argument
  dispatch and the only one (besides `HardResetCommand`'s own shutdown) that mutates extension
  lifecycle state from the terminal.
- **`LeaveCommand`** (`screen-leave`/`l`/`sl`) - detaches the current `screen` session without
  killing it.
- **`CloudUserCommand`** (`cloudUser`/`cu`/`user`) - `list`/`info <email>`/`reset
  <email>`/`delete <email>`/`update <email> <bytes>` against `ICloudUserService`.
- **`RecomputeStorageCommand`** (`recomputeStorage`/`recompute`) - `<email>` or `all`; recomputes
  an account's (or every account's) `currentUploadedBytes` from its actually-owned files via
  `ICloudUserService#recomputeUploadedBytes`, fixing drift for accounts that predate incremental
  usage tracking.
- **`StatisticsCommand`** (`statistics`/`stats`, in `.command.system`) - prints uptime/version,
  configured server storage capacity, registered extension count, registered cloud-user count,
  and total uploaded file count/storage used.
- **`DispatchCommand`** (`dispatch`/`exec`/`sudo`/`d`, in `.command.system`) - runs an arbitrary
  shell command via `ProcessBuilder`, streaming combined stdout/stderr and the exit code.
- **`HardResetCommand`** (`hardReset`/`reset`, in `.command.system`) - the two-confirmation,
  whole-database wipe plus shutdown described under "Safety & security" above.
- **`AdminCommand`** (`admin`/`isAdmin`, in `.command.system`) - `grant <email>`/`revoke <email>`
  against `AuthUser#isAdmin()`, described under "Safety & security" above.
- **`AuditLogCommand`** (`auditLog`/`audit`, in `.command.system`) - `auditLog` (most recent 20,
  newest first), `auditLog all` (every entry), `auditLog <email>` (most recent 20 for that
  account's actor id) against the persisted `AuditEvent` trail.

## API usage

This module exposes no library API - `CloudTerminalExtension` and its commands are loaded as a
jar dropped into `Constraints.EXTENSIONS_PATH` (or assembled there by
`shell/test-bootstrap.sh`), not called directly from Java. Build it alongside a bootstrap jar
built from the same commit:

```
mvn -pl cloud-driver-extensions/cloud-driver-extensions-terminal -am package
```

Once registered, its commands are typed directly into the operator terminal the host process
opens - no code sample applies there. To add a new command, implement `Command`
(`de.lino.cloud.api.terminal.service`) and register it the same way `CloudTerminalExtension`
registers the twelve above:

```java
CommandService commandService = this.cloudDriver().getTerminal().getCommandService();
commandService.register(new MyCommand());
```

# cloud-driver-extensions-terminal

Registers `Command` implementations on the host `CloudDriver`'s `jline`-based interactive `Terminal` and starts its reading loop - the first, and currently only, real consumer of `cloud-driver-api`'s `terminal` package (`de.lino.cloud.api.terminal`). Without this extension (or something equivalent), a `Terminal` is constructed and receives log output, but never actually reads a line of input, no matter how many `Command`s exist elsewhere.

## Project structure

Reactor position: a child of the `cloud-driver-extensions` aggregator (`packaging=pom`), sibling
of `cloud-driver-extensions-backup`/`-rest`/`-watcher`. Its `pom.xml` declares exactly one in-repo
dependency, `cloud-driver-api` - notably **not** `cloud-driver-plugin`, unlike the other three
extension modules: every type this module touches beyond the `terminal` package itself
(`ICloudUserService`, `IAuthService`, `FileFactory`, `ExtensionFactory`) is a `cloud-driver-api`
contract, reached via `CloudDriver.getInstance().getFactoryContainer()`/`getServiceContainer()`,
so it never needs a concrete `cloud-driver-plugin`/`cloud-driver-auth` type at compile time.
Package: `de.lino.cloud.extensions.terminal` (the extension itself) plus
`de.lino.cloud.extensions.terminal.command` (one class per `Command`).

## Why this exists

`cloud-driver-api`'s `terminal` package deliberately implements the engine only - `Command`/`CommandService` exist so the reading loop has something to dispatch into, not as a catalog of real commands. This module is that catalog: an operator console for a running `cloud-driver-bootstrap` process (or anything else hosting a `CloudDriver`), with commands to inspect/start/stop extensions, shut the process down, run an arbitrary shell command, and detach the `screen` session the process typically runs inside.

## `extension.json`

```json
{
  "name": "cloud-driver-terminal",
  "version": "1.0.0",
  "description": "CloudDriver terminal containing all commands inside the system",
  "authors": ["Lino Alessio Kauschinger"],
  "dependencies": ["cloud-driver-bootstrap"]
}
```

Depends only on `"cloud-driver-bootstrap"` being registered and `RUNNING` first.

## What registering this extension actually wires up

`CloudTerminalExtension#onRunning` registers seven `Command`s on `this.cloudDriver().getTerminal().getCommandService()`:

| Command | Aliases | Purpose |
|---|---|---|
| `ExitCommand` | `exit`/`quit`/`q` | Calls `CloudDriver#shutdown()` |
| `HelpCommand` | `help`/`?`/`h` | Lists every registered command, its aliases, and description |
| `ClearCommand` | `clear`/`clc` | Clears the screen and reprints the banner |
| `ExtensionCommand` | `extensions`/`extension`/`ext` | `list`/`info <name>`/`start <name>`/`stop <name>` against `ExtensionFactory` |
| `StatisticsCommand` | `about`/`ab` | Prints uptime, uploaded file count, and used storage |
| `LeaveCommand` | `screen-leave`/`l`/`sl` | Detaches the current `screen` session without killing it |
| `DispatchCommand` | `dispatch`/`exec`/`sudo`/`d` | Runs an arbitrary shell command via `ProcessBuilder`, streaming its output |

`onRunning` is also **the only place in the whole codebase that starts the terminal's reading loop** (`this.cloudDriver().getTerminal().readingThread().start()`) - `CloudBootstrap` itself has no equivalent `startTerminal()` subsystem. `onEnding`/`onException` are no-ops beyond logging (unlike, e.g., `cloud-driver-extensions-rest`, there is no explicit per-command unregistration here).

## Code structure

- **`CloudTerminalExtension`** (`de.lino.cloud.extensions.terminal`) - the extension; resolves `CommandService` once at field-initialization time via `this.cloudDriver().getTerminal().getCommandService()`.
- **`command/`** - one class per `Command`:
  - `ExitCommand`, `HelpCommand`, `ClearCommand`, `StatisticsCommand` - simple, single-purpose, no sub-arguments.
  - `ExtensionCommand` - the one command with real sub-argument dispatch (`list`/`info`/`start`/`stop`), and the only one that mutates extension lifecycle state from the terminal; refuses to `start`/`stop` `"cloud-driver-bootstrap"` itself.
  - `DispatchCommand`/`LeaveCommand` - shell out via `ProcessBuilder` (an arbitrary command, and `screen -X -S $STY detach`, respectively); both stream output and interpret exit codes rather than blocking silently.

## Performance / concurrency characteristics

- **`ReadingThread` is non-daemon by design** (inherited from `Terminal`'s own construction context, fixed at construction time regardless of which thread later calls `.start()` on it) - starting it is what keeps the process alive for as long as the terminal is open, mirroring `PoloCloud`'s own reading-thread design. This extension's `onRunning` runs on `ExtensionFactory#start`'s own dedicated, non-daemon `"extension-cloud-driver-terminal"` thread; which thread calls `readingThread().start()` has no bearing on the reading loop's own daemon-ness.
- **Every dispatched command runs on `MultiTaskingFactory`'s shared virtual-thread executor**, not the reading thread itself - `ReadingThread` always calls `CommandService#dispatchAsync`, so a slow command (e.g. `DispatchCommand` waiting on a long-running child process) never delays the next line of input being read.
- **`DispatchCommand`/`LeaveCommand` block their own dispatched virtual thread on `Process#waitFor()`** while relaying output line-by-line via a blocking `BufferedReader` read loop - acceptable given they already run off the reading thread, but note that a genuinely long-running dispatched process (e.g. `dispatch sleep 3600`) pins one virtual thread for its entire duration; virtual threads make this cheap in count, but it is still a thread held open, not fire-and-forget.
- **`ExtensionCommand`'s `start`/`stop` actions run synchronously** on whatever thread dispatched the command (a virtual thread from `dispatchAsync`) - `ExtensionFactory#start`/`#stop` themselves return promptly (they spawn/interrupt the extension's own dedicated thread rather than blocking on its `onRunning`), so this is not a concern in practice, but it does mean a slow `onLoading()` in the extension being started runs on that dispatch virtual thread until `start` returns.

## Data handling / safety considerations

- `DispatchCommand` runs **any** shell command the terminal operator types, with the process's own privileges - by design (it's an operator console, not a public-facing surface), but worth flagging explicitly: there is no allowlist, sandboxing, or confirmation step before execution.
- `LeaveCommand` only acts if the `STY` environment variable is set (i.e., the process is actually running inside a `screen` session) - a no-op with a message otherwise, never a failed shell invocation against a session that doesn't exist.
- `ExtensionCommand` explicitly special-cases `"cloud-driver-bootstrap"` in its `start`/`stop` handling to avoid an operator accidentally tearing down the very extension that hosts the whole running process.

## Scalability

This module has no meaningful data-volume scalability dimension of its own - it's an interactive, single-operator console, not a component processing bulk data. Its only genuine scaling concern is command-dispatch latency, already addressed by dispatching every command on a virtual thread rather than the reading thread (see above).

## API usage

This module exposes no library API - `CloudTerminalExtension` and its `Command`s are loaded as a
jar dropped into `Constraints.EXTENSIONS_PATH` (or picked up the same way by
`shell/test-bootstrap.sh`), not called directly from Java. Build it alongside a bootstrap jar
built from the same commit:

```
mvn -pl cloud-driver-extensions/cloud-driver-extensions-terminal -am package
```

Once registered, its commands are typed directly into the operator terminal the host process
opens - no code sample applies. To add a new command, implement `Command`
(`de.lino.cloud.api.terminal.service`) and register it the same way `CloudTerminalExtension`
registers the seven above:

```java
CommandService commandService = this.cloudDriver().getTerminal().getCommandService();
commandService.register(new MyCommand());
```

## Javadoc conventions

Google Java Style Guide throughout (summary fragment, blank line, `@param`/`@return`/`@throws`). As part of this documentation pass, `@param` tags across every `Command` implementation (`ClearCommand`, `ExitCommand`, `HelpCommand`, `LeaveCommand`, `DispatchCommand`) were corrected from a stale `@param args` to the actual parameter name, `arguments` (the `Command#execute` contract takes a `CommandArguments arguments`, not a raw `String[] args`); `DispatchCommand`'s `name()`/`aliases()`/`description()` and `AboutCommand#execute` gained the same one-line `@return`/`@param` documentation every sibling command already had, for consistency. `ExtensionCommand`'s class- and method-level Javadoc was expanded to actually describe its `list`/`info`/`start`/`stop` sub-dispatch, which the previous one-line summary omitted entirely.

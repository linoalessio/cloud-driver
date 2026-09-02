# cloud-driver-platforms

Parent aggregator for the **client-side** modules of `cloud-driver` — code that talks to a running `cloud-driver` server purely over its REST API, as opposed to the server-side modules (`cloud-driver-api`/`-auth`/`-plugin`/`-bootstrap`/`cloud-driver-extensions`) that implement that server.

## Project structure

Maven `packaging=pom` aggregator, `groupId=de.lino.cloud.platforms`, `artifactId=cloud-driver-platforms` (plural — an earlier revision of this module used the singular `cloud-driver-platform`; if you see that name anywhere outside this repo's git history, it's stale). Its own Maven `<parent>` is the repo root `pom.xml` (`de.lino.cloud:cloud-driver`), and it declares exactly **one** `<module>`:

- **[`cloud-driver-platforms-rest`](cloud-driver-platforms-rest/README.md)** — a dependency-free (of any other module in this repo) REST API client library: `ApiClient`, `SessionManager`, `Dtos`, `TokenStore` + its OS-specific implementations.

**`cloud-driver-platforms-desktop` lives in this same directory but is deliberately *not* a Maven module.** It's a Gradle build (Kotlin Multiplatform / Compose Desktop), with its own `build.gradle.kts`/`settings.gradle.kts`/committed `gradlew` wrapper — there is no `pom.xml` for it, and the parent `pom.xml` here does not (and cannot) list it in `<modules>`. It resolves its one in-repo dependency, `cloud-driver-platforms-rest`, via Gradle's `mavenLocal()` rather than a Gradle project dependency, since that sibling is Maven-built, not part of any Gradle build. See [`cloud-driver-platforms-desktop/README.md`](cloud-driver-platforms-desktop/README.md) for the actual desktop app — a real end-user app (register/login/browse/upload/download/share/admin), not a facade library, despite the module name pattern matching its Maven siblings.

*Historical note:* an earlier revision of this repo had a JavaFX desktop app living under this same `cloud-driver-platforms-desktop` name (`de.lino.cloud.platform.app`, before that `cloud-driver-platform-app`). That module was deleted outright (`MainApp`/`LoginController`/`RegisterController`/`FileListController`/`app.css` do not exist anymore — check with `git log --diff-filter=D` if you need the history). The name was later reused for the current, unrelated Kotlin Multiplatform module described above. Don't trust any doc or Javadoc describing "the desktop app" as JavaFX unless it's explicitly dated before that deletion.

Sibling to `cloud-driver-extensions` (both are children of the root aggregator), **not** a submodule of it. `cloud-driver-platforms` sits entirely outside the `cloud-driver-api ← cloud-driver-auth ← cloud-driver-plugin ← cloud-driver-bootstrap` server-side dependency chain — neither child depends on any of those four modules, or on each other in the reverse direction (`cloud-driver-platforms-desktop` depends on `cloud-driver-platforms-rest`, never the other way around). This is deliberate: a desktop/mobile/CLI client should only ever need an HTTP connection to a server, never the server's own database credentials or encryption internals on its classpath.

## Performance

N/A — this module declares no source of its own, only aggregates its Maven child (`cloud-driver-platforms-rest`) for the reactor. See that child's own README, and `cloud-driver-platforms-desktop/README.md`, for their actual performance characteristics.

## Data handling

N/A — no entities, no persistence at this level. See `cloud-driver-platforms-rest`'s README for the JSON DTOs it exchanges with the server over HTTP, and `cloud-driver-platforms-desktop`'s README for what little local state the desktop app itself keeps (theme preference, OS-keychain session token).

## Safety & security

N/A — a pure aggregator `pom` module has no runtime behavior and therefore no security surface of its own. See `cloud-driver-platforms-rest`'s README for how session/refresh tokens are transmitted/stored, and this repo's root `CLAUDE.md`'s "JWT authentication for end-user clients" section for the server-side contract both children ultimately talk to.

## Scalability

N/A — not applicable to a build-only aggregator.

## API surface

None — this module exposes no Java API of its own, only groups `cloud-driver-platforms-rest` in the Maven reactor (`cloud-driver-platforms-desktop`, being Gradle, isn't part of this reactor at all).

## Building this module

There's no public API to call here — build (or build+run) one of its children instead. This substitutes for a code sample, per this module having no library surface of its own:

```bash
# Build the Maven child (from the repo root) - required at least once before the desktop app can build,
# since it resolves this artifact via mavenLocal()
mvn -pl cloud-driver-platforms/cloud-driver-platforms-rest -am install

# Run the desktop app (Gradle, not Maven - note the different working directory)
cd cloud-driver-platforms/cloud-driver-platforms-desktop
./gradlew run

# ...or package a native installer for the current OS:
./gradlew packageDistributionForCurrentOS
```

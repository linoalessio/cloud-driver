# cloud-driver-platforms

Parent directory for the **client-side GUI apps** of `cloud-driver` — code that talks to a running `cloud-driver` server purely over its REST/WebSocket API, as opposed to the server-side modules (`cloud-driver-api`/`-auth`/`-plugin`/`-bootstrap`/`cloud-driver-extensions`) that implement that server. Each per-language networking/session client library those apps depend on now lives one directory over, in [`cloud-driver-multiplatform`](../cloud-driver-multiplatform/README.md) — see that module's own README for why the split exists.

## Project structure

Maven `packaging=pom` aggregator, `groupId=de.lino.cloud.platforms`, `artifactId=cloud-driver-platforms` (plural — an earlier revision of this module used the singular `cloud-driver-platform`; if you see that name anywhere outside this repo's git history, it's stale). Its own Maven `<parent>` is the repo root `pom.xml` (`de.lino.cloud:cloud-driver`), and it declares **no** `<module>` at all — its one-time Maven child, `cloud-driver-platforms-rest`, moved out to [`cloud-driver-multiplatform/cloud-driver-multiplatform-java`](../cloud-driver-multiplatform/cloud-driver-multiplatform-java/README.md) (renamed in the move, 2026-09-07). This pom is left in place purely as a directory anchor for the two GUI apps below; it contributes nothing to the reactor build itself anymore.

**`cloud-driver-platforms-desktop` lives in this directory but is deliberately *not* a Maven module.** It's a Gradle build (Kotlin Multiplatform / Compose Desktop), with its own `build.gradle.kts`/`settings.gradle.kts`/committed `gradlew` wrapper — there is no `pom.xml` for it, and the parent `pom.xml` here does not (and cannot) list it in `<modules>`. It resolves its one in-repo dependency, `cloud-driver-multiplatform-java`, via Gradle's `mavenLocal()` rather than a Gradle project dependency, since that sibling is Maven-built, not part of any Gradle build, and no longer lives in this directory at all. See [`cloud-driver-platforms-desktop/README.md`](cloud-driver-platforms-desktop/README.md) for the actual desktop app — a real end-user app (register/login/browse/upload/download/share/admin), not a facade library, despite the module name pattern matching its former Maven sibling.

*Historical note:* an earlier revision of this repo had a JavaFX desktop app living under this same `cloud-driver-platforms-desktop` name (`de.lino.cloud.platform.app`, before that `cloud-driver-platform-app`). That module was deleted outright (`MainApp`/`LoginController`/`RegisterController`/`FileListController`/`app.css` do not exist anymore — check with `git log --diff-filter=D` if you need the history). The name was later reused for the current, unrelated Kotlin Multiplatform module described above. Don't trust any doc or Javadoc describing "the desktop app" as JavaFX unless it's explicitly dated before that deletion.

**`cloud-driver-platforms-mobile` (added 2026-09-03) also lives in this directory and is also *not* a Maven module** — it's a native iOS (iPhone) app, plain Swift/SwiftUI, built with Xcode. It is **not** Kotlin Multiplatform and shares **no code** with `cloud-driver-platforms-desktop`/`cloud-driver-multiplatform-java` — a deliberate choice, not an oversight; see [`cloud-driver-platforms-mobile/README.md`](cloud-driver-platforms-mobile/README.md)'s own "Why native Swift instead of Kotlin Multiplatform" section for the full reasoning (short version: `cloud-driver-multiplatform-java`'s networking/keychain code is plain JVM Java, unreachable from Kotlin/Native/iOS without a full rewrite, and mobile UX needs different screens from desktop regardless). Its `CloudDriverMobile.xcodeproj` is generated from a committed `project.yml` via [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen && xcodegen generate`) rather than hand-maintained, the same "generated, not hand-edited" reasoning this repo already applies to `cloud-driver-bootstrap`'s shaded jar. It talks to the exact same REST API `cloud-driver-multiplatform-java`/`-desktop` do — **as of 2026-09-07, this module is GUI only**: its networking/session layer (`APIClient`, `Dtos`, `SessionManager`, `KeychainTokenStore`) was extracted out into the sibling Swift package [`cloud-driver-multiplatform/cloud-driver-multiplatform-swift`](../cloud-driver-multiplatform/cloud-driver-multiplatform-swift/README.md), a local SPM path dependency declared in `project.yml`, rather than living inside this module.

Sibling to `cloud-driver-extensions` and `cloud-driver-multiplatform` (all three are children of the root aggregator), **not** a submodule of either. `cloud-driver-platforms` sits entirely outside the `cloud-driver-api ← cloud-driver-auth ← cloud-driver-plugin ← cloud-driver-bootstrap` server-side dependency chain — no child (Gradle or Xcode alike) depends on any of those four modules, or on each other; each depends only on its own networking library over in `cloud-driver-multiplatform` (`cloud-driver-platforms-desktop` → `cloud-driver-multiplatform-java`; `cloud-driver-platforms-mobile` → `cloud-driver-multiplatform-swift`). This is deliberate: a desktop/mobile/CLI client should only ever need an HTTP connection to a server, never the server's own database credentials or encryption internals on its classpath.

## Performance

N/A — this module declares no source of its own and (as of 2026-09-07) aggregates no Maven child either. See [`cloud-driver-multiplatform/cloud-driver-multiplatform-java`](../cloud-driver-multiplatform/cloud-driver-multiplatform-java/README.md)'s own README, and `cloud-driver-platforms-desktop/README.md`, for their actual performance characteristics.

## Data handling

N/A — no entities, no persistence at this level. See `cloud-driver-multiplatform-java`'s README for the JSON DTOs it exchanges with the server over HTTP, and `cloud-driver-platforms-desktop`'s README for what little local state the desktop app itself keeps (theme preference, OS-keychain session token).

## Safety & security

N/A — a pure directory-anchor `pom` module has no runtime behavior and therefore no security surface of its own. See `cloud-driver-multiplatform-java`'s README for how session/refresh tokens are transmitted/stored, and this repo's root `CLAUDE.md`'s "JWT authentication for end-user clients" section for the server-side contract both children ultimately talk to.

## Scalability

N/A — not applicable to a build-only directory anchor.

## API surface

None — this module exposes no Java API of its own and (as of 2026-09-07) groups no Maven child in the reactor either.

## Building this module

There's no public API to call here — build (or build+run) one of its two GUI apps instead. This substitutes for a code sample, per this module having no library surface of its own:

```bash
# Build cloud-driver-multiplatform-java (from the repo root, now over in cloud-driver-multiplatform) - required
# at least once before the desktop app can build, since it resolves this artifact via mavenLocal()
mvn -pl cloud-driver-multiplatform/cloud-driver-multiplatform-java -am install

# Run the desktop app (Gradle, not Maven - note the different working directory)
cd cloud-driver-platforms/cloud-driver-platforms-desktop
./gradlew run

# ...or package a native installer for the current OS:
./gradlew packageDistributionForCurrentOS
```

```bash
# The iOS app (Xcode, not Maven/Gradle). xcodegen/Xcode resolve its cloud-driver-multiplatform-swift
# dependency automatically as a local SPM package (no separate install step, unlike
# cloud-driver-multiplatform-java above).
brew install xcodegen   # if not already installed
cd cloud-driver-platforms/cloud-driver-platforms-mobile
xcodegen generate       # regenerate after adding/removing/renaming a source file
open CloudDriverMobile.xcodeproj   # then build/run from Xcode - requires full Xcode, not just the CLTs
```

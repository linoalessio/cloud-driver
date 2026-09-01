# cloud-driver-platforms-desktop

A Kotlin Multiplatform / Compose Multiplatform Desktop client for `cloud-driver`'s JWT-authenticated REST API - register (two-step, e-mail-verified), sign in, reset a forgotten password (also two-step, e-mail-verified), browse a signed-in user's folders/files, upload/download, multi-select delete/download, and upload a whole local folder as a zip. Runs on macOS, Linux, and Windows from one codebase.

## Build tooling: Gradle, not Maven

Every other module in this repo is Maven. This one is **Gradle** (`build.gradle.kts`/`settings.gradle.kts`), deliberately, and is **not** listed in `cloud-driver-platforms/pom.xml`'s `<modules>` - it does not participate in the root `mvn clean install` reactor at all. Real Compose Multiplatform Desktop tooling (native `.dmg`/`.msi`/`.deb` installers via `nativeDistributions`, resource bundling) is Gradle-only in practice; there is no equivalent Maven plugin. A Gradle wrapper (`gradlew`/`gradlew.bat`, pinned to Gradle 9.7.1) is committed, so no local Gradle install is required to build.

It still depends on its Maven-built sibling, `cloud-driver-platforms-rest` - resolved from the local Maven repository (`mavenLocal()`, declared in `settings.gradle.kts`) rather than as a Gradle project dependency, since that module is not itself part of a Gradle build. **Run `mvn -pl cloud-driver-platforms/cloud-driver-platforms-rest -am install` (or a full `mvn clean install` from the repo root) at least once before building this module**, so `cloud-driver-platforms-rest-1.0.1.jar` actually exists in `~/.m2`.

```
cd cloud-driver-platforms/cloud-driver-platforms-desktop
./gradlew run                              # launch the app directly
./gradlew packageDistributionForCurrentOS  # build a native installer (.dmg/.msi/.deb) for the current OS
```

## Project structure

A Kotlin Multiplatform project (`kotlin("multiplatform")` + `org.jetbrains.compose` + `org.jetbrains.kotlin.plugin.compose`) with a single `jvm("desktop")` target - there is no Android/iOS/JS target, "multiplatform" here means "one Kotlin/Compose codebase, three desktop OSes," matching the task this module was built for. Source lives under `src/desktopMain/kotlin` (JVM-specific - `cloud-driver-platforms-rest`'s `ApiClient` is a plain Java class, so anything touching it can't live in `commonMain`) and `src/commonMain` (currently unused - reserved for the day a second target is added, per the standard Compose Multiplatform project layout).

```
de.lino.cloud.platform.desktop
├── Main.kt                 application entry point (`fun main() = application { Window { ... } }`)
├── App.kt                  root composable - dispatches on AppViewModel.screen
├── Screen.kt                sealed interface of every screen, before and after login
├── AppViewModel.kt          all mutable state + actions (session, navigation, file browser)
├── Entry.kt                 unifies a FolderResponse/StoredFileSummaryResponse into one list-row shape
├── AuthScreens.kt           Login / Register (2-step) / Reset password (2-step) composables
├── FileBrowserScreen.kt     the after-login screen: toolbar, breadcrumbs, folder/file table
├── CloudDriverClient.kt     coroutine facade over cloud-driver-platforms-rest's ApiClient
├── ByteFormat.kt            client-side port of the server's Constraints#resolveBytesToUnit
├── FileDownloader.kt        StoredFileResponse -> decoded (base64 + optional DEFLATE-inflate) bytes -> local file
└── FolderZipper.kt          zips a local directory (recursively) into a temp file for upload
```

## Why this module never depends on `cloud-driver-api`

Matches this repo's "client must never see the database" boundary (see CLAUDE.md's `cloud-driver-platforms-rest` section) - this module only ever talks to the server over HTTP via `ApiClient`/`Dtos`, never the server-side `StoredFile`/`Folder`/`Constraints` classes those DTOs mirror. Two consequences worth knowing if you're extending this module:

- **`ByteFormat.kt`** is a from-scratch reimplementation of `cloud-driver-api`'s `Constraints#resolveBytesToUnit` - same algorithm, same output format, kept in sync by hand rather than by a shared dependency.
- **`FileDownloader.kt`** is a from-scratch reimplementation of exactly what `StoredFile`'s constructor undoes on the server side (base64-decode, then DEFLATE-inflate only if `contentCompressed` is `true`) - not a call into `StoredFile#downloadToDevice`, which this module has no access to.

## Password reset - added to the server for this app

No password-reset flow existed anywhere in this repo before this module needed one. It now exists end-to-end, mirroring the existing two-step, e-mail-verified registration flow exactly (see CLAUDE.md's "JWT authentication for end-user clients" section for the full picture):

- `cloud-driver-auth`: `IAuthService#requestPasswordReset`/`#confirmPasswordReset`, backed by a new `PendingPasswordReset` entity (`cloud-driver-auth`, `de.lino.cloud.auth.pending`) - same shape as `PendingRegistration`, minus a password field, since the caller's new password is only ever supplied at the confirm step, never persisted mid-flow.
- `cloud-driver-plugin`: `DefaultRestFactory` mounts `POST /auth/reset-password` (e-mails a code **only if** an account exists under the given address, but responds identically either way - unlike registration's deliberately leaky `EmailAlreadyRegisteredException`, leaking account existence here would hand an attacker a credential-stuffing oracle) and `POST /auth/reset-password/confirm` (code + a caller-chosen new password -> the account's password is replaced and a fresh JWT returned, the same way confirming a registration does).
- `cloud-driver-platforms-rest`: `ApiClient#requestPasswordReset`/`#confirmPasswordReset` (+ `*Async` forms), `Dtos.RequestPasswordResetRequest`/`ConfirmPasswordResetRequest`.
- This module: `CloudDriverClient#requestPasswordReset`/`#confirmPasswordReset` (`suspend` wrappers), and `ResetPasswordRequestScreen`/`ResetPasswordConfirmScreen` in `AuthScreens.kt`.

## Folder upload/download/delete semantics

- **Uploading a local folder zips it first.** The server has no folder-tree upload endpoint, only single-file `POST /files` - `FolderZipper.kt` zips the chosen local directory (recursively, preserving relative paths) into a temp file, which is then uploaded as one regular file named `<folder name>.zip`. The temp zip is deleted once the upload completes.
- **Deleting a selected folder cascades client-side.** The server's `deleteFolder` 409s on a non-empty folder by design (see CLAUDE.md: "a folder is never deleted recursively" server-side). `AppViewModel#deleteFolderRecursively` empties a folder bottom-up (every contained file, then every nested folder, recursively) before deleting it, via ordinary per-item API calls - the same approach `CloudUserService#resetCloudUser` already uses server-side for a full account wipe, just driven from the client instead.
- **Downloading a selected folder recreates its structure locally.** `AppViewModel#downloadFolderRecursively` walks the folder's files/subfolders the same way and writes each file under a matching local directory tree rooted at the chosen destination.

## What this module does not do

No settings/preferences persistence (the server URL field on the login screen resets to `https://api.cloud-driver.de` on restart), no session-token keychain persistence (unlike `cloud-driver-platforms-rest`'s own `TokenStore`/`SessionManager`, which this module doesn't currently wire up - every launch starts logged out), no drag-and-drop upload, no file preview/open action. All reachable as future work on top of `AppViewModel`/`CloudDriverClient` without restructuring either.

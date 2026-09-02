# cloud-driver-platforms-desktop

A Kotlin Multiplatform / Compose Multiplatform Desktop client app for `cloud-driver`'s JWT-authenticated REST API - register (two-step, e-mail-verified), sign in (with optional two-factor authentication), persist the session across restarts, browse/upload/download/organize files and folders, view an account dashboard, browse the trash, share files/folders with other accounts and browse/download what's shared with you, and (for an admin account) view a read-only accounts/audit-trail panel. Runs on macOS, Linux, and Windows from one codebase, styled after iCloud Drive/Finder.

## Build tooling: Gradle, not Maven

Every other module in this repo is Maven. This one is **Gradle** (`build.gradle.kts`/`settings.gradle.kts`), deliberately, and is **not** listed in `cloud-driver-platforms/pom.xml`'s `<modules>` - it does not participate in the root `mvn clean install` reactor at all. Real Compose Multiplatform Desktop tooling (native `.dmg`/`.msi`/`.deb` installers via `nativeDistributions`, resource bundling) is Gradle-only in practice; there is no equivalent Maven plugin. A Gradle wrapper (`gradlew`/`gradlew.bat`, pinned to Gradle 9.7.1) is committed, so no local Gradle install is required to build.

It still depends on its Maven-built sibling, `cloud-driver-platforms-rest` - resolved from the local Maven repository (`mavenLocal()`, declared in `settings.gradle.kts`) rather than as a Gradle project dependency, since that module is not itself part of a Gradle build. **Run `mvn -pl cloud-driver-platforms/cloud-driver-platforms-rest -am install` (or a full `mvn clean install` from the repo root) at least once before building this module**, so `cloud-driver-platforms-rest-1.0.1.jar` actually exists in `~/.m2`.

This is an application module, not a library - there is no public API for another module to call, so the section below that would normally hold an API code sample instead shows how to build and run it.

```
cd cloud-driver-platforms/cloud-driver-platforms-desktop
./gradlew run                              # launch the app directly (full JDK, fastest inner loop)
./gradlew packageDistributionForCurrentOS  # build a native installer (.dmg/.msi/.deb) for the current OS
./build-app.sh                             # build + install into the OS's normal app location, with a desktop shortcut
```

## 1. Project structure

A Kotlin Multiplatform project (`kotlin("multiplatform")` version 2.1.0 + `org.jetbrains.compose` 1.7.1 + `org.jetbrains.kotlin.plugin.compose`) with a single `jvm("desktop")` target, JVM toolchain 21 - there is no Android/iOS/JS target; "multiplatform" here means "one Kotlin/Compose codebase, three desktop OSes," matching the task this module was built for. Source lives under `src/desktopMain/kotlin` (JVM-specific - `cloud-driver-platforms-rest`'s `ApiClient` is a plain Java class, so anything touching it can't live in `commonMain`); `src/commonMain` holds only a shared resource (`composeResources/drawable/app_icon.png`).

Sits outside the `api ← auth ← plugin ← bootstrap` server-side dependency chain entirely (see the root `CLAUDE.md`'s "Module layout and dependency direction") - it depends on exactly one in-repo module, its sibling `cloud-driver-platforms-rest`, and nothing else in this repo. It never depends on `cloud-driver-api`/`cloud-driver-auth`/`cloud-driver-plugin` - matching the "client must never see the database" boundary every module in the `cloud-driver-platforms/` tree observes - so it never sees the server's own `StoredFile`/`Folder`/`Constraints`/`AuthUser` classes, only the plain HTTP DTOs `cloud-driver-platforms-rest`'s `Dtos`/`ApiClient` expose. Three small server-side algorithms are consequently reimplemented here by hand rather than shared: `ByteFormat.kt` (byte-count formatting), `PreviewSupport.kt` (content-type-to-preview-kind classification), and `PasswordValidation.kt` (the password format rule) - each documents in its own Javadoc which server-side class it mirrors.

```
de.lino.cloud.platform.desktop
├── Main.kt                          application entry point - window setup, session restore, theme load
├── App.kt                           root composable - dispatches on AppViewModel.screen
├── auth/AuthScreens.kt              Login / Register (2-step) / Reset password (2-step) / Two-factor-code composables
├── client/CloudDriverClient.kt      coroutine facade over cloud-driver-platforms-rest's ApiClient/SessionManager
├── model/
│   ├── Screen.kt                    sealed interface of every screen, before and after login
│   ├── Entry.kt                     unifies a FolderResponse/StoredFileSummaryResponse into one list-row shape
│   └── AccountStats.kt              recursive whole-account stats aggregation (files/folders/storage/trash/shared)
├── panel/                           after-login screens
│   ├── Sidebar.kt                   AuthenticatedShell/Sidebar - shared post-login layout every panel screen wraps in
│   ├── FileBrowserScreen.kt         Home: toolbar, breadcrumbs, folder/file table, Share/Move/Duplicate dialogs
│   ├── FilePreviewDialog.kt         double-click text/PDF/DOCX in-app preview
│   ├── DashboardScreen.kt           account overview, stat cards, settings dialogs, Danger Zone (Uninstall)
│   ├── TrashScreen.kt                trashed files/folders, per-row purge timestamp, restore, Empty trash bin
│   ├── SharedWithMeScreen.kt        files/folders other accounts have shared with this one
│   ├── SharedFolderBrowserScreen.kt browsing/downloading inside a folder reached via a share
│   └── AdminScreen.kt               read-only accounts + audit-trail panel, admin accounts only
├── theme/Theme.kt                   light/dark ColorScheme pair, iCloud/Finder-styled
├── viewmodel/AppViewModel.kt        all mutable state + actions for every screen above
└── utils/                           small, mostly-pure helpers
    ├── AppSettingsStore.kt          persists ThemeMode to a local Properties file
    ├── ArchiveExtractor.kt          unzips a downloaded .zip into the current folder
    ├── ByteFormat.kt                client-side port of the server's Constraints#resolveBytesToUnit
    ├── Concurrency.kt               mapConcurrently - capped-parallelism batch helper
    ├── EntryIcons.kt                per-content-type file/folder icon mapping
    ├── FileDownloader.kt            streams a file straight to disk via CloudDriverClient
    ├── FolderZipper.kt              zips a local directory (recursively) into a temp file for upload
    ├── JwtDecoder.kt                reads the account id out of a JWT's `sub` claim, client-side, no server call
    ├── PasswordValidation.kt        client-side port of the server's password format rule
    ├── PreviewSupport.kt            content-type-to-PreviewKind classifier backing FilePreviewDialog
    ├── Thumbnails.kt                real image-file row thumbnails, session-lifetime cache
    └── Uninstaller.kt               the Dashboard's "Uninstall" action - reverses build-app.sh's install step
```

## 2. Performance

- **Everything is coroutine-based, driven from one `AppViewModel`.** Every user action (`login`, `uploadFiles`, `deleteEntries`, ...) goes through a private `run { }` helper that launches on the view model's own `CoroutineScope`, toggles a `busy` flag (disabling every action button while `true`), and is a no-op if another action is already in flight - a synchronous check on the UI thread, race-free without a lock.
- **Batch operations are capped-concurrency, not serial or unbounded.** `utils/Concurrency.kt#mapConcurrently` runs a batch (upload/delete/download/duplicate/drop-to-upload) with at most `ApiClient.DEFAULT_MAX_CONCURRENT_TRANSFERS` (8) requests in flight at once, over the one HTTP/2 connection `ApiClient` multiplexes everything through. Recursive folder operations (`deleteEntries`, `duplicateEntries`, `AccountStats#computeAccountStats`, archive-extraction upload planning) deliberately **plan the whole tree first** (a sequential, listings-only walk building one flat item list) **before** running a single capped `mapConcurrently` batch over it - the fix for a real, confirmed bug where nesting a fresh `mapConcurrently` call at every recursion level multiplied the true number of simultaneously in-flight requests past the connection's concurrent-stream limit, surfacing as `"too many concurrent streams"` on a large enough tree.
- **Uploads/downloads stream, never fully buffer in this process.** `ApiClient#uploadFile(Path, ...)` sends via `BodyPublishers.ofFile`; `FileDownloader.kt`/`CloudDriverClient#downloadFileToPath` write via `BodyHandlers.ofFile` straight to disk - file content never exists as an in-memory `ByteArray` end-to-end in this module, matching the server's own streamed `GET /files/{id}/content` route. Real byte-level progress (not an indeterminate spinner) is aggregated across an entire batch via a shared `runTransfer` helper, since several transfers run concurrently at once.
- **The current folder view is paginated, not loaded whole.** `FileBrowserScreen`'s own listing loads `FOLDER_VIEW_PAGE_SIZE` (200) entries at a time via the server's cursor-paginated `GET /files`/`GET /folders` (`?limit=`/`?cursor=`), with an explicit "Load more" button rather than auto-load-on-scroll - every *other* caller that needs a folder's complete contents (delete/duplicate/download planning, `computeAccountStats`) still uses the unpaginated listing calls, since those genuinely need everything.
- **Row thumbnails are cached process-wide, session-lifetime.** `Thumbnails.kt`'s `ThumbnailCache` downloads and decodes an image file's thumbnail once (only for files under `MAX_THUMBNAIL_SOURCE_BYTES`, 20 MB) and reuses it on every later scroll/revisit - `LazyColumn` only composing visible rows is what keeps concurrent thumbnail fetches naturally bounded to what's on screen.
- **`FileBrowserScreen`'s listing is a `derivedStateOf`, not a plain recomputed `val`** - the composable also reads `busy`/`errorMessage`/breadcrumb state on every recomposition, so a plain `val` would reallocate the folder/file list on every one of those unrelated changes too; `derivedStateOf` only re-runs when the underlying lists actually change.

## 3. Data handling

This module owns no persistent server-side data of its own - it is a pure client over `cloud-driver-platforms-rest`'s HTTP DTOs (`StoredFileSummaryResponse`, `FolderResponse`, `SharedFileSummaryResponse`/`SharedFolderSummaryResponse`, `TrashedFileSummaryResponse`/`TrashedFolderSummaryResponse`, `SharedFolderContentsResponse`, `AuthUserResponse`, `AuditLogEntryResponse`, `MeResponse`, `CloudUserResponse`, ...) rendered directly into Compose UI state on `AppViewModel`, never cached to local disk beyond what's described below.

Two things this module *does* persist locally, both outside any server-side data model:

- **Theme preference** (`AppSettingsStore.kt`) - a plain `java.util.Properties` file at `~/.cloud-driver-desktop/settings.properties`, holding only `ThemeMode`.
- **Session token** - delegated entirely to `cloud-driver-platforms-rest`'s `SessionManager`/`TokenStore` (see that module's own README) - an OS keychain entry where available, a permission-restricted plain file otherwise.

## 4. Safety & security

- **Session persistence uses the OS keychain, with an explicit fallback notice.** `CloudDriverClient` wraps `cloud-driver-platforms-rest`'s `SessionManager`, which picks a real OS keychain/secret-service-backed `TokenStore` where available (macOS Keychain, Windows DPAPI, Linux Secret Service) and only falls back to a plain, permission-restricted file if none is available. `AppViewModel.showKeychainFallbackNotice` surfaces that fallback as a dismissible banner across every authenticated screen (`Sidebar.kt`'s `AuthenticatedShell`) - the app never silently persists a session less securely than a real keychain would with no signal to the user.
- **The JWT's subject claim is decoded client-side without signature verification, deliberately.** `JwtDecoder.kt#decodeJwtSubject` is a plain base64 decode used only to display the account id on the Dashboard - it is never used to authorize anything, and every real request still sends the raw token to the server, which is the only party that actually verifies it. Not a weakness: there is nothing this client could gain by forging what it displays to itself.
- **Password format is validated client-side before submit, but the server remains the real enforcement point.** `PasswordValidation.kt#isValidPasswordFormat` mirrors the server's rule exactly (hand-kept-in-sync, not shared code - see this module's own "client must never depend on cloud-driver-api" boundary above) purely to avoid a round trip for an obviously-invalid password on Register/Reset-password screens; an existing password is never re-validated on login, matching the server's own behavior.
- **All traffic is HTTPS**, to the single hardcoded `DEFAULT_SERVER_URL` (`Main.kt`) - there is deliberately no in-app "Server" field (one existed on an earlier revision of the login screen; removed so a first-time user isn't asked to configure anything before signing in).
- **Two-factor authentication and admin-gated data are both fully delegated to server checks.** `TwoFactorLoginScreen`/`DashboardScreen`'s 2FA setup dialog only ever drive the server's own TOTP flow; `AdminScreen` is shown only while `AppViewModel.currentUserIsAdmin` (learned via `GET /auth/me`) as a UI convenience - the server's own `requireAdmin` filter is the real enforcement point, so this client has no privileged capability of its own to protect.

## 5. Scalability

N/A in the usual "requests per second" sense - this is a single-user desktop client, one process per signed-in session, with no shared or server-side state of its own to scale. Its own scaling concerns are instead about **large accounts**, handled as described in "Performance" above: capped-concurrency batches (never unbounded parallel requests), server-side cursor pagination for the visible folder listing, and a plan-then-batch shape for recursive operations so a wide/deep folder tree or a large shared folder never exceeds the underlying HTTP/2 connection's concurrent-stream limit.

## 6. API surface (no public library API - key screens/view-model responsibilities instead)

This module exposes no API for another module to call (see "Build tooling" above) - the closest equivalent is `AppViewModel`, the one class every screen reads/drives:

- **`AppViewModel`** - all mutable UI state (current screen, folder listing, selection, transfer progress, every "shared"/"trash"/"admin" list) and every user-triggered action, each routed through the `run { }` busy-guard described above.
- **`CloudDriverClient`** - the coroutine-friendly facade `AppViewModel` calls into; wraps `cloud-driver-platforms-rest`'s blocking/`CompletableFuture`-based `ApiClient` and `SessionManager`.
- **Screens** (`panel/`, `auth/AuthScreens.kt`) - `LoginScreen`/`RegisterScreen`/`RegisterConfirmScreen`/`ResetPasswordRequestScreen`/`ResetPasswordConfirmScreen`/`TwoFactorLoginScreen` (before login); `FileBrowserScreen` (Home - upload/download/move/duplicate/delete/share, drag-and-drop both within the app and from the OS); `DashboardScreen` (account info, stat cards, settings menu - change email/reset password/two-factor, Danger Zone Uninstall); `TrashScreen` (restore, per-item purge-eligibility timestamp, Empty trash bin); `SharedWithMeScreen`/`SharedFolderBrowserScreen` (what others have shared with you, including browsing into and downloading a shared folder); `AdminScreen` (admin-only, read-only accounts + audit trail).

## 7. Notable design decisions worth knowing before extending this module

- **Folder upload/download semantics.** The server has no folder-tree upload endpoint, only single-file `POST /files` - `FolderZipper.kt` zips a chosen local directory (recursively) into a temp file, uploaded as one file named `<folder name>.zip`; double-clicking a downloaded/dropped `.zip` extracts it back via `ArchiveExtractor.kt`. Deleting a selected folder cascades client-side (the server's own `deleteFolder` 409s on a non-empty folder by design) - `AppViewModel` empties it bottom-up via a flattened plan before deleting the folder itself. Downloading a selected folder recreates its structure locally the same planned way.
- **Drag-and-drop works both ways** - within the app (reordering into a folder) and from the OS (Finder/Explorer) directly into the current folder, uploading a dropped file as-is and zipping a dropped directory first.
- **Sharing is read-only for the recipient, browsing included.** `SharedFolderBrowserScreen` lets a grantee browse into and download a shared folder's contents (including nested subfolders, since a folder share covers everything nested inside it), but never upload, rename, move, delete, or re-share anything reached via a share - every mutating action in this app stays owner-only.

## Password reset, two-factor authentication, and sharing - added to the server for this app

Several server-side features (`cloud-driver-auth`/`cloud-driver-plugin`) exist specifically because this module needed them; see the root `CLAUDE.md`'s "JWT authentication for end-user clients" and "Folder organization" sections for the full picture of each - not duplicated here to avoid the two drifting out of sync.

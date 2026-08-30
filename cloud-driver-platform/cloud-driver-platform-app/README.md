# cloud-driver-platform-app

A standalone JavaFX desktop client for `cloud-driver`'s REST API: register, log in, list files, upload/delete files. The GUI counterpart to `cloud-driver-platform-rest`'s programmatic client — this module is UI only, no networking logic of its own.

## Project structure

Maven `packaging=jar`, `groupId=de.lino.cloud.platform.app`, `artifactId=cloud-driver-platform-app`, package root `de.lino.cloud.platform.app`. Its Maven `<parent>` is the `cloud-driver-platform` aggregator. Renamed at some point from an original `de.lino.clouddriver.desktop` (both package and directory layout moved).

Depends on exactly one in-repo module — its own sibling **`cloud-driver-platform-rest`** (`ApiClient`/`SessionManager`/`Dtos`/`TokenStore*`) — and, directly, `javafx-controls` (which pulls in `javafx-graphics`/`javafx-base` transitively). It does **not** depend on `cloud-driver-api`/`cloud-driver-auth`/`cloud-driver-plugin`/`cloud-driver-bootstrap`, on purpose — same reasoning as its sibling.

Source files, all under `de.lino.cloud.platform.app`:

```
MainApp.java               entry point: wiring, screen switching
LoginController.java       drives the login screen
RegisterController.java    drives the two-step (email/password → code) register screen
FileListController.java    drives the "your files" screen (list/upload/delete)
resources/.../app.css      the one stylesheet every screen applies
```

**JavaFX has no OS-independent Maven artifact** — `javafx-controls` only publishes platform-specific jars, keyed by a `<classifier>` (`mac`/`win`/`linux`). This module has no `os-maven-plugin` to detect that automatically, so its `pom.xml` defines three `<profiles>` (`javafx-platform-mac`/`-win`/`-linux`), each OS-family-activated, setting a `javafx.platform` property the `javafx-controls` dependency's classifier reads. This resolves the *build machine's* OS/arch only — building on Linux does not produce a Mac-runnable jar.

## Performance

Pure UI glue over `cloud-driver-platform-rest`'s already-async client — this module adds no independent concurrency model of its own beyond keeping the JavaFX Application Thread responsive:

- `MainApp#tryRestoreSessionThenShowScreen` runs `SessionManager#tryRestoreSession()` (a real HTTP call) on a dedicated virtual thread (`Thread.ofVirtual().name("session-restore")`), then hops back onto the FX thread via `Platform.runLater` to switch screens.
- `FileListController`'s list/upload/delete actions run through `sessionManager.api()` calls dispatched off the FX thread the same way, so a slow network call never freezes the UI.
- No caching, no batching beyond what `ApiClient`'s own `uploadFilesAsync`/`deleteFilesAsync` already provide when a controller chooses to use them.

## Data handling

Displays and manipulates `Dtos.StoredFileResponse` records (from `cloud-driver-platform-rest`) in a JavaFX `ListView` — no local persistence of file content; every file lives on the server, fetched/uploaded/deleted on demand over REST. The only thing persisted locally is the session token, delegated entirely to `cloud-driver-platform-rest`'s `TokenStore`.

## Safety & security

- Delegates all actual authentication/session-token security to `cloud-driver-platform-rest` (see that module's README) — this module never touches a raw JWT or the keychain APIs directly.
- `MainApp#warnAboutFallbackStorage` surfaces a JavaFX `Alert` to the user when `TokenStoreFactory.Result#usedFallback()` is `true` (no OS keychain/secret-service found), so a user on a headless/minimal Linux install knows their session token is stored less securely (a permission-restricted plain file) rather than this degrading silently.
- `RegisterController` checks password/confirm-password match **client-side** before calling the server — a typo-catching convenience only, not a security boundary; the server independently validates the e-mail address itself (syntax + MX-record check).
- The two configured base URLs (`AUTH_PANEL_BASE_URL`/`API_BASE_URL` in `MainApp`) are hardcoded to `https://` endpoints — TLS termination happens at the Caddy reverse proxy in front of the real server (see CLAUDE.md's Caddyfile bullet); a real production build should read these from config/environment instead of hardcoding, per `MainApp`'s own Javadoc.

## Scalability

A single-user desktop client — one `ApiClient`/`SessionManager` per running instance, one JavaFX `Stage`. Not a server component; "scaling" this module means nothing beyond running more independent instances on more machines, each with its own locally-stored session.

## API surface

This module exposes no reusable library API (it's an application, not a library) — its classes are internal to running the desktop app:

- **`MainApp`** — `javafx.application.Application` entry point; owns the `Stage`, wires `ApiClient`+`TokenStore` into a `SessionManager`, and switches between the login/register/file-list screens.
- **`LoginController`** — drives the login screen's email/password fields and submit button.
- **`RegisterController`** — drives the two-step register screen: details (email/password/confirm) → verification code.
- **`FileListController`** — drives the "your files" screen: list, upload (via a JavaFX `FileChooser`), delete, and reacts to a `401` by triggering `onSessionExpired`.
- **`app.css`** — the shared stylesheet (`.auth-card`, `.app-toolbar`, `.button-primary`/`-secondary`/`-danger`/`-link`, `.status-label`/`-error`, ...) every screen applies via `MainApp#applyStylesheet`.

## Running this module

No public API to call from other code — run it as a desktop app instead. Substituting a "how to run" snippet for a code sample, per this module having no library surface of its own (see CLAUDE.md's "cloud-driver-platform-app" section for the full explanation of why the short `javafx:run` prefix form doesn't resolve on a stock Maven install, and why combining the fully-qualified goal with `-am` also fails):

```bash
# From inside cloud-driver-platform/cloud-driver-platform-app itself:
mvn org.openjfx:javafx-maven-plugin:0.0.8:run

# Or from the repo root, without -am (this module has no in-repo build-order
# dependency on cloud-driver-platform-rest beyond what mvn install already resolved):
mvn -pl cloud-driver-platform/cloud-driver-platform-app org.openjfx:javafx-maven-plugin:0.0.8:run
```

There is no shade-plugin-produced runnable jar (unlike `cloud-driver-bootstrap`) or `jlink`/`jpackage` standalone distributable today — `javafx:run` and an IDE run configuration pointed at `MainApp` (with the same module-path/`--add-modules` VM options set manually if the IDE doesn't infer them) are the only ways to launch it.

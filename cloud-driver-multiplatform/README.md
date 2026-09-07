# cloud-driver-multiplatform

Parent directory for `cloud-driver`'s **per-ecosystem client SDKs** — put another way, `cloud-driver-api`'s contract reimplemented as a plain HTTP/WebSocket client in every language this repo ships a client for. Each sibling below talks to the exact same JWT-authenticated REST/WebSocket API (see the root `CLAUDE.md`'s "JWT authentication for end-user clients"/"RestFactory" sections and [`docs/api-reference.md`](../docs/api-reference.md) for the full route table) independently — none of them share code with each other, and none of them depend on any server-side module (`cloud-driver-api`/`-auth`/`-plugin`/`-bootstrap`) at all, the same "a client should only ever need an HTTP connection, never the server's own database credentials or encryption internals" boundary every client-side module in this repo observes.

## Current submodules

| Submodule | Language/ecosystem | Build tool | Consumed by | See |
|---|---|---|---|---|
| [`cloud-driver-multiplatform-java`](cloud-driver-multiplatform-java/README.md) | Java | Maven | `cloud-driver-platforms-desktop` | Java `ApiClient`/`SessionManager`/`Dtos` |
| [`cloud-driver-multiplatform-swift`](cloud-driver-multiplatform-swift/README.md) | Swift | Swift Package Manager | `cloud-driver-platforms-mobile` | Swift `APIClient`/`SessionManager`/`Dtos` |
| [`cloud-driver-multiplatform-python`](cloud-driver-multiplatform-python/README.md) | Python | `pip`/hatchling | External Python microservices (nothing in this repo) | `CloudDriverClient`/`AsyncCloudDriverClient` |

Only `cloud-driver-multiplatform-java` is a Maven module — this directory's own `pom.xml` (`packaging=pom`, `groupId=de.lino.cloud.multiplatform`, `artifactId=cloud-driver-multiplatform`) declares it as its one `<module>`. `cloud-driver-multiplatform-python`/`cloud-driver-multiplatform-swift` are deliberately **not** listed there (each has its own build tooling, `pyproject.toml`/`Package.swift`, no `pom.xml`) — see this directory's `pom.xml` for the comments explaining exactly why each is excluded.

## Why one directory for three unrelated ecosystems

Before 2026-09-07, these three lived in three different places: the Java client was `cloud-driver-platforms-rest`, a Maven child of the `cloud-driver-platforms` aggregator (sibling to the desktop/mobile GUI apps); the Python client was `cloud-driver-python`, sitting directly at the repo root; and the Swift client didn't exist as its own module at all — it was `cloud-driver-platforms-mobile`'s own `Networking/` folder, compiled straight into the GUI app's target. Grouping all three here does two things: it makes "every client SDK for this API, one per language" a single, discoverable location instead of three unrelated spots in the tree, and — for the Swift case specifically — it lets `cloud-driver-platforms-mobile` shed its networking/session code entirely and become GUI-only, the same split `cloud-driver-platforms-desktop` already had via its own (now-moved) Java dependency.

Each move (and the same-day rename that gave two of the three their current `cloud-driver-multiplatform-*` names) is documented in full in the root `CLAUDE.md`'s own "`cloud-driver-multiplatform`" section, and in each submodule's own README under a "Moved"/"Moved, then renamed" note.

## What changed, what didn't

- **`cloud-driver-multiplatform-java`** — Maven module + Java package rename story: the Maven `artifactId`/`groupId`/`<parent>` changed (twice — first to `cloud-driver-maven`, then to its current name, `cloud-driver-multiplatform-java`), but the Java package root (`de.lino.cloud.platform.rest`) is **unchanged** on purpose — renaming it would touch every import across `cloud-driver-platforms-desktop` and this module's own source for no functional benefit. `cloud-driver-platforms-desktop`'s Gradle dependency coordinate was updated to match (`de.lino.cloud.multiplatform.java:cloud-driver-multiplatform-java:<version>`, resolved via `mavenLocal()`).
- **`cloud-driver-multiplatform-swift`** — a brand-new Swift Package Manager library, not a rename of an existing module: `cloud-driver-platforms-mobile`'s `Networking/` folder (`APIClient.swift`, `Dtos.swift`, `SessionManager.swift`, `KeychainTokenStore.swift`) moved here verbatim, with every type/member an app actually calls promoted from the default `internal` access to `public` (needed the moment this code left the app's own target and became a separate module). `cloud-driver-platforms-mobile`'s `project.yml` gained a local SPM path dependency (`packages: CloudDriverSwift: { path: ../../cloud-driver-multiplatform/cloud-driver-multiplatform-swift }`) and every file that references one of these types gained `import CloudDriverSwift`.
- **`cloud-driver-multiplatform-python`** — a pure directory move, twice (first to `cloud-driver-multiplatform/cloud-driver-python`, then renamed the same day to its current path) — the importable package name (`cloud_driver_client`) and PyPI distribution name (`cloud-driver-client`) are **unchanged**; only the module's own directory name/location moved. This one's Python package identity was already decoupled from its containing folder's name before this move (the folder was `cloud-driver-python`, the PyPI name was already `cloud-driver-client`), so keeping the package identity fixed while renaming the folder isn't a new kind of decision here, just the same pattern applied again.

Every reference across the repo — root `pom.xml`, `cloud-driver-platforms/pom.xml`, `cloud-driver-platforms-desktop`'s `build.gradle.kts`/`settings.gradle.kts`/`build-app.sh`, `cloud-driver-platforms-mobile`'s `project.yml`, `.github/workflows/{maven,swift,python}.yml`, `shell/release-and-package.sh`, and every `docs/*.md`/`README.md` that named the old locations — was updated in the same pass as these moves; see git history for the exact diffs if you need them.

## Performance / Data handling / Safety & security / Scalability

N/A at this directory's own level — a pure directory anchor (`cloud-driver-multiplatform-java`'s own `pom.xml` is `packaging=pom` with no source), the same "nothing to say here, see each child" shape `cloud-driver-platforms/README.md` documents for its own two GUI-app children. See each submodule's own README (linked in the table above) for what actually applies to its own client library.

## API surface

None at this directory's own level. See each submodule's own README's "API surface"/"API usage + code sample" sections for the real, callable API in that language — all three ultimately expose the same shape (login/register/reset/change-email, file/folder CRUD, sharing, trash, and - Java/Swift only - presigned direct-to-storage transfer and live push over WebSocket) against the one REST/WebSocket contract documented in [`docs/api-reference.md`](../docs/api-reference.md)/[`docs/api-usage.md`](../docs/api-usage.md).

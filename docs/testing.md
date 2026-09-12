# Testing

No automated test framework (JUnit, XCTest, etc.) is wired into the Java, Kotlin, or Swift parts
of this codebase today — the two Python packages are the exception, each carrying a real `pytest`
suite run in CI. This page documents how changes are actually verified.

```mermaid
flowchart LR
    CHG["Code change"] --> WHICH{"Which part?"}
    WHICH -->|"Backend (Java)"| J["mvn package →<br/>run the real jar / worked example"]
    WHICH -->|"Desktop (Kotlin)"| K["compileKotlinDesktop →<br/>real ./gradlew run session"]
    WHICH -->|"Mobile (Swift)"| SW["xcodebuild simulator build →<br/>device run where hardware matters"]
    WHICH -->|"Python packages"| PYT["pytest (also in CI)"]
    J & K & SW & PYT --> CI["CI on push:<br/>build checks + Python tests"]
```

## Backend (Java/Maven modules)

- Files under a module's `src/test` directory are **runnable worked examples with a `main`
  method**, not `mvn test` targets — a repo-wide convention. Run one directly from an IDE, or via:

  ```
  mvn -pl <module> -am test-compile
  ```

  then run the compiled class on the built classpath. The ones that assert something and print a
  pass/fail tally (rather than just demonstrating an API) are the closest thing to a test suite
  here:

  | Worked example | Module | Covers |
  |---|---|---|
  | `ChunkedStreamingEncryptionSample` | `plugin` | The v2 chunked-AEAD layout: round-trip, truncation, tampering |
  | `StreamedUploadSample` | `plugin` | Content-file-backed uploads above the 32 MiB threshold |
  | `ConditionalDownloadSample` | `plugin` | `ETag`/`If-None-Match`/`304` on the content routes |
  | `IndexedLookupSample` | `plugin` | `SecondaryIndexed` lookups against equivalent full scans |
  | `ResumableUploadSample` | `plugin` | Multipart session geometry, resume, abort, dedup precheck |
  | `RedisClusteringSample` | `plugin` | Scheduler locks and the cross-instance pending-upload cache |
  | `S3ObjectStorageServiceSample` | `plugin` | Object-storage put/get/delete against a real bucket |
  | `RestFactorySample`, `RestFactoryCloudUserSample` | `plugin` | Generic route mounting and the `/cloudUsers` routes |
  | `ChunkPatchSample` | `versioning` | Chunk manifests, `PATCH` reassembly, version delta chains |
  | `PostgresSearchIndexSample` | `search` | The `tsvector`/GIN index and its in-memory fallback |
- The closest thing to an end-to-end smoke test is assembling a throwaway run directory by hand
  and starting the process from inside it — the layout a real deployment expects (the bootstrap
  jar plus a sibling `extensions/` folder holding the feature-module jars, and a `cloud-driver/`
  folder holding the two config files). Run `mvn clean install` first, then:

  ```bash
  mkdir -p /tmp/cd-smoke/extensions /tmp/cd-smoke/cloud-driver
  cp cloud-driver-bootstrap/target/cloud-driver-bootstrap-*.jar /tmp/cd-smoke/
  cp cloud-driver-extensions/*/target/cloud-driver-extensions-*.jar /tmp/cd-smoke/extensions/
  cp cloud-driver/*.json /tmp/cd-smoke/cloud-driver/          # real config, never committed
  cd /tmp/cd-smoke && java -Xmx6g -jar cloud-driver-bootstrap-*.jar
  ```

  The bootstrap jar and every extension jar must come from the same build — extension jars
  resolve shared classes off the host jar's classpath, so a mixed pair crashes at startup.
- New backend functionality is verified by actually running the built jar (or a worked example)
  against a real or local database, not by a unit test suite.

## Desktop app

- No automated test target exists. Changes are verified by compiling
  (`./gradlew compileKotlinDesktop`) and by an actual `./gradlew run` session exercising the
  feature by hand.
- Before packaging a release build, a real `./gradlew createDistributable` (or the platform
  install script) should be run at least once — a plain compile does not catch every packaging
  issue (e.g. JDK module trimming for the native runtime image).

## Mobile app

- No XCTest target exists either. Changes are verified via a real
  `xcodebuild ... -sdk iphonesimulator build` (not just a syntax check) and, where camera/file
  access is involved, an actual device run — the iOS Simulator cannot exercise every feature (for
  example, the document scanner requires real camera hardware).
- Regenerate the Xcode project (`xcodegen generate`) after adding, removing, or renaming any
  source file, before building.

## Python packages

The exception to the above: `cloud-driver-multiplatform-python` (the SDK) and
`cloud-driver-intelligence` (the semantic-search service) each have a real `pytest` suite —
network calls mocked in the SDK's case, so no live server is needed:

```
cd <package-dir>
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
pytest
```

## CI

Five automated checks run on pushes/pull requests (see [deployment.md](deployment.md) for the
full pipeline):

| Workflow | Verifies |
|---|---|
| Backend build (`maven.yml`) | `mvn package` across the whole Maven reactor — build only, no tests |
| Mobile build (`swift.yml`) | An Xcode simulator build of the mobile app — build only |
| Python SDK (`python.yml`) | `pytest` across Python 3.10/3.11/3.12 |
| Intelligence service (`intelligence.yml`) | `pytest` for the semantic-search service |
| Qodana (`qodana_code_quality.yml`) | Static analysis |

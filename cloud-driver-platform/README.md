# cloud-driver-platform

Parent aggregator for the **client-side** modules of `cloud-driver` — code that talks to a running `cloud-driver` server purely over its REST API, as opposed to the server-side modules (`cloud-driver-api`/`-auth`/`-plugin`/`-bootstrap`/`cloud-driver-extensions`) that implement that server.

## Project structure

Maven `packaging=pom` aggregator, `groupId=de.lino.cloud.platform`, `artifactId=cloud-driver-platform`. Its own Maven `<parent>` is the repo root `pom.xml` (`de.lino.cloud:cloud-driver`), and it declares two `<modules>`:

- **[`cloud-driver-platform-rest`](cloud-driver-platform-rest/README.md)** — a dependency-free (of any other module in this repo) REST API client library: `ApiClient`, `SessionManager`, `Dtos`, `TokenStore` + its OS-specific implementations.
- **[`cloud-driver-platform-app`](cloud-driver-platform-app/README.md)** — a JavaFX desktop client built on top of `cloud-driver-platform-rest`.

Sibling to `cloud-driver-extensions` (both are children of the root aggregator), **not** a submodule of it. `cloud-driver-platform` sits entirely outside the `cloud-driver-api ← cloud-driver-auth ← cloud-driver-plugin ← cloud-driver-bootstrap` server-side dependency chain — neither of its children depends on any of those four modules. This is deliberate: a desktop/mobile/CLI client should only ever need an HTTP connection to a server, never the server's own database credentials or encryption internals on its classpath.

## Performance

N/A — this module declares no source of its own, only aggregates its two children for the Maven reactor. See each child's own README for its performance characteristics.

## Data handling

N/A — no entities, no persistence. See `cloud-driver-platform-rest`'s README for the JSON DTOs it exchanges with the server over HTTP.

## Safety & security

N/A — a pure aggregator `pom` module has no runtime behavior and therefore no security surface of its own. See `cloud-driver-platform-rest`'s README for how session tokens are transmitted/stored, and CLAUDE.md's "JWT authentication for end-user clients" section for the server-side contract both children talk to.

## Scalability

N/A — not applicable to a build-only aggregator.

## API surface

None — this module exposes no Java API of its own, only groups its two children in the Maven reactor.

## Building this module

There's no public API to call — build (or build+run) one of its children instead. This substitutes for a code sample, per this module having no library surface of its own:

```bash
# Build both children (from the repo root)
mvn -pl cloud-driver-platform/cloud-driver-platform-rest,cloud-driver-platform/cloud-driver-platform-app -am install

# Run the desktop app directly
mvn -pl cloud-driver-platform/cloud-driver-platform-app -am org.openjfx:javafx-maven-plugin:0.0.8:run
```

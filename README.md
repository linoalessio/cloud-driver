# CloudDriver

![Java](https://img.shields.io/badge/Java-21-orange)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF)
![Swift](https://img.shields.io/badge/Swift-5.9-F05138)
![Python](https://img.shields.io/badge/Python-3.10%2B-3776AB)
![Build](https://img.shields.io/badge/Build-Maven-C71A36)
![Build](https://img.shields.io/badge/Build-Gradle-02303A)
![Build](https://img.shields.io/badge/Build-SwiftPM-F05138)
![Build](https://img.shields.io/badge/Build-pip-3776AB)
![Version](https://img.shields.io/badge/Version-1.0.6-blue)

An encrypted cloud storage system: a Java backend that envelope-encrypts every stored record
(AES-256-GCM, KMS/HSM-style key wrapping) before persisting it, exposed over a JWT-authenticated
REST API, with a native desktop client, a native iOS client, and a Python SDK on top of it.

This file is the map. Detailed, cross-cutting documentation lives under [`docs/`](docs/); each
module's own `README.md` (linked below) is the reference for that module's code.

## Documentation

| Page | Covers |
|---|---|
| [docs/architecture.md](docs/architecture.md) | How the system fits together, request flow, layering, dependency direction |
| [docs/security.md](docs/security.md) | Encryption, authentication, authorization, network hardening |
| [docs/configuration.md](docs/configuration.md) | Every environment-specific config file and key |
| [docs/getting-started.md](docs/getting-started.md) | Building and running the backend, desktop app, and mobile app locally |
| [docs/api-reference.md](docs/api-reference.md) | The REST API surface both clients use |
| [docs/api-usage.md](docs/api-usage.md) | Code samples for every way to call the system — in-process Java, REST, and each client library |
| [docs/testing.md](docs/testing.md) | How changes are verified today |
| [docs/deployment.md](docs/deployment.md) | Release process, CI, and how the backend reaches a server |
| [docs/contributing.md](docs/contributing.md) | Code conventions and how to extend the system |
| [docs/troubleshooting.md](docs/troubleshooting.md) | Known issues and their fixes |

## Components

| Component | What it is | README |
|---|---|---|
| `cloud-driver-api` | Backend contracts — interfaces, abstract classes, value objects, exceptions | [`cloud-driver-api/README.md`](cloud-driver-api/README.md) |
| `cloud-driver-auth` | Email + password → JWT authentication engine; file/folder sharing, trash | [`cloud-driver-auth/README.md`](cloud-driver-auth/README.md) |
| `cloud-driver-plugin` | Every concrete backend implementation — encryption stack, database client, REST server | [`cloud-driver-plugin/README.md`](cloud-driver-plugin/README.md) |
| `cloud-driver-bootstrap` | The runnable backend entry point (one shaded jar) | [`cloud-driver-bootstrap/README.md`](cloud-driver-bootstrap/README.md) |
| `cloud-driver-extensions` | Feature modules loaded into the running backend process: REST API, database change watcher, operator terminal, backup job, metrics endpoint | [`cloud-driver-extensions/README.md`](cloud-driver-extensions/README.md) |
| `cloud-driver-platforms-desktop` | Desktop client app (macOS / Windows / Linux) | [README](cloud-driver-platforms/cloud-driver-platforms-desktop/README.md) |
| `cloud-driver-platforms-mobile` | Mobile client app (iOS) — GUI only, its networking/session layer lives in `cloud-driver-multiplatform-swift` | [README](cloud-driver-platforms/cloud-driver-platforms-mobile/README.md) |
| `cloud-driver-multiplatform` | Parent of the three per-ecosystem client SDKs below — one REST/WebSocket API, one client library per language | [README](cloud-driver-multiplatform/README.md) |
| `cloud-driver-multiplatform-java` | Java REST API client library, shared by the desktop app | [README](cloud-driver-multiplatform/cloud-driver-multiplatform-java/README.md) |
| `cloud-driver-multiplatform-swift` | Swift REST/WebSocket API client library, shared by the mobile app | [README](cloud-driver-multiplatform/cloud-driver-multiplatform-swift/README.md) |
| `cloud-driver-multiplatform-python` | Full-coverage Python SDK for writing microservices against the REST/WebSocket API | [README](cloud-driver-multiplatform/cloud-driver-multiplatform-python/README.md) |

See [docs/architecture.md](docs/architecture.md) for how these pieces actually run together (one
backend process hosting several feature modules, not a fleet of independently deployed services),
and for the individual feature modules under `cloud-driver-extensions`.

## Quick start

```
mvn clean install                              # build every backend module
mvn -pl cloud-driver-bootstrap -am package      # produce the runnable, shaded jar
java -jar cloud-driver-bootstrap/target/cloud-driver-bootstrap-<version>.jar
```

See [docs/getting-started.md](docs/getting-started.md) for the full setup, including the desktop
and mobile apps and required configuration files.

## License

See `LICENSE`.

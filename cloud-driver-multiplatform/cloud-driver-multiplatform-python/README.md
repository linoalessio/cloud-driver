# cloud-driver-multiplatform-python

A full-coverage Python SDK for `cloud-driver`'s JWT-authenticated REST/WebSocket API, so a Python
microservice can talk to the exact same server every other client in this repo
(`cloud-driver-multiplatform-java`/`cloud-driver-multiplatform-swift`/`-desktop`/`-mobile`) talks to, without hand-rolling
HTTP calls.

**Not part of the Maven reactor** - like `cloud-driver-platforms-desktop`/`-mobile`, this is a
client-side module in a different ecosystem (Python, not Java/Kotlin/Swift) with its own build
tooling (`pyproject.toml`/`pip`, not Maven). It is deliberately **not** listed in
`cloud-driver-multiplatform/pom.xml`'s `<modules>`, so `mvn clean install` never touches it. It
talks to the server purely over HTTP/WebSocket - it does not depend on, and cannot depend on,
`cloud-driver-api`/`-auth`/`-plugin` (those are JVM-only), the same boundary `cloud-driver-multiplatform-java`
documents for itself.

## Install

```bash
cd cloud-driver-multiplatform/cloud-driver-multiplatform-python
python3 -m venv .venv && source .venv/bin/activate
pip install -e .                 # core SDK (httpx + pydantic only)
pip install -e ".[live]"         # + GET /ws/updates live-push support
pip install -e ".[keyring]"      # + OS-keychain token persistence
pip install -e ".[dev]"          # + test tooling (pytest, respx)
```

## Quickstart

```python
from cloud_driver_client import CloudDriverClient

with CloudDriverClient("https://api.cloud-driver.de") as client:
    client.auth.login("user@example.com", "hunter2!A")

    # Full folder-scoped, cursor-paginated listing:
    for f in client.files.iter_all(folder_id=None):  # None = root
        print(f.file_name, f.size_bytes)

    client.files.upload("report.pdf", folder_id=None)
    client.folders.create("Invoices")
```

Async microservices (FastAPI, aiohttp, ...) use the same surface via `AsyncCloudDriverClient`,
which offloads every call onto a thread pool (`asyncio.to_thread`) rather than duplicating the
whole client on top of `httpx.AsyncClient`:

```python
from cloud_driver_client import AsyncCloudDriverClient

client = AsyncCloudDriverClient("https://api.cloud-driver.de")
await client.auth.login("user@example.com", "hunter2!A")
files = await client.files.list(folder_id=None)
```

## Coverage

Every route documented in the repo root `CLAUDE.md` (see "RestFactory", "JWT authentication for
end-user clients", "Folder organization", "File/folder sharing between accounts", "Recycle bin /
soft delete", "Metrics/observability exporter", "Live push via WebSocket") is reachable through one
of six resource namespaces on `CloudDriverClient`/`AsyncCloudDriverClient`:

| Namespace       | Covers |
|-----------------|--------|
| `.auth`         | login/register(+confirm)/reset-password(+confirm)/change-email(+confirm)/refresh/logout/me |
| `.cloud_users`  | account quota/theme (`GET/PUT /cloudUsers*`) |
| `.files`        | upload (stream/bytes/presigned), list (+pagination/`iter_all`), get, download (stream/bytes), delete, move, rename, restore, trash listing, sharing |
| `.folders`      | create, list (+pagination/`iter_all`), update, color, delete, restore, trash listing, sharing, shared-folder browsing |
| `.trash`        | empty trash |
| `.admin`        | list accounts, audit log, metrics snapshot (admin-gated) |

Plus `client.live_updates()` for the `GET /ws/updates` push channel (`pip install
cloud-driver-client[live]`).

## Design notes

- **Sync-first.** `CloudDriverClient` (backed by one shared, thread-safe `httpx.Client`) is the
  single source of truth for every endpoint; `AsyncCloudDriverClient` is a thin facade over it
  (see `async_client.py`'s own docstring for why duplicating ~40 endpoints across two independent
  implementations was rejected in favor of a `asyncio.to_thread` wrapper).
- **Automatic token refresh.** A `401` on any authenticated call transparently triggers one
  `POST /auth/refresh` + retry, mirroring `cloud-driver-multiplatform-java`'s `ApiClient` - see
  CLAUDE.md's "Refresh tokens" section for the server-side contract (single-use, rotated on every
  refresh) this relies on.
- **Typed errors.** Every non-2xx response raises a status-code-specific `ApiException` subclass
  (`NotFoundError`, `ConflictError`, `PayloadTooLargeError`, ...) - see `exceptions.py`.
- **Streaming, not base64.** Upload/download go through `POST /files` (raw
  `application/octet-stream`) and `GET /files/{id}/content`, never the JSON+base64 shape - a large
  file is never fully materialized as a Python object unless `download_bytes`/`upload_bytes` is
  used deliberately.
- **Hand-mirrored models, not generated.** `models.py` mirrors the server's JSON DTOs by hand, the
  same convention `cloud-driver-multiplatform-java`'s `Dtos.java` documents for itself (there is no
  OpenAPI spec to generate from) - keep it in sync by hand when a server-side DTO shape changes.

## Testing

```bash
pip install -e ".[dev]"
pytest
```

Tests mock the server via `respx` (no real network calls, no server required).

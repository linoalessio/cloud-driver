# API Reference

The backend exposes one JWT-authenticated REST API, mounted by the `cloud-driver-extensions-rest`
feature module. Both the desktop and mobile apps talk to the exact same routes. This page is a
summary; the full, current route-by-route contract is documented in that module's own README.

Full contract: [`cloud-driver-extensions/cloud-driver-extensions-rest/README.md`](../cloud-driver-extensions/cloud-driver-extensions-rest/README.md)

Client libraries covering these routes: [`cloud-driver-multiplatform-java`](../cloud-driver-multiplatform/cloud-driver-multiplatform-java/README.md) (Java), [`cloud-driver-multiplatform-swift`](../cloud-driver-multiplatform/cloud-driver-multiplatform-swift/README.md) (Swift), [`cloud-driver-multiplatform-python`](../cloud-driver-multiplatform/cloud-driver-multiplatform-python/README.md) (Python)

For code samples calling these routes (and every other way to use the system — the in-process Java
API, each client library, the operator terminal), see [api-usage.md](api-usage.md).

## Authentication

Every route other than the ones below requires an `Authorization: Bearer <token>` header (a
short-lived access token, refreshed via its own route once expired).

| Route | Method | Purpose |
|---|---|---|
| `/auth/register` | POST | Start signup — e-mails a verification code |
| `/auth/register/confirm` | POST | Confirm the code — creates the account, returns a token pair |
| `/auth/login` | POST | Exchange email + password for a token pair |
| `/auth/refresh` | POST | Exchange a still-valid refresh token for a fresh pair (rotates on use) |
| `/auth/logout` | POST | Invalidate a refresh token |
| `/auth/reset-password` | POST | Start a forgotten-password reset — e-mails a code |
| `/auth/reset-password/confirm` | POST | Confirm the code and set a new password |
| `/auth/change-email` | POST | Start an e-mail change (bearer-gated) — e-mails a code to the new address |
| `/auth/change-email/confirm` | POST | Confirm the code and apply the change |
| `/auth/me` | GET | The caller's own account id, email, and admin flag |

## Files and folders

| Route | Method | Purpose |
|---|---|---|
| `/files` | POST | Upload a file (raw body, not JSON) into a folder or the root |
| `/files` | GET | List the caller's files (optionally scoped to a folder, optionally paginated) |
| `/files/{id}` | GET | Fetch one file's metadata + content |
| `/files/{id}/content` | GET | Stream a file's content directly (no JSON/base64 wrapping) |
| `/files/{id}/folder` | PUT | Move a file |
| `/files/{id}/rename` | PUT | Rename a file |
| `/files/{id}` | DELETE | Move a file to the trash |
| `/folders` | POST / GET | Create / list folders |
| `/folders/{id}` | PUT / DELETE | Rename+move (combined) / trash a folder |
| `/folders/{id}/color` | PUT | Set a folder's display color |

## Trash

| Route | Method | Purpose |
|---|---|---|
| `/files/trash`, `/folders/trash` | GET | List trashed items |
| `/files/{id}/restore`, `/folders/{id}/restore` | POST | Restore a trashed item |
| `/trash/empty` | POST | Permanently remove everything currently trashed |

## Sharing

| Route | Method | Purpose |
|---|---|---|
| `/files/{id}/share`, `/folders/{id}/share` | POST | Grant another account read-only access |
| `/files/{id}/share/{email}`, `/folders/{id}/share/{email}` | DELETE | Revoke access |
| `/files/{id}/share`, `/folders/{id}/share` | GET | List who a file/folder is shared with (owner-side) |
| `/files/shared-with-me`, `/folders/shared-with-me` | GET | List what's been shared with the caller |
| `/folders/{id}/shared-contents` | GET | Browse inside a folder reached via a share |
| `/files/shared-by-me/count` | GET | Count of the caller's own files currently shared with someone |

## Account and admin

| Route | Method | Purpose |
|---|---|---|
| `/cloudUsers`, `/cloudUsers/{id}` | GET | The caller's own account record |
| `/cloudUsers/theme` | PUT | Sync the caller's light/dark theme preference |
| `/cloudUsers/exists` | GET | Whether an account exists under a given email (defense-in-depth for sharing) |
| `/admin/authUsers`, `/admin/authUsers/{id}` | GET | List/fetch every account (admin-only) |
| `/admin/audit-log` | GET | Security-relevant action history (admin-only) |
| `/admin/metrics` | GET | In-process metrics snapshot (admin-only) |

## Live updates

| Route | Protocol | Purpose |
|---|---|---|
| `/ws/updates` | WebSocket | Push notification when the caller's own data changes elsewhere (another device, a share, etc.) |

## Direct-to-storage transfer (optional, if configured)

| Route | Method | Purpose |
|---|---|---|
| `/files/upload-url` | POST | Get a presigned upload URL, bypassing the backend for the data itself |
| `/files/{id}/complete-upload` | POST | Finalize a presigned upload |
| `/files/{id}/download-url` | GET | Get a presigned download URL |

Clients fall back to the ordinary upload/download routes automatically if this isn't configured on
a given deployment (surfaced as a `503` response).

## Observability

| Route | Method | Purpose | Runs on |
|---|---|---|---|
| `/metrics` | GET | Prometheus scrape endpoint | The metrics feature module's own separate port, not the main API port |

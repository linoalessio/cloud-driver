"""The synchronous CloudDriverClient - the primary, fully-featured implementation.

Mirrors cloud-driver-platforms-rest's ApiClient (Java) endpoint-for-endpoint: every route
documented under CLAUDE.md's "RestFactory" / "JWT authentication for end-user clients" / "Folder
organization" / "File/folder sharing between accounts" / "Metrics/observability exporter" sections
is reachable through one of the resource namespaces below (`.auth`, `.cloud_users`, `.files`,
`.folders`, `.trash`, `.admin`). See `async_client.AsyncCloudDriverClient` for an async facade
built on top of this one via a thread-pool offload, and `live_updates.LiveUpdateClient` for the
GET /ws/updates push channel.
"""

from __future__ import annotations

import os
import urllib.parse
from collections.abc import Callable, Iterable, Iterator
from pathlib import Path
from typing import TYPE_CHECKING, Any, BinaryIO

import httpx

from ._http import raise_for_status
from .models import (
    AuditLogEntry,
    AuthTokens,
    AuthUser,
    BeginDownloadUrl,
    BeginUploadUrl,
    CloudUser,
    EmailExists,
    Folder,
    MessageResponse,
    MeResponse,
    MetricsSnapshot,
    Page,
    SharedByMeCount,
    SharedFileSummary,
    SharedFolderContents,
    SharedFolderSummary,
    StoredFile,
    StoredFileSummary,
    TrashedFileSummary,
    TrashedFolderSummary,
)
from .token_store import InMemoryTokenStore, TokenStore

if TYPE_CHECKING:
    # Not imported at runtime - live_updates() below imports it lazily so that merely constructing
    # a CloudDriverClient never requires the optional `websockets` dependency to be installed.
    from .live_updates import LiveUpdateClient

DEFAULT_TIMEOUT = 30.0
# Matches cloud-driver-platforms-rest's own ApiClient.TRANSFER_TIMEOUT - a plain 30s timeout would
# throttle-trip well below realistic upload/download throughput on a slow link (see CLAUDE.md's
# "cloud-driver-platforms-rest" section, "Fixed a real bug (2026-09-01)").
TRANSFER_TIMEOUT = 600.0
DEFAULT_CHUNK_SIZE = 1024 * 1024

# Sentinel distinguishing "folderId query param omitted" (list every owned file, GET /files'
# pre-folders default) from `folder_id=None` (list only the root folder's own files, "?folderId=root").
UNSCOPED = object()


def _iter_file_chunks(
    fileobj: BinaryIO, chunk_size: int, on_progress: Callable[[int], None] | None
) -> Iterator[bytes]:
    transferred = 0
    while chunk := fileobj.read(chunk_size):
        transferred += len(chunk)
        if on_progress is not None:
            on_progress(transferred)
        yield chunk


class CloudDriverClient:
    """A blocking client for the cloud-driver REST API. Thread-safe (backed by one shared
    ``httpx.Client``), suitable for use directly inside a sync microservice, or through
    :class:`cloud_driver_client.async_client.AsyncCloudDriverClient` inside an async one."""

    def __init__(
        self,
        base_url: str,
        *,
        token_store: TokenStore | None = None,
        timeout: float = DEFAULT_TIMEOUT,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self._token_store = token_store or InMemoryTokenStore()
        loaded = self._token_store.load()
        self._access_token, self._refresh_token = loaded if loaded else (None, None)
        self._client = httpx.Client(base_url=self.base_url, timeout=timeout)

        self.auth = _AuthResource(self)
        self.cloud_users = _CloudUsersResource(self)
        self.files = _FilesResource(self)
        self.folders = _FoldersResource(self)
        self.trash = _TrashResource(self)
        self.admin = _AdminResource(self)

    # -- lifecycle --------------------------------------------------------------------------

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> "CloudDriverClient":
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    # -- session state ------------------------------------------------------------------------

    @property
    def access_token(self) -> str | None:
        return self._access_token

    def restore_session(self) -> bool:
        """Re-reads a previously persisted session from the configured TokenStore. Returns
        whether a session was actually found - the caller should still expect the first real
        request to fail with UnauthorizedError if the refresh token has since been revoked."""
        loaded = self._token_store.load()
        if loaded is None:
            return False
        self._access_token, self._refresh_token = loaded
        return True

    def _apply_tokens(self, tokens: AuthTokens) -> None:
        self._access_token = tokens.access_token
        self._refresh_token = tokens.refresh_token
        self._token_store.save(tokens.access_token, tokens.refresh_token)

    def _clear_tokens(self) -> None:
        self._access_token = None
        self._refresh_token = None
        self._token_store.clear()

    def _try_refresh(self) -> bool:
        if not self._refresh_token:
            return False
        resp = self._client.post("/auth/refresh", json={"refreshToken": self._refresh_token})
        if resp.status_code >= 400:
            return False
        self._apply_tokens(AuthTokens.model_validate(resp.json()))
        return True

    # -- request plumbing ---------------------------------------------------------------------

    def _headers(self, auth_required: bool, extra: dict[str, str] | None = None) -> dict[str, str]:
        headers = dict(extra or {})
        if auth_required and self._access_token:
            headers["Authorization"] = f"Bearer {self._access_token}"
        return headers

    def _request(
        self,
        method: str,
        path: str,
        *,
        json: Any = None,
        params: dict[str, Any] | None = None,
        content: bytes | Iterable[bytes] | None = None,
        headers: dict[str, str] | None = None,
        auth_required: bool = True,
        timeout: float | None = None,
    ) -> httpx.Response:
        hdrs = self._headers(auth_required, headers)
        resp = self._client.request(method, path, json=json, params=params, content=content, headers=hdrs, timeout=timeout)
        if resp.status_code == 401 and auth_required and self._try_refresh():
            hdrs = self._headers(auth_required, headers)
            resp = self._client.request(method, path, json=json, params=params, content=content, headers=hdrs, timeout=timeout)
        return raise_for_status(resp)

    def _stream_download(
        self,
        path: str,
        *,
        on_chunk: Callable[[bytes], None],
        on_progress: Callable[[int], None] | None,
        chunk_size: int,
    ) -> httpx.Response:
        def _do() -> httpx.Response:
            headers = self._headers(True)
            with self._client.stream("GET", path, headers=headers, timeout=TRANSFER_TIMEOUT) as resp:
                if resp.status_code >= 400:
                    # Must fully read the body *before* the `with` block exits (a streaming
                    # response's .json()/.text raise httpx.ResponseNotRead otherwise) so
                    # raise_for_status can still extract the error message afterward.
                    resp.read()
                    return resp
                transferred = 0
                for chunk in resp.iter_bytes(chunk_size):
                    transferred += len(chunk)
                    on_chunk(chunk)
                    if on_progress is not None:
                        on_progress(transferred)
                return resp

        resp = _do()
        if resp.status_code == 401 and self._try_refresh():
            resp = _do()
        return raise_for_status(resp)

    # -- live updates ---------------------------------------------------------------------------

    def live_updates(self, *, reconnect_delay: float = 5.0) -> "LiveUpdateClient":
        """Returns a (not-yet-started) LiveUpdateClient for GET /ws/updates. Requires the
        `websockets` package (`pip install cloud-driver-client[live]`)."""
        from .live_updates import LiveUpdateClient

        return LiveUpdateClient(self.base_url, lambda: self._access_token, reconnect_delay=reconnect_delay)


class _Resource:
    def __init__(self, client: CloudDriverClient) -> None:
        self._c = client


class _AuthResource(_Resource):
    def login(self, email: str, password: str) -> AuthTokens:
        resp = self._c._request("POST", "/auth/login", json={"username": email, "password": password}, auth_required=False)
        tokens = AuthTokens.model_validate(resp.json())
        self._c._apply_tokens(tokens)
        return tokens

    def register(self, email: str, password: str) -> MessageResponse:
        resp = self._c._request("POST", "/auth/register", json={"username": email, "password": password}, auth_required=False)
        return MessageResponse.model_validate(resp.json())

    def confirm_registration(self, email: str, code: str) -> AuthTokens:
        resp = self._c._request(
            "POST", "/auth/register/confirm", json={"username": email, "code": code}, auth_required=False
        )
        tokens = AuthTokens.model_validate(resp.json())
        self._c._apply_tokens(tokens)
        return tokens

    def request_password_reset(self, email: str) -> MessageResponse:
        resp = self._c._request("POST", "/auth/reset-password", json={"username": email}, auth_required=False)
        return MessageResponse.model_validate(resp.json())

    def confirm_password_reset(self, email: str, code: str, new_password: str) -> AuthTokens:
        resp = self._c._request(
            "POST",
            "/auth/reset-password/confirm",
            json={"username": email, "code": code, "newPassword": new_password},
            auth_required=False,
        )
        tokens = AuthTokens.model_validate(resp.json())
        self._c._apply_tokens(tokens)
        return tokens

    def request_email_change(self, new_email: str) -> MessageResponse:
        resp = self._c._request("POST", "/auth/change-email", json={"newEmail": new_email})
        return MessageResponse.model_validate(resp.json())

    def confirm_email_change(self, code: str) -> MessageResponse:
        resp = self._c._request("POST", "/auth/change-email/confirm", json={"code": code})
        return MessageResponse.model_validate(resp.json())

    def refresh(self) -> AuthTokens:
        if not self._c._try_refresh():
            resp = self._c._request(
                "POST", "/auth/refresh", json={"refreshToken": self._c._refresh_token}, auth_required=False
            )
            tokens = AuthTokens.model_validate(resp.json())
            self._c._apply_tokens(tokens)
        assert self._c._access_token and self._c._refresh_token
        return AuthTokens(token=self._c._access_token, refreshToken=self._c._refresh_token)

    def logout(self) -> None:
        refresh_token = self._c._refresh_token
        if refresh_token:
            self._c._request("POST", "/auth/logout", json={"refreshToken": refresh_token}, auth_required=False)
        self._c._clear_tokens()

    def me(self) -> MeResponse:
        return MeResponse.model_validate(self._c._request("GET", "/auth/me").json())


class _CloudUsersResource(_Resource):
    def list(self) -> list[CloudUser]:
        return [CloudUser.model_validate(x) for x in self._c._request("GET", "/cloudUsers").json()]

    def get(self, auth_user_id: str) -> CloudUser:
        return CloudUser.model_validate(self._c._request("GET", f"/cloudUsers/{auth_user_id}").json())

    def set_theme(self, theme_mode: str | None) -> None:
        self._c._request("PUT", "/cloudUsers/theme", json={"themeMode": theme_mode})

    def exists(self, email: str) -> bool:
        resp = self._c._request("GET", "/cloudUsers/exists", params={"email": email})
        return EmailExists.model_validate(resp.json()).exists


class _FilesResource(_Resource):
    def upload(
        self,
        local_path: str | os.PathLike[str],
        *,
        folder_id: str | None = None,
        file_name: str | None = None,
        on_progress: Callable[[int], None] | None = None,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> StoredFileSummary:
        local_path = Path(local_path)
        with local_path.open("rb") as fileobj:
            return self.upload_stream(
                fileobj,
                file_name=file_name or local_path.name,
                folder_id=folder_id,
                on_progress=on_progress,
                chunk_size=chunk_size,
            )

    def upload_bytes(self, data: bytes, file_name: str, *, folder_id: str | None = None) -> StoredFileSummary:
        params = {"fileName": file_name}
        if folder_id is not None:
            params["folderId"] = folder_id
        resp = self._c._request(
            "POST",
            "/files",
            params=params,
            content=data,
            headers={"Content-Type": "application/octet-stream"},
            timeout=TRANSFER_TIMEOUT,
        )
        return StoredFileSummary.model_validate(resp.json())

    def upload_stream(
        self,
        fileobj: BinaryIO,
        *,
        file_name: str,
        folder_id: str | None = None,
        on_progress: Callable[[int], None] | None = None,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> StoredFileSummary:
        params = {"fileName": file_name}
        if folder_id is not None:
            params["folderId"] = folder_id
        resp = self._c._request(
            "POST",
            "/files",
            params=params,
            content=_iter_file_chunks(fileobj, chunk_size, on_progress),
            headers={"Content-Type": "application/octet-stream"},
            timeout=TRANSFER_TIMEOUT,
        )
        return StoredFileSummary.model_validate(resp.json())

    def list(
        self, folder_id: str | None | object = UNSCOPED, *, limit: int | None = None, cursor: str | None = None
    ) -> list[StoredFileSummary] | Page[StoredFileSummary]:
        params: dict[str, Any] = {}
        if folder_id is not UNSCOPED:
            params["folderId"] = "root" if folder_id is None else folder_id
        if limit is not None:
            params["limit"] = limit
            if cursor is not None:
                params["cursor"] = cursor
        data = self._c._request("GET", "/files", params=params).json()
        if limit is not None:
            return Page[StoredFileSummary].model_validate(data)
        return [StoredFileSummary.model_validate(x) for x in data]

    def iter_all(
        self, folder_id: str | None | object = UNSCOPED, *, page_size: int = 200
    ) -> Iterator[StoredFileSummary]:
        cursor: str | None = None
        while True:
            page = self.list(folder_id, limit=page_size, cursor=cursor)
            assert isinstance(page, Page)
            yield from page.items
            if page.next_cursor is None:
                return
            cursor = page.next_cursor

    def get(self, file_id: str) -> StoredFile:
        return StoredFile.model_validate(self._c._request("GET", f"/files/{file_id}").json())

    def download_to_path(
        self,
        file_id: str,
        destination: str | os.PathLike[str],
        *,
        on_progress: Callable[[int], None] | None = None,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> Path:
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("wb") as out:
            self._c._stream_download(
                f"/files/{file_id}/content", on_chunk=out.write, on_progress=on_progress, chunk_size=chunk_size
            )
        return destination

    def download_bytes(self, file_id: str, *, chunk_size: int = DEFAULT_CHUNK_SIZE) -> bytes:
        buffer = bytearray()
        self._c._stream_download(
            f"/files/{file_id}/content", on_chunk=buffer.extend, on_progress=None, chunk_size=chunk_size
        )
        return bytes(buffer)

    def delete(self, file_id: str) -> None:
        self._c._request("DELETE", f"/files/{file_id}")

    def move(self, file_id: str, folder_id: str | None) -> None:
        self._c._request("PUT", f"/files/{file_id}/folder", json={"folderId": folder_id})

    def rename(self, file_id: str, new_file_name: str) -> None:
        self._c._request("PUT", f"/files/{file_id}/rename", json={"fileName": new_file_name})

    def restore(self, file_id: str) -> None:
        self._c._request("POST", f"/files/{file_id}/restore")

    def list_trash(self) -> list[TrashedFileSummary]:
        return [TrashedFileSummary.model_validate(x) for x in self._c._request("GET", "/files/trash").json()]

    def share(self, file_id: str, grantee_email: str) -> None:
        self._c._request("POST", f"/files/{file_id}/share", json={"granteeEmail": grantee_email})

    def revoke_share(self, file_id: str, grantee_email: str) -> None:
        self._c._request("DELETE", f"/files/{file_id}/share/{urllib.parse.quote(grantee_email, safe='')}")

    def list_shares(self, file_id: str) -> list[str]:
        return list(self._c._request("GET", f"/files/{file_id}/share").json())

    def list_shared_with_me(self) -> list[SharedFileSummary]:
        return [SharedFileSummary.model_validate(x) for x in self._c._request("GET", "/files/shared-with-me").json()]

    def count_shared_by_me(self) -> int:
        resp = self._c._request("GET", "/files/shared-by-me/count")
        return SharedByMeCount.model_validate(resp.json()).count

    def begin_upload_url(self, file_name: str, size_bytes: int, *, folder_id: str | None = None) -> BeginUploadUrl:
        resp = self._c._request(
            "POST",
            "/files/upload-url",
            json={"fileName": file_name, "sizeBytes": size_bytes, "folderId": folder_id},
        )
        return BeginUploadUrl.model_validate(resp.json())

    def complete_upload(
        self, file_id: str, file_name: str, checksum_sha256: str, *, folder_id: str | None = None
    ) -> StoredFileSummary:
        resp = self._c._request(
            "POST",
            f"/files/{file_id}/complete-upload",
            json={"fileName": file_name, "checksumSha256": checksum_sha256, "folderId": folder_id},
        )
        return StoredFileSummary.model_validate(resp.json())

    def begin_download_url(self, file_id: str) -> BeginDownloadUrl:
        return BeginDownloadUrl.model_validate(self._c._request("GET", f"/files/{file_id}/download-url").json())


class _FoldersResource(_Resource):
    def create(self, name: str, *, parent_folder_id: str | None = None) -> Folder:
        resp = self._c._request("POST", "/folders", json={"name": name, "parentFolderId": parent_folder_id})
        return Folder.model_validate(resp.json())

    def list(
        self, parent_folder_id: str | None = None, *, limit: int | None = None, cursor: str | None = None
    ) -> list[Folder] | Page[Folder]:
        params: dict[str, Any] = {}
        if parent_folder_id is not None:
            params["parentFolderId"] = parent_folder_id
        if limit is not None:
            params["limit"] = limit
            if cursor is not None:
                params["cursor"] = cursor
        data = self._c._request("GET", "/folders", params=params).json()
        if limit is not None:
            return Page[Folder].model_validate(data)
        return [Folder.model_validate(x) for x in data]

    def iter_all(self, parent_folder_id: str | None = None, *, page_size: int = 200) -> Iterator[Folder]:
        cursor: str | None = None
        while True:
            page = self.list(parent_folder_id, limit=page_size, cursor=cursor)
            assert isinstance(page, Page)
            yield from page.items
            if page.next_cursor is None:
                return
            cursor = page.next_cursor

    def update(self, folder_id: str, *, name: str, parent_folder_id: str | None = None) -> Folder:
        resp = self._c._request(
            "PUT", f"/folders/{folder_id}", json={"name": name, "parentFolderId": parent_folder_id}
        )
        return Folder.model_validate(resp.json())

    def set_color(self, folder_id: str, color: str | None) -> None:
        self._c._request("PUT", f"/folders/{folder_id}/color", json={"color": color})

    def delete(self, folder_id: str) -> None:
        self._c._request("DELETE", f"/folders/{folder_id}")

    def restore(self, folder_id: str) -> None:
        self._c._request("POST", f"/folders/{folder_id}/restore")

    def list_trash(self) -> list[TrashedFolderSummary]:
        return [TrashedFolderSummary.model_validate(x) for x in self._c._request("GET", "/folders/trash").json()]

    def share(self, folder_id: str, grantee_email: str) -> None:
        self._c._request("POST", f"/folders/{folder_id}/share", json={"granteeEmail": grantee_email})

    def revoke_share(self, folder_id: str, grantee_email: str) -> None:
        self._c._request("DELETE", f"/folders/{folder_id}/share/{urllib.parse.quote(grantee_email, safe='')}")

    def list_shares(self, folder_id: str) -> list[str]:
        return list(self._c._request("GET", f"/folders/{folder_id}/share").json())

    def list_shared_with_me(self) -> list[SharedFolderSummary]:
        return [
            SharedFolderSummary.model_validate(x) for x in self._c._request("GET", "/folders/shared-with-me").json()
        ]

    def shared_contents(self, folder_id: str) -> SharedFolderContents:
        resp = self._c._request("GET", f"/folders/{folder_id}/shared-contents")
        return SharedFolderContents.model_validate(resp.json())


class _TrashResource(_Resource):
    def empty(self) -> None:
        self._c._request("POST", "/trash/empty")


class _AdminResource(_Resource):
    def list_auth_users(self) -> list[AuthUser]:
        return [AuthUser.model_validate(x) for x in self._c._request("GET", "/admin/authUsers").json()]

    def get_auth_user(self, auth_user_id: str) -> AuthUser:
        return AuthUser.model_validate(self._c._request("GET", f"/admin/authUsers/{auth_user_id}").json())

    def audit_log(self, *, all: bool = False, email: str | None = None) -> list[AuditLogEntry]:
        params: dict[str, Any] = {}
        if all:
            params["all"] = "true"
        if email is not None:
            params["email"] = email
        data = self._c._request("GET", "/admin/audit-log", params=params).json()
        return [AuditLogEntry.model_validate(x) for x in data]

    def metrics(self) -> MetricsSnapshot:
        return MetricsSnapshot.model_validate(self._c._request("GET", "/admin/metrics").json())

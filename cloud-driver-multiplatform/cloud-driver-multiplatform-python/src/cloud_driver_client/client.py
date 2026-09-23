"""The synchronous CloudDriverClient - the primary, fully-featured implementation.

Mirrors cloud-driver-platforms-rest's ApiClient (Java) endpoint-for-endpoint: every route
documented under CLAUDE.md's "RestFactory" / "JWT authentication for end-user clients" / "Folder
organization" / "File/folder sharing between accounts" / "Metrics/observability exporter" sections,
plus every server-side capability added since under the API reference (thumbnails, content
versioning, the activity feed, search, sharing permission levels + public links, content-scan
status, and the sync optimistic-concurrency precondition) is reachable through one of the resource
namespaces below (`.auth`, `.cloud_users`, `.files`, `.folders`, `.trash`, `.admin`, `.activity`)
or a top-level method (`.search`, `.download_public_file_to_path`/`_bytes`). See
`async_client.AsyncCloudDriverClient` for an async facade built on top of this one via a
thread-pool offload, and `live_updates.LiveUpdateClient` for the GET /ws/updates push channel.
Webhooks () are deliberately not mirrored here - CLAUDE.md
documents that capability as Java-only.
"""

from __future__ import annotations

import base64
import hashlib
import os
import tempfile
import urllib.parse
from collections.abc import Callable, Iterable, Iterator
from pathlib import Path
from typing import TYPE_CHECKING, Any, BinaryIO

import httpx

from . import crypto
from ._http import raise_for_status
from .exceptions import (
    ConflictError,
    ContentIntegrityError,
    NotFoundError,
    ServiceUnavailableError,
    SyncConflictError,
    UnsupportedEncryptionError,
)
from .models import (
    ActivityEntry,
    AuditLogEntry,
    AuthTokens,
    AuthUser,
    BeginDownloadUrl,
    BeginUploadSessionResult,
    BeginUploadUrl,
    CloudUser,
    ConditionalDownload,
    EmailExists,
    FileVersionSummary,
    Folder,
    MessageResponse,
    MeResponse,
    MetricsSnapshot,
    Page,
    PublicFileLinkSummary,
    DuplicateFileGroup,
    SearchResult,
    SemanticSearchResult,
    TagSuggestion,
    SharedByMeCount,
    SharedFileSummary,
    SharedFolderContents,
    SharedFolderSummary,
    StoredFile,
    StoredFileSummary,
    TrashedFileSummary,
    TrashedFolderSummary,
    UploadEncryption,
    UploadSession,
    UploadSessionPartUrl,
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

# The status a conditional content request gets when the caller's entity tag still matches - no
# body follows, and the destination must be left exactly as it was.
_NOT_MODIFIED_STATUS = 304

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


class _LazyFileWriter:
    """Opens ``destination`` for writing on the first chunk only, so a 304 (no chunks) leaves
    an existing local copy untouched instead of truncating it the moment the request starts."""

    def __init__(self, destination: Path) -> None:
        self._destination = destination
        self._handle: BinaryIO | None = None
        self._opened = False

    def write(self, chunk: bytes) -> None:
        self.open_for_writing()
        assert self._handle is not None
        self._handle.write(chunk)

    def open_for_writing(self) -> None:
        """Creates (and truncates) the destination, even with no chunk to write.

        Called explicitly on a real response whose body turned out to be empty - a zero-byte file
        is still content, and leaving whatever was already at ``destination`` in place would report
        a download that never happened while the caller went on reading a stale copy.
        """
        if self._handle is None:
            self._handle = self._destination.open("wb")
            self._opened = True

    def close(self) -> None:
        if self._handle is not None:
            self._handle.close()
            self._handle = None

    @property
    def opened(self) -> bool:
        """Whether the destination was ever written to - stays True after :meth:`close`."""
        return self._opened


def _sha256_hex(local_path: Path, chunk_size: int = DEFAULT_CHUNK_SIZE) -> str:
    """The file's plaintext SHA-256, lowercase hex - the digest complete-upload is checked
    against, always computed over the plaintext whether the object is encrypted or not."""
    digest = hashlib.sha256()
    with local_path.open("rb") as source:
        while chunk := source.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


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
        self.activity = _ActivityResource(self)

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
        auth_required: bool = True,
        extra_headers: dict[str, str] | None = None,
        not_modified_ok: bool = False,
    ) -> httpx.Response:
        def _do() -> httpx.Response:
            headers = self._headers(auth_required, extra_headers)
            with self._client.stream("GET", path, headers=headers, timeout=TRANSFER_TIMEOUT) as resp:
                if resp.status_code >= 400:
                    # Must fully read the body *before* the `with` block exits (a streaming
                    # response's .json()/.text raise httpx.ResponseNotRead otherwise) so
                    # raise_for_status can still extract the error message afterward.
                    resp.read()
                    return resp
                if not_modified_ok and resp.status_code == _NOT_MODIFIED_STATUS:
                    # A 304 carries no body - returned before iter_bytes, so `on_chunk` is never
                    # called and nothing downstream opens or truncates a destination file.
                    return resp
                transferred = 0
                for chunk in resp.iter_bytes(chunk_size):
                    transferred += len(chunk)
                    on_chunk(chunk)
                    if on_progress is not None:
                        on_progress(transferred)
                return resp

        resp = _do()
        if auth_required and resp.status_code == 401 and self._try_refresh():
            resp = _do()
        return raise_for_status(resp)

    def _conditional_download(
        self,
        path: str,
        destination: str | os.PathLike[str],
        entity_tag: str | None,
        on_progress: Callable[[int], None] | None,
        chunk_size: int,
    ) -> ConditionalDownload:
        """GETs `path` conditionally, writing to `destination` only if the server sends a body."""
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        writer = _LazyFileWriter(destination)
        headers = {"If-None-Match": entity_tag} if entity_tag else None
        try:
            resp = self._stream_download(
                path,
                on_chunk=writer.write,
                on_progress=on_progress,
                chunk_size=chunk_size,
                extra_headers=headers,
                not_modified_ok=True,
            )
            if resp.status_code != _NOT_MODIFIED_STATUS and not writer.opened:
                # A real response whose body was empty: the file's content genuinely is zero bytes
                # now, so the destination has to be created/truncated all the same. Only a 304 is
                # allowed to leave it alone.
                writer.open_for_writing()
        finally:
            writer.close()
        server_tag = resp.headers.get("ETag")
        if resp.status_code == _NOT_MODIFIED_STATUS:
            return ConditionalDownload(not_modified=True, path=None, entity_tag=server_tag or entity_tag)
        return ConditionalDownload(not_modified=False, path=destination, entity_tag=server_tag)

    # -- live updates ---------------------------------------------------------------------------

    def live_updates(self, *, reconnect_delay: float = 5.0) -> "LiveUpdateClient":
        """Returns a (not-yet-started) LiveUpdateClient for GET /ws/updates. Requires the
        `websockets` package (`pip install cloud-driver-client[live]`)."""
        from .live_updates import LiveUpdateClient

        return LiveUpdateClient(self.base_url, lambda: self._access_token, reconnect_delay=reconnect_delay)

    # -- search  ----------------------------------------------

    def search(self, query: str, *, limit: int = 25) -> list[SearchResult]:
        """GET /search?q=&limit= - filename/indexed-text-content search over the caller's own
        files, top-level rather than nested under `.files` (matching how the server itself treats
        this as a standalone capability, not a files sub-resource). Returns an empty list for a
        blank query, or if `cloud-driver-extensions-search` isn't running on this deployment
        (raises ServiceUnavailableError instead if a non-blank query is rejected for that reason -
        an empty/blank query short-circuits server-side before that check even runs)."""
        resp = self._request("GET", "/search", params={"q": query, "limit": limit})
        return [SearchResult.model_validate(x) for x in resp.json()]

    def semantic_search(self, query: str, *, limit: int = 25) -> list[SemanticSearchResult]:
        """GET /search/semantic?q=&limit= - ranks the caller's accessible files by *meaning*
        rather than literal text, so "invoice from the garage" can surface `scan_0042.pdf`.

        Complements `search()` rather than replacing it; the two find genuinely different things.
        Raises ServiceUnavailableError if semantic search isn't running on this deployment, which
        is a caller's cue to fall back to `search()` rather than to surface an error. A blank
        query short-circuits server-side and returns an empty list."""
        resp = self._request("GET", "/search/semantic", params={"q": query, "limit": limit})
        return [SemanticSearchResult.model_validate(x) for x in resp.json()]

    def find_duplicates(
        self, *, minimum_similarity: float = 0.95, limit: int = 50
    ) -> list[DuplicateFileGroup]:
        """GET /files/duplicates - groups the caller's files into sets that look like the same
        document.

        `minimum_similarity` should stay high (0.9+): similarity is not linear in perceived
        sameness, so a low threshold groups everything that merely shares a topic - which, for a
        result a user reads as "these are duplicates", is worse than returning nothing. A value
        outside [0, 1] is rejected server-side with BadRequestError rather than clamped."""
        resp = self._request(
            "GET",
            "/files/duplicates",
            params={"minimumSimilarity": minimum_similarity, "limit": limit},
        )
        return [DuplicateFileGroup.model_validate(x) for x in resp.json()]

    def suggest_file_tags(self, file_id: str, *, limit: int = 5) -> list[TagSuggestion]:
        """GET /files/{id}/tags - suggests descriptive labels for one file.

        Access-checked exactly like a download, so a file the caller cannot read raises
        NotFoundError rather than revealing that it exists. An empty list is a normal answer for a
        file that was never indexed - not an error."""
        resp = self._request("GET", f"/files/{file_id}/tags", params={"limit": limit})
        return [TagSuggestion.model_validate(x) for x in resp.json()]

    # -- public share links  ---------------------------------

    def download_public_file_to_path(
        self,
        token: str,
        destination: str | os.PathLike[str],
        *,
        on_progress: Callable[[int], None] | None = None,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> Path:
        """GET /public/files/{token} - streams a publicly-shared file's content to `destination`.
        Genuinely unauthenticated: works even on a CloudDriverClient with no stored/valid session
        at all, since a public link's whole point is requiring no login. `token` comes from
        `client.files.create_public_link(...)`/`.list_public_links(...)`."""
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("wb") as out:
            self._stream_download(
                f"/public/files/{token}",
                on_chunk=out.write,
                on_progress=on_progress,
                chunk_size=chunk_size,
                auth_required=False,
            )
        return destination

    def download_public_file_bytes(self, token: str, *, chunk_size: int = DEFAULT_CHUNK_SIZE) -> bytes:
        """The in-memory counterpart to `download_public_file_to_path` - see its docstring."""
        buffer = bytearray()
        self._stream_download(
            f"/public/files/{token}", on_chunk=buffer.extend, on_progress=None, chunk_size=chunk_size, auth_required=False
        )
        return bytes(buffer)


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
        """GET /files - one listing, exactly as the route answers it.

        `folder_id` left at `UNSCOPED` omits the parameter, which spans every folder; `None` means
        the root specifically. With `limit` the response is a cursor `Page`; without it, a bare
        array the server caps at 500 entries and flags in no way - no header, no field, no error.
        So anything that needs completeness must go through :meth:`iter_all`, never this.
        """
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
        """Yields every matching file, paging as it goes.

        With an explicit `folder_id` (including `None`, the root) this pages that one folder.
        The default, unscoped form walks the whole folder tree instead of asking for every file
        at once: only a folder-scoped listing can be paged to completion, so an unscoped call
        that opted into paging would quietly come back scoped to the root.
        """
        if folder_id is UNSCOPED:
            yield from self._iter_tree(None, page_size=page_size)
        else:
            yield from self._iter_folder(folder_id, page_size=page_size)

    def _iter_folder(self, folder_id: str | None, *, page_size: int) -> Iterator[StoredFileSummary]:
        """Pages one folder's direct contents to exhaustion."""
        cursor: str | None = None
        while True:
            page = self.list(folder_id, limit=page_size, cursor=cursor)
            assert isinstance(page, Page)
            yield from page.items
            if page.next_cursor is None:
                return
            cursor = page.next_cursor

    def _iter_tree(self, root_folder_id: str | None, *, page_size: int) -> Iterator[StoredFileSummary]:
        """Walks `root_folder_id` and every folder beneath it, yielding each folder's files.

        An explicit stack rather than recursion: a deeply nested tree must not be able to hit the
        interpreter's recursion limit. `folders.iter_all(None)` already means "the top-level
        folders", so the root needs no special case.
        """
        pending: list[str | None] = [root_folder_id]
        while pending:
            folder_id = pending.pop()
            yield from self._iter_folder(folder_id, page_size=page_size)
            pending.extend(sub.folder_id for sub in self._c.folders.iter_all(folder_id, page_size=page_size))

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

    def download_to_path_if_changed(
        self,
        file_id: str,
        destination: str | os.PathLike[str],
        *,
        entity_tag: str | None = None,
        on_progress: Callable[[int], None] | None = None,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> ConditionalDownload:
        """GET /files/{id}/content, telling the server which copy the caller already holds.

        Pass the `entity_tag` the previous download of this file returned: the server answers 304
        while it still matches, and the result's `not_modified` is then True with `destination`
        never opened - keep using the copy that tag described. A `None` tag makes an ordinary
        unconditional download. See `download_to_path` for the plain form.
        """
        return self._c._conditional_download(
            f"/files/{file_id}/content", destination, entity_tag, on_progress, chunk_size
        )

    def download_bytes(self, file_id: str, *, chunk_size: int = DEFAULT_CHUNK_SIZE) -> bytes:
        buffer = bytearray()
        self._c._stream_download(
            f"/files/{file_id}/content", on_chunk=buffer.extend, on_progress=None, chunk_size=chunk_size
        )
        return bytes(buffer)

    def get_thumbnail(self, file_id: str) -> bytes | None:
        """GET /files/{id}/thumbnail - raw JPEG bytes, or None if no thumbnail exists (the file
        isn't a previewable type, its content is too large to have been thumbnailed, or
        `cloud-driver-extensions-thumbnails` isn't running on this deployment). Both a 404 and a
        503 mean exactly the same thing here - "no thumbnail available" - so neither is treated as
        an error worth propagating, unlike every other route in this SDK."""
        try:
            resp = self._c._request("GET", f"/files/{file_id}/thumbnail")
        except (NotFoundError, ServiceUnavailableError):
            return None
        return resp.content

    def replace_content(
        self,
        file_id: str,
        data: bytes,
        *,
        expected_updated_at_epoch_millis: int | None = None,
    ) -> StoredFileSummary:
        """PUT /files/{id}/content - overwrites file_id's content in place, capturing whatever was
        live beforehand as a new retained version (see .list_versions/.restore_version;
        ). Pass `expected_updated_at_epoch_millis` (from a prior
        .get()/.list() call's `updated_at_epoch_milli`) for optimistic concurrency (section 10): if
        another write already changed the file since that timestamp, the canonical file is left
        completely untouched and this raises SyncConflictError instead of silently overwriting a
        concurrent edit - `SyncConflictError.conflicted_copy` is the brand-new StoredFileSummary
        your own content was saved into. Omit it to unconditionally overwrite, matching this
        route's pre-section-10 behavior."""
        params: dict[str, Any] = {}
        if expected_updated_at_epoch_millis is not None:
            params["expectedUpdatedAt"] = expected_updated_at_epoch_millis
        try:
            resp = self._c._request(
                "PUT",
                f"/files/{file_id}/content",
                params=params,
                content=data,
                headers={"Content-Type": "application/octet-stream"},
                timeout=TRANSFER_TIMEOUT,
            )
        except ConflictError as exc:
            if isinstance(exc.body, dict) and "fileId" in exc.body:
                raise SyncConflictError(
                    exc.status_code, exc.message, exc.body, StoredFileSummary.model_validate(exc.body)
                ) from exc
            raise
        return StoredFileSummary.model_validate(resp.json())

    def list_versions(self, file_id: str) -> list[FileVersionSummary]:
        """GET /files/{id}/versions - every currently-retained prior version, oldest first."""
        return [
            FileVersionSummary.model_validate(x) for x in self._c._request("GET", f"/files/{file_id}/versions").json()
        ]

    def download_version_to_path(
        self,
        file_id: str,
        version_number: int,
        destination: str | os.PathLike[str],
        *,
        on_progress: Callable[[int], None] | None = None,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> Path:
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("wb") as out:
            self._c._stream_download(
                f"/files/{file_id}/versions/{version_number}/content",
                on_chunk=out.write,
                on_progress=on_progress,
                chunk_size=chunk_size,
            )
        return destination

    def download_version_to_path_if_changed(
        self,
        file_id: str,
        version_number: int,
        destination: str | os.PathLike[str],
        *,
        entity_tag: str | None = None,
        on_progress: Callable[[int], None] | None = None,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> ConditionalDownload:
        """GET /files/{id}/versions/{n}/content, conditional exactly as
        `download_to_path_if_changed` is - an unchanged version transfers no body."""
        return self._c._conditional_download(
            f"/files/{file_id}/versions/{version_number}/content", destination, entity_tag, on_progress, chunk_size
        )

    def download_version_bytes(self, file_id: str, version_number: int, *, chunk_size: int = DEFAULT_CHUNK_SIZE) -> bytes:
        buffer = bytearray()
        self._c._stream_download(
            f"/files/{file_id}/versions/{version_number}/content",
            on_chunk=buffer.extend,
            on_progress=None,
            chunk_size=chunk_size,
        )
        return bytes(buffer)

    def restore_version(self, file_id: str, version_number: int) -> StoredFileSummary:
        """POST /files/{id}/versions/{n}/restore - restores a prior version by replacing the
        file's current content with it (which itself captures the about-to-be-superseded content
        as yet another new version first - nothing is ever lost)."""
        resp = self._c._request("POST", f"/files/{file_id}/versions/{version_number}/restore")
        return StoredFileSummary.model_validate(resp.json())

    def list_activity(
        self, file_id: str, *, limit: int | None = None, cursor: str | None = None
    ) -> Page[ActivityEntry]:
        """GET /files/{id}/activity - always paginated (unlike .list()), newest first."""
        params: dict[str, Any] = {}
        if limit is not None:
            params["limit"] = limit
        if cursor is not None:
            params["cursor"] = cursor
        data = self._c._request("GET", f"/files/{file_id}/activity", params=params).json()
        return Page[ActivityEntry].model_validate(data)

    def iter_activity(self, file_id: str, *, page_size: int = 50) -> Iterator[ActivityEntry]:
        cursor: str | None = None
        while True:
            page = self.list_activity(file_id, limit=page_size, cursor=cursor)
            yield from page.items
            if page.next_cursor is None:
                return
            cursor = page.next_cursor

    def create_public_link(self, file_id: str, *, expires_at_epoch_millis: int | None = None) -> PublicFileLinkSummary:
        """POST /files/{id}/public-link - owner-only. See CloudDriverClient.download_public_file_to_path/_bytes for consuming the resulting token."""
        resp = self._c._request(
            "POST", f"/files/{file_id}/public-link", json={"expiresAtEpochMillis": expires_at_epoch_millis}
        )
        return PublicFileLinkSummary.model_validate(resp.json())

    def list_public_links(self, file_id: str) -> list[PublicFileLinkSummary]:
        """GET /files/{id}/public-link - every currently-active public link on file_id, owner-only."""
        return [
            PublicFileLinkSummary.model_validate(x)
            for x in self._c._request("GET", f"/files/{file_id}/public-link").json()
        ]

    def revoke_public_link(self, file_id: str, token: str) -> None:
        """DELETE /files/{id}/public-link/{token} - idempotent, owner-only."""
        self._c._request("DELETE", f"/files/{file_id}/public-link/{urllib.parse.quote(token, safe='')}")

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

    def share(
        self,
        file_id: str,
        grantee_email: str,
        *,
        permission_level: str = "VIEW",
        expires_at_epoch_millis: int | None = None,
    ) -> None:
        """POST /files/{id}/share. `permission_level` is "VIEW" (default) or "EDIT" - EDIT lets
        the grantee call .replace_content on this exact file (a direct grant only, never
        folder-inherited); `expires_at_epoch_millis` defaults to never-expiring
        (). Existing callers passing neither keyword argument get
        the exact pre-section-6 behavior."""
        self._c._request(
            "POST",
            f"/files/{file_id}/share",
            json={
                "granteeEmail": grantee_email,
                "permissionLevel": permission_level,
                "expiresAtEpochMillis": expires_at_epoch_millis,
            },
        )

    def revoke_share(self, file_id: str, grantee_email: str) -> None:
        self._c._request("DELETE", f"/files/{file_id}/share/{urllib.parse.quote(grantee_email, safe='')}")

    def list_shares(self, file_id: str) -> list[str]:
        return list(self._c._request("GET", f"/files/{file_id}/share").json())

    def list_shared_with_me(self) -> list[SharedFileSummary]:
        return [SharedFileSummary.model_validate(x) for x in self._c._request("GET", "/files/shared-with-me").json()]

    def count_shared_by_me(self) -> int:
        resp = self._c._request("GET", "/files/shared-by-me/count")
        return SharedByMeCount.model_validate(resp.json()).count

    def begin_upload_url(
        self,
        file_name: str,
        size_bytes: int,
        *,
        folder_id: str | None = None,
        allow_encrypted: bool = False,
    ) -> BeginUploadUrl:
        """Issue a raw presigned upload ticket.

        `size_bytes` is the PLAINTEXT length - the server derives the stored object's own length
        from it. A ticket carrying an `encryption` object expects the encrypted object at its URL,
        not the file, so by default such a ticket is refused rather than handed back: PUTting the
        plaintext against it is rejected by the server at completion anyway, with an error about
        declared sizes that says nothing about the real cause. Pass `allow_encrypted=True` only if
        you intend to produce the stored object yourself with
        :mod:`cloud_driver_client.crypto`; :meth:`upload_via_presigned_url` does that for you.

        Raises :class:`UnsupportedEncryptionError` on an encrypted ticket without the opt-in.
        """
        resp = self._c._request(
            "POST",
            "/files/upload-url",
            json={"fileName": file_name, "sizeBytes": size_bytes, "folderId": folder_id},
        )
        ticket = BeginUploadUrl.model_validate(resp.json())
        if ticket.encryption is not None and not allow_encrypted:
            raise UnsupportedEncryptionError(
                "this deployment stores content client-side encrypted - use "
                "files.upload_via_presigned_url, the server-mediated files.upload, or pass "
                "allow_encrypted=True to handle the ciphertext yourself with "
                "cloud_driver_client.crypto"
            )
        return ticket

    def complete_upload(
        self, file_id: str, file_name: str, checksum_sha256: str, *, folder_id: str | None = None
    ) -> StoredFileSummary:
        resp = self._c._request(
            "POST",
            f"/files/{file_id}/complete-upload",
            json={"fileName": file_name, "checksumSha256": checksum_sha256, "folderId": folder_id},
        )
        return StoredFileSummary.model_validate(resp.json())

    def upload_via_presigned_url(
        self,
        local_path: str | os.PathLike[str],
        *,
        folder_id: str | None = None,
        file_name: str | None = None,
        on_progress: Callable[[int], None] | None = None,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> StoredFileSummary:
        """Uploads `local_path` straight to the object store, bypassing the cloud-driver server for
        the data path: begin ticket, PUT, complete.

        Encrypts client-side when the ticket carries encryption material, producing exactly the
        stored object the server expects; a ticket with `encryption` of `None` means the deployment
        stores this object plaintext and the file is PUT as it is. Memory stays O(`chunk_size`),
        though an encrypted upload stages the ciphertext in a temp file first.

        `on_progress` receives the cumulative number of bytes PUT so far - on an encrypted upload
        that is ciphertext bytes, a few dozen per MiB more than the plaintext.

        Raises :class:`ServiceUnavailableError` (503) when this deployment has no presigned
        transfer configured; fall back to :meth:`upload` on exactly that.
        """
        local_path = Path(local_path)
        name = file_name or local_path.name
        size_bytes = local_path.stat().st_size
        checksum = _sha256_hex(local_path)

        ticket = self.begin_upload_url(name, size_bytes, folder_id=folder_id, allow_encrypted=True)
        if ticket.encryption is None:
            # A legacy plaintext object: the deployment stores this one unencrypted, and that path
            # must keep working exactly as it did.
            with local_path.open("rb") as body:
                self._put_presigned_object(
                    ticket.upload_url, ticket.required_headers, body, size_bytes, on_progress, chunk_size
                )
        else:
            encrypted_path, encrypted_length = self._encrypt_for_presigned_upload(local_path, ticket.encryption)
            try:
                with encrypted_path.open("rb") as body:
                    self._put_presigned_object(
                        ticket.upload_url, ticket.required_headers, body, encrypted_length, on_progress, chunk_size
                    )
            finally:
                encrypted_path.unlink(missing_ok=True)
        # The digest is over the plaintext either way - it is the file's identity, not the object's.
        return self.complete_upload(ticket.file_id, name, checksum, folder_id=folder_id)

    def begin_download_url(self, file_id: str, *, allow_encrypted: bool = False) -> BeginDownloadUrl:
        """Issue a raw presigned download ticket.

        A ticket carrying an `encryption` object names a stored object whose bytes are ciphertext -
        fetched and written to disk as though they were the file, with nothing anywhere reporting a
        problem. So by default such a ticket is refused rather than handed back. Pass
        `allow_encrypted=True` only if you intend to decrypt it yourself with
        :mod:`cloud_driver_client.crypto`; :meth:`download_via_presigned_url` does that for you.

        Raises :class:`UnsupportedEncryptionError` on an encrypted ticket without the opt-in.
        """
        ticket = BeginDownloadUrl.model_validate(self._c._request("GET", f"/files/{file_id}/download-url").json())
        if ticket.encryption is not None and not allow_encrypted:
            raise UnsupportedEncryptionError(
                "this file is stored client-side encrypted - use files.download_via_presigned_url, "
                "the server-mediated files.download_to_path, or pass allow_encrypted=True to "
                "decrypt it yourself with cloud_driver_client.crypto"
            )
        return ticket

    def download_via_presigned_url(
        self,
        file_id: str,
        destination: str | os.PathLike[str],
        *,
        on_progress: Callable[[int], None] | None = None,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> Path:
        """Downloads `file_id` straight from the object store, bypassing the cloud-driver server for
        the data path, and decrypts it when the ticket carries encryption material.

        A ticket with `encryption` of `None` names a legacy plaintext object, streamed to
        `destination` unchanged. Memory stays O(`chunk_size`), though an encrypted download stages
        the fetched ciphertext in a temp file first.

        Raises :class:`ServiceUnavailableError` (503) when this deployment has no presigned
        transfer configured; fall back to :meth:`download_to_path` on exactly that, and
        :class:`ContentIntegrityError` when the stored object fails verification.
        """
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)

        ticket = self.begin_download_url(file_id, allow_encrypted=True)
        if ticket.encryption is None:
            # A legacy plaintext object: what the object store serves is already the file.
            with destination.open("wb") as out:
                self._fetch_presigned_object(ticket.download_url, out.write, on_progress, chunk_size)
            return destination

        fd, raw_name = tempfile.mkstemp(prefix="cloud-driver-download-", suffix=".enc")
        ciphertext_path = Path(raw_name)
        try:
            with os.fdopen(fd, "wb") as staged:
                self._fetch_presigned_object(ticket.download_url, staged.write, on_progress, chunk_size)
            try:
                with ciphertext_path.open("rb") as stored, destination.open("wb") as out:
                    crypto.decrypt_object(
                        stored,
                        out,
                        key_material=base64.b64decode(ticket.encryption.content_key_base64),
                        associated_data_prefix=ticket.encryption.associated_data_prefix,
                        header_length_bytes=ticket.encryption.header_length_bytes,
                    )
            except BaseException:
                # A failed or partial decrypt must never leave unverified bytes behind where the
                # caller would take them for the file.
                destination.unlink(missing_ok=True)
                raise
        finally:
            ciphertext_path.unlink(missing_ok=True)
        return destination

    # -- resumable multipart upload sessions ---------------------------------------------------

    def begin_upload_session(
        self, file_name: str, size_bytes: int, checksum_sha256: str, *, folder_id: str | None = None
    ) -> BeginUploadSessionResult:
        """POST /files/upload-session - begins a crash-resumable multipart upload.

        `size_bytes` and `checksum_sha256` are the PLAINTEXT length and digest. A dedup precheck
        hit comes back as `already_stored` with no session at all: nothing needs uploading.

        Raises :class:`ServiceUnavailableError` (503) when this deployment offers no resumable
        sessions; fall back to :meth:`upload_via_presigned_url` or :meth:`upload` on exactly that.
        """
        body = self._c._request(
            "POST",
            "/files/upload-session",
            json={
                "fileName": file_name,
                "sizeBytes": size_bytes,
                "folderId": folder_id,
                "checksumSha256": checksum_sha256,
            },
        ).json()
        if "alreadyStored" in body:
            return BeginUploadSessionResult(
                already_stored=StoredFileSummary.model_validate(body["alreadyStored"]), session=None
            )
        return BeginUploadSessionResult(already_stored=None, session=UploadSession.model_validate(body))

    def get_upload_session(self, session_file_id: str) -> UploadSession:
        """GET /files/upload-session/{id} - the session's durable progress: geometry, the parts the
        object store already holds, and the recovered encryption parameters of an encrypted
        session, so resuming needs nothing but the session id."""
        return UploadSession.model_validate(
            self._c._request("GET", f"/files/upload-session/{session_file_id}").json()
        )

    def presign_upload_session_part(self, session_file_id: str, part_number: int) -> UploadSessionPartUrl:
        """POST /files/upload-session/{id}/parts/{n}/url - presigns one 1-based part's upload."""
        return UploadSessionPartUrl.model_validate(
            self._c._request("POST", f"/files/upload-session/{session_file_id}/parts/{part_number}/url").json()
        )

    def complete_upload_session(
        self, session_file_id: str, file_name: str, checksum_sha256: str, *, folder_id: str | None = None
    ) -> StoredFileSummary:
        """POST /files/upload-session/{id}/complete - assembles the uploaded parts and registers
        the file, the object's exact length verified server-side."""
        resp = self._c._request(
            "POST",
            f"/files/upload-session/{session_file_id}/complete",
            json={"fileName": file_name, "checksumSha256": checksum_sha256, "folderId": folder_id},
        )
        return StoredFileSummary.model_validate(resp.json())

    def abort_upload_session(self, session_file_id: str) -> None:
        """DELETE /files/upload-session/{id} - discards every uploaded part. The object store bills
        for them until told this, so abort a session you will not finish."""
        self._c._request("DELETE", f"/files/upload-session/{session_file_id}")

    def upload_via_session(
        self,
        local_path: str | os.PathLike[str],
        *,
        folder_id: str | None = None,
        file_name: str | None = None,
        on_progress: Callable[[int], None] | None = None,
    ) -> StoredFileSummary:
        """Uploads `local_path` through a resumable multipart session, end to end.

        A dedup precheck hit returns the existing file immediately, with zero bytes uploaded.
        Otherwise the session id is available from the returned file only after completion, so a
        caller that wants to survive its own crash should drive the flow itself:
        :meth:`begin_upload_session`, persist ``result.session.file_id``, then
        :meth:`resume_upload_session`.

        `on_progress` receives the cumulative number of object bytes uploaded so far, counting the
        parts the store already holds as done.

        Raises :class:`ServiceUnavailableError` (503) when this deployment offers no sessions.
        """
        local_path = Path(local_path)
        name = file_name or local_path.name
        checksum = _sha256_hex(local_path)
        begin = self.begin_upload_session(
            name, local_path.stat().st_size, checksum, folder_id=folder_id
        )
        if begin.already_stored is not None:
            return begin.already_stored
        assert begin.session is not None
        return self._run_upload_session(begin.session, local_path, name, checksum, folder_id, on_progress)

    def resume_upload_session(
        self,
        session_file_id: str,
        local_path: str | os.PathLike[str],
        *,
        file_name: str | None = None,
        folder_id: str | None = None,
        on_progress: Callable[[int], None] | None = None,
    ) -> StoredFileSummary:
        """Resumes a crashed or interrupted session: re-reads its status, uploads only the parts
        the object store does not already hold, and completes.

        `local_path` must still be the content the session was begun for, byte for byte - see
        :meth:`_run_upload_session` for why, and what happens when it is not.
        """
        local_path = Path(local_path)
        session = self.get_upload_session(session_file_id)
        return self._run_upload_session(
            session,
            local_path,
            file_name or local_path.name,
            _sha256_hex(local_path),
            folder_id,
            on_progress,
        )

    def _run_upload_session(
        self,
        session: UploadSession,
        local_path: Path,
        file_name: str,
        checksum_sha256: str,
        folder_id: str | None,
        on_progress: Callable[[int], None] | None,
    ) -> StoredFileSummary:
        """The shared upload loop: materialise the object stream, PUT every missing part, complete."""
        # The session's content key is fixed for its lifetime and the object's nonce base is
        # derived from that key, so every pass over this session encrypts under one key/nonce
        # pair. Re-encrypting different bytes under it would splice two encryptions into one
        # object and reuse that pair across two plaintexts, so a session carries only the exact
        # content it was begun for; anything else needs a new session.
        if session.checksum_sha256 is not None and session.checksum_sha256.lower() != checksum_sha256.lower():
            raise ValueError(
                f"the local file is not the content upload session '{session.file_id}' was begun "
                f"for - abort the session (files.abort_upload_session) and start a new one"
            )

        encrypted_path: Path | None = None
        if session.encryption is not None:
            encrypted_path, _ = self._encrypt_for_presigned_upload(local_path, session.encryption)
            object_path = encrypted_path
        else:
            # A legacy plaintext session: the object the parts assemble into is the file itself.
            object_path = local_path

        try:
            object_size = object_path.stat().st_size
            if object_size != session.total_object_bytes:
                # The structural gate behind the digest comparison above, and the only one against
                # a server that reports no digest at all.
                raise ValueError(
                    f"local object stream is {object_size} bytes but the session expects "
                    f"{session.total_object_bytes} - was the file modified since the session began?"
                )
            already_uploaded = set(session.uploaded_part_numbers)
            transferred = sum(
                min(session.part_size_bytes, object_size - (part - 1) * session.part_size_bytes)
                for part in already_uploaded
            )
            with object_path.open("rb") as object_stream:
                for part_number in range(1, session.part_count + 1):
                    if part_number in already_uploaded:
                        continue
                    offset = (part_number - 1) * session.part_size_bytes
                    length = min(session.part_size_bytes, object_size - offset)
                    object_stream.seek(offset)
                    part_bytes = object_stream.read(length)
                    part = self.presign_upload_session_part(session.file_id, part_number)
                    # No Authorization header: the presigned signature covers the header set, and
                    # this account's bearer token must never reach the object store's host.
                    resp = self._c._client.request(
                        "PUT", part.url, content=part_bytes, headers=dict(part.required_headers),
                        timeout=TRANSFER_TIMEOUT,
                    )
                    raise_for_status(resp)
                    transferred += len(part_bytes)
                    if on_progress is not None:
                        on_progress(transferred)
            return self.complete_upload_session(
                session.file_id, file_name, checksum_sha256, folder_id=folder_id
            )
        finally:
            if encrypted_path is not None:
                encrypted_path.unlink(missing_ok=True)

    # -- presigned transfer primitives ---------------------------------------------------------
    # Both deliberately bypass CloudDriverClient._request/_stream_download: those attach the
    # account's bearer token and the 401-refresh retry, and a presigned URL belongs to the object
    # store, a third-party host that must never see this account's token. An unexpected header can
    # also break the request's signature. self._c._client merges nothing into an absolute URL and
    # carries no default Authorization header.

    def _put_presigned_object(
        self,
        url: str,
        required_headers: dict[str, str],
        body: BinaryIO,
        content_length: int,
        on_progress: Callable[[int], None] | None,
        chunk_size: int,
    ) -> None:
        """PUTs `body` to a presigned URL, replaying the ticket's required headers exactly."""
        headers = dict(required_headers)
        # The object store rejects a chunked PUT; an explicit Content-Length makes httpx send the
        # streamed body with a fixed length instead of Transfer-Encoding: chunked.
        headers["Content-Length"] = str(content_length)
        resp = self._c._client.request(
            "PUT",
            url,
            content=_iter_file_chunks(body, chunk_size, on_progress),
            headers=headers,
            timeout=TRANSFER_TIMEOUT,
        )
        raise_for_status(resp)

    def _fetch_presigned_object(
        self,
        url: str,
        on_chunk: Callable[[bytes], None],
        on_progress: Callable[[int], None] | None,
        chunk_size: int,
    ) -> None:
        """Streams a presigned URL's object, handing each chunk to `on_chunk`."""
        with self._c._client.stream("GET", url, timeout=TRANSFER_TIMEOUT) as resp:
            if resp.status_code >= 400:
                # A streamed response's body must be read before the block exits, or
                # raise_for_status cannot extract the error message.
                resp.read()
                raise_for_status(resp)
            transferred = 0
            for chunk in resp.iter_bytes(chunk_size):
                transferred += len(chunk)
                on_chunk(chunk)
                if on_progress is not None:
                    on_progress(transferred)

    def _encrypt_for_presigned_upload(self, local_path: Path, encryption: UploadEncryption) -> tuple[Path, int]:
        """Encrypts `local_path` into a temp file as the exact stored object `encryption` describes.

        :return: the temp file's path and its length, which the caller must PUT and then delete
        :raises ContentIntegrityError: if the produced object is not exactly the length the ticket
            requires - what the server verifies at completion, caught here before any byte moves
        """
        fd, raw_name = tempfile.mkstemp(prefix="cloud-driver-upload-", suffix=".enc")
        encrypted_path = Path(raw_name)
        try:
            with local_path.open("rb") as plaintext, os.fdopen(fd, "wb") as sink:
                produced = crypto.encrypt_object(
                    plaintext,
                    sink,
                    key_material=base64.b64decode(encryption.content_key_base64),
                    header=base64.b64decode(encryption.header_base64),
                    associated_data_prefix=encryption.associated_data_prefix,
                    chunk_size_bytes=encryption.chunk_size_bytes,
                )
            if produced != encryption.object_length_bytes:
                raise ContentIntegrityError(
                    f"encrypted upload is {produced} bytes but the ticket requires exactly "
                    f"{encryption.object_length_bytes} - did the file change since the upload began?"
                )
        except BaseException:
            encrypted_path.unlink(missing_ok=True)
            raise
        return encrypted_path, produced


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

    def share(
        self,
        folder_id: str,
        grantee_email: str,
        *,
        permission_level: str = "VIEW",
        expires_at_epoch_millis: int | None = None,
    ) -> None:
        """POST /folders/{id}/share. See `_FilesResource.share`'s docstring for the two new
        keyword arguments () - note EDIT permission has no
        defined meaning on a folder grant server-side (VIEW-only access is honored either way)."""
        self._c._request(
            "POST",
            f"/folders/{folder_id}/share",
            json={
                "granteeEmail": grantee_email,
                "permissionLevel": permission_level,
                "expiresAtEpochMillis": expires_at_epoch_millis,
            },
        )

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

    def list_activity(
        self, folder_id: str, *, limit: int | None = None, cursor: str | None = None
    ) -> Page[ActivityEntry]:
        """GET /folders/{id}/activity - always paginated, newest first."""
        params: dict[str, Any] = {}
        if limit is not None:
            params["limit"] = limit
        if cursor is not None:
            params["cursor"] = cursor
        data = self._c._request("GET", f"/folders/{folder_id}/activity", params=params).json()
        return Page[ActivityEntry].model_validate(data)

    def iter_activity(self, folder_id: str, *, page_size: int = 50) -> Iterator[ActivityEntry]:
        cursor: str | None = None
        while True:
            page = self.list_activity(folder_id, limit=page_size, cursor=cursor)
            yield from page.items
            if page.next_cursor is None:
                return
            cursor = page.next_cursor


class _TrashResource(_Resource):
    def empty(self) -> None:
        self._c._request("POST", "/trash/empty")


class _ActivityResource(_Resource):
    """GET /activity - the caller's global activity feed across every file/folder they own or have
    been shared, newest first (). For a single file/folder's own
    history, use `.files.list_activity`/`.folders.list_activity` instead."""

    def list(self, *, limit: int | None = None, cursor: str | None = None) -> Page[ActivityEntry]:
        params: dict[str, Any] = {}
        if limit is not None:
            params["limit"] = limit
        if cursor is not None:
            params["cursor"] = cursor
        data = self._c._request("GET", "/activity", params=params).json()
        return Page[ActivityEntry].model_validate(data)

    def iter_all(self, *, page_size: int = 50) -> Iterator[ActivityEntry]:
        cursor: str | None = None
        while True:
            page = self.list(limit=page_size, cursor=cursor)
            yield from page.items
            if page.next_cursor is None:
                return
            cursor = page.next_cursor


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

"""Pydantic mirrors of the server's JSON response shapes.

Hand-mirrored from cloud-driver-plugin's DefaultRestFactory / cloud-driver-platforms-rest's
Dtos.java, the same "kept in sync by hand, not shared code" convention every other client in this
repo already uses (see CLAUDE.md's "cloud-driver-platforms-rest" section) - there is no OpenAPI
spec to generate these from. Every model uses `populate_by_name=True` plus an explicit alias per
field, since the server's own field-name casing is inconsistent across DTOs (e.g. StoredFileSummary
uses "createdAtEpochMilli" while Folder uses "createdAtEpochMillis") and can't be derived from one
generic camelCase rule.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


class _Model(BaseModel):
    model_config = ConfigDict(populate_by_name=True)


class AuthTokens(_Model):
    access_token: str = Field(alias="token")
    refresh_token: str = Field(alias="refreshToken")


class MessageResponse(_Model):
    message: str


class MeResponse(_Model):
    auth_user_id: str = Field(alias="authUserId")
    email_address: str = Field(alias="emailAddress")
    is_admin: bool = Field(alias="isAdmin")


class CloudUser(_Model):
    auth_user_id: str = Field(alias="authUserId")
    time_stamp: int = Field(alias="timeStamp")
    max_bytes_to_upload: int = Field(alias="maxBytesToUpload")
    current_uploaded_bytes: int = Field(alias="currentUploadedBytes")
    theme_mode: str | None = Field(default=None, alias="themeMode")


class StoredFileSummary(_Model):
    file_id: str = Field(alias="fileId")
    file_name: str = Field(alias="fileName")
    content_type: str = Field(alias="contentType")
    size_bytes: int = Field(alias="sizeBytes")
    created_at_epoch_milli: int = Field(alias="createdAtEpochMilli")
    updated_at_epoch_milli: int = Field(alias="updatedAtEpochMilli")
    folder_id: str | None = Field(default=None, alias="folderId")
    # The server always resolves a real value ("CLEAN"/"PENDING"/"FLAGGED", see CLAUDE.md's
    # "Content scanning" section) - the default here only guards against parsing a response from
    # an older server build that predates this field entirely.
    scan_status: str = Field(default="CLEAN", alias="scanStatus")


class StoredFile(_Model):
    """GET /files/{id}'s full, content-carrying shape. Deliberately permissive (`extra="allow"`) -
    an S3-backed/direct-transfer file's real fields differ (see CLAUDE.md's "S3-backed StoredFile
    content"), and this model only guarantees the fields every caller actually needs."""

    model_config = ConfigDict(populate_by_name=True, extra="allow")

    file_id: str = Field(alias="fileId")
    file_name: str = Field(alias="fileName")
    content_type: str = Field(alias="contentType")
    content_base64: str | None = Field(default=None, alias="contentBase64")
    folder_id: str | None = Field(default=None, alias="folderId")


class Folder(_Model):
    folder_id: str = Field(alias="folderId")
    owner_id: str = Field(alias="ownerId")
    name: str
    parent_folder_id: str | None = Field(default=None, alias="parentFolderId")
    created_at_epoch_millis: int = Field(alias="createdAtEpochMillis")
    modified_at_epoch_millis: int = Field(alias="modifiedAtEpochMillis")
    deleted_at_epoch_millis: int | None = Field(default=None, alias="deletedAtEpochMillis")
    color: str | None = None


class Page(_Model, Generic[T]):
    items: list[T]
    next_cursor: str | None = Field(default=None, alias="nextCursor")


class TrashedFileSummary(_Model):
    file: StoredFileSummary
    purge_at_epoch_millis: int = Field(alias="purgeAtEpochMillis")


class TrashedFolderSummary(_Model):
    folder: Folder
    purge_at_epoch_millis: int = Field(alias="purgeAtEpochMillis")


class SharedFileSummary(_Model):
    file: StoredFileSummary
    owner_email: str = Field(alias="ownerEmail")


class SharedFolderSummary(_Model):
    folder: Folder
    owner_email: str = Field(alias="ownerEmail")


class SharedFolderContents(_Model):
    files: list[StoredFileSummary]
    subfolders: list[Folder]


class AuthUser(_Model):
    """GET /admin/authUsers entries. `password_hash` is intentionally not modeled - it's a one-way
    Argon2id hash the admin route happens to also return, never a secret worth surfacing here."""

    id: str
    email_address: str = Field(alias="emailAddress")
    is_admin: bool = Field(alias="isAdmin")


class AuditLogEntry(_Model):
    timestamp_epoch_millis: int = Field(alias="timestampEpochMillis")
    action: str
    actor_email: str | None = Field(default=None, alias="actorEmail")
    target_id: str | None = Field(default=None, alias="targetId")


class MetricsSnapshot(_Model):
    uploads_succeeded: int = Field(alias="uploadsSucceeded")
    uploads_failed: int = Field(alias="uploadsFailed")
    uploads_queued: int = Field(alias="uploadsQueued")
    upload_quota_rejections: int = Field(alias="uploadQuotaRejections")
    pending_upload_queue_depth: int = Field(alias="pendingUploadQueueDepth")
    extensions_by_status: dict[str, int] = Field(alias="extensionsByStatus")


class EmailExists(_Model):
    exists: bool


class SharedByMeCount(_Model):
    count: int


class UploadEncryption(_Model):
    """The per-file key material a presigned upload must encrypt its object with.

    Present whenever the deployment stores content client-side encrypted, which is every
    S3-backed deployment. ``None`` means the object is a legacy plaintext one.
    """

    content_key_base64: str = Field(alias="contentKeyBase64")
    header_base64: str = Field(alias="headerBase64")
    associated_data_prefix: str = Field(alias="associatedDataPrefix")
    chunk_size_bytes: int = Field(alias="chunkSizeBytes")
    object_length_bytes: int = Field(alias="objectLengthBytes")


class DownloadEncryption(_Model):
    """The per-file key material a presigned download must decrypt its object with.

    ``None`` means the stored object is legacy plaintext and should be used as fetched.
    """

    content_key_base64: str = Field(alias="contentKeyBase64")
    associated_data_prefix: str = Field(alias="associatedDataPrefix")
    header_length_bytes: int = Field(alias="headerLengthBytes")


class BeginUploadUrl(_Model):
    file_id: str = Field(alias="fileId")
    upload_url: str = Field(alias="uploadUrl")
    required_headers: dict[str, str] = Field(alias="requiredHeaders")
    expires_at_epoch_millis: int = Field(alias="expiresAtEpochMillis")
    encryption: UploadEncryption | None = None


class BeginDownloadUrl(_Model):
    download_url: str = Field(alias="downloadUrl")
    expires_at_epoch_millis: int = Field(alias="expiresAtEpochMillis")
    encryption: DownloadEncryption | None = None


class UploadSession(_Model):
    """A resumable multipart upload session's geometry and progress.

    The body of POST /files/upload-session (with `uploaded_part_numbers` empty) and of
    GET /files/upload-session/{id} (with the object store's confirmed parts). The client cuts its
    object stream - encrypted when `encryption` is set, the plaintext file itself when it is
    ``None`` (a legacy plaintext session) - into `part_size_bytes` ranges and uploads exactly the
    part numbers missing from `uploaded_part_numbers`. `checksum_sha256` is the plaintext digest
    the session is bound to; ``None`` against an older server that reports none.
    """

    file_id: str = Field(alias="fileId")
    part_size_bytes: int = Field(alias="partSizeBytes")
    part_count: int = Field(alias="partCount")
    total_object_bytes: int = Field(alias="totalObjectBytes")
    uploaded_part_numbers: list[int] = Field(default_factory=list, alias="uploadedPartNumbers")
    encryption: UploadEncryption | None = None
    checksum_sha256: str | None = Field(default=None, alias="checksumSha256")


class UploadSessionPartUrl(_Model):
    """One presigned part upload - the body of POST /files/upload-session/{id}/parts/{n}/url."""

    part_number: int = Field(alias="partNumber")
    url: str
    required_headers: dict[str, str] = Field(default_factory=dict, alias="requiredHeaders")
    expires_at_epoch_milli: int = Field(alias="expiresAtEpochMilli")


class LiveUpdateEvent(_Model):
    """One GET /ws/updates push payload - see CLAUDE.md's "Live push via WebSocket"."""

    table: str
    operation: str
    id: str


class FileVersionSummary(_Model):
    """One entry in GET /files/{id}/versions - (versioning)."""

    version_number: int = Field(alias="versionNumber")
    captured_at_epoch_millis: int = Field(alias="capturedAtEpochMillis")
    size_bytes: int = Field(alias="sizeBytes")


class ActivityEntry(_Model):
    """One raw AuditEvent entry, as returned by GET /files/{id}/activity, GET /folders/{id}/activity,
    and GET /activity - . Distinct from AuditLogEntry (which is
    the /admin/audit-log shape, with an already-resolved actor_email instead of a raw
    actor_auth_user_id)."""

    id: str
    actor_auth_user_id: str | None = Field(default=None, alias="actorAuthUserId")
    action: str
    target_id: str | None = Field(default=None, alias="targetId")
    timestamp_epoch_millis: int = Field(alias="timestampEpochMillis")
    metadata: str | None = None


class SearchResult(_Model):
    """One match returned by GET /search - (search/indexing)."""

    stored_file_id: str = Field(alias="storedFileId")
    file_name: str = Field(alias="fileName")
    folder_id: str | None = Field(default=None, alias="folderId")


class SemanticSearchResult(_Model):
    """One match returned by GET /search/semantic.

    SearchResult plus a `score` - the cosine similarity between the query and the file, higher
    being closer. A separate model rather than an optional field on SearchResult because the two
    answer different questions: a keyword search either matched or it did not, whereas every
    semantic result matched to *some* degree and the score is what makes the list interpretable.
    """

    stored_file_id: str = Field(alias="storedFileId")
    file_name: str = Field(alias="fileName")
    folder_id: str | None = Field(default=None, alias="folderId")
    score: float


class DuplicateFileEntry(_Model):
    """One member of a DuplicateFileGroup."""

    stored_file_id: str = Field(alias="storedFileId")
    file_name: str = Field(alias="fileName")
    folder_id: str | None = Field(default=None, alias="folderId")


class DuplicateFileGroup(_Model):
    """A set of files the server considers near-identical in meaning, from GET /files/duplicates.

    **Not the same as byte-identical.** The server already deduplicates identical content exactly
    and invisibly; this is a similarity judgement over *meaning*, which is what catches the cases
    an exact hash cannot - the same invoice scanned twice, a document re-exported at another
    quality. It is a suggestion for a human, never grounds for deleting anything automatically.

    `similarity` is the group's weakest pairwise score, so comparing it against your own threshold
    judges the whole group rather than its best pair.
    """

    files: list[DuplicateFileEntry]
    similarity: float


class TagSuggestion(_Model):
    """One suggested label for a file, from GET /files/{id}/tags.

    Zero-shot: the server scores the file's embedding against a fixed label vocabulary. There is no
    training and no learning from user behaviour, so `confidence` is a *relative* similarity and
    not a calibrated probability - do not render it to a user as a percentage of correctness.
    """

    tag: str
    confidence: float


class PublicFileLinkSummary(_Model):
    """An unauthenticated public share link on a file - . `token`
    is the whole of what's needed to resolve the file's content through the anonymous
    GET /public/files/{token} route (see CloudDriverClient.download_public_file_to_path/_bytes)."""

    token: str
    created_at_epoch_millis: int = Field(alias="createdAtEpochMillis")
    expires_at_epoch_millis: int | None = Field(default=None, alias="expiresAtEpochMillis")


@dataclass(frozen=True)
class ConditionalDownload:
    """Result of a conditional download. ``not_modified`` means the server answered 304: the
    destination was never opened, so an existing local copy is untouched and still current.
    ``path`` is set iff ``not_modified`` is False. ``entity_tag`` is the server's ETag
    verbatim - pass it back on the next download of the same file; ``None`` for a file the
    server records no checksum for, which only ever costs a full transfer."""

    not_modified: bool
    path: Path | None
    entity_tag: str | None


@dataclass(frozen=True)
class BeginUploadSessionResult:
    """The outcome of beginning an upload session - exactly one field is set.

    ``already_stored`` means the server's dedup precheck matched content this account already
    stores, so nothing has to be uploaded at all; ``session`` is the session to upload through
    otherwise.
    """

    already_stored: StoredFileSummary | None
    session: UploadSession | None

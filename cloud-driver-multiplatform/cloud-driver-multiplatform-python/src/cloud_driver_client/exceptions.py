"""Exceptions raised by :mod:`cloud_driver_client`.

Mapped by HTTP status code, mirroring how DefaultRestFactory's own
notFoundOrPropagate/folderFailureOrPropagate/registrationFailureOrPropagate helpers translate a
server-side exception into a status code - see CLAUDE.md's "RestFactory" section for the full
mapping this mirrors.
"""

from __future__ import annotations

from typing import Any


class ApiException(Exception):
    """Base class for every error response the server returns."""

    def __init__(self, status_code: int, message: str, body: Any = None) -> None:
        super().__init__(f"HTTP {status_code}: {message}")
        self.status_code = status_code
        self.message = message
        self.body = body


class BadRequestError(ApiException):
    """400 - e.g. an invalid/expired verification code, or a malformed password."""


class UnauthorizedError(ApiException):
    """401 - wrong credentials, or a missing/invalid/expired bearer token."""


class ForbiddenError(ApiException):
    """403 - a non-admin account calling an admin-gated route."""


class NotFoundError(ApiException):
    """404 - unowned/nonexistent file, folder, or account (existence is deliberately not confirmed)."""


class ConflictError(ApiException):
    """409 - e.g. an email already registered, a non-empty folder, or a restore on a non-trashed item."""


class SyncConflictError(ConflictError):
    """409 raised specifically by CloudDriverClient.files.replace_content when
    `expected_updated_at_epoch_millis` was supplied and didn't match the file's current version -
    some other write already changed the file first. The canonical file is left completely
    untouched; `conflicted_copy` is the brand-new file your own content was saved into instead (see
    `architecture/MICRO.md` section 10). Not raised by any other route - every other 409 in this
    SDK is a plain `ConflictError`, never this subclass."""

    def __init__(self, status_code: int, message: str, body: Any, conflicted_copy: Any) -> None:
        super().__init__(status_code, message, body)
        self.conflicted_copy = conflicted_copy


class PayloadTooLargeError(ApiException):
    """413 - the per-account upload quota, or the server's own max request size, was exceeded."""


class TooManyRequestsError(ApiException):
    """429 - the auth-route rate limit (10 requests / 5 minutes per address, by default) was hit."""


class ServiceUnavailableError(ApiException):
    """503 - a feature (presigned transfer, admin metrics) isn't configured on this deployment."""


_STATUS_TO_EXCEPTION: dict[int, type[ApiException]] = {
    400: BadRequestError,
    401: UnauthorizedError,
    403: ForbiddenError,
    404: NotFoundError,
    409: ConflictError,
    413: PayloadTooLargeError,
    429: TooManyRequestsError,
    503: ServiceUnavailableError,
}


def exception_for_status(status_code: int, message: str, body: Any = None) -> ApiException:
    cls = _STATUS_TO_EXCEPTION.get(status_code, ApiException)
    return cls(status_code, message, body)

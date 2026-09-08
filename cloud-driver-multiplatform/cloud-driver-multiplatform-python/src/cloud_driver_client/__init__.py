"""Full-coverage Python SDK for the cloud-driver REST/WebSocket API.

    from cloud_driver_client import CloudDriverClient

    client = CloudDriverClient("https://api.cloud-driver.de")
    client.auth.login("user@example.com", "hunter2!A")
    for f in client.files.iter_all():
        print(f.file_name, f.size_bytes)

See this package's README.md for the full concept/architecture writeup.
"""

from .async_client import AsyncCloudDriverClient
from .client import CloudDriverClient
from .exceptions import (
    ApiException,
    BadRequestError,
    ConflictError,
    ForbiddenError,
    NotFoundError,
    PayloadTooLargeError,
    ServiceUnavailableError,
    SyncConflictError,
    TooManyRequestsError,
    UnauthorizedError,
)
from .live_updates import LiveUpdateClient
from .token_store import FileTokenStore, InMemoryTokenStore, KeyringTokenStore, TokenStore

__all__ = [
    "CloudDriverClient",
    "AsyncCloudDriverClient",
    "LiveUpdateClient",
    "TokenStore",
    "InMemoryTokenStore",
    "FileTokenStore",
    "KeyringTokenStore",
    "ApiException",
    "BadRequestError",
    "UnauthorizedError",
    "ForbiddenError",
    "NotFoundError",
    "ConflictError",
    "SyncConflictError",
    "PayloadTooLargeError",
    "TooManyRequestsError",
    "ServiceUnavailableError",
]

__version__ = "1.0.6"

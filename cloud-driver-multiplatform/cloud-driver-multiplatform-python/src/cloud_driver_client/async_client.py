"""AsyncCloudDriverClient - an async facade over CloudDriverClient.

Every one of CloudDriverClient's blocking calls is offloaded to the default thread-pool executor
via `asyncio.to_thread` rather than reimplemented on top of `httpx.AsyncClient` - `httpx.Client` is
documented thread-safe for concurrent use, and this avoids maintaining two independent (and
easy-to-drift) copies of ~40 endpoint methods. `on_progress` callbacks passed to a `.files.upload*`/
`.files.download*` call still fire on the worker thread, not the event loop - keep them cheap and
non-blocking, the same expectation any thread-offloaded callback carries.
"""

from __future__ import annotations

import asyncio
import os
from collections.abc import Callable
from pathlib import Path
from typing import TYPE_CHECKING, Any

from .client import CloudDriverClient
from .token_store import TokenStore

if TYPE_CHECKING:
    from .live_updates import LiveUpdateClient
    from .models import SearchResult


class _AsyncResourceProxy:
    """Wraps one of CloudDriverClient's resource namespaces (`.files`, `.folders`, ...), exposing
    every method as an `async def` that runs the real (blocking) call via `asyncio.to_thread`."""

    def __init__(self, sync_resource: Any) -> None:
        self._sync_resource = sync_resource

    def __getattr__(self, name: str) -> Any:
        attr = getattr(self._sync_resource, name)
        if not callable(attr):
            return attr

        async def _call(*args: Any, **kwargs: Any) -> Any:
            return await asyncio.to_thread(attr, *args, **kwargs)

        return _call


class AsyncCloudDriverClient:
    """The async counterpart to CloudDriverClient - same resource namespaces (`.auth`,
    `.cloud_users`, `.files`, `.folders`, `.trash`, `.admin`, `.activity`), every method
    `await`-able."""

    def __init__(self, base_url: str, *, token_store: TokenStore | None = None, timeout: float = 30.0) -> None:
        self._sync = CloudDriverClient(base_url, token_store=token_store, timeout=timeout)
        self.auth = _AsyncResourceProxy(self._sync.auth)
        self.cloud_users = _AsyncResourceProxy(self._sync.cloud_users)
        self.files = _AsyncResourceProxy(self._sync.files)
        self.folders = _AsyncResourceProxy(self._sync.folders)
        self.trash = _AsyncResourceProxy(self._sync.trash)
        self.admin = _AsyncResourceProxy(self._sync.admin)
        self.activity = _AsyncResourceProxy(self._sync.activity)

    @property
    def base_url(self) -> str:
        return self._sync.base_url

    @property
    def access_token(self) -> str | None:
        return self._sync.access_token

    def restore_session(self) -> bool:
        return self._sync.restore_session()

    def live_updates(self, *, reconnect_delay: float = 5.0) -> "LiveUpdateClient":
        return self._sync.live_updates(reconnect_delay=reconnect_delay)

    async def search(self, query: str, *, limit: int = 25) -> "list[SearchResult]":
        return await asyncio.to_thread(self._sync.search, query, limit=limit)

    async def download_public_file_to_path(
        self,
        token: str,
        destination: str | os.PathLike[str],
        *,
        on_progress: Callable[[int], None] | None = None,
        chunk_size: int = 1024 * 1024,
    ) -> Path:
        return await asyncio.to_thread(
            self._sync.download_public_file_to_path,
            token,
            destination,
            on_progress=on_progress,
            chunk_size=chunk_size,
        )

    async def download_public_file_bytes(self, token: str, *, chunk_size: int = 1024 * 1024) -> bytes:
        return await asyncio.to_thread(self._sync.download_public_file_bytes, token, chunk_size=chunk_size)

    async def close(self) -> None:
        await asyncio.to_thread(self._sync.close)

    async def __aenter__(self) -> "AsyncCloudDriverClient":
        return self

    async def __aexit__(self, *exc_info: object) -> None:
        await self.close()

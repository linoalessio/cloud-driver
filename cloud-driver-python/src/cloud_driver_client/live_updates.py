"""GET /ws/updates live-push client - see CLAUDE.md's "Live push via WebSocket" section.

Requires the optional `websockets` package (`pip install cloud-driver-client[live]`, >=12 for its
synchronous `websockets.sync.client` API) - imported lazily in `start()` so constructing/using
every other part of this SDK never requires it to be installed.
"""

from __future__ import annotations

import json
import threading
from collections.abc import Callable

from ._http import to_ws_url
from .models import LiveUpdateEvent


class LiveUpdateClient:
    """A reconnecting GET /ws/updates client, callback-driven rather than asyncio-based -
    matches how a typical sync microservice (a worker, a cron job, a Flask app) wants to react to
    a push: "call my function when something changes", not "await an async iterator"."""

    def __init__(
        self,
        base_url: str,
        access_token_provider: Callable[[], str | None],
        *,
        reconnect_delay: float = 5.0,
    ) -> None:
        self._url = to_ws_url(base_url) + "/ws/updates"
        self._access_token_provider = access_token_provider
        self._reconnect_delay = reconnect_delay
        self._thread: threading.Thread | None = None
        self._stop_event = threading.Event()

    def start(
        self,
        on_event: Callable[[LiveUpdateEvent], None],
        *,
        on_error: Callable[[Exception], None] | None = None,
    ) -> None:
        """Connects in a background daemon thread and calls `on_event` for every push received.
        Reconnects automatically after `reconnect_delay` seconds on any drop - matching
        cloud-driver-platforms-rest's own LiveUpdateClient ("a fixed delay, not exponential
        backoff", per CLAUDE.md)."""
        if self._thread is not None:
            raise RuntimeError("LiveUpdateClient is already started")
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, args=(on_event, on_error), daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=self._reconnect_delay + 5)
            self._thread = None

    def _run(self, on_event: Callable[[LiveUpdateEvent], None], on_error: Callable[[Exception], None] | None) -> None:
        try:
            import websockets.sync.client as ws_client
            from websockets.exceptions import ConnectionClosed
        except ImportError as exc:  # pragma: no cover - exercised only without the extra installed
            raise ImportError(
                "LiveUpdateClient requires the 'live' extra: pip install cloud-driver-client[live]"
            ) from exc

        while not self._stop_event.is_set():
            token = self._access_token_provider()
            headers = {"Authorization": f"Bearer {token}"} if token else {}
            try:
                with ws_client.connect(self._url, additional_headers=headers, open_timeout=10) as connection:
                    while not self._stop_event.is_set():
                        try:
                            message = connection.recv(timeout=1)
                        except TimeoutError:
                            continue
                        event = LiveUpdateEvent.model_validate(json.loads(message))
                        on_event(event)
            except ConnectionClosed:
                pass
            except Exception as exc:  # noqa: BLE001 - a broken connection must never kill the reconnect loop
                if on_error is not None:
                    on_error(exc)
            if not self._stop_event.is_set():
                self._stop_event.wait(self._reconnect_delay)

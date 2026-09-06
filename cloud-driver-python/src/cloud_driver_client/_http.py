"""Shared, transport-agnostic HTTP helpers used by CloudDriverClient."""

from __future__ import annotations

import httpx

from .exceptions import exception_for_status


def extract_error_message(response: httpx.Response) -> str:
    try:
        body = response.json()
    except ValueError:
        return response.text or response.reason_phrase
    if isinstance(body, dict):
        for key in ("message", "error", "title"):
            value = body.get(key)
            if isinstance(value, str) and value:
                return value
    return response.text


def raise_for_status(response: httpx.Response) -> httpx.Response:
    if response.status_code >= 400:
        message = extract_error_message(response)
        body: object
        try:
            body = response.json()
        except ValueError:
            body = response.text
        raise exception_for_status(response.status_code, message, body)
    return response


def to_ws_url(base_url: str) -> str:
    if base_url.startswith("https://"):
        return "wss://" + base_url[len("https://") :]
    if base_url.startswith("http://"):
        return "ws://" + base_url[len("http://") :]
    return base_url

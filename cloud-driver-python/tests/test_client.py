"""Unit tests for CloudDriverClient, mocking the server via respx (no real network calls)."""

from __future__ import annotations

import httpx
import pytest
import respx

from cloud_driver_client import CloudDriverClient, NotFoundError, UnauthorizedError

BASE_URL = "https://api.example.test"


@pytest.fixture
def client() -> CloudDriverClient:
    with CloudDriverClient(BASE_URL) as c:
        yield c


@respx.mock
def test_login_stores_tokens(client: CloudDriverClient) -> None:
    route = respx.post(f"{BASE_URL}/auth/login").mock(
        return_value=httpx.Response(200, json={"token": "access-1", "refreshToken": "refresh-1"})
    )

    tokens = client.auth.login("user@example.com", "hunter2!A")

    assert route.called
    request = route.calls.last.request
    assert "Authorization" not in request.headers
    assert tokens.access_token == "access-1"
    assert client.access_token == "access-1"


@respx.mock
def test_401_triggers_one_transparent_refresh_and_retry(client: CloudDriverClient) -> None:
    client._access_token = "expired"
    client._refresh_token = "still-valid"

    me_route = respx.get(f"{BASE_URL}/auth/me").mock(
        side_effect=[
            httpx.Response(401, json={"message": "expired"}),
            httpx.Response(200, json={"authUserId": "u1", "emailAddress": "a@b.c", "isAdmin": False}),
        ]
    )
    refresh_route = respx.post(f"{BASE_URL}/auth/refresh").mock(
        return_value=httpx.Response(200, json={"token": "access-2", "refreshToken": "refresh-2"})
    )

    me = client.auth.me()

    assert me.auth_user_id == "u1"
    assert refresh_route.call_count == 1
    assert me_route.call_count == 2
    # The retried call must carry the freshly refreshed access token, not the expired one.
    assert me_route.calls.last.request.headers["Authorization"] == "Bearer access-2"
    assert client.access_token == "access-2"


@respx.mock
def test_error_response_raises_typed_exception(client: CloudDriverClient) -> None:
    client._access_token = "token"
    respx.get(f"{BASE_URL}/files/does-not-exist").mock(
        return_value=httpx.Response(404, json={"message": "No StoredFile with id does-not-exist"})
    )

    with pytest.raises(NotFoundError) as exc_info:
        client.files.get("does-not-exist")

    assert exc_info.value.status_code == 404
    assert "does-not-exist" in exc_info.value.message


@respx.mock
def test_failed_refresh_surfaces_original_unauthorized(client: CloudDriverClient) -> None:
    client._access_token = "expired"
    client._refresh_token = "also-invalid"

    respx.get(f"{BASE_URL}/auth/me").mock(return_value=httpx.Response(401, json={"message": "expired"}))
    respx.post(f"{BASE_URL}/auth/refresh").mock(return_value=httpx.Response(401, json={"message": "invalid"}))

    with pytest.raises(UnauthorizedError):
        client.auth.me()


@respx.mock
def test_list_files_root_vs_unscoped_query_params(client: CloudDriverClient) -> None:
    client._access_token = "token"
    route = respx.get(f"{BASE_URL}/files").mock(return_value=httpx.Response(200, json=[]))

    client.files.list()  # default: unscoped, no folderId param at all
    assert route.calls.last.request.url.params.get("folderId") is None

    client.files.list(None)  # root
    assert route.calls.last.request.url.params.get("folderId") == "root"

    client.files.list("abc-123")  # a specific folder
    assert route.calls.last.request.url.params.get("folderId") == "abc-123"


@respx.mock
def test_upload_bytes_sends_octet_stream(client: CloudDriverClient) -> None:
    client._access_token = "token"
    route = respx.post(f"{BASE_URL}/files").mock(
        return_value=httpx.Response(
            200,
            json={
                "fileId": "f1",
                "fileName": "a.txt",
                "contentType": "text/plain",
                "sizeBytes": 5,
                "createdAtEpochMilli": 1,
                "updatedAtEpochMilli": 1,
                "folderId": None,
            },
        )
    )

    summary = client.files.upload_bytes(b"hello", "a.txt")

    request = route.calls.last.request
    assert request.url.params["fileName"] == "a.txt"
    assert request.headers["Content-Type"] == "application/octet-stream"
    assert request.content == b"hello"
    assert summary.file_id == "f1"
    assert summary.size_bytes == 5

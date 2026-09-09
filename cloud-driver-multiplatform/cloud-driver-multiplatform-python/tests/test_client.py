"""Unit tests for CloudDriverClient, mocking the server via respx (no real network calls)."""

from __future__ import annotations

import httpx
import pytest
import respx

from cloud_driver_client import CloudDriverClient, NotFoundError, SyncConflictError, UnauthorizedError

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


@respx.mock
def test_stored_file_summary_scan_status(client: CloudDriverClient) -> None:
    client._access_token = "token"
    respx.get(f"{BASE_URL}/files").mock(
        return_value=httpx.Response(
            200,
            json=[
                {
                    "fileId": "f1",
                    "fileName": "a.txt",
                    "contentType": "text/plain",
                    "sizeBytes": 5,
                    "createdAtEpochMilli": 1,
                    "updatedAtEpochMilli": 1,
                    "folderId": None,
                    "scanStatus": "FLAGGED",
                }
            ],
        )
    )

    files = client.files.list()

    assert files[0].scan_status == "FLAGGED"


@respx.mock
def test_get_thumbnail_returns_bytes(client: CloudDriverClient) -> None:
    client._access_token = "token"
    respx.get(f"{BASE_URL}/files/f1/thumbnail").mock(return_value=httpx.Response(200, content=b"\xff\xd8jpeg"))

    assert client.files.get_thumbnail("f1") == b"\xff\xd8jpeg"


@respx.mock
def test_get_thumbnail_returns_none_on_404_and_503(client: CloudDriverClient) -> None:
    client._access_token = "token"
    respx.get(f"{BASE_URL}/files/no-thumb/thumbnail").mock(
        return_value=httpx.Response(404, json={"message": "No thumbnail available"})
    )
    respx.get(f"{BASE_URL}/files/no-ext/thumbnail").mock(
        return_value=httpx.Response(503, json={"message": "not running"})
    )

    assert client.files.get_thumbnail("no-thumb") is None
    assert client.files.get_thumbnail("no-ext") is None


@respx.mock
def test_replace_content_sends_expected_updated_at_and_octet_stream(client: CloudDriverClient) -> None:
    client._access_token = "token"
    route = respx.put(f"{BASE_URL}/files/f1/content").mock(
        return_value=httpx.Response(
            200,
            json={
                "fileId": "f1",
                "fileName": "a.txt",
                "contentType": "text/plain",
                "sizeBytes": 3,
                "createdAtEpochMilli": 1,
                "updatedAtEpochMilli": 2,
                "folderId": None,
                "scanStatus": "CLEAN",
            },
        )
    )

    summary = client.files.replace_content("f1", b"new", expected_updated_at_epoch_millis=1)

    request = route.calls.last.request
    assert request.url.params["expectedUpdatedAt"] == "1"
    assert request.headers["Content-Type"] == "application/octet-stream"
    assert request.content == b"new"
    assert summary.updated_at_epoch_milli == 2


@respx.mock
def test_replace_content_conflict_raises_sync_conflict_error(client: CloudDriverClient) -> None:
    client._access_token = "token"
    conflicted_copy = {
        "fileId": "f2",
        "fileName": "a (conflicted copy 2026-09-08 120000).txt",
        "contentType": "text/plain",
        "sizeBytes": 3,
        "createdAtEpochMilli": 1,
        "updatedAtEpochMilli": 1,
        "folderId": None,
        "scanStatus": "CLEAN",
    }
    respx.put(f"{BASE_URL}/files/f1/content").mock(return_value=httpx.Response(409, json=conflicted_copy))

    with pytest.raises(SyncConflictError) as exc_info:
        client.files.replace_content("f1", b"new", expected_updated_at_epoch_millis=1)

    assert exc_info.value.status_code == 409
    assert exc_info.value.conflicted_copy.file_id == "f2"


@respx.mock
def test_replace_content_without_precondition_never_raises_sync_conflict(client: CloudDriverClient) -> None:
    # A plain (non-sync-conflict) 409 - e.g. some other business-rule conflict - must still surface
    # as an ordinary ConflictError, not be misdetected as a sync conflict just because it's a 409.
    client._access_token = "token"
    respx.put(f"{BASE_URL}/files/f1/content").mock(return_value=httpx.Response(409, json={"message": "nope"}))

    with pytest.raises(Exception) as exc_info:
        client.files.replace_content("f1", b"new")

    assert not isinstance(exc_info.value, SyncConflictError)


@respx.mock
def test_list_versions_and_restore_version(client: CloudDriverClient) -> None:
    client._access_token = "token"
    respx.get(f"{BASE_URL}/files/f1/versions").mock(
        return_value=httpx.Response(
            200, json=[{"versionNumber": 1, "capturedAtEpochMillis": 100, "sizeBytes": 10}]
        )
    )
    respx.post(f"{BASE_URL}/files/f1/versions/1/restore").mock(
        return_value=httpx.Response(
            200,
            json={
                "fileId": "f1",
                "fileName": "a.txt",
                "contentType": "text/plain",
                "sizeBytes": 10,
                "createdAtEpochMilli": 1,
                "updatedAtEpochMilli": 2,
                "folderId": None,
                "scanStatus": "CLEAN",
            },
        )
    )

    versions = client.files.list_versions("f1")
    restored = client.files.restore_version("f1", 1)

    assert versions[0].version_number == 1
    assert restored.size_bytes == 10


@respx.mock
def test_search_sends_query_and_limit(client: CloudDriverClient) -> None:
    client._access_token = "token"
    route = respx.get(f"{BASE_URL}/search").mock(
        return_value=httpx.Response(200, json=[{"storedFileId": "f1", "fileName": "a.txt", "folderId": None}])
    )

    results = client.search("invoice", limit=10)

    request = route.calls.last.request
    assert request.url.params["q"] == "invoice"
    assert request.url.params["limit"] == "10"
    assert results[0].stored_file_id == "f1"


@respx.mock
def test_share_sends_permission_level_and_expiry(client: CloudDriverClient) -> None:
    client._access_token = "token"
    route = respx.post(f"{BASE_URL}/files/f1/share").mock(return_value=httpx.Response(204))

    client.files.share("f1", "grantee@example.test", permission_level="EDIT", expires_at_epoch_millis=123)

    body = route.calls.last.request.content
    import json as _json

    payload = _json.loads(body)
    assert payload == {"granteeEmail": "grantee@example.test", "permissionLevel": "EDIT", "expiresAtEpochMillis": 123}


@respx.mock
def test_share_defaults_to_view_permission_never_expiring(client: CloudDriverClient) -> None:
    client._access_token = "token"
    route = respx.post(f"{BASE_URL}/files/f1/share").mock(return_value=httpx.Response(204))

    client.files.share("f1", "grantee@example.test")

    import json as _json

    payload = _json.loads(route.calls.last.request.content)
    assert payload["permissionLevel"] == "VIEW"
    assert payload["expiresAtEpochMillis"] is None


@respx.mock
def test_create_and_list_and_revoke_public_link(client: CloudDriverClient) -> None:
    client._access_token = "token"
    respx.post(f"{BASE_URL}/files/f1/public-link").mock(
        return_value=httpx.Response(201, json={"token": "tok1", "createdAtEpochMillis": 1, "expiresAtEpochMillis": None})
    )
    respx.get(f"{BASE_URL}/files/f1/public-link").mock(
        return_value=httpx.Response(200, json=[{"token": "tok1", "createdAtEpochMillis": 1, "expiresAtEpochMillis": None}])
    )
    revoke_route = respx.delete(f"{BASE_URL}/files/f1/public-link/tok1").mock(return_value=httpx.Response(204))

    created = client.files.create_public_link("f1")
    links = client.files.list_public_links("f1")
    client.files.revoke_public_link("f1", "tok1")

    assert created.token == "tok1"
    assert links[0].token == "tok1"
    assert revoke_route.called


@respx.mock
def test_download_public_file_is_unauthenticated(client: CloudDriverClient, tmp_path) -> None:
    client._access_token = "token"  # a session may exist, but must not be sent for this route
    route = respx.get(f"{BASE_URL}/public/files/tok1").mock(return_value=httpx.Response(200, content=b"public bytes"))

    destination = tmp_path / "out.bin"
    client.download_public_file_to_path("tok1", destination)

    assert destination.read_bytes() == b"public bytes"
    assert "Authorization" not in route.calls.last.request.headers


@respx.mock
def test_file_and_global_activity_feed_pagination(client: CloudDriverClient) -> None:
    client._access_token = "token"
    respx.get(f"{BASE_URL}/files/f1/activity").mock(
        return_value=httpx.Response(
            200,
            json={
                "items": [
                    {
                        "id": "e1",
                        "actorAuthUserId": "u1",
                        "action": "FILE_UPLOAD",
                        "targetId": "f1",
                        "timestampEpochMillis": 100,
                        "metadata": None,
                    }
                ],
                "nextCursor": None,
            },
        )
    )
    respx.get(f"{BASE_URL}/activity").mock(
        return_value=httpx.Response(200, json={"items": [], "nextCursor": None})
    )

    page = client.files.list_activity("f1")
    global_page = client.activity.list()

    assert page.items[0].action == "FILE_UPLOAD"
    assert page.next_cursor is None
    assert global_page.items == []


# --------------------------------------------------------------- semantic search / duplicates / tags


@respx.mock
def test_semantic_search_parses_scores(client: CloudDriverClient) -> None:
    route = respx.get(f"{BASE_URL}/search/semantic").mock(
        return_value=httpx.Response(
            200,
            json=[
                {"storedFileId": "f1", "fileName": "scan_0042.pdf", "folderId": None, "score": 0.81},
                {"storedFileId": "f2", "fileName": "notes.txt", "folderId": "fold-1", "score": 0.42},
            ],
        )
    )

    results = client.semantic_search("Rechnung Autowerkstatt", limit=5)

    assert [r.stored_file_id for r in results] == ["f1", "f2"]
    assert results[0].score == pytest.approx(0.81)
    assert results[1].folder_id == "fold-1"
    assert route.calls.last.request.url.params["q"] == "Rechnung Autowerkstatt"
    assert route.calls.last.request.url.params["limit"] == "5"


@respx.mock
def test_semantic_search_unavailable_raises_so_a_caller_can_fall_back(client: CloudDriverClient) -> None:
    """A 503 must be distinguishable from "nothing matched" - that is what makes fallback possible."""
    from cloud_driver_client import ServiceUnavailableError

    respx.get(f"{BASE_URL}/search/semantic").mock(
        return_value=httpx.Response(503, json={"message": "not running"})
    )
    with pytest.raises(ServiceUnavailableError):
        client.semantic_search("anything")


@respx.mock
def test_find_duplicates_parses_groups(client: CloudDriverClient) -> None:
    route = respx.get(f"{BASE_URL}/files/duplicates").mock(
        return_value=httpx.Response(
            200,
            json=[
                {
                    "files": [
                        {"storedFileId": "a", "fileName": "invoice.pdf", "folderId": None},
                        {"storedFileId": "b", "fileName": "invoice (1).pdf", "folderId": None},
                    ],
                    "similarity": 0.97,
                }
            ],
        )
    )

    groups = client.find_duplicates(minimum_similarity=0.9, limit=10)

    assert len(groups) == 1
    assert [f.stored_file_id for f in groups[0].files] == ["a", "b"]
    assert groups[0].similarity == pytest.approx(0.97)
    assert route.calls.last.request.url.params["minimumSimilarity"] == "0.9"


@respx.mock
def test_find_duplicates_defaults_to_a_high_threshold(client: CloudDriverClient) -> None:
    """A low default would group everything sharing a topic - worse than returning nothing."""
    route = respx.get(f"{BASE_URL}/files/duplicates").mock(return_value=httpx.Response(200, json=[]))
    client.find_duplicates()
    assert float(route.calls.last.request.url.params["minimumSimilarity"]) >= 0.9


@respx.mock
def test_suggest_file_tags_parses_suggestions(client: CloudDriverClient) -> None:
    respx.get(f"{BASE_URL}/files/f1/tags").mock(
        return_value=httpx.Response(
            200, json=[{"tag": "invoice", "confidence": 0.63}, {"tag": "receipt", "confidence": 0.55}]
        )
    )

    suggestions = client.suggest_file_tags("f1", limit=2)

    assert [s.tag for s in suggestions] == ["invoice", "receipt"]
    assert suggestions[0].confidence == pytest.approx(0.63)


@respx.mock
def test_suggest_file_tags_empty_is_not_an_error(client: CloudDriverClient) -> None:
    """A never-indexed file has no vector to compare - "no suggestions" is a real answer."""
    respx.get(f"{BASE_URL}/files/f1/tags").mock(return_value=httpx.Response(200, json=[]))
    assert client.suggest_file_tags("f1") == []


@respx.mock
def test_suggest_file_tags_inaccessible_file_is_a_404(client: CloudDriverClient) -> None:
    respx.get(f"{BASE_URL}/files/other/tags").mock(
        return_value=httpx.Response(404, json={"message": "No StoredFile with id other"})
    )
    with pytest.raises(NotFoundError):
        client.suggest_file_tags("other")

"""Unit tests for the chunked content cipher and the presigned transfer helpers built on it.

The cipher tests run straight against cloud_driver_client.crypto over in-memory streams; the
helper tests mock the cloud-driver server and the object store via respx (no real network calls).
"""

from __future__ import annotations

import base64
import hashlib
import io
from pathlib import Path

import httpx
import pytest
import respx

# The cipher lives behind the optional `crypto` extra, so a checkout whose venv predates it
# reports skips rather than a collection error - the same shape the DatabaseTokenStore tests use
# for their own optional dependency.
pytest.importorskip("cryptography")

from cloud_driver_client import (  # noqa: E402  (must follow the importorskip above)
    CloudDriverClient,
    ContentIntegrityError,
    UnsupportedEncryptionError,
    crypto,
)

BASE_URL = "https://api.example.test"
OBJECT_STORE_URL = "https://objects.example.test/bucket/obj?X-Amz-Signature=abc"

KEY = bytes(range(32))
HEADER = b"\x00\x00\x00\x02" + b"HEADER-BYTES"
PREFIX = "StoredFile:abc-123"
CHUNK_SIZE = 16


@pytest.fixture
def client() -> CloudDriverClient:
    with CloudDriverClient(BASE_URL) as c:
        c._access_token = "token"
        yield c


def _encrypt(plaintext: bytes, *, chunk_size: int = CHUNK_SIZE) -> bytes:
    sink = io.BytesIO()
    crypto.encrypt_object(
        io.BytesIO(plaintext),
        sink,
        key_material=KEY,
        header=HEADER,
        associated_data_prefix=PREFIX,
        chunk_size_bytes=chunk_size,
    )
    return sink.getvalue()


def _decrypt(stored: bytes, *, key: bytes = KEY, prefix: str = PREFIX) -> bytes:
    out = io.BytesIO()
    crypto.decrypt_object(
        io.BytesIO(stored),
        out,
        key_material=key,
        associated_data_prefix=prefix,
        header_length_bytes=len(HEADER),
    )
    return out.getvalue()


def _summary_json(file_id: str = "f1", file_name: str = "a.bin", size_bytes: int = 5) -> dict:
    return {
        "fileId": file_id,
        "fileName": file_name,
        "contentType": "application/octet-stream",
        "sizeBytes": size_bytes,
        "createdAtEpochMilli": 1,
        "updatedAtEpochMilli": 1,
        "folderId": None,
    }


# -- the cipher ---------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "size",
    [0, 1, CHUNK_SIZE - 1, CHUNK_SIZE, CHUNK_SIZE + 1, CHUNK_SIZE * 4, CHUNK_SIZE * 4 + 7],
)
def test_round_trip(size: int) -> None:
    plaintext = bytes((i * 7 + 3) % 256 for i in range(size))
    assert _decrypt(_encrypt(plaintext)) == plaintext


@pytest.mark.parametrize(
    "size",
    [0, 1, CHUNK_SIZE - 1, CHUNK_SIZE, CHUNK_SIZE + 1, CHUNK_SIZE * 4, CHUNK_SIZE * 4 + 7],
)
def test_encrypted_length_matches_the_real_produced_length(size: int) -> None:
    produced = _encrypt(bytes(size))
    assert len(produced) == crypto.encrypted_length(len(HEADER), size, CHUNK_SIZE)


def test_known_answer_vector_pins_the_wire_format() -> None:
    # Fixed inputs, fixed outputs: these literals pin the bytes other clients have to be able to
    # read, so a later refactor of the framing or the nonce derivation cannot change them silently.
    assert crypto.derive_base_nonce(KEY).hex() == "4d8424d6"
    produced = _encrypt(bytes(range(40)))
    assert hashlib.sha256(produced).hexdigest() == (
        "cc66baf0ae475ba1c56ba278258ac774c627a3956491de967498b21b3c3e6ddc"
    )


def test_empty_associated_data_prefix_round_trips() -> None:
    sink = io.BytesIO()
    crypto.encrypt_object(
        io.BytesIO(b"payload"),
        sink,
        key_material=KEY,
        header=HEADER,
        associated_data_prefix="",
        chunk_size_bytes=CHUNK_SIZE,
    )
    assert _decrypt(sink.getvalue(), prefix="") == b"payload"


def test_wrong_key_is_rejected() -> None:
    with pytest.raises(ContentIntegrityError):
        _decrypt(_encrypt(b"payload"), key=bytes(32))


def _decrypt_collecting(stored: bytes) -> tuple[bytes, ContentIntegrityError]:
    """Decrypts, expecting a rejection, and returns whatever reached the sink before it."""
    out = io.BytesIO()
    with pytest.raises(ContentIntegrityError) as exc_info:
        crypto.decrypt_object(
            io.BytesIO(stored),
            out,
            key_material=KEY,
            associated_data_prefix=PREFIX,
            header_length_bytes=len(HEADER),
        )
    return out.getvalue(), exc_info.value


def test_truncated_by_one_byte_is_rejected() -> None:
    written, _ = _decrypt_collecting(_encrypt(bytes(range(40)))[:-1])
    # Whatever authenticated before the failure may have been written; nothing after it.
    assert written == bytes(range(32))


def test_truncated_mid_frame_is_rejected() -> None:
    stored = _encrypt(bytes(range(40)))
    written, _ = _decrypt_collecting(stored[: len(HEADER) + 4 + 3])
    assert written == b""


def test_flipped_ciphertext_byte_is_rejected() -> None:
    stored = bytearray(_encrypt(bytes(range(40))))
    # First frame's ciphertext starts after header + base nonce + flags + length.
    offset = len(HEADER) + crypto.BASE_NONCE_LENGTH_BYTES + crypto.FRAME_HEADER_LENGTH_BYTES
    stored[offset] ^= 0xFF
    written, _ = _decrypt_collecting(bytes(stored))
    assert written == b""


def test_flipped_tag_byte_is_rejected() -> None:
    stored = bytearray(_encrypt(bytes(range(40))))
    offset = len(HEADER) + crypto.BASE_NONCE_LENGTH_BYTES + crypto.FRAME_HEADER_LENGTH_BYTES
    stored[offset + CHUNK_SIZE] ^= 0xFF  # the first byte of the appended tag
    written, _ = _decrypt_collecting(bytes(stored))
    assert written == b""


def test_flipped_final_flag_is_rejected() -> None:
    # The flags byte is authenticated, so clearing the final marker on the last frame is an AAD
    # mismatch, not merely a stream that keeps going.
    stored = bytearray(_encrypt(bytes(range(40))))
    last_frame = len(stored) - (crypto.FRAME_HEADER_LENGTH_BYTES + crypto.TAG_LENGTH_BYTES + 8)
    assert stored[last_frame] == crypto.FLAG_FINAL
    stored[last_frame] = crypto.FLAG_NOT_FINAL
    written, _ = _decrypt_collecting(bytes(stored))
    assert written == bytes(range(32))


def test_trailing_data_after_the_final_chunk_is_rejected() -> None:
    written, error = _decrypt_collecting(_encrypt(bytes(range(40))) + b"\x00")
    assert "trailing data" in error.message
    assert written == bytes(range(40))


def test_implausible_frame_length_is_rejected_on_the_bound() -> None:
    stored = bytearray(_encrypt(bytes(range(40))))
    offset = len(HEADER) + crypto.BASE_NONCE_LENGTH_BYTES + 1
    stored[offset : offset + 4] = (2**31 - 1).to_bytes(4, "big")
    written, error = _decrypt_collecting(bytes(stored))
    assert "implausible chunk ciphertext length" in error.message
    assert written == b""


def test_unknown_flags_byte_is_rejected() -> None:
    stored = bytearray(_encrypt(bytes(range(40))))
    stored[len(HEADER) + crypto.BASE_NONCE_LENGTH_BYTES] = 0x7F
    written, error = _decrypt_collecting(bytes(stored))
    assert "unknown chunk flags value" in error.message
    assert written == b""


# -- the presigned helpers ----------------------------------------------------------------------


def _upload_ticket_json(*, encrypted: bool, plaintext_length: int) -> dict:
    ticket: dict = {
        "fileId": "f1",
        "uploadUrl": OBJECT_STORE_URL,
        "requiredHeaders": {"x-amz-server-side-encryption": "AES256"},
        "expiresAtEpochMillis": 1,
        "encryption": None,
    }
    if encrypted:
        ticket["encryption"] = {
            "contentKeyBase64": base64.b64encode(KEY).decode(),
            "headerBase64": base64.b64encode(HEADER).decode(),
            "associatedDataPrefix": PREFIX,
            "chunkSizeBytes": CHUNK_SIZE,
            "objectLengthBytes": crypto.encrypted_length(len(HEADER), plaintext_length, CHUNK_SIZE),
        }
    return ticket


def _download_ticket_json(*, encrypted: bool) -> dict:
    ticket: dict = {"downloadUrl": OBJECT_STORE_URL, "expiresAtEpochMillis": 1, "encryption": None}
    if encrypted:
        ticket["encryption"] = {
            "contentKeyBase64": base64.b64encode(KEY).decode(),
            "associatedDataPrefix": PREFIX,
            "headerLengthBytes": len(HEADER),
        }
    return ticket


@respx.mock
def test_upload_via_presigned_url_encrypts_and_completes(client: CloudDriverClient, tmp_path: Path) -> None:
    plaintext = bytes(range(40))
    source = tmp_path / "a.bin"
    source.write_bytes(plaintext)

    respx.post(f"{BASE_URL}/files/upload-url").mock(
        return_value=httpx.Response(200, json=_upload_ticket_json(encrypted=True, plaintext_length=len(plaintext)))
    )
    put_route = respx.put(OBJECT_STORE_URL).mock(return_value=httpx.Response(200))
    complete_route = respx.post(f"{BASE_URL}/files/f1/complete-upload").mock(
        return_value=httpx.Response(200, json=_summary_json(size_bytes=len(plaintext)))
    )

    summary = client.files.upload_via_presigned_url(source)

    assert summary.file_id == "f1"
    put_request = put_route.calls.last.request
    # What reached the object store is the stored object, and it decrypts back to the exact file.
    assert _decrypt(put_request.content) == plaintext
    assert put_request.headers["x-amz-server-side-encryption"] == "AES256"
    assert put_request.headers["Content-Length"] == str(len(put_request.content))
    assert "Transfer-Encoding" not in put_request.headers
    # The account's bearer token must never reach a third-party host.
    assert "Authorization" not in put_request.headers
    # The completion digest is over the PLAINTEXT, not the stored object.
    import json

    assert json.loads(complete_route.calls.last.request.content)["checksumSha256"] == (
        hashlib.sha256(plaintext).hexdigest()
    )


@respx.mock
def test_upload_via_presigned_url_plaintext_ticket(client: CloudDriverClient, tmp_path: Path) -> None:
    source = tmp_path / "a.bin"
    source.write_bytes(b"hello")

    respx.post(f"{BASE_URL}/files/upload-url").mock(
        return_value=httpx.Response(200, json=_upload_ticket_json(encrypted=False, plaintext_length=5))
    )
    put_route = respx.put(OBJECT_STORE_URL).mock(return_value=httpx.Response(200))
    complete_route = respx.post(f"{BASE_URL}/files/f1/complete-upload").mock(
        return_value=httpx.Response(200, json=_summary_json())
    )

    client.files.upload_via_presigned_url(source)

    assert put_route.calls.last.request.content == b"hello"
    assert complete_route.called


@respx.mock
def test_download_via_presigned_url_decrypts(client: CloudDriverClient, tmp_path: Path) -> None:
    plaintext = bytes(range(40))
    respx.get(f"{BASE_URL}/files/f1/download-url").mock(
        return_value=httpx.Response(200, json=_download_ticket_json(encrypted=True))
    )
    respx.get(OBJECT_STORE_URL).mock(return_value=httpx.Response(200, content=_encrypt(plaintext)))

    destination = tmp_path / "out.bin"
    assert client.files.download_via_presigned_url("f1", destination) == destination
    assert destination.read_bytes() == plaintext


@respx.mock
def test_download_via_presigned_url_plaintext_ticket(client: CloudDriverClient, tmp_path: Path) -> None:
    respx.get(f"{BASE_URL}/files/f1/download-url").mock(
        return_value=httpx.Response(200, json=_download_ticket_json(encrypted=False))
    )
    respx.get(OBJECT_STORE_URL).mock(return_value=httpx.Response(200, content=b"hello"))

    destination = tmp_path / "out.bin"
    client.files.download_via_presigned_url("f1", destination)
    assert destination.read_bytes() == b"hello"


@respx.mock
def test_download_via_presigned_url_leaves_no_file_when_object_is_tampered(
    client: CloudDriverClient, tmp_path: Path
) -> None:
    stored = bytearray(_encrypt(bytes(range(40))))
    offset = len(HEADER) + crypto.BASE_NONCE_LENGTH_BYTES + crypto.FRAME_HEADER_LENGTH_BYTES
    stored[offset] ^= 0xFF

    respx.get(f"{BASE_URL}/files/f1/download-url").mock(
        return_value=httpx.Response(200, json=_download_ticket_json(encrypted=True))
    )
    respx.get(OBJECT_STORE_URL).mock(return_value=httpx.Response(200, content=bytes(stored)))

    destination = tmp_path / "out.bin"
    with pytest.raises(ContentIntegrityError):
        client.files.download_via_presigned_url("f1", destination)
    assert destination.exists() is False


@respx.mock
def test_begin_ticket_still_refuses_without_opt_in(client: CloudDriverClient) -> None:
    respx.get(f"{BASE_URL}/files/f1/download-url").mock(
        return_value=httpx.Response(200, json=_download_ticket_json(encrypted=True))
    )
    respx.post(f"{BASE_URL}/files/upload-url").mock(
        return_value=httpx.Response(200, json=_upload_ticket_json(encrypted=True, plaintext_length=5))
    )

    with pytest.raises(UnsupportedEncryptionError):
        client.files.begin_download_url("f1")
    with pytest.raises(UnsupportedEncryptionError):
        client.files.begin_upload_url("a.bin", 5)

    # With the opt-in both hand the ticket back, encryption material and all.
    assert client.files.begin_download_url("f1", allow_encrypted=True).encryption is not None
    assert client.files.begin_upload_url("a.bin", 5, allow_encrypted=True).encryption is not None


# -- resumable multipart sessions ---------------------------------------------------------------


PART_URL_1 = "https://objects.example.test/bucket/obj?partNumber=1&X-Amz-Signature=p1"
PART_URL_2 = "https://objects.example.test/bucket/obj?partNumber=2&X-Amz-Signature=p2"


def _session_json(
    *, encrypted: bool, plaintext_length: int, part_size: int, uploaded: list[int] | None = None
) -> dict:
    object_length = (
        crypto.encrypted_length(len(HEADER), plaintext_length, CHUNK_SIZE) if encrypted else plaintext_length
    )
    part_count = (object_length + part_size - 1) // part_size
    session: dict = {
        "fileId": "s1",
        "partSizeBytes": part_size,
        "partCount": part_count,
        "totalObjectBytes": object_length,
        "uploadedPartNumbers": uploaded or [],
        "encryption": None,
        "checksumSha256": None,
    }
    if encrypted:
        session["encryption"] = {
            "contentKeyBase64": base64.b64encode(KEY).decode(),
            "headerBase64": base64.b64encode(HEADER).decode(),
            "associatedDataPrefix": PREFIX,
            "chunkSizeBytes": CHUNK_SIZE,
            "objectLengthBytes": object_length,
        }
    return session


@respx.mock
def test_upload_via_session_uploads_every_part_and_completes(client: CloudDriverClient, tmp_path: Path) -> None:
    plaintext = bytes(range(40))
    source = tmp_path / "a.bin"
    source.write_bytes(plaintext)
    stored = _encrypt(plaintext)
    part_size = 64

    session = _session_json(encrypted=True, plaintext_length=len(plaintext), part_size=part_size)
    session["checksumSha256"] = hashlib.sha256(plaintext).hexdigest()
    assert session["partCount"] == 2

    respx.post(f"{BASE_URL}/files/upload-session").mock(return_value=httpx.Response(200, json=session))
    respx.post(f"{BASE_URL}/files/upload-session/s1/parts/1/url").mock(
        return_value=httpx.Response(
            200,
            json={"partNumber": 1, "url": PART_URL_1, "requiredHeaders": {"x-amz-acl": "private"}, "expiresAtEpochMilli": 1},
        )
    )
    respx.post(f"{BASE_URL}/files/upload-session/s1/parts/2/url").mock(
        return_value=httpx.Response(
            200, json={"partNumber": 2, "url": PART_URL_2, "requiredHeaders": {}, "expiresAtEpochMilli": 1}
        )
    )
    put1 = respx.put(PART_URL_1).mock(return_value=httpx.Response(200))
    put2 = respx.put(PART_URL_2).mock(return_value=httpx.Response(200))
    complete = respx.post(f"{BASE_URL}/files/upload-session/s1/complete").mock(
        return_value=httpx.Response(200, json=_summary_json(size_bytes=len(plaintext)))
    )

    summary = client.files.upload_via_session(source)

    assert summary.file_id == "f1"
    # The parts, concatenated in order, are exactly the stored object.
    assert put1.calls.last.request.content + put2.calls.last.request.content == stored
    assert put1.calls.last.request.headers["x-amz-acl"] == "private"
    assert "Authorization" not in put1.calls.last.request.headers
    assert "Authorization" not in put2.calls.last.request.headers
    import json

    assert json.loads(complete.calls.last.request.content)["checksumSha256"] == hashlib.sha256(plaintext).hexdigest()


@respx.mock
def test_resume_upload_session_skips_the_parts_already_held(client: CloudDriverClient, tmp_path: Path) -> None:
    plaintext = bytes(range(40))
    source = tmp_path / "a.bin"
    source.write_bytes(plaintext)
    stored = _encrypt(plaintext)
    part_size = 64

    session = _session_json(encrypted=True, plaintext_length=len(plaintext), part_size=part_size, uploaded=[1])
    respx.get(f"{BASE_URL}/files/upload-session/s1").mock(return_value=httpx.Response(200, json=session))
    part1 = respx.post(f"{BASE_URL}/files/upload-session/s1/parts/1/url").mock(
        return_value=httpx.Response(
            200, json={"partNumber": 1, "url": PART_URL_1, "requiredHeaders": {}, "expiresAtEpochMilli": 1}
        )
    )
    respx.post(f"{BASE_URL}/files/upload-session/s1/parts/2/url").mock(
        return_value=httpx.Response(
            200, json={"partNumber": 2, "url": PART_URL_2, "requiredHeaders": {}, "expiresAtEpochMilli": 1}
        )
    )
    put1 = respx.put(PART_URL_1).mock(return_value=httpx.Response(200))
    put2 = respx.put(PART_URL_2).mock(return_value=httpx.Response(200))
    respx.post(f"{BASE_URL}/files/upload-session/s1/complete").mock(
        return_value=httpx.Response(200, json=_summary_json())
    )

    client.files.resume_upload_session("s1", source)

    assert part1.called is False
    assert put1.called is False
    assert put2.calls.last.request.content == stored[part_size:]


@respx.mock
def test_resume_refuses_a_different_file_before_any_part_url(client: CloudDriverClient, tmp_path: Path) -> None:
    source = tmp_path / "a.bin"
    source.write_bytes(b"not what the session was begun for")

    session = _session_json(encrypted=True, plaintext_length=40, part_size=64)
    session["checksumSha256"] = hashlib.sha256(bytes(range(40))).hexdigest()
    respx.get(f"{BASE_URL}/files/upload-session/s1").mock(return_value=httpx.Response(200, json=session))
    part1 = respx.post(f"{BASE_URL}/files/upload-session/s1/parts/1/url").mock(
        return_value=httpx.Response(200, json={"partNumber": 1, "url": PART_URL_1, "requiredHeaders": {}, "expiresAtEpochMilli": 1})
    )

    with pytest.raises(ValueError) as exc_info:
        client.files.resume_upload_session("s1", source)

    assert "abort_upload_session" in str(exc_info.value)
    assert part1.called is False


@respx.mock
def test_plaintext_session_uploads_the_file_itself(client: CloudDriverClient, tmp_path: Path) -> None:
    """A legacy plaintext session: the object the parts assemble into is the file, unencrypted."""
    source = tmp_path / "a.bin"
    source.write_bytes(b"hello world")

    session = _session_json(encrypted=False, plaintext_length=11, part_size=64)
    respx.post(f"{BASE_URL}/files/upload-session").mock(return_value=httpx.Response(200, json=session))
    respx.post(f"{BASE_URL}/files/upload-session/s1/parts/1/url").mock(
        return_value=httpx.Response(200, json={"partNumber": 1, "url": PART_URL_1, "requiredHeaders": {}, "expiresAtEpochMilli": 1})
    )
    put1 = respx.put(PART_URL_1).mock(return_value=httpx.Response(200))
    respx.post(f"{BASE_URL}/files/upload-session/s1/complete").mock(
        return_value=httpx.Response(200, json=_summary_json())
    )

    client.files.upload_via_session(source)

    assert put1.calls.last.request.content == b"hello world"


@respx.mock
def test_begin_upload_session_dedup_hit_uploads_nothing(client: CloudDriverClient, tmp_path: Path) -> None:
    source = tmp_path / "a.bin"
    source.write_bytes(b"hello")
    respx.post(f"{BASE_URL}/files/upload-session").mock(
        return_value=httpx.Response(200, json={"alreadyStored": _summary_json()})
    )

    summary = client.files.upload_via_session(source)

    assert summary.file_id == "f1"


@respx.mock
def test_begin_upload_session_503_surfaces_for_fallback(client: CloudDriverClient, tmp_path: Path) -> None:
    from cloud_driver_client import ServiceUnavailableError

    source = tmp_path / "a.bin"
    source.write_bytes(b"hello")
    respx.post(f"{BASE_URL}/files/upload-session").mock(
        return_value=httpx.Response(503, json={"message": "resumable uploads are not configured"})
    )

    with pytest.raises(ServiceUnavailableError):
        client.files.upload_via_session(source)

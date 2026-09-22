"""The client half of the server's chunked AES-256-GCM content layout.

Used by presigned direct-to-object-store transfers: the server issues a per-file content key over
the authenticated API (``POST /files/upload-url``'s ``encryption`` object) and this module produces
and reads the exact stored-object bytes, the same layout the Java and Swift SDKs write.

**Wire format** (everything big-endian), after the server-supplied header written verbatim at
offset 0::

    baseNonce                4 bytes, derived from the content key (12-byte GCM nonce minus the
                             8-byte counter)
    repeated chunk frames:
      flags                  1 byte - 0x01 marks the final chunk, 0x00 any other
      ciphertextLength       4-byte int
      ciphertext             chunk ciphertext, 16-byte GCM tag appended

Chunk ``i``'s nonce is ``baseNonce || bigEndianUInt64(i)``; its associated data is
``prefix (UTF-8) || bigEndianUInt64(i) || flags``. The header is opaque here - it opens with the
object's schema-version tag, which only the server writes and only the server interprets, so it is
copied through untouched on upload and skipped by its declared length on download. The stream always
ends with a final-flagged chunk (empty when the plaintext length is an exact chunk-size multiple),
and :func:`decrypt_object` fails closed: truncation, reordering, tampering or trailing data all
reject the object rather than yielding a shorter or altered "valid" file.
"""

from __future__ import annotations

import hmac
from hashlib import sha256
from typing import BinaryIO

from .exceptions import ContentIntegrityError, UnsupportedEncryptionError

#: GCM nonce length, in bytes.
NONCE_LENGTH_BYTES = 12

#: Bytes of each chunk's nonce taken by the big-endian chunk counter; the rest is the nonce base.
CHUNK_COUNTER_LENGTH_BYTES = 8

#: Length, in bytes, of each stored object's nonce base.
BASE_NONCE_LENGTH_BYTES = NONCE_LENGTH_BYTES - CHUNK_COUNTER_LENGTH_BYTES

#: GCM authentication tag length, in bytes.
TAG_LENGTH_BYTES = 16

#: ``flags`` value marking the final chunk of a stream.
FLAG_FINAL = 0x01

#: ``flags`` value for every chunk before the final one.
FLAG_NOT_FINAL = 0x00

#: Bytes each frame spends on its own header: the flags byte plus the 4-byte ciphertext length.
FRAME_HEADER_LENGTH_BYTES = 1 + 4

#: Hard upper bound on a declared chunk ciphertext length during decryption (64 MiB).
MAX_CHUNK_CIPHERTEXT_LENGTH_BYTES = 1 << 26

# The HKDF info string binding derive_base_nonce to this purpose - byte for byte the same string
# the Java and Swift SDKs use, or objects written by one client would not read back in another.
_BASE_NONCE_INFO = b"cloud-driver:chunked-content:base-nonce"


def _aes_gcm() -> type:
    """The ``cryptography`` package's AESGCM class, imported on first use.

    Imported lazily so that importing this module - and therefore the package - never requires the
    optional dependency: every server-mediated path works without a cipher at all.
    """
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError as exc:
        raise UnsupportedEncryptionError(
            "presigned transfers on this deployment are client-side encrypted, which needs the "
            "optional `cryptography` package - install cloud-driver-client[crypto], or use the "
            "server-mediated files.upload / files.download_to_path instead"
        ) from exc
    return AESGCM


def derive_base_nonce(key_material: bytes) -> bytes:
    """Derives a stored object's base nonce deterministically from its issued content key.

    HKDF-SHA-256 (extract-then-expand, RFC 5869) with an empty salt and a fixed info string,
    truncated to :data:`BASE_NONCE_LENGTH_BYTES`.

    Derived rather than drawn at random because a resumable upload re-encrypts the file to work out
    which parts it still owes, and the parts already stored carry the nonce of the pass that wrote
    them - a fresh random draw yields an object of exactly the right total length (all the server
    verifies) whose parts were sealed under two different nonces, and which can therefore never be
    decrypted again. Safe because the content key is issued fresh per file, so this nonce is never
    reused under a different key.

    :param key_material: the raw content key issued for this file
    :return: the 4-byte base nonce to write into the object
    """
    # RFC 5869 defines an absent salt as HashLen zero bytes, which is what the Java and Swift
    # clients pass. One expand block is far more than the four bytes needed.
    prk = hmac.new(b"\x00" * 32, key_material, sha256).digest()
    block = hmac.new(prk, _BASE_NONCE_INFO + b"\x01", sha256).digest()
    return block[:BASE_NONCE_LENGTH_BYTES]


def encrypted_length(header_length_bytes: int, plaintext_length: int, chunk_size_bytes: int) -> int:
    """The exact stored-object length a plaintext of ``plaintext_length`` bytes produces.

    Mirrors the server's own formula, so a caller can check a ticket's ``objectLengthBytes`` before
    moving a single byte.

    :raises ValueError: on a negative header or plaintext length, or a non-positive chunk size
    """
    if header_length_bytes < 0 or plaintext_length < 0 or chunk_size_bytes <= 0:
        raise ValueError(
            f"invalid lengths: header={header_length_bytes}, plaintext={plaintext_length}, "
            f"chunk={chunk_size_bytes}"
        )
    frames = plaintext_length // chunk_size_bytes + 1
    per_frame_overhead = FRAME_HEADER_LENGTH_BYTES + TAG_LENGTH_BYTES
    return header_length_bytes + BASE_NONCE_LENGTH_BYTES + frames * per_frame_overhead + plaintext_length


def _read_up_to(source: BinaryIO, count: int) -> bytes:
    """Reads up to ``count`` bytes, stopping only at a genuine end of stream.

    Only an empty read means EOF; a short read from a pipe or a socket does not, and treating one as
    EOF would flag a chunk final early and produce an object no other client can read.
    """
    collected = bytearray()
    while len(collected) < count:
        chunk = source.read(count - len(collected))
        if not chunk:
            break
        collected.extend(chunk)
    return bytes(collected)


def _read_exactly(source: BinaryIO, count: int) -> bytes:
    """``_read_up_to``, rejecting the object when fewer than ``count`` bytes are available."""
    data = _read_up_to(source, count)
    if len(data) != count:
        raise ContentIntegrityError("stream truncated mid-chunk - object rejected")
    return data


def _associated_data(prefix: bytes, chunk_index: int, flags: int) -> bytes:
    """One chunk's authenticated associated data: the prefix, the chunk index, and the flags byte."""
    return prefix + chunk_index.to_bytes(8, "big") + bytes([flags])


def _nonce(base_nonce: bytes, chunk_index: int) -> bytes:
    """One chunk's GCM nonce: the object's base nonce followed by the big-endian chunk counter."""
    return base_nonce + chunk_index.to_bytes(8, "big")


def encrypt_object(
    plaintext: BinaryIO,
    sink: BinaryIO,
    *,
    key_material: bytes,
    header: bytes,
    associated_data_prefix: str,
    chunk_size_bytes: int,
) -> int:
    """Encrypts ``plaintext`` into ``sink`` as one complete stored object.

    Memory use is O(``chunk_size_bytes``). Neither stream is closed.

    :param plaintext: the file's plaintext, read to EOF
    :param sink: where the stored object's bytes are written
    :param key_material: the raw content key from the ticket's ``contentKeyBase64``
    :param header: the server-supplied header (``headerBase64``), written verbatim first
    :param associated_data_prefix: the ticket's ``associatedDataPrefix``
    :param chunk_size_bytes: the ticket's ``chunkSizeBytes``
    :return: the total number of bytes written (header + nonce + frames)
    :raises ValueError: if the chunk size is not positive, or is large enough that its frames would
        exceed what this and every other client accepts on decryption
    """
    if chunk_size_bytes <= 0:
        raise ValueError(f"chunk_size_bytes must be positive, got {chunk_size_bytes}")
    if chunk_size_bytes + TAG_LENGTH_BYTES > MAX_CHUNK_CIPHERTEXT_LENGTH_BYTES:
        raise ValueError(
            f"chunk_size_bytes {chunk_size_bytes} would produce frames longer than the "
            f"{MAX_CHUNK_CIPHERTEXT_LENGTH_BYTES}-byte maximum every client rejects on decryption"
        )

    aesgcm = _aes_gcm()(key_material)
    # A null prefix must behave as an empty one, matching the other clients.
    prefix = (associated_data_prefix or "").encode("utf-8")
    base_nonce = derive_base_nonce(key_material)

    # The header is the server's own bytes - copied through untouched, never constructed here.
    sink.write(header)
    sink.write(base_nonce)
    written = len(header) + len(base_nonce)

    chunk_index = 0
    while True:
        chunk = _read_up_to(plaintext, chunk_size_bytes)
        # A short read only ever means EOF, so a full chunk stays non-final - the next round serves
        # the remainder, or an empty final chunk on an exact chunk-size multiple.
        flags = FLAG_FINAL if len(chunk) < chunk_size_bytes else FLAG_NOT_FINAL
        ciphertext = aesgcm.encrypt(
            _nonce(base_nonce, chunk_index), chunk, _associated_data(prefix, chunk_index, flags)
        )
        sink.write(bytes([flags]))
        sink.write(len(ciphertext).to_bytes(4, "big"))
        sink.write(ciphertext)
        written += FRAME_HEADER_LENGTH_BYTES + len(ciphertext)
        chunk_index += 1
        if flags == FLAG_FINAL:
            return written


def decrypt_object(
    stored_object: BinaryIO,
    sink: BinaryIO,
    *,
    key_material: bytes,
    associated_data_prefix: str,
    header_length_bytes: int,
) -> int:
    """Decrypts a stored object into ``sink``, verifying every chunk before writing its plaintext.

    Fails closed: truncation anywhere, an altered or reordered chunk, an implausible declared frame
    length, an unknown flags value or trailing data all raise :class:`ContentIntegrityError` before
    ``sink`` sees unverified bytes. Neither stream is closed.

    :param stored_object: the fetched object's bytes, read to EOF
    :param sink: where the verified plaintext is written
    :param key_material: the raw content key from the ticket's ``contentKeyBase64``
    :param associated_data_prefix: the ticket's ``associatedDataPrefix``
    :param header_length_bytes: the ticket's ``headerLengthBytes``
    :return: the number of plaintext bytes written
    :raises ValueError: if ``header_length_bytes`` is negative
    """
    if header_length_bytes < 0:
        raise ValueError(f"header_length_bytes cannot be negative, got {header_length_bytes}")

    aesgcm = _aes_gcm()(key_material)
    prefix = (associated_data_prefix or "").encode("utf-8")

    # Read the header rather than seeking past it: the source may be non-seekable, and reading is
    # what proves the object is at least that long.
    _read_exactly(stored_object, header_length_bytes)
    # Taken from the object, never re-derived - an object written by an older client must still
    # read back.
    base_nonce = _read_exactly(stored_object, BASE_NONCE_LENGTH_BYTES)

    plaintext_bytes = 0
    chunk_index = 0
    while True:
        flags_byte = stored_object.read(1)
        if not flags_byte:
            raise ContentIntegrityError("stream ended before a final chunk was seen - object rejected")
        flags = flags_byte[0]
        if flags not in (FLAG_FINAL, FLAG_NOT_FINAL):
            raise ContentIntegrityError(f"unknown chunk flags value {flags} - object rejected")
        # This length is read before anything authenticates it, so it must never be able to demand
        # a huge allocation.
        ciphertext_length = int.from_bytes(_read_exactly(stored_object, 4), "big")
        if ciphertext_length < TAG_LENGTH_BYTES or ciphertext_length > MAX_CHUNK_CIPHERTEXT_LENGTH_BYTES:
            raise ContentIntegrityError(
                f"implausible chunk ciphertext length {ciphertext_length} - object rejected"
            )
        ciphertext = _read_exactly(stored_object, ciphertext_length)
        try:
            chunk = aesgcm.decrypt(
                _nonce(base_nonce, chunk_index), ciphertext, _associated_data(prefix, chunk_index, flags)
            )
        except Exception as exc:  # cryptography raises InvalidTag; anything else is just as fatal
            raise ContentIntegrityError(
                f"chunk {chunk_index} failed authentication - object rejected"
            ) from exc
        # Written only after it authenticates.
        sink.write(chunk)
        plaintext_bytes += len(chunk)
        chunk_index += 1
        if flags == FLAG_FINAL:
            if stored_object.read(1):
                raise ContentIntegrityError("trailing data after the final chunk - object rejected")
            return plaintext_bytes

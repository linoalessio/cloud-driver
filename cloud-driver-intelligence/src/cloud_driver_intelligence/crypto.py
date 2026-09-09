"""At-rest encryption for stored vectors.

Every other persistence path in ``cloud-driver`` envelope-encrypts its payload before it reaches
storage. This service was, for a long time, the single exception: a store file holding vectors
derived from real file content, sitting on disk in the clear. Embeddings are not the plaintext,
but they are *partially invertible* - approximate source text can be reconstructed from them -
so the honest characterisation was always "derived plaintext", not "opaque numbers".

This module closes that gap. It is deliberately **opt-in** (see
:attr:`~.config.Settings.encryption_key`): silently changing an existing deployment's storage
format would be worse than the gap it fixes, and the store is fully derived, so opting in costs a
key and a re-index.

**Failure posture is fail-closed, unlike everything else in this service.** Elsewhere here a
missing optional dependency degrades to reduced functionality, because the cost is only "search
finds less". Here the cost of degrading would be writing unencrypted vectors to disk after an
operator explicitly asked for encryption - so a configured-but-unusable key raises, and
:mod:`.store` responds by refusing to persist at all rather than persisting in the clear.
"""

from __future__ import annotations

import base64
import os
import struct

#: AES-256 key length in bytes.
_KEY_LENGTH = 32

#: GCM nonce length in bytes - 96 bits, the size AES-GCM is specified and optimised for.
_NONCE_LENGTH = 12


class VectorCipher:
    """AES-256-GCM over a vector's raw ``float32`` bytes.

    GCM (rather than a plain stream cipher) so a tampered store is *detected* rather than silently
    decrypted into a garbage vector that would quietly poison every ranking it takes part in -
    the same authenticated-encryption reasoning ``AesGcmEncryptionService`` applies on the Java
    side, for the same reason.
    """

    def __init__(self, key: bytes) -> None:
        if len(key) != _KEY_LENGTH:
            raise ValueError(
                f"CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY must decode to {_KEY_LENGTH} bytes, got {len(key)}"
            )
        try:
            from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: PLC0415
        except ImportError as missing:  # pragma: no cover - exercised only without the extra
            raise RuntimeError(
                "CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY is set but the 'cryptography' package is "
                "not installed - install this service with the 'encryption' extra. Refusing to "
                "fall back to unencrypted storage."
            ) from missing
        self._aesgcm = AESGCM(key)

    @classmethod
    def from_base64(cls, encoded: str) -> "VectorCipher":
        """Build a cipher from the base64 key form the environment carries."""
        try:
            key = base64.b64decode(encoded, validate=True)
        except Exception as malformed:  # noqa: BLE001 - any decode failure is the same operator error
            raise ValueError(
                "CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY is not valid base64"
            ) from malformed
        return cls(key)

    def encrypt(self, vector: list[float]) -> bytes:
        """Encrypt ``vector``, returning ``nonce || ciphertext``.

        A fresh random nonce per call, never reused with the same key - the one rule AES-GCM
        cannot survive being broken.
        """
        nonce = os.urandom(_NONCE_LENGTH)
        plaintext = struct.pack(f"<{len(vector)}f", *vector)
        return nonce + self._aesgcm.encrypt(nonce, plaintext, None)

    def decrypt(self, blob: bytes) -> list[float]:
        """Inverse of :meth:`encrypt`.

        Raises if the blob was tampered with or was written under a different key - both of which
        are states a caller must treat as "this entry is unusable", never as a recoverable vector.
        """
        nonce, ciphertext = blob[:_NONCE_LENGTH], blob[_NONCE_LENGTH:]
        plaintext = self._aesgcm.decrypt(nonce, ciphertext, None)
        return list(struct.unpack(f"<{len(plaintext) // 4}f", plaintext))


def build_cipher(encoded_key: str | None) -> VectorCipher | None:
    """The cipher for ``encoded_key``, or ``None`` when no key is configured.

    Propagates rather than swallows a configured-but-broken key - see this module's docstring on
    why this one path is fail-closed.
    """
    if not encoded_key or not encoded_key.strip():
        return None
    return VectorCipher.from_base64(encoded_key.strip())

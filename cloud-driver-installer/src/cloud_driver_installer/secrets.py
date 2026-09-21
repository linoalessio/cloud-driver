"""Secret generation and log redaction.

Every credential the installer invents is produced here, and every line that reaches the GUI log
passes through :class:`Redactor`, which knows all of them. Generation mirrors what
``shell/provision-root-server.sh`` does on the server (``openssl rand -hex 24`` for database
passwords - hex, never base64, so a value can never contain the ``/`` a sed delimiter would trip
over - and ``openssl rand -base64 32`` for the JWT signing key), so files written by either path
look the same.
"""

from __future__ import annotations

import base64
import secrets as _secrets

#: Values shorter than this are never redacted: a 4-character token would also blank every
#: unrelated occurrence of those characters in ordinary output.
MIN_REDACT_LENGTH = 8

MASK = "••••••••"


def generate_hex(n_bytes: int = 24) -> str:
    """Return ``n_bytes`` of randomness as lowercase hex (48 characters by default)."""
    return _secrets.token_hex(n_bytes)


def generate_base64(n_bytes: int = 32) -> str:
    """Return ``n_bytes`` of randomness as standard base64 (44 characters by default)."""
    return base64.b64encode(_secrets.token_bytes(n_bytes)).decode("ascii")


class Redactor:
    """Replaces every registered secret in a piece of text with a mask.

    Longer secrets are replaced first so a secret that happens to be a prefix of another can
    never leave the longer one's tail visible.
    """

    def __init__(self) -> None:
        self._values: set[str] = set()

    def add(self, value: str | None) -> None:
        """Register ``value``; blank or very short values are ignored (see :data:`MIN_REDACT_LENGTH`)."""
        if value and len(value) >= MIN_REDACT_LENGTH:
            self._values.add(value)

    def add_all(self, values: "list[str | None] | tuple[str | None, ...]") -> None:
        """Register every value in ``values``."""
        for value in values:
            self.add(value)

    def redact(self, text: str) -> str:
        """Return ``text`` with every registered secret replaced by :data:`MASK`."""
        if not text or not self._values:
            return text
        for value in sorted(self._values, key=len, reverse=True):
            text = text.replace(value, MASK)
        return text

    def __contains__(self, value: object) -> bool:
        return value in self._values

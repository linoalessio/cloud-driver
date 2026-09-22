"""Tests for :mod:`cloud_driver_installer.credentials`: credential generation and log redaction."""

from __future__ import annotations

import base64
import re

from cloud_driver_installer.credentials import MASK, MIN_REDACT_LENGTH, Redactor, generate_base64, generate_hex


class TestGenerate:
    """The generators mirror ``openssl rand -hex 24`` and ``openssl rand -base64 32``."""

    def test_hex_default_is_48_lowercase_hex_characters(self) -> None:
        """24 random bytes render as 48 lowercase hex digits."""
        value = generate_hex()
        assert len(value) == 48
        assert re.fullmatch(r"[0-9a-f]{48}", value)

    def test_hex_length_follows_byte_count(self) -> None:
        """``n_bytes`` bytes become ``2 * n_bytes`` characters."""
        assert len(generate_hex(8)) == 16
        assert len(generate_hex(1)) == 2

    def test_hex_never_contains_a_sed_delimiter(self) -> None:
        """Hex output can never contain ``/``, which is why database passwords are hex."""
        assert all("/" not in generate_hex() for _ in range(50))

    def test_base64_default_is_44_characters_of_32_bytes(self) -> None:
        """32 random bytes render as 44 characters of standard (padded) base64."""
        value = generate_base64()
        assert len(value) == 44
        assert len(base64.b64decode(value, validate=True)) == 32

    def test_base64_length_follows_byte_count(self) -> None:
        """A different byte count decodes back to exactly that many bytes."""
        assert len(base64.b64decode(generate_base64(16), validate=True)) == 16

    def test_values_are_unique(self) -> None:
        """Two calls never return the same value."""
        assert len({generate_hex() for _ in range(20)}) == 20
        assert len({generate_base64() for _ in range(20)}) == 20


class TestRedactor:
    """Every registered secret is masked; short or blank values are ignored."""

    def test_registered_secret_is_masked_everywhere(self) -> None:
        """Every occurrence is replaced, not only the first."""
        redactor = Redactor()
        redactor.add("s3cr3tvalue")
        assert redactor.redact("pw=s3cr3tvalue again s3cr3tvalue") == f"pw={MASK} again {MASK}"

    def test_short_values_are_ignored(self) -> None:
        """A value shorter than :data:`MIN_REDACT_LENGTH` would blank unrelated text."""
        redactor = Redactor()
        redactor.add("abc")
        redactor.add("a" * (MIN_REDACT_LENGTH - 1))
        assert "abc" not in redactor
        assert redactor.redact("abc abc") == "abc abc"

    def test_value_of_exactly_minimum_length_is_redacted(self) -> None:
        """The threshold is inclusive."""
        value = "x" * MIN_REDACT_LENGTH
        redactor = Redactor()
        redactor.add(value)
        assert value in redactor
        assert redactor.redact(f"[{value}]") == f"[{MASK}]"

    def test_blank_and_none_are_ignored(self) -> None:
        """``None`` and ``""`` register nothing and never break redaction."""
        redactor = Redactor()
        redactor.add(None)
        redactor.add("")
        assert redactor.redact("untouched text") == "untouched text"

    def test_add_all_registers_every_value(self) -> None:
        """``add_all`` accepts lists and tuples, skipping ``None`` entries."""
        redactor = Redactor()
        redactor.add_all(["first-secret", None, "second-secret"])
        redactor.add_all(("third-secret",))
        assert redactor.redact("first-secret second-secret third-secret") == f"{MASK} {MASK} {MASK}"

    def test_longer_secret_wins_over_its_prefix(self) -> None:
        """A secret that is a prefix of another must not leave the longer one's tail visible."""
        redactor = Redactor()
        redactor.add("secretvalue")
        redactor.add("secretvalue-longer")
        assert redactor.redact("x secretvalue-longer y") == f"x {MASK} y"
        assert redactor.redact("x secretvalue y") == f"x {MASK} y"

    def test_redact_without_values_returns_text_unchanged(self) -> None:
        """No registered values means no work (and the same object back)."""
        redactor = Redactor()
        text = "nothing to hide"
        assert redactor.redact(text) is text

    def test_redact_empty_text(self) -> None:
        """Empty input stays empty even with secrets registered."""
        redactor = Redactor()
        redactor.add("s3cr3tvalue")
        assert redactor.redact("") == ""

    def test_contains_checks_registration(self) -> None:
        """``in`` reports whether a value is registered."""
        redactor = Redactor()
        redactor.add("s3cr3tvalue")
        assert "s3cr3tvalue" in redactor
        assert "other-value" not in redactor

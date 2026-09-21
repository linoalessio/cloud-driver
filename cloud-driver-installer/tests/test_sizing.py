"""Tests for :mod:`cloud_driver_installer.sizing`: the heap heuristic and the size conversions."""

from __future__ import annotations

import pytest

from cloud_driver_installer.sizing import (
    GIB,
    MIB,
    MIN_XMX_G,
    bytes_to_gib,
    format_bytes,
    gib_to_bytes,
    heap_is_tight,
    parse_clamd_size,
    parse_size_to_bytes,
    parse_xmx_mib,
    suggest_jvm_xmx,
)

#: The 7.7 GiB reference box.
REFERENCE_RAM_MIB = 7884


class TestSuggestJvmXmx:
    """RAM minus the fixed overheads, rounded to whole gigabytes, never below the floor."""

    def test_reference_box_with_clamav(self) -> None:
        """7884 - 1024 (JVM) - 1024 (Postgres) - 1024 (clamd) = 4812 MiB -> 5g."""
        assert suggest_jvm_xmx(REFERENCE_RAM_MIB) == "5g"

    def test_reference_box_without_clamav_matches_the_shipped_6g(self) -> None:
        """Without clamd the budget is 5836 MiB -> 6g, the value start-cloud.sh ships."""
        assert suggest_jvm_xmx(REFERENCE_RAM_MIB, clamav_enabled=False) == "6g"

    def test_intelligence_service_costs_two_gigabytes(self) -> None:
        """The intelligence unit's MemoryMax=2G is subtracted when enabled."""
        assert suggest_jvm_xmx(REFERENCE_RAM_MIB, intelligence_enabled=True) == "3g"

    def test_external_postgres_frees_a_gigabyte(self) -> None:
        """A remote database costs no local RAM."""
        assert suggest_jvm_xmx(REFERENCE_RAM_MIB, postgres_local=False) == "6g"

    def test_never_below_the_floor(self) -> None:
        """A tiny (or negative) budget still suggests the minimum."""
        assert suggest_jvm_xmx(2048) == f"{MIN_XMX_G}g"
        assert suggest_jvm_xmx(0, intelligence_enabled=True) == "2g"

    def test_large_box(self) -> None:
        """16 GiB -> 13g."""
        assert suggest_jvm_xmx(16384) == "13g"

    def test_rounds_to_nearest_gigabyte(self) -> None:
        """4.71 GiB rounds up, 4.13 GiB rounds down."""
        assert suggest_jvm_xmx(7900) == "5g"
        assert suggest_jvm_xmx(7300) == "4g"


class TestHeapIsTight:
    """Tight means under 512 MiB of headroom after heap plus overheads."""

    def test_comfortable_heap(self) -> None:
        """4g on the reference box leaves 716 MiB."""
        assert heap_is_tight(REFERENCE_RAM_MIB, "4g", clamav_enabled=True, intelligence_enabled=False) is False

    def test_oversubscribed_heap(self) -> None:
        """5g plus clamd exceeds the reference box."""
        assert heap_is_tight(REFERENCE_RAM_MIB, "5g", clamav_enabled=True, intelligence_enabled=False) is True

    def test_disabling_clamav_relaxes_it(self) -> None:
        """Same heap, no clamd: 716 MiB free again."""
        assert heap_is_tight(REFERENCE_RAM_MIB, "5g", clamav_enabled=False, intelligence_enabled=False) is False

    def test_intelligence_counts(self) -> None:
        """4g + all overheads + intelligence exceeds 8 GiB."""
        assert heap_is_tight(8192, "4g", clamav_enabled=True, intelligence_enabled=True) is True
        assert heap_is_tight(16384, "6g", clamav_enabled=True, intelligence_enabled=True) is False

    def test_megabyte_heap_spelling(self) -> None:
        """``4096m`` is the same heap as ``4g``."""
        assert heap_is_tight(REFERENCE_RAM_MIB, "4096m", clamav_enabled=True, intelligence_enabled=False) is False

    def test_invalid_heap_raises(self) -> None:
        """An unparseable -Xmx propagates the ValueError."""
        with pytest.raises(ValueError):
            heap_is_tight(REFERENCE_RAM_MIB, "lots", clamav_enabled=True, intelligence_enabled=False)

    @pytest.mark.xfail(
        strict=True,
        reason="suggest_jvm_xmx rounds to the nearest gigabyte (up to 512 MiB past the budget) while "
        "heap_is_tight demands 512 MiB headroom, so the suggestion for the reference box is itself flagged tight",
    )
    def test_suggested_heap_is_not_flagged_tight(self) -> None:
        """The value the installer proposes should not immediately trigger the RAM warning."""
        xmx = suggest_jvm_xmx(REFERENCE_RAM_MIB)
        assert heap_is_tight(REFERENCE_RAM_MIB, xmx, clamav_enabled=True, intelligence_enabled=False) is False


class TestParseXmxMib:
    """JVM heap spellings to MiB."""

    @pytest.mark.parametrize(
        ("text", "mib"),
        [("6g", 6144), ("6G", 6144), ("6144m", 6144), ("512M", 512), ("1048576k", 1024), (" 2g ", 2048)],
    )
    def test_valid(self, text: str, mib: int) -> None:
        """Units g/m/k in either case, surrounding whitespace tolerated."""
        assert parse_xmx_mib(text) == mib

    @pytest.mark.parametrize("text", ["", "6gb", "lots", "g", "1.5g"])
    def test_invalid(self, text: str) -> None:
        """Anything that is not ``<digits><g|m|k>`` is rejected."""
        with pytest.raises(ValueError, match="not a JVM heap size"):
            parse_xmx_mib(text)


class TestParseSizeToBytes:
    """Human sizes (decimal comma accepted) to bytes."""

    @pytest.mark.parametrize(
        ("text", "expected"),
        [
            ("256 GiB", 256 * GIB),
            ("1.5G", int(1.5 * GIB)),
            ("1,5 GB", int(1.5 * GIB)),
            ("104857600", 104857600),
            ("2 TiB", 2 * 1024 * GIB),
            ("512k", 524288),
            ("10 b", 10),
            ("3 mib", 3 * MIB),
        ],
    )
    def test_valid(self, text: str, expected: int) -> None:
        """Every unit spelling maps to its binary factor."""
        assert parse_size_to_bytes(text) == expected

    @pytest.mark.parametrize("text", ["", "abc", "-5 GiB", "5 ib"])
    def test_invalid(self, text: str) -> None:
        """Garbage, negatives and unknown units raise."""
        with pytest.raises(ValueError):
            parse_size_to_bytes(text)


class TestConversions:
    """GiB <-> bytes and the human formatter."""

    def test_gib_round_trip(self) -> None:
        """256 GiB is 274877906944 bytes and back."""
        assert gib_to_bytes(256) == 274877906944
        assert bytes_to_gib(274877906944) == 256.0
        assert bytes_to_gib(gib_to_bytes(0.5)) == 0.5

    @pytest.mark.parametrize(
        ("n", "expected"),
        [
            (0, "0 B"),
            (512, "512 B"),
            (1024, "1 KiB"),
            (1536, "1.5 KiB"),
            (REFERENCE_RAM_MIB * MIB, "7.7 GiB"),
            (int(61.4 * MIB), "61.4 MiB"),
            (2 * 1024 * GIB, "2 TiB"),
            (3 * 1024 * 1024 * GIB, "3072 TiB"),
        ],
    )
    def test_format_bytes(self, n: int, expected: str) -> None:
        """One decimal, ``.0`` dropped, TiB is the largest unit."""
        assert format_bytes(n) == expected


class TestParseClamdSize:
    """clamd.conf size syntax."""

    @pytest.mark.parametrize(("text", "expected"), [("128M", 128 * MIB), ("2G", 2 * GIB), ("300", 300), ("1k", 1024), ("64m", 64 * MIB)])
    def test_valid(self, text: str, expected: int) -> None:
        """K/M/G suffixes (no ``B``), bare numbers are bytes."""
        assert parse_clamd_size(text) == expected

    @pytest.mark.parametrize("text", ["", "128MB", "abc", "1.5G"])
    def test_invalid(self, text: str) -> None:
        """Byte suffixes and fractions are not clamd syntax."""
        with pytest.raises(ValueError, match="not a clamd size"):
            parse_clamd_size(text)

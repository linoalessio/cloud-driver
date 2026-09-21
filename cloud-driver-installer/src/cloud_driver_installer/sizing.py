"""Byte-size conversions and the JVM heap suggestion.

The heap rule is the one docs/requirements.md §7 states for the reference deployment: budget the
box's RAM minus roughly 1 GiB for the JVM outside its heap, 1 GiB for a co-located Postgres, 1 GiB
for ``clamd`` once its signature database is loaded, and the 2 GiB ``MemoryMax`` the intelligence
service's unit caps it at. The result is rounded to the nearest whole gigabyte because ``-Xmx``
values on this project have always been whole gigabytes (``6g`` on the 7.7 GiB reference box
without the intelligence service).
"""

from __future__ import annotations

import re

MIB = 1024 ** 2
GIB = 1024 ** 3

#: Overheads in MiB, per requirements.md §7.
JVM_OFF_HEAP_MIB = 1024
POSTGRES_MIB = 1024
CLAMD_MIB = 1024
INTELLIGENCE_MIB = 2048

#: Never suggest less than this - below it the in-memory upload path for files under 32 MiB
#: cannot serve a handful of concurrent uploads.
MIN_XMX_G = 2

_SIZE_RE = re.compile(r"^\s*(\d+(?:[.,]\d+)?)\s*([kmgt]?i?b?)\s*$", re.IGNORECASE)
_UNIT_FACTORS = {
    "": 1,
    "b": 1,
    "k": 1024,
    "kb": 1024,
    "kib": 1024,
    "m": MIB,
    "mb": MIB,
    "mib": MIB,
    "g": GIB,
    "gb": GIB,
    "gib": GIB,
    "t": GIB * 1024,
    "tb": GIB * 1024,
    "tib": GIB * 1024,
}


def suggest_jvm_xmx(
    ram_mib: int,
    *,
    postgres_local: bool = True,
    clamav_enabled: bool = True,
    intelligence_enabled: bool = False,
) -> str:
    """Return a ``-Xmx`` value such as ``"5g"`` for a box with ``ram_mib`` MiB of RAM."""
    budget = ram_mib - JVM_OFF_HEAP_MIB
    if postgres_local:
        budget -= POSTGRES_MIB
    if clamav_enabled:
        budget -= CLAMD_MIB
    if intelligence_enabled:
        budget -= INTELLIGENCE_MIB
    gigs = int(round(budget / 1024))
    return f"{max(MIN_XMX_G, gigs)}g"


def heap_is_tight(ram_mib: int, xmx: str, *, clamav_enabled: bool, intelligence_enabled: bool) -> bool:
    """True when ``xmx`` plus the fixed overheads leaves under 512 MiB of the box's RAM free."""
    used = parse_xmx_mib(xmx) + JVM_OFF_HEAP_MIB + POSTGRES_MIB
    if clamav_enabled:
        used += CLAMD_MIB
    if intelligence_enabled:
        used += INTELLIGENCE_MIB
    return ram_mib - used < 512


def parse_xmx_mib(xmx: str) -> int:
    """Convert a JVM ``-Xmx`` value (``6g``, ``6144m``, ``512M``) to MiB."""
    match = re.fullmatch(r"\s*(\d+)\s*([kKmMgG])?\s*", xmx or "")
    if not match:
        raise ValueError(f"not a JVM heap size: {xmx!r} (expected e.g. 6g or 6144m)")
    amount = int(match.group(1))
    unit = (match.group(2) or "m").lower()
    return {"k": amount // 1024, "m": amount, "g": amount * 1024}[unit]


def parse_size_to_bytes(text: str) -> int:
    """Parse ``"256 GiB"``, ``"1.5G"``, ``"104857600"`` … into a byte count (decimal comma accepted)."""
    match = _SIZE_RE.match(text or "")
    if not match:
        raise ValueError(f"not a size: {text!r} (expected e.g. 256 GiB or 104857600)")
    amount = float(match.group(1).replace(",", "."))
    unit = match.group(2).lower()
    if unit not in _UNIT_FACTORS:
        raise ValueError(f"unknown size unit in {text!r}")
    return int(amount * _UNIT_FACTORS[unit])


def gib_to_bytes(gib: float) -> int:
    """``256`` -> ``274877906944``."""
    return int(gib * GIB)


def bytes_to_gib(n: int) -> float:
    """``274877906944`` -> ``256.0``."""
    return n / GIB


def format_bytes(n: int) -> str:
    """Human-readable binary size: ``7.7 GiB``, ``412 KiB``, ``61.4 MiB``."""
    value = float(n)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if value < 1024 or unit == "TiB":
            if unit == "B":
                return f"{int(value)} B"
            return f"{value:.1f} {unit}".replace(".0 ", " ")
        value /= 1024
    return f"{value:.1f} TiB"


def parse_clamd_size(text: str) -> int:
    """clamd.conf size syntax (``128M``, ``300M``, ``2G``) -> bytes; used to validate the limits."""
    match = re.fullmatch(r"\s*(\d+)\s*([kKmMgG])?\s*", text or "")
    if not match:
        raise ValueError(f"not a clamd size: {text!r} (expected e.g. 128M)")
    amount = int(match.group(1))
    unit = (match.group(2) or "").lower()
    return amount * {"": 1, "k": 1024, "m": MIB, "g": GIB}[unit]

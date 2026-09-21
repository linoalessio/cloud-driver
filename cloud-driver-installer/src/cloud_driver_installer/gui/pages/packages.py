"""Base packages page: a read-only table of the apt packages the ``packages`` step installs.

The package list comes from ``PackagesStep.PACKAGES`` when that step module is importable and
falls back to the reference set otherwise. Present/missing per package is not part of
:class:`~cloud_driver_installer.model.Discovered`, so the pills are derived from the step's status
and its check detail (``"Installed: a, b. Missing: c, d."``) instead.
"""

from __future__ import annotations

import re
import tkinter as tk
from tkinter import ttk
from typing import TYPE_CHECKING, Any, Iterable

from cloud_driver_installer.engine import StepStatus
from cloud_driver_installer.gui.pages.base import Page
from cloud_driver_installer.gui.widgets import Hint
from cloud_driver_installer.steps import STEP_ORDER

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.model import InstallPlan

_WHY_CADDY = "Caddy apt repository, health probes"
_WHY_PROVISION = "same set provision-root-server.sh installs"

#: The reference package set with the mockup's one-line reasons; used when the step is not importable.
FALLBACK_PACKAGES: tuple[tuple[str, str], ...] = (
    ("screen", "start-cloud.sh runs the JVM inside a detached session"),
    ("curl", _WHY_CADDY),
    ("gnupg", _WHY_CADDY),
    ("ca-certificates", _WHY_CADDY),
    ("openssl", "secret generation on the server side"),
    ("unzip", _WHY_PROVISION),
    ("apt-transport-https", _WHY_PROVISION),
    ("debian-keyring", _WHY_PROVISION),
    ("debian-archive-keyring", _WHY_PROVISION),
)

#: Pill colours (the mockup's ok / warn / skip).
_PILL_COLOURS = {"present": "#2FA84F", "missing": "#D9840A", "not checked": "#A1A1A6"}


def package_table() -> list[tuple[str, str]]:
    """``(package, why)`` rows: the step's own list when available, else :data:`FALLBACK_PACKAGES`."""
    try:
        from cloud_driver_installer.steps.packages import PackagesStep

        declared: Iterable[Any] = PackagesStep.PACKAGES
    except (ImportError, AttributeError):
        return list(FALLBACK_PACKAGES)
    reasons = dict(FALLBACK_PACKAGES)
    rows: list[tuple[str, str]] = []
    for item in declared:
        if isinstance(item, str):
            rows.append((item, reasons.get(item, _WHY_PROVISION)))
        else:
            name = str(item[0])
            why = str(item[1]) if len(item) > 1 and item[1] else reasons.get(name, _WHY_PROVISION)
            rows.append((name, why))
    return rows or list(FALLBACK_PACKAGES)


def parse_package_detail(detail: str) -> tuple[set[str], set[str]]:
    """Extract the ``Installed:`` and ``Missing:`` package lists from the step's check detail."""
    installed: set[str] = set()
    missing: set[str] = set()
    for keyword, target in (("Installed:", installed), ("Missing:", missing)):
        match = re.search(re.escape(keyword) + r"\s*([^.;]*)", detail or "")
        if match:
            target.update(name.strip() for name in match.group(1).split(",") if name.strip())
    return installed, missing


def package_states(state: Any, names: Iterable[str]) -> dict[str, str]:
    """Map each package to ``present``, ``missing`` or ``not checked`` from the state's status + detail."""
    discovered = state.discovered
    installed = set(getattr(discovered, "installed_packages", None) or ())
    missing = set(getattr(discovered, "missing_packages", None) or ())
    if not installed and not missing:
        installed, missing = parse_package_detail(state.details.get("packages", ""))
    status = state.statuses.get("packages")
    result: dict[str, str] = {}
    for name in names:
        if name in missing:
            result[name] = "missing"
        elif name in installed or status in (StepStatus.OK, StepStatus.DONE):
            result[name] = "present"
        elif missing and status is StepStatus.NEEDS_APPLY:
            result[name] = "present"
        else:
            result[name] = "not checked"
    return result


class PackagesPage(Page):
    """The ``packages`` step: no inputs, just the table and the apt note."""

    step_id = "packages"
    title = dict(STEP_ORDER)["packages"]

    def __init__(self, parent: tk.Misc, state: Any, actions: Any) -> None:
        self._pills: dict[str, ttk.Label] = {}
        super().__init__(parent, state, actions)

    def build(self, body: tk.Misc) -> None:
        """Lay out the Package / Why / State table and the note under it."""
        table = ttk.Frame(body)
        table.pack(fill="x")
        table.columnconfigure(1, weight=1)
        for column, heading in enumerate(("Package", "Why", "State")):
            Hint(table, heading.upper()).grid(row=0, column=column, sticky="w", padx=(0, 16), pady=(0, 4))
        for row, (name, why) in enumerate(package_table(), start=1):
            ttk.Label(table, text=name, font="TkFixedFont").grid(row=row, column=0, sticky="w", padx=(0, 16), pady=2)
            ttk.Label(table, text=why).grid(row=row, column=1, sticky="w", padx=(0, 16), pady=2)
            pill = ttk.Label(table, text="not checked", foreground=_PILL_COLOURS["not checked"])
            pill.grid(row=row, column=2, sticky="w", pady=2)
            self._pills[name] = pill
        Hint(
            body,
            "Runs one apt-get install -y -qq with DEBIAN_FRONTEND=noninteractive. Output streams to the log; "
            "the step cannot be stopped mid-apt.",
        ).pack(anchor="w", pady=(12, 0))

    def load(self, plan: "InstallPlan") -> None:
        """Nothing to load: the page has no inputs."""

    def store(self, plan: "InstallPlan") -> None:
        """Nothing to store: the page has no inputs."""

    def refresh(self, state: Any) -> None:
        """Update the header (base class) and the present/missing pills."""
        try:
            super().refresh(state)
        except NotImplementedError:
            pass
        for name, verdict in package_states(state, self._pills).items():
            self._pills[name].configure(text=verdict, foreground=_PILL_COLOURS[verdict])

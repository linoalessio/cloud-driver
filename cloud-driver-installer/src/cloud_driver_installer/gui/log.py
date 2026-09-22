"""The log console: every line the installer produces, already redacted."""

from __future__ import annotations

import time
import tkinter as tk
from tkinter import filedialog, ttk

from cloud_driver_installer.gui.widgets import COLORS, SPACE, Check

#: Lines kept in memory (and on screen); older ones scroll out of the buffer.
MAX_LINES = 20000

LEVEL_COLORS = {
    "DEBUG": "#7C8493",
    "INFO": "#6FA8FF",
    "OK": "#5FD08B",
    "WARN": "#F0B45E",
    "ERROR": "#FF7B72",
}


class LogPane(ttk.Frame):
    """A dark, filterable transcript with Copy and Save."""

    def __init__(self, parent: tk.Misc) -> None:
        super().__init__(parent)
        self._lines: list[tuple[str, str, str]] = []  # (timestamp, level, message)
        self._show_debug = tk.BooleanVar(value=False)

        bar = ttk.Frame(self, padding=(SPACE["md"], SPACE["sm"], SPACE["md"], SPACE["sm"]))
        bar.pack(fill="x")
        ttk.Label(bar, text="LOG", style="Faint.TLabel").pack(side="left", padx=(0, SPACE["md"]))
        Check(bar, text="show command output", variable=self._show_debug, background=COLORS["paper"], command=self._rerender).pack(side="left")
        ttk.Button(bar, text="Save…", style="Ghost.TButton", command=self._save).pack(side="right")
        ttk.Button(bar, text="Copy", style="Ghost.TButton", command=self._copy).pack(side="right", padx=SPACE["sm"])

        self.text = tk.Text(
            self,
            height=10,
            wrap="none",
            background=COLORS["log_bg"],
            foreground=COLORS["log_ink"],
            insertbackground=COLORS["log_ink"],
            selectbackground="#2C3038",
            font="TkFixedFont",
            state="disabled",
            borderwidth=0,
            highlightthickness=0,
            padx=SPACE["md"],
            pady=SPACE["sm"],
            spacing1=1,
        )
        scrollbar = ttk.Scrollbar(self, orient="vertical", command=self.text.yview)
        self.text.configure(yscrollcommand=scrollbar.set)
        self.text.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        for level, color in LEVEL_COLORS.items():
            self.text.tag_configure(level, foreground=color)
        self.text.tag_configure("TS", foreground="#8E8E93")

    # --- public ----------------------------------------------------------------------------------

    def append(self, level: str, message: str) -> None:
        """Add one line (already redacted by the worker)."""
        stamp = time.strftime("%H:%M:%S")
        self._lines.append((stamp, level, message))
        if len(self._lines) > MAX_LINES:
            del self._lines[: len(self._lines) - MAX_LINES]
            self._rerender()
            return
        if level == "DEBUG" and not self._show_debug.get():
            return
        self._write(stamp, level, message)

    def clear(self) -> None:
        """Drop every line."""
        self._lines.clear()
        self._rerender()

    # --- internals -------------------------------------------------------------------------------

    def _write(self, stamp: str, level: str, message: str) -> None:
        at_bottom = self.text.yview()[1] > 0.99
        self.text.configure(state="normal")
        self.text.insert("end", stamp + " ", "TS")
        prefix = {"INFO": "==> ", "OK": "OK   ", "WARN": "WARN ", "ERROR": "ERROR", "DEBUG": "  "}.get(level, "")
        self.text.insert("end", f"{prefix}{message}\n", level)
        self.text.configure(state="disabled")
        if at_bottom:
            self.text.see("end")

    def _rerender(self) -> None:
        self.text.configure(state="normal")
        self.text.delete("1.0", "end")
        self.text.configure(state="disabled")
        for stamp, level, message in self._lines:
            if level == "DEBUG" and not self._show_debug.get():
                continue
            self._write(stamp, level, message)

    def _plain(self) -> str:
        return "\n".join(f"{stamp} {level} {message}" for stamp, level, message in self._lines)

    def _copy(self) -> None:
        self.clipboard_clear()
        self.clipboard_append(self._plain())

    def _save(self) -> None:
        path = filedialog.asksaveasfilename(defaultextension=".log", initialfile="cloud-driver-install.log")
        if path:
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(self._plain() + "\n")

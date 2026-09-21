"""The dark log console pinned under the pages.

:class:`LogPane` keeps every line it was ever given (up to :data:`MAX_LINES`) in memory and
renders the ones the filter allows: DEBUG lines - the ``$ command`` echoes and streamed command
output - are hidden until "show command output" is ticked, and re-rendered from memory on toggle.
Lines arrive already redacted (the worker does that before queueing them); this widget adds only
a timestamp and colour.
"""

from __future__ import annotations

import time
import tkinter as tk
from collections import deque
from dataclasses import dataclass
from tkinter import filedialog, messagebox, ttk
from typing import Deque

from cloud_driver_installer.gui.widgets import COLORS, copy_to_clipboard, fonts

#: Lines kept in memory and in the text widget.
MAX_LINES = 20_000

#: Levels in the order the worker emits them.
LEVELS: tuple[str, ...] = ("DEBUG", "INFO", "WARN", "ERROR", "OK")

#: ``level -> (prefix shown, prefix tag, message tag)``.
_PREFIX: dict[str, tuple[str, str, str]] = {
    "INFO": ("==>", "h", "m"),
    "WARN": ("WARN", "w", "w"),
    "ERROR": ("ERROR", "e", "e"),
    "OK": ("OK", "g", "m"),
    "DEBUG": ("", "d", "d"),
}


@dataclass
class LogLine:
    """One entry: wall-clock time, level and (redacted) message."""

    when: float
    level: str
    message: str

    @property
    def stamp(self) -> str:
        """``HH:MM:SS`` local time."""
        return time.strftime("%H:%M:%S", time.localtime(self.when))

    def as_text(self) -> str:
        """The plain-text form used by Copy/Save."""
        prefix = _PREFIX.get(self.level, _PREFIX["INFO"])[0]
        head = f"{self.stamp} {prefix + ' ' if prefix else '  '}"
        lines = self.message.splitlines() or [""]
        indent = " " * len(head)
        return "\n".join([head + lines[0], *(indent + line for line in lines[1:])])


class LogPane(ttk.Frame):
    """Bar (Log · level · show command output · Copy · Save…) over a dark, tagged ``tk.Text``."""

    def __init__(self, parent: tk.Misc, *, height_lines: int = 9, **kwargs: object) -> None:
        super().__init__(parent, **kwargs)  # type: ignore[arg-type]
        self._lines: Deque[LogLine] = deque(maxlen=MAX_LINES)
        self.show_debug = tk.BooleanVar(master=self, value=False)
        f = fonts(self)

        bar = tk.Frame(self, background=COLORS["log_bg"], padx=10, pady=3)
        bar.pack(side="top", fill="x")
        tk.Label(bar, text="Log", background=COLORS["log_bg"], foreground=COLORS["log_ink"], font=f.small).pack(side="left")
        self.level_label = tk.Label(bar, text="INFO", background=COLORS["log_bg"], foreground=COLORS["log_muted"], font=f.small)
        self.level_label.pack(side="left", padx=(10, 0))
        self.debug_box = tk.Checkbutton(
            bar,
            text="show command output",
            variable=self.show_debug,
            command=self._on_toggle_debug,
            background=COLORS["log_bg"],
            foreground=COLORS["log_muted"],
            activebackground=COLORS["log_bg"],
            activeforeground=COLORS["log_ink"],
            selectcolor=COLORS["log_bg"],
            highlightthickness=0,
            font=f.small,
        )
        self.debug_box.pack(side="left", padx=(12, 0))
        self.save_button = tk.Button(bar, text="Save…", command=self.save, font=f.small, highlightthickness=0)
        self.save_button.pack(side="right")
        self.copy_button = tk.Button(bar, text="Copy", command=self.copy, font=f.small, highlightthickness=0)
        self.copy_button.pack(side="right", padx=(0, 6))

        body = tk.Frame(self, background=COLORS["log_bg"])
        body.pack(side="top", fill="both", expand=True)
        self.text = tk.Text(
            body,
            height=height_lines,
            wrap="none",
            background=COLORS["log_bg"],
            foreground=COLORS["log_ink"],
            insertbackground=COLORS["log_ink"],
            selectbackground="#3A3A3C",
            highlightthickness=0,
            borderwidth=0,
            padx=10,
            pady=6,
            font=f.mono_small,
            state="disabled",
            cursor="arrow",
        )
        self.vbar = ttk.Scrollbar(body, orient="vertical", command=self.text.yview)
        self.hbar = ttk.Scrollbar(body, orient="horizontal", command=self.text.xview)
        self.text.configure(yscrollcommand=self.vbar.set, xscrollcommand=self.hbar.set)
        self.vbar.pack(side="right", fill="y")
        self.hbar.pack(side="bottom", fill="x")
        self.text.pack(side="left", fill="both", expand=True)

        self.text.tag_configure("ts", foreground=COLORS["log_muted"])
        self.text.tag_configure("h", foreground=COLORS["blue"], font=f.mono_small)
        self.text.tag_configure("m", foreground=COLORS["log_ink"])
        self.text.tag_configure("w", foreground=COLORS["log_warn"])
        self.text.tag_configure("e", foreground=COLORS["log_fail"])
        self.text.tag_configure("g", foreground=COLORS["log_ok"])
        self.text.tag_configure("d", foreground=COLORS["log_muted"])

    # --- content ---------------------------------------------------------------------------------

    @property
    def lines(self) -> list[LogLine]:
        """Every line in memory, oldest first (DEBUG included)."""
        return list(self._lines)

    def visible_lines(self) -> list[LogLine]:
        """The lines the current filter shows."""
        return [line for line in self._lines if self._shown(line)]

    def append(self, level: str, message: str, *, when: float | None = None) -> None:
        """Add one line (rendered immediately when the filter shows it)."""
        line = LogLine(when if when is not None else time.time(), level.upper(), message)
        self._lines.append(line)
        if self._shown(line):
            self._render(line)

    def clear(self) -> None:
        """Forget everything."""
        self._lines.clear()
        self._set_text("")

    def _shown(self, line: LogLine) -> bool:
        return line.level != "DEBUG" or bool(self.show_debug.get())

    # --- rendering -------------------------------------------------------------------------------

    def _at_bottom(self) -> bool:
        return float(self.text.yview()[1]) >= 0.999

    def _render(self, line: LogLine) -> None:
        follow = self._at_bottom()
        self.text.configure(state="normal")
        self._insert(line)
        self._trim()
        self.text.configure(state="disabled")
        if follow:
            self.text.see("end")

    def _insert(self, line: LogLine) -> None:
        prefix, prefix_tag, message_tag = _PREFIX.get(line.level, _PREFIX["INFO"])
        parts = line.message.splitlines() or [""]
        self.text.insert("end", line.stamp + " ", ("ts",))
        if prefix:
            self.text.insert("end", prefix + " ", (prefix_tag,))
        else:
            self.text.insert("end", "  ", (prefix_tag,))
        self.text.insert("end", parts[0] + "\n", (message_tag,))
        indent = " " * (len(line.stamp) + 1 + (len(prefix) + 1 if prefix else 2))
        for extra in parts[1:]:
            self.text.insert("end", indent + extra + "\n", (message_tag,))

    def _trim(self) -> None:
        total = int(self.text.index("end-1c").split(".")[0])
        if total > MAX_LINES:
            self.text.delete("1.0", f"{total - MAX_LINES + 1}.0")

    def _set_text(self, text: str) -> None:
        self.text.configure(state="normal")
        self.text.delete("1.0", "end")
        if text:
            self.text.insert("end", text)
        self.text.configure(state="disabled")

    def rerender(self) -> None:
        """Rebuild the text widget from memory with the current filter."""
        follow = self._at_bottom()
        self.text.configure(state="normal")
        self.text.delete("1.0", "end")
        for line in self._lines:
            if self._shown(line):
                self._insert(line)
        self._trim()
        self.text.configure(state="disabled")
        if follow:
            self.text.see("end")

    def _on_toggle_debug(self) -> None:
        self.level_label.configure(text="DEBUG" if self.show_debug.get() else "INFO")
        self.rerender()

    # --- actions ---------------------------------------------------------------------------------

    def visible_text(self) -> str:
        """The shown lines as plain text."""
        return "\n".join(line.as_text() for line in self.visible_lines())

    def full_text(self) -> str:
        """Every line (DEBUG included) as plain text."""
        return "\n".join(line.as_text() for line in self._lines)

    def copy(self) -> None:
        """Copy the visible log to the clipboard."""
        copy_to_clipboard(self, self.visible_text())

    def save(self) -> None:
        """Save the complete log (command output included) to a file the operator picks."""
        path = filedialog.asksaveasfilename(
            parent=self,
            title="Save installer log",
            defaultextension=".log",
            initialfile=time.strftime("cloud-driver-installer-%Y%m%d-%H%M%S.log"),
            filetypes=[("Log files", "*.log"), ("Text files", "*.txt"), ("All files", "*")],
        )
        if not path:
            return
        try:
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(self.full_text() + "\n")
        except OSError as exc:
            messagebox.showerror("Save log", f"Could not write {path}:\n{exc}", parent=self)

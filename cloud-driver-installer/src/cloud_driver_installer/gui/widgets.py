"""Small ttk building blocks shared by every page.

The palette is the desktop app's own (``Theme.kt``) so the installer looks like part of the family.
"""

from __future__ import annotations

import tkinter as tk
from tkinter import filedialog, ttk
from typing import Callable

from cloud_driver_installer.sizing import GIB, format_bytes

#: Light palette, mirroring the desktop client's theme.
COLORS = {
    "paper": "#F5F5F7",
    "surface": "#FFFFFF",
    "sunken": "#EEEEF1",
    "ink": "#1D1D1F",
    "muted": "#6E6E73",
    "line": "#D2D2D7",
    "blue": "#0A84FF",
    "ok": "#2FA84F",
    "warn": "#D9840A",
    "fail": "#E5342A",
    "skip": "#A1A1A6",
    "log_bg": "#1D1D1F",
    "log_ink": "#E8E8ED",
}

#: Status name -> dot colour (``StepStatus.value``).
STATUS_COLORS = {
    "pending": "",
    "checking": COLORS["blue"],
    "ok": COLORS["ok"],
    "needs_apply": COLORS["warn"],
    "running": COLORS["blue"],
    "done": COLORS["ok"],
    "failed": COLORS["fail"],
    "skipped": COLORS["skip"],
}


def init_styles(root: tk.Misc) -> None:
    """Install the named styles the widgets below use."""
    style = ttk.Style(root)
    try:
        style.theme_use("aqua")
    except tk.TclError:
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
    style.configure("Muted.TLabel", foreground=COLORS["muted"])
    style.configure("Hint.TLabel", foreground=COLORS["muted"], font=("TkDefaultFont", 10))
    style.configure("Head.TLabel", font=("TkDefaultFont", 15, "bold"))
    style.configure("Section.TLabelframe.Label", foreground=COLORS["muted"])
    style.configure("Mono.TLabel", font="TkFixedFont")
    style.configure("Ok.TLabel", foreground=COLORS["ok"])
    style.configure("Warn.TLabel", foreground=COLORS["warn"])
    style.configure("Fail.TLabel", foreground=COLORS["fail"])
    style.configure("Primary.TButton", foreground=COLORS["blue"])


class ScrollFrame(ttk.Frame):
    """A vertically scrollable container; put content into :attr:`body`."""

    def __init__(self, parent: tk.Misc) -> None:
        super().__init__(parent)
        canvas = tk.Canvas(self, highlightthickness=0, background=COLORS["surface"])
        scrollbar = ttk.Scrollbar(self, orient="vertical", command=canvas.yview)
        self.body = ttk.Frame(canvas)
        window = canvas.create_window((0, 0), window=self.body, anchor="nw")
        canvas.configure(yscrollcommand=scrollbar.set)
        canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        self.body.bind("<Configure>", lambda _event: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda event: canvas.itemconfigure(window, width=event.width))
        canvas.bind_all("<MouseWheel>", lambda event: canvas.yview_scroll(int(-event.delta / 3), "units"), add="+")
        canvas.bind_all("<Button-4>", lambda _event: canvas.yview_scroll(-3, "units"), add="+")
        canvas.bind_all("<Button-5>", lambda _event: canvas.yview_scroll(3, "units"), add="+")


class Form(ttk.Frame):
    """A two-column label/field grid with optional hint lines, like the mockup's forms."""

    def __init__(self, parent: tk.Misc) -> None:
        super().__init__(parent)
        self.columnconfigure(1, weight=1)
        self._row = 0

    def row(self, label: str, widget: tk.Widget, hint: str = "") -> tk.Widget:
        """Add ``widget`` under ``label`` (an empty label spans the field column)."""
        if label:
            ttk.Label(self, text=label, style="Muted.TLabel").grid(row=self._row, column=0, sticky="e", padx=(0, 10), pady=3)
        widget.grid(row=self._row, column=1, sticky="ew", pady=3)
        self._row += 1
        if hint:
            ttk.Label(self, text=hint, style="Hint.TLabel", wraplength=560, justify="left").grid(
                row=self._row, column=1, sticky="w", pady=(0, 4)
            )
            self._row += 1
        return widget

    def entry(self, label: str, variable: tk.Variable, hint: str = "", width: int = 32) -> ttk.Entry:
        """A plain text entry bound to ``variable``."""
        widget = ttk.Entry(self, textvariable=variable, width=width, font="TkFixedFont")
        self.row(label, widget, hint)
        return widget

    def check(self, text: str, variable: tk.BooleanVar, hint: str = "") -> ttk.Checkbutton:
        """A checkbox spanning the field column."""
        widget = ttk.Checkbutton(self, text=text, variable=variable)
        self.row("", widget, hint)
        return widget

    def combo(self, label: str, variable: tk.Variable, values: "list[str] | tuple[str, ...]", hint: str = "") -> ttk.Combobox:
        """An editable combobox."""
        widget = ttk.Combobox(self, textvariable=variable, values=list(values), width=30)
        self.row(label, widget, hint)
        return widget

    def radios(self, label: str, variable: tk.StringVar, options: "list[tuple[str, str]]", hint: str = "", command: Callable[[], None] | None = None) -> ttk.Frame:
        """A row of radio buttons: ``options`` is ``[(value, text), …]``."""
        holder = ttk.Frame(self)
        for value, text in options:
            ttk.Radiobutton(holder, text=text, value=value, variable=variable, command=command).pack(side="left", padx=(0, 12))
        self.row(label, holder, hint)
        return holder

    def secret(self, label: str, variable: tk.StringVar, hint: str = "", generate: Callable[[], str] | None = None) -> ttk.Frame:
        """A masked entry with Show and (optionally) Generate."""
        holder = ttk.Frame(self)
        entry = ttk.Entry(holder, textvariable=variable, show="•", width=40, font="TkFixedFont")
        entry.pack(side="left", fill="x", expand=True)

        def toggle() -> None:
            entry.configure(show="" if entry.cget("show") else "•")

        ttk.Button(holder, text="Show", width=6, command=toggle).pack(side="left", padx=4)
        if generate is not None:
            ttk.Button(holder, text="Generate", width=9, command=lambda: variable.set(generate())).pack(side="left")
        self.row(label, holder, hint)
        return holder

    def path(self, label: str, variable: tk.StringVar, hint: str = "", directory: bool = False) -> ttk.Frame:
        """An entry with a Browse… button."""
        holder = ttk.Frame(self)
        ttk.Entry(holder, textvariable=variable, font="TkFixedFont").pack(side="left", fill="x", expand=True)

        def browse() -> None:
            chosen = filedialog.askdirectory() if directory else filedialog.askopenfilename()
            if chosen:
                variable.set(chosen)

        ttk.Button(holder, text="Browse…", width=9, command=browse).pack(side="left", padx=4)
        self.row(label, holder, hint)
        return holder

    def bytes_row(self, label: str, variable: tk.StringVar, hint: str = "") -> ttk.Frame:
        """A GiB entry that shows the exact byte count it writes."""
        holder = ttk.Frame(self)
        ttk.Entry(holder, textvariable=variable, width=10, font="TkFixedFont").pack(side="left")
        ttk.Label(holder, text="GiB", style="Muted.TLabel").pack(side="left", padx=4)
        readout = ttk.Label(holder, text="", style="Hint.TLabel")
        readout.pack(side="left", padx=6)

        def update(*_args: object) -> None:
            try:
                readout.configure(text=f"= {int(float(variable.get()) * GIB)} bytes")
            except (TypeError, ValueError):
                readout.configure(text="enter a number")

        variable.trace_add("write", update)
        update()
        self.row(label, holder, hint)
        return holder

    def readonly(self, label: str, text: str, hint: str = "") -> ttk.Label:
        """A value the operator cannot change (derived or discovered)."""
        widget = ttk.Label(self, text=text, style="Mono.TLabel")
        self.row(label, widget, hint)
        return widget


class StatusDot(tk.Canvas):
    """The coloured dot in front of a step."""

    def __init__(self, parent: tk.Misc, size: int = 10) -> None:
        super().__init__(parent, width=size, height=size, highlightthickness=0, background=COLORS["sunken"])
        self._item = self.create_oval(1, 1, size - 1, size - 1, outline=COLORS["line"], fill="")

    def set_status(self, status: str) -> None:
        """Paint the dot for a :class:`~cloud_driver_installer.engine.StepStatus` value."""
        self.itemconfigure(self._item, fill=STATUS_COLORS.get(status, ""))


class FactGrid(ttk.Frame):
    """A read-only grid of discovered facts."""

    def __init__(self, parent: tk.Misc, columns: int = 3) -> None:
        super().__init__(parent)
        self._columns = columns
        for column in range(columns):
            self.columnconfigure(column, weight=1)
        self._labels: dict[str, ttk.Label] = {}

    def set_facts(self, facts: "list[tuple[str, str]]") -> None:
        """Replace the grid's contents."""
        for child in self.winfo_children():
            child.destroy()
        for index, (name, value) in enumerate(facts):
            cell = ttk.Frame(self)
            cell.grid(row=index // self._columns, column=index % self._columns, sticky="ew", padx=4, pady=3)
            ttk.Label(cell, text=name.upper(), style="Hint.TLabel").pack(anchor="w")
            ttk.Label(cell, text=value or "—", style="Mono.TLabel").pack(anchor="w")


def section(parent: tk.Misc, title: str) -> ttk.LabelFrame:
    """A titled group box."""
    frame = ttk.LabelFrame(parent, text=title, style="Section.TLabelframe")
    frame.pack(fill="x", pady=6, padx=2, ipady=4)
    return frame


def note(parent: tk.Misc, text: str, style: str = "Hint.TLabel") -> ttk.Label:
    """An explanatory paragraph under a field group."""
    label = ttk.Label(parent, text=text, style=style, wraplength=620, justify="left")
    label.pack(anchor="w", padx=8, pady=(2, 6))
    return label


def human_bytes(value: int) -> str:
    """``format_bytes`` re-exported for pages."""
    return format_bytes(value)

"""Small ttk composites shared by every page, the connect dialog and the main window.

The visual vocabulary comes from the design mockup: a 170 px right-aligned label column, small
grey hints under fields, uppercase small section headings, six status colours, monospace for
every path/port/key. ttk cannot paint everything the mockup does (pill backgrounds, rounded
corners); what it can do is done through the named styles :func:`apply_styles` configures, and
every widget here falls back to plain ttk defaults when those styles were never applied.

Nothing in this module talks to the server or the plan: widgets read and write plain values
(``get_value``/``set_value``) and the pages bind them to the plan.
"""

from __future__ import annotations

import sys
import tkinter as tk
import tkinter.font as tkfont
import weakref
from tkinter import filedialog, ttk
from typing import Callable, Sequence

from cloud_driver_installer.engine import StepStatus
from cloud_driver_installer.sizing import GIB

# --- colours ----------------------------------------------------------------------------------

#: The light palette from the mockup (which mirrors the desktop app's ``Theme.kt``).
COLORS: dict[str, str] = {
    "paper": "#F5F5F7",
    "surface": "#FFFFFF",
    "sunken": "#EEEEF1",
    "ink": "#1D1D1F",
    "muted": "#6E6E73",
    "line": "#D2D2D7",
    "line_soft": "#E5E5EA",
    "blue": "#0A84FF",
    "blue_soft": "#D9EBFF",
    "ok": "#2FA84F",
    "warn": "#D9840A",
    "fail": "#E5342A",
    "skip": "#A1A1A6",
    "log_bg": "#1D1D1F",
    "log_ink": "#E8E8ED",
    "log_muted": "#8E8E93",
    "log_warn": "#FFB340",
    "log_fail": "#FF6961",
    "log_ok": "#5DD37A",
}

#: Status -> one of the six dot kinds (``pend``, ``run``, ``ok``, ``warn``, ``fail``, ``skip``).
STATUS_KIND: dict[StepStatus, str] = {
    StepStatus.PENDING: "pend",
    StepStatus.CHECKING: "run",
    StepStatus.RUNNING: "run",
    StepStatus.OK: "ok",
    StepStatus.DONE: "ok",
    StepStatus.NEEDS_APPLY: "warn",
    StepStatus.FAILED: "fail",
    StepStatus.SKIPPED: "skip",
}

#: Dot kind -> colour.
KIND_COLORS: dict[str, str] = {
    "pend": COLORS["line"],
    "run": COLORS["blue"],
    "ok": COLORS["ok"],
    "warn": COLORS["warn"],
    "fail": COLORS["fail"],
    "skip": COLORS["skip"],
}

#: Status -> the pill wording the mockup uses.
STATUS_LABELS: dict[StepStatus, str] = {
    StepStatus.PENDING: "Not checked",
    StepStatus.CHECKING: "Checking…",
    StepStatus.OK: "Ready",
    StepStatus.NEEDS_APPLY: "Needs apply",
    StepStatus.RUNNING: "Running…",
    StepStatus.DONE: "Done",
    StepStatus.FAILED: "Failed",
    StepStatus.SKIPPED: "Skipped",
}


def status_kind(status: StepStatus) -> str:
    """The dot kind for ``status``."""
    return STATUS_KIND.get(status, "pend")


def status_color(status: StepStatus) -> str:
    """The colour for ``status``."""
    return KIND_COLORS[status_kind(status)]


def status_label(status: StepStatus, detail: str = "") -> str:
    """The pill text for ``status``; a step skipped because it was unticked reads "Not selected"."""
    if status is StepStatus.SKIPPED and detail.strip().lower().startswith("not selected"):
        return "Not selected"
    return STATUS_LABELS.get(status, status.value)


def format_elapsed(seconds: float) -> str:
    """``0.8s``, ``41s``, ``1m 12s`` - the run-list notation."""
    if seconds <= 0:
        return ""
    if seconds < 10:
        return f"{seconds:.1f}s"
    if seconds < 60:
        return f"{int(round(seconds))}s"
    minutes, rest = divmod(int(round(seconds)), 60)
    return f"{minutes}m {rest:02d}s" if rest else f"{minutes}m"


# --- fonts ------------------------------------------------------------------------------------


class Fonts:
    """The named fonts derived from the platform defaults, one set per Tk interpreter."""

    def __init__(self, root: tk.Misc) -> None:
        base = tkfont.nametofont("TkDefaultFont", root)
        size = abs(int(base.cget("size") or 13))
        self.ui: tkfont.Font = base
        self.small: tkfont.Font = base.copy()
        self.small.configure(size=max(size - 2, 8))
        self.bold: tkfont.Font = base.copy()
        self.bold.configure(weight="bold")
        self.heading: tkfont.Font = base.copy()
        self.heading.configure(size=max(size - 2, 8), weight="bold")
        self.title: tkfont.Font = base.copy()
        self.title.configure(size=size + 5, weight="bold")
        mono = tkfont.nametofont("TkFixedFont", root)
        mono_size = abs(int(mono.cget("size") or size))
        self.mono: tkfont.Font = mono.copy()
        self.mono.configure(size=max(mono_size, size - 1))
        self.mono_small: tkfont.Font = mono.copy()
        self.mono_small.configure(size=max(min(mono_size, size - 2), 8))


_FONTS: "weakref.WeakKeyDictionary[tk.Misc, Fonts]" = weakref.WeakKeyDictionary()


def fonts(widget: tk.Misc) -> Fonts:
    """The :class:`Fonts` of ``widget``'s interpreter (created on first use)."""
    root = widget.nametowidget(".")
    try:
        return _FONTS[root]
    except KeyError:
        created = Fonts(root)
        _FONTS[root] = created
        return created


# --- styles -----------------------------------------------------------------------------------

STYLE_TITLE = "Title.TLabel"
STYLE_HEADING = "Heading.TLabel"
STYLE_MUTED = "Muted.TLabel"
STYLE_HINT = "Hint.TLabel"
STYLE_MONO = "Mono.TLabel"
STYLE_ERROR = "Error.TLabel"
STYLE_PILL = "Pill.TLabel"
STYLE_FACT_LABEL = "Fact.TLabel"
STYLE_FACT_VALUE = "FactValue.TLabel"
STYLE_PRIMARY = "Primary.TButton"
STYLE_DANGER = "Danger.TButton"
STYLE_SECTION = "Section.TLabelframe"
STYLE_SIDEBAR = "Sidebar.TFrame"
STYLE_SIDEBAR_ACTIVE = "SidebarActive.TFrame"
STYLE_SURFACE = "Surface.TFrame"
STYLE_LINK = "Link.TLabel"


def apply_styles(root: tk.Misc) -> ttk.Style:
    """Configure every named style the widgets use; idempotent, returns the :class:`ttk.Style`."""
    style = ttk.Style(root)
    f = fonts(root)
    style.configure(STYLE_TITLE, font=f.title)
    style.configure(STYLE_HEADING, font=f.heading, foreground=COLORS["muted"])
    style.configure(STYLE_MUTED, foreground=COLORS["muted"])
    style.configure(STYLE_HINT, font=f.small, foreground=COLORS["muted"])
    style.configure(STYLE_MONO, font=f.mono)
    style.configure(STYLE_ERROR, foreground=COLORS["fail"])
    style.configure(STYLE_PILL, font=f.small)
    style.configure(STYLE_FACT_LABEL, font=f.small, foreground=COLORS["muted"])
    style.configure(STYLE_FACT_VALUE, font=f.mono)
    style.configure(STYLE_LINK, foreground=COLORS["blue"])
    style.configure(STYLE_PRIMARY, font=f.bold)
    style.configure(STYLE_DANGER, foreground=COLORS["fail"])
    style.configure(STYLE_SECTION, padding=(12, 6, 12, 10))
    style.configure(STYLE_SECTION + ".Label", font=f.heading, foreground=COLORS["muted"])
    style.configure(STYLE_SIDEBAR, background=COLORS["sunken"])
    style.configure(STYLE_SIDEBAR_ACTIVE, background=COLORS["surface"])
    style.configure(STYLE_SURFACE, background=COLORS["surface"])
    return style


def set_enabled(widget: tk.Misc, enabled: bool) -> None:
    """Enable/disable a ttk or classic widget uniformly."""
    if isinstance(widget, ttk.Widget):
        widget.state(["!disabled"] if enabled else ["disabled"])
    else:
        try:
            widget.configure(state=(tk.NORMAL if enabled else tk.DISABLED))  # type: ignore[call-arg]
        except tk.TclError:
            pass


def copy_to_clipboard(widget: tk.Misc, text: str) -> None:
    """Put ``text`` on the system clipboard."""
    widget.clipboard_clear()
    widget.clipboard_append(text)


# --- layout helpers ----------------------------------------------------------------------------


class Hint(ttk.Label):
    """A small grey label that wraps to the width it is given."""

    def __init__(self, parent: tk.Misc, text: str = "", *, wraplength: int = 640, **kwargs: object) -> None:
        kwargs.setdefault("style", STYLE_HINT)
        kwargs.setdefault("justify", "left")
        kwargs.setdefault("anchor", "w")
        super().__init__(parent, text=text, wraplength=wraplength, **kwargs)  # type: ignore[arg-type]
        self._last_width = 0
        self.bind("<Configure>", self._on_configure, add="+")

    def _on_configure(self, event: "tk.Event[tk.Misc]") -> None:
        width = int(event.width) - 4
        if width > 40 and abs(width - self._last_width) > 2:
            self._last_width = width
            self.configure(wraplength=width)

    def set_text(self, text: str) -> None:
        """Replace the text."""
        self.configure(text=text)


class Field(ttk.Frame):
    """A two-column form grid: 170 px right-aligned labels, stretching inputs, hints beneath.

    Widgets passed to :meth:`add` must be children of the field (or of one of its ancestors);
    :meth:`row` returns an inline frame for the "Host … Port …" style rows where the caller packs
    its own widgets.
    """

    LABEL_WIDTH = 170

    def __init__(self, parent: tk.Misc, **kwargs: object) -> None:
        super().__init__(parent, **kwargs)  # type: ignore[arg-type]
        self.grid_columnconfigure(0, minsize=self.LABEL_WIDTH)
        self.grid_columnconfigure(1, weight=1)
        self._row = 0

    def _label(self, text: str) -> ttk.Label:
        return ttk.Label(self, text=text, anchor="e", justify="right", style=STYLE_MUTED)

    def add(self, label: str, widget: tk.Widget, *, hint: str | None = None, sticky: str = "ew") -> tk.Widget:
        """Place ``label`` and ``widget`` on the next row (``hint`` beneath the widget)."""
        if label:
            self._label(label).grid(row=self._row, column=0, sticky="e", padx=(0, 12), pady=3)
        widget.grid(in_=self, row=self._row, column=1, sticky=sticky, pady=3)
        self._row += 1
        if hint:
            self.hint(hint)
        return widget

    def row(self, label: str = "", *, hint: str | None = None) -> ttk.Frame:
        """An inline frame on the next row for several widgets side by side."""
        frame = ttk.Frame(self)
        self.add(label, frame, hint=hint)
        return frame

    def hint(self, text: str) -> Hint:
        """A hint line spanning the input column."""
        hint = Hint(self, text)
        hint.grid(row=self._row, column=1, sticky="ew", pady=(0, 4))
        self._row += 1
        return hint

    def check(self, text: str, variable: tk.Variable, *, hint: str | None = None, command: Callable[[], None] | None = None) -> ttk.Checkbutton:
        """A checkbutton row with an empty label column (the mockup's ``<label></label>`` rows)."""
        box = ttk.Checkbutton(self, text=text, variable=variable, command=command)
        self.add("", box, hint=hint, sticky="w")
        return box

    def spacer(self, height: int = 6) -> None:
        """Vertical space between groups of rows."""
        ttk.Frame(self, height=height).grid(row=self._row, column=0, columnspan=2)
        self._row += 1


class LabeledEntry(ttk.Frame):
    """``Label  [entry]`` packed inline - for the short "Port" inputs beside a host field."""

    def __init__(
        self,
        parent: tk.Misc,
        label: str,
        variable: tk.Variable | None = None,
        *,
        width: int = 8,
        mono: bool = True,
        **kwargs: object,
    ) -> None:
        super().__init__(parent, **kwargs)  # type: ignore[arg-type]
        self.var: tk.Variable = variable if variable is not None else tk.StringVar(master=self)
        self.label = ttk.Label(self, text=label, style=STYLE_MUTED)
        self.entry = ttk.Entry(self, textvariable=self.var, width=width, font=fonts(self).mono if mono else fonts(self).ui)
        self.label.pack(side="left", padx=(0, 6))
        self.entry.pack(side="left", fill="x", expand=True)

    def get_value(self) -> str:
        """The entry text."""
        return str(self.var.get())

    def set_value(self, value: object) -> None:
        """Set the entry text."""
        self.var.set("" if value is None else str(value))


class SecretEntry(ttk.Frame):
    """A masked entry with a Show/Hide toggle, an optional Generate button and a side note.

    ``on_generate`` is called when Generate is pressed; when it returns a string that value is
    put into the field (the page decides what else generating implies, e.g. ticking rotate).
    """

    MASK_CHAR = "•"

    def __init__(
        self,
        parent: tk.Misc,
        variable: tk.StringVar | None = None,
        *,
        on_generate: Callable[[], str | None] | None = None,
        width: int = 44,
        generate_label: str = "Generate",
        **kwargs: object,
    ) -> None:
        super().__init__(parent, **kwargs)  # type: ignore[arg-type]
        self.var: tk.StringVar = variable if variable is not None else tk.StringVar(master=self)
        self._visible = False
        self._on_generate = on_generate
        self.entry = ttk.Entry(self, textvariable=self.var, show=self.MASK_CHAR, width=width, font=fonts(self).mono)
        self.entry.pack(side="left", fill="x", expand=True)
        self.toggle_button = ttk.Button(self, text="Show", width=5, command=self.toggle_visibility)
        self.toggle_button.pack(side="left", padx=(6, 0))
        self.generate_button: ttk.Button | None = None
        if on_generate is not None:
            self.generate_button = ttk.Button(self, text=generate_label, command=self._generate)
            self.generate_button.pack(side="left", padx=(6, 0))
        self.note = ttk.Label(self, text="", style=STYLE_HINT)
        self.note.pack(side="left", padx=(8, 0))

    @property
    def visible(self) -> bool:
        """True while the value is shown in clear."""
        return self._visible

    def reveal(self, visible: bool) -> None:
        """Show or mask the value."""
        self._visible = visible
        self.entry.configure(show="" if visible else self.MASK_CHAR)
        self.toggle_button.configure(text="Hide" if visible else "Show")

    def toggle_visibility(self) -> None:
        """Flip between shown and masked."""
        self.reveal(not self._visible)

    def set_note(self, text: str) -> None:
        """The small text right of the field ("kept from server", "rotating")."""
        self.note.configure(text=text)

    def _generate(self) -> None:
        if self._on_generate is None:
            return
        value = self._on_generate()
        if value:
            self.var.set(value)

    def get_value(self) -> str:
        """The secret."""
        return self.var.get()

    def set_value(self, value: object) -> None:
        """Set the secret."""
        self.var.set("" if value is None else str(value))


class IntEntry(ttk.Entry):
    """An entry that only accepts digits; :meth:`get_value` enforces the ``[minimum, maximum]`` range."""

    def __init__(
        self,
        parent: tk.Misc,
        variable: tk.StringVar | None = None,
        *,
        minimum: int | None = None,
        maximum: int | None = None,
        width: int = 8,
        allow_blank: bool = False,
        **kwargs: object,
    ) -> None:
        self.var: tk.StringVar = variable if variable is not None else tk.StringVar(master=parent)
        self.minimum = minimum
        self.maximum = maximum
        self.allow_blank = allow_blank
        kwargs.setdefault("font", fonts(parent).mono)
        super().__init__(parent, textvariable=self.var, width=width, **kwargs)  # type: ignore[arg-type]
        digits = len(str(maximum)) if maximum is not None else 12
        self._validator = self.register(lambda proposed: self._accept(proposed, digits))
        self.configure(validate="key", validatecommand=(self._validator, "%P"))

    @staticmethod
    def _accept(proposed: str, digits: int) -> bool:
        return proposed == "" or (proposed.isdigit() and len(proposed) <= digits)

    def get_value(self) -> int | None:
        """The number (``None`` for a blank field when ``allow_blank``); ``ValueError`` otherwise."""
        text = self.var.get().strip()
        if not text:
            if self.allow_blank:
                return None
            raise ValueError("must be a whole number")
        if not text.isdigit():
            raise ValueError("must be a whole number")
        value = int(text)
        if self.minimum is not None and value < self.minimum:
            raise ValueError(f"must be at least {self.minimum}")
        if self.maximum is not None and value > self.maximum:
            raise ValueError(f"must be at most {self.maximum}")
        return value

    def set_value(self, value: object) -> None:
        """Show ``value`` (``None``/blank clears)."""
        self.var.set("" if value is None or value == "" else str(int(value)))  # type: ignore[call-overload]


class PortEntry(IntEntry):
    """A TCP port field: 1-65535."""

    def __init__(self, parent: tk.Misc, variable: tk.StringVar | None = None, *, width: int = 7, **kwargs: object) -> None:
        super().__init__(parent, variable, minimum=1, maximum=65535, width=width, **kwargs)

    def get_value(self) -> int:  # type: ignore[override]
        """The port; ``ValueError("must be 1-65535")`` when it is not one."""
        try:
            value = super().get_value()
        except ValueError:
            raise ValueError("must be 1-65535") from None
        assert value is not None
        return value


class BytesEntry(ttk.Frame):
    """A GiB spinbox with a live "<n> bytes" label; reads and writes an integer byte count."""

    def __init__(self, parent: tk.Misc, *, maximum_gib: int = 1_048_576, width: int = 8, **kwargs: object) -> None:
        super().__init__(parent, **kwargs)  # type: ignore[arg-type]
        self.var = tk.StringVar(master=self, value="0")
        self.spinbox = ttk.Spinbox(self, textvariable=self.var, from_=0, to=maximum_gib, increment=1, width=width, font=fonts(self).mono)
        self.spinbox.pack(side="left")
        ttk.Label(self, text="GiB", style=STYLE_MUTED).pack(side="left", padx=(6, 10))
        self.bytes_label = ttk.Label(self, text="", style=STYLE_HINT, font=fonts(self).mono_small)
        self.bytes_label.pack(side="left")
        self.var.trace_add("write", lambda *_: self._update_label())
        self._update_label()

    @staticmethod
    def _parse_gib(text: str) -> float:
        cleaned = text.strip().replace(",", ".")
        if not cleaned:
            raise ValueError("enter a size in GiB")
        value = float(cleaned)
        if value < 0:
            raise ValueError("must not be negative")
        return value

    def _update_label(self) -> None:
        try:
            self.bytes_label.configure(text=f"{int(self._parse_gib(self.var.get()) * GIB)} bytes")
        except ValueError:
            self.bytes_label.configure(text="")

    def get_value(self) -> int:
        """The byte count; ``ValueError`` when the field is not a number."""
        return int(self._parse_gib(self.var.get()) * GIB)

    def set_value(self, value: object) -> None:
        """Show ``value`` bytes as GiB (whole numbers without decimals)."""
        gib = int(value or 0) / GIB  # type: ignore[call-overload]
        self.var.set(str(int(gib)) if gib == int(gib) else f"{gib:.3f}".rstrip("0").rstrip("."))


class PathPicker(ttk.Frame):
    """A monospace entry plus a Browse… button opening a directory/file chooser."""

    def __init__(
        self,
        parent: tk.Misc,
        variable: tk.StringVar | None = None,
        *,
        kind: str = "dir",
        title: str = "Choose",
        width: int = 48,
        **kwargs: object,
    ) -> None:
        super().__init__(parent, **kwargs)  # type: ignore[arg-type]
        self.var: tk.StringVar = variable if variable is not None else tk.StringVar(master=self)
        self.kind = kind
        self.title = title
        self.entry = ttk.Entry(self, textvariable=self.var, width=width, font=fonts(self).mono)
        self.entry.pack(side="left", fill="x", expand=True)
        self.browse_button = ttk.Button(self, text="Browse…", command=self.browse)
        self.browse_button.pack(side="left", padx=(6, 0))

    def browse(self) -> None:
        """Open the chooser and put the choice into the entry."""
        current = self.var.get().strip()
        if self.kind == "dir":
            chosen = filedialog.askdirectory(parent=self, title=self.title, initialdir=current or None, mustexist=True)
        elif self.kind == "save":
            chosen = filedialog.asksaveasfilename(parent=self, title=self.title, initialfile=current or None)
        else:
            chosen = filedialog.askopenfilename(parent=self, title=self.title, initialfile=current or None)
        if chosen:
            self.var.set(chosen)

    def get_value(self) -> str:
        """The path text (stripped)."""
        return self.var.get().strip()

    def set_value(self, value: object) -> None:
        """Set the path text."""
        self.var.set("" if value is None else str(value))


# --- status ------------------------------------------------------------------------------------


def _background_of(widget: tk.Misc) -> str:
    """The ttk background behind ``widget`` (for canvas-based widgets that must blend in)."""
    parent = widget.master if isinstance(widget.master, tk.Misc) else widget
    try:
        style_name = str(parent.cget("style")) if isinstance(parent, ttk.Widget) else ""
        found = ttk.Style(widget).lookup(style_name or parent.winfo_class(), "background")
        return str(found) if found else COLORS["surface"]
    except tk.TclError:
        return COLORS["surface"]


class StatusDot(tk.Canvas):
    """A 10×10 dot in one of the six status colours (pending draws an outline only)."""

    SIZE = 10

    def __init__(self, parent: tk.Misc, status: StepStatus = StepStatus.PENDING, *, background: str | None = None, **kwargs: object) -> None:
        kwargs.setdefault("highlightthickness", 0)
        kwargs.setdefault("borderwidth", 0)
        super().__init__(parent, width=self.SIZE, height=self.SIZE, background=background or _background_of(parent), **kwargs)  # type: ignore[arg-type]
        self._status = status
        self._item = self.create_oval(1, 1, self.SIZE - 1, self.SIZE - 1, outline="", fill="")
        self.set_status(status)

    @property
    def status(self) -> StepStatus:
        """The status currently drawn."""
        return self._status

    def set_status(self, status: StepStatus) -> None:
        """Redraw for ``status``."""
        self._status = status
        color = status_color(status)
        if status_kind(status) == "pend":
            self.itemconfigure(self._item, fill="", outline=color, width=1.5)
        else:
            self.itemconfigure(self._item, fill=color, outline=color, width=1)


class StatusPill(ttk.Label):
    """``● Needs apply`` in the status colour (ttk has no pill background; the colour carries it)."""

    def __init__(self, parent: tk.Misc, status: StepStatus = StepStatus.PENDING, text: str | None = None, **kwargs: object) -> None:
        kwargs.setdefault("style", STYLE_PILL)
        super().__init__(parent, **kwargs)  # type: ignore[arg-type]
        self._status = status
        self.set_status(status, text)

    @property
    def status(self) -> StepStatus:
        """The status currently shown."""
        return self._status

    def set_status(self, status: StepStatus, text: str | None = None, detail: str = "") -> None:
        """Recolour and relabel (``text`` overrides the standard wording)."""
        self._status = status
        self.configure(text=f"● {text if text is not None else status_label(status, detail)}", foreground=status_color(status))


class Pill(ttk.Label):
    """A small coloured tag such as ``present``/``missing``/``found`` (kinds as in :data:`KIND_COLORS`)."""

    def __init__(self, parent: tk.Misc, text: str = "", kind: str = "pend", **kwargs: object) -> None:
        kwargs.setdefault("style", STYLE_PILL)
        super().__init__(parent, **kwargs)  # type: ignore[arg-type]
        self.set(text, kind)

    def set(self, text: str, kind: str = "pend") -> None:
        """Relabel and recolour; ``kind="pend"`` renders muted."""
        color = COLORS["muted"] if kind == "pend" else KIND_COLORS.get(kind, COLORS["muted"])
        self.configure(text=text, foreground=color)


class RadioRow(ttk.Frame):
    """Radiobuttons side by side bound to one variable; ``options`` are ``(value, label)`` pairs."""

    def __init__(
        self,
        parent: tk.Misc,
        variable: tk.Variable,
        options: Sequence[tuple[str, str]],
        *,
        command: Callable[[], None] | None = None,
        **kwargs: object,
    ) -> None:
        super().__init__(parent, **kwargs)  # type: ignore[arg-type]
        self.var = variable
        self.buttons: list[ttk.Radiobutton] = []
        for value, label in options:
            button = ttk.Radiobutton(self, text=label, value=value, variable=variable, command=command)
            button.pack(side="left", padx=(0, 18))
            self.buttons.append(button)

    def set_enabled(self, enabled: bool) -> None:
        """Enable/disable every button."""
        for button in self.buttons:
            set_enabled(button, enabled)


class Section(ttk.LabelFrame):
    """A group box with the mockup's uppercase small heading."""

    def __init__(self, parent: tk.Misc, title: str, **kwargs: object) -> None:
        kwargs.setdefault("style", STYLE_SECTION)
        kwargs.setdefault("padding", (12, 6, 12, 10))
        super().__init__(parent, text=title.upper(), **kwargs)  # type: ignore[arg-type]


class ScrollFrame(ttk.Frame):
    """A vertically scrolling container: build into :attr:`inner`.

    The mouse wheel scrolls while the pointer is over the frame (Windows/macOS ``<MouseWheel>``,
    X11 buttons 4/5) and only when the content is taller than the view.
    """

    def __init__(self, parent: tk.Misc, **kwargs: object) -> None:
        super().__init__(parent, **kwargs)  # type: ignore[arg-type]
        self.canvas = tk.Canvas(self, highlightthickness=0, borderwidth=0, background=_background_of(self))
        self.scrollbar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=self.scrollbar.set)
        self.scrollbar.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.inner = ttk.Frame(self.canvas)
        self._window = self.canvas.create_window((0, 0), window=self.inner, anchor="nw")
        self.inner.bind("<Configure>", self._on_inner_configure, add="+")
        self.canvas.bind("<Configure>", self._on_canvas_configure, add="+")
        self.canvas.bind("<Enter>", self._bind_wheel, add="+")
        self.canvas.bind("<Leave>", self._unbind_wheel, add="+")

    def _on_inner_configure(self, _event: object) -> None:
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def _on_canvas_configure(self, event: "tk.Event[tk.Canvas]") -> None:
        self.canvas.itemconfigure(self._window, width=event.width)

    def _bind_wheel(self, _event: object) -> None:
        self.canvas.bind_all("<MouseWheel>", self._on_wheel, add="+")
        self.canvas.bind_all("<Button-4>", self._on_wheel, add="+")
        self.canvas.bind_all("<Button-5>", self._on_wheel, add="+")

    def _unbind_wheel(self, _event: object) -> None:
        self.canvas.unbind_all("<MouseWheel>")
        self.canvas.unbind_all("<Button-4>")
        self.canvas.unbind_all("<Button-5>")

    def _on_wheel(self, event: "tk.Event[tk.Misc]") -> None:
        if self.inner.winfo_reqheight() <= self.canvas.winfo_height():
            return
        if getattr(event, "num", None) == 4:
            steps = -1
        elif getattr(event, "num", None) == 5:
            steps = 1
        else:
            delta = int(getattr(event, "delta", 0) or 0)
            if delta == 0:
                return
            steps = -delta if sys.platform == "darwin" else (-1 if delta > 0 else 1)
        self.canvas.yview_scroll(steps, "units")

    def scroll_to_top(self) -> None:
        """Back to the top."""
        self.canvas.yview_moveto(0)


# --- feedback ----------------------------------------------------------------------------------


class StatusLine(ttk.Frame):
    """A dot plus a line of text: "Connecting… checking root, apt and systemd on the host"."""

    KINDS = {"info": "pend", "run": "run", "ok": "ok", "warn": "warn", "fail": "fail", "skip": "skip"}

    def __init__(self, parent: tk.Misc, **kwargs: object) -> None:
        super().__init__(parent, **kwargs)  # type: ignore[arg-type]
        self.dot = StatusDot(self)
        self.dot.pack(side="left", padx=(0, 8))
        self.label = ttk.Label(self, text="", style=STYLE_HINT)
        self.label.pack(side="left", fill="x", expand=True)
        self._clear_job: str | None = None

    def show(self, text: str, kind: str = "info", *, clear_after_ms: int | None = None) -> None:
        """Show ``text`` with the dot in ``kind`` colour (info, run, ok, warn, fail, skip)."""
        status = {
            "pend": StepStatus.PENDING,
            "run": StepStatus.RUNNING,
            "ok": StepStatus.OK,
            "warn": StepStatus.NEEDS_APPLY,
            "fail": StepStatus.FAILED,
            "skip": StepStatus.SKIPPED,
        }[self.KINDS.get(kind, "pend")]
        self.dot.set_status(status)
        self.label.configure(text=text, foreground=COLORS["fail"] if kind == "fail" else COLORS["muted"])
        if self._clear_job is not None:
            self.after_cancel(self._clear_job)
            self._clear_job = None
        if clear_after_ms:
            self._clear_job = self.after(clear_after_ms, self.clear)

    def clear(self) -> None:
        """Blank the line."""
        self._clear_job = None
        self.dot.set_status(StepStatus.PENDING)
        self.label.configure(text="")


def toast(widget: tk.Misc, text: str, *, ms: int = 2500) -> tk.Toplevel:
    """A borderless note near the bottom of ``widget``'s window that disappears after ``ms``."""
    root = widget.winfo_toplevel()
    note = tk.Toplevel(root)
    note.overrideredirect(True)
    try:
        note.attributes("-topmost", True)
    except tk.TclError:
        pass
    frame = tk.Frame(note, background=COLORS["ink"], padx=14, pady=8)
    frame.pack()
    tk.Label(frame, text=text, background=COLORS["ink"], foreground=COLORS["log_ink"], font=fonts(widget).ui).pack()
    note.update_idletasks()
    x = root.winfo_rootx() + (root.winfo_width() - note.winfo_reqwidth()) // 2
    y = root.winfo_rooty() + root.winfo_height() - note.winfo_reqheight() - 40
    note.geometry(f"+{max(x, 0)}+{max(y, 0)}")
    note.after(ms, note.destroy)
    return note


class Tooltip:
    """A hover tooltip; ``text`` may be a string or a callable returning the current text."""

    def __init__(self, widget: tk.Misc, text: str | Callable[[], str], *, delay_ms: int = 500) -> None:
        self.widget = widget
        self.text = text
        self.delay_ms = delay_ms
        self._window: tk.Toplevel | None = None
        self._job: str | None = None
        widget.bind("<Enter>", self._schedule, add="+")
        widget.bind("<Leave>", self._hide, add="+")
        widget.bind("<ButtonPress>", self._hide, add="+")

    def _current_text(self) -> str:
        return self.text() if callable(self.text) else self.text

    def _schedule(self, _event: object) -> None:
        self._cancel()
        self._job = self.widget.after(self.delay_ms, self._show)

    def _cancel(self) -> None:
        if self._job is not None:
            self.widget.after_cancel(self._job)
            self._job = None

    def _show(self) -> None:
        self._job = None
        text = self._current_text()
        if not text or self._window is not None:
            return
        window = tk.Toplevel(self.widget)
        window.overrideredirect(True)
        label = tk.Label(window, text=text, background=COLORS["ink"], foreground=COLORS["log_ink"], font=fonts(self.widget).small, padx=8, pady=4, justify="left")
        label.pack()
        x = self.widget.winfo_rootx() + 12
        y = self.widget.winfo_rooty() + self.widget.winfo_height() + 4
        window.geometry(f"+{x}+{y}")
        self._window = window

    def _hide(self, _event: object = None) -> None:
        self._cancel()
        if self._window is not None:
            self._window.destroy()
            self._window = None

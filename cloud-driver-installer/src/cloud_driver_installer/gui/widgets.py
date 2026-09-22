"""Small ttk building blocks shared by every page - and the design system they follow.

The look is the desktop client's (``Theme.kt``): a light, flat surface palette with one accent
colour, hairline borders instead of bevels, and type that carries the hierarchy rather than boxes
and rules. Everything is styled through ttk's ``clam`` theme on every platform: the native themes
(``aqua`` above all) ignore most colour options, so a window that mixes them ends up half styled.

Three rules keep pages consistent: content lives in a :func:`card`, every label/field pair goes
through :class:`Form`, and nothing sets a colour of its own - it takes one from :data:`COLORS`.
"""

from __future__ import annotations

import tkinter as tk
from tkinter import filedialog, ttk
from typing import Callable

from cloud_driver_installer.sizing import GIB, format_bytes

#: The palette. Light surfaces, one accent, and status colours that stay legible on both.
COLORS = {
    "paper": "#F4F5F7",      # the window behind everything
    "surface": "#FFFFFF",    # cards, fields, the page body
    "sunken": "#EDEFF3",     # hover, previews, disabled fields
    "ink": "#15171C",        # primary text
    "muted": "#6B7280",      # secondary text and labels
    "faint": "#9AA1AC",      # tertiary text: units, placeholders
    "line": "#E1E4EA",       # hairline borders
    "blue": "#0A6CFF",       # the accent (kept under its old name: pages reference it)
    "blue_hover": "#0057D8",
    "blue_soft": "#E8F0FF",  # selected rows, focus rings, accent backgrounds
    "ok": "#1E9E5A",
    "ok_soft": "#E3F6EC",
    "warn": "#B26A00",
    "warn_soft": "#FDF0DC",
    "fail": "#D03232",
    "fail_soft": "#FCE9E9",
    "skip": "#A1A1A6",
    "log_bg": "#15171C",
    "log_ink": "#E6E8EE",
}

#: Type scale. One family, five sizes - the hierarchy comes from weight and colour, not decoration.
FONTS = {
    "display": ("TkDefaultFont", 19, "bold"),   # page titles
    "title": ("TkDefaultFont", 13, "bold"),     # card headings
    "body": ("TkDefaultFont", 12),
    "label": ("TkDefaultFont", 12),
    "hint": ("TkDefaultFont", 11),
    "badge": ("TkDefaultFont", 10, "bold"),
}

#: The spacing scale every page uses, so padding is never invented per widget.
SPACE = {"xs": 4, "sm": 8, "md": 12, "lg": 18, "xl": 26}

#: Status name -> (dot colour, badge background). ``StepStatus.value`` is the key.
STATUS_COLORS = {
    "pending": COLORS["faint"],
    "checking": COLORS["blue"],
    "ok": COLORS["ok"],
    "needs_apply": COLORS["warn"],
    "running": COLORS["blue"],
    "done": COLORS["ok"],
    "failed": COLORS["fail"],
    "skipped": COLORS["skip"],
}

#: Status name -> the badge style that renders it in a page header.
STATUS_BADGES = {
    "ok": "BadgeOk.TLabel",
    "done": "BadgeOk.TLabel",
    "needs_apply": "BadgeWarn.TLabel",
    "failed": "BadgeFail.TLabel",
    "running": "BadgeInfo.TLabel",
    "checking": "BadgeInfo.TLabel",
}


def init_styles(root: tk.Misc) -> None:
    """Install the palette, the type scale and every named style the widgets below use."""
    style = ttk.Style(root)
    # clam everywhere: it is the only built-in theme that honours background/border colours, and a
    # consistent window beats a half-native one.
    try:
        style.theme_use("clam")
    except tk.TclError:  # pragma: no cover - a Tk build without clam
        pass
    root.configure(background=COLORS["paper"])
    root.option_add("*Font", FONTS["body"])

    style.configure(".", background=COLORS["paper"], foreground=COLORS["ink"], font=FONTS["body"], borderwidth=0, focuscolor=COLORS["blue"])
    style.configure("TFrame", background=COLORS["paper"])
    style.configure("Surface.TFrame", background=COLORS["surface"])
    style.configure("Line.TFrame", background=COLORS["line"])
    style.configure("Sunken.TFrame", background=COLORS["sunken"])
    style.configure("Accent.TFrame", background=COLORS["blue"])

    style.configure("TLabel", background=COLORS["paper"], foreground=COLORS["ink"], font=FONTS["body"])
    style.configure("Surface.TLabel", background=COLORS["surface"])
    style.configure("Muted.TLabel", foreground=COLORS["muted"], font=FONTS["label"])
    style.configure("Hint.TLabel", foreground=COLORS["muted"], font=FONTS["hint"])
    style.configure("Faint.TLabel", foreground=COLORS["faint"], font=FONTS["hint"])
    style.configure("Head.TLabel", font=FONTS["display"], foreground=COLORS["ink"])
    style.configure("Title.TLabel", font=FONTS["title"], foreground=COLORS["ink"])
    style.configure("Section.TLabel", font=FONTS["badge"], foreground=COLORS["muted"], background=COLORS["surface"])
    style.configure("Mono.TLabel", font="TkFixedFont", foreground=COLORS["ink"])
    style.configure("Ok.TLabel", foreground=COLORS["ok"])
    style.configure("Warn.TLabel", foreground=COLORS["warn"])
    style.configure("Fail.TLabel", foreground=COLORS["fail"])
    for name, ink, background in (
        ("BadgeOk", COLORS["ok"], COLORS["ok_soft"]),
        ("BadgeWarn", COLORS["warn"], COLORS["warn_soft"]),
        ("BadgeFail", COLORS["fail"], COLORS["fail_soft"]),
        ("BadgeInfo", COLORS["blue"], COLORS["blue_soft"]),
        ("BadgeMuted", COLORS["muted"], COLORS["sunken"]),
    ):
        style.configure(f"{name}.TLabel", foreground=ink, background=background, font=FONTS["badge"], padding=(8, 3))

    style.configure(
        "TButton",
        background=COLORS["surface"],
        foreground=COLORS["ink"],
        bordercolor=COLORS["line"],
        borderwidth=1,
        focusthickness=0,
        padding=(12, 6),
        relief="flat",
        font=FONTS["body"],
    )
    style.map(
        "TButton",
        background=[("pressed", COLORS["sunken"]), ("active", COLORS["sunken"]), ("disabled", COLORS["paper"])],
        foreground=[("disabled", COLORS["faint"])],
        bordercolor=[("active", COLORS["line"])],
    )
    style.configure("Primary.TButton", background=COLORS["blue"], foreground="#FFFFFF", bordercolor=COLORS["blue"])
    style.map(
        "Primary.TButton",
        background=[("pressed", COLORS["blue_hover"]), ("active", COLORS["blue_hover"]), ("disabled", COLORS["sunken"])],
        foreground=[("disabled", COLORS["faint"])],
    )
    style.configure("Danger.TButton", foreground=COLORS["fail"], bordercolor=COLORS["line"])
    style.map("Danger.TButton", background=[("pressed", COLORS["fail_soft"]), ("active", COLORS["fail_soft"])])
    style.configure("Ghost.TButton", background=COLORS["paper"], bordercolor=COLORS["paper"], foreground=COLORS["muted"])
    style.map("Ghost.TButton", background=[("active", COLORS["sunken"])], foreground=[("active", COLORS["ink"])])

    style.configure(
        "TEntry",
        fieldbackground=COLORS["surface"],
        background=COLORS["surface"],
        foreground=COLORS["ink"],
        bordercolor=COLORS["line"],
        lightcolor=COLORS["line"],
        darkcolor=COLORS["line"],
        borderwidth=1,
        padding=(8, 6),
        insertcolor=COLORS["ink"],
    )
    style.map("TEntry", bordercolor=[("focus", COLORS["blue"])], lightcolor=[("focus", COLORS["blue"])], darkcolor=[("focus", COLORS["blue"])])
    style.configure(
        "TCombobox",
        fieldbackground=COLORS["surface"],
        background=COLORS["surface"],
        foreground=COLORS["ink"],
        bordercolor=COLORS["line"],
        lightcolor=COLORS["line"],
        darkcolor=COLORS["line"],
        arrowcolor=COLORS["muted"],
        borderwidth=1,
        padding=(8, 5),
    )
    style.map("TCombobox", bordercolor=[("focus", COLORS["blue"])], fieldbackground=[("readonly", COLORS["surface"])])

    # clam draws the tick with ``indicatorforeground`` on an ``indicatorbackground`` box: left at
    # its defaults a ticked box is a grey square with a dark cross, which reads as "delete", not
    # "on". Ticked means a filled accent box with a white mark, like every other modern checkbox.
    for name, surface in (("TCheckbutton", COLORS["surface"]), ("Paper.TCheckbutton", COLORS["paper"])):
        style.configure(
            name,
            background=surface,
            foreground=COLORS["ink"],
            indicatorbackground=COLORS["surface"],
            indicatorforeground=COLORS["surface"],
            bordercolor=COLORS["line"],
            lightcolor=COLORS["line"],
            darkcolor=COLORS["line"],
            indicatormargin=(0, 0, SPACE["sm"], 0),
            focusthickness=0,
            padding=(2, 3),
        )
        style.map(
            name,
            background=[("active", surface)],
            indicatorbackground=[("selected", COLORS["blue"]), ("disabled", COLORS["sunken"]), ("pressed", COLORS["blue_soft"])],
            indicatorforeground=[("selected", "#FFFFFF"), ("disabled", COLORS["faint"])],
            bordercolor=[("selected", COLORS["blue"]), ("focus", COLORS["blue"])],
            foreground=[("disabled", COLORS["faint"])],
        )
    style.configure(
        "TRadiobutton",
        background=COLORS["surface"],
        indicatorbackground=COLORS["surface"],
        indicatorforeground=COLORS["blue"],
        bordercolor=COLORS["line"],
        focusthickness=0,
        padding=(2, 3),
    )
    style.map(
        "TRadiobutton",
        background=[("active", COLORS["surface"])],
        indicatorbackground=[("selected", COLORS["surface"])],
        indicatorforeground=[("selected", COLORS["blue"])],
        bordercolor=[("selected", COLORS["blue"]), ("focus", COLORS["blue"])],
    )

    style.configure(
        "Treeview",
        background=COLORS["surface"],
        fieldbackground=COLORS["surface"],
        foreground=COLORS["ink"],
        bordercolor=COLORS["line"],
        borderwidth=0,
        rowheight=26,
        font=FONTS["body"],
    )
    style.configure("Treeview.Heading", background=COLORS["sunken"], foreground=COLORS["muted"], font=FONTS["hint"], relief="flat", padding=(8, 6))
    style.map("Treeview", background=[("selected", COLORS["blue_soft"])], foreground=[("selected", COLORS["ink"])])
    style.map("Treeview.Heading", background=[("active", COLORS["sunken"])])

    style.configure(
        "Vertical.TScrollbar",
        background=COLORS["line"],
        troughcolor=COLORS["paper"],
        bordercolor=COLORS["paper"],
        arrowcolor=COLORS["muted"],
        borderwidth=0,
        width=12,
    )
    style.map("Vertical.TScrollbar", background=[("active", COLORS["faint"])])
    style.configure("TProgressbar", background=COLORS["blue"], troughcolor=COLORS["sunken"], bordercolor=COLORS["sunken"], borderwidth=0, thickness=4)
    style.configure("TPanedwindow", background=COLORS["paper"])
    style.configure("Sash", sashthickness=6, gripcount=0)


#: One "unit" of canvas scrolling, in pixels - small enough for a trackpad, large enough that a
#: wheel notch (three units) moves about three lines.
SCROLL_INCREMENT = 15


def wheel_units(event: "tk.Event") -> int:
    """How far one wheel event should scroll, in :data:`SCROLL_INCREMENT` units, sign included.

    The three windowing systems disagree: X11 sends button 4/5 with no magnitude, Windows sends
    multiples of 120, and macOS sends small counts (often ±1). Dividing the macOS value - as this
    once did - rounds every trackpad event to zero, which is a scroll pane that does not scroll.
    """
    if getattr(event, "num", 0) == 4:
        return -3
    if getattr(event, "num", 0) == 5:
        return 3
    delta = int(getattr(event, "delta", 0) or 0)
    if delta == 0:
        return 0
    if abs(delta) >= 120:  # Windows: one notch is 120
        return -(delta // 120) * 3
    return -delta  # macOS: already a small count


def scrolls_itself(widget: tk.Misc) -> bool:
    """Whether ``widget`` handles the wheel on its own (the log transcript, a preview text)."""
    return isinstance(widget, (tk.Text, tk.Listbox))


class ScrollFrame(ttk.Frame):
    """A vertically scrollable container; put content into :attr:`body`.

    The scrollbar appears only when the content is taller than the window. Wheel events are not
    bound here at all: :func:`install_wheel_router` routes them to whichever scroller the pointer
    is over, because sixteen pages each binding ``bind_all`` would scroll all sixteen at once.
    """

    def __init__(self, parent: tk.Misc) -> None:
        super().__init__(parent)
        self.canvas = tk.Canvas(self, highlightthickness=0, background=COLORS["surface"], yscrollincrement=SCROLL_INCREMENT)
        self.scrollbar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.body = ttk.Frame(self.canvas)
        self._window = self.canvas.create_window((0, 0), window=self.body, anchor="nw")
        self.canvas.configure(yscrollcommand=self.scrollbar.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        self._scrollbar_shown = False
        self.body.bind("<Configure>", lambda _event: self._resized())
        self.canvas.bind("<Configure>", self._canvas_resized)

    def scroll(self, units: int) -> None:
        """Scroll by ``units`` (negative is up); ignored when everything already fits."""
        if units and self._overflows():
            self.canvas.yview_scroll(units, "units")

    def _canvas_resized(self, event: "tk.Event") -> None:
        self.canvas.itemconfigure(self._window, width=event.width)  # the body follows the width, so text wraps
        self._resized()

    def _resized(self) -> None:
        """Keep the scrollregion, the scrollbar's presence and the offset consistent."""
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))
        if not self.canvas.winfo_ismapped():
            return  # an unmapped canvas reports a height of 1: every content would look too tall
        overflows = self._overflows()
        if overflows and not self._scrollbar_shown:
            self.scrollbar.pack(side="right", fill="y")
        elif not overflows and self._scrollbar_shown:
            self.canvas.yview_moveto(0)  # nothing to scroll: never leave the content parked off-screen
            self.scrollbar.pack_forget()
        self._scrollbar_shown = overflows

    def _overflows(self) -> bool:
        return self.body.winfo_reqheight() > self.canvas.winfo_height()


#: Marks the application whose wheel events are already routed (``bind_all`` is application-wide,
#: so binding a second time would scroll everything twice as fast).
_ROUTER_INSTALLED = "_cloud_driver_wheel_router"


def install_wheel_router(root: tk.Misc) -> None:
    """Send every wheel event to the scroller under the pointer. Safe to call more than once.

    Tk delivers ``<MouseWheel>`` to the focused widget on Windows and to the pointer's widget
    elsewhere, so the target is resolved from the pointer position either way. A widget that
    scrolls itself keeps its event: its own class binding has already run by the time this does.
    """
    toplevel = root.winfo_toplevel()
    if getattr(toplevel, _ROUTER_INSTALLED, False):
        return
    setattr(toplevel, _ROUTER_INSTALLED, True)

    def route(event: "tk.Event") -> None:
        units = wheel_units(event)
        if not units:
            return
        try:
            widget = root.winfo_containing(event.x_root, event.y_root)
        except tk.TclError:  # pragma: no cover - pointer outside every toplevel
            widget = None
        while isinstance(widget, tk.Misc):
            if scrolls_itself(widget):
                return
            if isinstance(widget, ScrollFrame):
                widget.scroll(units)
                return
            widget = getattr(widget, "master", None)

    for sequence in ("<MouseWheel>", "<Button-4>", "<Button-5>"):
        root.bind_all(sequence, route, add="+")


class Check(ttk.Frame):
    """A checkbox drawn by hand: an accent-filled box with a real tick.

    ttk's ``clam`` indicator is a grey square that draws a **cross** when ticked, which reads as
    "remove", not "on" - and the native themes it would otherwise fall back to ignore the palette.
    This is a canvas box plus a label, bound to the same kind of ``BooleanVar`` as a Checkbutton,
    and it follows the variable when a page loads the plan into it.
    """

    SIZE = 16

    def __init__(
        self,
        parent: tk.Misc,
        text: str = "",
        variable: "tk.BooleanVar | None" = None,
        command: "Callable[[], None] | None" = None,
        background: str = COLORS["surface"],
    ) -> None:
        super().__init__(parent)
        self.variable = variable if variable is not None else tk.BooleanVar()
        self._command = command
        self._enabled = True
        self._background = background
        self.configure(style="Surface.TFrame" if background == COLORS["surface"] else "TFrame")
        self.box = tk.Canvas(self, width=self.SIZE, height=self.SIZE, highlightthickness=0, borderwidth=0, background=background, cursor="hand2")
        self.box.pack(side="left")
        self.label = tk.Label(self, text=text, background=background, foreground=COLORS["ink"], font=FONTS["body"], cursor="hand2", anchor="w", justify="left")
        if text:
            self.label.pack(side="left", padx=(SPACE["sm"], 0))
        for widget in (self.box, self.label):
            widget.bind("<Button-1>", self.toggle)
        self.variable.trace_add("write", lambda *_args: self._draw())
        self._draw()

    def toggle(self, _event: "tk.Event | None" = None) -> None:
        """Flip the value and call the command, unless the widget is disabled."""
        if not self._enabled:
            return
        self.variable.set(not bool(self.variable.get()))
        if self._command is not None:
            self._command()

    def set_background(self, colour: str) -> None:
        """Follow the row behind it (the sidebar tints rows on hover and selection)."""
        self._background = colour
        self.box.configure(background=colour)
        self.label.configure(background=colour)
        self._draw()

    def configure(self, cnf: "dict | None" = None, **kwargs: object) -> object:  # type: ignore[override]
        """Accept ``state="disabled"``/``"normal"`` like a Checkbutton; pass the rest through."""
        state = kwargs.pop("state", None)
        if state is not None:
            self._enabled = str(state) != "disabled"
            if hasattr(self, "label"):
                self.label.configure(foreground=COLORS["ink"] if self._enabled else COLORS["faint"])
                self.box.configure(cursor="hand2" if self._enabled else "arrow")
                self._draw()
        return super().configure(cnf, **kwargs) if (cnf or kwargs) else None

    config = configure

    def state(self, statespec: "list[str] | tuple[str, ...] | None" = None) -> object:  # type: ignore[override]
        """``state(["disabled"])`` and ``state(["!disabled"])`` work as on a ttk widget."""
        if statespec:
            for flag in statespec:
                if flag in ("disabled", "!disabled"):
                    self.configure(state="normal" if flag.startswith("!") else "disabled")
        return [] if self._enabled else ["disabled"]

    def invoke(self) -> None:
        """Toggle as a click would (what tests and keyboard bindings use)."""
        self.toggle()

    def _draw(self) -> None:
        checked, enabled = bool(self.variable.get()), self._enabled
        fill = (COLORS["blue"] if enabled else COLORS["faint"]) if checked else self._background
        outline = fill if checked else (COLORS["line"] if enabled else COLORS["sunken"])
        self.box.delete("all")
        size = self.SIZE
        self.box.create_rectangle(1, 1, size - 2, size - 2, fill=fill, outline=outline, width=1)
        if checked:  # a tick, drawn in two strokes
            self.box.create_line(4, 8, 7, 11, fill="#FFFFFF", width=2, capstyle="round")
            self.box.create_line(7, 11, 12, 5, fill="#FFFFFF", width=2, capstyle="round")


class Form(ttk.Frame):
    """A two-column label/field grid with optional hint lines, like the mockup's forms."""

    def __init__(self, parent: tk.Misc) -> None:
        super().__init__(parent, style="Surface.TFrame")
        self.columnconfigure(1, weight=1)
        self._row = 0

    def row(self, label: str, widget: tk.Widget, hint: str = "") -> tk.Widget:
        """Add ``widget`` under ``label`` (an empty label spans the field column)."""
        if label:
            ttk.Label(self, text=label, style="Muted.TLabel", background=COLORS["surface"]).grid(
                row=self._row, column=0, sticky="e", padx=(0, SPACE["md"]), pady=SPACE["xs"]
            )
        widget.grid(row=self._row, column=1, sticky="ew", pady=SPACE["xs"])
        self._row += 1
        if hint:
            ttk.Label(self, text=hint, style="Hint.TLabel", background=COLORS["surface"], wraplength=620, justify="left").grid(
                row=self._row, column=1, sticky="w", pady=(0, SPACE["sm"])
            )
            self._row += 1
        return widget

    def entry(self, label: str, variable: tk.Variable, hint: str = "", width: int = 32) -> ttk.Entry:
        """A plain text entry bound to ``variable``."""
        widget = ttk.Entry(self, textvariable=variable, width=width, font="TkFixedFont")
        self.row(label, widget, hint)
        return widget

    def check(self, text: str, variable: tk.BooleanVar, hint: str = "") -> Check:
        """A checkbox spanning the field column."""
        widget = Check(self, text=text, variable=variable)
        self.row("", widget, hint)
        return widget

    def combo(self, label: str, variable: tk.Variable, values: "list[str] | tuple[str, ...]", hint: str = "") -> ttk.Combobox:
        """An editable combobox."""
        widget = ttk.Combobox(self, textvariable=variable, values=list(values), width=30)
        self.row(label, widget, hint)
        return widget

    def radios(self, label: str, variable: tk.StringVar, options: "list[tuple[str, str]]", hint: str = "", command: Callable[[], None] | None = None) -> ttk.Frame:
        """A row of radio buttons: ``options`` is ``[(value, text), …]``."""
        holder = ttk.Frame(self, style="Surface.TFrame")
        for value, text in options:
            ttk.Radiobutton(holder, text=text, value=value, variable=variable, command=command).pack(side="left", padx=(0, 12))
        self.row(label, holder, hint)
        return holder

    def secret(self, label: str, variable: tk.StringVar, hint: str = "", generate: Callable[[], str] | None = None) -> ttk.Frame:
        """A masked entry with Show and (optionally) Generate."""
        holder = ttk.Frame(self, style="Surface.TFrame")
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
        holder = ttk.Frame(self, style="Surface.TFrame")
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
        holder = ttk.Frame(self, style="Surface.TFrame")
        ttk.Entry(holder, textvariable=variable, width=10, font="TkFixedFont").pack(side="left")
        ttk.Label(holder, text="GiB", style="Muted.TLabel", background=COLORS["surface"]).pack(side="left", padx=4)
        readout = ttk.Label(holder, text="", style="Hint.TLabel", background=COLORS["surface"])
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
    """The coloured dot in front of a step: filled when it has a verdict, hollow while it waits."""

    def __init__(self, parent: tk.Misc, size: int = 9, background: str = COLORS["paper"]) -> None:
        super().__init__(parent, width=size, height=size, highlightthickness=0, background=background, borderwidth=0)
        self._item = self.create_oval(0, 0, size - 1, size - 1, outline=COLORS["line"], fill="")

    def set_status(self, status: str) -> None:
        """Paint the dot for a :class:`~cloud_driver_installer.engine.StepStatus` value."""
        colour = STATUS_COLORS.get(status, COLORS["faint"])
        hollow = status in ("pending", "")
        self.itemconfigure(self._item, fill="" if hollow else colour, outline=COLORS["line"] if hollow else colour)

    def set_background(self, colour: str) -> None:
        """Follow the row behind it (hover and selection change it)."""
        self.configure(background=colour)


class FactGrid(ttk.Frame):
    """A read-only grid of discovered facts."""

    def __init__(self, parent: tk.Misc, columns: int = 3) -> None:
        super().__init__(parent, style="Surface.TFrame")
        self._columns = columns
        for column in range(columns):
            self.columnconfigure(column, weight=1)
        self._labels: dict[str, ttk.Label] = {}

    def set_facts(self, facts: "list[tuple[str, str]]") -> None:
        """Replace the grid's contents."""
        for child in self.winfo_children():
            child.destroy()
        for index, (name, value) in enumerate(facts):
            cell = ttk.Frame(self, style="Surface.TFrame")
            cell.grid(row=index // self._columns, column=index % self._columns, sticky="ew", padx=(0, SPACE["lg"]), pady=(0, SPACE["sm"]))
            ttk.Label(cell, text=name.upper(), style="Faint.TLabel", background=COLORS["surface"]).pack(anchor="w")
            ttk.Label(cell, text=value or "—", style="Mono.TLabel", background=COLORS["surface"]).pack(anchor="w")


class Card(ttk.Frame):
    """A white panel with a hairline border and a small capitalised heading.

    The border is a one-pixel frame behind the surface rather than a widget relief: ttk's bevels
    look like 1998, and clam's ``solid`` relief still draws two tones. Content goes into
    :attr:`body`, which :func:`section` returns directly so pages read the same as before.
    """

    def __init__(self, parent: tk.Misc, title: str = "") -> None:
        super().__init__(parent, style="Line.TFrame")
        inner = ttk.Frame(self, style="Surface.TFrame")
        inner.pack(fill="both", expand=True, padx=1, pady=1)
        if title:
            heading = ttk.Label(inner, text=title.upper(), style="Section.TLabel")
            heading.pack(anchor="w", padx=SPACE["md"], pady=(SPACE["md"], SPACE["xs"]))
        self.body = ttk.Frame(inner, style="Surface.TFrame")
        self.body.pack(fill="both", expand=True, padx=SPACE["md"], pady=(0, SPACE["md"]))
        # Pages put ordinary ttk labels and frames in here; ttk has no inheritance, so each one
        # would keep the window's grey and draw a band across the white card. One pass when the
        # card first appears puts everything on the surface colour - except widgets that chose a
        # background of their own (the status badges, the log, previews).
        self.bind("<Map>", self._paint_surface, add="+")

    def _paint_surface(self, _event: "tk.Event | None" = None) -> None:
        self.unbind("<Map>")
        for widget in self._descendants(self.body):
            try:
                if isinstance(widget, ttk.Label) and not str(widget.cget("style")).startswith("Badge"):
                    widget.configure(background=COLORS["surface"])
                elif isinstance(widget, ttk.Frame) and str(widget.cget("style")) in ("", "TFrame"):
                    widget.configure(style="Surface.TFrame")
                elif isinstance(widget, (tk.Label, tk.Frame)) and widget.cget("background") in ("", COLORS["paper"]):
                    widget.configure(background=COLORS["surface"])
            except tk.TclError:  # pragma: no cover - a widget that has no such option
                continue

    @staticmethod
    def _descendants(widget: tk.Misc) -> "list[tk.Misc]":
        found: list[tk.Misc] = []
        stack = list(widget.winfo_children())
        while stack:
            child = stack.pop()
            found.append(child)
            stack.extend(child.winfo_children())
        return found


def section(parent: tk.Misc, title: str) -> ttk.Frame:
    """A titled card, packed into ``parent``; returns the frame to put content in."""
    card = Card(parent, title)
    card.pack(fill="x", pady=SPACE["sm"], padx=2)
    return card.body


def note(parent: tk.Misc, text: str, style: str = "Hint.TLabel") -> ttk.Label:
    """An explanatory paragraph under a field group."""
    label = ttk.Label(parent, text=text, style=style, wraplength=680, justify="left")
    label.pack(anchor="w", padx=SPACE["sm"], pady=(SPACE["xs"], SPACE["sm"]))
    return label


def human_bytes(value: int) -> str:
    """``format_bytes`` re-exported for pages."""
    return format_bytes(value)

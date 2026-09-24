"""The confirmation window for *Remove everything* - the last look before a deployment is gone.

A single ``askyesno`` is not enough for this one: it deletes the database, the jars, the services
and the configuration of a whole server, and the only thing standing between a misplaced click and
a production deployment is what this window shows. So it lists, in removal order, every step's own
``describe_removal`` sentence, names what removal deliberately cannot reach, and arms its button
only once the operator has typed the server's address.
"""

from __future__ import annotations

import tkinter as tk
from tkinter import ttk

from cloud_driver_installer.gui.state import AppState
from cloud_driver_installer.gui.widgets import COLORS, FONTS, SPACE, ScrollFrame, install_wheel_router, section
from cloud_driver_installer.removal import confirmation_phrase, matches_confirmation, removal_items, retained_items


class RemoveEverythingDialog(tk.Toplevel):
    """Modal confirmation; :attr:`confirmed` is true only if the operator armed and pressed it."""

    def __init__(self, parent: tk.Misc, state: AppState) -> None:
        super().__init__(parent)
        self.state = state
        self.confirmed = False
        plan = state.plan
        self.items = removal_items(plan)
        self.phrase = confirmation_phrase(plan)

        self.title("Remove everything")
        self.transient(parent.winfo_toplevel())
        self.configure(background=COLORS["paper"])
        self.minsize(640, 460)
        self.geometry("820x640")
        install_wheel_router(self)

        head = ttk.Frame(self, padding=(SPACE["lg"], SPACE["lg"], SPACE["lg"], SPACE["sm"]))
        head.pack(fill="x")
        ttk.Label(head, text="Remove the whole deployment", style="Head.TLabel").pack(anchor="w")
        ttk.Label(
            head,
            text=f"Every step below is deleted over this SSH session from the remote server {plan.ssh.label()}, newest first. "
            "The database and everything ever uploaded into it go with it. Nothing on this machine is touched except the "
            "~/.ssh/config alias the installer wrote. This cannot be undone.",
            style="Warn.TLabel",
            wraplength=740,
            justify="left",
        ).pack(anchor="w", pady=(SPACE["xs"], 0))

        # The buttons and the confirmation field are packed against the bottom first, so a short
        # window scrolls the list instead of pushing the only way out of the dialog off-screen.
        buttons = ttk.Frame(self, padding=(SPACE["lg"], SPACE["sm"], SPACE["lg"], SPACE["lg"]))
        buttons.pack(fill="x", side="bottom")
        self.remove_button = ttk.Button(buttons, text="Remove everything", style="Danger.TButton", command=self._accept)
        self.remove_button.pack(side="right")
        self.remove_button.state(["disabled"])
        ttk.Button(buttons, text="Cancel", command=self._cancel).pack(side="right", padx=SPACE["sm"])

        confirm = ttk.Frame(self, padding=(SPACE["lg"], 0))
        confirm.pack(fill="x", side="bottom")
        ttk.Label(confirm, text=f"Type  {self.phrase or '(no host)'}  to confirm", style="Muted.TLabel").pack(anchor="w")
        self.typed = tk.StringVar()
        self.typed.trace_add("write", lambda *_args: self._sync_button())
        entry = ttk.Entry(confirm, textvariable=self.typed, width=32, font=FONTS["body"])
        entry.pack(anchor="w", pady=(SPACE["xs"], SPACE["sm"]))

        scroller = ScrollFrame(self)
        scroller.pack(fill="both", expand=True, padx=SPACE["md"])
        body = scroller.body

        going = section(body, f"Removed, in this order ({len(self.items)} steps)")
        for index, item in enumerate(self.items, start=1):
            row = ttk.Frame(going)
            row.pack(fill="x", padx=SPACE["sm"], pady=(0, SPACE["xs"]))
            ttk.Label(row, text=f"{index}. {item.title}", style="Mono.TLabel", background=COLORS["surface"]).pack(anchor="w")
            ttk.Label(row, text=item.description, style="Hint.TLabel", background=COLORS["surface"], wraplength=700, justify="left").pack(anchor="w", padx=(SPACE["md"], 0))

        kept = section(body, "Not touched")
        for line in retained_items(plan):
            ttk.Label(kept, text=f"• {line}", style="Hint.TLabel", background=COLORS["surface"], wraplength=720, justify="left").pack(
                anchor="w", padx=SPACE["sm"], pady=(0, SPACE["xs"])
            )

        self.protocol("WM_DELETE_WINDOW", self._cancel)
        self.bind("<Escape>", lambda _event: self._cancel())
        entry.focus_set()

    # --- internals -----------------------------------------------------------------------------

    def _sync_button(self) -> None:
        """Arm the button only while the typed text names this server."""
        armed = matches_confirmation(self.state.plan, self.typed.get())
        self.remove_button.state(["!disabled"] if armed else ["disabled"])

    def _accept(self) -> None:
        if not matches_confirmation(self.state.plan, self.typed.get()):
            return
        self.confirmed = True
        self.destroy()

    def _cancel(self) -> None:
        self.confirmed = False
        self.destroy()


def ask_remove_everything(parent: tk.Misc, state: AppState) -> bool:
    """Show the dialog and block until it is answered; true means "wipe this server"."""
    dialog = RemoveEverythingDialog(parent, state)
    dialog.grab_set()
    parent.wait_window(dialog)
    return dialog.confirmed

"""The main window: sidebar of steps, one page at a time, the log, and the run controls."""

from __future__ import annotations

import queue
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from cloud_driver_installer.engine import StepEvent, StepStatus
from cloud_driver_installer.gui.log import LogPane
from cloud_driver_installer.gui.pages import PAGES, PageActions, page_ids
from cloud_driver_installer.gui.state import AppState
from cloud_driver_installer.gui.widgets import COLORS, FONTS, SPACE, Check, ScrollFrame, StatusDot, install_wheel_router
from cloud_driver_installer.gui.worker import JobDone, LogEvent, ProbeResult, ProgressEvent, Worker
from cloud_driver_installer.profiles import PROFILE_DIR, load_profile, save_profile
from cloud_driver_installer.setup_export import write_setup_markdown
from cloud_driver_installer.steps import STEP_IDS

#: How many queued events one drain may render, so a burst of apt output cannot freeze the window.
DRAIN_BUDGET = 500


class MainWindow(ttk.Frame):
    """Everything after the connection dialog."""

    def __init__(self, root: tk.Tk, state: AppState) -> None:
        super().__init__(root)
        self.root = root
        self.state = state
        self.worker = Worker(state)
        self.started_at = 0.0
        self.pack(fill="both", expand=True)
        # Small enough to fit a 1280x800 laptop screen with room for the dock and the menu bar:
        # every pane inside scrolls, so a short window hides nothing, it only needs scrolling.
        root.minsize(760, 480)
        root.geometry("1200x800")
        install_wheel_router(root)
        self._build_menu()

        panes = ttk.PanedWindow(self, orient="vertical")
        panes.pack(fill="both", expand=True)
        upper = ttk.Frame(panes)
        panes.add(upper, weight=4)
        self.log = LogPane(panes)
        panes.add(self.log, weight=1)
        # Weights only govern how *extra* space is shared; the first sash still has to be placed,
        # or the log opens at its requested height and takes half the window with it.
        self.panes = panes
        root.after_idle(self._place_sash)
        root.after(250, self._place_sash)  # again once the pages have their real size

        self.sidebar = ttk.Frame(upper, width=260)
        self.sidebar.pack(side="left", fill="y")
        self.sidebar.pack_propagate(False)
        ttk.Frame(upper, style="Line.TFrame", width=1).pack(side="left", fill="y")  # hairline, not a bevel
        self.content = ttk.Frame(upper, style="Surface.TFrame")
        self.content.pack(side="left", fill="both", expand=True)

        self.rows: dict[str, dict] = {}
        self.sidebar_scroller: ScrollFrame | None = None
        self._build_sidebar()
        self.pages = {}
        actions = PageActions(
            check=self.check_step,
            apply=self.apply_step,
            remove=self.remove_step,
            probe_aws=self.probe_aws,
            probe_dns=self.worker.probe_dns,
            install=self.install,
            stop=self.worker.cancel,
            export_setup=self.export_setup,
        )
        for page_id in page_ids():
            page = PAGES[page_id](self.content, state, actions)
            page.place(relwidth=1, relheight=1)
            self.pages[page_id] = page
        self.current = "server"

        ttk.Frame(self, style="Line.TFrame", height=1).pack(fill="x")
        status = ttk.Frame(self, padding=(SPACE["md"], SPACE["sm"]))
        status.pack(fill="x")
        self.status_left = ttk.Label(status, text="", style="Hint.TLabel")
        self.status_left.pack(side="left")
        self.status_right = ttk.Label(status, text="", style="Hint.TLabel")
        self.status_right.pack(side="right")
        self.progress = ttk.Progressbar(status, mode="determinate", maximum=1.0, length=180)
        self.progress.pack(side="right", padx=SPACE["md"])

        self.load_pages()
        self.show("server")
        root.title(f"cloud-driver installer — {state.plan.ssh.label()}")
        root.bind("<F5>", lambda _event: self.check_all())
        root.bind("<Control-Return>", lambda _event: self.apply_step(self.current))
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(100, self.drain)

    # --- construction ----------------------------------------------------------------------------

    def _build_menu(self) -> None:
        menubar = tk.Menu(self.root)
        file_menu = tk.Menu(menubar, tearoff=0)
        file_menu.add_command(label="Load profile…", command=self.load_profile)
        file_menu.add_command(label="Save profile as…", command=self.save_profile)
        file_menu.add_command(label="Export setup (Setup.md)…", command=self.export_setup)
        file_menu.add_separator()
        file_menu.add_command(label="Quit", command=self.close)
        menubar.add_cascade(label="File", menu=file_menu)
        server_menu = tk.Menu(menubar, tearoff=0)
        server_menu.add_command(label="Check all", command=self.check_all)
        server_menu.add_command(label="Install selected steps", command=self.install)
        server_menu.add_command(label="Stop", command=self.worker.cancel)
        menubar.add_cascade(label="Server", menu=server_menu)
        help_menu = tk.Menu(menubar, tearoff=0)
        help_menu.add_command(label="About", command=self.about)
        menubar.add_cascade(label="Help", menu=help_menu)
        self.root.configure(menu=menubar)

    def _build_sidebar(self) -> None:
        from cloud_driver_installer.steps import all_steps

        mandatory = {step.id for step in all_steps() if step.mandatory}
        # The run buttons are packed first so they keep their place at the bottom; the sixteen step
        # rows take what is left and scroll when the window is too short to show them all.
        buttons = ttk.Frame(self.sidebar)
        buttons.pack(fill="x", side="bottom", padx=SPACE["md"], pady=SPACE["md"])
        ttk.Button(buttons, text="Install selected steps", style="Primary.TButton", command=self.install).pack(fill="x")
        secondary = ttk.Frame(buttons)
        secondary.pack(fill="x", pady=(SPACE["sm"], 0))
        ttk.Button(secondary, text="Check all", command=self.check_all).pack(side="left", fill="x", expand=True)
        ttk.Button(secondary, text="Stop", style="Ghost.TButton", command=self.worker.cancel).pack(side="left", padx=(SPACE["sm"], 0))
        ttk.Label(self.sidebar, text="STEPS", style="Faint.TLabel").pack(anchor="w", padx=SPACE["md"], pady=(SPACE["md"], SPACE["xs"]))
        scroller = ScrollFrame(self.sidebar)
        scroller.canvas.configure(background=COLORS["paper"])
        scroller.pack(fill="both", expand=True)
        self.sidebar_scroller = scroller
        holder = scroller.body
        for step_id in STEP_IDS:
            # One clickable block per step: accent bar, dot, title, detail line, include box.
            block = tk.Frame(holder, background=COLORS["paper"])
            block.pack(fill="x", padx=(0, SPACE["sm"]), pady=1)
            marker = tk.Frame(block, background=COLORS["paper"], width=3)
            marker.pack(side="left", fill="y")
            marker.pack_propagate(False)
            inner = tk.Frame(block, background=COLORS["paper"])
            inner.pack(side="left", fill="x", expand=True, padx=(SPACE["sm"], 0), pady=SPACE["xs"])
            top = tk.Frame(inner, background=COLORS["paper"])
            top.pack(fill="x")
            dot = StatusDot(top, background=COLORS["paper"])
            dot.pack(side="left", padx=(0, SPACE["sm"]))
            included = tk.BooleanVar(value=step_id in self.state.included)
            box = Check(top, variable=included, background=COLORS["paper"], command=lambda sid=step_id: self.toggle(sid))
            if step_id in mandatory:
                box.configure(state="disabled")  # the deployment does not work without it
            box.pack(side="right")
            label = tk.Label(top, text=self.state.title_of(step_id), cursor="hand2", background=COLORS["paper"], foreground=COLORS["ink"], font=FONTS["body"], anchor="w")
            label.pack(side="left", anchor="w")
            # The detail stays on one line and only appears when there is something to say: with a
            # wrapped line under all sixteen rows the list is 900 pixels tall and most of it is out
            # of sight on any normal window.
            detail = tk.Label(inner, text="", background=COLORS["paper"], foreground=COLORS["muted"], font=FONTS["hint"], justify="left", anchor="w")
            target = step_id if step_id in PAGES else "summary"
            tinted = (block, inner, top, label, detail)
            for widget in (label, top, inner, block, detail):
                widget.bind("<Button-1>", lambda _event, page=target: self.show(page))
            block.bind("<Enter>", lambda _event, sid=step_id: self._hover_row(sid, True), add="+")
            block.bind("<Leave>", lambda _event, sid=step_id: self._hover_row(sid, False), add="+")
            self.rows[step_id] = {"dot": dot, "label": label, "detail": detail, "included": included, "marker": marker, "tinted": tinted, "box": box, "block": block, "page": target}

    def _place_sash(self) -> None:
        """Give the work area roughly two thirds of the window and the log the rest."""
        self.update_idletasks()
        height = self.panes.winfo_height()
        if height > 200:
            self.panes.sashpos(0, int(height * 0.72))

    # --- navigation ------------------------------------------------------------------------------

    def show(self, page_id: str) -> None:
        """Bring one page to the front."""
        page = self.pages.get(page_id)
        if page is None:
            return
        previous, self.current = self.current, page_id
        page.tkraise()
        page.refresh(self.state)
        page.refresh_header(self.state)
        for step_id in (*self.rows, ):  # repaint the old and the new selection
            if self.rows[step_id]["page"] in (previous, page_id):
                self._tint_row(step_id)
        if page_id in self.rows:
            self._ensure_visible(page_id)

    def toggle(self, step_id: str) -> None:
        """Include or exclude a step and re-render the dependent ones."""
        variable = self.rows[step_id]["included"]
        if variable.get():
            self.state.included.add(step_id)
        else:
            self.state.included.discard(step_id)
        self.refresh_sidebar()

    # --- plan <-> pages --------------------------------------------------------------------------

    def load_pages(self) -> None:
        """Push the plan into every page."""
        for page in self.pages.values():
            page.load(self.state.plan)
            page.refresh(self.state)

    def store_pages(self) -> bool:
        """Pull every page into the plan; shows the first problem and jumps to its page."""
        for page_id, page in self.pages.items():
            try:
                page.store(self.state.plan)
                page.show_error("")
            except ValueError as exc:
                self.show(page_id)
                page.show_error(str(exc))
                return False
        problems = self.state.plan.validate(self.state.discovered)
        if problems:
            messagebox.showerror("Fix these first", "\n\n".join(f"• {problem}" for problem in problems))
            return False
        return True

    # --- jobs ------------------------------------------------------------------------------------

    def check_all(self) -> None:
        """Re-run every read-only check."""
        if not self.store_pages():
            return
        self.started_at = time.monotonic()
        self.worker.check_all()

    def check_step(self, step_id: str) -> None:
        """Check one step."""
        if self.store_pages():
            self.worker.check_one(step_id)

    def apply_step(self, step_id: str) -> None:
        """Run one step end to end."""
        if self.store_pages():
            self.started_at = time.monotonic()
            self.worker.run_one(step_id)

    def remove_step(self, step_id: str) -> None:
        """Delete one step's footprint from the server, after the operator confirms what goes.

        The plan is stored first because a removal reads it (which database, which install
        directory, which site block), and the dialog quotes the step's own ``describe_removal`` -
        this is the operator's last look at exactly what is about to be deleted.
        """
        if not self.store_pages():
            return
        step = self.worker.runner.by_id[step_id]
        if not step.removable:
            messagebox.showinfo("Nothing to remove", f"{step.title} installs nothing on the server - there is nothing to remove.")
            return
        confirmed = messagebox.askyesno(
            f"Remove {step.title}?",
            f"This deletes what the {step.title} step installed on {self.state.plan.ssh.label()}:\n\n"
            f"{step.describe_removal(self.state.plan)}\n\n"
            "This cannot be undone. Continue?",
            icon="warning",
            default="no",
        )
        if not confirmed:
            return
        self.started_at = time.monotonic()
        self.worker.remove_one(step_id)

    def install(self) -> None:
        """Run every selected step in order."""
        if not self.store_pages():
            return
        if self.state.destructive_actions() and not self.state.acknowledged:
            self.show("summary")
            messagebox.showwarning("Confirm first", "This run changes things that are already in use. Read the list on the summary page and tick the confirmation.")
            return
        failed = self.worker.first_failed()
        start_at = None
        if failed and messagebox.askyesno("Retry", f"Continue from the failed step ({self.state.title_of(failed)})?"):
            start_at = failed
        self.started_at = time.monotonic()
        self.worker.run_all(start_at=start_at)

    def probe_aws(self) -> None:
        """Validate the AWS credentials without touching the server."""
        if self.store_pages():
            self.worker.probe_aws()

    # --- event loop ------------------------------------------------------------------------------

    def drain(self) -> None:
        """Render whatever the worker queued since the last tick."""
        rendered = 0
        while rendered < DRAIN_BUDGET:
            try:
                event = self.worker.events.get_nowait()
            except queue.Empty:
                break
            rendered += 1
            if isinstance(event, LogEvent):
                self.log.append(event.level, event.message)
            elif isinstance(event, StepEvent):
                self.worker.apply_event(event)
                self.update_row(event.step_id)
            elif isinstance(event, ProgressEvent):
                self.status_right.configure(text=event.text)
                self.progress.configure(value=max(0.0, min(1.0, event.fraction)))
            elif isinstance(event, ProbeResult):
                self.handle_probe(event)
            elif isinstance(event, JobDone):
                self.log.append("OK" if event.ok else "ERROR", f"{event.job} finished" + ("" if event.ok else f": {event.error}"))
                self.refresh_sidebar()
                self.show(self.current)
        if self.worker.busy:
            self.status_left.configure(text=f"running… {int(time.monotonic() - self.started_at)}s")
        else:
            will_run, total = self.state.selected_count()
            self.status_left.configure(text=f"{self.state.plan.ssh.label()} · {will_run} of {total} steps selected")
            self.progress.configure(value=0.0)
        self.root.after(100, self.drain)

    def handle_probe(self, event: ProbeResult) -> None:
        """Route a local probe's answer to the page that asked for it."""
        if event.kind == "aws":
            self.pages["aws"].show_identity(event.ok, event.text)
        elif event.kind == "dns":
            self.pages["caddy"].show_dns(event.ok, event.text)
        self.log.append("INFO" if event.ok else "WARN", f"{event.kind}: {event.text}")

    def update_row(self, step_id: str) -> None:
        """Repaint one sidebar row from the state."""
        row = self.rows.get(step_id)
        if not row:
            return
        status = self.state.statuses.get(step_id, StepStatus.PENDING)
        row["dot"].set_status(status.value)
        self._set_detail(row, self.state.details.get(step_id, ""))
        self._tint_row(step_id)
        if status is StepStatus.RUNNING:
            self._ensure_visible(step_id)
        if self.current in (step_id, "summary"):
            self.pages[self.current].refresh_header(self.state)

    @staticmethod
    def _set_detail(row: dict, text: str) -> None:
        """One short line under a row, or no line at all when the step has nothing to report."""
        first = " ".join(text.split())
        # About what fits the 245-pixel column at the hint size; the page header carries the full
        # text, so a row that clips mid-word only costs the reader a second.
        shortened = (first[:31].rstrip() + "…") if len(first) > 32 else first
        row["detail"].configure(text=shortened)
        if shortened and not row["detail"].winfo_manager():
            row["detail"].pack(fill="x")
        elif not shortened and row["detail"].winfo_manager():
            row["detail"].pack_forget()

    def _ensure_visible(self, step_id: str) -> None:
        """Scroll the step list so this row is inside the viewport (selection, or a step starting)."""
        row = self.rows.get(step_id)
        if not row or self.sidebar_scroller is None:
            return
        canvas = self.sidebar_scroller.canvas
        body_height = self.sidebar_scroller.body.winfo_reqheight()
        if body_height <= canvas.winfo_height() or not canvas.winfo_ismapped():
            return
        top = row["block"].winfo_y()
        bottom = top + row["block"].winfo_height()
        view_top = canvas.canvasy(0)
        view_bottom = view_top + canvas.winfo_height()
        if top < view_top:
            canvas.yview_moveto(max(0.0, top / body_height))
        elif bottom > view_bottom:
            canvas.yview_moveto(max(0.0, (bottom - canvas.winfo_height()) / body_height))

    def _tint_row(self, step_id: str, *, hovered: bool = False) -> None:
        """Paint one sidebar row: selected (accent bar, tinted), hovered, or plain."""
        row = self.rows.get(step_id)
        if not row:
            return
        selected = self.current == row["page"] and row["page"] != "summary"
        background = COLORS["blue_soft"] if selected else (COLORS["sunken"] if hovered else COLORS["paper"])
        for widget in row["tinted"]:
            widget.configure(background=background)
        row["dot"].set_background(background)
        row["box"].set_background(background)
        row["marker"].configure(background=COLORS["blue"] if selected else background)
        row["label"].configure(foreground=COLORS["blue"] if selected else row["label"].cget("foreground"))

    def _hover_row(self, step_id: str, entering: bool) -> None:
        self._tint_row(step_id, hovered=entering)

    def refresh_sidebar(self) -> None:
        """Grey out steps that cannot run and repaint every row."""
        reasons = self.state.dependency_reasons()
        for step_id, row in self.rows.items():
            reason = reasons.get(step_id)
            row["label"].configure(foreground=COLORS["faint"] if reason else COLORS["ink"])
            if reason and self.state.statuses.get(step_id) in (StepStatus.PENDING, StepStatus.SKIPPED):
                row["detail"].configure(text=reason)
            self.update_row(step_id)

    # --- profiles and closing ----------------------------------------------------------------------

    def load_profile(self) -> None:
        """Replace the plan with a saved profile (secrets and one-shot flags are never restored)."""
        path = filedialog.askopenfilename(initialdir=str(PROFILE_DIR), filetypes=[("Installer profile", "*.json")])
        if not path:
            return
        self.state.plan = load_profile(Path(path))
        self.state.profile_path = Path(path)
        self.state.reset_statuses()
        self.load_pages()
        self.refresh_sidebar()
        self.log.append("INFO", f"loaded profile {path}")

    def save_profile(self) -> None:
        """Write the current plan without any secret."""
        if not self.store_pages():
            return
        PROFILE_DIR.mkdir(parents=True, exist_ok=True)
        path = filedialog.asksaveasfilename(initialdir=str(PROFILE_DIR), defaultextension=".json", initialfile="cloud-driver.json")
        if not path:
            return
        save_profile(self.state.plan, Path(path))
        self.state.profile_path = Path(path)
        self.log.append("OK", f"saved profile {path} (no secrets)")

    def export_setup(self) -> None:
        """Write Setup.md: every setting and every credential of this deployment, in clear text.

        The passwords are the point of the document, so the dialog says so before the file exists
        and the file itself is written ``0600`` with the same warning in its first lines.
        """
        if not self.store_pages():
            return
        if not messagebox.askyesno(
            "Export setup",
            "Setup.md contains this deployment's passwords and keys in clear text: the database and "
            "Redis passwords, the JWT signing key, the intelligence shared secret, the server's AWS "
            "access key and the SMTP password.\n\n"
            "It is written with owner-only permissions. Keep it somewhere encrypted - never in the "
            "repository or a chat message.\n\nWrite the file?",
            icon="warning",
        ):
            return
        path = filedialog.asksaveasfilename(
            title="Export setup",
            defaultextension=".md",
            initialfile="Setup.md",
            filetypes=[("Markdown", "*.md"), ("All files", "*.*")],
        )
        if not path:
            return
        written = write_setup_markdown(path, self.state.plan, self.state.secrets, self.state.discovered)
        self.log.append("OK", f"wrote {written} (0600) - it contains every credential in clear text")
        messagebox.showinfo("Export setup", f"Wrote {written}\n\nIt contains every credential in clear text.")

    def about(self) -> None:
        """Version and what the tool is."""
        from cloud_driver_installer import __version__

        messagebox.showinfo(
            "cloud-driver installer",
            f"Version {__version__}\n\nInstalls and configures the whole cloud-driver system on a Debian/Ubuntu "
            "root server over SSH: packages, PostgreSQL, Redis, ClamAV, Java, Python, the AWS resources, Caddy, "
            "the configuration files, the application jars and the semantic-search service.",
        )

    def close(self) -> None:
        """Stop a running job, close the session and leave."""
        if self.worker.busy and not messagebox.askyesno("Quit", "A job is still running. Stop it and quit?"):
            return
        self.worker.cancel()
        if self.state.session is not None:
            self.state.session.close()
        self.root.destroy()

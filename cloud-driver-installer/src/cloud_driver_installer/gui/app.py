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
from cloud_driver_installer.gui.widgets import COLORS, StatusDot
from cloud_driver_installer.gui.worker import JobDone, LogEvent, ProbeResult, ProgressEvent, Worker
from cloud_driver_installer.profile import PROFILE_DIR, load_profile, save_profile
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
        root.minsize(980, 640)
        root.geometry("1200x800")
        self._build_menu()

        panes = ttk.PanedWindow(self, orient="vertical")
        panes.pack(fill="both", expand=True)
        upper = ttk.Frame(panes)
        panes.add(upper, weight=4)
        self.log = LogPane(panes)
        panes.add(self.log, weight=1)

        self.sidebar = ttk.Frame(upper, width=250)
        self.sidebar.pack(side="left", fill="y")
        self.sidebar.pack_propagate(False)
        self.content = ttk.Frame(upper)
        self.content.pack(side="left", fill="both", expand=True)

        self.rows: dict[str, dict] = {}
        self._build_sidebar()
        self.pages = {}
        actions = PageActions(
            check=self.check_step,
            apply=self.apply_step,
            probe_aws=self.probe_aws,
            probe_dns=self.worker.probe_dns,
            install=self.install,
            stop=self.worker.cancel,
        )
        for page_id in page_ids():
            page = PAGES[page_id](self.content, state, actions)
            page.place(relwidth=1, relheight=1)
            self.pages[page_id] = page
        self.current = "server"

        status = ttk.Frame(self)
        status.pack(fill="x")
        self.status_left = ttk.Label(status, text="", style="Hint.TLabel")
        self.status_left.pack(side="left", padx=8)
        self.status_right = ttk.Label(status, text="", style="Hint.TLabel")
        self.status_right.pack(side="right", padx=8)

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
        holder = ttk.Frame(self.sidebar)
        holder.pack(fill="both", expand=True)
        for step_id in STEP_IDS:
            row = ttk.Frame(holder)
            row.pack(fill="x", padx=4, pady=1)
            dot = StatusDot(row)
            dot.pack(side="left", padx=(4, 6))
            included = tk.BooleanVar(value=step_id in self.state.included)
            box = ttk.Checkbutton(row, variable=included, command=lambda sid=step_id: self.toggle(sid))
            if step_id in mandatory:
                box.configure(state="disabled")  # the deployment does not work without it
            box.pack(side="right")
            label = ttk.Label(row, text=self.state.title_of(step_id), cursor="hand2")
            label.pack(side="left", anchor="w")
            detail = ttk.Label(holder, text="", style="Hint.TLabel", wraplength=210, justify="left")
            detail.pack(fill="x", padx=(28, 6))
            target = step_id if step_id in PAGES else "summary"
            for widget in (label, row, detail):
                widget.bind("<Button-1>", lambda _event, page=target: self.show(page))
            self.rows[step_id] = {"dot": dot, "label": label, "detail": detail, "included": included}

        buttons = ttk.Frame(self.sidebar)
        buttons.pack(fill="x", side="bottom", pady=6)
        ttk.Button(buttons, text="Check all", command=self.check_all).pack(fill="x", padx=6, pady=2)
        ttk.Button(buttons, text="Install selected steps", style="Primary.TButton", command=self.install).pack(fill="x", padx=6, pady=2)
        ttk.Button(buttons, text="Stop", command=self.worker.cancel).pack(fill="x", padx=6, pady=2)

    # --- navigation ------------------------------------------------------------------------------

    def show(self, page_id: str) -> None:
        """Bring one page to the front."""
        page = self.pages.get(page_id)
        if page is None:
            return
        self.current = page_id
        page.tkraise()
        page.refresh(self.state)
        page.refresh_header(self.state)

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
        row["detail"].configure(text=self.state.details.get(step_id, "")[:160])
        if self.current in (step_id, "summary"):
            self.pages[self.current].refresh_header(self.state)

    def refresh_sidebar(self) -> None:
        """Grey out steps that cannot run and repaint every row."""
        reasons = self.state.dependency_reasons()
        for step_id, row in self.rows.items():
            reason = reasons.get(step_id)
            row["label"].configure(foreground=COLORS["skip"] if reason else COLORS["ink"])
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

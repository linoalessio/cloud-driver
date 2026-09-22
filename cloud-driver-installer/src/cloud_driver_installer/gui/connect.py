"""The connection dialog - the first window, and the only modal one."""

from __future__ import annotations

import json
import threading
import tkinter as tk
from pathlib import Path
from tkinter import ttk
from typing import Callable

from cloud_driver_installer.gui.widgets import COLORS, Form
from cloud_driver_installer.remote import RemoteHost
from cloud_driver_installer.ssh import SshError, SshSession, SshTarget, list_ssh_config_aliases, resolve_alias

#: Remembered connection (never a password or passphrase).
CONNECTION_FILE = Path.home() / ".config" / "cloud-driver-installer" / "connection.json"


def load_connection() -> dict:
    """The last connection the operator chose to remember."""
    try:
        return json.loads(CONNECTION_FILE.read_text())
    except (OSError, ValueError):
        return {}


def save_connection(target: SshTarget) -> None:
    """Remember host, port, user, method and key path - nothing secret."""
    CONNECTION_FILE.parent.mkdir(parents=True, exist_ok=True)
    CONNECTION_FILE.write_text(
        json.dumps({"host": target.host, "port": target.port, "user": target.user, "auth": target.auth, "key_path": target.key_path}, indent=2)
    )


class ConnectDialog(ttk.Frame):
    """Collects the SSH details, opens the session, and hands it to ``on_connected``."""

    def __init__(self, root: tk.Tk, on_connected: Callable[[SshSession, RemoteHost, SshTarget], None]) -> None:
        super().__init__(root, padding=16)
        self.root = root
        self.on_connected = on_connected
        self.pack(fill="both", expand=True)
        root.title("cloud-driver installer — connect")

        remembered = load_connection()
        self.aliases = list_ssh_config_aliases()
        self.host = tk.StringVar(value=remembered.get("host", self.aliases[0].name if self.aliases else ""))
        self.port = tk.StringVar(value=str(remembered.get("port", 22)))
        self.user = tk.StringVar(value=remembered.get("user", "root"))
        self.auth = tk.StringVar(value=remembered.get("auth", "key" if self.aliases else "agent"))
        self.key_path = tk.StringVar(value=remembered.get("key_path", ""))
        self.passphrase = tk.StringVar()
        self.password = tk.StringVar()
        self.remember = tk.BooleanVar(value=True)

        ttk.Label(self, text="Connect to the server", style="Head.TLabel").pack(anchor="w", pady=(0, 10))
        form = Form(self)
        form.pack(fill="x")
        form.combo("Server", self.host, [alias.label for alias in self.aliases], "An alias from ~/.ssh/config, or a host name / IP address.")
        form.entry("Port", self.port, width=8)
        form.entry("User", self.user, "The JVM, its AWS credentials and the managed crontab all belong to root.", width=16)
        form.radios("Authentication", self.auth, [("agent", "SSH agent"), ("key", "Private key"), ("password", "Password")])
        form.path("Key file", self.key_path)
        form.secret("Passphrase", self.passphrase)
        form.secret("Password", self.password)
        form.check("Remember this connection (never the passphrase or password)", self.remember)

        self.status = ttk.Label(self, text="", style="Hint.TLabel", wraplength=520, justify="left")
        self.status.pack(anchor="w", pady=(8, 8))
        buttons = ttk.Frame(self)
        buttons.pack(fill="x")
        ttk.Button(buttons, text="Quit", command=root.destroy).pack(side="right", padx=4)
        self.connect_button = ttk.Button(buttons, text="Connect", style="Primary.TButton", command=self.connect)
        self.connect_button.pack(side="right")
        root.bind("<Return>", lambda _event: self.connect())

    # --- connecting ------------------------------------------------------------------------------

    def target(self) -> SshTarget:
        """The typed connection details, with an alias resolved to its real host."""
        host = self.host.get().split(" ")[0].strip()
        try:
            port = int(self.port.get())
        except ValueError as exc:
            raise SshError("the port must be a number") from exc
        return resolve_alias(
            SshTarget(
                host=host,
                port=port,
                user=self.user.get().strip() or "root",
                auth=self.auth.get(),
                key_path=self.key_path.get().strip(),
                key_passphrase=self.passphrase.get(),
                password=self.password.get(),
            ),
            self.aliases,
        )

    def connect(self) -> None:
        """Open the session on a worker thread and report progress in the status line."""
        self.connect_button.configure(state="disabled")
        self._say("Connecting…")

        def run() -> None:
            try:
                target = self.target()
                session = SshSession(target=target, host_key_prompt=self._ask_host_key)
                session.connect()
                uid = session.exec("id -u").text
                if uid != "0":
                    raise SshError(f"logged in as uid {uid or '?'} - connect as root")
                self.root.after(0, lambda: self._say("Checking apt and systemd…"))
                for command, message in (("command -v apt-get", "this host has no apt-get"), ("command -v systemctl", "this host has no systemd")):
                    if session.exec(command).code != 0:
                        raise SshError(message)
                remote = RemoteHost(session=session, log=lambda level, message: None)
                if self.remember.get():
                    save_connection(target)
                self.root.after(0, lambda: self.on_connected(session, remote, target))
            except SshError as exc:
                self.root.after(0, lambda: self._fail(str(exc)))
            except Exception as exc:  # noqa: BLE001 - the dialog must show, not swallow, the reason
                self.root.after(0, lambda: self._fail(f"{type(exc).__name__}: {exc}"))

        threading.Thread(target=run, name="installer-connect", daemon=True).start()

    def _ask_host_key(self, hostname: str, key_type: str, fingerprint: str) -> bool:
        """Show the fingerprint on the Tk thread and block the worker until the operator answers."""
        answer: dict[str, bool] = {}
        done = threading.Event()

        def ask() -> None:
            window = tk.Toplevel(self.root)
            window.title("Unknown host key")
            window.transient(self.root)
            window.grab_set()
            body = ttk.Frame(window, padding=16)
            body.pack(fill="both", expand=True)
            ttk.Label(body, text=f"{hostname} is not in the installer's known hosts yet.").pack(anchor="w")
            ttk.Label(body, text=f"{key_type} · {fingerprint}", style="Mono.TLabel").pack(anchor="w", pady=6)
            ttk.Label(
                body,
                text="Compare this with the fingerprint your provider shows. Trusting stores it in "
                "~/.config/cloud-driver-installer/known_hosts, never in your own ~/.ssh/known_hosts.",
                style="Hint.TLabel",
                wraplength=420,
                justify="left",
            ).pack(anchor="w")
            buttons = ttk.Frame(body)
            buttons.pack(fill="x", pady=(10, 0))

            def answer_with(value: bool) -> None:
                answer["trust"] = value
                window.destroy()
                done.set()

            ttk.Button(buttons, text="Abort", command=lambda: answer_with(False)).pack(side="right", padx=4)
            ttk.Button(buttons, text="Trust and connect", style="Primary.TButton", command=lambda: answer_with(True)).pack(side="right")
            window.protocol("WM_DELETE_WINDOW", lambda: answer_with(False))

        self.root.after(0, ask)
        done.wait()
        return answer.get("trust", False)

    def _say(self, text: str) -> None:
        self.status.configure(text=text, foreground=COLORS["muted"])

    def _fail(self, text: str) -> None:
        self.status.configure(text=text, foreground=COLORS["fail"])
        self.connect_button.configure(state="normal")

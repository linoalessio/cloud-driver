"""The connect dialog: the first and only modal.

Picks (or types) a server, authenticates, trusts an unknown host key explicitly, probes the box
(``id -u``, ``apt-get``, ``systemctl``) and hands a connected
:class:`~cloud_driver_installer.ssh.SshSession` plus a :class:`~cloud_driver_installer.remote.RemoteHost`
to ``on_connected``. Everything that can block runs on a thread; the host-key question is
marshalled back to the Tk thread (a queue polled with ``after`` + a ``threading.Event`` the
worker waits on) and shown as the mockup's "Unknown host key" window.

"Remember this connection" stores host, port, user, auth method and key path - never a
passphrase or password - in ``~/.config/cloud-driver-installer/connection.json``.
"""

from __future__ import annotations

import json
import queue
import threading
import tkinter as tk
from dataclasses import asdict, dataclass
from pathlib import Path
from tkinter import ttk
from typing import Any, Callable

from cloud_driver_installer.gui.widgets import (
    COLORS,
    STYLE_ERROR,
    STYLE_MUTED,
    STYLE_PILL,
    STYLE_PRIMARY,
    STYLE_TITLE,
    Field,
    Hint,
    PathPicker,
    RadioRow,
    SecretEntry,
    StatusLine,
    fonts,
    set_enabled,
)
from cloud_driver_installer.remote import RemoteHost
from cloud_driver_installer.ssh import INSTALLER_KNOWN_HOSTS, SshAlias, SshError, SshSession, SshTarget, list_ssh_config_aliases, resolve_alias

#: Where "Remember this connection" writes to.
CONNECTION_FILE = Path.home() / ".config" / "cloud-driver-installer" / "connection.json"

#: ``on_connected(session, remote, target)``.
ConnectedCallback = Callable[[SshSession, RemoteHost, SshTarget], None]

_AUTH_OPTIONS: tuple[tuple[str, str], ...] = (("agent", "SSH agent"), ("key", "Private key"), ("password", "Password"))


# --- remembered connection -------------------------------------------------------------------------


@dataclass
class RememberedConnection:
    """The non-secret half of a target, as stored in :data:`CONNECTION_FILE`."""

    host: str = ""
    port: int = 22
    user: str = "root"
    auth: str = "key"
    key_path: str = ""

    def as_target(self) -> SshTarget:
        """A target with blank secrets."""
        return SshTarget(host=self.host, port=self.port, user=self.user, auth=self.auth, key_path=self.key_path)


def load_remembered(path: Path = CONNECTION_FILE) -> RememberedConnection | None:
    """Read the remembered connection; ``None`` when absent or unreadable."""
    try:
        data = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    if not isinstance(data, dict):
        return None
    try:
        return RememberedConnection(
            host=str(data.get("host", "")),
            port=int(data.get("port", 22) or 22),
            user=str(data.get("user", "root") or "root"),
            auth=str(data.get("auth", "key") or "key"),
            key_path=str(data.get("key_path", "") or ""),
        )
    except (TypeError, ValueError):
        return None


def save_remembered(target: SshTarget, path: Path = CONNECTION_FILE) -> None:
    """Persist ``target`` without its secrets (mode 0600)."""
    remembered = RememberedConnection(host=target.alias or target.host, port=target.port, user=target.user, auth=target.auth, key_path=target.key_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(asdict(remembered), indent=2) + "\n")
    try:
        path.chmod(0o600)
    except OSError:
        pass


def forget_remembered(path: Path = CONNECTION_FILE) -> None:
    """Delete the remembered connection, if any."""
    try:
        path.unlink()
    except OSError:
        pass


# --- probe -----------------------------------------------------------------------------------------


@dataclass
class ProbeResult:
    """What the quick post-connect probe learned."""

    uid: int = -1
    has_apt: bool = False
    has_systemd: bool = False
    sudo_ok: bool | None = None  # None = not tried (root)
    os_pretty: str = ""

    @property
    def is_root(self) -> bool:
        """True when the login user is uid 0."""
        return self.uid == 0

    def summary(self) -> str:
        """``root · apt · systemd`` style line for the status row and the log."""
        who = "root" if self.is_root else f"uid {self.uid}" + (" · sudo -n ok" if self.sudo_ok else "")
        parts = [self.os_pretty or "", who, "apt" if self.has_apt else "no apt-get", "systemd" if self.has_systemd else "no systemd"]
        return " · ".join(p for p in parts if p)

    def problems(self) -> list[str]:
        """Why the installer cannot work on this host (empty = fine)."""
        problems: list[str] = []
        if not self.is_root and not self.sudo_ok:
            problems.append("the user is not root and passwordless sudo (sudo -n) does not work")
        if not self.has_apt:
            problems.append("apt-get was not found - only Debian/Ubuntu servers are supported")
        if not self.has_systemd:
            problems.append("systemctl was not found - the installer manages systemd units")
        return problems


def probe_host(session: SshSession) -> ProbeResult:
    """Run the quick probe over ``session``."""
    result = ProbeResult()
    uid_result = session.exec("id -u", timeout=30)
    if uid_result.ok and uid_result.text.isdigit():
        result.uid = int(uid_result.text)
    result.has_apt = session.exec("command -v apt-get >/dev/null 2>&1", timeout=30).ok
    result.has_systemd = session.exec("command -v systemctl >/dev/null 2>&1", timeout=30).ok
    if not result.is_root:
        result.sudo_ok = session.exec("sudo -n true", timeout=30).ok
    pretty = session.exec(". /etc/os-release 2>/dev/null && printf '%s' \"$PRETTY_NAME\"", timeout=30)
    if pretty.ok:
        result.os_pretty = pretty.text
    return result


def _discard_log(level: str, message: str) -> None:
    """Initial log sink of the :class:`RemoteHost`; the :class:`~cloud_driver_installer.gui.worker.Worker` replaces it."""


# --- host key window -------------------------------------------------------------------------------


class HostKeyDialog(tk.Toplevel):
    """The "Unknown host key" window: fingerprint, Abort / Trust and connect."""

    def __init__(self, parent: tk.Misc, hostname: str, key_type: str, fingerprint: str) -> None:
        super().__init__(parent)
        self.title("Unknown host key")
        self.resizable(False, False)
        self.transient(parent.winfo_toplevel())
        self.trusted = False
        f = fonts(self)
        body = ttk.Frame(self, padding=(20, 16, 20, 16))
        body.pack(fill="both", expand=True)
        ttk.Label(body, text=f"{hostname} is not in the installer's known hosts yet.", font=f.bold).pack(anchor="w")
        ttk.Label(body, text=f"{key_type.upper()} · {fingerprint}", font=f.mono).pack(anchor="w", pady=(8, 8))
        Hint(
            body,
            "Compare with the fingerprint your provider shows. Trusting stores it in "
            f"{INSTALLER_KNOWN_HOSTS}, never in your own ~/.ssh/known_hosts.",
            wraplength=460,
        ).pack(anchor="w", fill="x")
        buttons = ttk.Frame(body)
        buttons.pack(fill="x", pady=(14, 0))
        ttk.Button(buttons, text="Trust and connect", style=STYLE_PRIMARY, command=self._trust).pack(side="right")
        ttk.Button(buttons, text="Abort", command=self._abort).pack(side="right", padx=(0, 8))
        self.protocol("WM_DELETE_WINDOW", self._abort)
        self.bind("<Escape>", lambda _e: self._abort())
        self.bind("<Return>", lambda _e: self._trust())
        self.update_idletasks()
        self.grab_set()
        self.focus_set()

    def _trust(self) -> None:
        self.trusted = True
        self.destroy()

    def _abort(self) -> None:
        self.trusted = False
        self.destroy()

    @classmethod
    def ask(cls, parent: tk.Misc, hostname: str, key_type: str, fingerprint: str) -> bool:
        """Show modally and return whether the operator trusted the key."""
        dialog = cls(parent, hostname, key_type, fingerprint)
        parent.wait_window(dialog)
        return dialog.trusted


# --- the dialog ------------------------------------------------------------------------------------


class ConnectDialog(tk.Toplevel):
    """``cloud-driver installer — connect``.

    ``on_connected(session, remote, target)`` is called on the Tk thread once the probe passed;
    the dialog then closes. Quit (or closing the window while ``hide_root``) destroys the root.
    """

    POLL_MS = 100

    def __init__(
        self,
        root: tk.Misc,
        on_connected: ConnectedCallback,
        *,
        aliases: list[SshAlias] | None = None,
        remembered: RememberedConnection | None = None,
        hide_root: bool = True,
    ) -> None:
        super().__init__(root)
        self._root = root
        self._on_connected = on_connected
        self._hide_root = hide_root
        self._queue: "queue.Queue[tuple[Any, ...]]" = queue.Queue()
        self._thread: threading.Thread | None = None
        self._session: SshSession | None = None
        self.aliases: list[SshAlias] = aliases if aliases is not None else self._safe_aliases()
        self._alias_by_label: dict[str, SshAlias] = {alias.label: alias for alias in self.aliases}
        self.title("cloud-driver installer — connect")
        self.resizable(False, False)
        if hide_root:
            try:
                root.withdraw()
            except tk.TclError:
                pass
        self._build()
        self._prefill(remembered if remembered is not None else load_remembered())
        self.protocol("WM_DELETE_WINDOW", self.quit_installer if hide_root else self.destroy)
        self.bind("<Return>", lambda _e: self.connect())
        self.bind("<Escape>", lambda _e: (self.quit_installer() if hide_root else self.destroy()))
        self.update_idletasks()
        self.focus_set()

    @staticmethod
    def _safe_aliases() -> list[SshAlias]:
        try:
            return list_ssh_config_aliases()
        except Exception:  # noqa: BLE001 - a malformed ~/.ssh/config must not block the dialog
            return []

    # --- layout ----------------------------------------------------------------------------------

    def _build(self) -> None:
        f = fonts(self)
        body = ttk.Frame(self, padding=(20, 18, 20, 16))
        body.pack(fill="both", expand=True)
        ttk.Label(body, text="Connect to the server", style=STYLE_TITLE, font=f.title).pack(anchor="w", pady=(0, 10))

        field = Field(body)
        field.pack(fill="x")
        self.host_var = tk.StringVar(master=self)
        self.host_box = ttk.Combobox(field, textvariable=self.host_var, values=list(self._alias_by_label), width=44, font=f.mono)
        self.host_box.bind("<<ComboboxSelected>>", self._on_alias_selected)
        field.add("Server", self.host_box, hint="Aliases read from ~/.ssh/config; pick one or type a host.")

        self.port_var = tk.StringVar(master=self, value="22")
        self.port_entry = ttk.Entry(field, textvariable=self.port_var, width=7, font=f.mono)
        field.add("Port", self.port_entry, sticky="w")

        user_row = field.row("User")
        self.user_var = tk.StringVar(master=self, value="root")
        self.user_entry = ttk.Entry(user_row, textvariable=self.user_var, width=18, font=f.mono)
        self.user_entry.pack(side="left")
        self.user_pill = ttk.Label(user_row, text="", style=STYLE_PILL)
        self.user_pill.pack(side="left", padx=(10, 0))
        self.user_var.trace_add("write", lambda *_: self._update_user_pill())
        self._update_user_pill()

        self.auth_var = tk.StringVar(master=self, value="key")
        self.auth_row = RadioRow(field, self.auth_var, _AUTH_OPTIONS, command=self._update_auth_fields)
        field.add("Authentication", self.auth_row, sticky="w")

        self.key_var = tk.StringVar(master=self)
        self.key_picker = PathPicker(field, self.key_var, kind="file", title="Choose the private key", width=40)
        field.add("Key file", self.key_picker)

        self.passphrase_entry = SecretEntry(field, width=32)
        self.passphrase_entry.set_note("optional")
        field.add("Passphrase", self.passphrase_entry)

        self.password_entry = SecretEntry(field, width=32)
        field.add("Password", self.password_entry)

        self.remember_var = tk.BooleanVar(master=self, value=True)
        field.check("Remember this connection (never the passphrase or password)", self.remember_var)

        self.status = StatusLine(body)
        self.status.pack(fill="x", pady=(12, 0))
        self.error_label = Hint(body, "", style=STYLE_ERROR, wraplength=470)
        self.error_label.configure(foreground=COLORS["fail"])
        self.error_label.pack(fill="x", pady=(4, 0))

        ttk.Separator(body).pack(fill="x", pady=(12, 10))
        actions = ttk.Frame(body)
        actions.pack(fill="x")
        self.connect_button = ttk.Button(actions, text="Connect", style=STYLE_PRIMARY, command=self.connect)
        self.connect_button.pack(side="right")
        self.quit_button = ttk.Button(actions, text="Quit", command=self.quit_installer)
        self.quit_button.pack(side="right", padx=(0, 8))
        self._update_auth_fields()

    def _prefill(self, remembered: RememberedConnection | None) -> None:
        if remembered is None:
            if self.aliases:
                self.host_var.set(self.aliases[0].label)
                self._on_alias_selected()
            return
        alias = next((a for a in self.aliases if a.name == remembered.host), None)
        self.host_var.set(alias.label if alias else remembered.host)
        self.port_var.set(str(remembered.port))
        self.user_var.set(remembered.user or "root")
        self.auth_var.set(remembered.auth if remembered.auth in ("agent", "key", "password") else "key")
        self.key_var.set(remembered.key_path)
        self._update_auth_fields()

    # --- form behaviour --------------------------------------------------------------------------

    def _on_alias_selected(self, _event: object = None) -> None:
        alias = self._alias_by_label.get(self.host_var.get())
        if alias is None:
            return
        self.port_var.set(str(alias.port or 22))
        if alias.user:
            self.user_var.set(alias.user)
        if alias.identity_file:
            self.key_var.set(alias.identity_file)
            self.auth_var.set("key")
        self._update_auth_fields()

    def _update_user_pill(self) -> None:
        user = self.user_var.get().strip()
        if user == "root":
            self.user_pill.configure(text="root · no sudo needed", foreground=COLORS["ok"])
        else:
            self.user_pill.configure(text="sudo -n will be used", foreground=COLORS["muted"])

    def _update_auth_fields(self) -> None:
        auth = self.auth_var.get()
        for widget in (self.key_picker.entry, self.key_picker.browse_button):
            set_enabled(widget, auth == "key")
        set_enabled(self.passphrase_entry.entry, auth == "key")
        set_enabled(self.passphrase_entry.toggle_button, auth == "key")
        set_enabled(self.password_entry.entry, auth == "password")
        set_enabled(self.password_entry.toggle_button, auth == "password")

    def current_target(self) -> SshTarget:
        """The target described by the form (``ValueError`` on a bad port or blank host)."""
        text = self.host_var.get().strip()
        alias = self._alias_by_label.get(text)
        host = alias.name if alias else text
        if not host:
            raise ValueError("Server: enter a host, an IP address or an ssh-config alias")
        port_text = self.port_var.get().strip()
        if not port_text.isdigit() or not 1 <= int(port_text) <= 65535:
            raise ValueError("Port: must be 1-65535")
        user = self.user_var.get().strip() or "root"
        auth = self.auth_var.get()
        return SshTarget(
            host=host,
            port=int(port_text),
            user=user,
            auth=auth,
            key_path=self.key_var.get().strip() if auth == "key" else "",
            key_passphrase=self.passphrase_entry.get_value() if auth == "key" else "",
            password=self.password_entry.get_value() if auth == "password" else "",
        )

    def show_error(self, message: str | None) -> None:
        """Red text under the form (``None`` clears)."""
        self.error_label.configure(text=message or "")

    def _set_form_enabled(self, enabled: bool) -> None:
        for widget in (self.host_box, self.port_entry, self.user_entry, self.connect_button):
            set_enabled(widget, enabled)
        self.auth_row.set_enabled(enabled)
        if enabled:
            self._update_auth_fields()
        else:
            for widget in (
                self.key_picker.entry,
                self.key_picker.browse_button,
                self.passphrase_entry.entry,
                self.passphrase_entry.toggle_button,
                self.password_entry.entry,
                self.password_entry.toggle_button,
            ):
                set_enabled(widget, False)

    # --- connecting ------------------------------------------------------------------------------

    def connect(self) -> None:
        """Validate the form and start the connection thread."""
        if self._thread is not None and self._thread.is_alive():
            return
        self.show_error(None)
        try:
            target = self.current_target()
        except ValueError as exc:
            self.show_error(str(exc))
            return
        if self.remember_var.get():
            try:
                save_remembered(target)
            except OSError:
                pass
        else:
            forget_remembered()
        self._set_form_enabled(False)
        self.status.show(f"Connecting to {target.label()}…", "run")
        self._thread = threading.Thread(target=self._connect_thread, args=(target,), name="installer-connect", daemon=True)
        self._thread.start()
        self.after(self.POLL_MS, self._poll)

    def _connect_thread(self, target: SshTarget) -> None:
        session: SshSession | None = None
        try:
            resolved = resolve_alias(target, self.aliases)
            session = SshSession(resolved, host_key_prompt=self._host_key_prompt)
            session.connect()
            self._queue.put(("status", "Connected — checking root, apt and systemd on the host…"))
            probe = probe_host(session)
            self._queue.put(("done", session, resolved, probe))
        except SshError as exc:
            if session is not None:
                session.close()
            self._queue.put(("error", str(exc)))
        except Exception as exc:  # noqa: BLE001 - must surface in the dialog, never kill the thread silently
            if session is not None:
                session.close()
            self._queue.put(("error", f"{type(exc).__name__}: {exc}"))

    def _host_key_prompt(self, hostname: str, key_type: str, fingerprint: str) -> bool:
        """Called on the connection thread; blocks until the Tk thread answered."""
        answered = threading.Event()
        holder: list[bool] = []
        self._queue.put(("hostkey", hostname, key_type, fingerprint, answered, holder))
        answered.wait()
        return bool(holder and holder[0])

    def _poll(self) -> None:
        try:
            while True:
                message = self._queue.get_nowait()
                self._handle(message)
        except queue.Empty:
            pass
        if self._thread is not None and self._thread.is_alive():
            self.after(self.POLL_MS, self._poll)
        elif not self._queue.empty():
            self.after(self.POLL_MS, self._poll)

    def _handle(self, message: tuple[Any, ...]) -> None:
        kind = message[0]
        if kind == "status":
            self.status.show(str(message[1]), "run")
        elif kind == "hostkey":
            _, hostname, key_type, fingerprint, answered, holder = message
            try:
                holder.append(HostKeyDialog.ask(self, hostname, key_type, fingerprint))
            finally:
                answered.set()
        elif kind == "error":
            self.status.show("Not connected", "fail")
            self.show_error(str(message[1]))
            self._set_form_enabled(True)
        elif kind == "done":
            _, session, target, probe = message
            self._finish(session, target, probe)

    def _finish(self, session: SshSession, target: SshTarget, probe: ProbeResult) -> None:
        problems = probe.problems()
        if problems:
            session.close()
            self.status.show("Not connected", "fail")
            self.show_error(f"Cannot install on {target.label()}: " + "; ".join(problems))
            self._set_form_enabled(True)
            return
        remote = RemoteHost(session, log=_discard_log, use_sudo=not probe.is_root)
        self.status.show(f"Connected to {target.label()} · {probe.summary()}", "ok")
        self._session = session
        if self._hide_root:
            try:
                self._root.deiconify()
            except tk.TclError:
                pass
        try:
            self._on_connected(session, remote, target)
        finally:
            self.destroy()

    def quit_installer(self) -> None:
        """Quit: close any half-open session and destroy the root window."""
        if self._session is not None:
            self._session.close()
        self._root.destroy()

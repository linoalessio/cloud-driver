"""The SSH transport: one paramiko session per installer run, plus ``~/.ssh/config`` alias lookup.

Design points that matter for an operator tool:

* Host keys are never trusted blindly. On first contact the caller's ``host_key_prompt`` is shown
  the key type and SHA-256 fingerprint and must answer yes; accepted keys are stored in the
  installer's own file (``~/.config/cloud-driver-installer/known_hosts``), never written into the
  user's ``~/.ssh/known_hosts`` (which is still *read*, so a host the user already trusts needs no
  prompt).
* Secrets never travel on a command line. :meth:`SshSession.exec` accepts ``input`` and feeds it
  over stdin, so a password can be piped to ``cat > file`` or exported through the environment
  without ever being visible in ``ps`` on the server.
* Output is streamed line by line through ``on_line`` while the command runs, so a long
  ``apt-get`` or ``pip install`` shows progress in the log instead of appearing all at once.
"""

from __future__ import annotations

import base64
import hashlib
import os
import select
import socket
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterator

import paramiko

#: Where the installer keeps the host keys the operator accepted through it.
INSTALLER_KNOWN_HOSTS = Path.home() / ".config" / "cloud-driver-installer" / "known_hosts"

#: ``(hostname, key_type, sha256_fingerprint) -> trust?``
HostKeyPrompt = Callable[[str, str, str], bool]
LineSink = Callable[[str, str], None]  # (stream: "out"|"err", line)


class SshError(Exception):
    """Connection, authentication or host-key failure, with a message fit for the GUI."""


@dataclass
class SshAlias:
    """One ``Host`` entry from ``~/.ssh/config``."""

    name: str
    hostname: str
    port: int = 22
    user: str = ""
    identity_file: str = ""
    proxy: str = ""  # ProxyJump / ProxyCommand, unsupported - surfaced so the GUI can say so

    @property
    def label(self) -> str:
        """``cloud_driver  (root@82.165.48.39, id_ed25519_strato)``."""
        who = f"{self.user}@" if self.user else ""
        key = f", {Path(self.identity_file).name}" if self.identity_file else ""
        return f"{self.name}  ({who}{self.hostname}{key})"


@dataclass
class ExecResult:
    """Outcome of one remote command."""

    command: str
    code: int
    out: str
    err: str

    @property
    def ok(self) -> bool:
        return self.code == 0

    @property
    def text(self) -> str:
        """stdout, stripped."""
        return self.out.strip()


@dataclass
class SshTarget:
    """Where and how to connect. ``auth`` is ``agent``, ``key`` or ``password``."""

    host: str
    port: int = 22
    user: str = "root"
    auth: str = "key"
    key_path: str = ""
    key_passphrase: str = ""
    password: str = ""
    alias: str = ""

    def label(self) -> str:
        return f"{self.user}@{self.host}" + (f":{self.port}" if self.port != 22 else "")


def list_ssh_config_aliases(config_path: Path | None = None) -> list[SshAlias]:
    """Parse ``~/.ssh/config`` (or ``config_path``) into concrete aliases, wildcards excluded."""
    path = config_path or (Path.home() / ".ssh" / "config")
    if not path.is_file():
        return []
    config = paramiko.SSHConfig()
    with path.open() as handle:
        config.parse(handle)
    aliases: list[SshAlias] = []
    for name in sorted(config.get_hostnames()):
        if any(ch in name for ch in "*?!"):
            continue
        entry = config.lookup(name)
        identity = entry.get("identityfile") or []
        if isinstance(identity, str):
            identity = [identity]
        aliases.append(
            SshAlias(
                name=name,
                hostname=entry.get("hostname", name),
                port=int(entry.get("port", 22)),
                user=entry.get("user", ""),
                identity_file=os.path.expanduser(identity[0]) if identity else "",
                proxy=entry.get("proxyjump", "") or entry.get("proxycommand", ""),
            )
        )
    return aliases


def resolve_alias(target: SshTarget, aliases: list[SshAlias] | None = None) -> SshTarget:
    """If ``target.host`` names an alias, return a copy with host/port/user/key filled from it."""
    for alias in aliases if aliases is not None else list_ssh_config_aliases():
        if alias.name == target.host:
            if alias.proxy:
                raise SshError(
                    f"ssh alias {alias.name!r} uses ProxyJump/ProxyCommand, which the installer does not "
                    "support - connect to the final host directly"
                )
            return SshTarget(
                host=alias.hostname,
                port=alias.port or target.port,
                user=alias.user or target.user,
                auth=target.auth if target.auth != "key" or target.key_path else ("key" if alias.identity_file else target.auth),
                key_path=target.key_path or alias.identity_file,
                key_passphrase=target.key_passphrase,
                password=target.password,
                alias=alias.name,
            )
    return target


def fingerprint_sha256(key: paramiko.PKey) -> str:
    """OpenSSH-style ``SHA256:…`` fingerprint (unpadded base64)."""
    digest = hashlib.sha256(key.asbytes()).digest()
    return "SHA256:" + base64.b64encode(digest).decode("ascii").rstrip("=")


class _PromptingHostKeyPolicy(paramiko.MissingHostKeyPolicy):
    """Asks the operator once per unknown host and persists an accepted key to the installer file."""

    def __init__(self, prompt: HostKeyPrompt, store: Path) -> None:
        self._prompt = prompt
        self._store = store

    def missing_host_key(self, client: paramiko.SSHClient, hostname: str, key: paramiko.PKey) -> None:
        if not self._prompt(hostname, key.get_name(), fingerprint_sha256(key)):
            raise SshError(f"host key for {hostname} was not trusted - connection aborted")
        client.get_host_keys().add(hostname, key.get_name(), key)
        self._store.parent.mkdir(parents=True, exist_ok=True)
        client.save_host_keys(str(self._store))
        try:
            os.chmod(self._store, 0o600)
        except OSError:
            pass


@dataclass
class SshSession:
    """A connected paramiko client. Use as a context manager or call :meth:`close`."""

    target: SshTarget
    host_key_prompt: HostKeyPrompt
    known_hosts_path: Path = field(default_factory=lambda: INSTALLER_KNOWN_HOSTS)
    connect_timeout: float = 15.0
    _client: paramiko.SSHClient | None = field(default=None, init=False, repr=False)
    _sftp: paramiko.SFTPClient | None = field(default=None, init=False, repr=False)

    # --- lifecycle -----------------------------------------------------------------------------

    def connect(self) -> None:
        """Open the connection, authenticating as the target says; raises :class:`SshError`."""
        client = paramiko.SSHClient()
        user_known = Path.home() / ".ssh" / "known_hosts"
        if user_known.is_file():
            try:
                client.load_host_keys(str(user_known))
            except Exception:  # a malformed line must not block the installer
                pass
        if self.known_hosts_path.is_file():
            try:
                client.get_host_keys().load(str(self.known_hosts_path))
            except Exception:
                pass
        client.set_missing_host_key_policy(_PromptingHostKeyPolicy(self.host_key_prompt, self.known_hosts_path))

        kwargs: dict = {
            "hostname": self.target.host,
            "port": self.target.port,
            "username": self.target.user,
            "timeout": self.connect_timeout,
            "banner_timeout": self.connect_timeout,
            "auth_timeout": self.connect_timeout,
            "allow_agent": self.target.auth == "agent",
            "look_for_keys": self.target.auth == "agent",
        }
        if self.target.auth == "key":
            if not self.target.key_path:
                raise SshError("no private key file given")
            key_path = os.path.expanduser(self.target.key_path)
            if not os.path.isfile(key_path):
                raise SshError(f"private key not found: {key_path}")
            kwargs["key_filename"] = key_path
            if self.target.key_passphrase:
                kwargs["passphrase"] = self.target.key_passphrase
        elif self.target.auth == "password":
            if not self.target.password:
                raise SshError("no password given")
            kwargs["password"] = self.target.password
        elif self.target.auth != "agent":
            raise SshError(f"unknown authentication method {self.target.auth!r}")

        try:
            client.connect(**kwargs)
        except paramiko.PasswordRequiredException as exc:
            raise SshError("the private key is encrypted - enter its passphrase") from exc
        except paramiko.AuthenticationException as exc:
            raise SshError(f"authentication as {self.target.user} failed: {exc}") from exc
        except SshError:
            raise
        except (paramiko.SSHException, socket.error, OSError) as exc:
            raise SshError(f"could not connect to {self.target.label()}: {exc}") from exc
        transport = client.get_transport()
        if transport is not None:
            transport.set_keepalive(30)
        self._client = client

    def close(self) -> None:
        """Close SFTP and the client; safe to call twice."""
        if self._sftp is not None:
            try:
                self._sftp.close()
            finally:
                self._sftp = None
        if self._client is not None:
            try:
                self._client.close()
            finally:
                self._client = None

    def __enter__(self) -> "SshSession":
        if self._client is None:
            self.connect()
        return self

    def __exit__(self, *_exc) -> None:
        self.close()

    @property
    def connected(self) -> bool:
        transport = self._client.get_transport() if self._client else None
        return bool(transport and transport.is_active())

    # --- commands --------------------------------------------------------------------------------

    def exec(
        self,
        command: str,
        *,
        input: str | bytes | None = None,
        timeout: float | None = 600.0,
        on_line: LineSink | None = None,
    ) -> ExecResult:
        """Run ``command`` (a shell string) and return its exit code and captured output.

        ``input`` is written to the command's stdin and stdin is then closed. ``on_line`` receives
        every complete line as it arrives (``"out"``/``"err"``). ``timeout`` bounds the *whole*
        command; ``None`` waits forever (use for apt/pip/Maven).
        """
        if self._client is None:
            raise SshError("not connected")
        transport = self._client.get_transport()
        if transport is None or not transport.is_active():
            raise SshError("SSH connection lost")
        channel = transport.open_session()
        channel.settimeout(1.0)
        channel.exec_command(command)
        if input is not None:
            data = input.encode("utf-8") if isinstance(input, str) else input
            try:
                channel.sendall(data)
            finally:
                channel.shutdown_write()
        else:
            channel.shutdown_write()

        out_parts: list[bytes] = []
        err_parts: list[bytes] = []
        out_buf = b""
        err_buf = b""
        started = time.monotonic()

        def drain() -> bool:
            nonlocal out_buf, err_buf
            got = False
            while channel.recv_ready():
                chunk = channel.recv(65536)
                if not chunk:
                    break
                got = True
                out_parts.append(chunk)
                out_buf = _emit_lines(out_buf + chunk, "out", on_line)
            while channel.recv_stderr_ready():
                chunk = channel.recv_stderr(65536)
                if not chunk:
                    break
                got = True
                err_parts.append(chunk)
                err_buf = _emit_lines(err_buf + chunk, "err", on_line)
            return got

        while True:
            got = drain()
            if channel.exit_status_ready() and not got:
                drain()
                break
            if timeout is not None and time.monotonic() - started > timeout:
                channel.close()
                raise SshError(f"command timed out after {int(timeout)}s: {command[:120]}")
            if not got:
                try:
                    select.select([channel], [], [], 0.2)
                except (ValueError, OSError):
                    time.sleep(0.2)
        if out_buf and on_line:
            on_line("out", out_buf.decode("utf-8", "replace"))
        if err_buf and on_line:
            on_line("err", err_buf.decode("utf-8", "replace"))
        code = channel.recv_exit_status()
        channel.close()
        return ExecResult(
            command=command,
            code=code,
            out=b"".join(out_parts).decode("utf-8", "replace"),
            err=b"".join(err_parts).decode("utf-8", "replace"),
        )

    # --- files -----------------------------------------------------------------------------------

    def sftp(self) -> paramiko.SFTPClient:
        """The lazily opened SFTP channel."""
        if self._client is None:
            raise SshError("not connected")
        if self._sftp is None:
            self._sftp = self._client.open_sftp()
        return self._sftp

    def upload(self, local: str | Path, remote: str, progress: Callable[[int, int], None] | None = None) -> None:
        """Copy ``local`` to ``remote`` (a full remote path) over SFTP."""
        self.sftp().put(str(local), remote, callback=progress)

    def upload_bytes(self, data: bytes, remote: str) -> None:
        """Write ``data`` to ``remote`` over SFTP."""
        with self.sftp().open(remote, "wb") as handle:
            handle.write(data)

    def download_bytes(self, remote: str) -> bytes:
        """Read ``remote`` over SFTP."""
        with self.sftp().open(remote, "rb") as handle:
            return handle.read()


def _emit_lines(buffer: bytes, stream: str, on_line: LineSink | None) -> bytes:
    """Hand every complete line in ``buffer`` to ``on_line``; return the unterminated remainder."""
    if on_line is None:
        return b""
    while True:
        newline = buffer.find(b"\n")
        if newline < 0:
            return buffer
        line = buffer[:newline].rstrip(b"\r").decode("utf-8", "replace")
        buffer = buffer[newline + 1 :]
        on_line(stream, line)


def iter_lines(text: str) -> Iterator[str]:
    """Non-empty, stripped lines of ``text``."""
    for line in text.splitlines():
        line = line.strip()
        if line:
            yield line

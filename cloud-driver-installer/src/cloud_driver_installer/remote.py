"""High-level operations on the target server, built on :class:`~cloud_driver_installer.ssh.SshSession`.

Every step talks to the server through a :class:`RemoteHost` and nothing else, which is also what
makes the steps testable: the tests substitute a scripted fake with the same surface.

Conventions enforced here so no step has to remember them:

* Commands run through ``bash -c`` (``sudo -n`` prefixed when the login user is not root), so a
  step may use heredocs, pipes and ``$VAR`` freely inside the one string it passes.
* A secret is never placed on a command line: :meth:`run` takes ``input`` for stdin and
  :meth:`run_with_env` exports secrets through stdin-fed ``read`` calls.
* Every file written with :meth:`put_text`/:meth:`put_file` is uploaded to a temporary path in
  the destination directory, given its mode, and renamed over the target (``mv -f`` - an atomic
  rename, so a running JVM that re-reads ``configuration.json`` on every access can never see a
  half-written file), then checked with SHA-256 on both ends (a corrupted jar upload has bitten
  this project before). An existing target is first copied, permissions preserved, into the run's
  backup tree ``/var/backups/cloud-driver-installer/<run>/<original path>`` unless ``backup=False``
  - never as a sidecar next to the original, which a daemon scanning that directory
  (``/etc/logrotate.d``, ``.d`` drop-in dirs) would pick up as a second config file.
"""

from __future__ import annotations

import hashlib
import shlex
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Protocol

from cloud_driver_installer.ssh import ExecResult, SshError, SshSession

LogFn = Callable[[str, str], None]  # (level, message) - levels: DEBUG INFO WARN ERROR OK
ProgressFn = Callable[[int, int], None]  # (bytes_done, bytes_total)


class RemoteError(Exception):
    """A remote command failed where success was required."""

    def __init__(self, message: str, result: ExecResult | None = None) -> None:
        super().__init__(message)
        self.result = result


class Remote(Protocol):
    """What every step may assume about its server handle (implemented by :class:`RemoteHost` and the test fake)."""

    def run(self, command: str, *, input: str | None = ..., timeout: float | None = ..., check: bool = ..., quiet: bool = ...) -> ExecResult: ...
    def run_ok(self, command: str, *, timeout: float | None = ...) -> bool: ...
    def exists(self, path: str) -> bool: ...
    def read_text(self, path: str) -> str | None: ...
    def put_text(self, path: str, text: str, *, mode: int = ..., backup: bool = ...) -> None: ...
    def put_file(self, local: str | Path, remote: str, *, mode: int | None = ..., progress: ProgressFn | None = ...) -> str: ...
    def backup(self, path: str) -> str | None: ...
    def apt_install(self, packages: list[str]) -> None: ...
    def systemctl(self, *args: str, check: bool = ...) -> ExecResult: ...
    def sha256(self, path: str) -> str | None: ...


def sha256_of_file(path: str | Path) -> str:
    """Hex SHA-256 of a local file, streamed."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def timestamp() -> str:
    """``YYYYmmddHHMMSS`` - the suffix convention of ``Caddyfile.bak-<ts>`` on the reference box."""
    return time.strftime("%Y%m%d%H%M%S")


#: Where the run's copies of overwritten files go (one subdirectory per installer run).
BACKUP_ROOT = "/var/backups/cloud-driver-installer"


@dataclass
class RemoteHost:
    """The real implementation over an open :class:`SshSession`."""

    session: SshSession
    log: LogFn
    use_sudo: bool = False
    redact: Callable[[str], str] = lambda text: text
    run_id: str = ""

    def __post_init__(self) -> None:
        if not self.run_id:
            self.run_id = timestamp()

    @property
    def backup_dir(self) -> str:
        """This run's backup directory on the server."""
        return f"{BACKUP_ROOT}/{self.run_id}"

    # --- commands --------------------------------------------------------------------------------

    def wrap(self, command: str) -> str:
        """The exact string handed to the SSH server for ``command``."""
        quoted = shlex.quote(command)
        prefix = "sudo -n " if self.use_sudo else ""
        return f"{prefix}bash -c {quoted}"

    def run(
        self,
        command: str,
        *,
        input: str | None = None,
        timeout: float | None = 600.0,
        check: bool = False,
        quiet: bool = False,
    ) -> ExecResult:
        """Run ``command``; with ``check`` a non-zero exit raises :class:`RemoteError`.

        Output lines stream to the log at DEBUG level unless ``quiet``; the command itself is
        logged (redacted) at DEBUG as ``$ …``.
        """
        if not quiet:
            self.log("DEBUG", "$ " + self.redact(command.strip().splitlines()[0] if command.strip() else command))

        def on_line(stream: str, line: str) -> None:
            if not quiet:
                self.log("DEBUG", "  " + self.redact(line))

        try:
            result = self.session.exec(self.wrap(command), input=input, timeout=timeout, on_line=on_line)
        except SshError as exc:
            raise RemoteError(str(exc)) from exc
        if check and not result.ok:
            tail = (result.err.strip() or result.out.strip()).splitlines()[-5:]
            raise RemoteError(
                f"command failed (exit {result.code}): {self.redact(command.strip().splitlines()[0][:100])}\n"
                + "\n".join(self.redact(line) for line in tail),
                result,
            )
        return result

    def run_ok(self, command: str, *, timeout: float | None = 120.0) -> bool:
        """True when ``command`` exits 0 (no output logged)."""
        return self.run(command, timeout=timeout, quiet=True).ok

    def run_with_env(self, command: str, env: dict[str, str], *, timeout: float | None = 600.0, check: bool = False) -> ExecResult:
        """Run ``command`` with ``env`` exported, the values fed over stdin so they never hit ``ps``.

        Keys must be valid shell identifiers; values may contain anything except a newline.
        """
        for key, value in env.items():
            if not key.isidentifier():
                raise ValueError(f"invalid environment variable name {key!r}")
            if "\n" in value:
                raise ValueError(f"environment value for {key} must not contain a newline")
        reads = "".join(f"IFS= read -r {key}; export {key}; " for key in env)
        payload = "".join(f"{value}\n" for value in env.values())
        return self.run(reads + "exec </dev/null; " + command, input=payload, timeout=timeout, check=check)

    # --- files -----------------------------------------------------------------------------------

    def exists(self, path: str) -> bool:
        """True when ``path`` exists (any type)."""
        return self.run_ok(f"test -e {shlex.quote(path)}")

    def read_text(self, path: str) -> str | None:
        """Contents of ``path`` or ``None`` when it does not exist / cannot be read."""
        result = self.run(f"cat {shlex.quote(path)}", quiet=True)
        return result.out if result.ok else None

    def sha256(self, path: str) -> str | None:
        """Hex SHA-256 of a remote file, or ``None`` when missing."""
        result = self.run(f"sha256sum {shlex.quote(path)}", quiet=True)
        return result.out.split()[0] if result.ok and result.out.split() else None

    def backup(self, path: str) -> str | None:
        """Copy ``path`` (mode/owner preserved) into this run's backup tree; returns the copy's path or ``None`` when absent."""
        if not self.exists(path):
            return None
        target = f"{self.backup_dir}{path}"
        parent = str(Path(target).parent)
        self.run(
            f"mkdir -p {shlex.quote(parent)} && chmod 700 {shlex.quote(BACKUP_ROOT)} {shlex.quote(self.backup_dir)} && cp -p {shlex.quote(path)} {shlex.quote(target)}",
            check=True,
            quiet=True,
        )
        self.log("DEBUG", f"backed up {path} -> {target}")
        return target

    def put_text(self, path: str, text: str, *, mode: int = 0o644, backup: bool = True) -> None:
        """Write ``text`` to ``path`` atomically with ``mode``; SHA-256 verified."""
        data = text.encode("utf-8")
        if backup:
            self.backup(path)
        self.mkdirs(str(Path(path).parent))
        tmp = self._tmp_name(path)
        self.session.upload_bytes(data, tmp)
        self._install(tmp, path, mode)
        expected = hashlib.sha256(data).hexdigest()
        actual = self.sha256(path)
        if actual != expected:
            raise RemoteError(f"{path}: checksum mismatch after write (expected {expected}, got {actual})")

    def put_file(
        self,
        local: str | Path,
        remote: str,
        *,
        mode: int | None = None,
        progress: ProgressFn | None = None,
    ) -> str:
        """Upload ``local`` to ``remote`` (full path) and verify; returns the SHA-256."""
        local_path = Path(local)
        if not local_path.is_file():
            raise RemoteError(f"local file not found: {local_path}")
        expected = sha256_of_file(local_path)
        self.mkdirs(str(Path(remote).parent))
        tmp = self._tmp_name(remote)
        self.session.upload(local_path, tmp, progress=progress)
        self._install(tmp, remote, mode if mode is not None else 0o644)
        actual = self.sha256(remote)
        if actual != expected:
            self.run(f"rm -f {shlex.quote(remote)}", quiet=True)
            raise RemoteError(f"{remote}: checksum mismatch after upload (transfer corrupted, try again)")
        return expected

    def mkdirs(self, *paths: str, mode: int | None = None) -> None:
        """``mkdir -p`` every path (with ``chmod`` when ``mode`` is given)."""
        quoted = " ".join(shlex.quote(p) for p in paths)
        self.run(f"mkdir -p {quoted}" + (f" && chmod {mode:o} {quoted}" if mode is not None else ""), check=True, quiet=True)

    # --- packages & services ---------------------------------------------------------------------

    def apt_install(self, packages: list[str]) -> None:
        """Non-interactive ``apt-get install`` of ``packages`` (after ``apt-get update``)."""
        if not packages:
            return
        names = " ".join(shlex.quote(p) for p in packages)
        self.run(
            "export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && apt-get install -y -qq " + names,
            timeout=None,
            check=True,
        )

    def dpkg_installed(self, package: str) -> bool:
        """True when ``package`` is installed according to dpkg."""
        result = self.run(f"dpkg-query -W -f='${{Status}}' {shlex.quote(package)}", quiet=True)
        return result.ok and "install ok installed" in result.out

    def systemctl(self, *args: str, check: bool = True) -> ExecResult:
        """``systemctl <args>``."""
        return self.run("systemctl " + " ".join(shlex.quote(a) for a in args), check=check)

    def service_active(self, unit: str) -> bool:
        """``systemctl is-active`` for ``unit``."""
        return self.run_ok(f"systemctl is-active --quiet {shlex.quote(unit)}")

    def command_exists(self, name: str) -> bool:
        """``command -v name``."""
        return self.run_ok(f"command -v {shlex.quote(name)} >/dev/null 2>&1")

    # --- internals -------------------------------------------------------------------------------

    def _tmp_name(self, path: str) -> str:
        # Same directory as the target so the final rename is atomic (same filesystem).
        return f"{Path(path).parent}/.{Path(path).name}.cdi-{timestamp()}-{abs(hash(path)) % 100000}"

    def _install(self, tmp: str, path: str, mode: int) -> None:
        self.run(
            f"chmod {mode:o} {shlex.quote(tmp)} && mv -f {shlex.quote(tmp)} {shlex.quote(path)}",
            check=True,
            quiet=True,
        )

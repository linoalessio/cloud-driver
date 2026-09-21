"""A scripted stand-in for :class:`cloud_driver_installer.remote.RemoteHost`.

Steps only ever talk to the server through the ``Remote`` protocol, so a test can hand them this
object, pre-load the answers for the commands the step is expected to run, and afterwards assert
on what was executed and which files were written. Unknown commands answer with exit code 1 and
empty output unless ``default_ok`` is set, which keeps a test honest about the commands a step
really depends on.

Matching is by substring (first rule wins, in registration order), so a rule for ``"dpkg-query"``
answers every dpkg query unless a more specific rule was registered before it.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from cloud_driver_installer.remote import ProgressFn, RemoteError
from cloud_driver_installer.ssh import ExecResult

Responder = Callable[[str, str | None], ExecResult | tuple[int, str] | tuple[int, str, str] | int | str]


@dataclass
class Rule:
    needle: str
    responder: Responder


@dataclass
class FakeRemote:
    """Scripted server. ``files`` holds the remote filesystem the fake knows about."""

    files: dict[str, str] = field(default_factory=dict)
    modes: dict[str, int] = field(default_factory=dict)
    rules: list[Rule] = field(default_factory=list)
    commands: list[str] = field(default_factory=list)
    inputs: list[str | None] = field(default_factory=list)
    backups: list[str] = field(default_factory=list)
    uploads: list[tuple[str, str]] = field(default_factory=list)  # (local, remote)
    apt_installed: list[str] = field(default_factory=list)
    systemctl_calls: list[tuple[str, ...]] = field(default_factory=list)
    default_ok: bool = False
    use_sudo: bool = False
    log_lines: list[tuple[str, str]] = field(default_factory=list)

    # --- scripting -------------------------------------------------------------------------------

    def on(self, needle: str, response: Responder | ExecResult | tuple | int | str) -> "FakeRemote":
        """Answer any command containing ``needle`` with ``response`` (a value or a callable)."""
        responder = response if callable(response) else (lambda cmd, inp, r=response: r)
        self.rules.append(Rule(needle, responder))
        return self

    def ok(self, needle: str, out: str = "") -> "FakeRemote":
        return self.on(needle, (0, out))

    def fail(self, needle: str, err: str = "", code: int = 1) -> "FakeRemote":
        return self.on(needle, (code, "", err))

    def ran(self, needle: str) -> bool:
        """Whether any executed command contained ``needle``."""
        return any(needle in cmd for cmd in self.commands)

    def count(self, needle: str) -> int:
        return sum(1 for cmd in self.commands if needle in cmd)

    # --- Remote protocol -------------------------------------------------------------------------

    def log(self, level: str, message: str) -> None:
        self.log_lines.append((level, message))

    def run(self, command: str, *, input: str | None = None, timeout: float | None = 600.0, check: bool = False, quiet: bool = False) -> ExecResult:
        self.commands.append(command)
        self.inputs.append(input)
        result = self._answer(command, input)
        if check and not result.ok:
            raise RemoteError(f"command failed (exit {result.code}): {command[:100]}", result)
        return result

    def run_ok(self, command: str, *, timeout: float | None = 120.0) -> bool:
        return self.run(command, timeout=timeout, quiet=True).ok

    def run_with_env(self, command: str, env: dict[str, str], *, timeout: float | None = 600.0, check: bool = False) -> ExecResult:
        payload = "".join(f"{value}\n" for value in env.values())
        return self.run(command, input=payload, timeout=timeout, check=check)

    def exists(self, path: str) -> bool:
        return path in self.files or path in self.modes

    def read_text(self, path: str) -> str | None:
        return self.files.get(path)

    def sha256(self, path: str) -> str | None:
        text = self.files.get(path)
        return hashlib.sha256(text.encode()).hexdigest() if text is not None else None

    def backup(self, path: str) -> str | None:
        if path not in self.files:
            return None
        target = f"{path}.bak-TEST"
        self.files[target] = self.files[path]
        self.backups.append(target)
        return target

    def put_text(self, path: str, text: str, *, mode: int = 0o644, backup: bool = True) -> None:
        if backup:
            self.backup(path)
        self.files[path] = text
        self.modes[path] = mode

    def put_file(self, local: str | Path, remote: str, *, mode: int | None = None, progress: ProgressFn | None = None) -> str:
        local_path = Path(local)
        data = local_path.read_bytes() if local_path.is_file() else b""
        self.uploads.append((str(local), remote))
        self.files[remote] = data.decode("utf-8", "replace")
        self.modes[remote] = mode if mode is not None else 0o644
        if progress:
            progress(len(data), len(data))
        return hashlib.sha256(data).hexdigest()

    def mkdirs(self, *paths: str, mode: int | None = None) -> None:
        self.commands.append("mkdir -p " + " ".join(paths))

    def apt_install(self, packages: list[str]) -> None:
        self.apt_installed.extend(packages)
        self.commands.append("apt-get install " + " ".join(packages))

    def dpkg_installed(self, package: str) -> bool:
        return self.run(f"dpkg-query -W -f='${{Status}}' {package}", quiet=True).ok

    def systemctl(self, *args: str, check: bool = True) -> ExecResult:
        self.systemctl_calls.append(args)
        return self.run("systemctl " + " ".join(args), check=check)

    def service_active(self, unit: str) -> bool:
        return self.run_ok(f"systemctl is-active --quiet {unit}")

    def command_exists(self, name: str) -> bool:
        return self.run_ok(f"command -v {name} >/dev/null 2>&1")

    # --- internals -------------------------------------------------------------------------------

    def _answer(self, command: str, input: str | None) -> ExecResult:
        for rule in self.rules:
            if rule.needle in command:
                value = rule.responder(command, input)
                return self._coerce(command, value)
        return ExecResult(command, 0 if self.default_ok else 1, "", "" if self.default_ok else "no fake rule")

    @staticmethod
    def _coerce(command: str, value) -> ExecResult:
        if isinstance(value, ExecResult):
            return value
        if isinstance(value, int):
            return ExecResult(command, value, "", "")
        if isinstance(value, str):
            return ExecResult(command, 0, value, "")
        if isinstance(value, tuple):
            code, out = value[0], value[1]
            err = value[2] if len(value) > 2 else ""
            return ExecResult(command, code, out, err)
        raise TypeError(f"unsupported fake response {value!r}")

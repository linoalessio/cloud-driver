"""ClamAV step: ``clamd`` on a single loopback IPv4 TCP socket with size limits above the app's scan ceiling.

Mirrors step 6 of ``shell/provision-root-server.sh`` and the rules in docs/requirements.md §4.3:

* Debian/Ubuntu's ``clamav-daemon.socket`` is socket-activated, so ``clamd.conf``'s ``TCPSocket``
  directive does nothing - the TCP listener has to be added to the socket unit through a drop-in
  (``/etc/systemd/system/clamav-daemon.socket.d/tcp.conf``).
* Exactly ONE ``ListenStream``, IPv4 only: ``clamd`` 1.4.3 crash-loops ("Received more than two
  file descriptors from systemd") when systemd hands it two TCP sockets, so a second IPv6 line is
  a bug, not a nicety.
* ``StreamMaxLength``/``MaxFileSize``/``MaxScanSize`` are raised above ``content-scan-max-bytes``
  (Debian's 25M default sits under the app's 100 MiB ceiling; a file in the gap would be rejected
  by ``clamd`` and silently fail open as ``CLEAN``).
* ``clamd`` has no authentication of its own, so the socket binds loopback and nothing else.

The file edits are pure functions (:func:`render_drop_in`, :func:`apply_clamd_limits`) so they are
unit-testable without a server; the step itself only reads, installs, writes and restarts.
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass, field

from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError
from cloud_driver_installer.sizing import parse_clamd_size

#: The two apt packages: the scanner daemon and the signature updater.
PACKAGES: tuple[str, ...] = ("clamav-daemon", "clamav-freshclam")
#: Drop-in directory of the socket unit (systemd merges every ``*.conf`` in it into the unit).
DROP_IN_DIR = "/etc/systemd/system/clamav-daemon.socket.d"
#: The drop-in that adds the TCP listener.
DROP_IN_PATH = f"{DROP_IN_DIR}/tcp.conf"
#: clamd's own configuration file (Debian packaging).
CLAMD_CONF = "/etc/clamav/clamd.conf"
#: Units enabled and restarted together, in the order the shell script uses.
UNITS: tuple[str, ...] = ("clamav-daemon.socket", "clamav-daemon", "clamav-freshclam")
#: The three clamd.conf size limits the installer manages, in the order they are written.
LIMIT_KEYS: tuple[str, ...] = ("StreamMaxLength", "MaxFileSize", "MaxScanSize")

_LISTEN_RE = re.compile(r"^[ \t]*ListenStream[ \t]*=[ \t]*(.*?)[ \t]*$", re.MULTILINE)


# --- pure helpers (unit-tested without a server) --------------------------------------------------


def listen_address(host: str) -> str:
    """The IPv4 address the socket drop-in binds for the plan's ``clamav.host``.

    ``localhost`` (the backend's own default) and a blank value become ``127.0.0.1`` - systemd's
    ``ListenStream`` wants an address, and the Java client resolves ``localhost`` to the IPv4
    record anyway. An IPv6 literal is refused: the drop-in must carry exactly one IPv4 listener.
    """
    value = (host or "").strip()
    if value in ("", "localhost"):
        return "127.0.0.1"
    if ":" in value:
        raise StepError(
            f"clamav host {value!r} is an IPv6 address - clamd must listen on exactly one IPv4 socket "
            "(a second/IPv6 ListenStream crash-loops clamd 1.4.3); use 127.0.0.1"
        )
    return value


def render_drop_in(address: str, port: int) -> str:
    """The socket drop-in text: one ``[Socket]`` section with exactly one ``ListenStream``."""
    return f"[Socket]\nListenStream={address}:{port}\n"


def drop_in_listeners(text: str | None) -> list[str]:
    """Every ``ListenStream`` value in a drop-in (empty when the file is missing or has none)."""
    if not text:
        return []
    return [match.group(1) for match in _LISTEN_RE.finditer(text)]


def drop_in_is_current(text: str | None, address: str, port: int) -> bool:
    """True when the drop-in declares exactly one listener and it is ``address:port``."""
    return drop_in_listeners(text) == [f"{address}:{port}"]


def wanted_limits(plan: InstallPlan) -> dict[str, str]:
    """The clamd.conf limits the plan asks for, keyed by directive name."""
    return {
        "StreamMaxLength": plan.clamav.stream_max_length,
        "MaxFileSize": plan.clamav.max_file_size,
        "MaxScanSize": plan.clamav.max_scan_size,
    }


def _limit_pattern(key: str) -> re.Pattern[str]:
    # ``^Key value`` - the same lines the shell's ``grep -q "^${key} "`` / ``sed "s/^${key} .*/…/"`` touch;
    # a commented-out ``#Key`` line is deliberately NOT a match (it is appended to, like the script does).
    return re.compile(rf"^{re.escape(key)}[ \t]+(\S+)[ \t]*$")


def clamd_limit_values(text: str | None) -> dict[str, str | None]:
    """Current value of each managed limit (``None`` when unset or only present commented out).

    When a key appears more than once the last occurrence wins, which is what ``clamd`` does.
    """
    values: dict[str, str | None] = {key: None for key in LIMIT_KEYS}
    for line in (text or "").splitlines():
        for key in LIMIT_KEYS:
            match = _limit_pattern(key).match(line)
            if match:
                values[key] = match.group(1)
    return values


def _same_size(current: str | None, wanted: str) -> bool:
    if current is None:
        return False
    try:
        return parse_clamd_size(current) == parse_clamd_size(wanted)
    except ValueError:
        return current == wanted


def stale_limits(text: str | None, wanted: dict[str, str]) -> list[str]:
    """Managed keys whose current value differs from ``wanted`` (unset counts as differing)."""
    current = clamd_limit_values(text)
    return [key for key, value in wanted.items() if not _same_size(current.get(key), value)]


def apply_clamd_limits(text: str, wanted: dict[str, str]) -> str:
    """Return ``clamd.conf`` text with every ``wanted`` limit set.

    An existing ``Key value`` line is rewritten in place (every such line, like the script's
    ``sed -i "s/^${key} .*/…/"``); a key that is absent is appended at the end. Applying the
    result again yields the same text, so the caller can compare before writing.
    """
    ends_with_newline = text.endswith("\n") or text == ""
    lines = text.split("\n")
    if ends_with_newline and lines and lines[-1] == "":
        lines.pop()
    for key, value in wanted.items():
        pattern = _limit_pattern(key)
        replaced = False
        for index, line in enumerate(lines):
            if pattern.match(line):
                lines[index] = f"{key} {value}"
                replaced = True
        if not replaced:
            lines.append(f"{key} {value}")
    return "\n".join(lines) + "\n"


# --- the step ---------------------------------------------------------------------------------------


@dataclass
class _State:
    """What ``_inspect`` found; shared by ``check`` and ``apply`` so both use one definition of 'done'."""

    missing_packages: list[str] = field(default_factory=list)
    listeners: list[str] = field(default_factory=list)
    drop_in_ok: bool = False
    conf_present: bool = False
    stale_limits: list[str] = field(default_factory=list)
    disabled_units: list[str] = field(default_factory=list)
    socket_active: bool = False


class ClamAvStep(Step):
    """Install ``clamd`` + ``freshclam``, bind clamd to one loopback TCP socket, raise its size limits."""

    id = "clamav"
    title = "ClamAV"
    depends_on = ("packages",)
    mandatory = False

    #: How long ``verify`` waits for the port (seconds): clamd loads its signature database for
    #: up to about a minute on first start before it accepts connections.
    poll_timeout: float = 90.0
    #: Seconds between two port probes while waiting.
    poll_interval: float = 3.0

    def enabled(self, plan: InstallPlan) -> bool:
        """Only when the plan wants content scanning at all."""
        return plan.clamav.enabled

    # --- helpers ------------------------------------------------------------------------------------

    @staticmethod
    def _endpoint(plan: InstallPlan) -> tuple[str, int]:
        return listen_address(plan.clamav.host), plan.clamav.port

    def _inspect(self, ctx: Context) -> _State:
        """Read-only look at packages, drop-in, clamd.conf and unit state."""
        remote = ctx.remote
        address, port = self._endpoint(ctx.plan)
        state = _State()
        state.missing_packages = [pkg for pkg in PACKAGES if not remote.dpkg_installed(pkg)]
        drop_in = remote.read_text(DROP_IN_PATH)
        state.listeners = drop_in_listeners(drop_in)
        state.drop_in_ok = drop_in_is_current(drop_in, address, port)
        conf = remote.read_text(CLAMD_CONF)
        state.conf_present = conf is not None
        state.stale_limits = stale_limits(conf, wanted_limits(ctx.plan))
        state.disabled_units = [unit for unit in UNITS if not remote.run_ok(f"systemctl is-enabled --quiet {unit}")]
        state.socket_active = remote.service_active("clamav-daemon.socket")
        return state

    @staticmethod
    def _limits_text(wanted: dict[str, str]) -> str:
        return " / ".join(f"{key} {value}" for key, value in wanted.items())

    # --- phases -------------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """Packages installed, drop-in exactly right, limits set, units enabled and the socket active → ok."""
        address, port = self._endpoint(ctx.plan)
        wanted = wanted_limits(ctx.plan)
        state = self._inspect(ctx)
        ctx.discovered.clamd_installed = not state.missing_packages

        if not address.startswith("127."):
            ctx.warn(f"clamd will listen on {address}:{port}, which is not loopback - clamd has no authentication of its own; make sure nothing but this host can reach it")
        if len(state.listeners) > 1:
            ctx.warn(f"{DROP_IN_PATH} declares {len(state.listeners)} listeners ({', '.join(state.listeners)}) - clamd crash-loops with two TCP sockets; it will be rewritten with exactly one")

        changes: list[str] = []
        if state.missing_packages:
            changes.append("install " + ", ".join(state.missing_packages))
        if not state.drop_in_ok:
            replacing = f", replacing {', '.join(state.listeners)}" if state.listeners else ""
            changes.append(f"write {DROP_IN_PATH} (ListenStream={address}:{port}{replacing})")
        if state.stale_limits:
            changes.append("set " + ", ".join(f"{key} {wanted[key]}" for key in state.stale_limits) + f" in {CLAMD_CONF}")
        if state.disabled_units:
            changes.append("enable " + ", ".join(state.disabled_units))
        if not state.socket_active:
            changes.append("restart clamav-daemon.socket (not active)")
        if changes:
            return CheckResult.needs_apply("; ".join(changes))
        return CheckResult.ok(
            f"clamav-daemon + clamav-freshclam installed · clamd on {address}:{port} (socket drop-in) · "
            f"{self._limits_text(wanted)} · units enabled, socket active"
        )

    def apply(self, ctx: Context) -> None:
        """Install what is missing, write the drop-in and limits, enable the units, restart when something changed."""
        remote = ctx.remote
        address, port = self._endpoint(ctx.plan)
        wanted = wanted_limits(ctx.plan)
        state = self._inspect(ctx)
        try:
            if state.missing_packages:
                ctx.progress(0.1, "installing " + ", ".join(state.missing_packages))
                ctx.info("installing " + ", ".join(state.missing_packages))
                remote.apt_install(list(state.missing_packages))
            ctx.check_cancelled()

            changed = bool(state.missing_packages)
            if not state.drop_in_ok:
                ctx.progress(0.5, "writing the socket drop-in")
                remote.mkdirs(DROP_IN_DIR)
                remote.put_text(DROP_IN_PATH, render_drop_in(address, port), mode=0o644)
                ctx.info(f"wrote {DROP_IN_PATH}: ListenStream={address}:{port}")
                changed = True
            ctx.check_cancelled()

            conf = remote.read_text(CLAMD_CONF)
            if conf is None:
                raise StepError(f"{CLAMD_CONF} is missing - is clamav-daemon installed? (apt-get install clamav-daemon)")
            updated = apply_clamd_limits(conf, wanted)
            if updated != conf:
                ctx.progress(0.7, "raising clamd size limits")
                remote.put_text(CLAMD_CONF, updated, mode=0o644)
                ctx.info(f"set {self._limits_text(wanted)} in {CLAMD_CONF}")
                changed = True
            ctx.check_cancelled()

            ctx.progress(0.85, "enabling units")
            remote.systemctl("daemon-reload")
            remote.systemctl("enable", "--quiet", *UNITS)
            if changed or not state.socket_active:
                ctx.progress(0.95, "restarting clamd")
                # ``|| true`` as in the script: freshclam may be in the middle of its first download.
                remote.run("systemctl restart " + " ".join(UNITS) + " || true", timeout=300)
                ctx.info("restarted " + ", ".join(UNITS))
            else:
                ctx.debug("clamd configuration unchanged - units left running")
        except RemoteError as exc:
            raise StepError(f"ClamAV setup failed: {exc}") from exc
        ctx.discovered.clamd_installed = True

    def _port_listening(self, ctx: Context, address: str, port: int) -> bool:
        """``ss -ltn`` shows the socket, or a plain TCP connect to it succeeds."""
        pattern = f"[[:space:]]{re.escape(address)}:{port}[[:space:]]"
        return ctx.remote.run_ok(
            f"ss -ltn 2>/dev/null | grep -qE '{pattern}' || timeout 3 bash -c '</dev/tcp/{address}/{port}'",
            timeout=15,
        )

    def verify(self, ctx: Context) -> VerifyResult:
        """Wait for the port to accept connections; an active socket unit that is still loading is a warning, not a failure."""
        remote = ctx.remote
        address, port = self._endpoint(ctx.plan)
        started = time.monotonic()
        deadline = started + self.poll_timeout
        listening = False
        while True:
            ctx.check_cancelled()
            if self._port_listening(ctx, address, port):
                listening = True
                break
            now = time.monotonic()
            if now >= deadline:
                break
            ctx.progress((now - started) / max(self.poll_timeout, 0.001), f"waiting for clamd on {address}:{port}")
            time.sleep(self.poll_interval)

        freshclam_note = ""
        if not remote.service_active("clamav-freshclam"):
            freshclam_note = " · WARN clamav-freshclam is not active (signature download failed or not started: systemctl status clamav-freshclam)"
        if listening:
            return VerifyResult(True, f"clamd listening on {address}:{port}" + freshclam_note)
        if remote.service_active("clamav-daemon.socket"):
            return VerifyResult(
                True,
                f"WARN clamd is not accepting connections on {address}:{port} after {int(self.poll_timeout)} s, but "
                "clamav-daemon.socket is active - signatures are probably still loading (systemctl status clamav-daemon)"
                + freshclam_note,
            )
        status = remote.run("systemctl --no-pager --lines=10 status clamav-daemon.socket clamav-daemon", quiet=True, timeout=30)
        tail = "\n".join((status.out.strip() or status.err.strip()).splitlines()[-12:])
        return VerifyResult(False, f"clamd is not listening on {address}:{port} and clamav-daemon.socket is not active\n{tail}".rstrip())

    def describe(self, plan: InstallPlan) -> str:
        """One line for the summary page."""
        try:
            address = listen_address(plan.clamav.host)
        except StepError:
            address = plan.clamav.host
        return (
            f"install clamav-daemon + clamav-freshclam, bind clamd to {address}:{plan.clamav.port} through the socket drop-in "
            f"{DROP_IN_PATH} (exactly one IPv4 ListenStream), set {self._limits_text(wanted_limits(plan))} in {CLAMD_CONF}, "
            "enable + restart clamav-daemon.socket, clamav-daemon and clamav-freshclam"
        )

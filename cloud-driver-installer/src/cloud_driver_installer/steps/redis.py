"""Redis: a loopback-only, password-protected local server, or the credentials file for an
external one.

Mirrors step 7 (and the matching half of step 8) of ``shell/provision-root-server.sh``:

* **Keep unless rotate.** The password inside an existing ``redis-database.json`` on the server
  is reused (``secrets.redis_password_kept``) unless the plan's ``rotate`` flag is set or the
  operator typed one; a rotation rewrites ``requirepass`` and the file in the same apply. With no
  file on the server a 48-character hex password is generated (the script's ``openssl rand -hex
  24`` - hex so a value can never contain a sed delimiter, kept here for identical files).
* ``/etc/redis/redis.conf`` is edited the way the script's ``sed`` calls do (every ``bind`` line
  becomes ``bind 127.0.0.1 -::1``, every ``requirepass`` line is replaced, both appended when
  absent) - but in Python, and uploaded whole with ``put_text`` after a backup, so the password
  never appears in a command line. The file is handed back to ``redis:redis`` afterwards because
  ``install -m`` leaves it owned by root and the daemon runs as ``redis``.
* Verification is a real ``redis-cli PING`` authenticated through ``REDISCLI_AUTH``.
"""

from __future__ import annotations

import re
import shlex
import time
from dataclasses import dataclass

from cloud_driver_installer.config_files import parse_json, render_redis_credentials, to_json
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError
from cloud_driver_installer.secrets import generate_hex
from cloud_driver_installer.ssh import ExecResult

#: File name (under the plan's config dir) that ``database-driver`` reads at boot.
CREDENTIALS_FILE = "redis-database.json"
#: Debian/Ubuntu's server configuration file.
CONF_PATH = "/etc/redis/redis.conf"
#: Package for a local server (brings ``redis-cli`` along via ``redis-tools``).
SERVER_PACKAGE = "redis-server"
#: Package that provides ``redis-cli`` when only an external server is used.
CLIENT_PACKAGE = "redis-tools"
#: The systemd unit.
SERVICE = "redis-server"
#: The exact loopback bind line the shell script writes.
BIND_LINE = "bind 127.0.0.1 -::1"

_BIND_RE = re.compile(r"^bind\s")
_REQUIREPASS_RE = re.compile(r"^requirepass\s")
_NEEDS_QUOTES_RE = re.compile(r'[\s"\\]')
_VERSION_RE = re.compile(r"v=(\d+(?:\.\d+)*)")


@dataclass(frozen=True)
class ResolvedPassword:
    """The password this run uses for ``requirepass``, and where it came from."""

    value: str
    #: True when it was read back from the server's ``redis-database.json``.
    kept: bool
    #: ``plan`` (typed by the operator), ``file`` (kept) or ``generated`` (fresh box or rotation).
    source: str
    #: Human-readable provenance for the sidebar detail.
    note: str


def requirepass_line(password: str) -> str:
    """The ``requirepass`` directive for ``password`` (double-quoted only when the value needs it)."""
    if not password or _NEEDS_QUOTES_RE.search(password):
        escaped = password.replace("\\", "\\\\").replace('"', '\\"')
        return f'requirepass "{escaped}"'
    return f"requirepass {password}"


def render_redis_conf(text: str, password: str) -> str:
    """``text`` with every ``bind`` line replaced by :data:`BIND_LINE` and every ``requirepass``
    line set to ``password``; either directive is appended when absent. Unchanged input renders
    unchanged, which is what makes the check/apply comparison exact."""
    wanted = requirepass_line(password)
    out: list[str] = []
    saw_bind = saw_pass = False
    for line in text.splitlines():
        if _BIND_RE.match(line):
            out.append(BIND_LINE)
            saw_bind = True
        elif _REQUIREPASS_RE.match(line):
            out.append(wanted)
            saw_pass = True
        else:
            out.append(line)
    appended = False
    if not saw_bind:
        out.append(BIND_LINE)
        appended = True
    if not saw_pass:
        out.append(wanted)
        appended = True
    result = "\n".join(out)
    if appended or text.endswith("\n") or not text:
        result += "\n"
    return result


class RedisStep(Step):
    """Install Redis bound to loopback with ``requirepass``, or point the file at an external server."""

    id = "redis"
    title = "Redis"
    depends_on = ("packages",)
    mandatory = False

    def __init__(self) -> None:
        #: How long ``verify`` keeps retrying ``PING`` after a restart (tests set 0).
        self.verify_timeout: float = 15.0

    def enabled(self, plan: InstallPlan) -> bool:
        """Only when the plan wants Redis at all."""
        return bool(plan.redis.enabled)

    # --- Step ------------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """Read-only: package, service, ``redis.conf`` directives, a PING with the password on record, the file."""
        rd = ctx.plan.redis
        if rd.mode == "external":
            return self._check_external(ctx)
        remote = ctx.remote
        installed = remote.dpkg_installed(SERVER_PACKAGE)
        active = installed and remote.service_active(SERVICE)
        version = self._version(ctx) if installed else ""
        ctx.discovered.redis_installed = installed
        resolved = self._resolve_password(ctx)
        conf = remote.read_text(CONF_PATH)
        conf_ok = conf is not None and render_redis_conf(conf, resolved.value) == conf
        file_ok = self._file_current(ctx, resolved.value)

        todo: list[str] = []
        if not installed:
            todo.append(f"install {SERVER_PACKAGE}")
        elif not active:
            todo.append(f"enable + start {SERVICE}")
        if not conf_ok:
            todo.append(f"set '{BIND_LINE}' and requirepass in {CONF_PATH}")
        elif active and not self._ping_ok(ctx, resolved.value):
            todo.append(f"restart {SERVICE} (the running instance does not accept the password on record)")
        if not file_ok:
            todo.append(f"write {CREDENTIALS_FILE}")
        if todo:
            return CheckResult.needs_apply(", ".join(todo) + f" ({resolved.note})")
        label = f"Redis {version}" if version else "redis-server"
        return CheckResult.ok(f"{label} installed and active, bound to 127.0.0.1 with requirepass (PING ok), {resolved.note}")

    def apply(self, ctx: Context) -> None:
        """Install redis-server, edit redis.conf, enable/restart the service, write the file (all idempotent)."""
        rd = ctx.plan.redis
        remote = ctx.remote
        resolved = self._resolve_password(ctx)
        if rd.mode == "external":
            self._apply_external(ctx, resolved)
            return
        ctx.progress(0.0, "Redis")
        if not remote.dpkg_installed(SERVER_PACKAGE):
            ctx.info(f"installing {SERVER_PACKAGE}")
            try:
                remote.apt_install([SERVER_PACKAGE])
            except RemoteError as exc:
                raise StepError(f"Redis: apt-get install failed: {exc}") from exc
            ctx.discovered.redis_installed = True
        ctx.check_cancelled()
        ctx.progress(0.4, CONF_PATH)
        conf = remote.read_text(CONF_PATH)
        if conf is None:
            raise StepError(f"Redis: {CONF_PATH} not found after installing {SERVER_PACKAGE}")
        rendered = render_redis_conf(conf, resolved.value)
        changed = rendered != conf
        if changed:
            try:
                remote.put_text(CONF_PATH, rendered, mode=0o640)
                remote.run(f"chown redis:redis {shlex.quote(CONF_PATH)}", check=True, quiet=True)
            except RemoteError as exc:
                raise StepError(f"Redis: could not update {CONF_PATH}: {exc}") from exc
            ctx.info(f"updated {CONF_PATH}: '{BIND_LINE}', requirepass set ({resolved.note})")
        ctx.check_cancelled()
        ctx.progress(0.7, SERVICE)
        try:
            if not remote.service_active(SERVICE):
                remote.systemctl("enable", "--now", SERVICE)
                ctx.info(f"enabled and started {SERVICE}")
            else:
                if not remote.run_ok(f"systemctl is-enabled --quiet {SERVICE}"):
                    remote.systemctl("enable", SERVICE)
                    ctx.info(f"enabled {SERVICE} at boot")
                if changed or not self._ping_ok(ctx, resolved.value):
                    remote.systemctl("restart", SERVICE)
                    ctx.info(f"restarted {SERVICE}")
        except RemoteError as exc:
            raise StepError(f"Redis: could not start the service: {exc}") from exc
        ctx.check_cancelled()
        ctx.progress(0.9, CREDENTIALS_FILE)
        self._write_credentials(ctx, resolved.value)
        ctx.progress(1.0, "done")

    def verify(self, ctx: Context) -> VerifyResult:
        """The real probe: ``redis-cli PING`` authenticated via ``REDISCLI_AUTH`` must answer ``PONG``."""
        rd = ctx.plan.redis
        password = ctx.secrets.redis_password or self._resolve_password(ctx).value
        deadline = time.monotonic() + self.verify_timeout
        while True:
            result = self._ping(ctx, password)
            if result.ok and result.text == "PONG":
                version = self._version(ctx) if rd.mode == "install" else ""
                suffix = f" · Redis {version}" if version else ""
                return VerifyResult(True, f"redis-cli PING → PONG at {rd.host}:{rd.port}{suffix}")
            if time.monotonic() >= deadline:
                return VerifyResult(False, f"redis-cli PING at {rd.host}:{rd.port} failed: {_last_line(result)}")
            ctx.check_cancelled()
            time.sleep(1.0)

    def describe(self, plan: InstallPlan) -> str:
        """One line for the summary page."""
        rd = plan.redis
        if rd.rotate:
            password = "rotate the password"
        elif rd.password:
            password = "use the password from the plan"
        else:
            password = f"keep the password from an existing {CREDENTIALS_FILE} (generate one otherwise)"
        if rd.mode == "external":
            return (
                f"use the external Redis at {rd.host}:{rd.port} (db {rd.database}), "
                f"install {CLIENT_PACKAGE} if redis-cli is missing, write {CREDENTIALS_FILE}"
            )
        return (
            f"install {SERVER_PACKAGE} if missing, set '{BIND_LINE}' + requirepass in {CONF_PATH}, "
            f"enable and restart it, {password}, write {CREDENTIALS_FILE}"
        )

    # --- external mode ---------------------------------------------------------------------------

    def _check_external(self, ctx: Context) -> CheckResult:
        """External server: ``redis-cli`` present, PING works, file current."""
        rd = ctx.plan.redis
        resolved = self._resolve_password(ctx)
        has_cli = ctx.remote.command_exists("redis-cli")
        file_ok = self._file_current(ctx, resolved.value)
        todo: list[str] = []
        if not has_cli:
            todo.append(f"install {CLIENT_PACKAGE}")
        else:
            result = self._ping(ctx, resolved.value)
            if not (result.ok and result.text == "PONG"):
                todo.append(
                    f"PING {rd.host}:{rd.port} failed ({_last_line(result)}) - apply only writes the file, "
                    "fix the server or the credentials or verify will fail"
                )
        if not file_ok:
            todo.append(f"write {CREDENTIALS_FILE}")
        if todo:
            return CheckResult.needs_apply(", ".join(todo) + f" ({resolved.note})")
        return CheckResult.ok(f"external Redis at {rd.host}:{rd.port}: PING ok, {resolved.note}")

    def _apply_external(self, ctx: Context, resolved: ResolvedPassword) -> None:
        """External server: only the client package (when missing) and the credentials file."""
        remote = ctx.remote
        ctx.progress(0.0, "Redis client")
        if not remote.command_exists("redis-cli"):
            ctx.info(f"installing {CLIENT_PACKAGE} (redis-cli is needed for the PING probe)")
            try:
                remote.apt_install([CLIENT_PACKAGE])
            except RemoteError as exc:
                raise StepError(f"Redis: apt-get install {CLIENT_PACKAGE} failed: {exc}") from exc
        ctx.check_cancelled()
        ctx.progress(0.7, CREDENTIALS_FILE)
        self._write_credentials(ctx, resolved.value)
        ctx.progress(1.0, "done")

    # --- password --------------------------------------------------------------------------------

    def _resolve_password(self, ctx: Context) -> ResolvedPassword:
        """Deterministic password resolution shared by check, apply and verify.

        Order: the plan's password; else the password inside the server's existing file unless
        ``rotate``; else the value already generated earlier in this run; else a fresh hex value.
        The result is stored in ``ctx.secrets`` and registered with the redactor before anything
        is logged.
        """
        rd = ctx.plan.redis
        secrets = ctx.secrets
        existing = parse_json(ctx.remote.read_text(self._credentials_path(ctx.plan)))
        ctx.discovered.existing_redis = existing
        on_file = existing.get("password")
        on_file = on_file if isinstance(on_file, str) else ""
        if rd.password:
            resolved = ResolvedPassword(rd.password, False, "plan", "password from the plan")
        elif on_file and not rd.rotate:
            resolved = ResolvedPassword(on_file, True, "file", f"password kept from {CREDENTIALS_FILE}")
        elif rd.mode == "external":
            raise StepError("Redis: a password is required for an external server")
        else:
            note = "new password generated (rotation requested)" if rd.rotate and on_file else "new password generated (no credentials file on the server)"
            reuse = secrets.redis_password if secrets.redis_password and not secrets.redis_password_kept else ""
            resolved = ResolvedPassword(reuse or generate_hex(24), False, "generated", note)
        if not resolved.value or "\n" in resolved.value:
            raise StepError("Redis: the password must be a single non-empty line")
        secrets.redis_password = resolved.value
        secrets.redis_password_kept = resolved.kept
        ctx.remember_secret(resolved.value)
        return resolved

    # --- credentials file ------------------------------------------------------------------------

    @staticmethod
    def _credentials_path(plan: InstallPlan) -> str:
        """``<config_dir>/redis-database.json``."""
        return f"{plan.config_dir}/{CREDENTIALS_FILE}"

    def _file_current(self, ctx: Context, password: str) -> bool:
        """Whether the server's file already equals what this plan renders (compared as JSON)."""
        existing = parse_json(ctx.remote.read_text(self._credentials_path(ctx.plan)))
        return existing == render_redis_credentials(ctx.plan, password)

    def _write_credentials(self, ctx: Context, password: str) -> None:
        """Write the file (mode 0600, backup of an existing one) unless its content is already identical."""
        path = self._credentials_path(ctx.plan)
        document = render_redis_credentials(ctx.plan, password)
        if parse_json(ctx.remote.read_text(path)) == document:
            ctx.debug(f"{path} is up to date")
            return
        try:
            ctx.remote.put_text(path, to_json(document), mode=0o600)
        except RemoteError as exc:
            raise StepError(f"Redis: could not write {path}: {exc}") from exc
        ctx.discovered.existing_redis = document
        ctx.info(f"wrote {path}")

    # --- probes ----------------------------------------------------------------------------------

    def _ping(self, ctx: Context, password: str) -> ExecResult:
        """``redis-cli -h <host> -p <port> PING`` with the password exported as ``REDISCLI_AUTH`` over stdin."""
        rd = ctx.plan.redis
        user = f" --user {shlex.quote(rd.username)}" if rd.username else ""
        command = f"redis-cli -h {shlex.quote(rd.host)} -p {int(rd.port)}{user} PING"
        try:
            return ctx.remote.run_with_env(command, {"REDISCLI_AUTH": password}, timeout=30)
        except RemoteError as exc:
            raise StepError(f"Redis: could not run redis-cli on the server: {exc}") from exc

    def _ping_ok(self, ctx: Context, password: str) -> bool:
        """True when the running instance answers ``PONG`` for ``password``."""
        result = self._ping(ctx, password)
        return result.ok and result.text == "PONG"

    def _version(self, ctx: Context) -> str:
        """``redis-server --version`` -> ``7.0.15`` (blank when unavailable)."""
        result = ctx.remote.run("redis-server --version", quiet=True)
        match = _VERSION_RE.search(result.out) if result.ok else None
        return match.group(1) if match else ""


# --- module helpers ------------------------------------------------------------------------------


def _last_line(result: ExecResult) -> str:
    """The last non-blank line of stderr (or stdout) - where redis-cli puts the reason."""
    lines = [line for line in (result.err.strip() or result.out.strip()).splitlines() if line.strip()]
    return lines[-1].strip() if lines else f"exit {result.code}, no output"

"""The server step: preflight discovery of the target box plus the OS-level basics.

``check`` is the preflight every later step relies on: it fills :class:`~cloud_driver_installer.model.Discovered`
(OS, kernel, CPU, RAM, disk, public IP, clock sync, root/sudo, apt/systemd, Java/Python, which
services and jars are already there, the existing config files - whose passwords are registered
with the redactor before anything is logged) and refuses to go on when the host is not an
apt-based systemd box the installer can administer. ``apply`` covers what the reference
``provision-root-server.sh`` leaves to the operator: clock synchronisation (AWS SigV4 rejects a
clock more than five minutes off, which makes KMS - and therefore boot - fail), an optional
timezone, the install directory layout, optional unattended upgrades, and - locally, opt-in - a
``Host`` alias in the operator's ``~/.ssh/config`` so the existing shell scripts can target the
box unchanged.
"""

from __future__ import annotations

import ipaddress
import os
import re
import shlex
from pathlib import Path
from typing import Any

from cloud_driver_installer.config_files import parse_env_file, parse_json
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError
from cloud_driver_installer.sizing import suggest_jvm_xmx
from cloud_driver_installer.ssh import SshTarget
from cloud_driver_installer.steps.java import parse_java_version
from cloud_driver_installer.steps.python import VENV_PROBE_COMMAND, PYTHON_VERSION_COMMAND, parse_python_version

#: Below this much free space on the install directory's filesystem the operator is warned.
MIN_FREE_GIB = 10.0
#: With the intelligence service (PyTorch venv + model) the bar is higher.
MIN_FREE_GIB_INTELLIGENCE = 15.0

#: Units that count as "an NTP service is running" (Debian/Ubuntu spellings).
NTP_UNITS: tuple[str, ...] = ("systemd-timesyncd", "chrony", "chronyd", "ntp", "ntpsec")

#: Files whose presence tells later steps what is already on the box.
AWS_CREDENTIALS_FILE = "/root/.aws/credentials"
INTELLIGENCE_UVICORN = "/opt/cloud-driver-intelligence/.venv/bin/uvicorn"
INTELLIGENCE_ENV_FILE = "/etc/cloud-driver-intelligence.env"
UNATTENDED_PERIODIC_FILE = "/etc/apt/apt.conf.d/20auto-upgrades"

#: Values in the existing files that must be masked before any log line mentions them.
INTELLIGENCE_SECRET_KEYS: tuple[str, ...] = ("CLOUD_DRIVER_INTELLIGENCE_SECRET", "CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY")
CONFIG_SECRET_KEYS: tuple[str, ...] = ("jwt-signing-key", "smtp-password", "intelligence-shared-secret")

#: Packages this step may install.
PACKAGES_CHECKED: tuple[str, ...] = ("postgresql", "redis-server", "clamav-daemon", "caddy", "ufw")

TIMEDATE_COMMAND = "timedatectl show -p NTPSynchronized -p NTP -p Timezone"
PUBLIC_IP_COMMAND = "curl -4 -s --max-time 5 https://api.ipify.org"
ROUTE_COMMAND = "ip -4 route get 1.1.1.1"
IPV6_COMMAND = "ip -6 addr show scope global"
SET_NTP_COMMAND = "timedatectl set-ntp true"
UNATTENDED_RECONFIGURE_COMMAND = "dpkg-reconfigure -f noninteractive unattended-upgrades"


# --- parsers (pure, unit-testable) ---------------------------------------------------------------


def parse_os_release(text: str) -> dict[str, str]:
    """``KEY="value"`` lines of ``/etc/os-release`` -> dict (quotes stripped)."""
    result: dict[str, str] = {}
    for line in (text or "").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        result[key.strip()] = value
    return result


def parse_free(text: str) -> tuple[int, int]:
    """``free -m`` output -> ``(mem_total_mib, swap_total_mib)``; zeros when a line is missing."""
    mem = swap = 0
    for line in (text or "").splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1].isdigit():
            if parts[0].lower().startswith("mem"):
                mem = int(parts[1])
            elif parts[0].lower().startswith("swap"):
                swap = int(parts[1])
    return mem, swap


def parse_df_avail_gib(text: str) -> float:
    """``df -BG --output=avail`` output (``Avail`` header + ``  38G``) -> ``38.0``; ``0.0`` when unparsable."""
    for line in reversed((text or "").splitlines()):
        match = re.search(r"(\d+)\s*G?\s*$", line.strip())
        if match:
            return float(match.group(1))
    return 0.0


def parse_key_values(text: str) -> dict[str, str]:
    """``KEY=value`` lines (``timedatectl show``) -> dict."""
    return parse_env_file(text)


def parse_ipv4(text: str) -> str:
    """The text if it is one IPv4 address, else ``""``."""
    candidate = (text or "").strip()
    try:
        return candidate if isinstance(ipaddress.ip_address(candidate), ipaddress.IPv4Address) else ""
    except ValueError:
        return ""


def parse_route_source(text: str) -> str:
    """``ip route get`` output -> the ``src`` address, or ``""``."""
    match = re.search(r"\bsrc\s+(\S+)", text or "")
    return parse_ipv4(match.group(1)) if match else ""


def parse_ipv6_global(text: str) -> str:
    """First global ``inet6`` address of ``ip -6 addr show scope global``, or ``""``."""
    match = re.search(r"\binet6\s+([0-9A-Fa-f:]+)/\d+", text or "")
    return match.group(1) if match else ""


def screen_session_running(listing: str, session: str) -> bool:
    """Whether ``screen -list`` output names a ``<pid>.<session>`` socket."""
    return re.search(rf"\.{re.escape(session)}(?:\s|$)", listing or "", re.MULTILINE) is not None


def ssh_alias_present(config_text: str, alias: str) -> bool:
    """Whether ``config_text`` (an OpenSSH client config) has a ``Host`` line naming ``alias``."""
    for line in (config_text or "").splitlines():
        match = re.match(r"^\s*host\s*(?:=|\s)\s*(.+?)\s*$", line, re.IGNORECASE)
        if match and alias in match.group(1).split():
            return True
    return False


def render_ssh_alias_block(alias: str, target: SshTarget) -> str:
    """The ``Host`` block appended to ``~/.ssh/config`` for ``target``."""
    lines = [f"Host {alias}", f"    HostName {target.host}", f"    User {target.user or 'root'}"]
    if target.port and target.port != 22:
        lines.append(f"    Port {target.port}")
    if target.auth == "key" and target.key_path:
        lines.append(f"    IdentityFile {target.key_path}")
    return "\n".join(lines) + "\n"


def _str_values(document: dict[str, Any], keys: tuple[str, ...]) -> list[str]:
    return [document[key] for key in keys if isinstance(document.get(key), str) and document[key]]


class ServerStep(Step):
    """Preflight discovery plus clock sync, timezone, directory layout, unattended upgrades and the ssh alias."""

    id = "server"
    title = "Server"
    mandatory = True

    def describe(self, plan: InstallPlan) -> str:
        """One line for the summary page."""
        parts: list[str] = []
        if plan.server.ntp:
            parts.append("ensure NTP time sync (systemd-timesyncd if no NTP service runs)")
        if plan.server.timezone:
            parts.append(f"set timezone {plan.server.timezone}")
        parts.append(f"create {plan.server.install_dir} (cloud-driver/, extensions/, upload-scratch/)")
        if plan.server.unattended_upgrades:
            parts.append("install unattended-upgrades")
        if plan.server.write_ssh_alias:
            parts.append(f"add ssh alias {plan.server.ssh_alias_name} to ~/.ssh/config")
        return "preflight discovery; " + ", ".join(parts)

    # --- check -----------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """Discover the box into ``ctx.discovered``; refuse unsupported hosts; report pending changes."""
        try:
            timedate = self._discover(ctx)
        except RemoteError as exc:
            raise StepError(f"server discovery failed: {exc}") from exc
        discovered = ctx.discovered
        plan = ctx.plan

        if not discovered.has_apt:
            raise StepError(f"{discovered.os_pretty or 'this host'} has no apt-get - the installer supports Debian/Ubuntu only")
        if not discovered.has_systemd:
            raise StepError("systemctl not found - the installer needs a systemd-based Debian/Ubuntu host")
        if not discovered.is_root and not discovered.has_sudo:
            raise StepError("not logged in as root and `sudo -n true` fails - connect as root or grant the login user passwordless sudo")

        ctx.progress(0.6, "probing installed services")
        try:
            self._discover_services(ctx)
            changes = self._pending_changes(ctx, timedate)
        except RemoteError as exc:
            raise StepError(f"server discovery failed: {exc}") from exc
        ctx.progress(1.0, "discovery complete")

        needed = MIN_FREE_GIB_INTELLIGENCE if plan.intelligence.enabled else MIN_FREE_GIB
        if discovered.disk_free_gib and discovered.disk_free_gib < needed:
            ctx.warn(f"only {discovered.disk_free_gib:.0f} GiB free on {plan.server.install_dir}'s filesystem - {needed:.0f} GiB recommended")

        if not plan.app.jvm_xmx and discovered.ram_mib:
            plan.app.jvm_xmx = suggest_jvm_xmx(
                discovered.ram_mib,
                postgres_local=plan.postgres.mode == "install",
                clamav_enabled=plan.clamav.enabled,
                intelligence_enabled=plan.intelligence.enabled,
            )
            ctx.info(f"JVM heap suggested from {discovered.ram_mib} MiB RAM: -Xmx{plan.app.jvm_xmx}")

        state = self._state_label(ctx)
        summary = " · ".join(part for part in (discovered.summary(), state) if part)
        if changes:
            return CheckResult.needs_apply(", ".join(changes) + " · " + summary)
        return CheckResult.ok(summary)

    def _discover(self, ctx: Context) -> dict[str, str]:
        """Fill the OS/hardware/network/identity facts; returns the parsed ``timedatectl show`` output."""
        remote = ctx.remote
        discovered = ctx.discovered
        plan = ctx.plan
        ctx.progress(0.0, "reading OS facts")

        os_release = parse_os_release(remote.read_text("/etc/os-release") or "")
        discovered.os_pretty = os_release.get("PRETTY_NAME", "")
        discovered.os_id = os_release.get("ID", "")
        discovered.kernel = remote.run("uname -r", quiet=True).text
        nproc = remote.run("nproc", quiet=True).text
        discovered.cpu_count = int(nproc) if nproc.isdigit() else 0
        discovered.ram_mib, discovered.swap_mib = parse_free(remote.run("free -m", quiet=True).out)
        ctx.check_cancelled()

        install_dir = shlex.quote(plan.server.install_dir)
        df = remote.run(
            f'd={install_dir}; while [ ! -d "$d" ] && [ "$d" != / ]; do d=$(dirname "$d"); done; df -BG --output=avail "$d" | tail -n 1',
            quiet=True,
        )
        discovered.disk_free_gib = parse_df_avail_gib(df.out) if df.ok else 0.0

        ctx.progress(0.2, "looking up the public address")
        public = remote.run(PUBLIC_IP_COMMAND, timeout=20, quiet=True)
        discovered.public_ip = parse_ipv4(public.out) if public.ok else ""
        if not discovered.public_ip:
            route = remote.run(ROUTE_COMMAND, quiet=True)
            discovered.public_ip = parse_route_source(route.out) if route.ok else ""
        ipv6 = remote.run(IPV6_COMMAND, quiet=True)
        discovered.ipv6 = parse_ipv6_global(ipv6.out) if ipv6.ok else ""
        ctx.check_cancelled()

        timedate = self._timedate(ctx)
        discovered.ntp_synchronized = {"yes": True, "no": False}.get(timedate.get("NTPSynchronized", "").lower())
        discovered.timezone = timedate.get("Timezone", "")

        uid = remote.run("id -u", quiet=True).text
        discovered.is_root = uid == "0"
        discovered.has_apt = remote.command_exists("apt-get")
        discovered.has_systemd = remote.command_exists("systemctl")
        discovered.has_sudo = True if discovered.is_root else remote.run_ok("sudo -n true")
        return timedate

    def _discover_services(self, ctx: Context) -> None:
        """Fill the Java/Python, package, service, jar and config-file facts (secrets registered first)."""
        remote = ctx.remote
        discovered = ctx.discovered
        plan = ctx.plan

        java = remote.run("java -version 2>&1", quiet=True)
        discovered.java_version = parse_java_version(java.out + java.err) if java.ok else ""
        python = remote.run(PYTHON_VERSION_COMMAND, quiet=True)
        discovered.python_version = parse_python_version(python.out + python.err) if python.ok else ""
        discovered.venv_works = remote.run_ok(VENV_PROBE_COMMAND) if discovered.python_version else False
        ctx.check_cancelled()

        installed = {package: remote.dpkg_installed(package) for package in PACKAGES_CHECKED}
        discovered.pg_installed = installed["postgresql"]
        discovered.redis_installed = installed["redis-server"]
        discovered.clamd_installed = installed["clamav-daemon"]
        discovered.caddy_installed = installed["caddy"]
        if installed["ufw"]:
            status = remote.run("ufw status", quiet=True)
            discovered.ufw_active = status.ok and "status: active" in status.out.lower()
        else:
            discovered.ufw_active = None
        ctx.check_cancelled()

        listing = remote.run("screen -list", quiet=True)
        discovered.screen_running = screen_session_running(listing.out, plan.server.screen_session)
        jars = remote.run(
            f"ls {shlex.quote(plan.server.install_dir)}/cloud-driver-bootstrap-*.jar {shlex.quote(plan.extensions_dir)}/*.jar 2>/dev/null",
            quiet=True,
        )
        discovered.existing_jars = [Path(line.strip()).name for line in jars.out.splitlines() if line.strip()] if jars.ok else []

        discovered.existing_config = parse_json(remote.read_text(f"{plan.config_dir}/configuration.json"))
        discovered.existing_postgres = parse_json(remote.read_text(f"{plan.config_dir}/postgres-database.json"))
        discovered.existing_redis = parse_json(remote.read_text(f"{plan.config_dir}/redis-database.json"))
        for value in _str_values(discovered.existing_config, CONFIG_SECRET_KEYS):
            ctx.remember_secret(value)
        for document in (discovered.existing_postgres, discovered.existing_redis):
            for value in _str_values(document, ("password",)):
                ctx.remember_secret(value)

        discovered.aws_credentials_present = remote.exists(AWS_CREDENTIALS_FILE)
        discovered.intelligence_installed = remote.exists(INTELLIGENCE_UVICORN)
        discovered.intelligence_env = parse_env_file(remote.read_text(INTELLIGENCE_ENV_FILE))
        for key in INTELLIGENCE_SECRET_KEYS:
            ctx.remember_secret(discovered.intelligence_env.get(key))

    @staticmethod
    def _state_label(ctx: Context) -> str:
        discovered = ctx.discovered
        if discovered.existing_jars:
            return "already provisioned (jars: " + ", ".join(discovered.existing_jars) + ")"
        if discovered.existing_config or discovered.existing_postgres:
            return "already provisioned (config files present, no jars)"
        return "fresh box"

    # --- what apply would change ---------------------------------------------------------------

    @staticmethod
    def _timedate(ctx: Context) -> dict[str, str]:
        result = ctx.remote.run(TIMEDATE_COMMAND, quiet=True)
        return parse_key_values(result.out) if result.ok else {}

    @staticmethod
    def _ntp_in_place(timedate: dict[str, str]) -> bool:
        return timedate.get("NTPSynchronized", "").lower() == "yes" or timedate.get("NTP", "").lower() == "yes"

    @staticmethod
    def _directories(plan: InstallPlan) -> tuple[str, ...]:
        install_dir = plan.server.install_dir.rstrip("/")
        return (install_dir, plan.config_dir, plan.extensions_dir, f"{install_dir}/upload-scratch")

    def _directories_exist(self, ctx: Context) -> bool:
        return ctx.remote.run_ok(" && ".join(f"test -d {shlex.quote(path)}" for path in self._directories(ctx.plan)))

    @staticmethod
    def _ssh_config_path() -> Path:
        return Path.home() / ".ssh" / "config"

    def _ssh_alias_exists(self, ctx: Context) -> bool:
        path = self._ssh_config_path()
        try:
            text = path.read_text() if path.is_file() else ""
        except OSError:
            return False
        return ssh_alias_present(text, ctx.plan.server.ssh_alias_name)

    def _pending_changes(self, ctx: Context, timedate: dict[str, str]) -> list[str]:
        plan = ctx.plan
        changes: list[str] = []
        if plan.server.ntp and not self._ntp_in_place(timedate):
            changes.append("enable NTP time sync")
        if plan.server.timezone and timedate.get("Timezone", "") != plan.server.timezone:
            changes.append(f"set timezone {plan.server.timezone}")
        if not self._directories_exist(ctx):
            changes.append(f"create {plan.server.install_dir} (cloud-driver/, extensions/, upload-scratch/)")
        if plan.server.unattended_upgrades and not ctx.remote.dpkg_installed("unattended-upgrades"):
            changes.append("install unattended-upgrades")
        if plan.server.write_ssh_alias and not self._ssh_alias_exists(ctx):
            changes.append(f"add ssh alias {plan.server.ssh_alias_name} to ~/.ssh/config")
        return changes

    # --- apply -----------------------------------------------------------------------------------

    def apply(self, ctx: Context) -> None:
        """Clock sync, timezone, directories, unattended upgrades, local ssh alias - each only when needed."""
        plan = ctx.plan
        remote = ctx.remote
        try:
            timedate = self._timedate(ctx)
            if plan.server.ntp and not self._ntp_in_place(timedate):
                if not any(remote.service_active(unit) for unit in NTP_UNITS):
                    ctx.info("no NTP service is active - installing systemd-timesyncd")
                    remote.apt_install(["systemd-timesyncd"])
                ctx.check_cancelled()
                result = remote.run(SET_NTP_COMMAND)
                if result.ok:
                    ctx.info("NTP time synchronisation enabled (timedatectl set-ntp true)")
                else:
                    ctx.warn(f"`{SET_NTP_COMMAND}` failed: {(result.err or result.out).strip() or 'no output'}")
            if plan.server.timezone and timedate.get("Timezone", "") != plan.server.timezone:
                remote.run(f"timedatectl set-timezone {shlex.quote(plan.server.timezone)}", check=True)
                ctx.info(f"timezone set to {plan.server.timezone}")
            ctx.check_cancelled()

            remote.mkdirs(*self._directories(plan))
            ctx.debug("directories ensured: " + ", ".join(self._directories(plan)))

            if plan.server.unattended_upgrades:
                self._ensure_unattended_upgrades(ctx)
        except RemoteError as exc:
            raise StepError(f"server setup failed: {exc}") from exc

        if plan.server.write_ssh_alias:
            self._write_ssh_alias(ctx)

    def _ensure_unattended_upgrades(self, ctx: Context) -> None:
        remote = ctx.remote
        installed_now = False
        if not remote.dpkg_installed("unattended-upgrades"):
            ctx.info("installing unattended-upgrades")
            remote.apt_install(["unattended-upgrades"])
            installed_now = True
        periodic = remote.read_text(UNATTENDED_PERIODIC_FILE) or ""
        enabled = re.search(r'APT::Periodic::Unattended-Upgrade\s+"1"', periodic) is not None
        if installed_now or not enabled:
            result = remote.run(UNATTENDED_RECONFIGURE_COMMAND)
            if result.ok:
                ctx.info("unattended-upgrades enabled (security updates install automatically)")
                ctx.warn("a JDK update installed by unattended-upgrades only takes effect after the JVM is restarted")
            else:
                ctx.warn(f"`{UNATTENDED_RECONFIGURE_COMMAND}` failed: {(result.err or result.out).strip() or 'no output'}")
        else:
            ctx.debug("unattended-upgrades already installed and enabled")

    def _write_ssh_alias(self, ctx: Context) -> None:
        alias = ctx.plan.server.ssh_alias_name
        target = ctx.plan.ssh
        path = self._ssh_config_path()
        try:
            existing = path.read_text() if path.is_file() else ""
            if ssh_alias_present(existing, alias):
                ctx.debug(f"{path} already has a Host {alias} entry - left untouched")
                return
            path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            text = existing
            if text and not text.endswith("\n"):
                text += "\n"
            if text:
                text += "\n"
            text += render_ssh_alias_block(alias, target)
            if not path.exists():
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                os.close(fd)
            path.write_text(text)
        except OSError as exc:
            raise StepError(f"could not write the ssh alias to {path}: {exc}") from exc
        ctx.info(f"added Host {alias} ({target.user or 'root'}@{target.host}) to {path}")

    # --- verify ----------------------------------------------------------------------------------

    def verify(self, ctx: Context) -> VerifyResult:
        """Directories exist; NTP synchronized or at least enabled; timezone and alias as requested."""
        plan = ctx.plan
        try:
            if not self._directories_exist(ctx):
                return VerifyResult(False, f"{plan.server.install_dir} layout is missing after mkdir")
            parts = [f"{plan.server.install_dir} ready"]
            timedate = self._timedate(ctx) if plan.server.ntp or plan.server.timezone else {}
        except RemoteError as exc:
            raise StepError(f"server verification failed: {exc}") from exc
        if plan.server.ntp:
            if timedate.get("NTPSynchronized", "").lower() == "yes":
                parts.append("NTP synchronized")
            elif timedate.get("NTP", "").lower() == "yes":
                parts.append("NTP enabled (synchronisation pending)")
            else:
                return VerifyResult(False, "NTP could not be enabled (`timedatectl set-ntp true` was not accepted)")
        if plan.server.timezone:
            if timedate.get("Timezone", "") != plan.server.timezone:
                return VerifyResult(False, f"timezone is {timedate.get('Timezone', '?')}, expected {plan.server.timezone}")
            parts.append(f"timezone {plan.server.timezone}")
        if plan.server.write_ssh_alias:
            if not self._ssh_alias_exists(ctx):
                return VerifyResult(False, f"~/.ssh/config has no Host {plan.server.ssh_alias_name} entry")
            parts.append(f"ssh alias {plan.server.ssh_alias_name} present")
        return VerifyResult(True, " · ".join(parts))

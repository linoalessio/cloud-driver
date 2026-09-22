"""OS-level steps: preflight discovery, base packages, Java, Python, firewall and swap."""

from __future__ import annotations

import os
import re
import shlex
from pathlib import Path

from cloud_driver_installer.config_files import parse_env_file, parse_json
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan, apply_existing_config
from cloud_driver_installer.sizing import suggest_jvm_xmx

#: Packages every deployment needs (screen for the jline pty, cron/logrotate for the managed jobs,
#: fonts so PDFBox renders thumbnails of PDFs without embedded fonts).
BASE_PACKAGES: tuple[str, ...] = (
    "screen",
    "curl",
    "gnupg",
    "ca-certificates",
    "apt-transport-https",
    "openssl",
    "unzip",
    "cron",
    "logrotate",
    "fonts-dejavu-core",
)

#: Base packages a removal may purge. The rest of :data:`BASE_PACKAGES` is what a Debian system
#: (or apt itself) needs to keep working - purging ``cron``, ``curl``, ``gnupg``,
#: ``ca-certificates`` or ``logrotate`` would break far more than this installer ever installed.
PURGEABLE_BASE_PACKAGES: tuple[str, ...] = ("screen", "unzip", "fonts-dejavu-core", "awscli")

#: One ``ufw status`` rule line: "<to>  ALLOW  <from>", columns separated by runs of whitespace.
_UFW_RULE_RE = re.compile(r"^(?P<to>\S.*?)\s{2,}(?P<action>ALLOW|DENY|REJECT|LIMIT)(?:\s+(?:IN|OUT|FWD))?\s{2,}(?P<from>.+)$")

#: NTP implementations that satisfy the clock requirement (AWS SigV4 rejects a drifted clock).
NTP_PACKAGES: tuple[str, ...] = ("systemd-timesyncd", "chrony", "ntpsec", "ntp")


def parse_ufw_status(text: str) -> tuple[bool, set[str]]:
    """``(active, {allowed rule names})`` from ``ufw status``.

    Parsed, not searched: asking whether ``"80"`` appears anywhere in that output answers yes for
    a host that only allows 8080, so the check would report a port as open that is closed. The
    ``(v6)`` twin of a rule folds into its IPv4 name, and a rule is only allowed if its action is.
    """
    active = False
    allowed: set[str] = set()
    for line in (text or "").splitlines():
        stripped = line.strip()
        if stripped.lower().startswith("status:"):
            active = stripped.split(":", 1)[1].strip().lower() == "active"
            continue
        match = _UFW_RULE_RE.match(stripped)
        if match and match.group("action") == "ALLOW":
            allowed.add(match.group("to").replace(" (v6)", "").strip())
    return active, allowed


def missing_rules(wanted: list[str], allowed: set[str]) -> list[str]:
    """The wanted rules ufw does not already allow (``22/tcp`` also counts as allowed as ``22``)."""
    present = {rule.lower() for rule in allowed}
    return [rule for rule in wanted if rule.lower() not in present and rule.split("/")[0].lower() not in present]


def _text(ctx: Context, command: str) -> str:
    """Run ``command`` quietly and return its stripped stdout (empty on failure)."""
    result = ctx.remote.run(command, quiet=True, timeout=60)
    return result.out.strip() if result.ok else ""


def _int(value: str, default: int = 0) -> int:
    try:
        return int(value.strip())
    except (AttributeError, ValueError):
        return default


class ServerStep(Step):
    """Preflight: refuse an unsupported host, discover its state, and take over an existing config."""

    id = "server"
    title = "Server"
    mandatory = True
    removable = True

    def check(self, ctx: Context) -> CheckResult:
        remote, found = ctx.remote, ctx.discovered
        found.is_root = _text(ctx, "id -u") == "0"
        found.has_apt = remote.command_exists("apt-get")
        found.has_systemd = remote.command_exists("systemctl")
        if not found.has_apt:
            raise StepError("this is not a Debian/Ubuntu (apt) host - the installer only supports apt-based distributions")
        if not found.has_systemd:
            raise StepError("no systemd on this host - clamd's socket unit and the intelligence service both need it")
        if not found.is_root:
            raise StepError("connect as root: the JVM, its /root/.aws credentials and the managed crontab all belong to root")

        os_line = _text(ctx, '. /etc/os-release 2>/dev/null; printf "%s|%s" "$PRETTY_NAME" "$ID"')
        found.os_pretty, _, found.os_id = os_line.partition("|")
        found.kernel = _text(ctx, "uname -r")
        found.cpu_count = _int(_text(ctx, "nproc"))
        found.ram_mib = _int(_text(ctx, "free -m | awk '/^Mem:/{print $2}'"))
        found.swap_mib = _int(_text(ctx, "free -m | awk '/^Swap:/{print $2}'"))
        install_dir = ctx.plan.server.install_dir
        # The directory may not exist yet, so fall back to the root filesystem it will live on.
        free_mib = _int(_text(ctx, f"df -Pm {shlex.quote(install_dir)} 2>/dev/null || df -Pm /; true" + " | awk 'NR==2{print $4}'"))
        found.disk_free_gib = round(free_mib / 1024, 1)
        found.public_ip = _text(ctx, "curl -4 -s --max-time 5 https://api.ipify.org 2>/dev/null || ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}'")
        found.ipv6 = _text(ctx, "ip -6 addr show scope global 2>/dev/null | awk '/inet6/{print $2; exit}'").split("/")[0]

        clock = parse_env_file(_text(ctx, "timedatectl show -p NTPSynchronized -p Timezone 2>/dev/null"))
        found.ntp_synchronized = clock.get("NTPSynchronized") == "yes" if clock else None
        found.timezone = clock.get("Timezone", "")

        java = _text(ctx, "java -version 2>&1 | head -1")
        match = re.search(r'"(\d+)(?:\.[\d._]+)?"', java)
        found.java_version = match.group(0).strip('"') if match else ""
        found.python_version = _text(ctx, "python3 --version 2>&1").replace("Python", "").strip()
        found.venv_works = remote.run_ok(
            'probe="$(mktemp -d)"; python3 -m venv "$probe/v" >/dev/null 2>&1 && [ -x "$probe/v/bin/pip" ]; rc=$?; rm -rf "$probe"; exit $rc',
            timeout=180,
        )

        found.pg_installed = remote.dpkg_installed("postgresql")
        found.pg_version = _text(ctx, "psql --version 2>/dev/null | awk '{print $3}'")
        found.redis_installed = remote.dpkg_installed("redis-server")
        found.clamd_installed = remote.dpkg_installed("clamav-daemon")
        found.caddy_installed = remote.command_exists("caddy")
        found.ufw_active = "active" in _text(ctx, "ufw status 2>/dev/null | head -1").lower()
        found.screen_running = ctx.remote.run_ok(f"screen -list 2>/dev/null | grep -q '[.]{ctx.plan.server.screen_session}[[:space:]]'")
        found.existing_jars = [
            line.strip()
            for line in _text(ctx, f"ls -1 {shlex.quote(ctx.plan.server.install_dir)}/cloud-driver-bootstrap-*.jar {shlex.quote(ctx.plan.extensions_dir)}/*.jar 2>/dev/null").splitlines()
            if line.strip()
        ]
        found.aws_credentials_present = remote.exists("/root/.aws/credentials")
        found.intelligence_installed = remote.exists("/opt/cloud-driver-intelligence/.venv/bin/uvicorn")
        found.intelligence_env = parse_env_file(remote.read_text("/etc/cloud-driver-intelligence.env"))

        found.existing_config = parse_json(remote.read_text(f"{ctx.plan.config_dir}/configuration.json"))
        found.existing_postgres = parse_json(remote.read_text(f"{ctx.plan.config_dir}/postgres-database.json"))
        found.existing_redis = parse_json(remote.read_text(f"{ctx.plan.config_dir}/redis-database.json"))
        self._remember_existing_secrets(ctx)

        if found.existing_config or found.existing_postgres:
            taken = apply_existing_config(ctx.plan, found.existing_config, postgres=found.existing_postgres or None, redis=found.existing_redis or None)
            for line in taken:
                ctx.info(f"[Server] taken over from this server: {line}")

        if not ctx.plan.app.jvm_xmx and found.ram_mib:
            ctx.plan.app.jvm_xmx = suggest_jvm_xmx(
                found.ram_mib,
                postgres_local=ctx.plan.postgres.mode == "install",
                clamav_enabled=ctx.plan.clamav.enabled,
                intelligence_enabled=ctx.plan.intelligence.enabled,
            )
            ctx.info(f"[Server] suggested JVM heap -Xmx{ctx.plan.app.jvm_xmx} for {found.ram_mib} MiB of RAM")

        needed_free = 15 if ctx.plan.intelligence.enabled else 10
        if found.disk_free_gib and found.disk_free_gib < needed_free:
            ctx.warn(f"[Server] only {found.disk_free_gib} GiB free on {install_dir} - at least {needed_free} GiB is recommended")

        detail = found.summary() or "server reachable"
        if found.existing_jars:
            detail += f" · already provisioned ({len(found.existing_jars)} jars)"
        if self._pending(ctx):
            return CheckResult.needs_apply(detail + " · " + ", ".join(self._pending(ctx)))
        return CheckResult.ok(detail)

    def apply(self, ctx: Context) -> None:
        plan, found = ctx.plan, ctx.discovered
        ctx.remote.mkdirs(plan.server.install_dir, plan.config_dir, plan.extensions_dir, f"{plan.server.install_dir.rstrip('/')}/upload-scratch")
        if plan.server.ntp and found.ntp_synchronized is not True:
            self._ensure_ntp(ctx)
        if plan.server.timezone and plan.server.timezone != found.timezone:
            ctx.remote.run(f"timedatectl set-timezone {shlex.quote(plan.server.timezone)}", check=True)
            ctx.info(f"[Server] timezone set to {plan.server.timezone}")
        if plan.server.unattended_upgrades and not ctx.remote.dpkg_installed("unattended-upgrades"):
            ctx.remote.apt_install(["unattended-upgrades"])
            ctx.remote.run("dpkg-reconfigure -f noninteractive unattended-upgrades", check=False)
        if plan.server.install_public_key:
            self._install_public_key(ctx)
        if plan.server.write_ssh_alias:
            self._write_ssh_alias(ctx)

    def verify(self, ctx: Context) -> VerifyResult:
        plan = ctx.plan
        for path in (plan.server.install_dir, plan.config_dir, plan.extensions_dir):
            if not ctx.remote.exists(path):
                return VerifyResult(False, f"{path} was not created")
        return VerifyResult(True, ctx.discovered.summary() or "ready")

    def describe(self, plan: InstallPlan) -> str:
        parts = [f"create {plan.server.install_dir} (config, extensions, upload-scratch)"]
        if plan.server.ntp:
            parts.append("ensure clock synchronisation")
        if plan.server.timezone:
            parts.append(f"timezone {plan.server.timezone}")
        if plan.server.install_public_key:
            parts.append("install your public key for root")
        if plan.server.write_ssh_alias:
            parts.append(f"write the ~/.ssh/config alias {plan.server.ssh_alias_name}")
        return " · ".join(parts)

    def remove(self, ctx: Context) -> None:
        """Delete the directory layout and the operator-side alias; leave the host's own settings."""
        plan = ctx.plan
        ctx.remote.delete(plan.server.install_dir)
        ctx.info(f"[Server] deleted {plan.server.install_dir} and everything in it")
        self._remove_ssh_alias(ctx)
        ctx.warn("[Server] the timezone, clock synchronisation, unattended upgrades and the installed public key are host settings and stay as they are")

    def describe_removal(self, plan: InstallPlan) -> str:
        parts = [f"delete {plan.server.install_dir} recursively - the jars, the extensions, the config files and the upload scratch area all live in it"]
        if plan.server.write_ssh_alias:
            parts.append(f"remove the Host {plan.server.ssh_alias_name} entry from your own ~/.ssh/config")
        parts.append("the timezone, NTP, unattended upgrades and the root public key are left untouched (they are the host's, not this deployment's)")
        return " · ".join(parts)

    # --- internals -------------------------------------------------------------------------------

    def _remove_ssh_alias(self, ctx: Context) -> None:
        """Drop the ``Host <alias>`` block this step appended to the operator's own ssh config."""
        if not ctx.plan.server.write_ssh_alias:
            return
        name = ctx.plan.server.ssh_alias_name
        config = Path.home() / ".ssh" / "config"
        if not config.is_file():
            return
        lines = config.read_text().splitlines()
        kept: list[str] = []
        dropping = False
        for line in lines:
            if re.match(r"^\s*Host\s+", line):
                dropping = bool(re.match(rf"^\s*Host\s+{re.escape(name)}\s*$", line))
            if not dropping:
                kept.append(line)
        if len(kept) != len(lines):
            config.write_text("\n".join(kept).rstrip("\n") + "\n")
            ctx.info(f"[Server] removed Host {name} from ~/.ssh/config")

    def _pending(self, ctx: Context) -> list[str]:
        pending: list[str] = []
        plan, found = ctx.plan, ctx.discovered
        if plan.server.ntp and found.ntp_synchronized is not True:
            pending.append("clock not synchronised")
        if plan.server.timezone and plan.server.timezone != found.timezone:
            pending.append(f"timezone {found.timezone or 'unknown'} -> {plan.server.timezone}")
        if plan.server.install_public_key:
            pending.append("public key")
        if plan.server.write_ssh_alias:
            pending.append("ssh alias")
        if not ctx.remote.exists(plan.extensions_dir):
            pending.append("directory layout")
        return pending

    def _remember_existing_secrets(self, ctx: Context) -> None:
        found = ctx.discovered
        for document, key in (
            (found.existing_config, "jwt-signing-key"),
            (found.existing_config, "intelligence-shared-secret"),
            (found.existing_config, "smtp-password"),
            (found.existing_postgres, "password"),
            (found.existing_redis, "password"),
        ):
            value = document.get(key) if isinstance(document, dict) else None
            if isinstance(value, str):
                ctx.remember_secret(value)
        for value in found.intelligence_env.values():
            ctx.remember_secret(value)

    def _ensure_ntp(self, ctx: Context) -> None:
        installed = [name for name in NTP_PACKAGES if ctx.remote.dpkg_installed(name)]
        if not installed:
            ctx.remote.apt_install(["systemd-timesyncd"])
            installed = ["systemd-timesyncd"]
        unit = {"systemd-timesyncd": "systemd-timesyncd", "chrony": "chrony", "ntpsec": "ntpsec", "ntp": "ntp"}[installed[0]]
        ctx.remote.systemctl("enable", "--now", unit, check=False)
        ctx.remote.run("timedatectl set-ntp true", check=False)
        ctx.info(f"[Server] clock synchronisation through {unit}")

    def _install_public_key(self, ctx: Context) -> None:
        path = Path(os.path.expanduser(ctx.plan.server.public_key_path))
        key_line = path.read_text().strip()
        if not key_line:
            raise StepError(f"{path} is empty")
        ctx.remote.run("mkdir -p /root/.ssh && chmod 700 /root/.ssh && touch /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys", check=True)
        existing = ctx.remote.read_text("/root/.ssh/authorized_keys") or ""
        if key_line.split()[1] in existing:
            ctx.info("[Server] the public key is already in /root/.ssh/authorized_keys")
            return
        ctx.remote.run("cat >> /root/.ssh/authorized_keys", input=key_line + "\n", check=True)
        ctx.info(f"[Server] added {path.name} to /root/.ssh/authorized_keys")

    def _write_ssh_alias(self, ctx: Context) -> None:
        target = ctx.plan.ssh
        name = ctx.plan.server.ssh_alias_name
        config = Path.home() / ".ssh" / "config"
        current = config.read_text() if config.is_file() else ""
        if re.search(rf"^\s*Host\s+.*\b{re.escape(name)}\b", current, re.MULTILINE):
            ctx.warn(f"[Server] ~/.ssh/config already has a Host {name} entry - leaving it untouched")
            return
        block = [f"\nHost {name}", f"    HostName {target.host}", f"    User {target.user}"]
        if target.port != 22:
            block.append(f"    Port {target.port}")
        if target.auth == "key" and target.key_path:
            block.append(f"    IdentityFile {target.key_path}")
        config.parent.mkdir(parents=True, exist_ok=True)
        with config.open("a") as handle:
            handle.write("\n".join(block) + "\n")
        ctx.info(f"[Server] added Host {name} to ~/.ssh/config")


class PackagesStep(Step):
    """The apt packages every other step assumes."""

    id = "packages"
    title = "Base packages"
    mandatory = True
    depends_on = ("server",)
    removable = True

    def packages(self, ctx: Context) -> list[str]:
        """Base packages plus the ones this plan's options need."""
        packages = list(BASE_PACKAGES)
        if ctx.plan.app.backup_offsite:
            packages.append("awscli")
        return packages

    def check(self, ctx: Context) -> CheckResult:
        missing = [name for name in self.packages(ctx) if not ctx.remote.dpkg_installed(name)]
        if missing:
            return CheckResult.needs_apply(f"{len(missing)} of {len(self.packages(ctx))} missing: {', '.join(missing)}")
        return CheckResult.ok("every base package installed")

    def apply(self, ctx: Context) -> None:
        missing = [name for name in self.packages(ctx) if not ctx.remote.dpkg_installed(name)]
        if missing:
            ctx.remote.apt_install(missing)
        ctx.remote.systemctl("enable", "--now", "cron", check=False)

    def verify(self, ctx: Context) -> VerifyResult:
        missing = [name for name in self.packages(ctx) if not ctx.remote.dpkg_installed(name)]
        if missing:
            return VerifyResult(False, f"still missing: {', '.join(missing)}")
        return VerifyResult(True, f"{len(self.packages(ctx))} packages installed")

    def describe(self, plan: InstallPlan) -> str:
        extra = " + awscli" if plan.app.backup_offsite else ""
        return f"apt-get install {len(BASE_PACKAGES)} base packages{extra}, enable cron"

    def remove(self, ctx: Context) -> None:
        """Purge only the packages nothing else on a Debian host depends on."""
        ctx.remote.apt_purge(list(PURGEABLE_BASE_PACKAGES))
        kept = [name for name in BASE_PACKAGES if name not in PURGEABLE_BASE_PACKAGES]
        ctx.warn("[Base packages] kept (the system needs them): " + ", ".join(kept))

    def describe_removal(self, plan: InstallPlan) -> str:
        return (
            "apt-get purge " + " ".join(PURGEABLE_BASE_PACKAGES)
            + " · the remaining base packages (" + ", ".join(name for name in BASE_PACKAGES if name not in PURGEABLE_BASE_PACKAGES)
            + ") are kept: purging them would break apt, the system's cron jobs or its log rotation"
        )


class JavaStep(Step):
    """JDK 21 - the only version the backend builds and runs against."""

    id = "java"
    title = "Java 21"
    mandatory = True
    depends_on = ("server",)
    removable = True

    def check(self, ctx: Context) -> CheckResult:
        version = ctx.discovered.java_version or _text(ctx, "java -version 2>&1 | head -1")
        if version.startswith("21"):
            return CheckResult.ok(f"OpenJDK {version}")
        return CheckResult.needs_apply("not installed" if not version else f"Java {version} - 21 is required")

    def apply(self, ctx: Context) -> None:
        ctx.remote.apt_install(["openjdk-21-jdk-headless"])
        if not _text(ctx, "java -version 2>&1 | head -1").count("21"):
            ctx.remote.run("update-alternatives --set java $(ls -1 /usr/lib/jvm/java-21-openjdk-*/bin/java | head -1)", check=False)

    def verify(self, ctx: Context) -> VerifyResult:
        line = _text(ctx, "java -version 2>&1 | head -1")
        match = re.search(r'"(\d+)', line)
        ctx.discovered.java_version = (re.search(r'"([\d._]+)"', line).group(1) if re.search(r'"([\d._]+)"', line) else "")
        if match and match.group(1) == "21":
            return VerifyResult(True, line)
        return VerifyResult(False, f"java -version reports {line or 'nothing'}")

    def describe(self, plan: InstallPlan) -> str:
        return "apt-get install openjdk-21-jdk-headless"

    def remove(self, ctx: Context) -> None:
        """Purge the JDK. The backend cannot start afterwards - that is the point of removing it."""
        if ctx.remote.service_active("screen") or ctx.remote.run_ok("pgrep -f cloud-driver-bootstrap >/dev/null 2>&1"):
            ctx.warn("[Java 21] the backend is still running - it dies with the JVM this removes")
        ctx.remote.apt_purge(["openjdk-21-jdk-headless", "openjdk-21-jre-headless"])
        ctx.discovered.java_version = ""

    def describe_removal(self, plan: InstallPlan) -> str:
        return "apt-get purge openjdk-21-jdk-headless (and the matching JRE) · the backend cannot run without it"


class PythonStep(Step):
    """Python 3 with a working ``venv`` - the intelligence service is built from it."""

    id = "python"
    title = "Python 3"
    mandatory = True
    depends_on = ("server",)
    removable = True

    def check(self, ctx: Context) -> CheckResult:
        version = ctx.discovered.python_version
        works = ctx.discovered.venv_works
        if ctx.plan.intelligence.enabled and ctx.plan.intelligence.enable_encryption and version:
            if tuple(int(p) for p in re.findall(r"\d+", version)[:2]) < (3, 11):
                raise StepError(
                    f"the encrypted vector store needs Python 3.11+, this server has {version} - "
                    "install a newer interpreter or switch the intelligence encryption off"
                )
        if version and works:
            return CheckResult.ok(f"Python {version}, venv works")
        if not version:
            return CheckResult.needs_apply("python3 not installed")
        return CheckResult.needs_apply(f"Python {version} but venv/ensurepip is missing")

    def apply(self, ctx: Context) -> None:
        ctx.remote.apt_install(["python3", "python3-venv", "python3-pip"])
        if not self._venv_works(ctx):
            version = _text(ctx, "python3 --version 2>&1").replace("Python", "").strip()
            short = ".".join(version.split(".")[:2])
            if short:
                ctx.remote.apt_install([f"python{short}-venv"])

    def verify(self, ctx: Context) -> VerifyResult:
        ctx.discovered.python_version = _text(ctx, "python3 --version 2>&1").replace("Python", "").strip()
        ctx.discovered.venv_works = self._venv_works(ctx)
        if ctx.discovered.venv_works:
            return VerifyResult(True, f"Python {ctx.discovered.python_version}, venv works")
        return VerifyResult(False, "python3 -m venv still cannot create a working environment")

    def describe(self, plan: InstallPlan) -> str:
        return "apt-get install python3, python3-venv, python3-pip"

    def remove(self, ctx: Context) -> None:
        """Purge the venv/pip tooling. ``python3`` itself stays: apt is written in it."""
        ctx.remote.apt_purge(["python3-venv", "python3-pip"])
        ctx.discovered.venv_works = False
        ctx.warn("[Python 3] python3 itself is kept - apt, unattended-upgrades and half of Debian are written in it")

    def describe_removal(self, plan: InstallPlan) -> str:
        return "apt-get purge python3-venv and python3-pip · python3 itself is kept, because purging it takes apt and most of Debian's tooling with it"

    @staticmethod
    def _venv_works(ctx: Context) -> bool:
        return ctx.remote.run_ok(
            'probe="$(mktemp -d)"; python3 -m venv "$probe/v" >/dev/null 2>&1 && [ -x "$probe/v/bin/pip" ]; rc=$?; rm -rf "$probe"; exit $rc',
            timeout=180,
        )


class FirewallStep(Step):
    """``ufw``: SSH, 80 and 443 in, everything else denied (the app manages no firewall of its own)."""

    id = "firewall"
    title = "Firewall (ufw)"
    depends_on = ("packages",)
    removable = True

    def enabled(self, plan: InstallPlan) -> bool:
        return plan.server.firewall

    def ssh_ports(self, ctx: Context) -> list[str]:
        """Every port sshd listens on, plus the port this session uses - opened before enabling."""
        configured = [p for p in _text(ctx, "sshd -T 2>/dev/null | awk '/^port /{print $2}'").split() if p.isdigit()]
        ports = configured or ["22"]
        session_port = str(ctx.plan.ssh.port)
        if session_port not in ports:
            ports.append(session_port)
        return ports

    def rules(self, ctx: Context) -> list[str]:
        """Allow rules in the order they are added.

        80 and 443 are Caddy's. Without the reverse proxy the JVM is the public listener itself,
        so its REST port is opened instead - otherwise a proxy-less deployment ends up firewalled
        off from its own clients.
        """
        rules = [f"{port}/tcp" for port in self.ssh_ports(ctx)] + ["80/tcp", "443/tcp"]
        if not ctx.plan.proxy.enabled and ctx.plan.app.rest_bind_host not in ("127.0.0.1", "localhost", "::1"):
            rules.append(f"{ctx.plan.app.rest_port}/tcp")
        extra = [item for item in re.split(r"[,\s]+", ctx.plan.server.firewall_extra_ports or "") if item]
        rules += [item if "/" in item else f"{item}/tcp" for item in extra]
        return list(dict.fromkeys(rules))

    def check(self, ctx: Context) -> CheckResult:
        if not ctx.remote.dpkg_installed("ufw"):
            return CheckResult.needs_apply("ufw not installed - this host has no firewall at all")
        active, allowed = parse_ufw_status(_text(ctx, "ufw status 2>/dev/null"))
        missing = missing_rules(self.rules(ctx), allowed)
        if active and not missing:
            return CheckResult.ok("ufw active · " + ", ".join(self.rules(ctx)))
        return CheckResult.needs_apply(("inactive" if not active else "active") + (f" · missing {', '.join(missing)}" if missing else ""))

    def apply(self, ctx: Context) -> None:
        if not ctx.remote.dpkg_installed("ufw"):
            ctx.remote.apt_install(["ufw"])
        rules = self.rules(ctx)
        session_rule = f"{ctx.plan.ssh.port}/tcp"
        if session_rule not in rules:
            raise StepError(f"refusing to enable the firewall: this session's SSH port {ctx.plan.ssh.port} is not in the allow list")
        for rule in rules:  # SSH first, so enabling can never lock the operator out
            ctx.remote.run(f"ufw allow {shlex.quote(rule)}", check=True)
        ctx.remote.run("ufw default deny incoming && ufw default allow outgoing", check=True)
        ctx.remote.run("ufw --force enable", check=True)

    def verify(self, ctx: Context) -> VerifyResult:
        active, allowed = parse_ufw_status(_text(ctx, "ufw status verbose 2>/dev/null"))
        ctx.discovered.ufw_active = active
        if not active:
            return VerifyResult(False, "ufw is not active")
        missing = missing_rules(self.rules(ctx), allowed)
        if missing:  # the rules are the point of the step, so verify has to read them back
            return VerifyResult(False, "ufw is active but does not allow " + ", ".join(missing))
        return VerifyResult(True, "ufw active · " + ", ".join(self.rules(ctx)))

    def describe(self, plan: InstallPlan) -> str:
        direct = "" if plan.proxy.enabled or plan.app.rest_bind_host in ("127.0.0.1", "localhost", "::1") else f", {plan.app.rest_port}"
        return f"ufw: allow SSH, 80, 443{direct} (plus extras), deny incoming, enable"

    def remove(self, ctx: Context) -> None:
        """Disable the firewall, drop every rule, purge ufw. The host is then wide open."""
        if ctx.remote.dpkg_installed("ufw"):
            ctx.remote.run("ufw --force reset", check=False, timeout=300)  # also disables it
            ctx.remote.run("ufw --force disable", check=False)
            ctx.remote.apt_purge(["ufw"])
        ctx.discovered.ufw_active = False
        ctx.warn("[Firewall (ufw)] this host now has no firewall: every open port is reachable from the internet unless the provider filters it")

    def describe_removal(self, plan: InstallPlan) -> str:
        return "ufw --force reset, disable, then apt-get purge ufw · every rule is gone and the host has no firewall left (SSH stays reachable)"


class SwapStep(Step):
    """A swapfile as the kernel-OOM safety net under the JVM heap."""

    id = "swap"
    title = "Swap"
    depends_on = ("server",)
    removable = True

    def enabled(self, plan: InstallPlan) -> bool:
        return plan.server.swap_mb > 0

    def check(self, ctx: Context) -> CheckResult:
        active = _text(ctx, "swapon --show=NAME,SIZE --noheadings 2>/dev/null")
        if active:
            return CheckResult.ok(f"existing swap kept: {' '.join(active.split())}")
        return CheckResult.needs_apply(f"no swap · {ctx.plan.server.swap_mb} MB planned")

    def apply(self, ctx: Context) -> None:
        size = ctx.plan.server.swap_mb
        ctx.remote.run(
            "set -e; "
            f"fallocate -l {size}M /swapfile || dd if=/dev/zero of=/swapfile bs=1M count={size}; "
            "chmod 600 /swapfile; mkswap /swapfile; swapon /swapfile; "
            "grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab",
            check=True,
            timeout=900,
        )

    def verify(self, ctx: Context) -> VerifyResult:
        active = _text(ctx, "swapon --show=NAME,SIZE --noheadings 2>/dev/null")
        ctx.discovered.swap_mib = _int(_text(ctx, "free -m | awk '/^Swap:/{print $2}'"))
        return VerifyResult(bool(active), f"swap active: {' '.join(active.split())}" if active else "no swap is active")

    def describe(self, plan: InstallPlan) -> str:
        return f"create a {plan.server.swap_mb} MB /swapfile (kept as-is when swap already exists)"

    def remove(self, ctx: Context) -> None:
        """Switch /swapfile off, delete it and drop its fstab line - other swap areas stay."""
        if not ctx.remote.exists("/swapfile"):
            ctx.info("[Swap] no /swapfile on this host")
        else:
            ctx.remote.run("swapoff /swapfile", check=False, timeout=600)
            ctx.remote.delete("/swapfile")
        ctx.remote.run("sed -i '\\|^/swapfile[[:space:]]|d' /etc/fstab", check=False)
        ctx.discovered.swap_mib = _int(_text(ctx, "free -m | awk '/^Swap:/{print $2}'"))
        ctx.warn("[Swap] without swap the kernel kills the JVM outright when the heap and the page cache no longer fit in RAM")

    def describe_removal(self, plan: InstallPlan) -> str:
        return "swapoff /swapfile, delete the file and remove its /etc/fstab line · a swap area this installer did not create is left alone"

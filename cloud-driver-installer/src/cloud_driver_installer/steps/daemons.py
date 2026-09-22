"""The two daemons beside the JVM: ``clamd`` (malware scanning) and Caddy (TLS termination)."""

from __future__ import annotations

import re
import shlex

from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan

#: Debian's clamav-daemon is socket-activated: clamd only opens sockets systemd hands it, so the
#: TCP listener has to be a drop-in on the SOCKET unit. Exactly one (IPv4) ListenStream - a second
#: (IPv6) one makes clamd 1.4 crash-loop with "Received more than two file descriptors".
CLAMD_SOCKET_DROPIN = "/etc/systemd/system/clamav-daemon.socket.d/tcp.conf"
CLAMD_CONF = "/etc/clamav/clamd.conf"
CADDYFILE = "/etc/caddy/Caddyfile"


def render_clamd_dropin(host: str, port: int) -> str:
    """The socket drop-in contents."""
    return f"[Socket]\nListenStream={host}:{port}\n"


def rewrite_clamd_conf(config: str, limits: dict[str, str]) -> str:
    """Return ``config`` with each of ``limits`` set (replacing an existing line or appended)."""
    lines = config.splitlines()
    out: list[str] = []
    seen: set[str] = set()
    for line in lines:
        key = line.split(" ", 1)[0].strip()
        if key in limits:
            if key in seen:
                continue
            out.append(f"{key} {limits[key]}")
            seen.add(key)
        else:
            out.append(line)
    for key, value in limits.items():
        if key not in seen:
            out.append(f"{key} {value}")
    return "\n".join(out).rstrip("\n") + "\n"


def replace_site_block(caddyfile: str, domain: str, block: str) -> str:
    """Replace ``domain``'s site block in ``caddyfile`` with ``block`` (appended when absent).

    Blocks are matched by brace depth from the ``<domain> {`` line to its closing brace, so every
    other site block - and the apex block ``deploy-homepage.sh`` maintains - passes through
    untouched.
    """
    lines = caddyfile.splitlines()
    out: list[str] = []
    index = 0
    replaced = False
    opener = re.compile(rf"^\s*{re.escape(domain)}\s*(,[^{{]*)?\{{\s*$")
    while index < len(lines):
        if opener.match(lines[index]):
            depth = 0
            while index < len(lines):
                depth += lines[index].count("{") - lines[index].count("}")
                index += 1
                if depth <= 0:
                    break
            out.extend(block.rstrip("\n").splitlines())
            replaced = True
            continue
        out.append(lines[index])
        index += 1
    if not replaced:
        if out and out[-1].strip():
            out.append("")
        out.extend(block.rstrip("\n").splitlines())
    return "\n".join(out).rstrip("\n") + "\n"


def ensure_global_block(caddyfile: str, acme_email: str) -> str:
    """Put a global options block carrying ``acme_email`` first in ``caddyfile`` (Caddy requires it there)."""
    if not acme_email:
        return caddyfile
    lines = caddyfile.splitlines()
    if lines and lines[0].strip() == "{":
        depth = 0
        end = 0
        for index, line in enumerate(lines):
            depth += line.count("{") - line.count("}")
            if depth <= 0:
                end = index
                break
        body = [line for line in lines[1:end] if not line.strip().startswith("email ")]
        rebuilt = ["{", f"\temail {acme_email}", *body, "}", *lines[end + 1 :]]
        return "\n".join(rebuilt).rstrip("\n") + "\n"
    return "{\n" + f"\temail {acme_email}\n" + "}\n\n" + caddyfile.lstrip("\n")


def render_site_block(domain: str, rest_port: int) -> str:
    """The API site block: TLS terminates here, and X-Forwarded-For is overwritten, never appended.

    reverse_proxy's default appends the peer to whatever the client sent, which would leave a
    client-controlled value in the header the backend's rate limiter reads.
    """
    return (
        f"{domain} {{\n"
        f"    reverse_proxy 127.0.0.1:{rest_port} {{\n"
        f"        header_up X-Forwarded-For {{remote_host}}\n"
        f"    }}\n"
        f"}}\n"
    )


class ClamAvStep(Step):
    """``clamd`` on a loopback TCP socket, with size limits above the application's scan ceiling."""

    id = "clamav"
    title = "ClamAV"
    depends_on = ("packages",)

    def enabled(self, plan: InstallPlan) -> bool:
        return plan.clamav.enabled

    def limits(self, plan: InstallPlan) -> dict[str, str]:
        """clamd size limits - kept above ``content-scan-max-bytes`` so nothing in the gap fails open."""
        return {
            "StreamMaxLength": plan.clamav.stream_max_length,
            "MaxFileSize": plan.clamav.max_file_size,
            "MaxScanSize": plan.clamav.max_scan_size,
        }

    def check(self, ctx: Context) -> CheckResult:
        plan = ctx.plan.clamav
        if not (ctx.remote.dpkg_installed("clamav-daemon") and ctx.remote.dpkg_installed("clamav-freshclam")):
            return CheckResult.needs_apply("not installed")
        dropin = ctx.remote.read_text(CLAMD_SOCKET_DROPIN) or ""
        config = ctx.remote.read_text(CLAMD_CONF) or ""
        listener_ok = f"ListenStream={plan.host}:{plan.port}" in dropin and dropin.count("ListenStream=") == 1
        limits_ok = all(f"{key} {value}" in config for key, value in self.limits(ctx.plan).items())
        if listener_ok and limits_ok and ctx.remote.service_active("clamav-daemon.socket"):
            return CheckResult.ok(f"clamd listening on {plan.host}:{plan.port}, limits raised")
        missing = [text for text, ok in (("TCP socket drop-in", listener_ok), ("size limits", limits_ok)) if not ok]
        return CheckResult.needs_apply(", ".join(missing) or "socket unit not active")

    def apply(self, ctx: Context) -> None:
        plan = ctx.plan.clamav
        if not ctx.remote.dpkg_installed("clamav-daemon"):
            ctx.remote.apt_install(["clamav-daemon", "clamav-freshclam"])
        ctx.remote.mkdirs("/etc/systemd/system/clamav-daemon.socket.d")
        ctx.remote.put_text(CLAMD_SOCKET_DROPIN, render_clamd_dropin(plan.host, plan.port))
        config = ctx.remote.read_text(CLAMD_CONF) or ""
        ctx.remote.put_text(CLAMD_CONF, rewrite_clamd_conf(config, self.limits(ctx.plan)), mode=0o644)
        ctx.remote.systemctl("daemon-reload")
        ctx.remote.systemctl("enable", "clamav-daemon.socket", "clamav-daemon", "clamav-freshclam", check=False)
        # freshclam's first signature download can still be running: clamd refuses to start without
        # a signature database, so a restart failure here is expected and not fatal.
        ctx.remote.run("systemctl restart clamav-daemon.socket clamav-daemon clamav-freshclam || true", check=False, timeout=300)

    def verify(self, ctx: Context) -> VerifyResult:
        plan = ctx.plan.clamav
        if not ctx.remote.service_active("clamav-daemon.socket"):
            return VerifyResult(False, "clamav-daemon.socket is not active")
        if ctx.remote.run_ok(f"timeout 5 bash -c '</dev/tcp/{plan.host}/{plan.port}'"):
            return VerifyResult(True, f"clamd answering on {plan.host}:{plan.port}")
        signatures = ctx.remote.run_ok("ls /var/lib/clamav/main.c[vl]d /var/lib/clamav/daily.c[vl]d >/dev/null 2>&1")
        if not signatures:
            ctx.warn("[ClamAV] freshclam is still downloading its first signature database - uploads are marked clean unscanned until it finishes")
            return VerifyResult(True, "socket active, signatures still downloading (scans fail open until freshclam finishes)")
        return VerifyResult(False, f"nothing listening on {plan.host}:{plan.port} although signatures are present")

    def describe(self, plan: InstallPlan) -> str:
        limits = ", ".join(f"{key} {value}" for key, value in self.limits(plan).items())
        return f"install clamav-daemon + freshclam, TCP drop-in {plan.clamav.host}:{plan.clamav.port}, {limits}"


class CaddyStep(Step):
    """Caddy in front of the loopback REST port; TLS is issued by its own ACME client."""

    id = "caddy"
    title = "Reverse proxy (Caddy)"
    depends_on = ("packages",)

    def enabled(self, plan: InstallPlan) -> bool:
        return plan.proxy.enabled

    def check(self, ctx: Context) -> CheckResult:
        plan = ctx.plan.proxy
        dns = self._dns(ctx)
        if not ctx.remote.command_exists("caddy"):
            return CheckResult.needs_apply(f"Caddy not installed · {dns}")
        current = ctx.remote.read_text(CADDYFILE) or ""
        wanted = render_site_block(plan.api_domain, ctx.plan.app.rest_port)
        if replace_site_block(current, plan.api_domain, wanted) == current and ctx.remote.service_active("caddy"):
            return CheckResult.ok(f"{plan.api_domain} -> 127.0.0.1:{ctx.plan.app.rest_port} · {dns}")
        return CheckResult.needs_apply(f"site block for {plan.api_domain} missing or different · {dns}")

    def apply(self, ctx: Context) -> None:
        plan = ctx.plan.proxy
        if not ctx.remote.command_exists("caddy"):
            ctx.remote.run(
                "set -e; "
                'curl -1sLf "https://dl.cloudsmith.io/public/caddy/stable/gpg.key" | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg; '
                'curl -1sLf "https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt" > /etc/apt/sources.list.d/caddy-stable.list',
                check=True,
                timeout=300,
            )
            ctx.remote.apt_install(["caddy"])
        current = ctx.remote.read_text(CADDYFILE) or ""
        if "/usr/share/caddy" in current and plan.api_domain not in current:
            ctx.info("[Reverse proxy] replacing Caddy's packaged placeholder site")
            current = ""
        updated = ensure_global_block(replace_site_block(current, plan.api_domain, render_site_block(plan.api_domain, ctx.plan.app.rest_port)), plan.acme_email)
        # Validate before swapping: a broken Caddyfile only shows up at the next reload, by which
        # time TLS for the API (and the apex homepage) is already down.
        ctx.remote.put_text(f"{CADDYFILE}.new", updated, backup=False)
        result = ctx.remote.run(f"caddy validate --config {CADDYFILE}.new --adapter caddyfile", timeout=120)
        if not result.ok:
            ctx.remote.run(f"rm -f {CADDYFILE}.new", quiet=True)
            raise StepError("the rewritten Caddyfile does not validate: " + (result.err or result.out).strip().splitlines()[-1])
        ctx.remote.backup(CADDYFILE)
        ctx.remote.run(f"mv -f {CADDYFILE}.new {CADDYFILE}", check=True)
        ctx.remote.systemctl("enable", "caddy", check=False)
        ctx.remote.run("systemctl reload caddy || systemctl restart caddy", check=True)

    def verify(self, ctx: Context) -> VerifyResult:
        if not ctx.remote.service_active("caddy"):
            return VerifyResult(False, "caddy is not running")
        if not ctx.remote.run_ok(f"caddy validate --config {CADDYFILE} --adapter caddyfile"):
            return VerifyResult(False, "the live Caddyfile does not validate")
        answering = ctx.remote.run_ok("curl -s -o /dev/null -m 5 http://127.0.0.1/")
        return VerifyResult(True, f"caddy serving {ctx.plan.proxy.api_domain}" + ("" if answering else " (no answer on :80 yet)"))

    def describe(self, plan: InstallPlan) -> str:
        return f"install Caddy, serve {plan.proxy.api_domain} -> 127.0.0.1:{plan.app.rest_port} with TLS from Let's Encrypt"

    def _dns(self, ctx: Context) -> str:
        domain = ctx.plan.proxy.api_domain
        if not domain:
            return "no domain set"
        resolved = ctx.remote.run(f"getent hosts {shlex.quote(domain)} 2>/dev/null | awk '{{print $1}}' | head -1", quiet=True).out.strip()
        if not resolved:
            return f"DNS: {domain} does not resolve yet - Caddy cannot obtain a certificate"
        if ctx.discovered.public_ip and resolved != ctx.discovered.public_ip:
            return f"DNS: {domain} -> {resolved}, this server is {ctx.discovered.public_ip}"
        return f"DNS: {domain} -> {resolved} (matches)"

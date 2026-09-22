"""The two daemons beside the JVM: ``clamd`` (malware scanning) and Caddy (TLS termination)."""

from __future__ import annotations

import re
import shlex

from dataclasses import dataclass, field

from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.sizing import parse_clamd_size

#: Debian's clamav-daemon is socket-activated: clamd only opens sockets systemd hands it, so the
#: TCP listener has to be a drop-in on the SOCKET unit. Exactly one (IPv4) ListenStream - a second
#: (IPv6) one makes clamd 1.4 crash-loop with "Received more than two file descriptors".
CLAMD_SOCKET_DROPIN = "/etc/systemd/system/clamav-daemon.socket.d/tcp.conf"
CLAMD_CONF = "/etc/clamav/clamd.conf"

#: The clamd.conf directives this step manages.
LIMIT_KEYS: tuple[str, ...] = ("StreamMaxLength", "MaxFileSize", "MaxScanSize")

#: One ``ListenStream=`` line in the socket drop-in.
_LISTEN_RE = re.compile(r"^[ \t]*ListenStream[ \t]*=[ \t]*(.*?)[ \t]*$", re.MULTILINE)
CADDYFILE = "/etc/caddy/Caddyfile"


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


def render_clamd_dropin(address: str, port: int) -> str:
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


def rewrite_clamd_conf(config: str, limits: dict[str, str]) -> str:
    """``apply_clamd_limits`` under the name the callers use."""
    return apply_clamd_limits(config, limits)


def site_address(domain: str) -> str:
    """The Caddyfile site address for ``domain``: the name itself, or ``:80`` when there is none.

    Without a domain there is nothing to put on a certificate, so the site is plain HTTP on port
    80 for whatever address the request arrived on - which is also the address of Caddy's own
    packaged placeholder site, so installing over it replaces it instead of stacking on it.
    """
    return domain or ":80"


@dataclass
class CaddyBlock:
    """One top-level ``<header> {`` … ``}`` block of a Caddyfile.

    ``start``/``end`` are inclusive line indices into the file split on ``\\n``; ``header`` is the
    text before the opening brace (``""`` for the global options block, ``(name)`` for a snippet,
    one or more comma-separated site addresses otherwise).
    """

    header: str
    start: int
    end: int
    lines: list[str] = field(default_factory=list)

    @property
    def is_global(self) -> bool:
        """The global options block (a bare ``{`` line)."""
        return self.header == ""

    @property
    def addresses(self) -> list[str]:
        """Site addresses named in the header, normalised with :func:`normalise_address`."""
        return [normalise_address(token) for token in re.split(r"[,\s]+", self.header) if token]

    @property
    def text(self) -> str:
        """The block's lines joined back together (no trailing newline)."""
        return "\n".join(self.lines)


def normalise_address(address: str) -> str:
    """Lower-case a site address and drop a scheme and port, so ``https://API.example.com:443`` matches ``api.example.com``.

    A port-only address (``:80``, what a site without a domain is called) is itself the address
    and survives whole - dropping its "port" would leave nothing to match on.
    """
    value = address.strip().lower()
    if value.startswith(":"):
        return value
    for scheme in ("https://", "http://"):
        if value.startswith(scheme):
            value = value[len(scheme):]
    if value.startswith("["):
        closing = value.find("]")
        return value if closing < 0 else value[: closing + 1]
    return value.split(":", 1)[0]


def _code_only(line: str) -> str:
    """``line`` without its ``# comment`` and with quoted strings blanked - what brace counting looks at."""
    out: list[str] = []
    quote: str | None = None
    index = 0
    while index < len(line):
        char = line[index]
        if quote is not None:
            if char == "\\" and quote == '"':
                index += 2
                continue
            if char == quote:
                quote = None
            index += 1
            continue
        if char in ('"', "`"):
            quote = char
            index += 1
            continue
        if char == "#" and (index == 0 or line[index - 1].isspace()):
            break
        out.append(char)
        index += 1
    return "".join(out).strip()


def _opens_block(code: str) -> bool:
    # Caddyfile syntax: an opening brace must be the last token of its line (``{remote_host}``
    # placeholders never end a line with a lone brace).
    return code == "{" or code.endswith(" {") or code.endswith("\t{")


def _closes_block(code: str) -> bool:
    # … and a closing brace must be the first token of its line.
    return code == "}" or code.startswith("} ") or code.startswith("}\t")


def parse_blocks(text: str) -> list[CaddyBlock]:
    """Top-level blocks of a Caddyfile, in file order, located by brace depth.

    Nested blocks (``reverse_proxy … {``, named matchers ``@name {``) only move the depth; braces
    inside comments, quoted strings and ``{placeholder}`` tokens are ignored. An unclosed block at
    the end of the file is dropped (``caddy validate`` reports it later).
    """
    lines = text.split("\n")
    blocks: list[CaddyBlock] = []
    depth = 0
    start = -1
    header = ""
    for index, raw in enumerate(lines):
        code = _code_only(raw)
        if depth == 0:
            if _opens_block(code):
                start = index
                header = code[:-1].strip()
                depth = 1
            continue
        if _closes_block(code):
            depth -= 1
            if depth == 0:
                blocks.append(CaddyBlock(header, start, index, lines[start : index + 1]))
            continue
        if _opens_block(code):
            depth += 1
    return blocks


def has_global_block(text: str) -> bool:
    """Whether the Caddyfile already carries a global options block."""
    return any(block.is_global for block in parse_blocks(text))


def find_site_block(text: str, domain: str) -> CaddyBlock | None:
    """The top-level block whose header names ``domain`` (first match), or ``None``."""
    wanted = normalise_address(domain)
    for block in parse_blocks(text):
        if not block.is_global and wanted in block.addresses:
            return block
    return None


def _append_block(caddyfile: str, block: str) -> str:
    """Put ``block`` at the end, one blank line after whatever was there."""
    base = caddyfile.rstrip("\n")
    if not base.strip():
        return block
    return base + "\n\n" + block


def replace_site_block(caddyfile: str, address: str, block: str) -> str:
    """Replace ``address``'s site block with ``block`` (appended when there is none).

    Every other block - the global options, the apex homepage ``deploy-homepage.sh`` maintains,
    snippets - is copied through untouched. A header that lists several addresses keeps its block
    for the others: only ``address`` leaves that header, and the new block is appended.
    """
    block_found = find_site_block(caddyfile, address)
    if block_found is None:
        return _append_block(caddyfile, block)
    lines = caddyfile.split("\n")
    if len(block_found.addresses) > 1:
        lines[block_found.start] = _header_without(lines[block_found.start], address)
        return _append_block("\n".join(lines), block)
    lines[block_found.start : block_found.end + 1] = block.rstrip("\n").split("\n")
    result = "\n".join(lines)
    return result if result.endswith("\n") else result + "\n"


def remove_site_block(caddyfile: str, address: str) -> str:
    """Return ``caddyfile`` without ``address``'s site block; everything else stays."""
    block = find_site_block(caddyfile, address)
    if block is None:
        return caddyfile
    lines = caddyfile.split("\n")
    if len(block.addresses) > 1:  # shared header: the block serves other names too
        lines[block.start] = _header_without(lines[block.start], address)
    else:
        del lines[block.start : block.end + 1]
    text = "\n".join(lines).strip("\n")
    return text + "\n" if text else ""


def _header_without(header_line: str, address: str) -> str:
    """``a.com, b.com {`` minus one address."""
    wanted = normalise_address(address)
    code = _code_only(header_line)
    tokens = [token for token in re.split(r"[,\s]+", code[:-1].strip()) if token]
    return ", ".join(token for token in tokens if normalise_address(token) != wanted) + " {"


def site_addresses(caddyfile: str) -> list[str]:
    """Every site address the file serves - the global options block and snippets are not sites."""
    found: list[str] = []
    for block in parse_blocks(caddyfile):
        if block.is_global or block.header.startswith("("):
            continue
        found.extend(address for address in block.addresses if address not in found)
    return found


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
    client-controlled value in the header the backend's rate limiter reads. An empty ``domain``
    renders the plain-HTTP ``:80`` form - no name, no certificate, see ``site_address``.
    """
    return (
        f"{site_address(domain)} {{\n"
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
    removable = True

    def enabled(self, plan: InstallPlan) -> bool:
        return plan.clamav.enabled

    def limits(self, plan: InstallPlan) -> dict[str, str]:
        """clamd size limits - kept above ``content-scan-max-bytes`` so nothing in the gap fails open."""
        return wanted_limits(plan)

    def check(self, ctx: Context) -> CheckResult:
        plan = ctx.plan.clamav
        if not (ctx.remote.dpkg_installed("clamav-daemon") and ctx.remote.dpkg_installed("clamav-freshclam")):
            return CheckResult.needs_apply("not installed")
        address = listen_address(plan.host)
        dropin = ctx.remote.read_text(CLAMD_SOCKET_DROPIN)
        config = ctx.remote.read_text(CLAMD_CONF)
        listener_ok = drop_in_is_current(dropin, address, plan.port)
        stale = stale_limits(config, self.limits(ctx.plan))
        if listener_ok and not stale and ctx.remote.service_active("clamav-daemon.socket"):
            return CheckResult.ok(f"clamd listening on {address}:{plan.port}, limits raised")
        missing = [text for text, ok in (("TCP socket drop-in", listener_ok), (f"size limits ({', '.join(stale)})", not stale)) if not ok]
        return CheckResult.needs_apply(", ".join(missing) or "socket unit not active")

    def apply(self, ctx: Context) -> None:
        plan = ctx.plan.clamav
        if not ctx.remote.dpkg_installed("clamav-daemon"):
            ctx.remote.apt_install(["clamav-daemon", "clamav-freshclam"])
        ctx.remote.mkdirs("/etc/systemd/system/clamav-daemon.socket.d")
        ctx.remote.put_text(CLAMD_SOCKET_DROPIN, render_clamd_dropin(listen_address(plan.host), plan.port))
        config = ctx.remote.read_text(CLAMD_CONF) or ""
        updated = apply_clamd_limits(config, self.limits(ctx.plan))
        if updated != config:  # a limit written as 256m already means 256M - do not rewrite for that
            ctx.remote.put_text(CLAMD_CONF, updated, mode=0o644)
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

    def remove(self, ctx: Context) -> None:
        """Purge clamd, freshclam, the signature database and the socket drop-in."""
        for unit in ("clamav-daemon.socket", "clamav-daemon", "clamav-freshclam"):
            ctx.remote.systemctl("stop", unit, check=False)
            ctx.remote.systemctl("disable", unit, check=False)
        ctx.remote.delete("/etc/systemd/system/clamav-daemon.socket.d")
        ctx.remote.apt_purge(["clamav-daemon", "clamav-freshclam", "clamav-base", "clamav"])
        ctx.remote.delete("/var/lib/clamav", "/etc/clamav", "/var/log/clamav")
        ctx.remote.systemctl("daemon-reload", check=False)
        ctx.discovered.clamd_installed = False
        ctx.warn("[ClamAV] uploads are no longer scanned - switch malware scanning off in the plan as well, or the backend keeps trying to reach a scanner that is gone")

    def describe_removal(self, plan: InstallPlan) -> str:
        return (
            "stop and purge clamav-daemon, clamav-freshclam and clamav-base, delete the socket drop-in, "
            "/var/lib/clamav (the whole signature database, ~1 GB) and /etc/clamav · uploads are no longer scanned"
        )


class CaddyStep(Step):
    """Caddy in front of the loopback REST port; TLS is issued by its own ACME client."""

    id = "caddy"
    title = "Reverse proxy (Caddy)"
    depends_on = ("packages",)
    removable = True

    def enabled(self, plan: InstallPlan) -> bool:
        return plan.proxy.enabled

    def check(self, ctx: Context) -> CheckResult:
        plan = ctx.plan.proxy
        dns = self._dns(ctx)
        address = site_address(plan.api_domain)
        if not ctx.remote.command_exists("caddy"):
            return CheckResult.needs_apply(f"Caddy not installed · {dns}")
        current = ctx.remote.read_text(CADDYFILE) or ""
        wanted = render_site_block(plan.api_domain, ctx.plan.app.rest_port)
        if replace_site_block(current, address, wanted) == current and ctx.remote.service_active("caddy"):
            return CheckResult.ok(f"{address} -> 127.0.0.1:{ctx.plan.app.rest_port} · {dns}")
        return CheckResult.needs_apply(f"site block for {address} missing or different · {dns}")

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
        address = site_address(plan.api_domain)
        # The packaged placeholder is itself a ``:80`` site, so a domain-less install rewrites that
        # very block instead of discarding the file.
        if "/usr/share/caddy" in current and address not in current:
            ctx.info("[Reverse proxy] replacing Caddy's packaged placeholder site")
            current = ""
        updated = ensure_global_block(replace_site_block(current, address, render_site_block(plan.api_domain, ctx.plan.app.rest_port)), plan.acme_email)
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
        return VerifyResult(True, f"caddy serving {site_address(ctx.plan.proxy.api_domain)}" + ("" if answering else " (no answer on :80 yet)"))

    def describe(self, plan: InstallPlan) -> str:
        target = f"127.0.0.1:{plan.app.rest_port}"
        if not plan.proxy.api_domain:
            return f"install Caddy, serve :80 -> {target} as plain HTTP (no domain, so no certificate)"
        return f"install Caddy, serve {plan.proxy.api_domain} -> {target} with TLS from Let's Encrypt"

    def remove(self, ctx: Context) -> None:
        """Take this deployment's site block out; purge Caddy only if it served nothing else.

        The apex homepage block that ``deploy-homepage.sh`` maintains lives in the same file, so a
        Caddyfile with other sites in it is edited and reloaded, never deleted.
        """
        address = site_address(ctx.plan.proxy.api_domain)
        if not ctx.remote.command_exists("caddy"):
            ctx.info("[Reverse proxy] Caddy is not installed")
            return
        current = ctx.remote.read_text(CADDYFILE) or ""
        remaining = remove_site_block(current, address)
        others = site_addresses(remaining)
        if others:
            ctx.remote.backup(CADDYFILE)
            ctx.remote.put_text(CADDYFILE, remaining, backup=False)
            ctx.remote.run("systemctl reload caddy || systemctl restart caddy", check=False)
            ctx.info(f"[Reverse proxy] removed the {address} site; Caddy still serves {', '.join(others)}")
            return
        ctx.remote.systemctl("stop", "caddy", check=False)
        ctx.remote.systemctl("disable", "caddy", check=False)
        ctx.remote.apt_purge(["caddy"])
        ctx.remote.delete("/etc/caddy", "/var/lib/caddy", "/etc/apt/sources.list.d/caddy-stable.list", "/usr/share/keyrings/caddy-stable-archive-keyring.gpg")
        ctx.discovered.caddy_installed = False
        ctx.warn("[Reverse proxy] the issued TLS certificates are deleted with /var/lib/caddy; a re-install asks Let's Encrypt for new ones (rate limits apply)")

    def describe_removal(self, plan: InstallPlan) -> str:
        address = site_address(plan.proxy.api_domain)
        return (
            f"remove the {address} site block from {CADDYFILE} and reload · if no other site is left (the apex homepage block counts), "
            "stop and purge Caddy and delete /etc/caddy, /var/lib/caddy (the issued certificates) and the Cloudsmith apt source · "
            "the API is then reachable only on its own port, without TLS"
        )

    def _dns(self, ctx: Context) -> str:
        domain = ctx.plan.proxy.api_domain
        if not domain:
            return "no domain: plain HTTP on :80 for this server's address"
        resolved = ctx.remote.run(f"getent hosts {shlex.quote(domain)} 2>/dev/null | awk '{{print $1}}' | head -1", quiet=True).out.strip()
        if not resolved:
            return f"DNS: {domain} does not resolve yet - Caddy cannot obtain a certificate"
        if ctx.discovered.public_ip and resolved != ctx.discovered.public_ip:
            return f"DNS: {domain} -> {resolved}, this server is {ctx.discovered.public_ip}"
        return f"DNS: {domain} -> {resolved} (matches)"

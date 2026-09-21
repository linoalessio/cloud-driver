"""Reverse-proxy step: Caddy from its cloudsmith repository, one site block for the API domain.

Caddy terminates TLS on 80/443 (ACME certificate issued by itself once DNS points here) and
forwards to the loopback REST port; the JVM never sees TLS. The step mirrors steps 2 and 9 of
``shell/provision-root-server.sh`` for the installation and the site block, and the
backup → rewrite → ``caddy validate`` → swap → reload sequence of ``shell/deploy-homepage.sh``
for editing ``/etc/caddy/Caddyfile``.

The Caddyfile editing is pure Python (:func:`parse_blocks`, :func:`replace_site_block`,
:func:`ensure_global_block`): top-level blocks are located by brace depth, from the
``<address> {`` line to its matching ``}``, so a Caddyfile that also carries a global options
block and other sites (the reference box serves the homepage from the apex) is edited without
touching any block but the API domain's. ``caddy validate`` runs against ``Caddyfile.new`` before
the swap, so a rejected file never replaces a working one.

The site block overrides ``X-Forwarded-For`` with the real peer address instead of appending to
whatever the client sent - the backend's rate limiter reads that header once
``trusted-proxy-addresses`` names the proxy, so it must not carry a client-controlled value.
"""

from __future__ import annotations

import re
import shlex
from dataclasses import dataclass, field

from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError

#: Caddy's configuration file (Debian package layout).
CADDYFILE = "/etc/caddy/Caddyfile"
#: Staging file that is validated before it replaces :data:`CADDYFILE`.
CADDYFILE_NEW = f"{CADDYFILE}.new"
#: Where the cloudsmith signing key lands (same path as the shell script).
KEYRING = "/usr/share/keyrings/caddy-stable-archive-keyring.gpg"
#: The apt source list for the stable repository.
SOURCES_LIST = "/etc/apt/sources.list.d/caddy-stable.list"
GPG_KEY_URL = "https://dl.cloudsmith.io/public/caddy/stable/gpg.key"
SOURCES_URL = "https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt"
#: The ``header_up`` line every managed site block must carry (see the module docstring).
XFF_OVERRIDE = "header_up X-Forwarded-For {remote_host}"

#: Repository setup, verbatim from ``provision-root-server.sh`` step 2 apart from the ``[ -s … ] ||``
#: guard: ``gpg --dearmor -o`` prompts before overwriting an existing keyring, which would hang a
#: re-run after a half-finished install. ``apt-get update`` + ``install caddy`` follow through
#: ``Remote.apt_install``.
REPO_SETUP = (
    "set -e -o pipefail\n"
    "export DEBIAN_FRONTEND=noninteractive\n"
    f'[ -s {KEYRING} ] || curl -1sLf "{GPG_KEY_URL}" | gpg --dearmor -o {KEYRING}\n'
    f'curl -1sLf "{SOURCES_URL}" > {SOURCES_LIST}\n'
)

_IPV4_RE = re.compile(r"\b(\d{1,3}(?:\.\d{1,3}){3})\b")


# --- Caddyfile parsing (pure) ----------------------------------------------------------------------


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
    """Lower-case a site address and drop a scheme and port, so ``https://API.example.com:443`` matches ``api.example.com``."""
    value = address.strip().lower()
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


def render_site_block(domain: str, upstream: str) -> str:
    """The managed site block, verbatim from ``provision-root-server.sh`` step 9."""
    return (
        f"{domain} {{\n"
        f"    reverse_proxy {upstream} {{\n"
        "        # Overwrite rather than append: reverse_proxy's default adds the peer address to\n"
        "        # whatever the client sent, which leaves a client-controlled value in the header the\n"
        "        # backend's rate limiter reads. Setting it to the real peer makes the header\n"
        "        # trustworthy no matter what the client sends.\n"
        f"        {XFF_OVERRIDE}\n"
        "    }\n"
        "}\n"
    )


def render_global_block(acme_email: str) -> str:
    """The global options block that gives Caddy's ACME client a contact address."""
    return f"{{\n\temail {acme_email}\n}}\n"


def ensure_global_block(text: str, acme_email: str) -> str:
    """Prepend ``{ email … }`` when the Caddyfile has no global options block; an existing one is left alone.

    Caddy requires the global block to be the first block, so it goes to the very top.
    """
    if not acme_email or has_global_block(text):
        return text
    body = text.lstrip("\n")
    if not body.strip():
        return render_global_block(acme_email)
    return render_global_block(acme_email) + "\n" + body


def _append_block(text: str, block: str) -> str:
    base = text.rstrip("\n")
    if not base.strip():
        return block
    return base + "\n\n" + block


def replace_site_block(text: str, domain: str, new_block: str) -> str:
    """Replace the ``<domain> {`` … ``}`` block with ``new_block`` (appended when absent).

    Every other block - the global options, other sites, snippets - is copied through untouched.
    A header that lists several addresses (``a.com, b.com {``) keeps its block for the other
    addresses: only ``domain`` is removed from that header and the new block is appended.
    """
    block = find_site_block(text, domain)
    if block is None:
        return _append_block(text, new_block)
    lines = text.split("\n")
    if len(block.addresses) > 1:
        wanted = normalise_address(domain)
        code = _code_only(lines[block.start])
        tokens = [token for token in re.split(r"[,\s]+", code[:-1].strip()) if token]
        remaining = [token for token in tokens if normalise_address(token) != wanted]
        lines[block.start] = ", ".join(remaining) + " {"
        return _append_block("\n".join(lines), new_block)
    lines[block.start : block.end + 1] = new_block.rstrip("\n").split("\n")
    result = "\n".join(lines)
    return result if result.endswith("\n") else result + "\n"


def block_upstreams(block_text: str) -> list[str]:
    """Every argument of the ``reverse_proxy`` directives in a block (matchers included - callers test membership)."""
    upstreams: list[str] = []
    for raw in block_text.split("\n"):
        tokens = _code_only(raw).split()
        if tokens and tokens[0] == "reverse_proxy":
            upstreams.extend(token for token in tokens[1:] if token != "{")
    return upstreams


def block_overrides_forwarded_for(block_text: str) -> bool:
    """Whether the block carries the ``X-Forwarded-For`` override line."""
    return any(_code_only(raw).split() == XFF_OVERRIDE.split() for raw in block_text.split("\n"))


def site_block_satisfies(block_text: str, upstream: str) -> bool:
    """A block is good enough to keep when it proxies to ``upstream`` and overrides ``X-Forwarded-For``."""
    return upstream in block_upstreams(block_text) and block_overrides_forwarded_for(block_text)


def upstream_address(bind_host: str, port: int) -> str:
    """Where Caddy forwards to: ``127.0.0.1:<port>`` (``[::1]:<port>`` when the JVM binds the IPv6 loopback)."""
    if (bind_host or "").strip().lower() == "::1":
        return f"[::1]:{port}"
    return f"127.0.0.1:{port}"


def ipv4_addresses(text: str) -> list[str]:
    """Distinct IPv4 addresses in resolver output, in order of appearance."""
    seen: list[str] = []
    for match in _IPV4_RE.finditer(text or ""):
        if match.group(1) not in seen:
            seen.append(match.group(1))
    return seen


# --- the step ---------------------------------------------------------------------------------------


@dataclass
class _State:
    """What ``_inspect`` found; ``check`` and ``apply`` share it so 'done' means one thing."""

    installed: bool = False
    version: str = ""
    caddyfile: str | None = None
    block: CaddyBlock | None = None
    block_ok: bool = False
    global_missing: bool = False
    unit_enabled: bool = False
    active: bool = False


class CaddyStep(Step):
    """Install Caddy, write the API domain's reverse-proxy block (validated before the swap), enable + reload."""

    id = "caddy"
    title = "Reverse proxy (Caddy)"
    #: The firewall step may be switched off, so it is not a dependency: ufw's 80/443 rules are
    #: its business when it runs, and this step only needs the base packages (curl, gnupg).
    depends_on = ("packages",)
    mandatory = False

    def enabled(self, plan: InstallPlan) -> bool:
        """Only when the plan puts Caddy in front of the API."""
        return plan.proxy.enabled

    # --- helpers ------------------------------------------------------------------------------------

    @staticmethod
    def _upstream(plan: InstallPlan) -> str:
        return upstream_address(plan.app.rest_bind_host, plan.app.rest_port)

    def _inspect(self, ctx: Context) -> _State:
        """Read-only look at the package, the Caddyfile and the unit."""
        remote = ctx.remote
        plan = ctx.plan
        state = _State()
        state.installed = remote.dpkg_installed("caddy") or remote.command_exists("caddy")
        if state.installed:
            version = remote.run("caddy version", quiet=True, timeout=30)
            state.version = version.text.split()[0] if version.ok and version.text else ""
        state.caddyfile = remote.read_text(CADDYFILE)
        text = state.caddyfile or ""
        state.block = find_site_block(text, plan.proxy.api_domain)
        state.block_ok = state.block is not None and site_block_satisfies(state.block.text, self._upstream(plan))
        state.global_missing = bool(plan.proxy.acme_email) and not has_global_block(text)
        state.unit_enabled = remote.run_ok("systemctl is-enabled --quiet caddy")
        state.active = remote.service_active("caddy")
        return state

    def _dns_note(self, ctx: Context) -> str:
        """Resolve the API domain from the server and compare with the discovered public IP (never a failure)."""
        domain = ctx.plan.proxy.api_domain
        quoted = shlex.quote(domain)
        result = ctx.remote.run(
            f"if command -v dig >/dev/null 2>&1; then dig +short A {quoted}; else getent ahostsv4 {quoted}; fi",
            quiet=True,
            timeout=20,
        )
        addresses = ipv4_addresses(result.out) if result.ok else []
        public_ip = ctx.discovered.public_ip
        if not addresses:
            note = f"WARN DNS: {domain} does not resolve yet - Caddy cannot obtain its certificate until an A record points at {public_ip or 'this server'}"
            ctx.warn(note)
            return note
        if public_ip and public_ip in addresses:
            return f"DNS → {public_ip} (matches)"
        if public_ip:
            note = f"WARN DNS → {', '.join(addresses)} but this server is {public_ip} - fix the A record or Caddy's certificate request fails"
            ctx.warn(note)
            return note
        return f"DNS → {', '.join(addresses)} (server public IP unknown)"

    def _desired_caddyfile(self, plan: InstallPlan, current: str, state: _State) -> str:
        """The Caddyfile ``apply`` wants: current text + global block (if missing) + our site block."""
        text = ensure_global_block(current, plan.proxy.acme_email) if plan.proxy.acme_email else current
        if not state.block_ok:
            text = replace_site_block(text, plan.proxy.api_domain, render_site_block(plan.proxy.api_domain, self._upstream(plan)))
        return text

    def _swap_caddyfile(self, ctx: Context, current: str | None, desired: str) -> None:
        """Write ``Caddyfile.new``, validate it, back up the live file, move the new one into place."""
        remote = ctx.remote
        remote.put_text(CADDYFILE_NEW, desired, mode=0o644, backup=False)
        validation = remote.run(f"caddy validate --config {CADDYFILE_NEW} --adapter caddyfile", timeout=60)
        if not validation.ok:
            remote.run(f"rm -f {CADDYFILE_NEW}", quiet=True)
            message = "\n".join((validation.err.strip() or validation.out.strip()).splitlines()[-6:])
            raise StepError(f"caddy validate rejected the new Caddyfile - {CADDYFILE} is unchanged:\n{message}".rstrip())
        if current is not None:
            remote.backup(CADDYFILE)
        remote.run(f"mv -f {CADDYFILE_NEW} {CADDYFILE}", check=True, quiet=True)

    # --- phases -------------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """Caddy installed, the site block proxies to the REST port, the unit is enabled and active → ok."""
        plan = ctx.plan
        domain = plan.proxy.api_domain
        upstream = self._upstream(plan)
        state = self._inspect(ctx)
        ctx.discovered.caddy_installed = state.installed

        changes: list[str] = []
        if not state.installed:
            changes.append("install caddy from the cloudsmith repository")
        if state.global_missing:
            changes.append(f"add global options block (email {plan.proxy.acme_email})")
        if state.block is None:
            changes.append(f"add site block {domain} → {upstream} to {CADDYFILE}")
        elif not state.block_ok:
            changes.append(f"rewrite site block {domain} → {upstream} (current block lacks that upstream or the X-Forwarded-For override; backup kept)")
        if not state.unit_enabled:
            changes.append("enable caddy")
        if not state.active:
            changes.append("start caddy")
        dns = self._dns_note(ctx)
        if changes:
            return CheckResult.needs_apply("; ".join(changes) + " · " + dns)
        version = f"caddy {state.version}" if state.version else "caddy"
        return CheckResult.ok(f"{version} active · {domain} → {upstream} in {CADDYFILE} · {dns}")

    def apply(self, ctx: Context) -> None:
        """Install when missing, rewrite the Caddyfile only when it differs (validated first), enable, reload."""
        remote = ctx.remote
        plan = ctx.plan
        domain = plan.proxy.api_domain
        upstream = self._upstream(plan)
        state = self._inspect(ctx)
        try:
            if not state.installed:
                ctx.progress(0.1, "adding the Caddy apt repository")
                ctx.info("installing caddy from the cloudsmith stable repository")
                remote.run(REPO_SETUP, check=True, timeout=180)
                ctx.check_cancelled()
                ctx.progress(0.3, "apt-get install caddy")
                remote.apt_install(["caddy"])
                version = remote.run("caddy version", quiet=True, timeout=30)
                ctx.info(f"caddy installed ({version.text.split()[0] if version.ok and version.text else 'version unknown'})")
            ctx.check_cancelled()

            current = remote.read_text(CADDYFILE)
            desired = self._desired_caddyfile(plan, current or "", state)
            changed = False
            if current is None or desired != current:
                ctx.progress(0.6, "writing the Caddyfile")
                if state.block is not None and not state.block_ok:
                    ctx.warn(f"replacing the existing {domain} block in {CADDYFILE} (it did not proxy to {upstream} with the X-Forwarded-For override); the previous file is backed up")
                self._swap_caddyfile(ctx, current, desired)
                what = []
                if state.global_missing:
                    what.append(f"global options (email {plan.proxy.acme_email})")
                if not state.block_ok:
                    what.append(f"site block {domain} → {upstream}")
                ctx.info(f"wrote {CADDYFILE}: " + ", ".join(what or ["unchanged blocks"]) + " (validated with caddy validate)")
                changed = True
            else:
                ctx.debug(f"{CADDYFILE} already correct")
            ctx.check_cancelled()

            ctx.progress(0.85, "enabling caddy")
            remote.systemctl("enable", "--quiet", "caddy")
            if changed or not state.active:
                ctx.progress(0.95, "reloading caddy")
                remote.run("systemctl reload caddy || systemctl restart caddy", check=True, timeout=120)
                ctx.info("caddy reloaded")
            else:
                ctx.debug("caddy already active with the current Caddyfile - no reload")
        except RemoteError as exc:
            raise StepError(f"Caddy setup failed: {exc}") from exc
        ctx.discovered.caddy_installed = True

    def verify(self, ctx: Context) -> VerifyResult:
        """The unit is active, the live Caddyfile validates, and Caddy answers HTTP on the loopback."""
        remote = ctx.remote
        plan = ctx.plan
        if not remote.service_active("caddy"):
            status = remote.run("systemctl --no-pager --lines=10 status caddy", quiet=True, timeout=30)
            tail = "\n".join((status.out.strip() or status.err.strip()).splitlines()[-12:])
            return VerifyResult(False, f"caddy is not active\n{tail}".rstrip())
        validation = remote.run(f"caddy validate --config {CADDYFILE} --adapter caddyfile", quiet=True, timeout=60)
        if not validation.ok:
            message = "\n".join((validation.err.strip() or validation.out.strip()).splitlines()[-6:])
            return VerifyResult(False, f"{CADDYFILE} does not validate:\n{message}".rstrip())
        probe = remote.run("curl -s -o /dev/null -w '%{http_code}' -m 5 http://127.0.0.1/", quiet=True, timeout=15)
        code = probe.text
        if not re.fullmatch(r"[1-5]\d\d", code):
            return VerifyResult(False, f"Caddy does not answer on http://127.0.0.1/ (curl exit {probe.code}, output {code!r})")
        version = remote.run("caddy version", quiet=True, timeout=30)
        label = f"caddy {version.text.split()[0]}" if version.ok and version.text else "caddy"
        return VerifyResult(
            True,
            f"{label} active · Caddyfile valid · http://127.0.0.1/ → HTTP {code} · {plan.proxy.api_domain} → {self._upstream(plan)} "
            f"(Caddy issues the TLS certificate itself once DNS points here) · {self._dns_note(ctx)}",
        )

    def describe(self, plan: InstallPlan) -> str:
        """One line for the summary page."""
        parts = ["install caddy from the cloudsmith repository if missing"]
        if plan.proxy.acme_email:
            parts.append(f"add a global options block with email {plan.proxy.acme_email} unless one exists")
        parts.append(
            f"write the {plan.proxy.api_domain} → {self._upstream(plan)} site block into {CADDYFILE} "
            "(other blocks untouched, caddy validate before the swap, previous file backed up)"
        )
        parts.append("enable + reload caddy")
        return ", ".join(parts)

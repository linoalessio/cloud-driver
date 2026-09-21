"""Tests for the Caddy step: the pure Caddyfile block editor and the check → apply → verify phases."""

from __future__ import annotations

import pytest

from cloud_driver_installer.engine import Context, StepError, StepStatus
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.steps.caddy import (
    CADDYFILE,
    CADDYFILE_NEW,
    KEYRING,
    SOURCES_LIST,
    XFF_OVERRIDE,
    CaddyStep,
    block_upstreams,
    ensure_global_block,
    find_site_block,
    has_global_block,
    ipv4_addresses,
    normalise_address,
    parse_blocks,
    render_global_block,
    render_site_block,
    replace_site_block,
    site_block_satisfies,
    upstream_address,
)

from .fake_remote import FakeRemote

GLOBAL_BLOCK = "{\n\temail ops@example.com\n}\n"

APEX_BLOCK = (
    "cloud-driver.de {\n"
    "    root * /var/www/cloud-driver-homepage\n"
    '    header Cache-Control "no-cache"\n'
    "    file_server\n"
    "}\n"
)

AUTH_BLOCK = (
    "auth.example.com {\n"
    "    @api {\n"
    "        path /api/*\n"
    "    }\n"
    "    reverse_proxy @api 127.0.0.1:9000 {\n"
    "        header_up X-Real-IP {remote_host}\n"
    "    }\n"
    "    respond \"# not a comment }\" 200\n"
    "}\n"
)

OLD_API_BLOCK = (
    "api.example.com {\n"
    "    reverse_proxy 127.0.0.1:8080\n"
    "}\n"
)

MANAGED_API_BLOCK = render_site_block("api.example.com", "127.0.0.1:8080")

REFERENCE_CADDYFILE = "# The Caddyfile is an easy way to configure your Caddy web server.\n\n" + GLOBAL_BLOCK + "\n" + APEX_BLOCK + "\n" + OLD_API_BLOCK + "\n" + AUTH_BLOCK


def _installed(remote: FakeRemote) -> None:
    remote.ok("dpkg-query -W -f='${Status}' caddy", "install ok installed")
    remote.ok("caddy version", "v2.8.4 h1:q3pe0wm7flvvDgZeGRtLo8SZOKvaJ5dzLbdDcV5lOgY=\n")


def _provisioned(remote: FakeRemote) -> None:
    """Caddy installed, the managed block present, unit enabled + active, DNS pointing here."""
    _installed(remote)
    remote.files[CADDYFILE] = GLOBAL_BLOCK + "\n" + APEX_BLOCK + "\n" + MANAGED_API_BLOCK
    remote.ok("systemctl is-enabled --quiet caddy")
    remote.ok("systemctl is-active --quiet caddy")
    remote.ok("dig +short A", "203.0.113.10\n")


def _script_swap(remote: FakeRemote) -> None:
    """Let the fake honour validate + mv so the Caddyfile flow can be followed end to end."""
    remote.ok("caddy validate")

    def move(cmd: str, inp: str | None) -> int:
        remote.files[CADDYFILE] = remote.files.pop(CADDYFILE_NEW)
        return 0

    remote.on(f"mv -f {CADDYFILE_NEW} {CADDYFILE}", move)
    remote.ok("systemctl enable")
    remote.ok("systemctl reload caddy")


# --- pure Caddyfile editing -----------------------------------------------------------------------------


def test_normalise_address() -> None:
    assert normalise_address("API.Example.com") == "api.example.com"
    assert normalise_address("https://api.example.com:443") == "api.example.com"
    assert normalise_address("http://api.example.com") == "api.example.com"
    assert normalise_address("[::1]:8080") == "[::1]"


def test_parse_blocks_finds_global_and_every_site_block() -> None:
    blocks = parse_blocks(REFERENCE_CADDYFILE)
    assert [block.header for block in blocks] == ["", "cloud-driver.de", "api.example.com", "auth.example.com"]
    assert blocks[0].is_global
    assert blocks[1].addresses == ["cloud-driver.de"]
    assert blocks[3].text == AUTH_BLOCK.rstrip("\n")
    assert has_global_block(REFERENCE_CADDYFILE)
    assert not has_global_block(APEX_BLOCK + "\n" + OLD_API_BLOCK)


def test_parse_blocks_ignores_placeholders_comments_and_quotes() -> None:
    text = (
        "# a { comment }\n"
        "a.example.com {\n"
        "    reverse_proxy 127.0.0.1:1 {\n"
        "        header_up X-Forwarded-For {remote_host} # trailing { comment\n"
        "    }\n"
        '    respond "text with } brace" 200\n'
        "    respond `raw } brace` 200\n"
        "}\n"
        "b.example.com {\n"
        "    respond 200\n"
        "}\n"
    )
    blocks = parse_blocks(text)
    assert [block.header for block in blocks] == ["a.example.com", "b.example.com"]
    assert blocks[0].start == 1 and blocks[0].end == 7
    assert blocks[1].start == 8 and blocks[1].end == 10


def test_find_site_block_matches_normalised_addresses() -> None:
    assert find_site_block(REFERENCE_CADDYFILE, "api.example.com") is not None
    assert find_site_block(REFERENCE_CADDYFILE, "API.EXAMPLE.COM") is not None
    assert find_site_block(REFERENCE_CADDYFILE, "other.example.com") is None
    assert find_site_block("https://api.example.com:443 {\n    respond 200\n}\n", "api.example.com") is not None


def test_replace_site_block_touches_only_the_matching_block() -> None:
    result = replace_site_block(REFERENCE_CADDYFILE, "api.example.com", MANAGED_API_BLOCK)
    expected = "# The Caddyfile is an easy way to configure your Caddy web server.\n\n" + GLOBAL_BLOCK + "\n" + APEX_BLOCK + "\n" + MANAGED_API_BLOCK + "\n" + AUTH_BLOCK
    assert result == expected
    # applying again changes nothing
    assert replace_site_block(result, "api.example.com", MANAGED_API_BLOCK) == result


def test_replace_site_block_appends_when_absent() -> None:
    result = replace_site_block(GLOBAL_BLOCK + "\n" + APEX_BLOCK, "api.example.com", MANAGED_API_BLOCK)
    assert result == GLOBAL_BLOCK + "\n" + APEX_BLOCK + "\n" + MANAGED_API_BLOCK
    assert replace_site_block("", "api.example.com", MANAGED_API_BLOCK) == MANAGED_API_BLOCK
    assert replace_site_block("\n\n", "api.example.com", MANAGED_API_BLOCK) == MANAGED_API_BLOCK


def test_replace_site_block_keeps_a_shared_header_for_the_other_site() -> None:
    shared = "api.example.com, www.example.com {\n    respond 200\n}\n"
    result = replace_site_block(APEX_BLOCK + "\n" + shared, "api.example.com", MANAGED_API_BLOCK)
    assert result == APEX_BLOCK + "\n" + "www.example.com {\n    respond 200\n}\n" + "\n" + MANAGED_API_BLOCK
    blocks = parse_blocks(result)
    assert [block.header for block in blocks] == ["cloud-driver.de", "www.example.com", "api.example.com"]


def test_ensure_global_block_only_adds_when_missing() -> None:
    assert ensure_global_block(APEX_BLOCK, "ops@example.com") == render_global_block("ops@example.com") + "\n" + APEX_BLOCK
    assert ensure_global_block("", "ops@example.com") == "{\n\temail ops@example.com\n}\n"
    assert ensure_global_block(REFERENCE_CADDYFILE, "someone-else@example.com") == REFERENCE_CADDYFILE
    existing = "{\n\tadmin off\n}\n" + APEX_BLOCK
    assert ensure_global_block(existing, "ops@example.com") == existing
    assert ensure_global_block(APEX_BLOCK, "") == APEX_BLOCK


def test_site_block_requirements() -> None:
    assert block_upstreams(OLD_API_BLOCK) == ["127.0.0.1:8080"]
    assert block_upstreams(AUTH_BLOCK) == ["@api", "127.0.0.1:9000"]
    assert site_block_satisfies(MANAGED_API_BLOCK, "127.0.0.1:8080")
    assert not site_block_satisfies(MANAGED_API_BLOCK, "127.0.0.1:8081")
    assert not site_block_satisfies(OLD_API_BLOCK, "127.0.0.1:8080")  # no X-Forwarded-For override
    customised = "api.example.com {\n    encode gzip\n    reverse_proxy 127.0.0.1:8080 {\n        " + XFF_OVERRIDE + "\n    }\n}\n"
    assert site_block_satisfies(customised, "127.0.0.1:8080")


def test_render_site_block_mirrors_the_shell_script() -> None:
    assert MANAGED_API_BLOCK.startswith("api.example.com {\n    reverse_proxy 127.0.0.1:8080 {\n")
    assert "        header_up X-Forwarded-For {remote_host}\n    }\n}\n" in MANAGED_API_BLOCK
    assert upstream_address("127.0.0.1", 8080) == "127.0.0.1:8080"
    assert upstream_address("localhost", 8080) == "127.0.0.1:8080"
    assert upstream_address("::1", 8080) == "[::1]:8080"


def test_ipv4_addresses_from_dig_and_getent_output() -> None:
    assert ipv4_addresses("api.example.com.cdn.example.net.\n203.0.113.10\n203.0.113.10\n") == ["203.0.113.10"]
    assert ipv4_addresses("203.0.113.10 STREAM api.example.com\n203.0.113.10 DGRAM \n203.0.113.11 RAW \n") == ["203.0.113.10", "203.0.113.11"]
    assert ipv4_addresses("") == []


# --- check ----------------------------------------------------------------------------------------------


def test_enabled_follows_the_plan(plan: InstallPlan) -> None:
    step = CaddyStep()
    assert step.enabled(plan)
    plan.proxy.enabled = False
    assert not step.enabled(plan)
    assert step.depends_on == ("packages",)


def test_check_needs_apply_on_bare_box(ctx: Context, remote: FakeRemote) -> None:
    result = CaddyStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "install caddy from the cloudsmith repository" in result.detail
    assert f"add site block api.example.com → 127.0.0.1:8080 to {CADDYFILE}" in result.detail
    assert "enable caddy" in result.detail and "start caddy" in result.detail
    assert "WARN DNS: api.example.com does not resolve yet" in result.detail
    assert ctx.discovered.caddy_installed is False
    assert remote.files == {}
    assert remote.apt_installed == []
    assert not remote.ran("caddy validate")


def test_check_ok_on_provisioned_box(ctx: Context, remote: FakeRemote) -> None:
    _provisioned(remote)
    result = CaddyStep().check(ctx)
    assert result.status is StepStatus.OK
    assert result.detail.startswith("caddy v2.8.4 active · api.example.com → 127.0.0.1:8080")
    assert "DNS → 203.0.113.10 (matches)" in result.detail
    assert ctx.discovered.caddy_installed is True
    assert remote.backups == []
    assert CADDYFILE_NEW not in remote.files


def test_check_warns_on_dns_mismatch_without_failing(ctx: Context, remote: FakeRemote) -> None:
    _provisioned(remote)
    remote.rules = [rule for rule in remote.rules if rule.needle != "dig +short A"]
    remote.ok("dig +short A", "198.51.100.7\n")
    result = CaddyStep().check(ctx)
    assert result.status is StepStatus.OK
    assert "WARN DNS → 198.51.100.7 but this server is 203.0.113.10" in result.detail
    assert any(level == "WARN" and "198.51.100.7" in message for level, message in ctx.captured_log)


def test_check_needs_apply_when_block_lacks_the_forwarded_for_override(ctx: Context, remote: FakeRemote) -> None:
    _provisioned(remote)
    remote.files[CADDYFILE] = REFERENCE_CADDYFILE
    result = CaddyStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert result.detail.startswith("rewrite site block api.example.com → 127.0.0.1:8080")


def test_check_needs_apply_for_missing_global_block_when_acme_email_set(ctx: Context, remote: FakeRemote) -> None:
    _provisioned(remote)
    ctx.plan.proxy.acme_email = "ops@example.com"
    assert CaddyStep().check(ctx).status is StepStatus.OK
    remote.files[CADDYFILE] = APEX_BLOCK + "\n" + MANAGED_API_BLOCK
    result = CaddyStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert result.detail.startswith("add global options block (email ops@example.com)")


def test_check_needs_apply_when_caddy_inactive(ctx: Context, remote: FakeRemote) -> None:
    _installed(remote)
    remote.files[CADDYFILE] = MANAGED_API_BLOCK
    remote.ok("systemctl is-enabled --quiet caddy")
    remote.ok("dig +short A", "203.0.113.10\n")
    result = CaddyStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert result.detail.startswith("start caddy · DNS → 203.0.113.10 (matches)")


# --- apply ----------------------------------------------------------------------------------------------


def test_apply_on_bare_box_installs_and_writes_the_caddyfile(ctx: Context, remote: FakeRemote) -> None:
    _script_swap(remote)
    remote.ok("caddy version", "v2.8.4 h1:abc\n")
    ctx.plan.proxy.acme_email = "ops@example.com"
    CaddyStep().apply(ctx)

    setup = [cmd for cmd in remote.commands if "gpg --dearmor" in cmd]
    assert len(setup) == 1
    assert f'curl -1sLf "https://dl.cloudsmith.io/public/caddy/stable/gpg.key" | gpg --dearmor -o {KEYRING}' in setup[0]
    assert f'curl -1sLf "https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt" > {SOURCES_LIST}' in setup[0]
    assert remote.apt_installed == ["caddy"]
    assert remote.commands.index(setup[0]) < remote.commands.index("apt-get install caddy")

    assert remote.files[CADDYFILE] == render_global_block("ops@example.com") + "\n" + MANAGED_API_BLOCK
    assert remote.modes[CADDYFILE_NEW] == 0o644
    assert CADDYFILE_NEW not in remote.files
    assert remote.ran(f"caddy validate --config {CADDYFILE_NEW} --adapter caddyfile")
    assert remote.commands.index(f"caddy validate --config {CADDYFILE_NEW} --adapter caddyfile") < remote.commands.index(f"mv -f {CADDYFILE_NEW} {CADDYFILE}")
    assert remote.backups == []  # nothing to back up on a bare box
    assert ("enable", "--quiet", "caddy") in remote.systemctl_calls
    assert remote.ran("systemctl reload caddy || systemctl restart caddy")
    assert ctx.discovered.caddy_installed is True


def test_apply_replaces_only_the_api_block_and_backs_up(ctx: Context, remote: FakeRemote) -> None:
    _installed(remote)
    remote.files[CADDYFILE] = REFERENCE_CADDYFILE
    remote.ok("systemctl is-active --quiet caddy")
    _script_swap(remote)
    CaddyStep().apply(ctx)

    assert remote.apt_installed == []
    assert not remote.ran("gpg --dearmor")
    expected = "# The Caddyfile is an easy way to configure your Caddy web server.\n\n" + GLOBAL_BLOCK + "\n" + APEX_BLOCK + "\n" + MANAGED_API_BLOCK + "\n" + AUTH_BLOCK
    assert remote.files[CADDYFILE] == expected
    assert remote.backups == [f"{CADDYFILE}.bak-TEST"]
    assert remote.files[f"{CADDYFILE}.bak-TEST"] == REFERENCE_CADDYFILE
    assert remote.ran("systemctl reload caddy")
    assert any(level == "WARN" and "replacing the existing api.example.com block" in message for level, message in ctx.captured_log)


def test_apply_keeps_an_operator_customised_block_that_already_satisfies(ctx: Context, remote: FakeRemote) -> None:
    _installed(remote)
    customised = APEX_BLOCK + "\napi.example.com {\n    encode gzip\n    reverse_proxy 127.0.0.1:8080 {\n        " + XFF_OVERRIDE + "\n    }\n}\n"
    remote.files[CADDYFILE] = customised
    remote.ok("systemctl is-active --quiet caddy")
    _script_swap(remote)
    CaddyStep().apply(ctx)
    assert remote.files[CADDYFILE] == customised
    assert remote.backups == []
    assert not remote.ran("caddy validate")
    assert not remote.ran("systemctl reload caddy")


def test_apply_is_idempotent_on_provisioned_box(ctx: Context, remote: FakeRemote) -> None:
    _provisioned(remote)
    _script_swap(remote)
    before = dict(remote.files)
    CaddyStep().apply(ctx)
    assert remote.files == before
    assert remote.backups == []
    assert remote.apt_installed == []
    assert not remote.ran("caddy validate")
    assert not remote.ran("systemctl reload caddy")
    assert ("enable", "--quiet", "caddy") in remote.systemctl_calls


def test_apply_reloads_when_caddy_is_inactive_even_if_the_file_is_right(ctx: Context, remote: FakeRemote) -> None:
    _installed(remote)
    remote.files[CADDYFILE] = MANAGED_API_BLOCK
    _script_swap(remote)
    CaddyStep().apply(ctx)
    assert remote.backups == []
    assert remote.ran("systemctl reload caddy || systemctl restart caddy")


def test_apply_restores_on_validation_failure(ctx: Context, remote: FakeRemote) -> None:
    _installed(remote)
    remote.files[CADDYFILE] = REFERENCE_CADDYFILE
    remote.ok("systemctl is-active --quiet caddy")
    remote.fail("caddy validate", "Error: adapting config using caddyfile: /etc/caddy/Caddyfile.new:3: unrecognized directive: bogus")
    remote.ok("systemctl enable")
    with pytest.raises(StepError) as excinfo:
        CaddyStep().apply(ctx)
    assert "unrecognized directive: bogus" in str(excinfo.value)
    assert f"{CADDYFILE} is unchanged" in str(excinfo.value)
    assert remote.files[CADDYFILE] == REFERENCE_CADDYFILE
    assert remote.backups == []
    assert remote.ran(f"rm -f {CADDYFILE_NEW}")
    assert not remote.ran(f"mv -f {CADDYFILE_NEW}")
    assert not remote.ran("systemctl reload caddy")


def test_apply_wraps_remote_errors(ctx: Context, remote: FakeRemote) -> None:
    remote.fail("gpg --dearmor", "curl: (6) Could not resolve host: dl.cloudsmith.io")
    with pytest.raises(StepError, match="Caddy setup failed"):
        CaddyStep().apply(ctx)
    assert remote.apt_installed == []


# --- verify ---------------------------------------------------------------------------------------------


def test_verify_ok_when_active_valid_and_answering(ctx: Context, remote: FakeRemote) -> None:
    _provisioned(remote)
    remote.ok("caddy validate")
    remote.ok("curl -s -o /dev/null -w '%{http_code}' -m 5 http://127.0.0.1/", "308")
    result = CaddyStep().verify(ctx)
    assert result.ok
    assert result.detail.startswith("caddy v2.8.4 active · Caddyfile valid · http://127.0.0.1/ → HTTP 308 · api.example.com → 127.0.0.1:8080")
    assert "DNS → 203.0.113.10 (matches)" in result.detail
    assert remote.ran(f"caddy validate --config {CADDYFILE} --adapter caddyfile")


def test_verify_fails_when_inactive(ctx: Context, remote: FakeRemote) -> None:
    remote.ok("systemctl --no-pager --lines=10 status caddy", "x caddy.service - Caddy\n     Active: failed (Result: exit-code)\n")
    result = CaddyStep().verify(ctx)
    assert not result.ok
    assert result.detail.startswith("caddy is not active")
    assert "Active: failed" in result.detail


def test_verify_fails_when_caddyfile_invalid(ctx: Context, remote: FakeRemote) -> None:
    remote.ok("systemctl is-active --quiet caddy")
    remote.fail("caddy validate", "Error: adapting config using caddyfile: unrecognized directive: bogus")
    result = CaddyStep().verify(ctx)
    assert not result.ok
    assert "does not validate" in result.detail and "bogus" in result.detail


def test_verify_fails_when_caddy_does_not_answer(ctx: Context, remote: FakeRemote) -> None:
    remote.ok("systemctl is-active --quiet caddy")
    remote.ok("caddy validate")
    remote.on("curl -s -o /dev/null", (7, "000"))
    result = CaddyStep().verify(ctx)
    assert not result.ok
    assert "does not answer on http://127.0.0.1/" in result.detail


# --- describe -------------------------------------------------------------------------------------------


def test_describe_mirrors_apply(plan: InstallPlan) -> None:
    text = CaddyStep().describe(plan)
    assert "install caddy from the cloudsmith repository if missing" in text
    assert f"api.example.com → 127.0.0.1:8080 site block into {CADDYFILE}" in text
    assert "caddy validate" in text
    assert "global options" not in text
    plan.proxy.acme_email = "ops@example.com"
    assert "global options block with email ops@example.com" in CaddyStep().describe(plan)

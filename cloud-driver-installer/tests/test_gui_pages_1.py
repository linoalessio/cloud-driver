"""GUI page tests, part 1: server, packages, java, python, postgres, redis, clamav, firewall, swap.

Skipped without ``tkinter``, without a display (``tk.Tk()`` raising ``TclError``), and until the
shared ``gui.pages.base`` / ``gui.widgets`` modules exist. Every page must build, ``load`` then
``store`` must round-trip each of its fields, and ``store`` must raise ``ValueError`` on a bad
value (ports above all).
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any, Callable, Iterator

import pytest

tk = pytest.importorskip("tkinter")
pytest.importorskip("cloud_driver_installer.gui.widgets")
pytest.importorskip("cloud_driver_installer.gui.pages.base")

from cloud_driver_installer.engine import StepStatus
from cloud_driver_installer.model import Discovered, GeneratedSecrets, InstallPlan, to_dict
from cloud_driver_installer.secrets import Redactor
from cloud_driver_installer.steps import STEP_ORDER

#: ``(module, class)`` of every page this file covers.
PAGES: tuple[tuple[str, str], ...] = (
    ("server", "ServerPage"),
    ("packages", "PackagesPage"),
    ("java", "JavaPage"),
    ("python", "PythonPage"),
    ("postgres", "PostgresPage"),
    ("redis", "RedisPage"),
    ("clamav", "ClamavPage"),
    ("firewall", "FirewallPage"),
    ("swap", "SwapPage"),
)

PAGES_WITH_PORT: tuple[tuple[str, str], ...] = (("postgres", "PostgresPage"), ("redis", "RedisPage"), ("clamav", "ClamavPage"))


# --- variants: non-default values for every field a page owns ------------------------------------


def _server_variant(plan: InstallPlan) -> None:
    plan.server.install_dir = "/srv/cloud"
    plan.server.screen_session = "cd-prod"
    plan.server.timezone = "Europe/Berlin"
    plan.server.unattended_upgrades = True
    plan.server.write_ssh_alias = True
    plan.server.ssh_alias_name = "box1"


def _postgres_variant(plan: InstallPlan) -> None:
    plan.postgres.mode = "external"
    plan.postgres.host = "db.example.com"
    plan.postgres.port = 5433
    plan.postgres.database = "cd_db"
    plan.postgres.username = "cd_user"
    plan.postgres.password = "typed-secret-123456"
    plan.postgres.rotate = True


def _redis_variant(plan: InstallPlan) -> None:
    plan.redis.enabled = False
    plan.redis.mode = "external"
    plan.redis.host = "redis.example.com"
    plan.redis.port = 6380
    plan.redis.username = "app"
    plan.redis.database = "3"
    plan.redis.password = "redis-secret-123456"
    plan.redis.rotate = True


def _clamav_variant(plan: InstallPlan) -> None:
    plan.clamav.enabled = False
    plan.clamav.host = "10.0.0.5"
    plan.clamav.port = 3311
    plan.clamav.timeout_seconds = 45
    plan.clamav.stream_max_length = "256M"
    plan.clamav.max_file_size = "256M"
    plan.clamav.max_scan_size = "512M"
    plan.clamav.content_scan_max_bytes = 200_000_000


def _firewall_variant(plan: InstallPlan) -> None:
    plan.server.firewall = False
    plan.server.firewall_extra_ports = "9404/tcp 8443"


def _swap_variant(plan: InstallPlan) -> None:
    plan.server.swap_mb = 2048


VARIANTS: dict[str, Callable[[InstallPlan], None]] = {
    "server": _server_variant,
    "postgres": _postgres_variant,
    "redis": _redis_variant,
    "clamav": _clamav_variant,
    "firewall": _firewall_variant,
    "swap": _swap_variant,
}

#: Values that must make ``store`` raise, per page: ``(attribute of the page holding a StringVar, bad text, match)``.
BAD_VALUES: tuple[tuple[str, str, str, str, str], ...] = (
    ("swap", "SwapPage", "swap_var", "-1", "Swapfile size"),
    ("swap", "SwapPage", "swap_var", "abc", "Swapfile size"),
    ("swap", "SwapPage", "swap_var", "99999", "Swapfile size"),
    ("firewall", "FirewallPage", "extra_ports_var", "abc", "Additional ports"),
    ("firewall", "FirewallPage", "extra_ports_var", "9404/sctp", "Additional ports"),
    ("firewall", "FirewallPage", "extra_ports_var", "70000/tcp", "Additional ports"),
    ("server", "ServerPage", "install_dir_var", "relative/path", "Install directory"),
    ("server", "ServerPage", "screen_var", "has space", "Screen session"),
    ("clamav", "ClamavPage", "timeout_var", "never", "timeout"),
    ("clamav", "ClamavPage", "content_max_var", "0", "content-scan-max-bytes"),
    ("redis", "RedisPage", "database_var", "cache", "Redis database"),
)


# --- fixtures ------------------------------------------------------------------------------------


class FakeActions:
    """Records every call a page makes; ``generate_secret`` returns a deterministic long value."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, ...]] = []

    def check(self, step_id: str) -> None:
        self.calls.append(("check", step_id))

    def apply(self, step_id: str) -> None:
        self.calls.append(("apply", step_id))

    def validate_aws(self) -> None:
        self.calls.append(("validate_aws",))

    def check_dns(self, domain: str) -> None:
        self.calls.append(("check_dns", domain))

    def generate_secret(self, kind: str) -> str:
        self.calls.append(("generate_secret", kind))
        return f"generated-{kind}-0123456789abcdef"


def make_state(plan: InstallPlan, discovered: Discovered | None = None) -> Any:
    """An ``AppState`` when the module exists, else a namespace with the contract's attributes."""
    discovered = discovered or Discovered()
    try:
        from cloud_driver_installer.gui.state import AppState
    except ImportError:
        AppState = None  # type: ignore[assignment]
    if AppState is not None:
        try:
            state = AppState.new(plan)
            state.discovered = discovered
            return state
        except Exception:  # noqa: BLE001 - fall back to the namespace on any signature mismatch
            pass
    return SimpleNamespace(
        plan=plan,
        discovered=discovered,
        secrets=GeneratedSecrets(),
        redactor=Redactor(),
        included={step_id for step_id, _ in STEP_ORDER},
        statuses={},
        details={},
        elapsed={},
        profile_path=None,
        session=None,
        remote=None,
    )


@pytest.fixture(scope="module")
def root() -> Iterator[Any]:
    try:
        window = tk.Tk()
    except tk.TclError as exc:
        pytest.skip(f"no display available: {exc}")
    window.withdraw()
    yield window
    window.destroy()


@pytest.fixture
def make_page(root: Any) -> Callable[..., tuple[Any, Any, FakeActions]]:
    """``make_page(module, cls, plan=None, discovered=None) -> (page, state, actions)``."""

    def factory(module_name: str, class_name: str, plan: InstallPlan | None = None, discovered: Discovered | None = None) -> tuple[Any, Any, FakeActions]:
        module = pytest.importorskip(f"cloud_driver_installer.gui.pages.{module_name}")
        page_class = getattr(module, class_name)
        plan = plan or InstallPlan()
        actions = FakeActions()
        state = make_state(plan, discovered)
        page = page_class(root, state, actions)
        root.update_idletasks()
        return page, state, actions

    return factory


def _plan_dict(plan: InstallPlan) -> dict[str, Any]:
    return to_dict(plan, strip_secrets=False)


# --- every page ----------------------------------------------------------------------------------


@pytest.mark.parametrize(("module_name", "class_name"), PAGES)
def test_page_builds(make_page: Callable[..., Any], module_name: str, class_name: str) -> None:
    page, _, _ = make_page(module_name, class_name)
    assert page.step_id == module_name
    assert page.title == dict(STEP_ORDER)[module_name]
    assert page.winfo_exists()


@pytest.mark.parametrize(("module_name", "class_name"), PAGES)
def test_default_plan_round_trips(make_page: Callable[..., Any], module_name: str, class_name: str) -> None:
    page, _, _ = make_page(module_name, class_name)
    page.load(InstallPlan())
    target = InstallPlan()
    page.store(target)
    assert _plan_dict(target) == _plan_dict(InstallPlan())


@pytest.mark.parametrize(("module_name", "class_name"), PAGES)
def test_refresh_before_and_after_discovery(make_page: Callable[..., Any], module_name: str, class_name: str, discovered: Discovered) -> None:
    page, state, _ = make_page(module_name, class_name)
    page.refresh(state)
    state.discovered = discovered
    state.statuses[module_name] = StepStatus.NEEDS_APPLY
    state.details[module_name] = "something to do"
    page.refresh(state)
    state.statuses[module_name] = StepStatus.OK
    page.refresh(state)


@pytest.mark.parametrize(("module_name", "class_name"), [pair for pair in PAGES if pair[0] in VARIANTS])
def test_variant_round_trips_every_field(make_page: Callable[..., Any], module_name: str, class_name: str) -> None:
    expected = InstallPlan()
    VARIANTS[module_name](expected)
    assert _plan_dict(expected) != _plan_dict(InstallPlan()), "the variant must differ from the defaults"
    page, _, _ = make_page(module_name, class_name)
    page.load(expected)
    target = InstallPlan()
    page.store(target)
    assert _plan_dict(target) == _plan_dict(expected)


@pytest.mark.parametrize(("module_name", "class_name"), [pair for pair in PAGES if pair[0] in VARIANTS])
def test_store_only_touches_the_pages_own_fields(make_page: Callable[..., Any], module_name: str, class_name: str, plan: InstallPlan) -> None:
    """Storing into the conftest plan (repo root, domains, bucket …) leaves every foreign field alone."""
    page, _, _ = make_page(module_name, class_name)
    page.load(InstallPlan())
    before = _plan_dict(plan)
    page.store(plan)
    after = _plan_dict(plan)
    for section, values in before.items():
        if module_name == "server" and section == "server":
            continue
        if module_name in ("firewall", "swap") and section == "server":
            continue
        if section == module_name:
            continue
        assert after[section] == values, f"{class_name}.store changed plan.{section}"


# --- bad values ----------------------------------------------------------------------------------


@pytest.mark.parametrize(("module_name", "class_name"), PAGES_WITH_PORT)
@pytest.mark.parametrize("bad_port", ["abc", "", "0", "70000", "-5", "12.5"])
def test_store_raises_on_bad_port(make_page: Callable[..., Any], module_name: str, class_name: str, bad_port: str) -> None:
    page, _, _ = make_page(module_name, class_name)
    page.load(InstallPlan())
    page.port_var.set(bad_port)
    target = InstallPlan()
    with pytest.raises(ValueError, match="port"):
        page.store(target)
    assert _plan_dict(target) == _plan_dict(InstallPlan()), "a failed store must not half-write the plan"


@pytest.mark.parametrize(("module_name", "class_name", "attribute", "bad_text", "match"), BAD_VALUES)
def test_store_raises_on_bad_value(make_page: Callable[..., Any], module_name: str, class_name: str, attribute: str, bad_text: str, match: str) -> None:
    page, _, _ = make_page(module_name, class_name)
    page.load(InstallPlan())
    getattr(page, attribute).set(bad_text)
    with pytest.raises(ValueError, match=match):
        page.store(InstallPlan())


@pytest.mark.parametrize("attribute", ["stream_max_length", "max_file_size", "max_scan_size"])
def test_clamav_rejects_bad_size_syntax(make_page: Callable[..., Any], attribute: str) -> None:
    page, _, _ = make_page("clamav", "ClamavPage")
    page.load(InstallPlan())
    page.limit_vars[attribute].set("lots")
    with pytest.raises(ValueError, match="ClamAV"):
        page.store(InstallPlan())


def test_firewall_normalises_the_port_list(make_page: Callable[..., Any]) -> None:
    page, _, _ = make_page("firewall", "FirewallPage")
    page.load(InstallPlan())
    page.extra_ports_var.set(" 9404/tcp,8443 ,  53/udp ")
    target = InstallPlan()
    page.store(target)
    assert target.server.firewall_extra_ports == "9404/tcp 8443 53/udp"


# --- passwords -----------------------------------------------------------------------------------


@pytest.mark.parametrize(("module_name", "class_name", "section", "kind"), [("postgres", "PostgresPage", "postgres", "pg_password"), ("redis", "RedisPage", "redis", "redis_password")])
def test_generate_sets_the_plan_and_ticks_rotate(make_page: Callable[..., Any], module_name: str, class_name: str, section: str, kind: str) -> None:
    page, _, actions = make_page(module_name, class_name)
    page.load(InstallPlan())
    assert not page.rotate_var.get()
    value = page.generate_password()
    assert ("generate_secret", kind) in actions.calls
    assert page.password_var.get() == value
    assert page.rotate_var.get()
    target = InstallPlan()
    page.store(target)
    settings = getattr(target, section)
    assert settings.password == value
    assert settings.rotate is True


@pytest.mark.parametrize(("module_name", "class_name", "section", "secret_attr"), [("postgres", "PostgresPage", "postgres", "pg_password"), ("redis", "RedisPage", "redis", "redis_password")])
def test_kept_password_is_shown_but_not_written_to_the_plan(make_page: Callable[..., Any], module_name: str, class_name: str, section: str, secret_attr: str) -> None:
    page, state, _ = make_page(module_name, class_name)
    page.load(InstallPlan())
    setattr(state.secrets, secret_attr, "kept-from-server-0123456789")
    setattr(state.secrets, f"{secret_attr}_kept", True)
    page.refresh(state)
    assert page.password_var.get() == "kept-from-server-0123456789"
    target = InstallPlan()
    page.store(target)
    assert getattr(target, section).password == "", "a displayed server password stays the step's business"
    # load() after Check all (plan password blank) keeps showing the kept value …
    page.load(InstallPlan())
    assert page.password_var.get() == "kept-from-server-0123456789"
    # … until the operator types their own, which is then stored.
    page.password_var.set("typed-by-operator-0123456789")
    page.store(target)
    assert getattr(target, section).password == "typed-by-operator-0123456789"


def test_postgres_test_connection_asks_for_a_check(make_page: Callable[..., Any]) -> None:
    page, _, actions = make_page("postgres", "PostgresPage")
    page.test_connection()
    assert ("check", "postgres") in actions.calls


# --- derived labels (pure helpers) ---------------------------------------------------------------


def test_server_fact_values(discovered: Discovered) -> None:
    from cloud_driver_installer.gui.pages.server import FACT_CAPTIONS, NOT_CHECKED, fact_values

    blank = fact_values(Discovered())
    assert set(blank) == {key for key, _ in FACT_CAPTIONS}
    assert set(blank.values()) == {NOT_CHECKED}
    full = fact_values(discovered)
    assert full["os"] == "Debian GNU/Linux 12 (bookworm)"
    assert full["cpu"] == "2 vCPU"
    assert full["ram"] == "7.7 GiB"
    assert full["swap"] == "none"
    assert full["disk"] == "38.2 GiB"
    assert full["ip"] == "203.0.113.10"
    assert full["screen"] == "not running"
    assert full["ntp"] == NOT_CHECKED


def test_package_states_from_detail_and_status() -> None:
    from cloud_driver_installer.gui.pages.packages import FALLBACK_PACKAGES, package_states, package_table, parse_package_detail

    detail = "Installed: curl, ca-certificates, gnupg. Missing: screen, unzip, openssl, apt-transport-https, debian-keyring, debian-archive-keyring."
    installed, missing = parse_package_detail(detail)
    assert installed == {"curl", "ca-certificates", "gnupg"}
    assert "screen" in missing and len(missing) == 6
    names = [name for name, _ in package_table()]
    assert len(names) >= len(FALLBACK_PACKAGES) - 1
    state = SimpleNamespace(discovered=Discovered(), statuses={}, details={})
    assert set(package_states(state, names).values()) == {"not checked"}
    state.statuses["packages"] = StepStatus.NEEDS_APPLY
    state.details["packages"] = detail
    verdicts = package_states(state, ["screen", "curl"])
    assert verdicts == {"screen": "missing", "curl": "present"}
    state.statuses["packages"] = StepStatus.OK
    state.details["packages"] = "all 9 packages present"
    assert set(package_states(state, names).values()) == {"present"}


def test_java_and_python_detected_lines(discovered: Discovered) -> None:
    from cloud_driver_installer.gui.pages.java import detected_java
    from cloud_driver_installer.gui.pages.python import detected_python

    assert detected_java(Discovered()) == "—"
    assert detected_java(discovered) == "not found"
    discovered.java_version = "21.0.4"
    assert detected_java(discovered) == "21.0.4"
    assert detected_python(Discovered()) == "—"
    assert detected_python(discovered) == "3.11.2"
    discovered.venv_works = False
    assert detected_python(discovered) == "3.11.2 · venv broken (ensurepip)"
    discovered.venv_works = True
    assert detected_python(discovered) == "3.11.2 · venv ok"

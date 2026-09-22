"""Every page, against the real window classes: it builds, it round-trips, and it refuses junk.

Four properties, checked for each of the fifteen step pages plus the summary:

* the page builds and carries the step id and title the catalog gives it;
* loading a plan and storing it back leaves that plan unchanged - a field a page forgets to load,
  or stores as a default, changes it and fails here;
* storing touches only the sections that page owns, so opening a page never rewrites another's
  settings;
* a value that is not a number raises ``ValueError`` (which the window turns into the red line
  above the form) instead of writing half a plan.

Skipped without a display, like the rest of the GUI suite.
"""

from __future__ import annotations

import copy
from typing import Any, Callable, Iterator

import pytest

from cloud_driver_installer.engine import StepStatus
from cloud_driver_installer.model import Discovered, InstallPlan, to_dict
from cloud_driver_installer.steps import STEP_ORDER

tk = pytest.importorskip("tkinter")

from cloud_driver_installer.gui.pages import PAGES, PageActions
from cloud_driver_installer.gui.state import AppState
from cloud_driver_installer.gui.widgets import init_styles

#: ``step id -> the plan sections that page is allowed to write``. Three pages share
#: ``plan.server`` (the host options, the firewall and the swapfile all live in it), and two pages
#: carry a name of their own: the reverse proxy writes ``proxy``, the application writes ``app``.
OWNED_SECTIONS: dict[str, set[str]] = {
    "server": {"server"},
    "packages": set(),
    "java": set(),
    "python": set(),
    "postgres": {"postgres"},
    "redis": {"redis"},
    "clamav": {"clamav"},
    "firewall": {"server"},
    "swap": {"server"},
    "aws": {"aws"},
    "email": {"email"},
    "caddy": {"proxy"},
    "config": {"app"},
    "application": {"app"},
    "intelligence": {"intelligence"},
}

#: Every page that edits the plan (the summary only reads it).
STEP_PAGES = [(step_id, page_class) for step_id, page_class in PAGES.items() if step_id != "summary"]


def filled_plan(repo_root: str) -> InstallPlan:
    """A plan whose every section differs from the defaults, in the shape the pages store back.

    Values are written the way a page would store them (a lowercase domain, a single-space port
    list), so a round-trip is expected to be exact rather than merely equivalent.
    """
    plan = InstallPlan()
    plan.server.install_dir = "/srv/cloud"
    plan.server.screen_session = "cd-prod"
    plan.server.timezone = "Europe/Berlin"
    plan.server.unattended_upgrades = True
    plan.server.firewall = True
    plan.server.firewall_extra_ports = "9404/tcp 8443/tcp"
    plan.server.swap_mb = 2048
    plan.server.write_ssh_alias = True
    plan.server.ssh_alias_name = "box1"
    plan.postgres.mode = "external"
    plan.postgres.host = "db.example.com"
    plan.postgres.port = 5433
    plan.postgres.database = "cd_db"
    plan.postgres.username = "cd_user"
    plan.redis.enabled = True
    plan.redis.mode = "external"
    plan.redis.host = "redis.example.com"
    plan.redis.port = 6380
    plan.redis.database = "3"
    plan.clamav.enabled = True
    plan.clamav.host = "127.0.0.1"  # pinned by the page on purpose: clamd is installed here
    plan.clamav.port = 3311
    plan.clamav.timeout_seconds = 45
    plan.aws.region = "eu-west-1"
    plan.aws.kms_mode = "existing"
    plan.aws.kms_key_id = "alias/other-key"
    plan.aws.s3_bucket = "cloud-driver-content"
    plan.aws.s3_key_prefix = "files"
    plan.email.mode = "smtp"
    plan.email.smtp_host = "mail.example.com"
    plan.email.smtp_port = 2525
    plan.email.smtp_username = "postmaster"
    plan.email.smtp_password = "smtp-secret-value"
    plan.email.smtp_from_address = "cloud@example.com"
    plan.proxy.enabled = True
    plan.proxy.api_domain = "api.example.com"
    plan.proxy.acme_email = "ops@example.com"
    plan.app.rest_port = 8081
    plan.app.metrics_port = 9405
    plan.app.jvm_xmx = "5g"
    plan.app.repo_root = repo_root
    plan.intelligence.enabled = True
    plan.intelligence.port = 8010
    plan.intelligence.ocr = True
    plan.intelligence.ocr_languages = "deu+eng"
    return plan


@pytest.fixture(scope="module")
def root() -> Iterator[Any]:
    try:
        window = tk.Tk()
    except tk.TclError as exc:  # pragma: no cover - headless CI
        pytest.skip(f"no display available: {exc}")
    init_styles(window)
    window.withdraw()
    yield window
    window.destroy()


@pytest.fixture
def actions() -> PageActions:
    """Page actions that record nothing and reach no server."""
    return PageActions(
        check=lambda step_id: None,
        apply=lambda step_id: None,
        remove=lambda step_id: None,
        probe_aws=lambda: None,
        probe_dns=lambda domain: None,
        install=lambda: None,
        stop=lambda: None,
        export_setup=lambda: None,
    )


@pytest.fixture
def build_page(root: Any, actions: PageActions) -> Callable[..., Any]:
    """``build_page(page_class, plan) -> page`` on a shared root."""

    def factory(page_class: type, plan: InstallPlan | None = None) -> Any:
        state = AppState(plan=plan or InstallPlan())
        page = page_class(root, state, actions)
        root.update_idletasks()
        return page

    return factory


@pytest.mark.parametrize(("step_id", "page_class"), STEP_PAGES, ids=[step_id for step_id, _ in STEP_PAGES])
def test_every_page_builds_with_its_catalog_identity(build_page: Callable[..., Any], step_id: str, page_class: type) -> None:
    page = build_page(page_class)
    assert page.step_id == step_id
    assert page.title == dict(STEP_ORDER)[step_id]
    assert page.winfo_exists()


@pytest.mark.parametrize(("step_id", "page_class"), STEP_PAGES, ids=[step_id for step_id, _ in STEP_PAGES])
def test_loading_a_plan_and_storing_it_back_changes_nothing(build_page: Callable[..., Any], step_id: str, page_class: type, plan: InstallPlan) -> None:
    """The round-trip that matters: a field the page drops or defaults shows up as a difference."""
    source = filled_plan(plan.app.repo_root)
    page = build_page(page_class, source)
    page.load(source)
    target = copy.deepcopy(source)
    page.store(target)
    assert to_dict(target, strip_secrets=False) == to_dict(source, strip_secrets=False)


@pytest.mark.parametrize(("step_id", "page_class"), STEP_PAGES, ids=[step_id for step_id, _ in STEP_PAGES])
def test_a_page_stores_only_its_own_sections(build_page: Callable[..., Any], step_id: str, page_class: type, plan: InstallPlan) -> None:
    """Opening the Redis page must not rewrite the AWS settings, and so on for every page."""
    source = filled_plan(plan.app.repo_root)
    page = build_page(page_class, InstallPlan())
    page.load(InstallPlan())  # defaults in the widgets, a filled plan on the table
    target = copy.deepcopy(source)
    page.store(target)
    before, after = to_dict(source, strip_secrets=False), to_dict(target, strip_secrets=False)
    own = OWNED_SECTIONS[step_id]
    for section, values in before.items():
        if section in own or not isinstance(values, dict):
            continue
        assert after[section] == values, f"{page_class.__name__}.store changed plan.{section}"


@pytest.mark.parametrize(
    ("step_id", "attribute", "bad_value", "match"),
    [
        ("postgres", "port", "abc", "port"),
        ("postgres", "port", "0", "port"),
        ("redis", "port", "70000", "port"),
        ("clamav", "port", "12.5", "port"),
        ("clamav", "timeout", "never", "timeout"),
        ("application", "rest_port", "", "port"),
        ("application", "metrics_port", "-5", "port"),
        ("intelligence", "port", "eight", "port"),
    ],
)
def test_a_bad_number_raises_instead_of_half_writing_the_plan(
    build_page: Callable[..., Any], step_id: str, attribute: str, bad_value: str, match: str
) -> None:
    page = build_page(PAGES[step_id])
    page.load(InstallPlan())
    getattr(page, attribute).set(bad_value)
    target = InstallPlan()
    with pytest.raises(ValueError, match=match):
        page.store(target)
    assert to_dict(target, strip_secrets=False) == to_dict(InstallPlan(), strip_secrets=False)


@pytest.mark.parametrize(("step_id", "page_class"), list(PAGES.items()), ids=list(PAGES))
def test_refresh_survives_an_empty_and_a_discovered_state(build_page: Callable[..., Any], step_id: str, page_class: type, discovered: Discovered) -> None:
    """Pages render before the first check, with discovered facts, and with a status set."""
    page = build_page(page_class)
    state = AppState(plan=InstallPlan())
    page.refresh(state)
    state.discovered = discovered
    if step_id != "summary":
        state.statuses[step_id] = StepStatus.NEEDS_APPLY
        state.details[step_id] = "something to do"
    page.refresh(state)
    page.refresh_header(state)

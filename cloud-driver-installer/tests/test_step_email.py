"""EmailStep against a fake SES provisioner (never boto3) and the scripted FakeRemote."""

from __future__ import annotations

from typing import Any

import pytest

from cloud_driver_installer.aws import AwsError, SesIdentity
from cloud_driver_installer.engine import Context, StepError, StepStatus
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.steps.email import EmailStep

from fake_remote import FakeRemote

FROM = "noreply@example.com"
DOMAIN = "example.com"


class FakeSes:
    """The SES slice of ``AwsProvisioner``; identities live in a dict, every call is recorded with its region."""

    def __init__(self) -> None:
        self.identities: dict[str, SesIdentity] = {}
        self.configuration_sets: set[str] = set()
        self.calls: list[tuple[str, str, str | None]] = []
        self.error: AwsError | None = None

    def seed(self, identity: str, status: str = "PENDING") -> SesIdentity:
        kind = "address" if "@" in identity else "domain"
        records = [(f"tok{i}._domainkey.{identity}", f"tok{i}.dkim.amazonses.com") for i in (1, 2, 3)] if kind == "domain" else []
        self.identities[identity] = SesIdentity(identity=identity, kind=kind, status=status, dkim_records=records)
        return self.identities[identity]

    def _snapshot(self, identity: str, created: bool) -> SesIdentity:
        state = self.identities[identity]
        return SesIdentity(identity=state.identity, kind=state.kind, status=state.status, created=created, dkim_records=list(state.dkim_records))

    def ensure_ses_identity(self, identity: str, *, region: str | None = None) -> SesIdentity:
        self.calls.append(("ensure", identity, region))
        if self.error:
            raise self.error
        if identity in self.identities:
            return self._snapshot(identity, False)
        self.seed(identity)
        return self._snapshot(identity, True)

    def get_ses_identity(self, identity: str, *, region: str | None = None) -> SesIdentity | None:
        self.calls.append(("get", identity, region))
        if self.error:
            raise self.error
        return self._snapshot(identity, False) if identity in self.identities else None

    def ses_configuration_set_exists(self, name: str, *, region: str | None = None) -> bool:
        self.calls.append(("config-set", name, region))
        if self.error:
            raise self.error
        return name in self.configuration_sets


@pytest.fixture
def fake() -> FakeSes:
    return FakeSes()


@pytest.fixture
def step() -> EmailStep:
    return EmailStep()


@pytest.fixture
def ses_ctx(ctx: Context, fake: FakeSes, plan: InstallPlan) -> Context:
    plan.email.mode = "ses"
    plan.email.ses_from_address = FROM
    ctx.aws_factory = lambda: fake  # type: ignore[assignment,return-value]
    return ctx


@pytest.fixture
def smtp_ctx(ctx: Context, plan: InstallPlan) -> Context:
    plan.email.mode = "smtp"
    plan.email.smtp_host = "smtp.example.net"
    plan.email.smtp_port = 587
    plan.email.smtp_username = "mailer"
    plan.email.smtp_password = "smtpSecretValue0123456789"
    plan.email.smtp_from_address = FROM
    return ctx


def log_text(ctx: Context) -> str:
    return "\n".join(message for _, message in ctx.captured_log)  # type: ignore[attr-defined]


# --- enablement / metadata --------------------------------------------------------------------------


def test_enabled_only_with_a_transport(step: EmailStep, plan: InstallPlan) -> None:
    plan.email.mode = "none"
    assert not step.enabled(plan)
    plan.email.mode = "ses"
    assert step.enabled(plan)
    plan.email.mode = "smtp"
    assert step.enabled(plan)
    assert step.id == "email" and step.depends_on == ("aws",) and not step.mandatory


def test_ses_identity_resolution(step: EmailStep, plan: InstallPlan) -> None:
    plan.email.ses_from_address = "NoReply@Example.COM"
    plan.email.ses_identity_mode = "domain"
    assert step.ses_identity(plan) == ("example.com", "domain")
    plan.email.ses_identity_mode = "address"
    assert step.ses_identity(plan) == ("NoReply@Example.COM", "address")


# --- SES: domain identity -------------------------------------------------------------------------------


def test_ses_domain_check_needs_apply_when_identity_missing(step: EmailStep, ses_ctx: Context, fake: FakeSes) -> None:
    result = step.check(ses_ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert f"SES domain identity {DOMAIN} not created in eu-central-1" in result.detail
    assert fake.calls == [("get", DOMAIN, "eu-central-1")]


def test_ses_domain_apply_creates_identity_and_captures_dkim(step: EmailStep, ses_ctx: Context, fake: FakeSes) -> None:
    step.apply(ses_ctx)
    assert ("ensure", DOMAIN, "eu-central-1") in fake.calls
    assert DOMAIN in fake.identities
    assert ses_ctx.secrets.ses_dkim_records == [
        (f"tok1._domainkey.{DOMAIN}", "tok1.dkim.amazonses.com"),
        (f"tok2._domainkey.{DOMAIN}", "tok2.dkim.amazonses.com"),
        (f"tok3._domainkey.{DOMAIN}", "tok3.dkim.amazonses.com"),
    ]
    text = log_text(ses_ctx)
    assert f"created SES domain identity {DOMAIN}" in text
    assert f"tok1._domainkey.{DOMAIN}  CNAME  tok1.dkim.amazonses.com" in text
    assert f"_dmarc.{DOMAIN}" in text
    assert "sandbox" in text
    verify = step.verify(ses_ctx)
    assert verify.ok
    assert verify.detail.startswith("pending — DKIM records outstanding")


def test_ses_domain_apply_adopts_existing_identity(step: EmailStep, ses_ctx: Context, fake: FakeSes) -> None:
    fake.seed(DOMAIN, "PENDING")
    step.apply(ses_ctx)
    assert "already exists" in log_text(ses_ctx)
    assert "sandbox" not in log_text(ses_ctx)
    assert len(ses_ctx.secrets.ses_dkim_records) == 3


def test_ses_check_ok_when_verified_and_records_still_captured(step: EmailStep, ses_ctx: Context, fake: FakeSes) -> None:
    fake.seed(DOMAIN, "SUCCESS")
    result = step.check(ses_ctx)
    assert result.status is StepStatus.OK
    assert f"SES domain identity {DOMAIN} verified in eu-central-1" in result.detail
    assert len(ses_ctx.secrets.ses_dkim_records) == 3
    verify = step.verify(ses_ctx)
    assert verify.ok and "verified" in verify.detail


def test_ses_check_needs_apply_while_pending(step: EmailStep, ses_ctx: Context, fake: FakeSes) -> None:
    fake.seed(DOMAIN, "PENDING")
    result = step.check(ses_ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "publish the 3 DKIM CNAME records" in result.detail


def test_ses_uses_the_plan_ses_region(step: EmailStep, ses_ctx: Context, fake: FakeSes, plan: InstallPlan) -> None:
    plan.email.ses_region = "eu-west-1"
    step.check(ses_ctx)
    step.apply(ses_ctx)
    step.verify(ses_ctx)
    assert fake.calls and all(region == "eu-west-1" for _, _, region in fake.calls)


# --- SES: address identity --------------------------------------------------------------------------------


def test_ses_address_identity_is_the_from_address(step: EmailStep, ses_ctx: Context, fake: FakeSes, plan: InstallPlan) -> None:
    plan.email.ses_identity_mode = "address"
    assert step.check(ses_ctx).status is StepStatus.NEEDS_APPLY
    step.apply(ses_ctx)
    assert FROM in fake.identities and DOMAIN not in fake.identities
    assert ses_ctx.secrets.ses_dkim_records == []
    assert f"verification mail to {FROM}" in log_text(ses_ctx)
    verify = step.verify(ses_ctx)
    assert verify.ok and verify.detail.startswith("pending — verification mail outstanding")
    fake.identities[FROM].status = "SUCCESS"
    assert step.check(ses_ctx).status is StepStatus.OK


# --- SES: configuration set + verification switch ---------------------------------------------------------


def test_ses_missing_configuration_set_blocks(step: EmailStep, ses_ctx: Context, fake: FakeSes, plan: InstallPlan) -> None:
    plan.email.ses_configuration_set = "cloud-driver-mail"
    fake.seed(DOMAIN, "SUCCESS")
    result = step.check(ses_ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "configuration set cloud-driver-mail does not exist" in result.detail
    with pytest.raises(StepError, match="create it first or clear the field"):
        step.apply(ses_ctx)
    assert not any(kind == "ensure" for kind, _, _ in fake.calls)
    fake.configuration_sets.add("cloud-driver-mail")
    result = step.check(ses_ctx)
    assert result.status is StepStatus.OK and "configuration set cloud-driver-mail exists" in result.detail


def test_ses_verification_disabled_does_nothing_but_warns(step: EmailStep, ses_ctx: Context, fake: FakeSes, plan: InstallPlan) -> None:
    plan.email.ses_verify_identity = False
    result = step.check(ses_ctx)
    assert result.status is StepStatus.OK
    assert "verification disabled" in result.detail
    assert any(level == "WARN" for level, _ in ses_ctx.captured_log)  # type: ignore[attr-defined]
    step.apply(ses_ctx)
    assert not any(kind == "ensure" for kind, _, _ in fake.calls)
    verify = step.verify(ses_ctx)
    assert verify.ok and "verification disabled" in verify.detail


def test_ses_aws_error_becomes_step_error(step: EmailStep, ses_ctx: Context, fake: FakeSes) -> None:
    fake.error = AwsError("looking up SES identity example.com: AccessDeniedException: not authorized")
    with pytest.raises(StepError, match="AccessDeniedException"):
        step.check(ses_ctx)
    with pytest.raises(StepError, match="AccessDeniedException"):
        step.apply(ses_ctx)


def test_ses_apply_is_idempotent(step: EmailStep, ses_ctx: Context, fake: FakeSes) -> None:
    step.apply(ses_ctx)
    first = dict(fake.identities)
    records = list(ses_ctx.secrets.ses_dkim_records)
    step.apply(ses_ctx)
    assert fake.identities == first
    assert ses_ctx.secrets.ses_dkim_records == records


# --- SMTP ------------------------------------------------------------------------------------------------------


def test_smtp_check_ok_with_reachability_note(step: EmailStep, smtp_ctx: Context, remote: FakeRemote, plan: InstallPlan) -> None:
    remote.ok("/dev/tcp/smtp.example.net/587")
    result = step.check(smtp_ctx)
    assert result.status is StepStatus.OK
    assert "smtp.example.net:587 reachable from the server" in result.detail
    assert "mailer" in result.detail
    assert remote.ran("timeout 5 bash -c '</dev/tcp/smtp.example.net/587'")
    assert plan.email.smtp_password not in log_text(smtp_ctx)
    assert all(plan.email.smtp_password not in command for command in remote.commands)
    assert plan.email.smtp_password in smtp_ctx.redactor


def test_smtp_check_needs_apply_when_fields_missing(step: EmailStep, smtp_ctx: Context, plan: InstallPlan) -> None:
    plan.email.smtp_username = ""
    plan.email.smtp_from_address = ""
    result = step.check(smtp_ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "username" in result.detail and "from address" in result.detail
    with pytest.raises(StepError, match="username"):
        step.apply(smtp_ctx)


def test_smtp_apply_is_a_noop_and_verify_probes_the_host(step: EmailStep, smtp_ctx: Context, remote: FakeRemote) -> None:
    remote.ok("/dev/tcp/smtp.example.net/587")
    step.apply(smtp_ctx)
    assert remote.files == {} and not any(cmd.startswith("apt-get") for cmd in remote.commands)
    result = step.verify(smtp_ctx)
    assert result.ok and "reachable" in result.detail and "WARN" not in result.detail


def test_smtp_unreachable_host_warns_but_does_not_fail(step: EmailStep, smtp_ctx: Context, remote: FakeRemote) -> None:
    remote.fail("/dev/tcp/smtp.example.net/587", code=124)
    check = step.check(smtp_ctx)
    assert check.status is StepStatus.OK and "WARN" in check.detail
    result = step.verify(smtp_ctx)
    assert result.ok
    assert "not reachable from the server" in result.detail
    assert any(level == "WARN" and "not reachable" in message for level, message in smtp_ctx.captured_log)  # type: ignore[attr-defined]


# --- describe --------------------------------------------------------------------------------------------------


def test_describe(step: EmailStep, plan: InstallPlan) -> None:
    plan.email.mode = "ses"
    plan.email.ses_from_address = FROM
    line = step.describe(plan)
    assert f"verify domain identity {DOMAIN}" in line and "eu-central-1" in line and "\n" not in line
    plan.email.ses_identity_mode = "address"
    plan.email.ses_configuration_set = "cs"
    line = step.describe(plan)
    assert f"verify address identity {FROM}" in line and "configuration set cs" in line
    plan.email.ses_verify_identity = False
    assert "verification skipped" in step.describe(plan)
    plan.email.mode = "smtp"
    plan.email.smtp_host = "smtp.example.net"
    assert "SMTP smtp.example.net:587" in step.describe(plan)
    plan.email.mode = "none"
    assert "disabled" in step.describe(plan)


def test_none_mode_check_and_verify_are_trivial(step: EmailStep, ctx: Context, plan: InstallPlan) -> None:
    plan.email.mode = "none"
    assert step.check(ctx).status is StepStatus.OK
    step.apply(ctx)
    assert step.verify(ctx).ok

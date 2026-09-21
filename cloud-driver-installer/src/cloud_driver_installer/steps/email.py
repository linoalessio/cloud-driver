"""E-mail step: the SES sending identity, or (for SMTP) a reachability probe from the server.

Everything the backend needs to send mail through SES is an *identity* AWS has verified: the
sender's whole domain (Easy DKIM - three CNAME records the operator publishes, mail arrives in
the inbox) or the bare address (AWS sends a verification link to it). The identity is created
here with the operator's credentials; the runtime IAM user only ever gets ``ses:SendEmail``. The
DKIM records are captured into :attr:`GeneratedSecrets.ses_dkim_records` so the summary page can
show them as "DNS records to publish" - a pending verification never fails the run, because
publishing a DNS record is the operator's job, not the installer's.

SMTP has nothing to provision remotely: the five ``smtp-*`` keys land in ``configuration.json``
through the config step; this step only checks they are all set and probes the host:port from the
server so an outbound-blocked provider is reported before the first registration mail is lost.

Mirrors item 3 of the checklist ``shell/provision-root-server.sh`` prints (``ses verify-email-identity``
or a domain, sandbox note).
"""

from __future__ import annotations

import shlex
from typing import Any, Callable, TypeVar

from cloud_driver_installer.aws import AwsError, SesIdentity
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan

T = TypeVar("T")

#: The five ``smtp-*`` keys the backend needs (attribute name, human label).
SMTP_FIELDS: tuple[tuple[str, str], ...] = (
    ("smtp_host", "host"),
    ("smtp_port", "port"),
    ("smtp_username", "username"),
    ("smtp_password", "password"),
    ("smtp_from_address", "from address"),
)


class EmailStep(Step):
    """SES identity verification (domain with Easy DKIM, or address), or an SMTP reachability probe."""

    id = "email"
    title = "E-mail"
    depends_on = ("aws",)

    def enabled(self, plan: InstallPlan) -> bool:
        """Only when a transport is chosen; ``none`` leaves the codes in the server log."""
        return plan.email.mode != "none"

    # --- helpers ---------------------------------------------------------------------------------

    @staticmethod
    def _call(fn: Callable[..., T], *args: Any, **kwargs: Any) -> T:
        """Run one provisioner call, turning its (already readable) ``AwsError`` into a ``StepError``."""
        try:
            return fn(*args, **kwargs)
        except AwsError as exc:
            raise StepError(str(exc)) from exc

    @staticmethod
    def ses_identity(plan: InstallPlan) -> tuple[str, str]:
        """``(identity, kind)``: the from address's domain for Easy DKIM, else the address itself."""
        address = plan.email.ses_from_address.strip()
        if plan.email.ses_identity_mode == "domain" and "@" in address:
            return address.rsplit("@", 1)[1].lower(), "domain"
        return address, "address"

    @staticmethod
    def _capture(ctx: Context, state: SesIdentity) -> None:
        """Mirror the identity's DKIM records into the run's secrets (the summary's DNS panel)."""
        ctx.secrets.ses_dkim_records = [(name, value) for name, value in state.dkim_records]

    @staticmethod
    def _pending_hint(state: SesIdentity) -> str:
        if state.kind == "domain":
            count = len(state.dkim_records) or 3
            return f"verification pending ({state.status}): publish the {count} DKIM CNAME records for {state.identity}"
        return f"verification pending ({state.status}): open the mail AWS sent to {state.identity} and confirm the link"

    @staticmethod
    def _smtp_missing(plan: InstallPlan) -> list[str]:
        """Labels of the SMTP fields that are still blank."""
        return [label for attr, label in SMTP_FIELDS if not getattr(plan.email, attr)]

    @staticmethod
    def _smtp_probe(ctx: Context) -> tuple[bool, str]:
        """``</dev/tcp/host/port`` from the server with a 5 s timeout; returns ``(reachable, note)``."""
        email = ctx.plan.email
        target = f"{email.smtp_host}:{email.smtp_port}"
        redirect = shlex.quote(f"</dev/tcp/{email.smtp_host}/{email.smtp_port}")
        result = ctx.remote.run(f"timeout 5 bash -c {redirect}", timeout=20, quiet=True)
        if result.ok:
            return True, f"{target} reachable from the server"
        return False, f"WARN: {target} not reachable from the server (exit {result.code}) - check the provider's outbound SMTP rules and the firewall"

    # --- check -----------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """SES: identity verified? SMTP: all five fields set (plus a read-only reachability note)."""
        mode = ctx.plan.email.mode
        if mode == "ses":
            return self._check_ses(ctx)
        if mode == "smtp":
            return self._check_smtp(ctx)
        return CheckResult.ok("e-mail transport disabled")

    def _check_ses(self, ctx: Context) -> CheckResult:
        plan = ctx.plan
        email = plan.email
        region = plan.ses_region
        identity, kind = self.ses_identity(plan)
        in_place: list[str] = []
        pending: list[str] = []
        if email.ses_configuration_set:
            exists = self._call(ctx.aws.ses_configuration_set_exists, email.ses_configuration_set, region=region)
            if exists:
                in_place.append(f"configuration set {email.ses_configuration_set} exists")
            else:
                pending.append(f"SES configuration set {email.ses_configuration_set} does not exist in {region} - create it first or clear the field")
        state = self._call(ctx.aws.get_ses_identity, identity, region=region)
        if state is None:
            note = f"SES {kind} identity {identity} not created in {region}"
            if email.ses_verify_identity:
                pending.append(note)
            else:
                ctx.warn(f"{note} and verification is disabled in the plan - the backend cannot send until it is verified")
                in_place.append(note + " (verification disabled in the plan)")
        else:
            self._capture(ctx, state)
            if state.status == "SUCCESS":
                in_place.append(f"SES {kind} identity {identity} verified in {region}")
            elif email.ses_verify_identity:
                pending.append(self._pending_hint(state))
            else:
                ctx.warn(f"SES identity {identity}: {self._pending_hint(state)}")
                in_place.append(self._pending_hint(state))
        detail = " · ".join(in_place)
        if pending:
            return CheckResult.needs_apply("; ".join(pending) + (f" · {detail}" if detail else ""))
        return CheckResult.ok(detail)

    def _check_smtp(self, ctx: Context) -> CheckResult:
        plan = ctx.plan
        email = plan.email
        ctx.remember_secret(email.smtp_password)
        missing = self._smtp_missing(plan)
        if missing:
            return CheckResult.needs_apply(f"SMTP settings incomplete: {', '.join(missing)} (fill them in the plan - nothing remote to provision)")
        reachable, note = self._smtp_probe(ctx)
        if not reachable:
            ctx.warn(note)
        return CheckResult.ok(f"SMTP via {email.smtp_host}:{email.smtp_port} as {email.smtp_username}, from {email.smtp_from_address} · {note}")

    # --- apply -----------------------------------------------------------------------------------

    def apply(self, ctx: Context) -> None:
        """SES: create or adopt the identity and capture its DKIM records. SMTP: nothing to do."""
        plan = ctx.plan
        email = plan.email
        if email.mode == "smtp":
            missing = self._smtp_missing(plan)
            if missing:
                raise StepError(f"SMTP settings incomplete: {', '.join(missing)} - the backend needs all five smtp-* keys")
            ctx.info(f"SMTP transport {email.smtp_host}:{email.smtp_port}: nothing to provision on the server (keys are written by the configuration step)")
            return
        if email.mode != "ses":
            return
        region = plan.ses_region
        identity, kind = self.ses_identity(plan)
        if email.ses_configuration_set:
            exists = self._call(ctx.aws.ses_configuration_set_exists, email.ses_configuration_set, region=region)
            if not exists:
                raise StepError(f"SES configuration set {email.ses_configuration_set} does not exist in {region} - create it first or clear the field")
        if not email.ses_verify_identity:
            ctx.info(f"SES identity verification for {identity} skipped by the plan")
            return
        state = self._call(ctx.aws.ensure_ses_identity, identity, region=region)
        self._capture(ctx, state)
        if state.created:
            ctx.info(f"created SES {kind} identity {identity} in {region}")
        else:
            ctx.info(f"SES {kind} identity {identity} already exists in {region} (status {state.status})")
        if kind == "domain":
            if state.dkim_records:
                ctx.info(f"publish these DKIM CNAME records for {identity} (also listed on the summary page):")
                for name, value in state.dkim_records:
                    ctx.info(f"  {name}  CNAME  {value}")
            ctx.info(f"  suggested as well: _dmarc.{identity}  TXT  \"v=DMARC1; p=none\"")
        else:
            ctx.info(f"AWS sends a verification mail to {identity} - open it and confirm the link before the backend can send")
        if state.created:
            ctx.warn("a new SES account starts in the sandbox: 200 mails/day and every recipient must be verified until AWS grants production access")

    # --- verify ----------------------------------------------------------------------------------

    def verify(self, ctx: Context) -> VerifyResult:
        """SES: verified → ok; pending → ok with the outstanding action. SMTP: best-effort TCP probe."""
        plan = ctx.plan
        email = plan.email
        if email.mode == "smtp":
            reachable, note = self._smtp_probe(ctx)
            if not reachable:
                ctx.warn(note)
            return VerifyResult(True, note)
        if email.mode != "ses":
            return VerifyResult(True, "e-mail transport disabled")
        region = plan.ses_region
        identity, kind = self.ses_identity(plan)
        state = self._call(ctx.aws.get_ses_identity, identity, region=region)
        if state is None:
            if email.ses_verify_identity:
                return VerifyResult(False, f"SES identity {identity} is still missing in {region} after apply")
            return VerifyResult(True, f"SES identity {identity} not created (verification disabled in the plan)")
        self._capture(ctx, state)
        if state.status == "SUCCESS":
            return VerifyResult(True, f"SES {kind} identity {identity} verified in {region}")
        outstanding = "DKIM records" if kind == "domain" else "verification mail"
        return VerifyResult(True, f"pending — {outstanding} outstanding: {self._pending_hint(state)}")

    # --- summary ---------------------------------------------------------------------------------

    def describe(self, plan: InstallPlan) -> str:
        """One line mirroring :meth:`apply` for the summary page."""
        email = plan.email
        if email.mode == "ses":
            identity, kind = self.ses_identity(plan)
            if not email.ses_verify_identity:
                line = f"SES: use identity {identity} as is (verification skipped)"
            elif kind == "domain":
                line = f"SES: verify domain identity {identity} in {plan.ses_region} (Easy DKIM: 3 CNAME records to publish)"
            else:
                line = f"SES: verify address identity {identity} in {plan.ses_region} (verification mail from AWS)"
            if email.ses_configuration_set:
                line += f", require configuration set {email.ses_configuration_set}"
            return line
        if email.mode == "smtp":
            return f"SMTP {email.smtp_host}:{email.smtp_port}: nothing to provision, probe reachability from the server"
        return "e-mail transport disabled (codes appear in the server log only)"

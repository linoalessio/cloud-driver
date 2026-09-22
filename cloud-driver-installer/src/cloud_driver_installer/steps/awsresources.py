"""AWS: the KMS key, the buckets, the least-privilege runtime identity, and the e-mail transport.

The provisioning itself runs on the operator's machine with the operator's own credentials. What
reaches the server is only the access key of the IAM user created here, written to
``/root/.aws/credentials`` - the JVM resolves it through the SDK's default provider chain and the
region is passed per client, so nothing AWS-related ever belongs in ``configuration.json``.
"""

from __future__ import annotations

from cloud_driver_installer.aws import AwsError, render_aws_config, render_aws_credentials
from cloud_driver_installer.config_files import real_value
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan

AWS_DIR = "/root/.aws"
CREDENTIALS_FILE = f"{AWS_DIR}/credentials"
CONFIG_FILE = f"{AWS_DIR}/config"


def remote_access_key_id(ctx: Context) -> str:
    """The ``aws_access_key_id`` in the server's ``[default]`` profile, or ``""``."""
    text = ctx.remote.read_text(CREDENTIALS_FILE) or ""
    section = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("["):
            section = stripped == "[default]"
        elif section and stripped.startswith("aws_access_key_id"):
            return stripped.split("=", 1)[1].strip()
    return ""


class AwsStep(Step):
    """KMS key, content bucket, backup bucket, IAM user + policy, and the server's credentials file."""

    id = "aws"
    title = "AWS"
    mandatory = True
    depends_on = ("server",)

    def check(self, ctx: Context) -> CheckResult:
        plan = ctx.plan.aws
        try:
            identity = ctx.aws.whoami()
        except (AwsError, StepError) as exc:
            return CheckResult.needs_apply(str(exc))
        parts = [f"account {identity.account}"]
        pending: list[str] = []

        try:
            key = ctx.aws.describe_kms_key(plan.kms_key_id if plan.kms_mode == "existing" else plan.kms_alias)
            ctx.secrets.kms_key_id, ctx.secrets.kms_key_arn = key.config_value, key.arn
            parts.append(f"KMS {key.config_value}")
        except AwsError:
            if plan.kms_mode == "existing":
                return CheckResult.needs_apply(f"KMS key {plan.kms_key_id} not found in {plan.region}")
            pending.append(f"create KMS key {plan.kms_alias}")

        if plan.s3_enabled:
            try:
                ctx.aws.head_bucket(plan.s3_bucket)
                ctx.secrets.s3_bucket = plan.s3_bucket
                parts.append(f"bucket {plan.s3_bucket}")
            except AwsError:
                if plan.s3_mode == "existing":
                    return CheckResult.needs_apply(f"bucket {plan.s3_bucket} not found")
                pending.append(f"create bucket {plan.s3_bucket}")
            if ctx.plan.app.backup_offsite:
                try:
                    ctx.aws.head_bucket(plan.effective_backup_bucket)
                except AwsError:
                    pending.append(f"create backup bucket {plan.effective_backup_bucket}")

        if plan.server_identity == "iam_user":
            on_server = remote_access_key_id(ctx)
            try:
                keys = ctx.aws.list_access_keys(plan.iam_user_name)
            except AwsError:
                keys = []
                pending.append(f"create IAM user {plan.iam_user_name}")
            if plan.rotate_server_key:
                pending.append("rotate the server's access key")
            elif on_server and on_server in keys:
                parts.append(f"server uses {on_server}")
            elif ctx.secrets.server_secret_access_key:
                pending.append("write the access key created in this run to the server")
            else:
                pending.append("create an access key for the server")
        elif not ctx.remote.exists(CREDENTIALS_FILE):
            pending.append("write your credentials to the server")

        detail = " · ".join(parts + pending)
        return CheckResult.needs_apply(detail) if pending else CheckResult.ok(detail)

    def apply(self, ctx: Context) -> None:
        plan = ctx.plan.aws
        try:
            self._apply(ctx)
        except AwsError as exc:
            raise StepError(str(exc)) from exc
        ctx.info(f"[AWS] runtime identity ready for KMS {ctx.secrets.kms_key_id}" + (f", bucket {ctx.secrets.s3_bucket}" if plan.s3_enabled else ""))

    def _apply(self, ctx: Context) -> None:
        plan = ctx.plan.aws
        existing_key = real_value(ctx.discovered.existing_config, "aws-kms-key-id")

        if plan.kms_mode == "create":
            key = ctx.aws.ensure_kms_key(alias=plan.kms_alias, enable_rotation=plan.kms_enable_rotation)
        else:
            key = ctx.aws.describe_kms_key(plan.kms_key_id)
        ctx.secrets.kms_key_id, ctx.secrets.kms_key_arn = key.config_value, key.arn
        if existing_key and str(existing_key) != key.config_value and not plan.allow_kms_change:
            raise StepError(
                f"this server encrypts with KMS key {existing_key} but the plan names {key.config_value}; "
                "existing rows can only be decrypted with the original key - select it, or acknowledge the change"
            )
        if key.created:
            ctx.info(f"[AWS] created KMS key {key.key_id} with alias {plan.kms_alias}")

        bucket_name = None
        if plan.s3_enabled:
            bucket = (
                ctx.aws.ensure_bucket(plan.s3_bucket, abort_multipart_days=plan.s3_abort_multipart_days, versioning=plan.s3_versioning)
                if plan.s3_mode == "create"
                else ctx.aws.head_bucket(plan.s3_bucket)
            )
            ctx.secrets.s3_bucket = bucket.name
            bucket_name = bucket.name
            if bucket.created:
                ctx.info(f"[AWS] created bucket {bucket.name} (public access blocked, SSE-S3, owner-enforced)")

        backup_bucket = None
        if plan.s3_enabled and ctx.plan.app.backup_offsite:
            backup = ctx.aws.ensure_backup_bucket(plan.effective_backup_bucket, retention_days=plan.backup_retention_days)
            backup_bucket = backup.name
            if backup.created:
                ctx.info(f"[AWS] created backup bucket {backup.name} (archives expire after {plan.backup_retention_days} days)")

        ctx.check_cancelled()
        if plan.server_identity == "iam_user":
            if ctx.aws.ensure_server_user(plan.iam_user_name):
                ctx.info(f"[AWS] created IAM user {plan.iam_user_name}")
            ctx.aws.put_server_policy(
                plan.iam_user_name,
                kms_key_arn=key.arn,
                bucket=bucket_name,
                ses=ctx.plan.email.mode == "ses",
                backup_bucket=backup_bucket,
            )
            self._ensure_access_key(ctx, kms_key_id=key.config_value, bucket=bucket_name)
        else:
            credentials = self._operator_credentials(ctx)
            self._write_remote_credentials(ctx, *credentials)

    def verify(self, ctx: Context) -> VerifyResult:
        on_server = remote_access_key_id(ctx)
        if not on_server:
            return VerifyResult(False, f"{CREDENTIALS_FILE} carries no access key")
        if ctx.secrets.server_secret_access_key:
            try:
                arn = ctx.aws.verify_server_key(
                    ctx.secrets.server_access_key_id,
                    ctx.secrets.server_secret_access_key,
                    kms_key_id=ctx.secrets.kms_key_id,
                    bucket=ctx.secrets.s3_bucket or None,
                    ses=ctx.plan.email.mode == "ses",
                )
            except AwsError as exc:
                return VerifyResult(False, str(exc))
            self._retire_previous_key(ctx)
            return VerifyResult(True, f"{arn} can encrypt/decrypt with {ctx.secrets.kms_key_id}")
        return VerifyResult(True, f"server uses access key {on_server} (secret not held by this run)")

    def describe(self, plan: InstallPlan) -> str:
        aws = plan.aws
        parts = [f"KMS {'create ' + aws.kms_alias if aws.kms_mode == 'create' else aws.kms_key_id}"]
        if aws.s3_enabled:
            parts.append(f"bucket {'create ' if aws.s3_mode == 'create' else ''}{aws.s3_bucket}")
            if plan.app.backup_offsite:
                parts.append(f"backup bucket {aws.effective_backup_bucket}")
        if aws.server_identity == "iam_user":
            parts.append(f"IAM user {aws.iam_user_name} + access key -> {CREDENTIALS_FILE}")
        else:
            parts.append(f"your own credentials -> {CREDENTIALS_FILE}")
        return " · ".join(parts)

    # --- internals -------------------------------------------------------------------------------

    def _ensure_access_key(self, ctx: Context, *, kms_key_id: str, bucket: str | None) -> None:
        plan = ctx.plan.aws
        on_server = remote_access_key_id(ctx)
        keys = ctx.aws.list_access_keys(plan.iam_user_name)
        if ctx.secrets.server_secret_access_key:
            pass  # a Retry: reuse the key this run already created rather than creating a second one
        elif on_server in keys and not plan.rotate_server_key:
            ctx.secrets.server_access_key_id, ctx.secrets.server_key_kept = on_server, True
            ctx.info(f"[AWS] keeping the access key already on the server ({on_server})")
            return
        else:
            if len(keys) >= 2:
                # Two keys is IAM's hard limit: drop the one the server does not use.
                spare = next((key for key in keys if key != on_server), keys[0])
                ctx.aws.delete_access_key(plan.iam_user_name, spare)
                ctx.info(f"[AWS] deleted the unused access key {spare} to make room")
            created = ctx.aws.create_access_key(plan.iam_user_name, rotate=plan.rotate_server_key)
            ctx.secrets.server_access_key_id = created.access_key_id
            ctx.secrets.server_secret_access_key = created.secret_access_key
            ctx.remember_secret(created.secret_access_key)
            ctx.info(f"[AWS] created access key {created.access_key_id} for {plan.iam_user_name}")
        # The new key is written (and, in verify, proven) before the previous one is retired.
        self._write_remote_credentials(ctx, ctx.secrets.server_access_key_id, ctx.secrets.server_secret_access_key)
        ctx.secrets.server_key_kept = False
        self._previous_key = on_server if on_server and on_server != ctx.secrets.server_access_key_id else ""

    def _retire_previous_key(self, ctx: Context) -> None:
        previous = getattr(self, "_previous_key", "")
        if previous and ctx.plan.aws.rotate_server_key and ctx.plan.aws.server_identity == "iam_user":
            try:
                ctx.aws.delete_access_key(ctx.plan.aws.iam_user_name, previous)
                ctx.info(f"[AWS] retired the previous access key {previous}")
            except AwsError as exc:
                ctx.warn(f"[AWS] could not delete the previous access key {previous}: {exc}")
        self._previous_key = ""

    def _operator_credentials(self, ctx: Context) -> tuple[str, str]:
        plan = ctx.plan.aws
        if plan.credential_source == "keys":
            return plan.access_key_id, plan.secret_access_key
        import boto3

        frozen = boto3.session.Session(profile_name=plan.profile_name or None).get_credentials()
        if frozen is None:
            raise StepError(f"profile {plan.profile_name} has no credentials to copy to the server")
        ctx.warn("[AWS] copying your own credentials to the server - they carry every permission your account has")
        ctx.remember_secret(frozen.secret_key)
        return frozen.access_key, frozen.secret_key

    def _write_remote_credentials(self, ctx: Context, access_key_id: str, secret_access_key: str) -> None:
        ctx.remote.mkdirs(AWS_DIR, mode=0o700)
        ctx.remote.put_text(CREDENTIALS_FILE, render_aws_credentials(access_key_id, secret_access_key, ctx.remote.read_text(CREDENTIALS_FILE)), mode=0o600)
        ctx.remote.put_text(CONFIG_FILE, render_aws_config(ctx.plan.aws.region, ctx.remote.read_text(CONFIG_FILE)), mode=0o600)
        ctx.discovered.aws_credentials_present = True
        ctx.info(f"[AWS] wrote {CREDENTIALS_FILE} (0600) for access key {access_key_id}")


class EmailStep(Step):
    """The transport verification codes go out through: SES (preferred) or an SMTP relay."""

    id = "email"
    title = "E-mail"
    depends_on = ("aws",)

    def enabled(self, plan: InstallPlan) -> bool:
        return plan.email.mode != "none"

    def identity(self, plan: InstallPlan) -> str:
        """The SES identity to verify: the sender's domain (Easy DKIM) or the address itself."""
        address = plan.email.ses_from_address
        if plan.email.ses_identity_mode == "domain" and "@" in address:
            return address.split("@", 1)[1]
        return address

    def check(self, ctx: Context) -> CheckResult:
        plan = ctx.plan.email
        if plan.mode == "smtp":
            return CheckResult.ok(f"SMTP relay {plan.smtp_host}:{plan.smtp_port} as {plan.smtp_username}")
        identity = self.identity(ctx.plan)
        try:
            if plan.ses_configuration_set and not ctx.aws.ses_configuration_set_exists(plan.ses_configuration_set, region=ctx.plan.ses_region):
                return CheckResult.needs_apply(f"configuration set {plan.ses_configuration_set} does not exist in {ctx.plan.ses_region} - every send would fail")
            state = ctx.aws.get_ses_identity(identity, region=ctx.plan.ses_region)
        except AwsError as exc:
            return CheckResult.needs_apply(str(exc))
        if state is None:
            return CheckResult.needs_apply(f"SES identity {identity} does not exist in {ctx.plan.ses_region}")
        if state.status == "SUCCESS":
            return CheckResult.ok(f"SES identity {identity} verified in {ctx.plan.ses_region}")
        ctx.secrets.ses_dkim_records = state.dkim_records
        return CheckResult.needs_apply(f"SES identity {identity} is {state.status.lower()}")

    def apply(self, ctx: Context) -> None:
        plan = ctx.plan.email
        if plan.mode == "smtp":
            return
        if plan.ses_configuration_set and not ctx.aws.ses_configuration_set_exists(plan.ses_configuration_set, region=ctx.plan.ses_region):
            raise StepError(f"SES configuration set {plan.ses_configuration_set} does not exist in {ctx.plan.ses_region} - create it first or clear the field")
        if not plan.ses_verify_identity:
            return
        identity = self.identity(ctx.plan)
        try:
            state = ctx.aws.ensure_ses_identity(identity, region=ctx.plan.ses_region)
        except AwsError as exc:
            raise StepError(str(exc)) from exc
        ctx.secrets.ses_dkim_records = state.dkim_records
        if state.kind == "address" and state.created:
            ctx.info(f"[E-mail] SES sent a verification mail to {identity} - open it to finish verification")
        for name, value in state.dkim_records:
            ctx.info(f"[E-mail] publish DKIM record: {name} CNAME {value}")

    def verify(self, ctx: Context) -> VerifyResult:
        plan = ctx.plan.email
        if plan.mode == "smtp":
            reachable = ctx.remote.run_ok(f"timeout 5 bash -c '</dev/tcp/{plan.smtp_host}/{plan.smtp_port}'")
            return VerifyResult(True, f"{plan.smtp_host}:{plan.smtp_port} " + ("reachable from the server" if reachable else "NOT reachable from the server - check outbound port 587"))
        identity = self.identity(ctx.plan)
        try:
            state = ctx.aws.get_ses_identity(identity, region=ctx.plan.ses_region)
        except AwsError as exc:
            return VerifyResult(False, str(exc))
        if state and state.status == "SUCCESS":
            return VerifyResult(True, f"{identity} verified for sending")
        # A pending identity is the operator's DNS/mailbox to finish, not a failed install.
        return VerifyResult(True, f"{identity} verification pending - publish the DKIM records or confirm the verification mail")

    def describe(self, plan: InstallPlan) -> str:
        if plan.email.mode == "smtp":
            return f"write the smtp-* keys for {plan.email.smtp_host}:{plan.email.smtp_port}"
        return f"verify the SES identity {self.identity(plan)} in {plan.ses_region} and write the aws-ses-* keys"

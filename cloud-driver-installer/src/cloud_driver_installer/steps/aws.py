"""AWS step: the KMS key, the S3 buckets, the runtime IAM identity, and the credential files on the server.

The provisioning itself runs on the operator's machine with the operator's own credentials (see
:mod:`cloud_driver_installer.aws`); the only thing that reaches the server is the runtime
identity's access key, written to ``/root/.aws/credentials`` ``[default]`` next to a
``/root/.aws/config`` carrying the region. The home directory is always ``/root`` because the JVM
runs as root inside ``screen`` and resolves its credentials through the SDK's default provider
chain - never from ``configuration.json`` (docs/requirements.md #3.1/#4.1).

This is the automated form of the follow-up checklist ``shell/provision-root-server.sh`` prints
(KMS key + alias, bucket, an identity with exactly the listed permissions, a credentials file on
the host) and follows the same "never rotate what you cannot write down" rule the script applies
to its database passwords: an access key that is already on the server and still belongs to the
IAM user is kept unless the plan asks for a rotation, and a rotation creates the new key, writes
the file, proves the new key works and only then retires the previous one.

Two guards protect existing data: the KEK named in the server's ``configuration.json`` is never
silently replaced (``allow_kms_change`` must be ticked, and an alias that resolves to the same key
counts as unchanged), and hardening calls only ever touch a bucket created in this run.
"""

from __future__ import annotations

from typing import Any, Callable, TypeVar

from cloud_driver_installer.aws import AwsError, KmsKey, render_aws_config, render_aws_credentials
from cloud_driver_installer.config_files import real_value
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError

#: The runtime identity's AWS home: the JVM runs as root, whoever the installer logs in as.
AWS_HOME = "/root/.aws"
#: ``[default]`` access key of the runtime identity (mode 0600).
CREDENTIALS_PATH = f"{AWS_HOME}/credentials"
#: ``[default]`` region (mode 0600 as well - it is written next to the secret).
CONFIG_PATH = f"{AWS_HOME}/config"

T = TypeVar("T")


def parse_ini_section(text: str | None, section: str = "default") -> dict[str, str]:
    """``key = value`` pairs of one ``[section]`` of an INI-style file, keys lower-cased.

    Tolerant on purpose: a hand-edited credentials file with comments, blank lines or odd
    spacing must still yield its access key so the installer can decide whether to keep it.
    """
    values: dict[str, str] = {}
    if not text:
        return values
    inside = False
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("[") and line.endswith("]"):
            inside = line[1:-1].strip() == section
            continue
        if inside and "=" in line:
            key, _, value = line.partition("=")
            values[key.strip().lower()] = value.strip()
    return values


def _profile_credentials(profile_name: str) -> tuple[str, str]:
    """Long-lived access key of a local AWS profile (``reuse`` identity with the ``profile`` source).

    Imports boto3 lazily so the step module stays importable in tests that never touch AWS.
    Temporary (session-token) credentials are refused: they expire, and the server's
    ``credentials`` file has no place for the token.
    """
    import boto3
    from botocore.exceptions import BotoCoreError, ProfileNotFound

    label = profile_name or "default"
    try:
        session = boto3.session.Session(profile_name=profile_name or None)
        credentials = session.get_credentials()
    except (BotoCoreError, ProfileNotFound) as exc:
        raise StepError(f"reading the local AWS profile {label!r}: {exc}") from exc
    if credentials is None:
        raise StepError(f"the local AWS profile {label!r} holds no credentials")
    frozen = credentials.get_frozen_credentials()
    if frozen.token:
        raise StepError(
            f"the local AWS profile {label!r} yields temporary session credentials - "
            "they expire and cannot be reused on the server; create a dedicated IAM user instead"
        )
    return frozen.access_key, frozen.secret_key


class AwsStep(Step):
    """KMS key + alias, S3 content/backup buckets, least-privilege IAM user with access key, ``/root/.aws/*``."""

    id = "aws"
    title = "AWS"
    depends_on = ("server",)
    mandatory = True

    #: Retry budget of ``verify_server_key`` - a freshly created IAM access key takes a few seconds
    #: to propagate. Tests may lower them; the fake provisioner ignores them.
    key_probe_attempts: int = 8
    key_probe_delay: float = 3.0

    # --- helpers ---------------------------------------------------------------------------------

    @staticmethod
    def _call(fn: Callable[..., T], *args: Any, **kwargs: Any) -> T:
        """Run one provisioner call, turning its (already readable) ``AwsError`` into a ``StepError``."""
        try:
            return fn(*args, **kwargs)
        except AwsError as exc:
            raise StepError(str(exc)) from exc

    @staticmethod
    def _kms_reference(plan: InstallPlan) -> str:
        """What ``aws-kms-key-id`` carries: the alias for created/adopted keys, the given id otherwise."""
        return plan.aws.kms_alias if plan.aws.kms_mode == "create" else plan.aws.kms_key_id

    @staticmethod
    def _backup_bucket(plan: InstallPlan) -> str:
        """The dedicated off-site backup bucket, or ``""`` when the plan does not copy backups off-site."""
        if plan.app.backup_offsite and plan.aws.s3_enabled:
            return plan.aws.effective_backup_bucket
        return ""

    @staticmethod
    def _remote_credentials(ctx: Context) -> tuple[str, str]:
        """``(access key id, secret)`` of the server's current ``[default]`` section (blank when absent)."""
        section = parse_ini_section(ctx.remote.read_text(CREDENTIALS_PATH))
        key_id = section.get("aws_access_key_id", "")
        secret = section.get("aws_secret_access_key", "")
        ctx.remember_secret(secret)
        return key_id, secret

    def _reuse_credentials(self, ctx: Context) -> tuple[str, str]:
        """The operator's own access key for ``server_identity == "reuse"`` (typed keys or a local profile)."""
        aws = ctx.plan.aws
        if aws.credential_source == "keys":
            if not aws.access_key_id or not aws.secret_access_key:
                raise StepError("AWS: reusing the operator's credentials needs the access key id and secret")
            if aws.session_token:
                raise StepError(
                    "AWS: the typed credentials include a session token - temporary credentials expire and "
                    "cannot be reused on the server; create a dedicated IAM user instead"
                )
            ctx.remember_secret(aws.secret_access_key)
            return aws.access_key_id, aws.secret_access_key
        key_id, secret = _profile_credentials(aws.profile_name)
        ctx.remember_secret(secret)
        return key_id, secret

    @staticmethod
    def _store_server_key(ctx: Context, key_id: str, secret: str, *, kept: bool) -> None:
        ctx.remember_secret(secret)
        ctx.secrets.server_access_key_id = key_id
        ctx.secrets.server_secret_access_key = secret
        ctx.secrets.server_key_kept = kept

    def _guard_kms_change(self, ctx: Context, planned_key: KmsKey | None) -> str:
        """Refuse to swap the KEK the server's ``configuration.json`` already names.

        Returns a note for the detail line. An alias and the key id it points at count as the
        same key. With ``allow_kms_change`` ticked the swap is logged loudly instead of refused.
        """
        plan = ctx.plan
        planned_ref = self._kms_reference(plan)
        existing = real_value(ctx.discovered.existing_config or {}, "aws-kms-key-id")
        if not existing:
            return ""
        existing_ref = str(existing)
        same = existing_ref == planned_ref
        if not same and planned_key is not None:
            try:
                same = ctx.aws.describe_kms_key(existing_ref).key_id == planned_key.key_id
            except AwsError as exc:
                ctx.warn(f"the KMS key {existing_ref} named in the server's configuration.json cannot be resolved: {exc}")
        if same:
            return f"KEK {existing_ref} unchanged"
        if plan.aws.allow_kms_change:
            ctx.warn(
                f"replacing KEK {existing_ref} (server's configuration.json) with {planned_ref} as acknowledged - rows "
                "encrypted under the old key stay readable only while it exists and the runtime identity may decrypt with it"
            )
            return f"KEK change {existing_ref} -> {planned_ref} acknowledged"
        raise StepError(
            f"the server's configuration.json encrypts with KMS key {existing_ref}, the plan names {planned_ref}; existing "
            "rows stay readable only with the original key - select it under 'Use existing key' or tick the acknowledgement"
        )

    # --- check -----------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """Read-only: credentials usable, KMS/buckets/IAM user present, the server's credentials file current."""
        plan = ctx.plan
        aws = plan.aws
        try:
            who = ctx.aws.whoami()
        except (AwsError, StepError) as exc:
            # The operator may still be entering keys - report, do not fail.
            return CheckResult.needs_apply(f"AWS credentials not usable yet: {exc}")
        in_place: list[str] = [f"account {who.account}"]
        pending: list[str] = []

        # KMS (+ the KEK guard, which raises)
        kms_ref = self._kms_reference(plan)
        planned_key: KmsKey | None = None
        try:
            planned_key = ctx.aws.describe_kms_key(kms_ref)
        except AwsError as exc:
            if aws.kms_mode == "create":
                pending.append(f"create KMS key {aws.kms_alias}")
                ctx.debug(f"KMS alias {aws.kms_alias} not resolvable yet: {exc}")
            else:
                pending.append(f"KMS key {aws.kms_key_id} not usable ({exc})")
        else:
            ctx.secrets.kms_key_id = kms_ref
            ctx.secrets.kms_key_arn = planned_key.arn
            in_place.append(f"KMS {kms_ref} enabled")
        note = self._guard_kms_change(ctx, planned_key)
        if note:
            in_place.append(note)

        # S3 content bucket + off-site backup bucket
        if aws.s3_enabled:
            try:
                bucket = ctx.aws.head_bucket(aws.s3_bucket)
            except AwsError as exc:
                if aws.s3_mode == "create":
                    pending.append(f"create bucket {aws.s3_bucket}")
                    ctx.debug(f"bucket {aws.s3_bucket} not accessible yet: {exc}")
                else:
                    pending.append(f"bucket {aws.s3_bucket} not accessible ({exc})")
            else:
                ctx.secrets.s3_bucket = aws.s3_bucket
                in_place.append(f"bucket {aws.s3_bucket} ({bucket.region})")
            backup = self._backup_bucket(plan)
            if backup:
                try:
                    ctx.aws.head_bucket(backup)
                except AwsError as exc:
                    pending.append(f"create backup bucket {backup}")
                    ctx.debug(f"backup bucket {backup} not accessible yet: {exc}")
                else:
                    in_place.append(f"backup bucket {backup}")
        else:
            in_place.append("S3 disabled")

        # runtime identity + the key on the server
        file_key_id, file_secret = self._remote_credentials(ctx)
        if aws.server_identity == "iam_user":
            try:
                current_keys = ctx.aws.list_access_keys(aws.iam_user_name)
            except AwsError as exc:
                pending.append(f"create IAM user {aws.iam_user_name} + policy + access key")
                ctx.debug(f"IAM user {aws.iam_user_name} not resolvable: {exc}")
            else:
                in_place.append(f"IAM user {aws.iam_user_name} ({len(current_keys)} access key(s))")
                if aws.rotate_server_key:
                    pending.append("rotate the server access key")
                elif file_key_id and file_key_id in current_keys and file_secret:
                    in_place.append(f"server key {file_key_id} kept")
                    self._store_server_key(ctx, file_key_id, file_secret, kept=True)
                elif len(current_keys) >= 2:
                    pending.append(
                        f"IAM user {aws.iam_user_name} already has two access keys and neither is on the server - "
                        "tick 'rotate' so the oldest is replaced"
                    )
                else:
                    pending.append("create the server access key")
        else:
            try:
                key_id, secret = self._reuse_credentials(ctx)
            except StepError as exc:
                pending.append(str(exc))
            else:
                if file_key_id == key_id and file_secret == secret:
                    in_place.append(f"server key {key_id} (operator's own) in place")
                    self._store_server_key(ctx, key_id, secret, kept=True)
                else:
                    pending.append(f"copy the operator's access key {key_id} to the server")

        # the files themselves
        if not ctx.remote.exists(CREDENTIALS_PATH):
            pending.append(f"write {CREDENTIALS_PATH}")
        config = parse_ini_section(ctx.remote.read_text(CONFIG_PATH))
        if config.get("region") != aws.region:
            pending.append(f"write {CONFIG_PATH} (region {aws.region})")
        else:
            in_place.append(f"{CONFIG_PATH} region {aws.region}")

        detail = " · ".join(in_place)
        if pending:
            return CheckResult.needs_apply("will " + ", ".join(pending) + f" · {detail}")
        return CheckResult.ok(detail)

    # --- apply -----------------------------------------------------------------------------------

    def apply(self, ctx: Context) -> None:
        """Provision (idempotently), write the credential files, then retire a rotated key."""
        ctx.progress(0.0, "AWS: KMS key")
        key = self._apply_kms(ctx)
        self._guard_kms_change(ctx, key)
        ctx.check_cancelled()
        ctx.progress(0.2, "AWS: S3 buckets")
        bucket_name, backup_name = self._apply_buckets(ctx)
        ctx.check_cancelled()
        ctx.progress(0.45, "AWS: runtime identity")
        key_id, secret, previous = self._apply_identity(ctx, key, bucket_name, backup_name)
        ctx.check_cancelled()
        ctx.progress(0.7, "AWS: credential files on the server")
        self._write_credential_files(ctx, key_id, secret)
        if previous:
            ctx.check_cancelled()
            ctx.progress(0.85, "AWS: proving the new key before retiring the old one")
            self._retire_previous_key(ctx, key_id, secret, previous, bucket_name)
        ctx.progress(1.0, "AWS: done")

    def _apply_kms(self, ctx: Context) -> KmsKey:
        aws = ctx.plan.aws
        if aws.kms_mode == "create":
            key = self._call(ctx.aws.ensure_kms_key, alias=aws.kms_alias, enable_rotation=aws.kms_enable_rotation)
            if key.created:
                ctx.info(f"created KMS key {key.key_id} with alias {aws.kms_alias} (symmetric, cloud-driver KEK)")
            else:
                ctx.info(f"KMS alias {aws.kms_alias} already exists - adopted key {key.key_id}")
            if aws.kms_enable_rotation and key.rotation_enabled is None:
                ctx.warn(f"could not enable automatic rotation on {aws.kms_alias} (kms:EnableKeyRotation denied?) - not fatal, the key works without it")
        else:
            key = self._call(ctx.aws.describe_kms_key, aws.kms_key_id)
            ctx.info(f"using existing KMS key {aws.kms_key_id} ({key.key_id})")
        ctx.secrets.kms_key_id = self._kms_reference(ctx.plan)
        ctx.secrets.kms_key_arn = key.arn
        return key

    def _apply_buckets(self, ctx: Context) -> tuple[str | None, str | None]:
        """Content bucket (create or adopt / head) and the off-site backup bucket; returns their names."""
        plan = ctx.plan
        aws = plan.aws
        if not aws.s3_enabled:
            ctx.secrets.s3_bucket = ""
            ctx.debug("S3 disabled in the plan - no bucket, no S3 permissions")
            return None, None
        if aws.s3_mode == "create":
            bucket = self._call(
                ctx.aws.ensure_bucket,
                aws.s3_bucket,
                abort_multipart_days=aws.s3_abort_multipart_days,
                versioning=aws.s3_versioning,
            )
            if bucket.created:
                extras = "public access blocked, SSE-S3, owner-enforced"
                if aws.s3_abort_multipart_days > 0:
                    extras += f", abandoned multipart uploads aborted after {aws.s3_abort_multipart_days} d"
                if aws.s3_versioning:
                    extras += ", versioning on"
                ctx.info(f"created bucket {aws.s3_bucket} in {bucket.region} ({extras})")
            else:
                ctx.info(f"bucket {aws.s3_bucket} already owned by this account - adopted unchanged")
        else:
            bucket = self._call(ctx.aws.head_bucket, aws.s3_bucket)
            ctx.info(f"using existing bucket {aws.s3_bucket} ({bucket.region})")
            if bucket.region != aws.region:
                ctx.warn(f"bucket {aws.s3_bucket} lives in {bucket.region} but the plan's region is {aws.region} - aws-s3-region must name {bucket.region}")
        ctx.secrets.s3_bucket = aws.s3_bucket

        backup_name = self._backup_bucket(plan)
        if not backup_name:
            return aws.s3_bucket, None
        backup = self._call(ctx.aws.ensure_backup_bucket, backup_name, retention_days=aws.backup_retention_days)
        if backup.created:
            ctx.info(f"created backup bucket {backup_name} (private, SSE-S3, archives expire after {aws.backup_retention_days} d)")
        else:
            ctx.info(f"backup bucket {backup_name} already owned by this account - adopted unchanged")
        return aws.s3_bucket, backup_name

    def _apply_identity(self, ctx: Context, key: KmsKey, bucket_name: str | None, backup_name: str | None) -> tuple[str, str, str]:
        """Resolve the runtime credentials; returns ``(key id, secret, previous key id to retire or "")``."""
        plan = ctx.plan
        aws = plan.aws
        if aws.server_identity == "reuse":
            key_id, secret = self._reuse_credentials(ctx)
            source = f"local profile {aws.profile_name or 'default'!r}" if aws.credential_source == "profile" else "the typed access key"
            ctx.warn(
                f"copying the operator's own credentials ({source}, key {key_id}) to the server as its runtime identity - "
                "they carry every permission you have; a dedicated least-privilege IAM user is the recommended setup"
            )
            self._store_server_key(ctx, key_id, secret, kept=False)
            return key_id, secret, ""

        name = aws.iam_user_name
        created = self._call(ctx.aws.ensure_server_user, name)
        ctx.info(f"created IAM user {name}" if created else f"IAM user {name} already exists")
        ses = plan.email.mode == "ses"
        document = self._call(ctx.aws.put_server_policy, name, kms_key_arn=key.arn, bucket=bucket_name, ses=ses, backup_bucket=backup_name)
        grants = ["KMS Encrypt/Decrypt/DescribeKey"]
        if bucket_name:
            grants.append(f"S3 objects + listing on {bucket_name}")
        if backup_name:
            grants.append(f"put/get/list on {backup_name} (no delete)")
        if ses:
            grants.append("SES SendEmail/SendRawEmail")
        ctx.info(f"wrote inline policy for {name}: {', '.join(grants)} ({len(document['Statement'])} statements; no deletion rights)")

        file_key_id, file_secret = self._remote_credentials(ctx)
        current_keys = self._call(ctx.aws.list_access_keys, name)
        previous = file_key_id if file_key_id in current_keys else ""
        if previous and file_secret and not aws.rotate_server_key:
            ctx.info(f"access key {previous} is already on the server and still belongs to {name} - kept")
            self._store_server_key(ctx, previous, file_secret, kept=True)
            return previous, file_secret, ""
        if previous:
            ctx.info(f"rotating the server access key of {name} as requested (previous {previous})")
            if len(current_keys) >= 2:
                # Two-key limit: make room by deleting the key the server does NOT reference.
                victim = next(k for k in current_keys if k != previous)
                self._call(ctx.aws.delete_access_key, name, victim)
                ctx.info(f"deleted unreferenced access key {victim} of {name} to make room for the new one")
        access = self._call(ctx.aws.create_access_key, name, rotate=aws.rotate_server_key)
        self._store_server_key(ctx, access.access_key_id, access.secret_access_key, kept=False)
        ctx.info(f"created access key {access.access_key_id} for {name}")
        return access.access_key_id, access.secret_access_key, previous if previous != access.access_key_id else ""

    def _write_credential_files(self, ctx: Context, key_id: str, secret: str) -> None:
        remote = ctx.remote
        try:
            remote.mkdirs(AWS_HOME, mode=0o700)
            existing = remote.read_text(CREDENTIALS_PATH)
            text = render_aws_credentials(key_id, secret, existing)
            if text != existing:
                remote.put_text(CREDENTIALS_PATH, text, mode=0o600)
                kept = " (other profiles kept)" if "[" in text.split("[default]", 1)[-1] else ""
                ctx.info(f"wrote {CREDENTIALS_PATH} with [default] = {key_id}{kept}")
            else:
                ctx.debug(f"{CREDENTIALS_PATH} already holds {key_id} - unchanged")
            existing_config = remote.read_text(CONFIG_PATH)
            config = render_aws_config(ctx.plan.aws.region, existing_config)
            if config != existing_config:
                remote.put_text(CONFIG_PATH, config, mode=0o600)
                ctx.info(f"wrote {CONFIG_PATH} with [default] region = {ctx.plan.aws.region}")
            else:
                ctx.debug(f"{CONFIG_PATH} already names region {ctx.plan.aws.region} - unchanged")
        except RemoteError as exc:
            raise StepError(f"writing the AWS credential files under {AWS_HOME}: {exc}") from exc

    def _probe_server_key(self, ctx: Context, key_id: str, secret: str) -> str:
        """``verify_server_key`` with the plan's KMS/bucket/SES scope; returns the caller ARN."""
        plan = ctx.plan
        return self._call(
            ctx.aws.verify_server_key,
            key_id,
            secret,
            kms_key_id=ctx.secrets.kms_key_id or self._kms_reference(plan),
            bucket=plan.aws.s3_bucket if plan.aws.s3_enabled else None,
            ses=plan.email.mode == "ses",
            attempts=self.key_probe_attempts,
            delay=self.key_probe_delay,
        )

    def _retire_previous_key(self, ctx: Context, key_id: str, secret: str, previous: str, bucket_name: str | None) -> None:
        """Prove the new key end to end, then delete the one it replaces (never the other way round)."""
        name = ctx.plan.aws.iam_user_name
        try:
            arn = self._probe_server_key(ctx, key_id, secret)
        except StepError as exc:
            raise StepError(
                f"the new access key {key_id} does not work yet ({exc}); the previous key {previous} was left active on "
                f"{name} but {CREDENTIALS_PATH} already holds the new one - Retry once IAM has propagated"
            ) from exc
        ctx.info(f"new access key {key_id} verified ({arn})")
        self._call(ctx.aws.delete_access_key, name, previous)
        ctx.info(f"deleted previous access key {previous} of {name}")

    # --- verify ----------------------------------------------------------------------------------

    def verify(self, ctx: Context) -> VerifyResult:
        """The real probe: the server's key does STS + KMS encrypt/decrypt + bucket HEAD (+ SES), and the file holds it."""
        plan = ctx.plan
        aws = plan.aws
        key_id = ctx.secrets.server_access_key_id
        secret = ctx.secrets.server_secret_access_key
        if not key_id or not secret:
            return VerifyResult(False, "no server access key resolved - run the AWS step's apply first")
        file_key_id, _ = self._remote_credentials(ctx)
        if file_key_id != key_id:
            return VerifyResult(False, f"{CREDENTIALS_PATH} holds {file_key_id or 'no key'} instead of {key_id}")
        try:
            arn = self._probe_server_key(ctx, key_id, secret)
        except StepError as exc:
            return VerifyResult(False, str(exc))
        parts = [f"server key {key_id} works as {arn}", f"KMS {ctx.secrets.kms_key_id or self._kms_reference(plan)} encrypt/decrypt ok"]
        if aws.s3_enabled:
            parts.append(f"bucket {aws.s3_bucket} reachable")
        if plan.email.mode == "ses":
            parts.append("SES account reachable")
        parts.append(f"{CREDENTIALS_PATH} holds the key")
        return VerifyResult(True, " · ".join(parts))

    # --- summary ---------------------------------------------------------------------------------

    def describe(self, plan: InstallPlan) -> str:
        """One line mirroring :meth:`apply` for the summary page."""
        aws = plan.aws
        bits: list[str] = []
        if aws.kms_mode == "create":
            bits.append(f"create or adopt KMS key {aws.kms_alias}" + (" (rotation on)" if aws.kms_enable_rotation else ""))
        else:
            bits.append(f"use existing KMS key {aws.kms_key_id}")
        if not aws.s3_enabled:
            bits.append("S3 off")
        else:
            if aws.s3_mode == "create":
                bits.append(f"create or adopt bucket {aws.s3_bucket} (private, SSE-S3" + (", versioned)" if aws.s3_versioning else ")"))
            else:
                bits.append(f"use existing bucket {aws.s3_bucket}")
            backup = self._backup_bucket(plan)
            if backup:
                bits.append(f"create or adopt backup bucket {backup} ({aws.backup_retention_days} d retention)")
        if aws.server_identity == "iam_user":
            key_plan = "rotate its access key (old one deleted after the new one is verified)" if aws.rotate_server_key else "keep the access key on the server or create one"
            bits.append(f"IAM user {aws.iam_user_name} + least-privilege inline policy, {key_plan}")
        else:
            bits.append(f"copy the operator's own access key ({aws.credential_source}) to the server")
        bits.append(f"write {CREDENTIALS_PATH} + {CONFIG_PATH} (0600)")
        return ", ".join(bits)

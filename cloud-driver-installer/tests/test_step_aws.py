"""AwsStep against a fake provisioner (never boto3) and the scripted FakeRemote."""

from __future__ import annotations

from typing import Any

import pytest

from cloud_driver_installer.aws import AccessKey, AwsError, Bucket, CallerIdentity, KmsKey, build_server_policy
from cloud_driver_installer.engine import Context, StepError, StepStatus
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.steps import aws as aws_step_module
from cloud_driver_installer.steps.aws import AWS_HOME, CONFIG_PATH, CREDENTIALS_PATH, AwsStep, parse_ini_section

from fake_remote import FakeRemote

ALIAS = "alias/cloud-driver-kms-key"
BUCKET = "cloud-driver-test-bucket"
BACKUP_BUCKET = "cloud-driver-test-bucket-backups"
USER = "cloud-driver-server"
EXISTING_KEY_ID = "AKIAEXISTING000000001"
EXISTING_SECRET = "existingSecretValue0123456789abcdefGHIJ"


class FakeAws:
    """Same method names as ``AwsProvisioner``, backed by dicts; every call lands in ``calls``."""

    def __init__(self) -> None:
        self.credentials_ok = True
        self.aliases: dict[str, str] = {}
        self.keys: dict[str, KmsKey] = {}
        self.buckets: dict[str, Bucket] = {}
        self.users: dict[str, list[str]] = {}
        self.policies: dict[str, dict[str, Any]] = {}
        self.issued: dict[str, str] = {}
        self.deleted_keys: list[str] = []
        self.calls: list[str] = []
        self.verify_error: AwsError | None = None
        self.raise_on: dict[str, AwsError] = {}
        self._counter = 0

    # --- seeding -----------------------------------------------------------------------------

    def seed_kms(self, alias: str = ALIAS, key_id: str = "1111-2222-3333") -> KmsKey:
        key = KmsKey(key_id=key_id, arn=f"arn:aws:kms:eu-central-1:123456789012:key/{key_id}", alias=alias, rotation_enabled=True)
        self.keys[key_id] = key
        if alias:
            self.aliases[alias] = key_id
        return key

    def seed_user(self, name: str = USER, *keys: str) -> None:
        self.users[name] = list(keys)
        for key_id in keys:
            self.issued.setdefault(key_id, EXISTING_SECRET)

    def _maybe_raise(self, name: str) -> None:
        self.calls.append(name)
        if name in self.raise_on:
            raise self.raise_on[name]

    # --- provisioner surface -------------------------------------------------------------------

    def whoami(self) -> CallerIdentity:
        self._maybe_raise("whoami")
        if not self.credentials_ok:
            raise AwsError("validating AWS credentials: InvalidClientTokenId: The security token included in the request is invalid")
        return CallerIdentity(account="123456789012", arn="arn:aws:iam::123456789012:user/operator", user_id="AIDAOPERATOR")

    def ensure_kms_key(self, *, alias: str, enable_rotation: bool = True) -> KmsKey:
        self._maybe_raise("ensure_kms_key")
        if alias in self.aliases:
            key = self.keys[self.aliases[alias]]
            return KmsKey(key.key_id, key.arn, alias=alias, created=False, rotation_enabled=True if enable_rotation else key.rotation_enabled)
        key_id = f"created-key-{len(self.keys) + 1}"
        key = KmsKey(key_id=key_id, arn=f"arn:aws:kms:eu-central-1:123456789012:key/{key_id}", alias=alias, created=True, rotation_enabled=enable_rotation)
        self.keys[key_id] = KmsKey(key_id, key.arn, alias=alias, rotation_enabled=enable_rotation)
        self.aliases[alias] = key_id
        return key

    def describe_kms_key(self, ref: str) -> KmsKey:
        self._maybe_raise("describe_kms_key")
        key_id = self.aliases.get(ref, ref)
        if key_id not in self.keys:
            raise AwsError(f"looking up KMS key {ref}: NotFoundException: Key '{ref}' does not exist")
        key = self.keys[key_id]
        return KmsKey(key.key_id, key.arn, alias=ref if ref.startswith("alias/") else key.alias, rotation_enabled=key.rotation_enabled)

    def ensure_bucket(self, name: str, *, abort_multipart_days: int = 7, versioning: bool = False) -> Bucket:
        self._maybe_raise(f"ensure_bucket {name} abort={abort_multipart_days} versioning={versioning}")
        if name in self.buckets:
            return Bucket(name, self.buckets[name].region, created=False)
        self.buckets[name] = Bucket(name, "eu-central-1", created=False)
        return Bucket(name, "eu-central-1", created=True)

    def ensure_backup_bucket(self, name: str, *, retention_days: int = 60) -> Bucket:
        self._maybe_raise(f"ensure_backup_bucket {name} retention={retention_days}")
        if name in self.buckets:
            return Bucket(name, self.buckets[name].region, created=False)
        self.buckets[name] = Bucket(name, "eu-central-1", created=False)
        return Bucket(name, "eu-central-1", created=True)

    def head_bucket(self, name: str) -> Bucket:
        self._maybe_raise(f"head_bucket {name}")
        if name not in self.buckets:
            raise AwsError(f"looking up bucket {name}: 404: Not Found")
        return Bucket(name, self.buckets[name].region, created=False)

    def ensure_server_user(self, user_name: str) -> bool:
        self._maybe_raise(f"ensure_server_user {user_name}")
        if user_name in self.users:
            return False
        self.users[user_name] = []
        return True

    def put_server_policy(self, user_name: str, *, kms_key_arn: str, bucket: str | None, ses: bool, backup_bucket: str | None = None) -> dict[str, Any]:
        self._maybe_raise(f"put_server_policy {user_name}")
        document = build_server_policy(kms_key_arn=kms_key_arn, bucket=bucket, ses=ses, backup_bucket=backup_bucket)
        self.policies[user_name] = document
        return document

    def list_access_keys(self, user_name: str) -> list[str]:
        self._maybe_raise(f"list_access_keys {user_name}")
        if user_name not in self.users:
            raise AwsError(f"listing access keys of {user_name}: NoSuchEntity: The user with name {user_name} cannot be found.")
        return list(self.users[user_name])

    def create_access_key(self, user_name: str, *, rotate: bool) -> AccessKey:
        self._maybe_raise(f"create_access_key {user_name} rotate={rotate}")
        keys = self.users[user_name]
        if len(keys) >= 2:
            if not rotate:
                raise AwsError(f"IAM user {user_name} already has two access keys - tick 'rotate' to replace the oldest")
            self.deleted_keys.append(keys.pop(0))
        self._counter += 1
        key_id = f"AKIAFAKE{self._counter:013d}"
        secret = f"fakeSecretNumber{self._counter:03d}" + "Zz" * 12
        keys.append(key_id)
        self.issued[key_id] = secret
        return AccessKey(access_key_id=key_id, secret_access_key=secret, created=True)

    def delete_access_key(self, user_name: str, access_key_id: str) -> None:
        self._maybe_raise(f"delete_access_key {user_name} {access_key_id}")
        self.users[user_name].remove(access_key_id)
        self.deleted_keys.append(access_key_id)

    def verify_server_key(self, access_key_id: str, secret_access_key: str, *, kms_key_id: str, bucket: str | None, ses: bool, attempts: int = 8, delay: float = 3.0) -> str:
        self._maybe_raise(f"verify_server_key {access_key_id} kms={kms_key_id} bucket={bucket} ses={ses}")
        if self.verify_error is not None:
            raise self.verify_error
        if self.issued.get(access_key_id) != secret_access_key:
            raise AwsError("verifying the server's access key: InvalidClientTokenId: unknown key")
        return f"arn:aws:iam::123456789012:user/{USER}"


# --- fixtures ----------------------------------------------------------------------------------


@pytest.fixture
def fake() -> FakeAws:
    return FakeAws()


@pytest.fixture
def step() -> AwsStep:
    step = AwsStep()
    step.key_probe_attempts = 1
    step.key_probe_delay = 0.0
    return step


@pytest.fixture
def actx(ctx: Context, fake: FakeAws) -> Context:
    ctx.aws_factory = lambda: fake  # type: ignore[assignment,return-value]
    return ctx


def credentials_text(key_id: str, secret: str) -> str:
    return f"[default]\naws_access_key_id = {key_id}\naws_secret_access_key = {secret}\n"


def provision(fake: FakeAws, remote: FakeRemote, plan: InstallPlan, *, key_id: str = EXISTING_KEY_ID) -> None:
    """Seed the fake AWS account and the server as a previous run left them."""
    fake.seed_kms()
    fake.buckets[BUCKET] = Bucket(BUCKET, "eu-central-1")
    fake.buckets[BACKUP_BUCKET] = Bucket(BACKUP_BUCKET, "eu-central-1")
    fake.seed_user(USER, key_id)
    remote.files[CREDENTIALS_PATH] = credentials_text(key_id, EXISTING_SECRET)
    remote.files[CONFIG_PATH] = f"[default]\nregion = {plan.aws.region}\noutput = json\n"


def all_log_text(ctx: Context) -> str:
    return "\n".join(message for _, message in ctx.captured_log)  # type: ignore[attr-defined]


def assert_secret_hidden(ctx: Context, remote: FakeRemote, secret: str) -> None:
    assert secret not in all_log_text(ctx)
    assert all(secret not in command for command in remote.commands)


# --- parse helper --------------------------------------------------------------------------------


def test_parse_ini_section_is_tolerant() -> None:
    text = "# comment\n[personal]\naws_access_key_id=AKIAPERSONAL\n\n[default]\n  aws_access_key_id =  AKIADEFAULT \naws_secret_access_key= s3cr3t\n[other]\nx=1\n"
    assert parse_ini_section(text) == {"aws_access_key_id": "AKIADEFAULT", "aws_secret_access_key": "s3cr3t"}
    assert parse_ini_section(text, "personal") == {"aws_access_key_id": "AKIAPERSONAL"}
    assert parse_ini_section(None) == {}
    assert parse_ini_section("no sections here") == {}


# --- check -----------------------------------------------------------------------------------------


def test_check_needs_apply_on_bare_account_and_box(step: AwsStep, actx: Context) -> None:
    result = step.check(actx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert f"create KMS key {ALIAS}" in result.detail
    assert f"create bucket {BUCKET}" in result.detail
    assert f"create backup bucket {BACKUP_BUCKET}" in result.detail
    assert f"create IAM user {USER}" in result.detail
    assert f"write {CREDENTIALS_PATH}" in result.detail
    assert "account 123456789012" in result.detail


def test_check_reports_unusable_credentials_without_failing(step: AwsStep, actx: Context, fake: FakeAws) -> None:
    fake.credentials_ok = False
    result = step.check(actx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "InvalidClientTokenId" in result.detail


def test_check_reports_missing_aws_factory_without_failing(step: AwsStep, ctx: Context) -> None:
    assert ctx.aws_factory is None
    result = step.check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "not configured" in result.detail


def test_check_ok_on_provisioned_box_and_fills_secrets(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    provision(fake, remote, plan)
    result = step.check(actx)
    assert result.status is StepStatus.OK, result.detail
    assert f"server key {EXISTING_KEY_ID} kept" in result.detail
    assert actx.secrets.kms_key_id == ALIAS
    assert actx.secrets.kms_key_arn.endswith("key/1111-2222-3333")
    assert actx.secrets.s3_bucket == BUCKET
    assert actx.secrets.server_access_key_id == EXISTING_KEY_ID
    assert actx.secrets.server_secret_access_key == EXISTING_SECRET
    assert actx.secrets.server_key_kept is True
    assert EXISTING_SECRET in actx.redactor
    assert not any(call.startswith(("ensure_", "create_", "put_", "delete_")) for call in fake.calls), fake.calls


def test_check_needs_apply_when_server_key_no_longer_belongs_to_user(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    provision(fake, remote, plan)
    fake.users[USER] = ["AKIASOMEOTHERKEY00001"]
    result = step.check(actx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "create the server access key" in result.detail


def test_check_needs_apply_when_rotate_requested(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    provision(fake, remote, plan)
    plan.aws.rotate_server_key = True
    result = step.check(actx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "rotate the server access key" in result.detail


def test_check_existing_kms_and_bucket_mode(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    provision(fake, remote, plan)
    plan.aws.kms_mode = "existing"
    plan.aws.kms_key_id = "1111-2222-3333"
    plan.aws.s3_mode = "existing"
    result = step.check(actx)
    assert result.status is StepStatus.OK, result.detail
    assert actx.secrets.kms_key_id == "1111-2222-3333"
    plan.aws.kms_key_id = "does-not-exist"
    result = step.check(actx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "KMS key does-not-exist not usable" in result.detail


def test_check_refuses_silent_kek_swap(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan, discovered: Any) -> None:
    provision(fake, remote, plan)
    discovered.existing_config = {"aws-kms-key-id": "alias/old-production-key", "aws-kms-region": "eu-central-1"}
    with pytest.raises(StepError, match="alias/old-production-key"):
        step.check(actx)
    plan.aws.allow_kms_change = True
    result = step.check(actx)
    assert result.status is StepStatus.OK, result.detail
    assert "acknowledged" in result.detail
    assert any(level == "WARN" and "replacing KEK" in message for level, message in actx.captured_log)  # type: ignore[attr-defined]


def test_check_treats_alias_and_its_key_id_as_the_same_kek(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan, discovered: Any) -> None:
    provision(fake, remote, plan)
    discovered.existing_config = {"aws-kms-key-id": "1111-2222-3333"}
    result = step.check(actx)
    assert result.status is StepStatus.OK, result.detail
    assert "KEK 1111-2222-3333 unchanged" in result.detail


def test_check_ignores_placeholder_kms_value(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan, discovered: Any) -> None:
    provision(fake, remote, plan)
    discovered.existing_config = {"aws-kms-key-id": "REPLACE-ME"}
    assert step.check(actx).status is StepStatus.OK


# --- apply ------------------------------------------------------------------------------------------


def test_apply_creates_everything_on_a_bare_account(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    plan.aws.s3_versioning = True
    plan.aws.s3_abort_multipart_days = 3
    plan.aws.backup_retention_days = 45
    step.apply(actx)

    assert ALIAS in fake.aliases
    assert f"ensure_bucket {BUCKET} abort=3 versioning=True" in fake.calls
    assert f"ensure_backup_bucket {BACKUP_BUCKET} retention=45" in fake.calls
    assert f"ensure_server_user {USER}" in fake.calls
    sids = [statement["Sid"] for statement in fake.policies[USER]["Statement"]]
    assert sids == ["CloudDriverKms", "CloudDriverS3Objects", "CloudDriverS3Bucket", "CloudDriverBackupObjects", "CloudDriverBackupBucket"]
    assert fake.policies[USER]["Statement"][0]["Resource"] == actx.secrets.kms_key_arn

    key_id = actx.secrets.server_access_key_id
    secret = actx.secrets.server_secret_access_key
    assert key_id in fake.users[USER] and secret == fake.issued[key_id]
    assert actx.secrets.server_key_kept is False
    assert actx.secrets.kms_key_id == ALIAS
    assert actx.secrets.s3_bucket == BUCKET

    assert remote.files[CREDENTIALS_PATH] == credentials_text(key_id, secret)
    assert remote.modes[CREDENTIALS_PATH] == 0o600
    assert remote.files[CONFIG_PATH] == "[default]\nregion = eu-central-1\noutput = json\n"
    assert remote.modes[CONFIG_PATH] == 0o600
    assert remote.ran(f"mkdir -p {AWS_HOME}")
    assert not fake.deleted_keys
    assert_secret_hidden(actx, remote, secret)
    assert secret in actx.redactor


def test_apply_uses_existing_kms_and_bucket_without_creating(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    fake.seed_kms(alias="", key_id="abcd-0001")
    fake.buckets[BUCKET] = Bucket(BUCKET, "eu-west-1")
    plan.aws.kms_mode = "existing"
    plan.aws.kms_key_id = "abcd-0001"
    plan.aws.s3_mode = "existing"
    plan.app.backup_offsite = False
    step.apply(actx)
    assert not any(call.startswith(("ensure_kms_key", "ensure_bucket", "ensure_backup_bucket")) for call in fake.calls), fake.calls
    assert actx.secrets.kms_key_id == "abcd-0001"
    assert actx.secrets.kms_key_arn.endswith("key/abcd-0001")
    sids = [statement["Sid"] for statement in fake.policies[USER]["Statement"]]
    assert "CloudDriverBackupObjects" not in sids
    assert any(level == "WARN" and "eu-west-1" in message for level, message in actx.captured_log)  # type: ignore[attr-defined]


def test_apply_without_s3_grants_only_kms(step: AwsStep, actx: Context, fake: FakeAws, plan: InstallPlan) -> None:
    plan.aws.s3_enabled = False
    plan.app.backup_offsite = False
    step.apply(actx)
    assert [s["Sid"] for s in fake.policies[USER]["Statement"]] == ["CloudDriverKms"]
    assert actx.secrets.s3_bucket == ""


def test_apply_grants_ses_when_email_mode_is_ses(step: AwsStep, actx: Context, fake: FakeAws, plan: InstallPlan) -> None:
    plan.email.mode = "ses"
    plan.email.ses_from_address = "noreply@example.com"
    step.apply(actx)
    assert "CloudDriverSes" in [s["Sid"] for s in fake.policies[USER]["Statement"]]


def test_apply_keeps_existing_key_and_leaves_files_untouched(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    provision(fake, remote, plan)
    before = dict(remote.files)
    step.apply(actx)
    assert actx.secrets.server_key_kept is True
    assert actx.secrets.server_access_key_id == EXISTING_KEY_ID
    assert actx.secrets.server_secret_access_key == EXISTING_SECRET
    assert not any(call.startswith("create_access_key") for call in fake.calls)
    assert remote.files == before
    assert remote.backups == []
    assert fake.users[USER] == [EXISTING_KEY_ID]
    assert_secret_hidden(actx, remote, EXISTING_SECRET)


def test_apply_rotates_key_writes_file_verifies_then_deletes_old(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    provision(fake, remote, plan)
    plan.aws.rotate_server_key = True
    step.apply(actx)
    new_id = actx.secrets.server_access_key_id
    new_secret = actx.secrets.server_secret_access_key
    assert new_id != EXISTING_KEY_ID and actx.secrets.server_key_kept is False
    assert remote.files[CREDENTIALS_PATH] == credentials_text(new_id, new_secret)
    assert remote.modes[CREDENTIALS_PATH] == 0o600
    assert remote.backups, "the rewritten credentials file must be backed up first"
    assert fake.deleted_keys == [EXISTING_KEY_ID]
    assert fake.users[USER] == [new_id]
    verify_index = next(i for i, call in enumerate(fake.calls) if call.startswith("verify_server_key"))
    delete_index = next(i for i, call in enumerate(fake.calls) if call.startswith("delete_access_key"))
    assert verify_index < delete_index
    assert_secret_hidden(actx, remote, new_secret)
    assert_secret_hidden(actx, remote, EXISTING_SECRET)


def test_apply_rotation_keeps_old_key_when_new_one_does_not_verify(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    provision(fake, remote, plan)
    plan.aws.rotate_server_key = True
    fake.verify_error = AwsError("verifying the server's access key: InvalidClientTokenId: not yet propagated")
    with pytest.raises(StepError, match=f"previous key {EXISTING_KEY_ID} was left active"):
        step.apply(actx)
    assert EXISTING_KEY_ID in fake.users[USER]
    assert fake.deleted_keys == []


def test_apply_rotation_at_two_key_limit_deletes_the_unreferenced_key_first(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    provision(fake, remote, plan)
    fake.users[USER] = ["AKIAUNREFERENCED00001", EXISTING_KEY_ID]
    plan.aws.rotate_server_key = True
    step.apply(actx)
    new_id = actx.secrets.server_access_key_id
    assert fake.deleted_keys == ["AKIAUNREFERENCED00001", EXISTING_KEY_ID]
    assert fake.users[USER] == [new_id]
    assert remote.files[CREDENTIALS_PATH] == credentials_text(new_id, actx.secrets.server_secret_access_key)


def test_apply_two_keys_none_on_server_without_rotate_is_a_readable_error(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    provision(fake, remote, plan)
    fake.users[USER] = ["AKIAOTHER000000000001", "AKIAOTHER000000000002"]
    with pytest.raises(StepError, match="tick 'rotate'"):
        step.apply(actx)


def test_apply_is_idempotent(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote) -> None:
    step.apply(actx)
    files_after_first = dict(remote.files)
    calls_after_first = list(fake.calls)
    users_after_first = {name: list(keys) for name, keys in fake.users.items()}
    step.apply(actx)
    assert remote.files == files_after_first
    assert fake.users == users_after_first
    new_calls = fake.calls[len(calls_after_first):]
    assert not any(call.startswith(("create_access_key", "delete_access_key")) for call in new_calls), new_calls
    assert actx.secrets.server_key_kept is True
    assert step.check(actx).status is StepStatus.OK


def test_apply_preserves_other_profiles_in_existing_files(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote) -> None:
    remote.files[CREDENTIALS_PATH] = "[personal]\naws_access_key_id = AKIAPERSONAL0000001\naws_secret_access_key = personalSecretXYZ\n\n[default]\naws_access_key_id = AKIASTALE00000000001\naws_secret_access_key = staleSecretValue1234\n"
    remote.files[CONFIG_PATH] = "[profile personal]\nregion = us-east-1\n"
    step.apply(actx)
    credentials = remote.files[CREDENTIALS_PATH]
    assert credentials.startswith(f"[default]\naws_access_key_id = {actx.secrets.server_access_key_id}\n")
    assert "[personal]\naws_access_key_id = AKIAPERSONAL0000001" in credentials
    assert "AKIASTALE00000000001" not in credentials
    assert remote.files[CONFIG_PATH] == "[default]\nregion = eu-central-1\noutput = json\n\n[profile personal]\nregion = us-east-1\n"
    assert remote.backups


def test_apply_reuse_typed_keys_copies_them_and_warns(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    plan.aws.server_identity = "reuse"
    plan.aws.credential_source = "keys"
    plan.aws.access_key_id = "AKIAOPERATOR000000001"
    plan.aws.secret_access_key = "operatorSecretValue0123456789ABCDEF"
    fake.issued[plan.aws.access_key_id] = plan.aws.secret_access_key
    step.apply(actx)
    assert not any(call.startswith(("ensure_server_user", "put_server_policy", "create_access_key")) for call in fake.calls)
    assert remote.files[CREDENTIALS_PATH] == credentials_text(plan.aws.access_key_id, plan.aws.secret_access_key)
    assert remote.modes[CREDENTIALS_PATH] == 0o600
    assert actx.secrets.server_access_key_id == plan.aws.access_key_id
    assert any(level == "WARN" and "operator's own credentials" in message for level, message in actx.captured_log)  # type: ignore[attr-defined]
    assert_secret_hidden(actx, remote, plan.aws.secret_access_key)
    assert step.verify(actx).ok


def test_apply_reuse_refuses_session_token(step: AwsStep, actx: Context, plan: InstallPlan) -> None:
    plan.aws.server_identity = "reuse"
    plan.aws.credential_source = "keys"
    plan.aws.access_key_id = "ASIATEMP0000000000001"
    plan.aws.secret_access_key = "temporarySecretValue0123456789"
    plan.aws.session_token = "FwoGZXIvYXdzEBYaD..."
    with pytest.raises(StepError, match="session token"):
        step.apply(actx)


def test_apply_reuse_profile_reads_local_profile(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan, monkeypatch: pytest.MonkeyPatch) -> None:
    plan.aws.server_identity = "reuse"
    plan.aws.credential_source = "profile"
    plan.aws.profile_name = "ops"
    asked: list[str] = []

    def fake_profile(profile_name: str) -> tuple[str, str]:
        asked.append(profile_name)
        return "AKIAPROFILE0000000001", "profileSecretValue0123456789ABCDEF"

    monkeypatch.setattr(aws_step_module, "_profile_credentials", fake_profile)
    step.apply(actx)
    assert asked == ["ops"]
    assert remote.files[CREDENTIALS_PATH] == credentials_text("AKIAPROFILE0000000001", "profileSecretValue0123456789ABCDEF")
    assert any("local profile 'ops'" in message for _, message in actx.captured_log)  # type: ignore[attr-defined]
    assert_secret_hidden(actx, remote, "profileSecretValue0123456789ABCDEF")


def test_apply_wraps_aws_errors_into_step_errors(step: AwsStep, actx: Context, fake: FakeAws) -> None:
    fake.raise_on["ensure_kms_key"] = AwsError("creating KMS key alias/cloud-driver-kms-key: AccessDeniedException: not authorized")
    with pytest.raises(StepError, match="AccessDeniedException"):
        step.apply(actx)


def test_apply_refuses_kek_swap_unless_acknowledged(step: AwsStep, actx: Context, fake: FakeAws, plan: InstallPlan, discovered: Any) -> None:
    discovered.existing_config = {"aws-kms-key-id": "alias/old-production-key"}
    with pytest.raises(StepError, match="alias/old-production-key"):
        step.apply(actx)
    assert not any(call.startswith("ensure_bucket") for call in fake.calls), "must stop before touching anything else"
    plan.aws.allow_kms_change = True
    step.apply(actx)
    assert ALIAS in fake.aliases


# --- verify --------------------------------------------------------------------------------------------


def test_verify_ok_after_apply(step: AwsStep, actx: Context, fake: FakeAws, plan: InstallPlan) -> None:
    plan.email.mode = "ses"
    plan.email.ses_from_address = "noreply@example.com"
    step.apply(actx)
    result = step.verify(actx)
    assert result.ok, result.detail
    assert f"works as arn:aws:iam::123456789012:user/{USER}" in result.detail
    assert f"bucket {BUCKET} reachable" in result.detail
    assert "SES account reachable" in result.detail
    probe = next(call for call in fake.calls if call.startswith("verify_server_key"))
    assert f"kms={ALIAS} bucket={BUCKET} ses=True" in probe


def test_verify_fails_when_key_probe_fails(step: AwsStep, actx: Context, fake: FakeAws) -> None:
    step.apply(actx)
    fake.verify_error = AwsError("verifying the server's access key: AccessDenied: kms:Encrypt")
    result = step.verify(actx)
    assert not result.ok
    assert "kms:Encrypt" in result.detail


def test_verify_fails_when_file_does_not_hold_the_key(step: AwsStep, actx: Context, remote: FakeRemote) -> None:
    step.apply(actx)
    remote.files[CREDENTIALS_PATH] = credentials_text("AKIASOMEBODYELSE00001", "otherSecretValue012345678")
    result = step.verify(actx)
    assert not result.ok
    assert "AKIASOMEBODYELSE00001" in result.detail


def test_verify_without_resolved_key_is_not_ok(step: AwsStep, actx: Context) -> None:
    result = step.verify(actx)
    assert not result.ok
    assert "apply" in result.detail


def test_verify_after_check_only_on_provisioned_box(step: AwsStep, actx: Context, fake: FakeAws, remote: FakeRemote, plan: InstallPlan) -> None:
    provision(fake, remote, plan)
    assert step.check(actx).status is StepStatus.OK
    result = step.verify(actx)
    assert result.ok, result.detail


# --- describe ---------------------------------------------------------------------------------------------


def test_describe_mirrors_the_plan(step: AwsStep, plan: InstallPlan) -> None:
    line = step.describe(plan)
    assert ALIAS in line and BUCKET in line and BACKUP_BUCKET in line and USER in line and CREDENTIALS_PATH in line
    assert "\n" not in line
    plan.aws.kms_mode = "existing"
    plan.aws.kms_key_id = "abcd"
    plan.aws.s3_enabled = False
    plan.app.backup_offsite = False
    plan.aws.server_identity = "reuse"
    line = step.describe(plan)
    assert "use existing KMS key abcd" in line and "S3 off" in line and "operator's own access key" in line
    plan.aws.s3_enabled = True
    plan.aws.server_identity = "iam_user"
    plan.aws.rotate_server_key = True
    assert "rotate its access key" in step.describe(plan)


def test_step_metadata() -> None:
    step = AwsStep()
    assert step.id == "aws" and step.mandatory and step.depends_on == ("server",)
    assert step.enabled(InstallPlan())

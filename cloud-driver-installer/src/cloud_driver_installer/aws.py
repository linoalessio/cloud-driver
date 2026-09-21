"""AWS-side provisioning, run on the operator's machine with the operator's own credentials.

What lands on the server is only ever the access key of the least-privilege IAM user created
here (or, when the operator chooses ``reuse``, the credentials they typed). Every call is
idempotent: an alias, bucket, user or policy that already exists is adopted, never re-created,
and nothing is ever deleted except an access key the operator explicitly asked to rotate.

The permissions the runtime identity needs are exactly those docs/requirements.md §4.1 lists:
``kms:Encrypt``/``kms:Decrypt`` (plus ``kms:DescribeKey`` so a mistyped id fails loudly at
boot), the five S3 actions on the one bucket, and ``ses:SendEmail``/``ses:SendRawEmail`` when
SES is the mail transport. Anything else (ScheduleKeyDeletion, DeleteBucket, IAM) is deliberately
absent - that absence is the deletion guard for the KEK every stored row depends on.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any

import boto3
from botocore.exceptions import BotoCoreError, ClientError, NoCredentialsError, ProfileNotFound

from cloud_driver_installer.model import AwsSettings

#: Inline policy name on the runtime IAM user.
SERVER_POLICY_NAME = "cloud-driver-server-access"

#: Common regions offered in the GUI combobox (free text is still accepted).
COMMON_REGIONS = (
    "eu-central-1",
    "eu-central-2",
    "eu-west-1",
    "eu-west-2",
    "eu-west-3",
    "eu-north-1",
    "eu-south-1",
    "us-east-1",
    "us-east-2",
    "us-west-1",
    "us-west-2",
    "ca-central-1",
    "ap-southeast-1",
    "ap-southeast-2",
    "ap-northeast-1",
    "sa-east-1",
)


class AwsError(Exception):
    """A readable failure: ``<Code>: <message>`` for ClientErrors, plain text otherwise."""


def _wrap(exc: Exception, what: str) -> AwsError:
    if isinstance(exc, ClientError):
        error = exc.response.get("Error", {})
        return AwsError(f"{what}: {error.get('Code', 'ClientError')}: {error.get('Message', str(exc))}")
    if isinstance(exc, NoCredentialsError):
        return AwsError(f"{what}: no AWS credentials found - pick a profile or enter an access key")
    if isinstance(exc, ProfileNotFound):
        return AwsError(f"{what}: {exc}")
    return AwsError(f"{what}: {exc}")


@dataclass
class CallerIdentity:
    """STS GetCallerIdentity."""

    account: str
    arn: str
    user_id: str


@dataclass
class KmsKey:
    """A resolved KMS key."""

    key_id: str
    arn: str
    alias: str = ""
    created: bool = False
    enabled: bool = True
    rotation_enabled: bool | None = None

    @property
    def config_value(self) -> str:
        """What ``aws-kms-key-id`` should carry: the alias when one is known, else the id."""
        return self.alias or self.key_id


@dataclass
class Bucket:
    """A resolved S3 bucket."""

    name: str
    region: str
    created: bool = False


@dataclass
class AccessKey:
    """An IAM access key pair (secret only known right after creation)."""

    access_key_id: str
    secret_access_key: str = ""
    created: bool = False


@dataclass
class SesIdentity:
    """An SES identity and its verification state."""

    identity: str
    kind: str  # "domain" | "address"
    status: str  # PENDING | SUCCESS | FAILED | TEMPORARY_FAILURE | NOT_STARTED
    created: bool = False
    dkim_records: list[tuple[str, str]] = field(default_factory=list)


def list_local_profiles() -> list[str]:
    """Profiles from ``~/.aws/credentials`` and ``~/.aws/config`` (``default`` first)."""
    try:
        names = boto3.session.Session().available_profiles
    except Exception:  # noqa: BLE001 - a malformed file must not crash the GUI
        return []
    names = sorted(set(names))
    if "default" in names:
        names.remove("default")
        names.insert(0, "default")
    return names


class AwsProvisioner:
    """boto3 clients for one region, built from :class:`AwsSettings`."""

    def __init__(self, settings: AwsSettings, *, session: Any | None = None) -> None:
        self.settings = settings
        self.region = settings.region
        try:
            if session is not None:
                self._session = session
            elif settings.credential_source == "keys":
                self._session = boto3.session.Session(
                    aws_access_key_id=settings.access_key_id,
                    aws_secret_access_key=settings.secret_access_key,
                    aws_session_token=settings.session_token or None,
                    region_name=self.region,
                )
            else:
                self._session = boto3.session.Session(profile_name=settings.profile_name or None, region_name=self.region)
        except (BotoCoreError, ProfileNotFound) as exc:
            raise _wrap(exc, "AWS session") from exc
        self._clients: dict[str, Any] = {}

    # --- clients ---------------------------------------------------------------------------------

    def client(self, service: str, region: str | None = None) -> Any:
        key = f"{service}:{region or self.region}"
        if key not in self._clients:
            self._clients[key] = self._session.client(service, region_name=region or self.region)
        return self._clients[key]

    # --- identity --------------------------------------------------------------------------------

    def whoami(self) -> CallerIdentity:
        """Validate the credentials; returns account + ARN for the GUI."""
        try:
            data = self.client("sts").get_caller_identity()
        except (ClientError, BotoCoreError, NoCredentialsError) as exc:
            raise _wrap(exc, "validating AWS credentials") from exc
        return CallerIdentity(account=data["Account"], arn=data["Arn"], user_id=data["UserId"])

    # --- KMS ---------------------------------------------------------------------------------------

    def ensure_kms_key(self, *, alias: str, enable_rotation: bool = True) -> KmsKey:
        """Create a symmetric key + alias, or adopt the key the alias already points at."""
        kms = self.client("kms")
        existing = self._find_alias(alias)
        if existing is not None:
            key = self.describe_kms_key(existing)
            key.alias = alias
            if enable_rotation:
                self._ensure_rotation(key)
            return key
        try:
            created = kms.create_key(
                Description="cloud-driver KEK (envelope-encryption key-encryption key)",
                KeyUsage="ENCRYPT_DECRYPT",
                KeySpec="SYMMETRIC_DEFAULT",
                Origin="AWS_KMS",
                Tags=[{"TagKey": "application", "TagValue": "cloud-driver"}],
            )["KeyMetadata"]
            kms.create_alias(AliasName=alias, TargetKeyId=created["KeyId"])
        except (ClientError, BotoCoreError) as exc:
            raise _wrap(exc, f"creating KMS key {alias}") from exc
        key = KmsKey(key_id=created["KeyId"], arn=created["Arn"], alias=alias, created=True, enabled=True)
        if enable_rotation:
            self._ensure_rotation(key)
        return key

    def describe_kms_key(self, key_id_or_alias: str) -> KmsKey:
        """Resolve an id / ARN / ``alias/…`` and require an enabled symmetric key."""
        kms = self.client("kms")
        try:
            meta = kms.describe_key(KeyId=key_id_or_alias)["KeyMetadata"]
        except (ClientError, BotoCoreError) as exc:
            raise _wrap(exc, f"looking up KMS key {key_id_or_alias}") from exc
        if meta.get("KeyState") != "Enabled":
            raise AwsError(f"KMS key {key_id_or_alias} is {meta.get('KeyState')} - it must be Enabled")
        if meta.get("KeySpec", meta.get("CustomerMasterKeySpec")) != "SYMMETRIC_DEFAULT":
            raise AwsError(f"KMS key {key_id_or_alias} is not a symmetric key")
        alias = key_id_or_alias if key_id_or_alias.startswith("alias/") else self._alias_of(meta["KeyId"])
        rotation: bool | None
        try:
            rotation = bool(kms.get_key_rotation_status(KeyId=meta["KeyId"]).get("KeyRotationEnabled"))
        except (ClientError, BotoCoreError):
            rotation = None
        return KmsKey(key_id=meta["KeyId"], arn=meta["Arn"], alias=alias, enabled=True, rotation_enabled=rotation)

    def _find_alias(self, alias: str) -> str | None:
        kms = self.client("kms")
        try:
            paginator = kms.get_paginator("list_aliases")
            for page in paginator.paginate():
                for entry in page.get("Aliases", []):
                    if entry.get("AliasName") == alias and entry.get("TargetKeyId"):
                        return entry["TargetKeyId"]
        except (ClientError, BotoCoreError) as exc:
            raise _wrap(exc, "listing KMS aliases") from exc
        return None

    def _alias_of(self, key_id: str) -> str:
        kms = self.client("kms")
        try:
            for page in kms.get_paginator("list_aliases").paginate(KeyId=key_id):
                for entry in page.get("Aliases", []):
                    name = entry.get("AliasName", "")
                    if name and not name.startswith("alias/aws/"):
                        return name
        except (ClientError, BotoCoreError):
            pass
        return ""

    def _ensure_rotation(self, key: KmsKey) -> None:
        kms = self.client("kms")
        try:
            if not kms.get_key_rotation_status(KeyId=key.key_id).get("KeyRotationEnabled"):
                kms.enable_key_rotation(KeyId=key.key_id)
            key.rotation_enabled = True
        except (ClientError, BotoCoreError):
            key.rotation_enabled = None  # not fatal: rotation is a nicety, the key works without it

    # --- S3 ----------------------------------------------------------------------------------------

    def ensure_bucket(self, name: str, *, abort_multipart_days: int = 7, versioning: bool = False) -> Bucket:
        """Create a private, SSE-S3 encrypted, owner-enforced bucket (or adopt one you already own)."""
        s3 = self.client("s3")
        created = False
        try:
            kwargs: dict[str, Any] = {"Bucket": name}
            if self.region != "us-east-1":
                kwargs["CreateBucketConfiguration"] = {"LocationConstraint": self.region}
            s3.create_bucket(**kwargs)
            created = True
        except ClientError as exc:
            code = exc.response.get("Error", {}).get("Code", "")
            if code == "BucketAlreadyOwnedByYou":
                created = False
            elif code == "BucketAlreadyExists":
                raise AwsError(f"bucket name {name} is taken by another AWS account - pick a different name") from exc
            else:
                raise _wrap(exc, f"creating bucket {name}") from exc
        except BotoCoreError as exc:
            raise _wrap(exc, f"creating bucket {name}") from exc
        if created:
            self._harden_bucket(name, abort_multipart_days=abort_multipart_days, versioning=versioning)
        return Bucket(name=name, region=self.region, created=created)

    def ensure_backup_bucket(self, name: str, *, retention_days: int = 60) -> Bucket:
        """Create the dedicated off-site backup bucket (private, encrypted, archives expire after ``retention_days``)."""
        bucket = self.ensure_bucket(name, abort_multipart_days=7, versioning=False)
        if bucket.created and retention_days > 0:
            try:
                self.client("s3").put_bucket_lifecycle_configuration(
                    Bucket=name,
                    LifecycleConfiguration={
                        "Rules": [
                            {"ID": "cloud-driver-abort-incomplete-multipart", "Status": "Enabled", "Filter": {"Prefix": ""}, "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 7}},
                            {"ID": "cloud-driver-backup-expiry", "Status": "Enabled", "Filter": {"Prefix": ""}, "Expiration": {"Days": retention_days}},
                        ]
                    },
                )
            except (ClientError, BotoCoreError) as exc:
                raise _wrap(exc, f"setting the retention rule on {name}") from exc
        return bucket

    def verify_server_key(
        self,
        access_key_id: str,
        secret_access_key: str,
        *,
        kms_key_id: str,
        bucket: str | None,
        ses: bool,
        attempts: int = 8,
        delay: float = 3.0,
    ) -> str:
        """Prove the runtime key works before it is relied on: STS identity, a KMS encrypt/decrypt
        round trip, a bucket HEAD and (with SES) the account lookup. Retries while IAM propagates.
        Returns the caller ARN."""
        import time

        session = boto3.session.Session(aws_access_key_id=access_key_id, aws_secret_access_key=secret_access_key, region_name=self.region)
        last: Exception | None = None
        for attempt in range(attempts):
            try:
                arn = session.client("sts").get_caller_identity()["Arn"]
                kms = session.client("kms")
                blob = kms.encrypt(KeyId=kms_key_id, Plaintext=b"cloud-driver-installer")["CiphertextBlob"]
                kms.decrypt(CiphertextBlob=blob, KeyId=kms_key_id)
                if bucket:
                    session.client("s3").head_bucket(Bucket=bucket)
                if ses:
                    session.client("sesv2").get_account()
                return arn
            except (ClientError, BotoCoreError, NoCredentialsError) as exc:
                last = exc
                if attempt + 1 < attempts:
                    time.sleep(delay)
        raise _wrap(last or AwsError("unknown"), "verifying the server's access key")

    def head_bucket(self, name: str) -> Bucket:
        """Require that ``name`` exists and is accessible."""
        s3 = self.client("s3")
        try:
            s3.head_bucket(Bucket=name)
            location = s3.get_bucket_location(Bucket=name).get("LocationConstraint") or "us-east-1"
        except (ClientError, BotoCoreError) as exc:
            raise _wrap(exc, f"looking up bucket {name}") from exc
        if location == "EU":
            location = "eu-west-1"
        return Bucket(name=name, region=location, created=False)

    def _harden_bucket(self, name: str, *, abort_multipart_days: int, versioning: bool) -> None:
        s3 = self.client("s3")
        try:
            s3.put_public_access_block(
                Bucket=name,
                PublicAccessBlockConfiguration={
                    "BlockPublicAcls": True,
                    "IgnorePublicAcls": True,
                    "BlockPublicPolicy": True,
                    "RestrictPublicBuckets": True,
                },
            )
            s3.put_bucket_encryption(
                Bucket=name,
                ServerSideEncryptionConfiguration={"Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]},
            )
            s3.put_bucket_ownership_controls(Bucket=name, OwnershipControls={"Rules": [{"ObjectOwnership": "BucketOwnerEnforced"}]})
            if abort_multipart_days > 0:
                s3.put_bucket_lifecycle_configuration(
                    Bucket=name,
                    LifecycleConfiguration={
                        "Rules": [
                            {
                                "ID": "cloud-driver-abort-incomplete-multipart",
                                "Status": "Enabled",
                                "Filter": {"Prefix": ""},
                                "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": abort_multipart_days},
                            }
                        ]
                    },
                )
            if versioning:
                s3.put_bucket_versioning(Bucket=name, VersioningConfiguration={"Status": "Enabled"})
        except (ClientError, BotoCoreError) as exc:
            raise _wrap(exc, f"hardening bucket {name}") from exc

    # --- IAM ---------------------------------------------------------------------------------------

    def ensure_server_user(self, user_name: str) -> bool:
        """Create the runtime IAM user; returns True when it was created now."""
        iam = self.client("iam")
        try:
            iam.get_user(UserName=user_name)
            return False
        except ClientError as exc:
            if exc.response.get("Error", {}).get("Code") != "NoSuchEntity":
                raise _wrap(exc, f"looking up IAM user {user_name}") from exc
        try:
            iam.create_user(UserName=user_name, Tags=[{"Key": "application", "Value": "cloud-driver"}])
        except (ClientError, BotoCoreError) as exc:
            raise _wrap(exc, f"creating IAM user {user_name}") from exc
        return True

    def put_server_policy(self, user_name: str, *, kms_key_arn: str, bucket: str | None, ses: bool, backup_bucket: str | None = None) -> dict[str, Any]:
        """Write (replace) the inline least-privilege policy; returns the document."""
        document = build_server_policy(kms_key_arn=kms_key_arn, bucket=bucket, ses=ses, backup_bucket=backup_bucket)
        try:
            self.client("iam").put_user_policy(UserName=user_name, PolicyName=SERVER_POLICY_NAME, PolicyDocument=json.dumps(document))
        except (ClientError, BotoCoreError) as exc:
            raise _wrap(exc, f"writing policy for {user_name}") from exc
        return document

    def list_access_keys(self, user_name: str) -> list[str]:
        """Access key ids the user currently has (at most two)."""
        try:
            data = self.client("iam").list_access_keys(UserName=user_name)
        except (ClientError, BotoCoreError) as exc:
            raise _wrap(exc, f"listing access keys of {user_name}") from exc
        return [entry["AccessKeyId"] for entry in data.get("AccessKeyMetadata", [])]

    def create_access_key(self, user_name: str, *, rotate: bool) -> AccessKey:
        """Create a new access key, deleting the oldest first when the two-key limit is reached and ``rotate`` is set."""
        iam = self.client("iam")
        try:
            existing = iam.list_access_keys(UserName=user_name).get("AccessKeyMetadata", [])
            if len(existing) >= 2:
                if not rotate:
                    raise AwsError(f"IAM user {user_name} already has two access keys - tick 'rotate' to replace the oldest")
                oldest = sorted(existing, key=lambda e: e.get("CreateDate"))[0]
                iam.delete_access_key(UserName=user_name, AccessKeyId=oldest["AccessKeyId"])
            created = iam.create_access_key(UserName=user_name)["AccessKey"]
        except AwsError:
            raise
        except (ClientError, BotoCoreError) as exc:
            raise _wrap(exc, f"creating access key for {user_name}") from exc
        return AccessKey(access_key_id=created["AccessKeyId"], secret_access_key=created["SecretAccessKey"], created=True)

    def delete_access_key(self, user_name: str, access_key_id: str) -> None:
        """Delete one access key (used to retire a key after rotation)."""
        try:
            self.client("iam").delete_access_key(UserName=user_name, AccessKeyId=access_key_id)
        except (ClientError, BotoCoreError) as exc:
            raise _wrap(exc, f"deleting access key {access_key_id}") from exc

    # --- SES ---------------------------------------------------------------------------------------

    def ensure_ses_identity(self, identity: str, *, region: str | None = None) -> SesIdentity:
        """Create (or adopt) an SES v2 identity - a domain (Easy DKIM) or an address - and report its state."""
        ses = self.client("sesv2", region)
        kind = "address" if "@" in identity else "domain"
        created = False
        try:
            try:
                ses.get_email_identity(EmailIdentity=identity)
            except ClientError as exc:
                if exc.response.get("Error", {}).get("Code") != "NotFoundException":
                    raise
                try:
                    ses.create_email_identity(EmailIdentity=identity)
                    created = True
                except ClientError as create_exc:
                    # Raced with someone else creating it, or eventual consistency: adopt it.
                    if create_exc.response.get("Error", {}).get("Code") != "AlreadyExistsException":
                        raise
            return self._ses_state(identity, kind, created, region)
        except (ClientError, BotoCoreError) as exc:
            raise _wrap(exc, f"verifying SES identity {identity}") from exc

    def get_ses_identity(self, identity: str, *, region: str | None = None) -> SesIdentity | None:
        """Current state of an identity, or ``None`` when it does not exist."""
        try:
            return self._ses_state(identity, "address" if "@" in identity else "domain", False, region)
        except ClientError as exc:
            if exc.response.get("Error", {}).get("Code") == "NotFoundException":
                return None
            raise _wrap(exc, f"looking up SES identity {identity}") from exc
        except BotoCoreError as exc:
            raise _wrap(exc, f"looking up SES identity {identity}") from exc

    def _ses_state(self, identity: str, kind: str, created: bool, region: str | None) -> SesIdentity:
        data = self.client("sesv2", region).get_email_identity(EmailIdentity=identity)
        dkim = data.get("DkimAttributes", {}) or {}
        records: list[tuple[str, str]] = []
        if kind == "domain":
            for token in dkim.get("Tokens", []) or []:
                records.append((f"{token}._domainkey.{identity}", f"{token}.dkim.amazonses.com"))
        status = dkim.get("Status") or ("SUCCESS" if data.get("VerifiedForSendingStatus") else "PENDING")
        if data.get("VerifiedForSendingStatus"):
            status = "SUCCESS"
        return SesIdentity(identity=identity, kind=kind, status=status, created=created, dkim_records=records)

    def ses_configuration_set_exists(self, name: str, *, region: str | None = None) -> bool:
        """``get_configuration_set`` succeeds."""
        try:
            self.client("sesv2", region).get_configuration_set(ConfigurationSetName=name)
            return True
        except ClientError as exc:
            if exc.response.get("Error", {}).get("Code") == "NotFoundException":
                return False
            raise _wrap(exc, f"looking up SES configuration set {name}") from exc
        except BotoCoreError as exc:
            raise _wrap(exc, f"looking up SES configuration set {name}") from exc


def build_server_policy(*, kms_key_arn: str, bucket: str | None, ses: bool, backup_bucket: str | None = None) -> dict[str, Any]:
    """The inline policy document for the runtime IAM user - exactly the actions the backend calls."""
    statements: list[dict[str, Any]] = [
        {
            "Sid": "CloudDriverKms",
            "Effect": "Allow",
            "Action": ["kms:Encrypt", "kms:Decrypt", "kms:DescribeKey"],
            "Resource": kms_key_arn,
        }
    ]
    if bucket:
        statements.append(
            {
                "Sid": "CloudDriverS3Objects",
                "Effect": "Allow",
                # ListMultipartUploadParts: the resumable-upload service lists already-uploaded
                # parts to resume a session - without it every resume is AccessDenied.
                "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"],
                "Resource": f"arn:aws:s3:::{bucket}/*",
            }
        )
        statements.append(
            {
                "Sid": "CloudDriverS3Bucket",
                "Effect": "Allow",
                "Action": ["s3:ListBucket", "s3:ListBucketMultipartUploads"],
                "Resource": f"arn:aws:s3:::{bucket}",
            }
        )
    if backup_bucket:
        # Deliberately no DeleteObject: a compromised server key must not be able to wipe the
        # off-site copies together with the primary data.
        statements.append(
            {
                "Sid": "CloudDriverBackupObjects",
                "Effect": "Allow",
                "Action": ["s3:PutObject", "s3:GetObject"],
                "Resource": f"arn:aws:s3:::{backup_bucket}/*",
            }
        )
        statements.append(
            {
                "Sid": "CloudDriverBackupBucket",
                "Effect": "Allow",
                "Action": ["s3:ListBucket"],
                "Resource": f"arn:aws:s3:::{backup_bucket}",
            }
        )
    if ses:
        statements.append(
            {
                "Sid": "CloudDriverSes",
                "Effect": "Allow",
                "Action": ["ses:SendEmail", "ses:SendRawEmail"],
                "Resource": "*",
            }
        )
    return {"Version": "2012-10-17", "Statement": statements}


def render_aws_credentials(access_key_id: str, secret_access_key: str, existing: str | None = None) -> str:
    """``~/.aws/credentials`` with the ``[default]`` section replaced, other sections preserved."""
    others = _strip_ini_section(existing or "", "default")
    block = f"[default]\naws_access_key_id = {access_key_id}\naws_secret_access_key = {secret_access_key}\n"
    return block + ("\n" + others if others.strip() else "")


def render_aws_config(region: str, existing: str | None = None) -> str:
    """``~/.aws/config`` with the ``[default]`` section replaced, other sections preserved."""
    others = _strip_ini_section(existing or "", "default")
    block = f"[default]\nregion = {region}\noutput = json\n"
    return block + ("\n" + others if others.strip() else "")


def _strip_ini_section(text: str, section: str) -> str:
    """Remove ``[section]`` and its body from an INI-style text."""
    out: list[str] = []
    skipping = False
    for line in text.splitlines():
        match = re.match(r"^\s*\[(.+?)\]\s*$", line)
        if match:
            skipping = match.group(1).strip() == section
        if not skipping:
            out.append(line)
    return "\n".join(out).strip("\n") + ("\n" if out else "")

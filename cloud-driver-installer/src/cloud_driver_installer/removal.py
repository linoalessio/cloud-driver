"""What removing the whole deployment deletes, in which order, and what it deliberately leaves.

A full removal is the step catalog run backwards: the intelligence service before the application,
the application before the configuration files it reads, the data stores before the packages they
came with, and the directory layout last, once everything that lived in it is already gone. Each
step still describes its own footprint through
:meth:`~cloud_driver_installer.engine.Step.describe_removal` - this module only puts those
sentences in removal order and adds the one thing no single step can say: what survives a full
wipe, and why.

Deliberately free of tkinter: the window renders :func:`removal_items` and :func:`retained_items`,
and the tests read them without a display.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Iterable

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.engine import Step
    from cloud_driver_installer.model import InstallPlan


@dataclass(frozen=True)
class RemovalItem:
    """One step's contribution to a full removal, in the order it will be removed."""

    step_id: str
    title: str
    description: str


def removal_steps(steps: Iterable["Step"] | None = None) -> list["Step"]:
    """Every removable step, newest first - the order :meth:`Runner.remove_all` uses.

    The selection on the sidebar is deliberately ignored: a step that is unticked (or whose feature
    is switched off in the plan) may still be installed on the server from an earlier run, and
    every ``remove`` is a no-op when its footprint is not there. "Remove everything" therefore
    means every step, not every selected step.
    """
    from cloud_driver_installer.steps import all_steps

    catalog = list(steps) if steps is not None else all_steps()
    return [step for step in reversed(catalog) if step.removable]


def removal_items(plan: "InstallPlan", steps: Iterable["Step"] | None = None) -> list[RemovalItem]:
    """The confirmation dialog's list: every step's own description of what it deletes."""
    return [RemovalItem(step.id, step.title, step.describe_removal(plan)) for step in removal_steps(steps)]


def retained_items(plan: "InstallPlan") -> list[str]:
    """What a full removal does not touch, named so the operator can see it is deliberate.

    Two kinds of thing end up here: what removal must never take (the AWS resources the data is
    encrypted under and stored in, a server that is not this deployment's) and what the host needs
    to keep working afterwards (Debian's own packages, the machine's own settings).
    """
    aws = plan.aws
    items = [
        f"AWS: the KMS key {aws.kms_key_id or aws.kms_alias} - every row ever written to the database is encrypted under it, "
        "so deleting it would make the data and the backups unreadable; it is deleted in the AWS console or not at all",
    ]
    if aws.s3_enabled:
        items.append(f"AWS: the bucket {aws.s3_bucket} - it holds the file content itself, and it is emptied and deleted in the console")
    if aws.s3_enabled and plan.app.backup_offsite:
        items.append(f"AWS: the backup bucket {aws.effective_backup_bucket} - the off-site database backups live there")
    if aws.server_identity == "iam_user":
        items.append(f"AWS: the IAM user {aws.iam_user_name} - only its access key on the server is deleted")
    if plan.email.mode == "ses":
        items.append(f"AWS: the verified SES identity for {plan.email.ses_from_address} - remove it in the SES console if you want it gone")
    if plan.postgres.mode == "external":
        items.append(f"the external PostgreSQL server {plan.postgres.host}:{plan.postgres.port} - only this deployment's database is dropped, never the server or its role")
    if plan.redis.enabled and plan.redis.mode == "external":
        items.append(f"the external Redis at {plan.redis.host}:{plan.redis.port} - only this deployment's credentials file is deleted")
    items.append(
        "the host's own settings: timezone, clock synchronisation, unattended upgrades and the root public key - "
        "they are the machine's, not this deployment's"
    )
    items.append("the packages Debian itself needs: cron, curl, ca-certificates and python3 stay, whatever else is purged")
    items.append("any other site in the Caddyfile (the apex homepage included) - Caddy itself is only purged when nothing else is served")
    return items


def confirmation_phrase(plan: "InstallPlan") -> str:
    """What the operator has to type to arm the button: the server's own address.

    A wipe is the one action here with no undo and no partial re-run, so it is not a yes/no box:
    typing the host is a deliberate act that cannot be done to the wrong server by reflex.
    """
    return plan.ssh.host.strip()


def matches_confirmation(plan: "InstallPlan", typed: str) -> bool:
    """Whether ``typed`` is the phrase for this plan (case-insensitive, surrounding space ignored)."""
    phrase = confirmation_phrase(plan)
    return bool(phrase) and typed.strip().casefold() == phrase.casefold()


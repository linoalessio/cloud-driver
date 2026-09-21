"""The base-packages step: the apt packages every later step or the runtime relies on.

The list mirrors ``shell/provision-root-server.sh`` step 1 minus the services that have their own
steps (Java, PostgreSQL, Redis, ClamAV, ufw) plus ``openssl`` (secret generation on the server),
``fonts-dejavu-core`` (PDFBox thumbnails of PDFs without embedded fonts) and, only when the
off-site backup copy is wanted, ``awscli`` for the daily ``aws s3 sync`` cron line.
"""

from __future__ import annotations

from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError

#: Always installed.
BASE_PACKAGES: tuple[str, ...] = (
    "screen",
    "curl",
    "gnupg",
    "ca-certificates",
    "apt-transport-https",
    "openssl",
    "unzip",
    "debian-keyring",
    "debian-archive-keyring",
    "fonts-dejavu-core",
)

#: Added when ``plan.app.backup_offsite`` is on (the cron line runs ``aws s3 sync``).
OFFSITE_BACKUP_PACKAGES: tuple[str, ...] = ("awscli",)


def required_packages(plan: InstallPlan) -> list[str]:
    """The package list this plan needs, in install order."""
    packages = list(BASE_PACKAGES)
    if plan.app.backup_offsite:
        packages.extend(OFFSITE_BACKUP_PACKAGES)
    return packages


class PackagesStep(Step):
    """Installs the base apt packages that are missing according to dpkg."""

    id = "packages"
    title = "Base packages"
    mandatory = True

    def describe(self, plan: InstallPlan) -> str:
        """One line for the summary page."""
        return "install base packages: " + " ".join(required_packages(plan))

    # --- phases ----------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """dpkg says every package is installed -> OK, else the missing ones are listed."""
        required = required_packages(ctx.plan)
        missing = self._missing(ctx, required)
        if not missing:
            return CheckResult.ok(f"{len(required)} base packages installed")
        return CheckResult.needs_apply(f"install {', '.join(missing)}")

    def apply(self, ctx: Context) -> None:
        """``apt-get install`` only what is missing."""
        missing = self._missing(ctx, required_packages(ctx.plan))
        if not missing:
            ctx.debug("every base package already installed")
            return
        ctx.info("installing " + " ".join(missing))
        try:
            ctx.remote.apt_install(missing)
        except RemoteError as exc:
            raise StepError(f"apt-get install of {' '.join(missing)} failed: {exc}") from exc

    def verify(self, ctx: Context) -> VerifyResult:
        """Every package installed and ``screen`` (the one the start script needs) on the PATH."""
        required = required_packages(ctx.plan)
        missing = self._missing(ctx, required)
        if missing:
            return VerifyResult(False, f"still missing after apt-get: {', '.join(missing)}")
        if not ctx.remote.command_exists("screen"):
            return VerifyResult(False, "the screen package is installed but `screen` is not on the PATH")
        return VerifyResult(True, f"{len(required)} base packages installed, screen available")

    # --- helpers ---------------------------------------------------------------------------------

    @staticmethod
    def _missing(ctx: Context, required: list[str]) -> list[str]:
        missing: list[str] = []
        for package in required:
            ctx.check_cancelled()
            if not ctx.remote.dpkg_installed(package):
                missing.append(package)
        return missing

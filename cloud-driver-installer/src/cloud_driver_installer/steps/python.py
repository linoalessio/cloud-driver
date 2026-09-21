"""The Python 3 step: ``python3`` plus a ``venv`` module that actually yields ``pip``.

The intelligence service is installed into ``/opt/cloud-driver-intelligence/.venv``; on
Debian/Ubuntu ``python3 -m venv`` succeeds even without the ``python3-venv`` package but leaves
the environment without ``bin/pip`` (``ensurepip`` is what that package ships), which then fails
much later inside ``pip install -e``. So instead of trusting dpkg this step really creates a
throwaway venv and looks for the pip binary - the same probe the server step's preflight runs.

The encrypted vector store (the ``database-driver`` Python edition) needs Python 3.11 or newer;
Ubuntu 22.04 ships 3.10, so that combination is refused here instead of failing on the server.
"""

from __future__ import annotations

import re

from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError

#: Creates a venv in a temp dir and succeeds only when it got a ``bin/pip``; cleans up after itself.
VENV_PROBE_COMMAND = 'd=$(mktemp -d); python3 -m venv "$d/v" >/dev/null 2>&1 && test -x "$d/v/bin/pip"; r=$?; rm -rf "$d"; exit $r'

#: ``python3 --version`` prints to stdout on Python 3; the redirect covers the odd build that does not.
PYTHON_VERSION_COMMAND = "python3 --version 2>&1"

#: The apt packages that make the probe pass on Debian/Ubuntu.
PYTHON_PACKAGES: tuple[str, ...] = ("python3", "python3-venv", "python3-pip")

#: Minimum interpreter for the intelligence service's encrypted vector store.
ENCRYPTION_MIN_VERSION: tuple[int, int] = (3, 11)

_VERSION_RE = re.compile(r"(\d+\.\d+(?:\.\d+)?)")


def parse_python_version(text: str) -> str:
    """``"Python 3.11.2"`` -> ``"3.11.2"``; ``""`` when no version number is present."""
    match = _VERSION_RE.search(text or "")
    return match.group(1) if match else ""


def version_tuple(version: str) -> tuple[int, ...]:
    """``"3.11.2"`` -> ``(3, 11, 2)``; ``(0,)`` for blank input."""
    return tuple(int(part) for part in re.findall(r"\d+", version or "")[:3]) or (0,)


def probe_python_version(ctx: Context) -> str:
    """The server's ``python3`` version string, or ``""`` when there is no ``python3``."""
    result = ctx.remote.run(PYTHON_VERSION_COMMAND, quiet=True)
    return parse_python_version(result.out + result.err) if result.ok else ""


def probe_venv(ctx: Context) -> bool:
    """Run the venv probe on the server (see :data:`VENV_PROBE_COMMAND`)."""
    return ctx.remote.run_ok(VENV_PROBE_COMMAND)


class PythonStep(Step):
    """Installs ``python3``/``python3-venv``/``python3-pip`` until a venv with pip can be created."""

    id = "python"
    title = "Python 3"
    mandatory = True

    def describe(self, plan: InstallPlan) -> str:
        """One line for the summary page."""
        return "install " + ", ".join(PYTHON_PACKAGES) + " (verified by creating a throwaway venv with pip)"

    # --- phases ----------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """python3 present and the venv probe passes -> OK."""
        version = probe_python_version(ctx)
        works = probe_venv(ctx) if version else False
        ctx.discovered.python_version = version
        ctx.discovered.venv_works = works
        self._require_encryption_capable(ctx, version)
        if version and works:
            return CheckResult.ok(f"Python {version}, venv + pip work")
        if not version:
            return CheckResult.needs_apply("install " + ", ".join(PYTHON_PACKAGES) + " (no python3 found)")
        return CheckResult.needs_apply(f"Python {version} present but `python3 -m venv` yields no pip: install python3-venv, python3-pip")

    def apply(self, ctx: Context) -> None:
        """Install the packages; fall back to the version-specific ``python3.X-venv`` when needed."""
        version = probe_python_version(ctx)
        if version and probe_venv(ctx):
            ctx.debug(f"Python {version} with a working venv already present - nothing to install")
            ctx.discovered.python_version = version
            ctx.discovered.venv_works = True
            return
        packages = list(PYTHON_PACKAGES)
        ctx.info("installing " + " ".join(packages))
        try:
            ctx.remote.apt_install(packages)
        except RemoteError as exc:
            raise StepError(f"apt-get install of {' '.join(packages)} failed: {exc}") from exc
        ctx.check_cancelled()
        version = probe_python_version(ctx)
        works = probe_venv(ctx) if version else False
        if version and not works:
            major_minor = ".".join(version.split(".")[:2])
            specific = f"python{major_minor}-venv"
            ctx.info(f"venv probe still failing after python3-venv - installing {specific}")
            try:
                ctx.remote.apt_install([specific])
            except RemoteError as exc:
                raise StepError(f"apt-get install of {specific} failed: {exc}") from exc
            works = probe_venv(ctx)
        ctx.discovered.python_version = version
        ctx.discovered.venv_works = works

    def verify(self, ctx: Context) -> VerifyResult:
        """The probe must pass on the real interpreter."""
        version = probe_python_version(ctx)
        works = probe_venv(ctx) if version else False
        ctx.discovered.python_version = version
        ctx.discovered.venv_works = works
        if not version:
            return VerifyResult(False, "python3 is still not found after the install")
        if not works:
            return VerifyResult(False, f"Python {version} installed but `python3 -m venv` yields no bin/pip (python3-venv missing?)")
        if self._encryption_needs_newer(ctx, version):
            return VerifyResult(False, self._encryption_message(version))
        return VerifyResult(True, f"Python {version}, venv + pip work")

    # --- helpers ---------------------------------------------------------------------------------

    @staticmethod
    def _encryption_needs_newer(ctx: Context, version: str) -> bool:
        plan = ctx.plan.intelligence
        return bool(version) and plan.enabled and plan.enable_encryption and version_tuple(version) < ENCRYPTION_MIN_VERSION

    @staticmethod
    def _encryption_message(version: str) -> str:
        minimum = ".".join(str(part) for part in ENCRYPTION_MIN_VERSION)
        return (
            f"the intelligence service's encrypted vector store needs Python {minimum}+ but the server has "
            f"Python {version} (Ubuntu 22.04 ships 3.10) - use Debian 12/13 or Ubuntu 24.04, or disable "
            "at-rest encryption on the Intelligence page"
        )

    def _require_encryption_capable(self, ctx: Context, version: str) -> None:
        if self._encryption_needs_newer(ctx, version):
            raise StepError(self._encryption_message(version))

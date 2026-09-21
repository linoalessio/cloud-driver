"""The Java step: OpenJDK 21 (headless) as the default ``java``.

The backend is a Java 21 build; ``shell/provision-root-server.sh`` installs
``openjdk-21-jdk-headless`` and this step does the same. A box that already has another JDK as
its ``update-alternatives`` default keeps it unless this step switches the alternative, so after
the install ``java -version`` is probed again and the 21 binary is selected when needed.
"""

from __future__ import annotations

import re

from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError

#: The package the reference deployment runs on.
JAVA_PACKAGE = "openjdk-21-jdk-headless"
#: Major version the backend is built for.
REQUIRED_MAJOR = 21
#: ``java -version`` writes to stderr; the redirect puts it where the result's ``out`` is.
JAVA_VERSION_COMMAND = "java -version 2>&1"
#: Best-effort switch of the default ``java`` (the glob is expanded by the remote bash).
ALTERNATIVES_COMMAND = "update-alternatives --set java /usr/lib/jvm/java-21-openjdk-*/bin/java"

_VERSION_RE = re.compile(r'version "([^"]+)"')


def parse_java_version(text: str) -> str:
    """``openjdk version "21.0.4" 2024-07-16`` -> ``"21.0.4"``; ``""`` when absent."""
    match = _VERSION_RE.search(text or "")
    return match.group(1) if match else ""


def java_major(version: str) -> int:
    """``"21.0.4"`` -> 21, legacy ``"1.8.0_292"`` -> 8, ``""`` -> 0."""
    parts = re.findall(r"\d+", version or "")
    if not parts:
        return 0
    if parts[0] == "1" and len(parts) > 1:
        return int(parts[1])
    return int(parts[0])


def probe_java_version(ctx: Context) -> str:
    """The version string ``java -version`` reports on the server, or ``""``."""
    result = ctx.remote.run(JAVA_VERSION_COMMAND, quiet=True)
    return parse_java_version(result.out + result.err) if result.ok else ""


class JavaStep(Step):
    """Installs OpenJDK 21 and makes sure ``java`` on the PATH is that JDK."""

    id = "java"
    title = "Java 21"
    mandatory = True

    def describe(self, plan: InstallPlan) -> str:
        """One line for the summary page."""
        return f"install {JAVA_PACKAGE} and make Java {REQUIRED_MAJOR} the default java"

    # --- phases ----------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """``java -version`` major 21 -> OK."""
        version = probe_java_version(ctx)
        ctx.discovered.java_version = version
        if java_major(version) == REQUIRED_MAJOR:
            return CheckResult.ok(f"Java {version} (OpenJDK {REQUIRED_MAJOR}) is the default java")
        if version:
            return CheckResult.needs_apply(f"install {JAVA_PACKAGE} (java {version} found, {REQUIRED_MAJOR} required)")
        return CheckResult.needs_apply(f"install {JAVA_PACKAGE} (no java found)")

    def apply(self, ctx: Context) -> None:
        """Install the package if missing; switch the alternative when another JDK stays default."""
        version = probe_java_version(ctx)
        if java_major(version) == REQUIRED_MAJOR:
            ctx.debug(f"Java {version} already the default - nothing to install")
            ctx.discovered.java_version = version
            return
        if not ctx.remote.dpkg_installed(JAVA_PACKAGE):
            ctx.info(f"installing {JAVA_PACKAGE}")
            try:
                ctx.remote.apt_install([JAVA_PACKAGE])
            except RemoteError as exc:
                raise StepError(f"apt-get install of {JAVA_PACKAGE} failed: {exc}") from exc
        ctx.check_cancelled()
        version = probe_java_version(ctx)
        if java_major(version) != REQUIRED_MAJOR:
            ctx.info(f"java {version or '(none)'} is still the default - selecting the OpenJDK {REQUIRED_MAJOR} alternative")
            result = ctx.remote.run(ALTERNATIVES_COMMAND)
            if not result.ok:
                ctx.warn(f"update-alternatives could not select Java {REQUIRED_MAJOR}: {(result.err or result.out).strip()}")
            version = probe_java_version(ctx)
        ctx.discovered.java_version = version

    def verify(self, ctx: Context) -> VerifyResult:
        """``java -version`` must report 21."""
        version = probe_java_version(ctx)
        ctx.discovered.java_version = version
        if java_major(version) == REQUIRED_MAJOR:
            return VerifyResult(True, f"java -version reports {version}")
        return VerifyResult(False, f"java -version reports {version or 'nothing'} - expected Java {REQUIRED_MAJOR}")

"""The swap step: a ``/swapfile`` of the requested size, activated and listed in ``/etc/fstab``.

Mirrors ``shell/provision-root-server.sh`` step 4 command for command (``fallocate`` with the
``dd`` fallback, ``chmod 600``, ``mkswap``, ``swapon``, the guarded fstab line). Swap is the
kernel-OOM safety net for the JVM heap this installer sizes; a box that already swaps on some
other device is left alone.
"""

from __future__ import annotations

from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError

SWAP_FILE = "/swapfile"
FSTAB = "/etc/fstab"
FSTAB_LINE = f"{SWAP_FILE} none swap sw 0 0"
#: Exact byte sizes so the comparison with the requested MB is not a guess from ``4G``.
SWAPON_COMMAND = "swapon --show=NAME,SIZE --bytes --noheadings"

MIB = 1024 * 1024


def parse_swapon(text: str) -> dict[str, int]:
    """``swapon --show=NAME,SIZE --bytes --noheadings`` output -> ``{device: size_bytes}``."""
    devices: dict[str, int] = {}
    for line in (text or "").splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        try:
            devices[parts[0]] = int(parts[1])
        except ValueError:
            continue
    return devices


def size_mib(size_bytes: int) -> int:
    """Whole MiB, rounded - ``mkswap`` keeps one page for its header so a 4096 MB file swaps 4 KiB less."""
    return int(round(size_bytes / MIB))


def create_script(size_mb: int) -> str:
    """The provisioning commands for a ``size_mb`` swapfile, as ``provision-root-server.sh`` runs them."""
    return "\n".join(
        [
            "set -e",
            f"fallocate -l {size_mb}M '{SWAP_FILE}' || dd if=/dev/zero of='{SWAP_FILE}' bs=1M count={size_mb}",
            f"chmod 600 '{SWAP_FILE}'",
            f"mkswap '{SWAP_FILE}'",
            f"swapon '{SWAP_FILE}'",
            f"grep -q '^{SWAP_FILE} ' {FSTAB} || echo '{FSTAB_LINE}' >> {FSTAB}",
        ]
    )


class SwapStep(Step):
    """Creates and activates ``/swapfile`` (size from the plan) unless swap is already there."""

    id = "swap"
    title = "Swap"

    def enabled(self, plan: InstallPlan) -> bool:
        """A size of 0 switches the step off."""
        return plan.server.swap_mb > 0

    def describe(self, plan: InstallPlan) -> str:
        """One line for the summary page."""
        return f"create a {plan.server.swap_mb} MB {SWAP_FILE} (fallocate, mkswap, swapon) and add it to {FSTAB}"

    # --- phases ----------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """``/swapfile`` active and large enough (or another swap device active) -> OK."""
        requested = ctx.plan.server.swap_mb
        devices = self._active(ctx)
        ctx.discovered.swap_mib = size_mib(sum(devices.values())) if devices else ctx.discovered.swap_mib
        if SWAP_FILE in devices:
            current = size_mib(devices[SWAP_FILE])
            if current < requested:
                return CheckResult.needs_apply(f"resize {SWAP_FILE} from {current} to {requested} MiB")
            if not self._in_fstab(ctx):
                return CheckResult.needs_apply(f"{SWAP_FILE} active ({current} MiB) but missing from {FSTAB}: add the fstab line")
            return CheckResult.ok(f"{SWAP_FILE} active ({current} MiB, in {FSTAB})")
        if devices:
            listing = ", ".join(f"{name} ({size_mib(size)} MiB)" for name, size in devices.items())
            return CheckResult.ok(f"swap already active on {listing} - leaving {SWAP_FILE} alone")
        return CheckResult.needs_apply(f"create {requested} MB {SWAP_FILE}, swapon, add to {FSTAB}")

    def apply(self, ctx: Context) -> None:
        """Create (or grow) and activate the swapfile; a foreign swap device means nothing to do."""
        requested = ctx.plan.server.swap_mb
        devices = self._active(ctx)
        try:
            if SWAP_FILE in devices:
                current = size_mib(devices[SWAP_FILE])
                if current >= requested:
                    if not self._in_fstab(ctx):
                        ctx.remote.run(f"grep -q '^{SWAP_FILE} ' {FSTAB} || echo '{FSTAB_LINE}' >> {FSTAB}", check=True)
                        ctx.info(f"added {SWAP_FILE} to {FSTAB}")
                    else:
                        ctx.debug(f"{SWAP_FILE} already active with {current} MiB - nothing to do")
                    return
                ctx.info(f"{SWAP_FILE} is {current} MiB, {requested} MiB requested - deactivating to recreate it")
                ctx.remote.run(f"swapoff '{SWAP_FILE}'", check=True)
            elif devices:
                ctx.debug("another swap device is active - not creating " + SWAP_FILE)
                return
            ctx.check_cancelled()
            ctx.info(f"creating a {requested} MB {SWAP_FILE}")
            ctx.remote.run(create_script(requested), check=True, timeout=None)
            ctx.info(f"{SWAP_FILE} active and listed in {FSTAB}")
        except RemoteError as exc:
            raise StepError(f"could not set up {SWAP_FILE}: {exc}") from exc

    def verify(self, ctx: Context) -> VerifyResult:
        """``swapon --show`` must list the swapfile (or the pre-existing device)."""
        devices = self._active(ctx)
        if SWAP_FILE in devices:
            ctx.discovered.swap_mib = size_mib(sum(devices.values()))
            return VerifyResult(True, f"{SWAP_FILE} active ({size_mib(devices[SWAP_FILE])} MiB)")
        if devices:
            ctx.discovered.swap_mib = size_mib(sum(devices.values()))
            return VerifyResult(True, "swap active on " + ", ".join(devices))
        return VerifyResult(False, "swapon --show lists no active swap")

    # --- helpers ---------------------------------------------------------------------------------

    @staticmethod
    def _active(ctx: Context) -> dict[str, int]:
        result = ctx.remote.run(SWAPON_COMMAND, quiet=True)
        return parse_swapon(result.out) if result.ok else {}

    @staticmethod
    def _in_fstab(ctx: Context) -> bool:
        text = ctx.remote.read_text(FSTAB)
        if text is None:
            return True  # unreadable: do not keep asking to add a line we cannot see
        return any(line.split() and line.split()[0] == SWAP_FILE for line in text.splitlines())

"""Tests for the swapfile step."""

from __future__ import annotations

from typing import Any

import pytest

from cloud_driver_installer.engine import Context, StepError, StepStatus
from cloud_driver_installer.steps.swap import FSTAB, FSTAB_LINE, SWAP_FILE, SwapStep, create_script, parse_swapon, size_mib

# mkswap keeps one 4 KiB page for its header, so a 4096 MB file shows 4 KiB less.
SWAPFILE_4G = "/swapfile 4294963200\n"
SWAPFILE_2G = "/swapfile 2147479552\n"
PARTITION = "/dev/sda2 2147479552\n"
FSTAB_WITH_SWAP = "UUID=abc / ext4 errors=remount-ro 0 1\n/swapfile none swap sw 0 0\n"
FSTAB_WITHOUT_SWAP = "UUID=abc / ext4 errors=remount-ro 0 1\n"


def test_enabled_follows_swap_mb(plan: Any) -> None:
    plan.server.swap_mb = 4096
    assert SwapStep().enabled(plan)
    plan.server.swap_mb = 0
    assert not SwapStep().enabled(plan)


def test_parse_helpers() -> None:
    assert parse_swapon(SWAPFILE_4G + PARTITION) == {"/swapfile": 4294963200, "/dev/sda2": 2147479552}
    assert parse_swapon("") == {}
    assert parse_swapon("NAME SIZE\n/swapfile 4G\n") == {}
    assert size_mib(4294963200) == 4096
    assert size_mib(2147479552) == 2048


def test_create_script_mirrors_provision_root_server(plan: Any) -> None:
    script = create_script(4096)
    assert script.splitlines() == [
        "set -e",
        "fallocate -l 4096M '/swapfile' || dd if=/dev/zero of='/swapfile' bs=1M count=4096",
        "chmod 600 '/swapfile'",
        "mkswap '/swapfile'",
        "swapon '/swapfile'",
        "grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab",
    ]


def test_check_bare_box_needs_apply(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", "")
    result = SwapStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert result.detail == f"create 4096 MB {SWAP_FILE}, swapon, add to {FSTAB}"
    assert not remote.ran("fallocate") and not remote.ran("mkswap")


def test_check_active_swapfile_is_ok(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", SWAPFILE_4G)
    remote.files[FSTAB] = FSTAB_WITH_SWAP
    result = SwapStep().check(ctx)
    assert result.status is StepStatus.OK
    assert result.detail == f"{SWAP_FILE} active (4096 MiB, in {FSTAB})"
    assert ctx.discovered.swap_mib == 4096


def test_check_other_swap_device_is_ok(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", PARTITION)
    result = SwapStep().check(ctx)
    assert result.status is StepStatus.OK
    assert "/dev/sda2 (2048 MiB)" in result.detail and "leaving /swapfile alone" in result.detail


def test_check_too_small_swapfile_needs_apply(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", SWAPFILE_2G)
    remote.files[FSTAB] = FSTAB_WITH_SWAP
    result = SwapStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert result.detail == "resize /swapfile from 2048 to 4096 MiB"


def test_check_missing_fstab_line_needs_apply(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", SWAPFILE_4G)
    remote.files[FSTAB] = FSTAB_WITHOUT_SWAP
    result = SwapStep().check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "add the fstab line" in result.detail


def test_apply_creates_the_swapfile(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", "")
    remote.ok("fallocate")
    SwapStep().apply(ctx)
    assert remote.count("fallocate") == 1
    script = next(cmd for cmd in remote.commands if "fallocate" in cmd)
    assert script == create_script(4096)
    assert not remote.ran("swapoff")


def test_apply_honours_the_requested_size(ctx: Context, remote: Any, plan: Any) -> None:
    plan.server.swap_mb = 2048
    remote.ok("swapon --show", "")
    remote.ok("fallocate")
    SwapStep().apply(ctx)
    assert remote.ran("fallocate -l 2048M '/swapfile' || dd if=/dev/zero of='/swapfile' bs=1M count=2048")


def test_apply_resizes_a_too_small_swapfile(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", SWAPFILE_2G)
    remote.ok("swapoff")
    remote.ok("fallocate")
    SwapStep().apply(ctx)
    assert remote.commands.index("swapoff '/swapfile'") < remote.commands.index(create_script(4096))


def test_apply_is_a_noop_when_the_swapfile_is_in_place(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", SWAPFILE_4G)
    remote.files[FSTAB] = FSTAB_WITH_SWAP
    SwapStep().apply(ctx)
    SwapStep().apply(ctx)
    assert not remote.ran("fallocate") and not remote.ran("swapoff") and not remote.ran("echo")


def test_apply_leaves_a_foreign_swap_device_alone(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", PARTITION)
    SwapStep().apply(ctx)
    assert not remote.ran("fallocate") and not remote.ran("swapoff")


def test_apply_adds_only_the_fstab_line_when_missing(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", SWAPFILE_4G)
    remote.files[FSTAB] = FSTAB_WITHOUT_SWAP
    remote.ok("grep -q")
    SwapStep().apply(ctx)
    assert remote.ran(f"grep -q '^{SWAP_FILE} ' {FSTAB} || echo '{FSTAB_LINE}' >> {FSTAB}")
    assert not remote.ran("fallocate")


def test_apply_failure_is_a_step_error(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", "")
    remote.fail("fallocate", "fallocate: fallocate failed: Operation not supported\ndd: error writing '/swapfile': No space left on device")
    with pytest.raises(StepError, match="could not set up /swapfile"):
        SwapStep().apply(ctx)


def test_verify(ctx: Context, remote: Any) -> None:
    remote.ok("swapon --show", SWAPFILE_4G)
    result = SwapStep().verify(ctx)
    assert result.ok and result.detail == "/swapfile active (4096 MiB)"
    assert ctx.discovered.swap_mib == 4096

    remote.rules.clear()
    remote.ok("swapon --show", PARTITION)
    result = SwapStep().verify(ctx)
    assert result.ok and "/dev/sda2" in result.detail

    remote.rules.clear()
    remote.ok("swapon --show", "")
    assert not SwapStep().verify(ctx).ok


def test_describe(plan: Any) -> None:
    line = SwapStep().describe(plan)
    assert "\n" not in line and "4096 MB" in line and "/etc/fstab" in line

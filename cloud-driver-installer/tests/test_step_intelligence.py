"""Tests for the intelligence step: the pure archive/env helpers and the step against a FakeRemote."""

from __future__ import annotations

import tarfile
from pathlib import Path

import pytest

from cloud_driver_installer.engine import Context, StepError, StepStatus
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.steps.intelligence import (
    ENV_CLIP,
    ENV_ENCRYPTION_KEY,
    ENV_FILE,
    ENV_OCR,
    ENV_OCR_LANGUAGES,
    ENV_SECRET,
    REMOTE_DIR,
    SOURCE_EXCLUDES,
    SOURCE_MEMBERS,
    TORCH_CPU_INDEX_URL,
    UNIT_NAME,
    UNIT_PATH,
    VENDOR_EXCLUDES,
    VENDOR_MEMBERS,
    IntelligenceStep,
    build_archive,
    env_has_key,
    hash_tree,
    is_excluded,
    merge_env_file,
    ocr_apt_packages,
    parse_sha256sum,
    venv_install_script,
)

from .fake_remote import FakeRemote

SECRET = "shared-intelligence-secret-0123456789abcdefghij"
OTHER_SECRET = "rotated-intelligence-secret-zyxwvutsrqponmlkji"
KEY = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5MDE="
HEALTH = '{"status":"ok","embeddingsAvailable":true,"persistentStore":true,"encryptedStore":true,"indexedDocuments":3}'
IS_ACTIVE = f"systemctl is-active --quiet {UNIT_NAME}"
HEALTH_PROBE = "curl -fsS -m 3 http://127.0.0.1:8600/health"
SOURCE_LISTING = f"cd '{REMOTE_DIR}' "
VENDOR_LISTING = f"cd '{REMOTE_DIR}/vendor'"


# --- helpers ----------------------------------------------------------------------------------------


def sha_listing(hashes: dict[str, str]) -> str:
    """Format a hash map the way ``sha256sum`` prints it."""
    return "".join(f"{digest}  {path}\n" for path, digest in hashes.items())


def seed_source(plan: InstallPlan) -> Path:
    """Put real files (and junk that must be excluded) into the fixture's intelligence module."""
    source = Path(plan.intelligence_source_dir)
    package = source / "src" / "cloud_driver_intelligence"
    (package / "app.py").write_text("app = 1\n")
    (package / "__pycache__").mkdir(exist_ok=True)
    (package / "__pycache__" / "app.cpython-311.pyc").write_bytes(b"\x00pyc")
    (package / "._app.py").write_bytes(b"\x00\x05\x16\x07")
    (source / "src" / "cloud_driver_intelligence.egg-info").mkdir(exist_ok=True)
    (source / "src" / "cloud_driver_intelligence.egg-info" / "PKG-INFO").write_text("Name: x\n")
    (source / "tests" / "test_app.py").write_text("def test(): pass\n")
    (source / ".venv").mkdir(exist_ok=True)
    (source / ".venv" / "pyvenv.cfg").write_text("home = /usr/bin\n")
    return source


def seed_vendor(plan: InstallPlan) -> Path:
    """Put files into the fixture's database-driver-v2 clone."""
    python_dir = Path(plan.intelligence_driver_clone_dir) / "python"
    for name in VENDOR_MEMBERS:
        (python_dir / name / "pyproject.toml").write_text(f"[project]\nname='{name}'\n")
        (python_dir / name / "tests").mkdir(exist_ok=True)
        (python_dir / name / "tests" / "test_x.py").write_text("def test(): pass\n")
    return python_dir


def enable(plan: InstallPlan, ctx: Context, **settings: object) -> IntelligenceStep:
    """Switch the feature on, seed the local trees and hand the step the resolved secret."""
    plan.intelligence.enabled = True
    for name, value in settings.items():
        setattr(plan.intelligence, name, value)
    seed_source(plan)
    seed_vendor(plan)
    ctx.secrets.intelligence_secret = SECRET
    ctx.remember_secret(SECRET)
    step = IntelligenceStep()
    step._sleep = lambda seconds: None
    return step


def provisioned(remote: FakeRemote, plan: InstallPlan, step: IntelligenceStep, *, env_text: str | None = None) -> None:
    """Script the fake as a box where everything is already in place and in sync."""
    remote.files[f"{REMOTE_DIR}/.venv/bin/uvicorn"] = ""
    remote.files[UNIT_PATH] = step.unit_file(plan).read_text()
    remote.files[ENV_FILE] = env_text if env_text is not None else f"{ENV_SECRET}={SECRET}\n{ENV_ENCRYPTION_KEY}={KEY}\n"
    remote.ok(IS_ACTIVE)
    remote.ok(HEALTH_PROBE, HEALTH)
    remote.on(VENDOR_LISTING, lambda cmd, inp: sha_listing(hash_tree(step.vendor_source_dir(plan), VENDOR_MEMBERS, VENDOR_EXCLUDES)))
    remote.on(SOURCE_LISTING, lambda cmd, inp: sha_listing(hash_tree(step.source_dir(plan), SOURCE_MEMBERS, SOURCE_EXCLUDES)))


def assert_no_leak(ctx: Context, remote: FakeRemote, *values: str) -> None:
    for value in values:
        assert all(value not in message for _, message in ctx.captured_log), f"secret leaked into the log"  # type: ignore[attr-defined]
        assert all(value not in command for command in remote.commands), "secret leaked onto a command line"


# --- pure helpers: archive ---------------------------------------------------------------------------


def test_is_excluded_matches_any_component() -> None:
    assert is_excluded("src/pkg/__pycache__/x.pyc", SOURCE_EXCLUDES)
    assert is_excluded("src/pkg.egg-info/PKG-INFO", SOURCE_EXCLUDES)
    assert is_excluded("._app.py", SOURCE_EXCLUDES)
    assert is_excluded("database-driver-api/tests/test_x.py", VENDOR_EXCLUDES)
    assert not is_excluded("tests/test_x.py", SOURCE_EXCLUDES)
    assert not is_excluded("src/pkg/app.py", SOURCE_EXCLUDES)


def test_build_archive_excludes_junk_and_normalises_owner(tmp_path: Path) -> None:
    base = tmp_path / "module"
    (base / "src" / "pkg" / "__pycache__").mkdir(parents=True)
    (base / "src" / "pkg" / "app.py").write_text("x = 1\n")
    (base / "src" / "pkg" / "__pycache__" / "app.pyc").write_bytes(b"pyc")
    (base / "src" / "pkg" / "._app.py").write_bytes(b"junk")
    (base / "src" / "pkg.egg-info").mkdir()
    (base / "src" / "pkg.egg-info" / "PKG-INFO").write_text("n\n")
    (base / "src" / ".venv").mkdir()
    (base / "src" / ".venv" / "bin").mkdir()
    (base / "src" / ".pytest_cache").mkdir()
    (base / "src" / ".pytest_cache" / "v").write_text("v")
    (base / "src" / ".mypy_cache").mkdir()
    (base / "src" / ".ruff_cache").mkdir()
    (base / "tests").mkdir()
    (base / "tests" / "test_app.py").write_text("def test(): pass\n")
    (base / "pyproject.toml").write_text("[project]\n")

    archive = build_archive(base, SOURCE_MEMBERS, SOURCE_EXCLUDES, tmp_path / "out" / "src.tar.gz")

    assert archive.is_file()
    with tarfile.open(archive, "r:gz") as tar:
        members = {m.name: m for m in tar.getmembers()}
    assert {"src", "src/pkg", "src/pkg/app.py", "tests", "tests/test_app.py", "pyproject.toml"} <= set(members)
    for name in members:
        assert "__pycache__" not in name
        assert "._" not in name
        assert ".egg-info" not in name
        assert ".venv" not in name
        assert ".pytest_cache" not in name and ".mypy_cache" not in name and ".ruff_cache" not in name
    info = members["src/pkg/app.py"]
    assert (info.uid, info.gid, info.uname, info.gname) == (0, 0, "root", "root")
    assert info.isfile()
    with tarfile.open(archive, "r:gz") as tar:
        extracted = tar.extractfile("src/pkg/app.py")
        assert extracted is not None and extracted.read() == b"x = 1\n"


def test_build_archive_vendor_drops_tests(tmp_path: Path) -> None:
    base = tmp_path / "python"
    for name in VENDOR_MEMBERS:
        (base / name / "src").mkdir(parents=True)
        (base / name / "src" / "mod.py").write_text("m = 1\n")
        (base / name / "tests").mkdir()
        (base / name / "tests" / "test_mod.py").write_text("t\n")
    archive = build_archive(base, VENDOR_MEMBERS, VENDOR_EXCLUDES, tmp_path / "vendor.tar.gz")
    with tarfile.open(archive, "r:gz") as tar:
        names = set(tar.getnames())
    assert "database-driver-api/src/mod.py" in names
    assert "database-driver-plugin/src/mod.py" in names
    assert not any("/tests" in name for name in names)


def test_build_archive_missing_member_raises(tmp_path: Path) -> None:
    base = tmp_path / "module"
    (base / "src").mkdir(parents=True)
    with pytest.raises(FileNotFoundError):
        build_archive(base, SOURCE_MEMBERS, SOURCE_EXCLUDES, tmp_path / "x.tar.gz")
    assert not (tmp_path / "x.tar.gz").exists()


def test_hash_tree_matches_archive_and_sha256sum_listing(tmp_path: Path) -> None:
    base = tmp_path / "module"
    (base / "src" / "pkg" / "__pycache__").mkdir(parents=True)
    (base / "src" / "pkg" / "app.py").write_text("x = 1\n")
    (base / "src" / "pkg" / "__pycache__" / "app.pyc").write_bytes(b"pyc")
    (base / "tests").mkdir()
    (base / "pyproject.toml").write_text("[project]\n")
    hashes = hash_tree(base, SOURCE_MEMBERS, SOURCE_EXCLUDES)
    assert set(hashes) == {"src/pkg/app.py", "pyproject.toml"}
    archive = build_archive(base, SOURCE_MEMBERS, SOURCE_EXCLUDES, tmp_path / "s.tar.gz")
    with tarfile.open(archive, "r:gz") as tar:
        files = {m.name for m in tar.getmembers() if m.isfile()}
    assert files == set(hashes)
    # the server's listing also carries __pycache__ its own Python created, plus ./ and * decorations
    listing = sha_listing(hashes) + f"{'0' * 64}  ./src/pkg/__pycache__/app.cpython-311.pyc\n{'1' * 64} *tests/x.py\n"
    parsed = parse_sha256sum(listing, SOURCE_EXCLUDES)
    assert parsed == {**hashes, "tests/x.py": "1" * 64}
    assert parse_sha256sum("", SOURCE_EXCLUDES) == {}
    assert parse_sha256sum("garbage line\n", SOURCE_EXCLUDES) == {}


# --- pure helpers: env file ------------------------------------------------------------------------


def test_merge_env_from_nothing_writes_secret_only() -> None:
    assert merge_env_file(None, SECRET, ocr=False, ocr_languages="deu+eng", clip=False) == f"{ENV_SECRET}={SECRET}\n"


def test_merge_env_preserves_key_and_replaces_secret() -> None:
    existing = (
        "# managed by hand\n"
        f"{ENV_SECRET}=old-secret-value\n"
        f"{ENV_ENCRYPTION_KEY}={KEY}\n"
        "CLOUD_DRIVER_INTELLIGENCE_LOG_LEVEL=debug\n"
    )
    merged = merge_env_file(existing, SECRET, ocr=False, ocr_languages="deu+eng", clip=False)
    assert merged == (
        "# managed by hand\n"
        f"{ENV_SECRET}={SECRET}\n"
        f"{ENV_ENCRYPTION_KEY}={KEY}\n"
        "CLOUD_DRIVER_INTELLIGENCE_LOG_LEVEL=debug\n"
    )
    assert "old-secret-value" not in merged
    assert merged.count(f"{ENV_SECRET}=") == 1
    assert env_has_key(merged, ENV_ENCRYPTION_KEY)
    # stable: merging the result again changes nothing
    assert merge_env_file(merged, SECRET, ocr=False, ocr_languages="deu+eng", clip=False) == merged


def test_merge_env_adds_then_removes_ocr_and_clip_flags() -> None:
    base = f"{ENV_SECRET}={SECRET}\n{ENV_ENCRYPTION_KEY}={KEY}\n"
    with_flags = merge_env_file(base, SECRET, ocr=True, ocr_languages="deu+eng", clip=True)
    assert f"{ENV_OCR}=true\n" in with_flags
    assert f"{ENV_OCR_LANGUAGES}=deu+eng\n" in with_flags
    assert f"{ENV_CLIP}=true\n" in with_flags
    assert with_flags.startswith(f"{ENV_SECRET}={SECRET}\n{ENV_ENCRYPTION_KEY}={KEY}\n")
    # language change is applied in place
    changed = merge_env_file(with_flags, SECRET, ocr=True, ocr_languages="eng", clip=True)
    assert f"{ENV_OCR_LANGUAGES}=eng\n" in changed and "deu+eng" not in changed
    without = merge_env_file(with_flags, SECRET, ocr=False, ocr_languages="deu+eng", clip=False)
    assert without == base
    # duplicates collapse, secret goes first when it was absent
    messy = f"FOO=1\n{ENV_OCR}=true\n{ENV_OCR}=false\n"
    assert merge_env_file(messy, SECRET, ocr=True, ocr_languages="deu", clip=False) == (
        f"{ENV_SECRET}={SECRET}\nFOO=1\n{ENV_OCR}=true\n{ENV_OCR_LANGUAGES}=deu\n"
    )


def test_ocr_apt_packages_and_venv_script() -> None:
    assert ocr_apt_packages("deu+eng") == ["tesseract-ocr", "poppler-utils", "tesseract-ocr-deu", "tesseract-ocr-eng"]
    assert ocr_apt_packages("eng+eng") == ["tesseract-ocr", "poppler-utils", "tesseract-ocr-eng"]
    script = venv_install_script(cpu_only_torch=True, ocr=True, clip=True)
    assert "[ -x .venv/bin/pip ] || { rm -rf .venv; python3 -m venv .venv; }" in script
    assert "./.venv/bin/pip install --quiet --upgrade pip" in script
    assert f"./.venv/bin/pip install --quiet --extra-index-url {TORCH_CPU_INDEX_URL} -e '.[embeddings,store]'" in script
    assert "./.venv/bin/pip install --quiet vendor/*/" in script
    assert "./.venv/bin/pip install --quiet -e '.[encryption]'" in script
    assert "-e '.[ocr]'" in script and "-e '.[clip]'" in script
    assert "import cloud_driver_intelligence" in script
    plain = venv_install_script(cpu_only_torch=False, ocr=False, clip=False)
    assert "extra-index-url" not in plain and "[ocr]" not in plain and "[clip]" not in plain


# --- step: enabled / describe -----------------------------------------------------------------------


def test_enabled_follows_plan(plan: InstallPlan) -> None:
    step = IntelligenceStep()
    assert step.id == "intelligence" and step.depends_on == ("python", "config")
    plan.intelligence.enabled = False
    assert not step.enabled(plan)
    plan.intelligence.enabled = True
    assert step.enabled(plan)


def test_describe_mirrors_plan(plan: InstallPlan) -> None:
    plan.intelligence.enabled = True
    plan.intelligence.ocr = True
    plan.intelligence.clip = True
    line = IntelligenceStep().describe(plan)
    assert "\n" not in line
    assert REMOTE_DIR in line and ENV_FILE in line and UNIT_NAME in line
    assert "encryption" in line and "ocr" in line and "clip" in line and "CPU-only torch" in line
    assert "tesseract-ocr" in line and "deu+eng" in line


# --- step: check --------------------------------------------------------------------------------------


def test_check_needs_apply_on_bare_box(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    result = step.check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    for word in ("venv", "unit file", "env file", "service", "/health", "source differs", "encryption key"):
        assert word in result.detail, result.detail
    assert not ctx.discovered.intelligence_installed
    assert ctx.secrets.intelligence_encryption_key_present is False
    assert_no_leak(ctx, remote, SECRET)


def test_check_ok_on_provisioned_box(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    provisioned(remote, plan, step)
    result = step.check(ctx)
    assert result.status is StepStatus.OK, result.detail
    assert "source in sync" in result.detail and "encryption key present" in result.detail and "/health" in result.detail
    assert ctx.discovered.intelligence_installed
    assert ctx.secrets.intelligence_encryption_key_present
    assert KEY in ctx.redactor  # the key read back from the env file is masked from now on
    # read-only: nothing written, nothing installed, no service touched
    assert remote.uploads == [] and remote.apt_installed == [] and remote.systemctl_calls == []
    assert not any(cmd.startswith(("tar ", "rm ", "mkdir")) for cmd in remote.commands)
    assert_no_leak(ctx, remote, SECRET, KEY)


def test_check_detects_source_drift_and_env_changes(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx, ocr=True)
    provisioned(remote, plan, step)
    remote.rules = [rule for rule in remote.rules if rule.needle != SOURCE_LISTING]
    remote.on(SOURCE_LISTING, lambda cmd, inp: sha_listing({"pyproject.toml": "f" * 64}))
    result = step.check(ctx)
    assert result.status is StepStatus.NEEDS_APPLY
    assert "source differs" in result.detail
    assert "OCR on (deu+eng)" in result.detail
    assert "OCR packages" in result.detail


def test_check_secret_falls_back_to_remote_configuration(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    ctx.secrets.intelligence_secret = ""
    remote.files[f"{plan.config_dir}/configuration.json"] = f'{{"intelligence-shared-secret": "{OTHER_SECRET}"}}\n'
    provisioned(remote, plan, step, env_text=f"{ENV_SECRET}={SECRET}\n{ENV_ENCRYPTION_KEY}={KEY}\n")
    result = step.check(ctx)
    assert ctx.secrets.intelligence_secret == OTHER_SECRET
    assert ctx.secrets.intelligence_secret_kept
    assert result.status is StepStatus.NEEDS_APPLY and "shared secret" in result.detail
    assert any(level == "WARN" and "configuration.json wins" in message for level, message in ctx.captured_log)  # type: ignore[attr-defined]
    assert_no_leak(ctx, remote, SECRET, OTHER_SECRET, KEY)


def test_check_without_any_secret_raises(plan: InstallPlan, ctx: Context) -> None:
    step = enable(plan, ctx)
    ctx.secrets.intelligence_secret = ""
    with pytest.raises(StepError, match="Configuration files step"):
        step.check(ctx)


# --- step: apply ------------------------------------------------------------------------------------


def test_apply_on_bare_box(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    remote.default_ok = True
    remote.ok("openssl rand -base64 32", KEY + "\n")
    progress: list[tuple[float, str]] = []
    ctx.progress_fn = lambda fraction, text: progress.append((fraction, text))

    step.apply(ctx)

    # source + vendor tarballs uploaded to /tmp, extracted after the mirror rm -rf, then removed
    uploaded = [remote_path for _, remote_path in remote.uploads]
    assert "/tmp/cloud-driver-intelligence-src.tar.gz" in uploaded
    assert "/tmp/cloud-driver-intelligence-vendor.tar.gz" in uploaded
    assert remote.ran(f"mkdir -p '{REMOTE_DIR}' && rm -rf '{REMOTE_DIR}/src' '{REMOTE_DIR}/tests' && tar xzf '/tmp/cloud-driver-intelligence-src.tar.gz' -C '{REMOTE_DIR}' && rm -f '/tmp/cloud-driver-intelligence-src.tar.gz'")
    assert remote.ran(f"rm -rf '{REMOTE_DIR}/vendor' && mkdir -p '{REMOTE_DIR}/vendor' && tar xzf '/tmp/cloud-driver-intelligence-vendor.tar.gz' -C '{REMOTE_DIR}/vendor'")
    assert remote.ran(f"find '{REMOTE_DIR}' -maxdepth 2 -name '._*' -delete 2>/dev/null || true")
    assert remote.ran(f"find '{REMOTE_DIR}/vendor' -name '._*' -delete 2>/dev/null || true")
    # the uploaded archive really excludes junk
    local_src = next(local for local, remote_path in remote.uploads if remote_path.endswith("-src.tar.gz"))
    assert not Path(local_src).exists()  # temp dir cleaned up
    assert remote.files["/tmp/cloud-driver-intelligence-src.tar.gz"]  # bytes were transferred
    # venv build mirrors the script
    assert remote.count("python3 -m venv .venv") == 1
    assert remote.ran(f"./.venv/bin/pip install --quiet --extra-index-url {TORCH_CPU_INDEX_URL} -e '.[embeddings,store]'")
    assert remote.ran("./.venv/bin/pip install --quiet vendor/*/")
    assert remote.ran("-e '.[encryption]'")
    assert not remote.ran("'.[ocr]'") and not remote.ran("'.[clip]'")
    # env file: secret + generated key, 0600, never on a command line
    env = remote.files[ENV_FILE]
    assert env == f"{ENV_SECRET}={SECRET}\n{ENV_ENCRYPTION_KEY}={KEY}\n"
    assert remote.modes[ENV_FILE] == 0o600
    assert ctx.secrets.intelligence_encryption_key_present
    assert KEY in ctx.redactor
    # unit file shipped from the source tree, then daemon-reload / enable / restart
    assert remote.files[UNIT_PATH] == step.unit_file(plan).read_text()
    assert (str(step.unit_file(plan)), UNIT_PATH) in remote.uploads
    assert remote.systemctl_calls == [("daemon-reload",), ("enable", "--quiet", UNIT_NAME), ("restart", UNIT_NAME)]
    assert remote.apt_installed == []
    assert progress and progress[-1][0] == 1.0
    assert_no_leak(ctx, remote, SECRET, KEY)


def test_apply_rerun_keeps_encryption_key_and_changes_nothing(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    remote.default_ok = True
    remote.files[ENV_FILE] = f"{ENV_SECRET}={SECRET}\n{ENV_ENCRYPTION_KEY}={KEY}\nEXTRA=kept\n"
    remote.files[UNIT_PATH] = step.unit_file(plan).read_text()
    remote.ok("openssl rand -base64 32", "SHOULD-NEVER-BE-USED-0123456789abcdef=")

    step.apply(ctx)

    assert remote.files[ENV_FILE] == f"{ENV_SECRET}={SECRET}\n{ENV_ENCRYPTION_KEY}={KEY}\nEXTRA=kept\n"
    assert not remote.ran("openssl rand")
    assert remote.backups == []  # identical content: not rewritten, so no backup either
    assert (str(step.unit_file(plan)), UNIT_PATH) not in remote.uploads  # unit already current
    assert ctx.secrets.intelligence_encryption_key_present
    assert not step._encryption_just_enabled
    # still restarted so the freshly mirrored source is what runs
    assert ("restart", UNIT_NAME) in remote.systemctl_calls


def test_apply_replaces_rotated_secret_and_backs_up(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    remote.default_ok = True
    remote.files[ENV_FILE] = f"{ENV_SECRET}=old-secret-value-abcdefghijklmnop\n{ENV_ENCRYPTION_KEY}={KEY}\n"
    ctx.secrets.intelligence_secret = OTHER_SECRET
    ctx.remember_secret(OTHER_SECRET)

    step.apply(ctx)

    assert remote.files[ENV_FILE] == f"{ENV_SECRET}={OTHER_SECRET}\n{ENV_ENCRYPTION_KEY}={KEY}\n"
    assert remote.modes[ENV_FILE] == 0o600
    assert remote.backups == [f"{ENV_FILE}.bak-TEST"]
    assert remote.files[f"{ENV_FILE}.bak-TEST"].startswith(f"{ENV_SECRET}=old-secret-value")
    assert not remote.ran("openssl rand")
    assert_no_leak(ctx, remote, OTHER_SECRET, KEY, "old-secret-value-abcdefghijklmnop")


def test_apply_ocr_and_clip(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx, ocr=True, ocr_languages="deu+eng", clip=True)
    remote.default_ok = True
    remote.fail("dpkg-query")
    remote.ok("dpkg-query -W -f='${Status}' poppler-utils", "install ok installed")
    remote.rules.reverse()  # the specific dpkg rule must win over the generic failure
    remote.ok("openssl rand -base64 32", KEY)

    step.apply(ctx)

    assert remote.apt_installed == ["tesseract-ocr", "tesseract-ocr-deu", "tesseract-ocr-eng"]
    assert remote.ran("-e '.[ocr]'")
    assert remote.ran(f"--extra-index-url {TORCH_CPU_INDEX_URL} -e '.[clip]'")
    env = remote.files[ENV_FILE]
    assert f"{ENV_OCR}=true\n" in env and f"{ENV_OCR_LANGUAGES}=deu+eng\n" in env and f"{ENV_CLIP}=true\n" in env


def test_apply_without_clone_leaves_vendor_alone(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx, enable_encryption=False)
    plan.intelligence.driver_clone_dir = str(Path(plan.app.repo_root).parent / "nowhere")
    remote.default_ok = True

    step.apply(ctx)

    assert not remote.ran(f"rm -rf '{REMOTE_DIR}/vendor'")
    assert not any(remote_path.endswith("-vendor.tar.gz") for _, remote_path in remote.uploads)
    assert remote.ran("if [ -d vendor ]; then")  # an existing remote vendor/ is still (re)installed
    assert not remote.ran("openssl rand")
    assert remote.files[ENV_FILE] == f"{ENV_SECRET}={SECRET}\n"
    assert any("leaving any existing vendor/" in message for _, message in ctx.captured_log)  # type: ignore[attr-defined]


def test_apply_incomplete_clone_raises(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    import shutil

    shutil.rmtree(Path(plan.intelligence_driver_clone_dir) / "python" / "database-driver-plugin")
    remote.default_ok = True
    with pytest.raises(StepError, match="database-driver-plugin is missing"):
        step.apply(ctx)


def test_apply_non_default_port_adjusts_unit(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx, port=8601, enable_encryption=False)
    step.unit_file(plan).write_text("[Service]\nExecStart=/opt/x/.venv/bin/uvicorn app --host 127.0.0.1 --port 8600\n")
    remote.default_ok = True
    step.apply(ctx)
    assert "--port 8601" in remote.files[UNIT_PATH] and "--port 8600" not in remote.files[UNIT_PATH]
    assert remote.modes[UNIT_PATH] == 0o644


def test_apply_refuses_when_venv_known_broken(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    ctx.discovered.venv_works = False
    with pytest.raises(StepError, match="python3 -m venv does not work"):
        step.apply(ctx)
    assert remote.uploads == []


def test_apply_pip_failure_is_readable(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    remote.default_ok = True
    remote.fail("python3 -m venv .venv", "ERROR: No matching distribution found for torch")
    with pytest.raises(StepError, match="building the intelligence venv failed"):
        step.apply(ctx)
    assert ENV_FILE not in remote.files  # the env file is only written after a successful build


# --- step: verify -----------------------------------------------------------------------------------


def test_verify_polls_health_and_reports_json(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    step.health_timeout_seconds = 30
    calls: list[int] = []

    def flaky(cmd: str, inp: str | None) -> tuple[int, str]:
        calls.append(1)
        return (0, HEALTH) if len(calls) >= 3 else (7, "")

    remote.on(HEALTH_PROBE, flaky)
    result = step.verify(ctx)
    assert result.ok
    assert len(calls) == 3
    assert "127.0.0.1:8600" in result.detail and '"encryptedStore":true' in result.detail
    assert "WARN" not in result.detail


def test_verify_warns_when_encryption_was_just_enabled(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    step._encryption_just_enabled = True
    remote.ok(HEALTH_PROBE, HEALTH.replace('"encryptedStore":true', '"encryptedStore":false'))
    result = step.verify(ctx)
    assert result.ok
    assert "intelligence backfill all --content" in result.detail
    assert "encryptedStore=false" in result.detail


def test_verify_fails_with_journal_tail(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    step = enable(plan, ctx)
    step.health_timeout_seconds = 0
    remote.fail(HEALTH_PROBE, "curl: (7) Failed to connect")
    remote.ok(f"journalctl -u {UNIT_NAME} -n 20 --no-pager", "Sep 21 20:00:00 box uvicorn[1]: ModuleNotFoundError: chromadb\n")
    result = step.verify(ctx)
    assert not result.ok
    assert "did not answer /health" in result.detail
    assert "journalctl -u cloud-driver-intelligence" in result.detail
    assert "ModuleNotFoundError: chromadb" in result.detail


def test_full_cycle_apply_then_check_is_ok(plan: InstallPlan, ctx: Context, remote: FakeRemote) -> None:
    """After apply, a check against the same fake reports nothing left to do."""
    step = enable(plan, ctx)
    remote.default_ok = True
    remote.ok("openssl rand -base64 32", KEY)
    step.apply(ctx)
    remote.files[f"{REMOTE_DIR}/.venv/bin/uvicorn"] = ""
    remote.ok(HEALTH_PROBE, HEALTH)
    remote.on(VENDOR_LISTING, lambda cmd, inp: sha_listing(hash_tree(step.vendor_source_dir(plan), VENDOR_MEMBERS, VENDOR_EXCLUDES)))
    remote.on(SOURCE_LISTING, lambda cmd, inp: sha_listing(hash_tree(step.source_dir(plan), SOURCE_MEMBERS, SOURCE_EXCLUDES)))
    remote.rules.reverse()
    result = step.check(ctx)
    assert result.status is StepStatus.OK, result.detail
    assert_no_leak(ctx, remote, SECRET, KEY)

"""The optional Python semantic-search service (``cloud-driver-intelligence``).

Mirrors ``cloud-driver-intelligence/deploy/install-on-server.sh``: source and the vendored
``lino-database-driver-*`` packages are mirrored into ``/opt/cloud-driver-intelligence``, a venv is
built there, the shared secret goes into a root-owned env file, and the service runs as the
repository's own systemd unit. The at-rest encryption key is generated on the server and never
rewritten - live ciphertext depends on it.
"""

from __future__ import annotations

import shlex
import tarfile
import tempfile
import time
from pathlib import Path

from cloud_driver_installer.config_files import parse_env_file
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan

REMOTE_DIR = "/opt/cloud-driver-intelligence"
ENV_FILE = "/etc/cloud-driver-intelligence.env"
UNIT_NAME = "cloud-driver-intelligence.service"
UNIT_PATH = f"/etc/systemd/system/{UNIT_NAME}"
DROPIN_DIR = f"{UNIT_PATH}.d"

#: Never copied to the server (build artefacts and macOS AppleDouble files GNU tar would keep).
TAR_EXCLUDES = ("__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".venv")


def build_source_tar(source: Path, names: list[str], destination: Path, *, skip_tests: bool = False) -> Path:
    """Pack ``names`` from ``source`` into ``destination`` (gzip), leaving build artefacts out."""

    def keep(info: tarfile.TarInfo) -> tarfile.TarInfo | None:
        parts = Path(info.name).parts
        base = Path(info.name).name
        if any(part in TAR_EXCLUDES for part in parts) or base.startswith("._") or base.endswith(".egg-info"):
            return None
        if skip_tests and "tests" in parts:
            return None
        info.uid = info.gid = 0
        info.uname = info.gname = "root"
        return info

    with tarfile.open(destination, "w:gz") as archive:
        for name in names:
            path = source / name
            if path.exists():
                archive.add(path, arcname=name, filter=keep)
    return destination


def merge_env(existing: str | None, updates: dict[str, str], remove: tuple[str, ...] = ()) -> str:
    """Return the env file with ``updates`` applied, ``remove``d keys gone, every other line kept."""
    lines: list[str] = []
    seen: set[str] = set()
    for line in (existing or "").splitlines():
        key = line.split("=", 1)[0].strip()
        if key in remove:
            continue
        if key in updates:
            if key in seen:
                continue
            lines.append(f"{key}={updates[key]}")
            seen.add(key)
        else:
            lines.append(line)
    for key, value in updates.items():
        if key not in seen:
            lines.append(f"{key}={value}")
    return "\n".join(line for line in lines if line.strip()) + "\n"


def render_port_dropin(port: int) -> str:
    """A unit drop-in moving uvicorn to ``port`` (the shipped unit hardcodes 8600)."""
    return (
        "[Service]\n"
        "ExecStart=\n"
        f"ExecStart={REMOTE_DIR}/.venv/bin/uvicorn cloud_driver_intelligence.app:app --host 127.0.0.1 --port {port}\n"
    )


class IntelligenceStep(Step):
    """Installs, configures and starts the semantic-search service."""

    id = "intelligence"
    title = "Intelligence service"
    depends_on = ("python", "config")

    def enabled(self, plan: InstallPlan) -> bool:
        return plan.intelligence.enabled

    def check(self, ctx: Context) -> CheckResult:
        plan = ctx.plan.intelligence
        source = Path(ctx.plan.intelligence_source_dir or "")
        if not (source / "pyproject.toml").is_file():
            raise StepError(f"{source or '<unset>'} is not the cloud-driver-intelligence module")
        if plan.enable_encryption and not ctx.plan.intelligence_driver_clone_dir and not ctx.remote.exists(f"{REMOTE_DIR}/vendor"):
            raise StepError(
                "at-rest encryption needs the lino-database-driver packages from the database-driver-v2 clone; "
                "point to the clone or switch the encryption off - otherwise the service would fail closed to an in-memory store"
            )
        installed = ctx.remote.exists(f"{REMOTE_DIR}/.venv/bin/uvicorn")
        unit = ctx.remote.exists(UNIT_PATH)
        env = parse_env_file(ctx.remote.read_text(ENV_FILE))
        secret_ok = env.get("CLOUD_DRIVER_INTELLIGENCE_SECRET") == ctx.secrets.intelligence_secret and bool(ctx.secrets.intelligence_secret)
        healthy = ctx.remote.run_ok(f"curl -fsS -m 3 http://127.0.0.1:{plan.port}/health")
        if installed and unit and secret_ok and healthy:
            encrypted = "CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY" in env
            return CheckResult.ok(f"service healthy on 127.0.0.1:{plan.port}" + (" · encrypted store" if encrypted else ""))
        missing = [text for text, ok in (("venv", installed), ("unit", unit), ("shared secret", secret_ok), ("/health", healthy)) if not ok]
        return CheckResult.needs_apply("to do: " + ", ".join(missing))

    def apply(self, ctx: Context) -> None:
        plan = ctx.plan.intelligence
        source = Path(ctx.plan.intelligence_source_dir)
        ctx.remote.mkdirs(REMOTE_DIR)

        ctx.progress(0.1, "uploading the service source")
        self._upload_tree(ctx, source, ["src", "tests", "pyproject.toml"], clean=["src", "tests"], into=REMOTE_DIR)
        clone = ctx.plan.intelligence_driver_clone_dir
        if clone:
            ctx.progress(0.2, "uploading the database-driver packages")
            self._upload_tree(
                ctx,
                Path(clone) / "python",
                ["database-driver-api", "database-driver-plugin"],
                clean=["vendor"],
                into=f"{REMOTE_DIR}/vendor",
                skip_tests=True,
            )
        elif not ctx.remote.exists(f"{REMOTE_DIR}/vendor"):
            ctx.warn("[Intelligence] no database-driver clone found - the encrypted store will not be installed")

        if plan.ocr:
            packages = ["tesseract-ocr", "poppler-utils"] + [f"tesseract-ocr-{lang}" for lang in plan.ocr_languages.split("+") if lang]
            ctx.remote.apt_install([name for name in packages if not ctx.remote.dpkg_installed(name)])

        ctx.progress(0.35, "building the virtual environment (PyTorch takes a few minutes)")
        self._build_venv(ctx)

        ctx.progress(0.85, "writing the environment file")
        self._write_env(ctx)
        self._write_unit(ctx)
        ctx.remote.systemctl("daemon-reload")
        ctx.remote.systemctl("enable", UNIT_NAME, check=False)
        ctx.remote.systemctl("restart", UNIT_NAME)

    def verify(self, ctx: Context) -> VerifyResult:
        port = ctx.plan.intelligence.port
        deadline = time.monotonic() + 600  # the first start downloads the embedding model
        while time.monotonic() < deadline:
            ctx.check_cancelled()
            result = ctx.remote.run(f"curl -fsS -m 5 http://127.0.0.1:{port}/health", quiet=True)
            if result.ok:
                detail = result.out.strip()[:200]
                if ctx.plan.intelligence.enable_encryption and '"encryptedStore":true' in detail.replace(" ", ""):
                    ctx.warn("[Intelligence] a freshly encrypted store starts empty - run 'intelligence backfill all --content' in the operator terminal")
                return VerifyResult(True, f"/health: {detail}")
            if not ctx.remote.service_active(UNIT_NAME):
                journal = ctx.remote.run(f"journalctl -u {UNIT_NAME} -n 20 --no-pager 2>/dev/null", quiet=True).out
                return VerifyResult(False, "the service is not running" + (f":\n{journal.strip()[-800:]}" if journal.strip() else ""))
            time.sleep(5)
        return VerifyResult(False, f"no /health answer on 127.0.0.1:{port} within 10 minutes - check journalctl -u {UNIT_NAME}")

    def describe(self, plan: InstallPlan) -> str:
        extras = ["embeddings", "store"]
        if plan.intelligence_driver_clone_dir and plan.intelligence.enable_encryption:
            extras.append("encryption")
        if plan.intelligence.ocr:
            extras.append("ocr")
        if plan.intelligence.clip:
            extras.append("clip")
        return f"install {REMOTE_DIR} with [{', '.join(extras)}], write {ENV_FILE}, run the systemd unit on 127.0.0.1:{plan.intelligence.port}"

    # --- internals -------------------------------------------------------------------------------

    def _upload_tree(self, ctx: Context, source: Path, names: list[str], *, clean: list[str], into: str, skip_tests: bool = False) -> None:
        with tempfile.TemporaryDirectory() as workdir:
            archive = build_source_tar(source, names, Path(workdir) / "payload.tar.gz", skip_tests=skip_tests)
            remote_archive = f"/tmp/cloud-driver-intelligence-{archive.stat().st_size}.tar.gz"
            ctx.remote.put_file(archive, remote_archive)
        targets = " ".join(shlex.quote(f"{REMOTE_DIR}/{name}") if into == REMOTE_DIR else shlex.quote(into) for name in clean)
        ctx.remote.run(f"rm -rf {targets} && mkdir -p {shlex.quote(into)}", check=True)
        ctx.remote.run(f"tar xzf {shlex.quote(remote_archive)} -C {shlex.quote(into)} && rm -f {shlex.quote(remote_archive)}", check=True, timeout=300)

    def _build_venv(self, ctx: Context) -> None:
        plan = ctx.plan.intelligence
        index = " --extra-index-url https://download.pytorch.org/whl/cpu" if plan.cpu_only_torch else ""
        extras = ["embeddings", "store"]
        if plan.ocr:
            extras.append("ocr")
        if plan.clip:
            extras.append("clip")
        script = (
            "set -e\n"
            f"cd {shlex.quote(REMOTE_DIR)}\n"
            "[ -x .venv/bin/pip ] || { rm -rf .venv; python3 -m venv .venv; }\n"
            "./.venv/bin/pip install --quiet --upgrade pip\n"
            f"./.venv/bin/pip install --progress-bar off -e '.[{','.join(extras)}]'{index}\n"
            "if [ -d vendor ]; then\n"
            "  ./.venv/bin/pip install --progress-bar off vendor/*/\n"
            "  ./.venv/bin/pip install --progress-bar off -e '.[encryption]'\n"
            "fi\n"
            "./.venv/bin/python -c 'import cloud_driver_intelligence; print(\"package import OK\")'\n"
        )
        result = ctx.remote.run(script, timeout=None)
        if not result.ok:
            raise StepError("building the service's virtual environment failed: " + (result.err or result.out).strip().splitlines()[-1][:300])

    def _write_env(self, ctx: Context) -> None:
        plan = ctx.plan.intelligence
        existing = ctx.remote.read_text(ENV_FILE)
        updates = {"CLOUD_DRIVER_INTELLIGENCE_SECRET": ctx.secrets.intelligence_secret}
        remove: list[str] = []
        if plan.ocr:
            updates["CLOUD_DRIVER_INTELLIGENCE_OCR"] = "true"
            updates["CLOUD_DRIVER_INTELLIGENCE_OCR_LANGUAGES"] = plan.ocr_languages
        else:
            remove.append("CLOUD_DRIVER_INTELLIGENCE_OCR")
        if plan.clip:
            updates["CLOUD_DRIVER_INTELLIGENCE_CLIP"] = "true"
        else:
            remove.append("CLOUD_DRIVER_INTELLIGENCE_CLIP")
        # The encryption key is generated on the server (so it never travels) and, once live
        # ciphertext depends on it, never rewritten or removed.
        current = parse_env_file(existing)
        if "CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY" in current:
            updates["CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY"] = current["CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY"]
            ctx.secrets.intelligence_encryption_key_present = True
        elif plan.enable_encryption:
            key = ctx.remote.run("openssl rand -base64 32", quiet=True).out.strip()
            if not key:
                raise StepError("could not generate the at-rest encryption key on the server (is openssl installed?)")
            ctx.remember_secret(key)
            updates["CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY"] = key
            ctx.secrets.intelligence_encryption_key_present = True
            ctx.info("[Intelligence] generated the at-rest encryption key on the server")
        ctx.remote.put_text(ENV_FILE, merge_env(existing, updates, tuple(remove)), mode=0o600)

    def _write_unit(self, ctx: Context) -> None:
        unit = Path(ctx.plan.intelligence_source_dir) / "deploy" / UNIT_NAME
        if not unit.is_file():
            raise StepError(f"{unit} not found in the checkout")
        ctx.remote.put_file(unit, UNIT_PATH)
        if ctx.plan.intelligence.port != 8600:
            ctx.remote.mkdirs(DROPIN_DIR)
            ctx.remote.put_text(f"{DROPIN_DIR}/port.conf", render_port_dropin(ctx.plan.intelligence.port))
        if ctx.plan.intelligence.clip:
            ctx.remote.mkdirs(DROPIN_DIR)
            ctx.remote.put_text(f"{DROPIN_DIR}/memory.conf", "[Service]\nMemoryMax=3G\n")
            ctx.warn("[Intelligence] CLIP loads a second model - the memory cap was raised to 3G, check the box's total RAM budget")

"""Step 15 - the optional ``cloud-driver-intelligence`` semantic-search service.

A port of ``cloud-driver-intelligence/deploy/install-on-server.sh`` onto the installer's
:class:`~cloud_driver_installer.remote.Remote` handle, keeping every semantic of that script:

* the source (``src``, ``tests``, ``pyproject.toml``) is *mirrored* into
  ``/opt/cloud-driver-intelligence`` - ``src``/``tests`` are removed first so a file deleted
  locally does not linger remotely; ``.venv``, the vector store and the model cache live outside
  those directories and are never touched;
* the ``lino-database-driver-*`` packages (on no index) are vendored into ``vendor/`` from the
  ``database-driver-v2`` clone when it is present locally, and an existing remote ``vendor/`` is
  left alone when it is not, so a machine without the clone can still redeploy;
* the venv is rebuilt only when broken (``[ -x .venv/bin/pip ]``), the ``embeddings`` + ``store``
  extras are always installed, ``encryption`` whenever ``vendor/`` exists, ``ocr``/``clip`` when
  chosen;
* ``/etc/cloud-driver-intelligence.env`` (root, ``0600``) is *merged*: every existing line is kept
  - above all ``CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY``, whose loss would silently drop the
  service from the encrypted store back to plaintext persistence - the shared secret is replaced,
  and the OCR/CLIP flags follow the plan. The at-rest key is generated **on the server** exactly
  once and never rotated;
* the unit file ships from the source tree, then ``daemon-reload`` / ``enable`` / ``restart`` and
  a ``/health`` poll (the first start downloads the ~90 MB embedding model).

The tar-over-ssh transfer of the script is replaced by a tarball built locally with
:mod:`tarfile` (which, unlike macOS bsdtar, writes neither AppleDouble ``._*`` members nor xattr
pax headers) and uploaded with the SHA-256-verified ``put_file``. The archive builder and the
env-file merge are pure functions so they can be unit-tested without a server.
"""

from __future__ import annotations

import fnmatch
import json
import os
import re
import shlex
import tarfile
import tempfile
import time
from pathlib import Path, PurePosixPath
from typing import TYPE_CHECKING, Callable, Sequence

from cloud_driver_installer.config_files import parse_env_file, parse_json
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.remote import RemoteError, sha256_of_file

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cloud_driver_installer.model import InstallPlan
    from cloud_driver_installer.remote import Remote

#: Where the service lives on the server (``REMOTE_DIR`` in the shell script).
REMOTE_DIR = "/opt/cloud-driver-intelligence"
#: The root-owned ``0600`` file the unit reads through ``EnvironmentFile=``.
ENV_FILE = "/etc/cloud-driver-intelligence.env"
#: The systemd unit name (``UNIT_NAME`` in the shell script).
UNIT_NAME = "cloud-driver-intelligence.service"
#: Where the unit file is installed.
UNIT_PATH = f"/etc/systemd/system/{UNIT_NAME}"
#: The port hardcoded in the shipped unit file's ``ExecStart``.
UNIT_DEFAULT_PORT = 8600
#: The one directory that persists across redeploys (Chroma store + model cache).
STATE_DIR = "/var/lib/cloud-driver-intelligence"

#: What the source tarball carries (``tar ... src tests pyproject.toml`` in the script).
SOURCE_MEMBERS: tuple[str, ...] = ("src", "tests", "pyproject.toml")
#: The two driver packages vendored from ``<clone>/python``.
VENDOR_MEMBERS: tuple[str, ...] = ("database-driver-api", "database-driver-plugin")
#: ``--exclude`` patterns of the source upload, matched against every path component.
SOURCE_EXCLUDES: tuple[str, ...] = ("__pycache__", ".pytest_cache", "*.egg-info", "._*", ".venv", ".mypy_cache", ".ruff_cache")
#: The vendor upload additionally drops the packages' test suites.
VENDOR_EXCLUDES: tuple[str, ...] = SOURCE_EXCLUDES + ("tests",)

#: Env-file keys this step manages (everything else in the file is passed through untouched).
ENV_SECRET = "CLOUD_DRIVER_INTELLIGENCE_SECRET"
ENV_ENCRYPTION_KEY = "CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY"
ENV_OCR = "CLOUD_DRIVER_INTELLIGENCE_OCR"
ENV_OCR_LANGUAGES = "CLOUD_DRIVER_INTELLIGENCE_OCR_LANGUAGES"
ENV_CLIP = "CLOUD_DRIVER_INTELLIGENCE_CLIP"

#: The configuration.json key that is the single source of truth for the shared secret.
CONFIG_SECRET_KEY = "intelligence-shared-secret"

#: PyPI's Linux ``torch`` wheel is the CUDA build (~2 GB of nvidia libraries); this index has the CPU one.
TORCH_CPU_INDEX_URL = "https://download.pytorch.org/whl/cpu"
#: System packages the ``ocr`` extra needs and pip cannot install (plus one ``tesseract-ocr-<lang>`` per language).
OCR_APT_PACKAGES: tuple[str, ...] = ("tesseract-ocr", "poppler-utils")

#: Remote temp paths the uploaded tarballs land on before extraction.
REMOTE_SOURCE_ARCHIVE = "/tmp/cloud-driver-intelligence-src.tar.gz"
REMOTE_VENDOR_ARCHIVE = "/tmp/cloud-driver-intelligence-vendor.tar.gz"


# --- pure helpers ----------------------------------------------------------------------------------


def is_excluded(path: str, excludes: Sequence[str]) -> bool:
    """Whether ``path`` (relative, ``/``-separated) has any component matching one of ``excludes``.

    This is GNU tar's default ``--exclude`` behaviour for the single-component patterns used here
    (``__pycache__`` at any depth, ``*.egg-info``, ``._*``): a matching directory excludes its
    whole subtree.
    """
    parts = PurePosixPath(path).parts
    return any(fnmatch.fnmatchcase(part, pattern) for part in parts for pattern in excludes)


def build_archive(base_dir: Path, members: Sequence[str], excludes: Sequence[str], destination: Path) -> Path:
    """Write ``destination`` (a ``.tar.gz``) holding ``members`` of ``base_dir`` minus ``excludes``.

    Member names are relative to ``base_dir`` (``tar -C base_dir members``), so ``tar xzf`` on the
    server unpacks them straight into the target directory. Ownership is normalised to ``root``
    (GNU tar run as root restores archived uid/gid, and the operator's desktop uid means nothing
    on the server). Symlinks are archived as symlinks. Raises :class:`FileNotFoundError` when a
    member is missing - the script would fail at ``tar`` for the same reason.
    """
    base_dir = Path(base_dir)
    for member in members:
        if not (base_dir / member).exists():
            raise FileNotFoundError(str(base_dir / member))

    def keep(info: tarfile.TarInfo) -> tarfile.TarInfo | None:
        if is_excluded(info.name, excludes):
            return None
        info.uid = info.gid = 0
        info.uname = info.gname = "root"
        return info

    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(destination, "w:gz") as archive:
        for member in members:
            archive.add(str(base_dir / member), arcname=member, recursive=True, filter=keep)
    return destination


def hash_tree(base_dir: Path, members: Sequence[str], excludes: Sequence[str]) -> dict[str, str]:
    """``{relative posix path: sha256}`` of every regular file the archive of ``members`` would carry.

    The same exclusion rules as :func:`build_archive`, so the result is comparable with a
    ``sha256sum`` listing of the extracted tree on the server (see :func:`parse_sha256sum`).
    """
    base_dir = Path(base_dir)
    hashes: dict[str, str] = {}
    for member in members:
        root = base_dir / member
        if not root.exists():
            raise FileNotFoundError(str(root))
        if root.is_file():
            if not is_excluded(member, excludes):
                hashes[member] = sha256_of_file(root)
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            relative_dir = Path(dirpath).relative_to(base_dir).as_posix()
            if is_excluded(relative_dir, excludes):
                dirnames[:] = []
                continue
            dirnames[:] = sorted(d for d in dirnames if not is_excluded(d, excludes))
            for filename in sorted(filenames):
                full = Path(dirpath) / filename
                if is_excluded(filename, excludes) or full.is_symlink() or not full.is_file():
                    continue
                hashes[full.relative_to(base_dir).as_posix()] = sha256_of_file(full)
    return hashes


def parse_sha256sum(output: str, excludes: Sequence[str] = ()) -> dict[str, str]:
    """Parse ``sha256sum`` output (``<hex>  <path>`` lines) into ``{path: hex}``.

    Paths lose a leading ``./`` and the ``*`` binary-mode marker; entries matching ``excludes``
    (``__pycache__`` the server's own Python created, for instance) are dropped so the listing
    compares with :func:`hash_tree`.
    """
    hashes: dict[str, str] = {}
    for raw in (output or "").splitlines():
        line = raw.strip()
        if not line:
            continue
        digest, _, rest = line.partition(" ")
        path = rest.strip()
        if path.startswith("*"):
            path = path[1:]
        if path.startswith("./"):
            path = path[2:]
        if len(digest) != 64 or not path or is_excluded(path, excludes):
            continue
        hashes[path] = digest.lower()
    return hashes


def _env_key(line: str) -> str | None:
    """The ``KEY`` of a ``KEY=value`` line, or ``None`` for blank/comment/other lines (same lenience as :func:`parse_env_file`)."""
    stripped = line.strip()
    if not stripped or stripped.startswith("#") or "=" not in stripped:
        return None
    return stripped.partition("=")[0].strip()


def env_has_key(text: str | None, key: str) -> bool:
    """Whether a ``key=`` line exists in ``text`` (``grep -q '^KEY='`` in the script - value may be blank)."""
    return any(_env_key(line) == key for line in (text or "").splitlines())


def merge_env_file(existing: str | None, secret: str, *, ocr: bool, ocr_languages: str, clip: bool) -> str:
    """Return the env file text for ``secret`` and the feature flags, merged over ``existing``.

    Every line this step does not manage is kept in place (comments included) - above all an
    existing ``CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY``. The shared secret is replaced in
    place (or put first, the script's layout, when the file is new); ``CLOUD_DRIVER_INTELLIGENCE_OCR``
    + ``_OCR_LANGUAGES`` and ``CLOUD_DRIVER_INTELLIGENCE_CLIP`` are set when the feature is on and
    removed when it is off. Duplicate managed lines collapse into one. The result is stable:
    merging it again with the same arguments returns it unchanged.
    """
    managed: dict[str, str | None] = {
        ENV_SECRET: secret,
        ENV_OCR: "true" if ocr else None,
        ENV_OCR_LANGUAGES: ocr_languages if ocr else None,
        ENV_CLIP: "true" if clip else None,
    }
    lines: list[str] = []
    seen: set[str] = set()
    for line in (existing or "").splitlines():
        key = _env_key(line)
        if key not in managed:
            lines.append(line.rstrip("\r"))
            continue
        value = managed[key]
        if value is None or key in seen:
            continue
        lines.append(f"{key}={value}")
        seen.add(key)
    if ENV_SECRET not in seen:
        lines.insert(0, f"{ENV_SECRET}={secret}")
    for key in (ENV_OCR, ENV_OCR_LANGUAGES, ENV_CLIP):
        value = managed[key]
        if value is not None and key not in seen:
            lines.append(f"{key}={value}")
    return "\n".join(lines) + "\n"


def ocr_apt_packages(languages: str) -> list[str]:
    """``tesseract-ocr``, ``poppler-utils`` and one ``tesseract-ocr-<lang>`` data pack per ``+``-separated language."""
    packages = list(OCR_APT_PACKAGES)
    for language in languages.split("+"):
        language = language.strip().lower()
        if language and f"tesseract-ocr-{language}" not in packages:
            packages.append(f"tesseract-ocr-{language}")
    return packages


def venv_install_script(*, cpu_only_torch: bool, ocr: bool, clip: bool) -> str:
    """The one remote shell script that builds/repairs the venv and installs every chosen extra.

    Mirrors step 4 of ``install-on-server.sh`` line by line: a venv is recreated only when it has
    no ``pip`` (a half-built one from a missing ``ensurepip`` would otherwise fail forever),
    ``vendor/`` when present is installed together with the ``encryption`` extra so a redeploy can
    never silently drop the encrypted store, and the import check at the end fails the step early
    instead of leaving a unit that crash-loops.
    """
    index = f" --extra-index-url {TORCH_CPU_INDEX_URL}" if cpu_only_torch else ""
    lines = [
        "set -e",
        f"cd {shlex.quote(REMOTE_DIR)}",
        "[ -x .venv/bin/pip ] || { rm -rf .venv; python3 -m venv .venv; }",
        "./.venv/bin/pip install --quiet --upgrade pip",
        f"./.venv/bin/pip install --quiet{index} -e '.[embeddings,store]'",
        "if [ -d vendor ]; then",
        "    ./.venv/bin/pip install --quiet vendor/*/",
        "    ./.venv/bin/pip install --quiet -e '.[encryption]'",
        "fi",
    ]
    if ocr:
        lines.append("./.venv/bin/pip install --quiet -e '.[ocr]'")
    if clip:
        lines.append(f"./.venv/bin/pip install --quiet{index} -e '.[clip]'")
    lines.append("./.venv/bin/python -c 'import cloud_driver_intelligence; print(\"  package import OK\")'")
    return "\n".join(lines) + "\n"


def _q(path: str) -> str:
    """``shlex.quote`` shorthand for remote paths."""
    return shlex.quote(path)


# --- the step -------------------------------------------------------------------------------------


class IntelligenceStep(Step):
    """Deploys ``cloud-driver-intelligence`` as a systemd service (see the module docstring)."""

    id = "intelligence"
    title = "Intelligence service"
    depends_on = ("python", "config")

    def __init__(self) -> None:
        #: How long ``verify`` waits for ``/health`` (the first start downloads the model).
        self.health_timeout_seconds: float = 120.0
        #: Pause between two ``/health`` probes.
        self.health_poll_interval: float = 2.0
        #: Injectable so tests never sleep.
        self._sleep: Callable[[float], None] = time.sleep
        #: Set by ``apply`` when it generated the at-rest key, so ``verify`` can warn about the empty store.
        self._encryption_just_enabled: bool = False

    def enabled(self, plan: "InstallPlan") -> bool:
        """Only when the plan switches the semantic-search service on."""
        return plan.intelligence.enabled

    # --- plan helpers ----------------------------------------------------------------------------

    @staticmethod
    def source_dir(plan: "InstallPlan") -> Path:
        """The local ``cloud-driver-intelligence`` module directory (holds ``pyproject.toml`` + ``deploy/``)."""
        return Path(plan.intelligence_source_dir)

    @staticmethod
    def unit_file(plan: "InstallPlan") -> Path:
        """The shipped unit file, ``<source>/deploy/cloud-driver-intelligence.service``."""
        return IntelligenceStep.source_dir(plan) / "deploy" / UNIT_NAME

    @staticmethod
    def vendor_source_dir(plan: "InstallPlan") -> Path | None:
        """``<database-driver-v2 clone>/python`` when both driver packages are there, else ``None``.

        The script keys on ``database-driver-api`` alone and would then fail at ``tar`` without
        the plugin; requiring both up front gives the operator a readable message instead.
        """
        clone = plan.intelligence_driver_clone_dir
        if not clone:
            return None
        python_dir = Path(clone) / "python"
        missing = [name for name in VENDOR_MEMBERS if not (python_dir / name).is_dir()]
        if len(missing) == len(VENDOR_MEMBERS):
            return None
        if missing:
            raise StepError(
                f"database-driver-v2 clone at {clone} is incomplete: python/{missing[0]} is missing - "
                "pull the clone or clear the driver clone directory in the plan"
            )
        return python_dir

    # --- secret resolution -----------------------------------------------------------------------

    def _resolve_secret(self, ctx: Context) -> str:
        """The shared secret to write: the config step's value, else what the server already holds.

        ``configuration.json`` is the single source of truth (the JVM reads it; the script reads
        the local copy). When the config step did not run in this session the remote copy is
        consulted, then the existing env file, so a partial re-run never invents a secret the JVM
        does not know.
        """
        secrets = ctx.secrets
        if secrets.intelligence_secret:
            return secrets.intelligence_secret
        remote = ctx.remote
        config = parse_json(remote.read_text(f"{ctx.plan.config_dir}/configuration.json"))
        from_config = str(config.get(CONFIG_SECRET_KEY) or "").strip()
        from_env = parse_env_file(remote.read_text(ENV_FILE)).get(ENV_SECRET, "").strip()
        ctx.remember_secret(from_config)
        ctx.remember_secret(from_env)
        chosen = from_config or from_env
        if not chosen:
            raise StepError(
                "no intelligence shared secret is known: run the Configuration files step first "
                f"(it writes {CONFIG_SECRET_KEY} into configuration.json)"
            )
        if from_config and from_env and from_config != from_env:
            ctx.warn(f"{ENV_FILE} carries a different shared secret than configuration.json - configuration.json wins and the env file will be rewritten")
        secrets.intelligence_secret = chosen
        secrets.intelligence_secret_kept = True
        return chosen

    # --- state inspection ------------------------------------------------------------------------

    def _remote_tree_hashes(self, remote: "Remote", remote_base: str, members: Sequence[str], excludes: Sequence[str]) -> dict[str, str]:
        """``sha256sum`` listing of ``members`` under ``remote_base`` (empty when the directory is absent)."""
        names = " ".join(_q(m) for m in members)
        result = remote.run(
            f"cd {_q(remote_base)} 2>/dev/null && find {names} -type f -print0 2>/dev/null | xargs -0 -r sha256sum",
            quiet=True,
        )
        return parse_sha256sum(result.out, excludes) if result.out else {}

    @staticmethod
    def _drift(local: dict[str, str], remote_hashes: dict[str, str]) -> int:
        """Number of paths that differ between the two hash maps (missing on either side counts)."""
        return sum(1 for path in set(local) | set(remote_hashes) if local.get(path) != remote_hashes.get(path))

    def _source_drift(self, ctx: Context) -> list[str]:
        """Human-readable list of what differs between the local source/vendor trees and the server."""
        plan, remote = ctx.plan, ctx.remote
        source = self.source_dir(plan)
        findings: list[str] = []
        try:
            local = hash_tree(source, SOURCE_MEMBERS, SOURCE_EXCLUDES)
        except FileNotFoundError as exc:
            raise StepError(f"intelligence source is incomplete: {exc} not found") from exc
        drift = self._drift(local, self._remote_tree_hashes(remote, REMOTE_DIR, SOURCE_MEMBERS, SOURCE_EXCLUDES))
        if drift:
            findings.append(f"source differs ({drift} file{'s' if drift != 1 else ''})")
        vendor = self.vendor_source_dir(plan)
        if vendor is not None:
            local_vendor = hash_tree(vendor, VENDOR_MEMBERS, VENDOR_EXCLUDES)
            drift = self._drift(local_vendor, self._remote_tree_hashes(remote, f"{REMOTE_DIR}/vendor", VENDOR_MEMBERS, VENDOR_EXCLUDES))
            if drift:
                findings.append(f"vendored driver packages differ ({drift} file{'s' if drift != 1 else ''})")
        return findings

    def _expected_unit_text(self, plan: "InstallPlan") -> str:
        """The unit file text for this plan: the shipped file, ``--port`` adjusted when the plan's port differs."""
        unit_local = self.unit_file(plan)
        if not unit_local.is_file():
            raise StepError(f"unit file not found: {unit_local}")
        text = unit_local.read_text(encoding="utf-8")
        port = plan.intelligence.port
        if port == UNIT_DEFAULT_PORT:
            return text
        adjusted = re.sub(r"--port\s+\d+", f"--port {port}", text)
        if adjusted == text:
            raise StepError(f"{unit_local} has no --port argument to adjust to {port}")
        return adjusted

    def _env_changes(self, existing: str | None, merged: str, plan: "InstallPlan") -> list[str]:
        """What the env-file rewrite changes, for the check detail."""
        if existing is None:
            return ["env file (new)"]
        if existing == merged:
            return []
        before, after = parse_env_file(existing), parse_env_file(merged)
        changes: list[str] = []
        if before.get(ENV_SECRET) != after.get(ENV_SECRET):
            changes.append("shared secret")
        if (before.get(ENV_OCR), before.get(ENV_OCR_LANGUAGES)) != (after.get(ENV_OCR), after.get(ENV_OCR_LANGUAGES)):
            changes.append(f"OCR {'on (' + plan.intelligence.ocr_languages + ')' if plan.intelligence.ocr else 'off'}")
        if before.get(ENV_CLIP) != after.get(ENV_CLIP):
            changes.append(f"CLIP {'on' if plan.intelligence.clip else 'off'}")
        return [f"env file ({', '.join(changes) if changes else 'layout'})"]

    def _health_probe(self, ctx: Context) -> "tuple[bool, str]":
        """``(answered, body)`` of one ``curl -fsS -m 3 http://127.0.0.1:<port>/health``."""
        result = ctx.remote.run(f"curl -fsS -m 3 http://127.0.0.1:{ctx.plan.intelligence.port}/health", quiet=True)
        return result.ok, result.text

    def check(self, ctx: Context) -> CheckResult:
        """Read-only: venv, unit, env file, service, ``/health`` and whether the source on the server matches the local tree."""
        plan, remote, settings = ctx.plan, ctx.remote, ctx.plan.intelligence
        secret = self._resolve_secret(ctx)

        missing: list[str] = []
        changes: list[str] = []

        venv_ok = remote.exists(f"{REMOTE_DIR}/.venv/bin/uvicorn")
        ctx.discovered.intelligence_installed = venv_ok
        if not venv_ok:
            missing.append("venv")

        expected_unit = self._expected_unit_text(plan)
        unit_text = remote.read_text(UNIT_PATH)
        if unit_text is None:
            missing.append("unit file")
        elif unit_text != expected_unit:
            changes.append("unit file")

        existing_env = remote.read_text(ENV_FILE)
        parsed_env = parse_env_file(existing_env)
        ctx.remember_secret(parsed_env.get(ENV_SECRET))
        ctx.remember_secret(parsed_env.get(ENV_ENCRYPTION_KEY))
        ctx.discovered.intelligence_env = parsed_env
        key_present = env_has_key(existing_env, ENV_ENCRYPTION_KEY)
        ctx.secrets.intelligence_encryption_key_present = key_present
        merged_env = merge_env_file(existing_env, secret, ocr=settings.ocr, ocr_languages=settings.ocr_languages, clip=settings.clip)
        env_changes = self._env_changes(existing_env, merged_env, plan)
        if existing_env is None:
            missing.append("env file")
        else:
            changes.extend(env_changes)
        if settings.enable_encryption and not key_present:
            changes.append("at-rest encryption key (generate on the server)")

        if settings.ocr:
            absent = [p for p in ocr_apt_packages(settings.ocr_languages) if not remote.dpkg_installed(p)]
            if absent:
                missing.append("OCR packages " + " ".join(absent))

        changes.extend(self._source_drift(ctx))

        active = remote.service_active(UNIT_NAME)
        if not active:
            missing.append("service (not active)")
        answered, _body = self._health_probe(ctx)
        if not answered:
            missing.append(f"/health on 127.0.0.1:{settings.port}")

        if missing or changes:
            parts = []
            if missing:
                parts.append("missing: " + ", ".join(missing))
            if changes:
                parts.append("changes: " + ", ".join(changes))
            return CheckResult.needs_apply(" · ".join(parts))
        key_note = "at-rest encryption key present" if key_present else "no at-rest encryption key"
        return CheckResult.ok(
            f"installed in {REMOTE_DIR} (source in sync), env file current ({key_note}), "
            f"{UNIT_NAME} active, /health answering on 127.0.0.1:{settings.port}"
        )

    # --- apply -----------------------------------------------------------------------------------

    def _upload_archive(self, ctx: Context, local_archive: Path, remote_archive: str, *, base: float, span: float, label: str) -> None:
        """``put_file`` with the byte progress mapped onto ``[base, base + span]`` of the step's progress."""
        total_hint = max(1, local_archive.stat().st_size)

        def on_progress(done: int, total: int) -> None:
            ctx.progress(base + span * (done / max(1, total or total_hint)), f"uploading {label}")

        try:
            ctx.remote.put_file(str(local_archive), remote_archive, mode=0o600, progress=on_progress)
        except RemoteError as exc:
            raise StepError(f"upload of {label} failed: {exc}") from exc

    def _deploy_source(self, ctx: Context, workdir: Path) -> None:
        """Mirror ``src``/``tests``/``pyproject.toml`` into ``REMOTE_DIR`` (step 3 of the script)."""
        source = self.source_dir(ctx.plan)
        ctx.progress(0.05, "packing source")
        try:
            archive = build_archive(source, SOURCE_MEMBERS, SOURCE_EXCLUDES, workdir / "cloud-driver-intelligence-src.tar.gz")
        except FileNotFoundError as exc:
            raise StepError(f"intelligence source is incomplete: {exc} not found") from exc
        self._upload_archive(ctx, archive, REMOTE_SOURCE_ARCHIVE, base=0.05, span=0.10, label="source")
        ctx.check_cancelled()
        try:
            ctx.remote.run(
                f"mkdir -p {_q(REMOTE_DIR)} && rm -rf {_q(REMOTE_DIR + '/src')} {_q(REMOTE_DIR + '/tests')} "
                f"&& tar xzf {_q(REMOTE_SOURCE_ARCHIVE)} -C {_q(REMOTE_DIR)} && rm -f {_q(REMOTE_SOURCE_ARCHIVE)}",
                check=True,
                quiet=True,
            )
        except RemoteError as exc:
            raise StepError(f"extracting the intelligence source on the server failed: {exc}") from exc
        # Clears AppleDouble junk an earlier run of the *shell* script may have left behind.
        ctx.remote.run(f"find {_q(REMOTE_DIR)} -maxdepth 2 -name '._*' -delete 2>/dev/null || true", quiet=True)
        ctx.info(f"Mirrored {source} -> {REMOTE_DIR} (src, tests, pyproject.toml)")

    def _deploy_vendor(self, ctx: Context, workdir: Path) -> bool:
        """Mirror the driver packages into ``REMOTE_DIR/vendor`` (step 3b); returns whether anything was uploaded."""
        plan = ctx.plan
        vendor = self.vendor_source_dir(plan)
        if vendor is None:
            where = plan.intelligence_driver_clone_dir or "next to the repository"
            ctx.info(f"database-driver-v2 clone not found ({where}) - leaving any existing vendor/ on the server as-is")
            if plan.intelligence.enable_encryption and not ctx.remote.exists(f"{REMOTE_DIR}/vendor"):
                ctx.warn(
                    "at-rest encryption is enabled but the vendored database-driver packages are neither local nor on the server - "
                    "the service will fall back to an in-memory store; point the plan at the database-driver-v2 clone and re-run"
                )
            return False
        ctx.progress(0.16, "packing vendored driver packages")
        try:
            archive = build_archive(vendor, VENDOR_MEMBERS, VENDOR_EXCLUDES, workdir / "cloud-driver-intelligence-vendor.tar.gz")
        except FileNotFoundError as exc:
            raise StepError(f"database-driver-v2 clone is incomplete: {exc} not found") from exc
        self._upload_archive(ctx, archive, REMOTE_VENDOR_ARCHIVE, base=0.16, span=0.06, label="vendored driver packages")
        ctx.check_cancelled()
        vendor_dir = f"{REMOTE_DIR}/vendor"
        try:
            ctx.remote.run(
                f"rm -rf {_q(vendor_dir)} && mkdir -p {_q(vendor_dir)} "
                f"&& tar xzf {_q(REMOTE_VENDOR_ARCHIVE)} -C {_q(vendor_dir)} && rm -f {_q(REMOTE_VENDOR_ARCHIVE)}",
                check=True,
                quiet=True,
            )
        except RemoteError as exc:
            raise StepError(f"extracting the vendored driver packages on the server failed: {exc}") from exc
        ctx.remote.run(f"find {_q(vendor_dir)} -name '._*' -delete 2>/dev/null || true", quiet=True)
        ctx.info(f"Mirrored database-driver packages from {vendor} -> {vendor_dir}")
        return True

    def _install_ocr_packages(self, ctx: Context) -> None:
        """``tesseract-ocr``, ``poppler-utils`` and the language packs (only the ones dpkg does not have)."""
        settings = ctx.plan.intelligence
        wanted = ocr_apt_packages(settings.ocr_languages)
        missing = [p for p in wanted if not ctx.remote.dpkg_installed(p)]
        if not missing:
            ctx.debug("OCR system packages already installed: " + " ".join(wanted))
            return
        ctx.info("Installing OCR system packages: " + " ".join(missing))
        try:
            ctx.remote.apt_install(missing)
        except RemoteError as exc:
            raise StepError(f"installing the OCR packages failed: {exc}") from exc

    def _build_venv(self, ctx: Context) -> None:
        """Step 4 of the script: venv + every extra (minutes on a first run - PyTorch)."""
        settings = ctx.plan.intelligence
        ctx.progress(0.25, "installing into .venv (pulls in PyTorch on a first run)")
        ctx.info(f"Installing into {REMOTE_DIR}/.venv (this pulls in PyTorch - several minutes on a first run)")
        script = venv_install_script(cpu_only_torch=settings.cpu_only_torch, ocr=settings.ocr, clip=settings.clip)
        try:
            ctx.remote.run(script, timeout=None, check=True)
        except RemoteError as exc:
            raise StepError(f"building the intelligence venv failed: {exc}") from exc

    def _write_env_file(self, ctx: Context, secret: str) -> None:
        """Step 5 (+5b) of the script: merge the env file, generate the at-rest key once, write 0600."""
        remote, settings = ctx.remote, ctx.plan.intelligence
        existing = remote.read_text(ENV_FILE)
        parsed = parse_env_file(existing)
        ctx.remember_secret(parsed.get(ENV_SECRET))
        ctx.remember_secret(parsed.get(ENV_ENCRYPTION_KEY))
        merged = merge_env_file(existing, secret, ocr=settings.ocr, ocr_languages=settings.ocr_languages, clip=settings.clip)

        key_present = env_has_key(existing, ENV_ENCRYPTION_KEY)
        if key_present:
            ctx.secrets.intelligence_encryption_key_present = True
            if not parsed.get(ENV_ENCRYPTION_KEY):
                ctx.warn(f"{ENV_FILE} has a blank {ENV_ENCRYPTION_KEY} line - left untouched (never rotated), but the service will refuse to persist until it is fixed")
            elif not settings.enable_encryption:
                ctx.info("at-rest encryption key already present on the server - kept (the plan does not enable encryption, but a key is never removed)")
        elif settings.enable_encryption:
            # Generated on the server (the script's `openssl rand -base64 32`) so the key never
            # has to be invented here; captured only to register it with the redactor.
            try:
                generated = remote.run("openssl rand -base64 32", quiet=True, check=True).text
            except RemoteError as exc:
                raise StepError(f"generating the at-rest encryption key on the server failed: {exc}") from exc
            if not generated or "\n" in generated:
                raise StepError("openssl rand -base64 32 returned no usable key on the server")
            ctx.remember_secret(generated)
            merged += f"{ENV_ENCRYPTION_KEY}={generated}\n"
            ctx.secrets.intelligence_encryption_key_present = True
            self._encryption_just_enabled = True
            ctx.info("Generated the at-rest encryption key on the server (appended to the env file, never rotated on later runs)")

        if merged == existing:
            ctx.debug(f"{ENV_FILE} already current")
            return
        try:
            remote.put_text(ENV_FILE, merged, mode=0o600)
        except RemoteError as exc:
            raise StepError(f"writing {ENV_FILE} failed: {exc}") from exc
        ctx.info(f"Wrote {ENV_FILE} (root-owned, 0600; {'new' if existing is None else 'existing lines preserved'})")

    def _install_unit(self, ctx: Context) -> None:
        """Ship the unit file (``scp`` in the script), adjusted only when the plan's port is not 8600."""
        plan, remote = ctx.plan, ctx.remote
        unit_local = self.unit_file(plan)
        expected = self._expected_unit_text(plan)
        if remote.read_text(UNIT_PATH) == expected:
            ctx.debug(f"{UNIT_PATH} already current")
            return
        try:
            if plan.intelligence.port == UNIT_DEFAULT_PORT:
                remote.put_file(str(unit_local), UNIT_PATH, mode=0o644)
            else:
                ctx.warn(f"unit file adjusted to --port {plan.intelligence.port} (the shipped unit uses {UNIT_DEFAULT_PORT})")
                remote.put_text(UNIT_PATH, expected, mode=0o644)
        except RemoteError as exc:
            raise StepError(f"installing {UNIT_NAME} failed: {exc}") from exc
        ctx.info(f"Installed {UNIT_PATH}")

    def apply(self, ctx: Context) -> None:
        """Upload, install, configure and (re)start the service - idempotent, never touches the store."""
        plan, remote, settings = ctx.plan, ctx.remote, ctx.plan.intelligence
        secret = self._resolve_secret(ctx)
        self._encryption_just_enabled = False

        if ctx.discovered.venv_works is False:
            raise StepError(
                "python3 -m venv does not work on the server (ensurepip is missing) - the Python step installs "
                "python3-venv (or the version-specific python3.X-venv); run it first"
            )
        source = self.source_dir(plan)
        if not (source / "pyproject.toml").is_file():
            raise StepError(f"intelligence source directory {source} does not contain pyproject.toml")
        self._expected_unit_text(plan)  # fails early when the unit file is missing

        if settings.ocr:
            self._install_ocr_packages(ctx)
        ctx.check_cancelled()

        with tempfile.TemporaryDirectory(prefix="cloud-driver-installer-intelligence-") as tmp:
            workdir = Path(tmp)
            self._deploy_source(ctx, workdir)
            ctx.check_cancelled()
            self._deploy_vendor(ctx, workdir)
        ctx.check_cancelled()

        self._build_venv(ctx)
        ctx.check_cancelled()

        ctx.progress(0.85, "writing env file and unit")
        self._write_env_file(ctx, secret)
        self._install_unit(ctx)
        ctx.check_cancelled()

        ctx.progress(0.95, "starting the service")
        try:
            remote.systemctl("daemon-reload")
            remote.systemctl("enable", "--quiet", UNIT_NAME)
            remote.systemctl("restart", UNIT_NAME)
        except RemoteError as exc:
            raise StepError(f"starting {UNIT_NAME} failed: {exc} - check: journalctl -u cloud-driver-intelligence -n 50") from exc
        ctx.info(f"Restarted {UNIT_NAME}")
        ctx.progress(1.0, "started")

    # --- verify ----------------------------------------------------------------------------------

    def verify(self, ctx: Context) -> VerifyResult:
        """Poll ``/health`` (up to :attr:`health_timeout_seconds`) and report what it says."""
        settings = ctx.plan.intelligence
        started = time.monotonic()
        attempt = 0
        while True:
            ctx.check_cancelled()
            attempt += 1
            answered, body = self._health_probe(ctx)
            if answered:
                break
            elapsed = time.monotonic() - started
            if elapsed >= self.health_timeout_seconds:
                journal = ctx.remote.run(f"journalctl -u {UNIT_NAME} -n 20 --no-pager", quiet=True)
                tail = journal.text if journal.ok else ""
                detail = (
                    f"did not answer /health on 127.0.0.1:{settings.port} within {int(self.health_timeout_seconds)} s "
                    f"- check: journalctl -u cloud-driver-intelligence -n 50"
                )
                return VerifyResult(False, detail + (f"\n{tail}" if tail else ""))
            ctx.progress(min(0.95, elapsed / max(1.0, self.health_timeout_seconds)), f"waiting for /health (attempt {attempt})")
            self._sleep(self.health_poll_interval)

        try:
            health = json.loads(body)
            compact = json.dumps(health, separators=(",", ":"))
        except ValueError:
            health = {}
            compact = body[:200]
        if not isinstance(health, dict):
            health = {}
        notes: list[str] = []
        if health.get("embeddingsAvailable") is False:
            notes.append("WARN: no embedding backend loaded - the embeddings extra failed to install; every search returns nothing")
        if settings.enable_encryption and health.get("encryptedStore") is False:
            notes.append("WARN: encryptedStore=false although encryption is configured - check the vendored database-driver packages and journalctl -u cloud-driver-intelligence")
        if self._encryption_just_enabled:
            notes.append(
                "WARN: at-rest encryption was just enabled - the encrypted store starts EMPTY: run "
                "`intelligence backfill all --content` in the operator terminal, then delete the old plaintext "
                f"Chroma files under {STATE_DIR}/chroma"
            )
        detail = f"/health answering on 127.0.0.1:{settings.port}: {compact}"
        if notes:
            detail += " · " + " · ".join(notes)
        return VerifyResult(True, detail)

    # --- summary ---------------------------------------------------------------------------------

    def describe(self, plan: "InstallPlan") -> str:
        """One line for the summary page mirroring what ``apply`` does with this plan."""
        settings = plan.intelligence
        has_vendor = bool(plan.intelligence_driver_clone_dir)
        extras = ["embeddings", "store"]
        if has_vendor:
            extras.append("encryption")
        if settings.ocr:
            extras.append("ocr")
        if settings.clip:
            extras.append("clip")
        env_bits = ["shared secret"]
        if settings.enable_encryption:
            env_bits.append("at-rest key generated on the server once")
        if settings.ocr:
            env_bits.append(f"OCR {settings.ocr_languages}")
        if settings.clip:
            env_bits.append("CLIP")
        return (
            f"upload cloud-driver-intelligence source{' + vendored database-driver packages' if has_vendor else ''} to {REMOTE_DIR}, "
            f"build .venv ({', '.join(extras)}{'; CPU-only torch' if settings.cpu_only_torch else ''}), "
            f"{'install tesseract-ocr + poppler-utils, ' if settings.ocr else ''}"
            f"write {ENV_FILE} ({', '.join(env_bits)}; existing lines kept), install + restart {UNIT_NAME}"
        )

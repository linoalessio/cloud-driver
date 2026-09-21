"""Application step: (optionally) build, then deploy and start the JVM process.

Mirrors ``shell/deploy-cloud.sh`` (prune stale versioned jars first - the bootstrap registers every
jar it finds and refuses to start when two claim the same extension name - upload with SHA-256
verification, restore ``+x`` on ``start-cloud.sh``) and ``shell/start-cloud.sh`` (a detached
``screen`` session running the restart loop; the operator terminal needs a real TTY, so this is
deliberately not a systemd unit). On top of what the shell scripts do, the step owns the pieces the
reference box had by hand: the ``@reboot`` autostart, the scratch sweep and the off-site backup sync
in one managed crontab block, and the logrotate file for the persisted console log.

``start-cloud.env`` itself is written by the config step; this step only reads it back to verify
the jar name it names is the one being uploaded.
"""

from __future__ import annotations

import re
import shlex
import subprocess
import time
from collections import deque
from pathlib import Path
from typing import Callable

from cloud_driver_installer.config_files import render_start_env
from cloud_driver_installer.engine import Cancelled, CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import Discovered, InstallPlan
from cloud_driver_installer.remote import RemoteError, sha256_of_file

#: The hardcoded fallback of ``shell/start-cloud.sh`` - used only when neither a local build nor a
#: remote jar can tell us the real name.
DEFAULT_BOOTSTRAP_JAR_NAME = "cloud-driver-bootstrap-1.0.7.jar"

#: Markers delimiting the crontab lines this installer owns; everything outside them is kept.
CRON_BEGIN = "# cloud-driver-installer BEGIN"
CRON_END = "# cloud-driver-installer END"

#: The logrotate stanza for the console log ``screen -L`` keeps appending to.
LOGROTATE_PATH = "/etc/logrotate.d/cloud-driver"

EXTENSION_PREFIX = "cloud-driver-extensions-"
BOOTSTRAP_GLOB = "cloud-driver-bootstrap-*.jar"

#: How long ``verify`` waits for the REST API after a start (the JVM loads every cached table first).
API_READY_TIMEOUT_SECONDS = 120.0
API_POLL_INTERVAL_SECONDS = 3.0
#: How long ``apply`` waits for ``screen -X quit`` to take the old session down.
SESSION_STOP_TIMEOUT_SECONDS = 10.0
SESSION_POLL_INTERVAL_SECONDS = 0.5

_JAR_STALE_RE = re.compile(r"^removing stale (.+)$")


# --- pure helpers (unit-tested on their own) ----------------------------------------------------


def extension_short_name(jar_name: str) -> str:
    """``cloud-driver-extensions-scan-1.0.7.jar`` -> ``scan`` (the manifest/extension name)."""
    stem = jar_name[:-4] if jar_name.endswith(".jar") else jar_name
    if stem.startswith(EXTENSION_PREFIX):
        stem = stem[len(EXTENSION_PREFIX):]
    return re.sub(r"-\d.*$", "", stem)


def cron_lines(plan: InstallPlan, bucket: str = "") -> list[str]:
    """The managed crontab lines for ``plan`` (empty when every cron feature is off).

    ``bucket`` is the resolved S3 bucket (``secrets.s3_bucket``); it falls back to the plan's.
    """
    quote = shlex.quote
    install_dir = plan.server.install_dir.rstrip("/")
    lines: list[str] = []
    if plan.app.autostart_on_reboot:
        lines.append(f"@reboot cd {quote(install_dir)} && ./start-cloud.sh")
    if plan.app.scratch_sweep:
        lines.append(f"17 * * * * find {quote(install_dir + '/upload-scratch')} -name 'upload-*.tmp' -mmin +180 -delete")
    bucket = bucket or plan.aws.s3_bucket
    if plan.app.backup_offsite and plan.aws.s3_enabled and bucket:
        prefix = (plan.aws.s3_key_prefix or "") + (plan.app.backup_offsite_prefix or "")
        if prefix and not prefix.endswith("/"):
            prefix += "/"
        lines.append(
            f"30 3 * * * aws s3 sync {quote(plan.config_dir + '/backup')} s3://{bucket}/{prefix} "
            "--exclude '.staging/*' --only-show-errors"
        )
    return lines


def extract_managed_block(crontab: str) -> list[str] | None:
    """The lines between the BEGIN/END markers of ``crontab`` (``None`` when there is no block)."""
    lines = (crontab or "").splitlines()
    try:
        start = lines.index(CRON_BEGIN)
    except ValueError:
        return None
    try:
        end = lines.index(CRON_END, start + 1)
    except ValueError:
        end = len(lines)
    return lines[start + 1:end]


def strip_managed_block(crontab: str) -> str:
    """``crontab`` without its managed block (an unterminated block is dropped to the end)."""
    lines = (crontab or "").splitlines()
    try:
        start = lines.index(CRON_BEGIN)
    except ValueError:
        return crontab or ""
    try:
        end = lines.index(CRON_END, start + 1) + 1
    except ValueError:
        end = len(lines)
    kept = lines[:start] + lines[end:]
    return "\n".join(kept) + ("\n" if kept else "")


def render_crontab(existing: str, managed: list[str]) -> str:
    """Pure: ``existing`` crontab text with its managed block replaced by ``managed`` lines.

    Unrelated lines are kept verbatim (order included); the block goes at the end, separated by a
    blank line; no block at all is written when ``managed`` is empty. The result always ends with a
    newline (``crontab -`` wants one) or is the empty string.
    """
    body = strip_managed_block(existing).rstrip("\n")
    if managed:
        block = "\n".join([CRON_BEGIN, *managed, CRON_END])
        body = f"{body}\n\n{block}" if body else block
    return body + "\n" if body else ""


def render_logrotate(plan: InstallPlan) -> str:
    """``/etc/logrotate.d/cloud-driver`` for the console log (weekly, 8 kept, copytruncate)."""
    log_path = f"{plan.server.install_dir.rstrip('/')}/cloud.log"
    return (
        f"{log_path} {{\n"
        "    weekly\n"
        "    rotate 8\n"
        "    compress\n"
        "    missingok\n"
        "    notifempty\n"
        "    copytruncate\n"
        "}\n"
    )


# --- the step ------------------------------------------------------------------------------------


class ApplicationStep(Step):
    """Deploy the bootstrap + extension jars and ``start-cloud.sh``, then (re)start the screen session."""

    id = "application"
    title = "Application"
    depends_on = ("java", "config")
    mandatory = True

    def __init__(self) -> None:
        #: Injection points so the wait loops are testable without real time passing.
        self.sleep: Callable[[float], None] = time.sleep
        self.clock: Callable[[], float] = time.monotonic

    # --- local artefact discovery (static so the config step and the GUI page can reuse them) ---

    @staticmethod
    def bootstrap_jar(plan: InstallPlan) -> Path | None:
        """The newest ``cloud-driver-bootstrap-*.jar`` under ``<repo>/cloud-driver-bootstrap/target`` (never ``original-*``)."""
        if not plan.app.repo_root:
            return None
        target = Path(plan.app.repo_root) / "cloud-driver-bootstrap" / "target"
        if not target.is_dir():
            return None
        candidates = [p for p in target.glob(BOOTSTRAP_GLOB) if p.is_file() and not p.name.startswith("original-")]
        if not candidates:
            return None
        return max(candidates, key=lambda p: (p.stat().st_mtime, p.name))

    @staticmethod
    def bootstrap_jar_name(plan: InstallPlan, discovered: Discovered | None = None) -> str:
        """The bootstrap jar name ``start-cloud.env`` must name.

        The local build wins when jars are being deployed; otherwise a bootstrap jar already on the
        server (from ``discovered.existing_jars``), then a local jar that is not being uploaded, and
        finally the shell script's own hardcoded default.
        """
        local = ApplicationStep.bootstrap_jar(plan)
        if local is not None and plan.app.deploy_jars:
            return local.name
        remote_names = sorted(
            Path(name).name
            for name in (discovered.existing_jars if discovered else [])
            if Path(name).name.startswith("cloud-driver-bootstrap-") and name.endswith(".jar")
        )
        if remote_names:
            return remote_names[-1]
        if local is not None:
            return local.name
        return DEFAULT_BOOTSTRAP_JAR_NAME

    @staticmethod
    def extension_jars(plan: InstallPlan, log: Callable[[str], None] | None = None) -> list[Path]:
        """Every built extension jar minus the operator's exclusions and the ones whose service is off.

        ``log`` (when given) receives one line per automatic exclusion (scan without ClamAV,
        intelligence without the intelligence service).
        """
        if not plan.app.repo_root:
            return []
        root = Path(plan.app.repo_root)
        found = sorted(
            (p for p in root.glob(f"cloud-driver-extensions/*/target/{EXTENSION_PREFIX}*.jar") if p.is_file() and not p.name.startswith("original-")),
            key=lambda p: p.name,
        )
        excluded = {name.strip() for name in plan.app.excluded_extensions if name and name.strip()}
        result: list[Path] = []
        for jar in found:
            short = extension_short_name(jar.name)
            if {jar.name, jar.stem, short, EXTENSION_PREFIX + short} & excluded:
                continue
            if short == "scan" and not plan.clamav.enabled:
                if log:
                    log(f"leaving out {jar.name}: ClamAV is disabled in the plan")
                continue
            if short == "intelligence" and not plan.intelligence.enabled:
                if log:
                    log(f"leaving out {jar.name}: the intelligence service is disabled in the plan")
                continue
            result.append(jar)
        return result

    @staticmethod
    def start_script(plan: InstallPlan) -> Path | None:
        """``<repo>/shell/start-cloud.sh`` when the checkout has it."""
        if not plan.app.repo_root:
            return None
        path = Path(plan.app.repo_root) / "shell" / "start-cloud.sh"
        return path if path.is_file() else None

    # --- remote paths ------------------------------------------------------------------------------

    @staticmethod
    def _install_dir(plan: InstallPlan) -> str:
        return plan.server.install_dir.rstrip("/")

    @classmethod
    def _remote_start_script(cls, plan: InstallPlan) -> str:
        return f"{cls._install_dir(plan)}/start-cloud.sh"

    @classmethod
    def _remote_start_env(cls, plan: InstallPlan) -> str:
        return f"{cls._install_dir(plan)}/start-cloud.env"

    @classmethod
    def _scratch_dir(cls, plan: InstallPlan) -> str:
        return f"{cls._install_dir(plan)}/upload-scratch"

    @classmethod
    def _log_path(cls, plan: InstallPlan) -> str:
        return f"{cls._install_dir(plan)}/cloud.log"

    # --- probes ------------------------------------------------------------------------------------

    def _session_running(self, ctx: Context) -> bool:
        """Whether the screen session of the plan exists (the exact test ``start-cloud.sh`` uses)."""
        session = ctx.plan.server.screen_session
        return ctx.remote.run_ok(f"screen -list 2>/dev/null | grep -q '\\.{session}[[:space:]]'")

    def _read_crontab(self, ctx: Context) -> str:
        """Root's current crontab, ``""`` when there is none (``crontab -l`` exits 1 then)."""
        result = ctx.remote.run("crontab -l", quiet=True)
        return result.out if result.ok else ""

    def _remote_jar_names(self, ctx: Context) -> list[str]:
        """Names of every versioned jar currently on the server (the two patterns the prune touches)."""
        plan = ctx.plan
        result = ctx.remote.run(
            f"ls -1 {shlex.quote(self._install_dir(plan))}/{BOOTSTRAP_GLOB} {shlex.quote(plan.extensions_dir)}/*.jar 2>/dev/null",
            quiet=True,
        )
        return [Path(line.strip()).name for line in result.out.splitlines() if line.strip()]

    def _api_status(self, ctx: Context) -> str:
        """HTTP status of ``GET /auth/me`` on the loopback REST port (``""`` when unreachable)."""
        port = ctx.plan.app.rest_port
        result = ctx.remote.run(f"curl -s -o /dev/null -w '%{{http_code}}' -m 3 http://127.0.0.1:{port}/auth/me", quiet=True)
        return result.text if result.ok else ""

    # --- Step -------------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """Compare every artefact with the server (SHA-256 for files) and the session state; never writes."""
        plan = ctx.plan
        remote = ctx.remote
        problems: list[str] = []
        notes: list[str] = []
        if plan.app.build_with_maven:
            problems.append("Maven build requested (always redeploys)")
        jar_name = self.bootstrap_jar_name(plan, ctx.discovered)
        keep: list[str] = [jar_name]
        if plan.app.deploy_jars:
            jar = self.bootstrap_jar(plan)
            if jar is None:
                problems.append(f"no {BOOTSTRAP_GLOB} under {plan.app.repo_root or '<repo>'}/cloud-driver-bootstrap/target (build first or tick 'Build with Maven')")
            elif remote.sha256(f"{self._install_dir(plan)}/{jar.name}") != sha256_of_file(jar):
                problems.append(f"{jar.name} (upload)")
            extensions = self.extension_jars(plan, log=ctx.debug)
            keep.extend(ext.name for ext in extensions)
            differing = [ext.name for ext in extensions if remote.sha256(f"{plan.extensions_dir}/{ext.name}") != sha256_of_file(ext)]
            if differing:
                problems.append(f"{len(differing)} of {len(extensions)} extension jars (upload: {', '.join(differing)})")
            else:
                notes.append(f"{len(extensions)} extension jars in place")
            stale = [name for name in self._remote_jar_names(ctx) if name not in keep]
            if stale:
                problems.append(f"stale jars to prune: {', '.join(stale)}")
        else:
            notes.append("jar upload disabled")

        script = self.start_script(plan)
        if script is None:
            problems.append("shell/start-cloud.sh missing in the checkout")
        elif remote.sha256(self._remote_start_script(plan)) != sha256_of_file(script):
            problems.append("start-cloud.sh (upload)")

        expected_env = render_start_env(plan, jar_name)
        if remote.read_text(self._remote_start_env(plan)) != expected_env:
            problems.append("start-cloud.env (rewrite)")

        if extract_managed_block(self._read_crontab(ctx)) != (cron_lines(plan, ctx.secrets.s3_bucket) or None):
            problems.append("crontab block")

        if plan.app.persist_log and remote.read_text(LOGROTATE_PATH) != render_logrotate(plan):
            problems.append(f"{LOGROTATE_PATH}")

        if not remote.exists(self._scratch_dir(plan)):
            problems.append("upload-scratch directory")

        running = self._session_running(ctx)
        ctx.discovered.screen_running = running
        if running:
            notes.append(f"screen session '{plan.server.screen_session}' running")
        elif plan.app.start_after_deploy:
            problems.append(f"screen session '{plan.server.screen_session}' not running (start)")
        else:
            notes.append(f"screen session '{plan.server.screen_session}' not running (start disabled)")

        if problems:
            return CheckResult.needs_apply("; ".join(problems))
        return CheckResult.ok(f"{jar_name} deployed · " + " · ".join(notes))

    def apply(self, ctx: Context) -> None:
        """Build (optional), prune, upload, cron, logrotate, (re)start - each part skipping what is already right."""
        plan = ctx.plan
        if plan.app.build_with_maven:
            self._build_with_maven(ctx)
            ctx.check_cancelled()

        jar_name = self.bootstrap_jar_name(plan, ctx.discovered)
        if plan.app.deploy_jars:
            jar = self.bootstrap_jar(plan)
            if jar is None:
                raise StepError(
                    f"no {BOOTSTRAP_GLOB} under {plan.app.repo_root or '<repo>'}/cloud-driver-bootstrap/target - "
                    "run 'mvn clean install' first or tick 'Build with Maven'"
                )
            extensions = self.extension_jars(plan, log=ctx.info)
            if not extensions:
                ctx.warn("no extension jars found under cloud-driver-extensions/*/target - the server will run without REST API, terminal and the other extensions")
            jar_name = jar.name
            self._prune_stale_jars(ctx, [jar.name, *(ext.name for ext in extensions)])
            ctx.check_cancelled()
            self._upload_all(ctx, jar, extensions)
            ctx.check_cancelled()
        else:
            self._upload_start_script(ctx)
            ctx.check_cancelled()

        try:
            ctx.remote.mkdirs(self._scratch_dir(plan))
        except RemoteError as exc:
            raise StepError(f"could not create {self._scratch_dir(plan)}: {exc}") from exc

        self._ensure_start_env(ctx, jar_name)
        self._ensure_crontab(ctx)
        self._ensure_logrotate(ctx)
        ctx.check_cancelled()

        if plan.app.start_after_deploy:
            self._restart(ctx)
        else:
            ctx.info("start after deploy is off - the running instance (if any) keeps serving the old jars until restarted")

    def verify(self, ctx: Context) -> VerifyResult:
        """Poll the REST API on the loopback port until it answers ``401`` on ``/auth/me``."""
        plan = ctx.plan
        port = plan.app.rest_port
        if not plan.app.start_after_deploy and not self._session_running(ctx):
            ctx.discovered.screen_running = False
            return VerifyResult(True, f"deployed to {self._install_dir(plan)}; not started (start after deploy is off)")
        deadline = self.clock() + API_READY_TIMEOUT_SECONDS
        last = ""
        while True:
            last = self._api_status(ctx)
            if last == "401":
                ctx.discovered.screen_running = True
                return VerifyResult(True, f"API answering on 127.0.0.1:{port} (401 on /auth/me)")
            if not self._session_running(ctx):
                ctx.discovered.screen_running = False
                raise StepError(
                    f"screen session '{plan.server.screen_session}' died while waiting for the API:\n" + self._log_tail(ctx)
                )
            if self.clock() >= deadline:
                shown = last or "no answer"
                return VerifyResult(False, f"API on 127.0.0.1:{port} did not answer 401 within {int(API_READY_TIMEOUT_SECONDS)} s (last: {shown}); see {self._log_path(plan)}")
            self.sleep(API_POLL_INTERVAL_SECONDS)
            ctx.check_cancelled()

    def describe(self, plan: InstallPlan) -> str:
        """One summary line mirroring :meth:`apply`."""
        parts: list[str] = []
        if plan.app.build_with_maven:
            parts.append("build with Maven (mvn -q clean install -DskipTests)")
        if plan.app.deploy_jars:
            jar = self.bootstrap_jar(plan)
            count = len(self.extension_jars(plan))
            parts.append(f"prune stale jars and upload {jar.name if jar else BOOTSTRAP_GLOB} + {count} extension jars and start-cloud.sh to {self._install_dir(plan)}")
        else:
            parts.append(f"upload start-cloud.sh to {self._install_dir(plan)} (jars untouched)")
        cron = [name for name, on in (("autostart", plan.app.autostart_on_reboot), ("scratch sweep", plan.app.scratch_sweep), ("off-site backup", plan.app.backup_offsite and plan.aws.s3_enabled)) if on]
        parts.append("crontab block (" + ", ".join(cron) + ")" if cron else "remove the crontab block")
        if plan.app.persist_log:
            parts.append(f"logrotate for cloud.log")
        if plan.app.start_after_deploy:
            parts.append(f"restart screen session '{plan.server.screen_session}'")
        return ", ".join(parts)

    # --- apply parts -------------------------------------------------------------------------------

    def _build_with_maven(self, ctx: Context) -> None:
        """Local ``mvn -q clean install -DskipTests`` with its output streamed to the log."""
        repo = ctx.plan.app.repo_root
        command = ["mvn", "-q", "clean", "install", "-DskipTests"]
        ctx.info(f"building in {repo}: {' '.join(command)}")
        try:
            process = subprocess.Popen(command, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        except OSError as exc:
            raise StepError(f"could not start mvn in {repo}: {exc} - install Maven or untick 'Build with Maven'") from exc
        tail: deque[str] = deque(maxlen=25)
        assert process.stdout is not None
        try:
            for raw in process.stdout:
                line = raw.rstrip("\n")
                tail.append(line)
                ctx.debug("  " + line)
                try:
                    ctx.check_cancelled()
                except Cancelled:
                    process.terminate()
                    process.wait()
                    raise
        finally:
            process.stdout.close()
        code = process.wait()
        if code != 0:
            raise StepError(f"Maven build failed (exit {code}):\n" + "\n".join(tail))
        ctx.info("Maven build finished")

    def _prune_stale_jars(self, ctx: Context, keep: list[str]) -> None:
        """Remove versioned jars the plan supersedes - exactly the two patterns ``deploy-cloud.sh`` prunes."""
        plan = ctx.plan
        install_dir = shlex.quote(self._install_dir(plan))
        extensions_dir = shlex.quote(plan.extensions_dir)
        script = (
            'keep="$(cat)"\n'
            f"for existing in {install_dir}/{BOOTSTRAP_GLOB} {extensions_dir}/*.jar; do\n"
            '    [ -e "$existing" ] || continue\n'
            "    if ! printf '%s\\n' \"$keep\" | grep -qxF \"$(basename \"$existing\")\"; then\n"
            '        echo "removing stale $existing"\n'
            '        rm -f "$existing"\n'
            "    fi\n"
            "done\n"
        )
        try:
            result = ctx.remote.run(script, input="".join(f"{name}\n" for name in keep), check=True)
        except RemoteError as exc:
            raise StepError(f"could not prune stale jars on the server: {exc}") from exc
        for line in result.out.splitlines():
            match = _JAR_STALE_RE.match(line.strip())
            if match:
                ctx.info(f"removed stale jar {match.group(1)}")

    def _upload_all(self, ctx: Context, jar: Path, extensions: list[Path]) -> None:
        """Upload bootstrap + extensions + start-cloud.sh, skipping files whose SHA-256 already matches."""
        plan = ctx.plan
        script = self.start_script(plan)
        if script is None:
            raise StepError(f"{plan.app.repo_root}/shell/start-cloud.sh not found - the checkout is incomplete")
        targets: list[tuple[Path, str, int]] = [(jar, f"{self._install_dir(plan)}/{jar.name}", 0o644)]
        targets.extend((ext, f"{plan.extensions_dir}/{ext.name}", 0o644) for ext in extensions)
        targets.append((script, self._remote_start_script(plan), 0o755))
        total = len(targets)
        uploaded = 0
        for index, (local, remote_path, mode) in enumerate(targets):
            ctx.check_cancelled()
            expected = sha256_of_file(local)
            if ctx.remote.sha256(remote_path) == expected:
                ctx.debug(f"{local.name} unchanged on the server")
                if mode == 0o755:
                    self._restore_exec_bit(ctx, remote_path)
                ctx.progress((index + 1) / total, f"{local.name} unchanged")
                continue
            size = local.stat().st_size
            ctx.progress(index / total, f"uploading {local.name}")

            def on_bytes(done: int, total_bytes: int, _index: int = index, _name: str = local.name) -> None:
                fraction = done / total_bytes if total_bytes else 1.0
                ctx.progress((_index + fraction) / total, f"uploading {_name} ({done // 1024} / {max(total_bytes, 1) // 1024} KiB)")

            try:
                ctx.remote.put_file(local, remote_path, mode=mode, progress=on_bytes)
            except RemoteError as exc:
                raise StepError(f"upload of {local.name} failed: {exc}") from exc
            uploaded += 1
            ctx.info(f"uploaded {local.name} ({size // 1024} KiB) -> {remote_path}")
        ctx.progress(1.0, "uploads done")
        ctx.discovered.existing_jars = [jar.name, *(ext.name for ext in extensions)]
        if uploaded == 0:
            ctx.info("every jar and start-cloud.sh already matched the server")

    def _upload_start_script(self, ctx: Context) -> None:
        """``deploy_jars`` off: still ship the start script (it is what sources start-cloud.env)."""
        plan = ctx.plan
        script = self.start_script(plan)
        if script is None:
            raise StepError(f"{plan.app.repo_root}/shell/start-cloud.sh not found - the checkout is incomplete")
        remote_path = self._remote_start_script(plan)
        if ctx.remote.sha256(remote_path) == sha256_of_file(script):
            self._restore_exec_bit(ctx, remote_path)
            return
        try:
            ctx.remote.put_file(script, remote_path, mode=0o755)
        except RemoteError as exc:
            raise StepError(f"upload of start-cloud.sh failed: {exc}") from exc
        ctx.info(f"uploaded start-cloud.sh -> {remote_path}")

    def _restore_exec_bit(self, ctx: Context, remote_path: str) -> None:
        """``chmod +x`` on the start script (deploy-cloud.sh does the same: scp does not preserve it)."""
        ctx.remote.run(f"chmod +x {shlex.quote(remote_path)}", quiet=True)

    def _ensure_start_env(self, ctx: Context, jar_name: str) -> None:
        """Make sure ``start-cloud.env`` names the jar just deployed (the config step wrote it first)."""
        plan = ctx.plan
        path = self._remote_start_env(plan)
        expected = render_start_env(plan, jar_name)
        if ctx.remote.read_text(path) == expected:
            return
        try:
            ctx.remote.put_text(path, expected, mode=0o644)
        except RemoteError as exc:
            raise StepError(f"could not write {path}: {exc}") from exc
        ctx.info(f"wrote {path} (JAR_NAME={jar_name}, JVM_XMX={plan.app.jvm_xmx})")

    def _ensure_crontab(self, ctx: Context) -> None:
        """Replace the managed block in root's crontab (installed through ``crontab -`` on stdin)."""
        existing = self._read_crontab(ctx)
        managed = cron_lines(ctx.plan, ctx.secrets.s3_bucket)
        rendered = render_crontab(existing, managed)
        if rendered == existing or (not rendered and not existing.strip()):
            ctx.debug("crontab block already current")
            return
        try:
            ctx.remote.run("crontab -", input=rendered, check=True)
        except RemoteError as exc:
            raise StepError(f"could not install the crontab: {exc}") from exc
        if managed:
            ctx.info(f"installed crontab block ({len(managed)} entries: {', '.join(line.split(' ', 1)[0] if line.startswith('@') else 'scheduled' for line in managed)})")
        else:
            ctx.info("removed the installer's crontab block (every cron feature is off)")

    def _ensure_logrotate(self, ctx: Context) -> None:
        """Write ``/etc/logrotate.d/cloud-driver`` when the console log is persisted."""
        plan = ctx.plan
        if not plan.app.persist_log:
            if ctx.remote.exists(LOGROTATE_PATH):
                ctx.debug(f"{LOGROTATE_PATH} left in place (log persistence is off)")
            return
        expected = render_logrotate(plan)
        if ctx.remote.read_text(LOGROTATE_PATH) == expected:
            return
        try:
            ctx.remote.put_text(LOGROTATE_PATH, expected, mode=0o644)
        except RemoteError as exc:
            raise StepError(f"could not write {LOGROTATE_PATH}: {exc}") from exc
        ctx.info(f"wrote {LOGROTATE_PATH}")

    def _restart(self, ctx: Context) -> None:
        """Stop a running session (waiting for it to disappear) and start ``start-cloud.sh``."""
        plan = ctx.plan
        session = plan.server.screen_session
        if self._session_running(ctx):
            ctx.info(f"stopping screen session '{session}'")
            ctx.remote.run(f"screen -S {shlex.quote(session)} -X quit", quiet=True)
            deadline = self.clock() + SESSION_STOP_TIMEOUT_SECONDS
            while self._session_running(ctx):
                if self.clock() >= deadline:
                    raise StepError(f"screen session '{session}' did not stop within {int(SESSION_STOP_TIMEOUT_SECONDS)} s - stop it by hand (screen -S {session} -X quit) and retry")
                self.sleep(SESSION_POLL_INTERVAL_SECONDS)
        install_dir = self._install_dir(plan)
        try:
            ctx.remote.run(f"cd {shlex.quote(install_dir)} && ./start-cloud.sh", check=True)
        except RemoteError as exc:
            raise StepError(f"start-cloud.sh failed: {exc}") from exc
        ctx.discovered.screen_running = True
        ctx.info(f"started screen session '{session}' (attach with: screen -r {session})")

    def _log_tail(self, ctx: Context) -> str:
        """The last 40 lines of the persisted console log, or a note when there is none."""
        text = ctx.remote.read_text(self._log_path(ctx.plan))
        if not text:
            return f"(no {self._log_path(ctx.plan)} - enable 'persist log' to keep the console output)"
        return "\n".join(text.splitlines()[-40:])

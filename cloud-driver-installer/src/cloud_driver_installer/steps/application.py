"""The config files, the jars, the launcher, the scheduled jobs, and the final smoke test."""

from __future__ import annotations

import os
import re
import shlex
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

from cloud_driver_installer.config_files import (
    SCREEN_LOG_FILE,
    changed_sensitive_keys,
    masked,
    parse_json,
    removed_keys,
    render_configuration,
    render_postgres_credentials,
    render_redis_credentials,
    render_start_env,
    to_json,
)
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import timestamp
from cloud_driver_installer.secrets import generate_base64

#: Delimiters of the crontab region this installer owns; everything outside is never touched.
CRON_BEGIN = "# cloud-driver-installer BEGIN"
CRON_END = "# cloud-driver-installer END"

LOGROTATE_FILE = "/etc/logrotate.d/cloud-driver"

#: The eleven feature modules; the three marked required make the backend usable at all.
EXTENSION_MODULES: tuple[str, ...] = (
    "rest",
    "watcher",
    "terminal",
    "backup",
    "metrics",
    "thumbnails",
    "versioning",
    "search",
    "webhooks",
    "scan",
    "intelligence",
)
REQUIRED_EXTENSIONS: tuple[str, ...] = ("rest", "watcher", "terminal")


def render_crontab(existing: str, lines: list[str]) -> str:
    """Return ``existing`` with the managed region replaced by ``lines`` (removed when empty)."""
    kept: list[str] = []
    inside = False
    for line in existing.splitlines():
        if line.strip() == CRON_BEGIN:
            inside = True
            continue
        if line.strip() == CRON_END:
            inside = False
            continue
        if not inside:
            kept.append(line)
    while kept and not kept[-1].strip():
        kept.pop()
    if lines:
        kept.extend([CRON_BEGIN, *lines, CRON_END])
    return "\n".join(kept).lstrip("\n") + ("\n" if kept else "")


def render_logrotate(log_file: str) -> str:
    """The logrotate stanza for the console log (root-only, since it can carry verification codes)."""
    return (
        f"{log_file} {{\n"
        "    weekly\n"
        "    rotate 8\n"
        "    compress\n"
        "    missingok\n"
        "    notifempty\n"
        "    copytruncate\n"
        "    create 0600 root root\n"
        "}\n"
    )


class ConfigStep(Step):
    """Writes ``configuration.json``, the credentials files and ``start-cloud.env``."""

    id = "config"
    title = "Configuration files"
    mandatory = True
    depends_on = ("postgres", "aws")

    def resolve_secrets(self, ctx: Context) -> None:
        """Fill the JWT key and the intelligence secret: kept from the server unless rotating."""
        existing = ctx.discovered.existing_config
        if not ctx.secrets.jwt_signing_key:
            current = existing.get("jwt-signing-key")
            if isinstance(current, str) and current and not ctx.plan.app.jwt_rotate:
                ctx.secrets.jwt_signing_key, ctx.secrets.jwt_kept = current, True
            else:
                ctx.secrets.jwt_signing_key, ctx.secrets.jwt_kept = generate_base64(32), False
            ctx.remember_secret(ctx.secrets.jwt_signing_key)
        if ctx.plan.intelligence.enabled and not ctx.secrets.intelligence_secret:
            current = existing.get("intelligence-shared-secret")
            env_secret = ctx.discovered.intelligence_env.get("CLOUD_DRIVER_INTELLIGENCE_SECRET", "")
            if isinstance(current, str) and current and not ctx.plan.intelligence.secret_rotate:
                ctx.secrets.intelligence_secret, ctx.secrets.intelligence_secret_kept = current, True
                if env_secret and env_secret != current:
                    ctx.warn("[Configuration] the server's intelligence env file holds a different shared secret - it will be rewritten to match configuration.json")
            else:
                ctx.secrets.intelligence_secret, ctx.secrets.intelligence_secret_kept = generate_base64(32), False
            ctx.remember_secret(ctx.secrets.intelligence_secret)

    def documents(self, ctx: Context) -> dict[str, tuple[str, int]]:
        """``path -> (contents, mode)`` for every file this step owns."""
        self.resolve_secrets(ctx)
        plan = ctx.plan
        files: dict[str, tuple[str, int]] = {
            f"{plan.config_dir}/configuration.json": (to_json(render_configuration(plan, ctx.secrets, ctx.discovered.existing_config)), 0o600),
            f"{plan.server.install_dir.rstrip('/')}/start-cloud.env": (render_start_env(plan), 0o600),
        }
        password = ctx.secrets.pg_password or str(ctx.discovered.existing_postgres.get("password", ""))
        if password:
            files[f"{plan.config_dir}/postgres-database.json"] = (to_json(render_postgres_credentials(plan, password)), 0o600)
        if plan.redis.enabled and ctx.secrets.redis_password:
            files[f"{plan.config_dir}/redis-database.json"] = (to_json(render_redis_credentials(plan, ctx.secrets.redis_password)), 0o600)
        return files

    def check(self, ctx: Context) -> CheckResult:
        differing = [path for path, (text, _) in self.documents(ctx).items() if (ctx.remote.read_text(path) or "") != text]
        setattr(ctx.discovered, "config_changed", bool(differing))
        note = "JWT key kept" if ctx.secrets.jwt_kept else "JWT key generated"
        if not differing:
            return CheckResult.ok(f"all {len(self.documents(ctx))} files current · {note}")
        return CheckResult.needs_apply(f"{len(differing)} file(s) to write: {', '.join(Path(p).name for p in differing)} · {note}")

    def apply(self, ctx: Context) -> None:
        documents = self.documents(ctx)
        rendered = parse_json(documents[f"{ctx.plan.config_dir}/configuration.json"][0])
        for key in removed_keys(ctx.discovered.existing_config, rendered):
            ctx.warn(f"[Configuration] removing {key} from configuration.json (its feature is disabled in this plan)")
        for key in changed_sensitive_keys(ctx.discovered.existing_config, rendered):
            ctx.warn(f"[Configuration] {key} changes value - existing data may depend on the old one")
        for path, (text, mode) in documents.items():
            ctx.remote.put_text(path, text, mode=mode)
            ctx.info(f"[Configuration] wrote {path}")
        if not ctx.plan.redis.enabled and ctx.remote.exists(f"{ctx.plan.config_dir}/redis-database.json"):
            ctx.warn("[Configuration] redis-database.json is still on the server - Redis stays enabled for the backend until it is removed by hand")
        setattr(ctx.discovered, "config_changed", True)
        self._write_local(ctx, documents)

    def verify(self, ctx: Context) -> VerifyResult:
        documents = self.documents(ctx)
        for path, (text, _) in documents.items():
            if (ctx.remote.read_text(path) or "") != text:
                return VerifyResult(False, f"{path} does not match what was written")
        return VerifyResult(True, f"{len(documents)} files written · " + ", ".join(sorted(masked(parse_json(documents[f'{ctx.plan.config_dir}/configuration.json'][0])).keys())[:4]) + " …")

    def describe(self, plan: InstallPlan) -> str:
        targets = ["configuration.json", "postgres-database.json", "start-cloud.env"]
        if plan.redis.enabled:
            targets.insert(2, "redis-database.json")
        local = " + write configuration.json back to the checkout" if plan.app.write_local_config else ""
        return f"write {', '.join(targets)} into {plan.config_dir}{local}"

    def _write_local(self, ctx: Context, documents: dict[str, tuple[str, int]]) -> None:
        plan = ctx.plan
        if not plan.app.repo_root or not plan.app.write_local_config:
            if not plan.app.write_local_config:
                ctx.warn("[Configuration] write-back is off: the next shell/deploy-cloud.sh run would overwrite the server's configuration.json with the local copy")
            return
        local_dir = Path(plan.app.repo_root) / "cloud-driver"
        local_dir.mkdir(parents=True, exist_ok=True)
        backup_dir = Path.home() / ".config" / "cloud-driver-installer" / "backups" / timestamp()
        wanted = {"configuration.json"}
        if plan.app.write_local_db_config:
            wanted |= {"postgres-database.json", "redis-database.json"}
        for path, (text, _) in documents.items():
            name = Path(path).name
            if name not in wanted:
                continue
            target = local_dir / name
            if target.is_file() and target.read_text() != text:
                backup_dir.mkdir(parents=True, exist_ok=True)
                backup = backup_dir / name
                backup.write_text(target.read_text())
                os.chmod(backup, 0o600)
            target.write_text(text)
            os.chmod(target, 0o600)
            ctx.info(f"[Configuration] wrote {target}")


class ApplicationStep(Step):
    """Uploads the jars and the launcher, installs the scheduled jobs, and (re)starts the JVM."""

    id = "application"
    title = "Application"
    mandatory = True
    depends_on = ("java", "config")

    # --- local artefacts -------------------------------------------------------------------------

    @staticmethod
    def bootstrap_jar(plan: InstallPlan) -> Path | None:
        """The shaded bootstrap jar in the checkout (the ``original-`` one is Maven's, not ours)."""
        if not plan.app.repo_root:
            return None
        target = Path(plan.app.repo_root) / "cloud-driver-bootstrap" / "target"
        jars = sorted(p for p in target.glob("cloud-driver-bootstrap-*.jar") if not p.name.startswith("original-"))
        return jars[-1] if jars else None

    @staticmethod
    def bootstrap_jar_name(plan: InstallPlan) -> str:
        """Its file name, for the pruning keep-list."""
        jar = ApplicationStep.bootstrap_jar(plan)
        return jar.name if jar else ""

    @staticmethod
    def module_jars(plan: InstallPlan) -> dict[str, Path | None]:
        """``module -> built jar`` for all eleven extensions (``None`` when not built)."""
        result: dict[str, Path | None] = {}
        root = Path(plan.app.repo_root) if plan.app.repo_root else None
        for module in EXTENSION_MODULES:
            jar = None
            if root:
                target = root / "cloud-driver-extensions" / f"cloud-driver-extensions-{module}" / "target"
                jars = sorted(p for p in target.glob(f"cloud-driver-extensions-{module}-*.jar") if not p.name.startswith("original-"))
                jar = jars[-1] if jars else None
            result[module] = jar
        return result

    @staticmethod
    def selected_modules(plan: InstallPlan) -> list[str]:
        """The extensions this plan deploys (features that are off are left out on purpose)."""
        selected = []
        for module in EXTENSION_MODULES:
            if module in plan.app.excluded_extensions:
                continue
            if module == "scan" and not plan.clamav.enabled:
                continue
            if module == "intelligence" and not plan.intelligence.enabled:
                continue
            selected.append(module)
        return selected

    def upload_set(self, ctx: Context) -> dict[Path, str]:
        """``local jar -> remote path`` for everything this run deploys."""
        plan = ctx.plan
        bootstrap = self.bootstrap_jar(plan)
        if bootstrap is None:
            raise StepError("no cloud-driver-bootstrap jar in the checkout - run 'mvn clean install' or tick 'Build with Maven'")
        version = re.sub(r"^cloud-driver-bootstrap-|\.jar$", "", bootstrap.name)
        uploads: dict[Path, str] = {bootstrap: f"{plan.server.install_dir.rstrip('/')}/{bootstrap.name}"}
        jars = self.module_jars(plan)
        missing_required = []
        for module in self.selected_modules(plan):
            jar = jars[module]
            if jar is None:
                if module in REQUIRED_EXTENSIONS:
                    missing_required.append(module)
                else:
                    ctx.warn(f"[Application] cloud-driver-extensions-{module} is not built - that feature will be missing")
                continue
            if version not in jar.name:
                raise StepError(f"{jar.name} does not match the bootstrap version {version} - rebuild the whole reactor, mixed versions crash at startup")
            uploads[jar] = f"{plan.extensions_dir}/{jar.name}"
        if missing_required:
            raise StepError("these modules are not built but are required: " + ", ".join(missing_required) + " - run 'mvn clean install' first")
        return uploads

    def cron_lines(self, plan: InstallPlan) -> list[str]:
        """The managed crontab region for this plan."""
        install_dir = plan.server.install_dir.rstrip("/")
        lines: list[str] = []
        if plan.app.autostart_on_reboot:
            # screen (not a plain systemd ExecStart) because the operator terminal needs a pty.
            lines.append(f"@reboot cd {install_dir} && ./start-cloud.sh")
        if plan.app.scratch_sweep:
            lines.append(f"17 * * * * find {install_dir}/upload-scratch -name 'upload-*.tmp' -mmin +180 -delete")
        if plan.app.backup_offsite and plan.aws.s3_enabled:
            lines.append(
                f"30 3 * * * aws s3 sync {plan.config_dir}/backup s3://{plan.aws.effective_backup_bucket}/$(hostname)/ "
                "--exclude '.staging/*' --only-show-errors"
            )
        return lines

    # --- step ------------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        if ctx.plan.app.build_with_maven:
            return CheckResult.needs_apply("a Maven build was requested")
        uploads = self.upload_set(ctx)
        outdated = [local.name for local, remote in uploads.items() if ctx.remote.sha256(remote) != self._sha(local)]
        launcher = self._launcher_outdated(ctx)
        cron_ok = self._cron_current(ctx)
        running = ctx.remote.run_ok(f"screen -list 2>/dev/null | grep -q '[.]{ctx.plan.server.screen_session}[[:space:]]'")
        problems = []
        if outdated:
            problems.append(f"{len(outdated)} jar(s) to upload")
        if launcher:
            problems.append("start-cloud.sh")
        if not cron_ok:
            problems.append("scheduled jobs")
        if not running and ctx.plan.app.start_after_deploy:
            problems.append("JVM not running")
        if getattr(ctx.discovered, "config_changed", False):
            problems.append("configuration changed - restart required")
        if problems:
            return CheckResult.needs_apply(" · ".join(problems))
        return CheckResult.ok(f"{len(uploads)} jars current · JVM running in screen '{ctx.plan.server.screen_session}'")

    def apply(self, ctx: Context) -> None:
        plan = ctx.plan
        if plan.app.build_with_maven:
            self._maven(ctx)
        uploads = self.upload_set(ctx)
        self._check_launcher_source(ctx)

        total = len(uploads) + 1
        for index, (local, remote) in enumerate(uploads.items(), start=1):
            ctx.check_cancelled()
            ctx.progress(index / total, f"uploading {local.name}")
            if ctx.remote.sha256(remote) == self._sha(local):
                ctx.debug(f"[Application] {local.name} already current")
                continue
            ctx.remote.put_file(local, remote)
            ctx.info(f"[Application] uploaded {local.name}")
        self._prune(ctx, uploads)

        launcher = Path(plan.app.repo_root) / "shell" / "start-cloud.sh"
        ctx.remote.put_file(launcher, f"{plan.server.install_dir.rstrip('/')}/start-cloud.sh", mode=0o755)
        ctx.remote.mkdirs(f"{plan.server.install_dir.rstrip('/')}/upload-scratch")
        if plan.app.persist_log:
            ctx.remote.mkdirs(str(Path(SCREEN_LOG_FILE).parent), mode=0o700)
            ctx.remote.put_text(LOGROTATE_FILE, render_logrotate(SCREEN_LOG_FILE))
        self._install_cron(ctx)
        if plan.app.start_after_deploy:
            self._restart(ctx)

    def verify(self, ctx: Context) -> VerifyResult:
        port = ctx.plan.app.rest_port
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            ctx.check_cancelled()
            code = ctx.remote.run(f"curl -s -o /dev/null -w '%{{http_code}}' -m 3 http://127.0.0.1:{port}/auth/me", quiet=True).out.strip()
            if code == "401":  # the API is up and the JWT layer is active
                return VerifyResult(True, f"API answering on 127.0.0.1:{port}")
            if code.startswith("2") or code.startswith("4"):
                return VerifyResult(True, f"API answering on 127.0.0.1:{port} (HTTP {code})")
            if not ctx.remote.run_ok(f"screen -list 2>/dev/null | grep -q '[.]{ctx.plan.server.screen_session}[[:space:]]'"):
                tail = ctx.remote.run(f"tail -n 40 {SCREEN_LOG_FILE} 2>/dev/null", quiet=True).out
                return VerifyResult(False, "the screen session exited" + (f":\n{tail.strip()}" if tail.strip() else ""))
            time.sleep(3)
        return VerifyResult(False, f"no answer from 127.0.0.1:{port} within 120s - attach with 'screen -r {ctx.plan.server.screen_session}' to see why")

    def describe(self, plan: InstallPlan) -> str:
        modules = self.selected_modules(plan)
        parts = [f"upload the bootstrap jar + {len(modules)} extension jars, start-cloud.sh"]
        if plan.app.build_with_maven:
            parts.insert(0, "run mvn clean install")
        if self.cron_lines(plan):
            parts.append(f"{len(self.cron_lines(plan))} cron job(s)")
        if plan.app.start_after_deploy:
            parts.append(f"(re)start the screen session '{plan.server.screen_session}'")
        return " · ".join(parts)

    # --- internals -------------------------------------------------------------------------------

    @staticmethod
    def _sha(path: Path) -> str:
        from cloud_driver_installer.remote import sha256_of_file

        return sha256_of_file(path)

    def _maven(self, ctx: Context) -> None:
        ctx.info("[Application] running mvn clean install (this takes a few minutes)")
        process = subprocess.Popen(
            ["mvn", "-B", "--no-transfer-progress", "clean", "install", "-DskipTests"],
            cwd=ctx.plan.app.repo_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        assert process.stdout is not None
        for line in process.stdout:
            ctx.debug("  " + line.rstrip())
        if process.wait() != 0:
            raise StepError("the Maven build failed - see the log above (a missing GitHub Packages token in ~/.m2/settings.xml is the usual cause)")

    def _check_launcher_source(self, ctx: Context) -> None:
        launcher = Path(ctx.plan.app.repo_root) / "shell" / "start-cloud.sh"
        if not launcher.is_file():
            raise StepError(f"{launcher} not found in the checkout")
        text = launcher.read_text()
        if "start-cloud.env" not in text or "jar_candidates" not in text:
            raise StepError("this checkout's shell/start-cloud.sh predates the start-cloud.env contract - update your checkout before deploying")

    def _launcher_outdated(self, ctx: Context) -> bool:
        launcher = Path(ctx.plan.app.repo_root) / "shell" / "start-cloud.sh" if ctx.plan.app.repo_root else None
        if launcher is None or not launcher.is_file():
            return False
        return ctx.remote.sha256(f"{ctx.plan.server.install_dir.rstrip('/')}/start-cloud.sh") != self._sha(launcher)

    def _prune(self, ctx: Context, uploads: dict[Path, str]) -> None:
        """Remove superseded versions of the jars just uploaded; never a jar nothing replaced."""
        keep = {Path(remote).name for remote in uploads.values()}
        stems = {re.sub(r"-\d[\d.]*\.jar$", "", name) for name in keep}
        listing = ctx.remote.run(
            f"ls -1 {shlex.quote(ctx.plan.server.install_dir.rstrip('/'))}/cloud-driver-bootstrap-*.jar {shlex.quote(ctx.plan.extensions_dir)}/*.jar 2>/dev/null",
            quiet=True,
        ).out
        for path in [line.strip() for line in listing.splitlines() if line.strip()]:
            name = Path(path).name
            if name in keep:
                continue
            if re.sub(r"-\d[\d.]*\.jar$", "", name) in stems:
                ctx.remote.run(f"rm -f {shlex.quote(path)}", check=True, quiet=True)
                ctx.info(f"[Application] removed superseded {name}")

    def _cron_current(self, ctx: Context) -> bool:
        existing = ctx.remote.run("crontab -l 2>/dev/null", quiet=True).out
        wanted = render_crontab(existing, self.cron_lines(ctx.plan))
        return wanted.strip() == existing.strip()

    def _install_cron(self, ctx: Context) -> None:
        lines = self.cron_lines(ctx.plan)
        existing = ctx.remote.run("crontab -l 2>/dev/null", quiet=True).out
        updated = render_crontab(existing, lines)
        if updated != existing:
            ctx.remote.run("crontab -", input=updated, check=True)
            ctx.info(f"[Application] installed {len(lines)} scheduled job(s) in root's crontab")

    def _restart(self, ctx: Context) -> None:
        session = ctx.plan.server.screen_session
        install_dir = ctx.plan.server.install_dir.rstrip("/")
        running = ctx.remote.run_ok(f"screen -list 2>/dev/null | grep -q '[.]{session}[[:space:]]'")
        if running:
            pid = ctx.remote.run("pgrep -f 'java .*cloud-driver-bootstrap' | head -1", quiet=True).out.strip()
            ctx.remote.run(f"screen -S {shlex.quote(session)} -X quit", check=False)
            if pid:
                # The old JVM runs shutdown hooks for a few seconds; starting before it releases
                # the REST port leaves the new process up with the API silently not started.
                for attempt in range(20):
                    if not ctx.remote.run_ok(f"kill -0 {pid} 2>/dev/null"):
                        break
                    if attempt == 12:
                        ctx.warn(f"[Application] JVM {pid} still running - sending SIGTERM")
                        ctx.remote.run(f"kill {pid}", check=False, quiet=True)
                    if attempt == 18:
                        ctx.warn(f"[Application] JVM {pid} still running - sending SIGKILL")
                        ctx.remote.run(f"kill -9 {pid}", check=False, quiet=True)
                    time.sleep(3)
            ctx.info("[Application] stopped the running instance")
        ctx.remote.run(f"cd {shlex.quote(install_dir)} && ./start-cloud.sh", check=True, timeout=120)
        ctx.info(f"[Application] started the JVM in screen session '{session}'")


class SmokeStep(Step):
    """The end-to-end probe: the API, the metrics port, the daemons and the scheduled jobs."""

    id = "smoke"
    title = "Smoke test"
    mandatory = True
    depends_on = ("application",)

    def check(self, ctx: Context) -> CheckResult:
        return CheckResult.needs_apply("probe the running deployment")

    def apply(self, ctx: Context) -> None:
        return None

    def verify(self, ctx: Context) -> VerifyResult:
        plan = ctx.plan
        results: list[str] = []
        code = ctx.remote.run(f"curl -s -o /dev/null -w '%{{http_code}}' -m 5 http://127.0.0.1:{plan.app.rest_port}/auth/me", quiet=True).out.strip()
        api_ok = code.startswith("4") or code.startswith("2")
        results.append(f"API 127.0.0.1:{plan.app.rest_port} HTTP {code or 'no answer'}")
        if ctx.remote.run_ok(f"timeout 3 bash -c '</dev/tcp/127.0.0.1/{plan.app.metrics_port}'"):
            results.append(f"metrics :{plan.app.metrics_port} open")
        if plan.clamav.enabled:
            results.append("clamd " + ("active" if ctx.remote.service_active("clamav-daemon.socket") else "NOT active"))
        if plan.intelligence.enabled:
            healthy = ctx.remote.run_ok(f"curl -fsS -m 5 http://127.0.0.1:{plan.intelligence.port}/health")
            results.append("intelligence " + ("healthy" if healthy else "not answering"))
        if plan.app.autostart_on_reboot:
            has_block = CRON_BEGIN in ctx.remote.run("crontab -l 2>/dev/null", quiet=True).out
            results.append("reboot autostart " + ("installed" if has_block else "MISSING"))
        if plan.proxy.enabled and plan.proxy.api_domain:
            results.append(self._public_probe(ctx))
        return VerifyResult(api_ok, " · ".join(results))

    def describe(self, plan: InstallPlan) -> str:
        return "probe the API, the metrics port, the daemons, the cron block and the public URL"

    @staticmethod
    def _public_probe(ctx: Context) -> str:
        url = f"https://{ctx.plan.proxy.api_domain}/auth/me"
        try:
            with urllib.request.urlopen(url, timeout=10) as response:  # noqa: S310 - fixed https URL
                return f"{url} HTTP {response.status}"
        except urllib.error.HTTPError as exc:
            return f"{url} HTTP {exc.code}"
        except Exception as exc:  # noqa: BLE001 - certificate/DNS/firewall are all "not yet"
            return f"{url} not reachable from here yet ({type(exc).__name__}) - certificate, DNS or provider firewall"

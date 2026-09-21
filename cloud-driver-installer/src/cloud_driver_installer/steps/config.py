"""Config step: the three JSON files the JVM reads at boot plus ``start-cloud.env``.

Everything is rendered by :mod:`cloud_driver_installer.config_files`; this step decides the
secrets that go into the documents and compares them with what the server already holds:

* ``jwt-signing-key`` - kept from the server's ``configuration.json`` unless the operator ticked
  rotate (a rotated key logs every client out, so it is never done silently);
* ``intelligence-shared-secret`` - kept from ``configuration.json`` or, when that has none yet,
  from the service's ``/etc/cloud-driver-intelligence.env`` (the two must agree: the JVM sends it,
  the service checks it; ``configuration.json`` is the source of truth and the intelligence step
  rewrites the env file from the same value);
* the Postgres/Redis passwords - resolved by their own steps (``ctx.secrets``), read back from
  the existing credential files when those steps were skipped.

Nothing here rotates a credential it cannot also write down, mirroring
``shell/provision-root-server.sh``. Files carrying secrets are written ``0600`` and every rewrite
of an existing file leaves a ``.bak-<ts>`` behind (``put_text`` does that).
"""

from __future__ import annotations

import os
import shutil
import time
from dataclasses import dataclass
from pathlib import Path

from cloud_driver_installer.config_files import (
    parse_env_file,
    parse_json,
    render_configuration,
    render_postgres_credentials,
    render_redis_credentials,
    render_start_env,
    to_json,
)
from cloud_driver_installer.engine import CheckResult, Context, Step, StepError, VerifyResult
from cloud_driver_installer.model import InstallPlan
from cloud_driver_installer.remote import RemoteError
from cloud_driver_installer.secrets import generate_base64
from cloud_driver_installer.sizing import suggest_jvm_xmx
from cloud_driver_installer.steps.application import ApplicationStep

#: The intelligence service's environment file (see cloud-driver-intelligence/deploy/install-on-server.sh).
INTELLIGENCE_ENV_PATH = "/etc/cloud-driver-intelligence.env"
INTELLIGENCE_SECRET_ENV = "CLOUD_DRIVER_INTELLIGENCE_SECRET"

CONFIGURATION_JSON = "configuration.json"
POSTGRES_JSON = "postgres-database.json"
REDIS_JSON = "redis-database.json"
START_ENV = "start-cloud.env"


@dataclass(frozen=True)
class FileSpec:
    """One rendered file: where it goes, what it holds, and how to compare it."""

    label: str      # file name, for log lines and the detail
    path: str       # remote absolute path
    text: str
    mode: int
    json: bool      # compare as parsed JSON (key order and whitespace do not matter)
    local: bool     # also written to <repo>/cloud-driver/ on write-back

    def same_as(self, current: str | None) -> bool:
        """Whether ``current`` (the server's file, ``None`` when absent) already equals this spec."""
        if current is None:
            return False
        if self.json:
            return parse_json(current) == parse_json(self.text)
        return current == self.text


class ConfigStep(Step):
    """Write ``configuration.json``, the credential files and ``start-cloud.env``; verify by reading back."""

    id = "config"
    title = "Configuration files"
    depends_on = ("postgres", "aws")
    mandatory = True

    # --- paths -------------------------------------------------------------------------------------

    @staticmethod
    def _config_path(plan: InstallPlan, name: str) -> str:
        return f"{plan.config_dir}/{name}"

    @staticmethod
    def _start_env_path(plan: InstallPlan) -> str:
        return f"{plan.server.install_dir.rstrip('/')}/{START_ENV}"

    # --- secret resolution (deterministic: check and apply agree) ----------------------------------

    def _resolve(self, ctx: Context, existing: dict) -> None:
        """Fill ``ctx.secrets`` for every value the documents need, registering each with the redactor."""
        plan, secrets, remote = ctx.plan, ctx.secrets, ctx.remote

        # JWT signing key: keep the server's unless rotate; a value generated earlier in this run
        # is reused so check, apply and verify all render the same document.
        current_jwt = str(existing.get("jwt-signing-key") or "")
        ctx.remember_secret(current_jwt)
        if current_jwt and not plan.app.jwt_rotate:
            secrets.jwt_signing_key = current_jwt
            secrets.jwt_kept = True
        elif not (secrets.jwt_signing_key and not secrets.jwt_kept):
            secrets.jwt_signing_key = generate_base64(32)
            secrets.jwt_kept = False
        ctx.remember_secret(secrets.jwt_signing_key)

        # Intelligence shared secret: configuration.json, else the service's env file, else new.
        if plan.intelligence.enabled:
            env = parse_env_file(remote.read_text(INTELLIGENCE_ENV_PATH))
            env_secret = env.get(INTELLIGENCE_SECRET_ENV, "")
            config_secret = str(existing.get("intelligence-shared-secret") or "")
            ctx.remember_secret(env_secret)
            ctx.remember_secret(config_secret)
            if (config_secret or env_secret) and not plan.intelligence.secret_rotate:
                secrets.intelligence_secret = config_secret or env_secret
                secrets.intelligence_secret_kept = True
                if config_secret and env_secret and config_secret != env_secret:
                    ctx.warn(
                        f"intelligence-shared-secret in {CONFIGURATION_JSON} differs from {INTELLIGENCE_SECRET_ENV} in "
                        f"{INTELLIGENCE_ENV_PATH}; {CONFIGURATION_JSON} wins and the intelligence step rewrites the env file"
                    )
            elif not (secrets.intelligence_secret and not secrets.intelligence_secret_kept):
                secrets.intelligence_secret = generate_base64(32)
                secrets.intelligence_secret_kept = False
            ctx.remember_secret(secrets.intelligence_secret)

        # Postgres password: the postgres step resolved it; fall back to the plan, then the file.
        if not secrets.pg_password:
            if plan.postgres.password:
                secrets.pg_password = plan.postgres.password
                secrets.pg_password_kept = False
            else:
                kept = str(parse_json(remote.read_text(self._config_path(plan, POSTGRES_JSON))).get("password") or "")
                if not kept:
                    raise StepError(
                        f"no PostgreSQL password to write: run the PostgreSQL step first or enter one on its page "
                        f"({self._config_path(plan, POSTGRES_JSON)} does not exist on the server)"
                    )
                secrets.pg_password = kept
                secrets.pg_password_kept = True
        ctx.remember_secret(secrets.pg_password)

        # Redis password, same rules, only when Redis is part of the plan.
        if plan.redis.enabled and not secrets.redis_password:
            if plan.redis.password:
                secrets.redis_password = plan.redis.password
                secrets.redis_password_kept = False
            else:
                kept = str(parse_json(remote.read_text(self._config_path(plan, REDIS_JSON))).get("password") or "")
                if not kept:
                    raise StepError(
                        f"no Redis password to write: run the Redis step first or enter one on its page "
                        f"({self._config_path(plan, REDIS_JSON)} does not exist on the server)"
                    )
                secrets.redis_password = kept
                secrets.redis_password_kept = True
        if plan.redis.enabled:
            ctx.remember_secret(secrets.redis_password)

        if plan.email.mode == "smtp":
            ctx.remember_secret(plan.email.smtp_password)

        # The heap is normally suggested by the server step; only fill it when still blank.
        if not plan.app.jvm_xmx:
            ram = ctx.discovered.ram_mib
            plan.app.jvm_xmx = (
                suggest_jvm_xmx(
                    ram,
                    postgres_local=plan.postgres.mode == "install",
                    clamav_enabled=plan.clamav.enabled,
                    intelligence_enabled=plan.intelligence.enabled,
                )
                if ram
                else "6g"
            )
            ctx.debug(f"jvm_xmx was blank - using {plan.app.jvm_xmx}")

    # --- rendering ---------------------------------------------------------------------------------

    def _read_existing_configuration(self, ctx: Context) -> dict:
        """The server's current ``configuration.json`` as a dict (``{}`` when absent or invalid)."""
        return parse_json(ctx.remote.read_text(self._config_path(ctx.plan, CONFIGURATION_JSON)))

    def _render(self, ctx: Context, existing: dict) -> list[FileSpec]:
        """Every document the step manages, in write order (secrets must already be resolved)."""
        plan, secrets = ctx.plan, ctx.secrets
        specs = [
            FileSpec(CONFIGURATION_JSON, self._config_path(plan, CONFIGURATION_JSON), to_json(render_configuration(plan, secrets, existing)), 0o600, True, True),
            FileSpec(POSTGRES_JSON, self._config_path(plan, POSTGRES_JSON), to_json(render_postgres_credentials(plan, secrets.pg_password)), 0o600, True, True),
        ]
        if plan.redis.enabled:
            specs.append(FileSpec(REDIS_JSON, self._config_path(plan, REDIS_JSON), to_json(render_redis_credentials(plan, secrets.redis_password)), 0o600, True, True))
        jar_name = ApplicationStep.bootstrap_jar_name(plan, ctx.discovered)
        specs.append(FileSpec(START_ENV, self._start_env_path(plan), render_start_env(plan, jar_name), 0o644, False, False))
        return specs

    def _prepare(self, ctx: Context) -> list[FileSpec]:
        """Resolve secrets and render - the shared prologue of check and apply."""
        existing = self._read_existing_configuration(ctx)
        self._resolve(ctx, existing)
        return self._render(ctx, existing)

    def _changes(self, ctx: Context, specs: list[FileSpec]) -> list[str]:
        """``"<label> (new|changed)"`` for every spec whose remote file differs."""
        changes: list[str] = []
        for spec in specs:
            current = ctx.remote.read_text(spec.path)
            if current is None:
                changes.append(f"{spec.label} (new)")
            elif not spec.same_as(current):
                changes.append(f"{spec.label} (changed)")
        return changes

    def _warn_orphaned_redis_file(self, ctx: Context) -> None:
        """Redis off but a credential file exists: say so, never delete it."""
        plan = ctx.plan
        if not plan.redis.enabled and ctx.remote.exists(self._config_path(plan, REDIS_JSON)):
            ctx.warn(f"Redis is disabled in the plan but {self._config_path(plan, REDIS_JSON)} exists - left untouched (the backend ignores it without Redis)")

    def _kept_summary(self, ctx: Context) -> str:
        """``JWT key kept · intelligence secret generated`` for the detail line."""
        secrets, plan = ctx.secrets, ctx.plan
        parts = ["JWT key " + ("kept" if secrets.jwt_kept else ("rotated" if plan.app.jwt_rotate else "generated"))]
        if plan.intelligence.enabled:
            parts.append("intelligence secret " + ("kept" if secrets.intelligence_secret_kept else "generated"))
        return " · ".join(parts)

    # --- Step -------------------------------------------------------------------------------------

    def check(self, ctx: Context) -> CheckResult:
        """Render everything and compare with the server's files (JSON compared parsed)."""
        specs = self._prepare(ctx)
        self._warn_orphaned_redis_file(ctx)
        changes = self._changes(ctx, specs)
        labels = ", ".join(spec.label for spec in specs)
        if changes:
            return CheckResult.needs_apply("write " + ", ".join(changes) + f" · {self._kept_summary(ctx)}")
        return CheckResult.ok(f"{labels} up to date in {ctx.plan.config_dir} · {self._kept_summary(ctx)}")

    def apply(self, ctx: Context) -> None:
        """Write every file that differs (0600 for the credential-bearing ones) and the local copies."""
        plan = ctx.plan
        specs = self._prepare(ctx)
        self._warn_orphaned_redis_file(ctx)
        try:
            ctx.remote.mkdirs(plan.config_dir)
        except RemoteError as exc:
            raise StepError(f"could not create {plan.config_dir}: {exc}") from exc
        written = 0
        for index, spec in enumerate(specs):
            ctx.check_cancelled()
            ctx.progress(index / len(specs), f"writing {spec.label}")
            current = ctx.remote.read_text(spec.path)
            if spec.same_as(current):
                ctx.debug(f"{spec.path} already current")
                continue
            try:
                ctx.remote.put_text(spec.path, spec.text, mode=spec.mode)
            except RemoteError as exc:
                raise StepError(f"could not write {spec.path}: {exc}") from exc
            written += 1
            ctx.info(f"wrote {spec.path}" + ("" if current is None else " (previous copy kept as .bak-<ts>)"))
        ctx.progress(1.0, "configuration written")
        if written == 0:
            ctx.info("every configuration file already matched")
        self._refresh_discovered(ctx, specs)
        if plan.app.write_local_config and plan.app.repo_root:
            self._write_local_copies(ctx, specs)

    def verify(self, ctx: Context) -> VerifyResult:
        """Read the remote files back and compare them with what was rendered."""
        specs = self._render(ctx, self._read_existing_configuration(ctx))
        changes = self._changes(ctx, specs)
        if changes:
            return VerifyResult(False, "not on the server as rendered: " + ", ".join(changes))
        return VerifyResult(True, ", ".join(spec.label for spec in specs) + f" verified in {ctx.plan.config_dir} · {self._kept_summary(ctx)}")

    def describe(self, plan: InstallPlan) -> str:
        """One summary line mirroring :meth:`apply`."""
        files = [CONFIGURATION_JSON, POSTGRES_JSON] + ([REDIS_JSON] if plan.redis.enabled else [])
        jwt = "rotate the JWT signing key" if plan.app.jwt_rotate else "JWT signing key kept when present"
        line = f"write {', '.join(files)} to {plan.config_dir} and {START_ENV} to {plan.server.install_dir.rstrip('/')} ({jwt})"
        if plan.app.write_local_config and plan.app.repo_root:
            line += f", copy the JSON files to {plan.app.repo_root}/cloud-driver/"
        return line

    # --- side effects ------------------------------------------------------------------------------

    def _refresh_discovered(self, ctx: Context, specs: list[FileSpec]) -> None:
        """Keep ``ctx.discovered`` in step with what was just written (later steps read it)."""
        for spec in specs:
            if spec.label == CONFIGURATION_JSON:
                ctx.discovered.existing_config = parse_json(spec.text)
            elif spec.label == POSTGRES_JSON:
                ctx.discovered.existing_postgres = parse_json(spec.text)
            elif spec.label == REDIS_JSON:
                ctx.discovered.existing_redis = parse_json(spec.text)

    def _write_local_copies(self, ctx: Context, specs: list[FileSpec]) -> None:
        """Write the JSON files into ``<repo_root>/cloud-driver/`` (0600, ``.bak-<ts>`` of a differing existing one)."""
        target_dir = Path(ctx.plan.app.repo_root) / "cloud-driver"
        try:
            target_dir.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise StepError(f"could not create {target_dir}: {exc}") from exc
        stamp = time.strftime("%Y%m%d%H%M%S")
        for spec in specs:
            if not spec.local:
                continue
            path = target_dir / spec.label
            try:
                if path.exists():
                    if path.read_text(encoding="utf-8") == spec.text:
                        ctx.debug(f"{path} already current")
                        continue
                    backup = Path(f"{path}.bak-{stamp}")
                    shutil.copy2(path, backup)
                    ctx.debug(f"backed up {path} -> {backup}")
                descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                    handle.write(spec.text)
                os.chmod(path, 0o600)
            except OSError as exc:
                raise StepError(f"could not write the local copy {path}: {exc}") from exc
            ctx.info(f"wrote local copy {path}")

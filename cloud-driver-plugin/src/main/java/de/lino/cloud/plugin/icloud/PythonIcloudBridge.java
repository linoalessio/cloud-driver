package de.lino.cloud.plugin.icloud;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import de.lino.cloud.api.icloud.IcloudAuthenticationException;
import de.lino.cloud.api.icloud.IcloudBridge;
import de.lino.cloud.api.icloud.IcloudBridgeException;
import de.lino.cloud.api.icloud.IcloudLoginResult;
import de.lino.cloud.api.icloud.IcloudTreeEntry;
import de.lino.cloud.api.utility.Constraints;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The one real {@link IcloudBridge} implementation - shells out to a bundled Python script (via
 * {@link ProcessBuilder}) that talks to Apple's private iCloud API through the {@code pyicloud}
 * library, since no Java equivalent exists. Each call spawns a fresh {@code python3} process, writes
 * one JSON command to its standard input, and reads one JSON response from its standard output -
 * mirroring {@code cloud-driver-platforms-rest}'s {@code LinuxSecretServiceTokenStore#run} almost
 * exactly (drain stdout/stderr fully before {@link Process#waitFor}, wrap {@link IOException}/{@link
 * InterruptedException}, kill and throw on timeout), just with a JSON payload instead of a bare
 * string and per-call timeouts long enough for a real login/tree-walk/file transfer.
 *
 * <p><b>Requires {@code python3} and the {@code pyicloud} package to be installed and on {@code
 * PATH} on whatever host runs this process</b> - a genuinely new operational dependency for this
 * codebase (every other module ships as a single self-contained shaded jar). The constructor checks
 * for both up front and throws {@link IcloudBridgeException} if either is missing, so the caller
 * (see {@code CloudRestExtension#buildIcloudImportService}) can catch that and simply not publish an
 * {@link de.lino.cloud.api.icloud.IcloudImportService} at all - the whole feature disables itself
 * with a logged warning rather than crashing the JVM, the same lesson {@code
 * cloud-driver-extensions-metrics}'s own missing-dependency incident already taught this codebase
 * (see {@code CLAUDE.md}'s "Metrics/observability exporter" section).
 */
public final class PythonIcloudBridge implements IcloudBridge {

    /** Timeout for login/2FA-confirm/tree-listing calls - network-bound but not transferring file content. */
    private static final long METADATA_TIMEOUT_SECONDS = 60L;

    /** Timeout for a single file download - generous, since a large file over a slow link can legitimately take a while. */
    private static final long DOWNLOAD_TIMEOUT_SECONDS = 300L;

    /** Classpath resource the bridge script is extracted from. */
    private static final String BRIDGE_SCRIPT_RESOURCE = "/icloud/icloud_bridge.py";

    /** Shared Gson instance for encoding requests / decoding responses. */
    private final Gson gson = new Gson();

    /** The extracted, on-disk location of the bundled bridge script every call invokes. */
    private final Path scriptPath;

    /**
     * Extracts the bundled bridge script to a fixed location under {@link
     * Constraints#ICLOUD_IMPORT_SCRATCH_PATH} (once, not per-call) and verifies {@code python3}/{@code
     * pyicloud} are actually available - so a deployment missing either fails fast, here, rather than
     * on the first real import attempt.
     *
     * @throws IcloudBridgeException if the script couldn't be extracted, or {@code python3}/{@code pyicloud} isn't available
     */
    public PythonIcloudBridge() throws IcloudBridgeException {
        this.scriptPath = extractBridgeScript();
        requirePythonAndPyicloud();
    }

    private static Path extractBridgeScript() throws IcloudBridgeException {
        try {
            Files.createDirectories(Constraints.ICLOUD_IMPORT_SCRATCH_PATH);
            final Path destination = Constraints.ICLOUD_IMPORT_SCRATCH_PATH.resolve("icloud_bridge.py");
            try (InputStream resource = PythonIcloudBridge.class.getResourceAsStream(BRIDGE_SCRIPT_RESOURCE)) {
                if (resource == null) {
                    throw new IcloudBridgeException("@PythonIcloudBridge: bundled resource " + BRIDGE_SCRIPT_RESOURCE + " is missing");
                }
                Files.copy(resource, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } catch (final IOException e) {
            throw new IcloudBridgeException("@PythonIcloudBridge: failed to extract bridge script", e);
        }
    }

    private static void requirePythonAndPyicloud() throws IcloudBridgeException {
        try {
            final Process process = new ProcessBuilder("python3", "-c", "import pyicloud").start();
            process.getOutputStream().close();
            final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            final boolean finished = process.waitFor(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IcloudBridgeException("@PythonIcloudBridge: 'python3 -c \"import pyicloud\"' did not respond in time");
            }
            if (process.exitValue() != 0) {
                throw new IcloudBridgeException(
                        "@PythonIcloudBridge: python3/pyicloud not available - install Python 3 and run "
                                + "'pip install pyicloud' on this host. (" + stderr.strip() + ")");
            }
        } catch (final IOException e) {
            throw new IcloudBridgeException("@PythonIcloudBridge: failed to run python3 - is it installed and on PATH?", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IcloudBridgeException("@PythonIcloudBridge: interrupted while checking for python3/pyicloud", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public IcloudLoginResult login(final String appleId, final char[] password, final Path sessionDir)
            throws IcloudAuthenticationException, IcloudBridgeException {
        final JsonObject payload = new JsonObject();
        payload.addProperty("action", "login");
        payload.addProperty("apple_id", appleId);
        payload.addProperty("password", new String(password));
        payload.addProperty("session_dir", sessionDir.toString());
        final JsonObject response = this.runBridgeCommand(payload, METADATA_TIMEOUT_SECONDS);
        return new IcloudLoginResult(response.has("requires_two_factor") && response.get("requires_two_factor").getAsBoolean());
    }

    /** {@inheritDoc} */
    @Override
    public void confirmTwoFactorCode(final String appleId, final String code, final Path sessionDir)
            throws IcloudAuthenticationException, IcloudBridgeException {
        final JsonObject payload = new JsonObject();
        payload.addProperty("action", "confirm2fa");
        payload.addProperty("apple_id", appleId);
        payload.addProperty("code", code);
        payload.addProperty("session_dir", sessionDir.toString());
        this.runBridgeCommand(payload, METADATA_TIMEOUT_SECONDS);
    }

    /** {@inheritDoc} */
    @Override
    public List<IcloudTreeEntry> listTree(final String appleId, final Path sessionDir) throws IcloudBridgeException {
        final JsonObject payload = new JsonObject();
        payload.addProperty("action", "list_tree");
        payload.addProperty("apple_id", appleId);
        payload.addProperty("session_dir", sessionDir.toString());
        final JsonObject response = this.runBridgeCommandNoAuthFailure(payload, METADATA_TIMEOUT_SECONDS);
        final List<IcloudTreeEntry> entries = new ArrayList<>();
        response.getAsJsonArray("entries").forEach(element -> {
            final JsonObject entry = element.getAsJsonObject();
            entries.add(new IcloudTreeEntry(
                    entry.get("path").getAsString(),
                    entry.get("directory").getAsBoolean(),
                    entry.has("size_bytes") ? entry.get("size_bytes").getAsLong() : 0L
            ));
        });
        return entries;
    }

    /** {@inheritDoc} */
    @Override
    public void downloadFile(final String appleId, final Path sessionDir, final String remotePath, final Path destination)
            throws IcloudBridgeException {
        final JsonObject payload = new JsonObject();
        payload.addProperty("action", "download_file");
        payload.addProperty("apple_id", appleId);
        payload.addProperty("session_dir", sessionDir.toString());
        payload.addProperty("remote_path", remotePath);
        payload.addProperty("destination", destination.toString());
        this.runBridgeCommandNoAuthFailure(payload, DOWNLOAD_TIMEOUT_SECONDS);
    }

    /**
     * {@link #runBridgeCommand} for a call that never throws {@link IcloudAuthenticationException}
     * (list/download only run against an already-authenticated session) - narrows the checked
     * throws clause so those two callers don't need to declare/catch a case that can't happen.
     */
    private JsonObject runBridgeCommandNoAuthFailure(final JsonObject payload, final long timeoutSeconds) throws IcloudBridgeException {
        try {
            return this.runBridgeCommand(payload, timeoutSeconds);
        } catch (final IcloudAuthenticationException e) {
            throw new IcloudBridgeException("@PythonIcloudBridge: unexpected authentication failure from '" + payload.get("action").getAsString() + "'", e);
        }
    }

    /**
     * Runs {@link #scriptPath} with {@code payload} on its standard input, parses one JSON object
     * back from its standard output, and translates a {@code {"status": "error", ...}} response (or
     * a non-zero exit with no parseable response) into the matching exception.
     *
     * @param payload the JSON command to send
     * @param timeoutSeconds how long to wait before killing the process and giving up
     * @return the parsed {@code {"status": "ok", ...}} response
     * @throws IcloudAuthenticationException if the response reports {@code "error_type": "auth"}
     * @throws IcloudBridgeException for every other failure
     */
    private JsonObject runBridgeCommand(final JsonObject payload, final long timeoutSeconds)
            throws IcloudAuthenticationException, IcloudBridgeException {
        final String action = payload.get("action").getAsString();
        try {
            final Process process = new ProcessBuilder("python3", this.scriptPath.toString()).start();

            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(this.gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
            }

            final String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            final boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IcloudBridgeException("@PythonIcloudBridge: '" + action + "' did not respond within " + timeoutSeconds + "s");
            }

            final JsonObject response = parseResponse(stdout);
            if (response == null) {
                throw new IcloudBridgeException("@PythonIcloudBridge: '" + action + "' failed (exit " + process.exitValue() + "): "
                        + (stderr.isBlank() ? stdout : stderr).strip());
            }

            if ("error".equals(response.get("status").getAsString())) {
                final String message = response.has("message") ? response.get("message").getAsString() : "unknown error";
                final String errorType = response.has("error_type") ? response.get("error_type").getAsString() : "";
                if ("auth".equals(errorType)) {
                    throw new IcloudAuthenticationException(message);
                }
                throw new IcloudBridgeException("@PythonIcloudBridge: '" + action + "' failed: " + message);
            }

            return response;
        } catch (final IOException e) {
            throw new IcloudBridgeException("@PythonIcloudBridge: failed to run '" + action + "' - is python3 on PATH?", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IcloudBridgeException("@PythonIcloudBridge: interrupted while running '" + action + "'", e);
        }
    }

    /** @return the parsed JSON object from {@code stdout}, or {@code null} if it isn't valid/parseable JSON. */
    private JsonObject parseResponse(final String stdout) {
        try {
            return this.gson.fromJson(stdout.strip(), JsonObject.class);
        } catch (final RuntimeException e) {
            return null;
        }
    }

}

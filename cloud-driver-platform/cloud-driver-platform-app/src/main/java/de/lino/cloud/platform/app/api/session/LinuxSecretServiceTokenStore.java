package de.lino.cloud.platform.app.api.session;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Stores the session token via {@code secret-tool} (the CLI for libsecret/GNOME Keyring),
 * common on GNOME-based and many other Linux desktops. {@link #isAvailable()} lets {@link
 * TokenStoreFactory} detect a minimal/headless install where {@code secret-tool} isn't present
 * and fall back to {@link FileTokenStore} instead of failing outright.
 */
final class LinuxSecretServiceTokenStore implements TokenStore {

    private static final String SERVICE_ATTR_VALUE = "cloud-driver-desktop";
    private static final String ACCOUNT_ATTR_VALUE = "session";

    /** @return {@code true} if {@code secret-tool} is installed and reachable on {@code PATH} */
    boolean isAvailable() {
        try {
            final Process process = new ProcessBuilder("secret-tool", "--version").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (final IOException e) {
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void save(final String token) throws TokenStoreException {
        final ProcessResult result = run(
                token,
                "secret-tool", "store", "--label=Cloud Driver Desktop Session",
                "service", SERVICE_ATTR_VALUE, "account", ACCOUNT_ATTR_VALUE
        );
        if (result.exitCode() != 0) {
            throw new TokenStoreException("@LinuxSecretServiceTokenStore.save: 'secret-tool store' failed: " + result.stderr());
        }
    }

    @Override
    public Optional<String> load() throws TokenStoreException {
        final ProcessResult result = run(
                null,
                "secret-tool", "lookup", "service", SERVICE_ATTR_VALUE, "account", ACCOUNT_ATTR_VALUE
        );
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        final String token = result.stdout().strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    @Override
    public void clear() throws TokenStoreException {
        final ProcessResult result = run(
                null,
                "secret-tool", "clear", "service", SERVICE_ATTR_VALUE, "account", ACCOUNT_ATTR_VALUE
        );
        // Same reasoning as MacKeychainTokenStore#clear: a non-zero exit here almost always
        // just means "nothing was stored", which is already the desired end state.
        if (result.exitCode() != 0 && !result.stderr().isBlank() && result.stdout().isBlank()) {
            // secret-tool clear is largely silent either way; treat any output-free failure
            // as "already empty" rather than surfacing noise to the caller.
        }
    }

    private static ProcessResult run(final String stdinInput, final String... command) throws TokenStoreException {
        try {
            final Process process = new ProcessBuilder(command).start();

            if (stdinInput != null) {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(stdinInput.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }

            final String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            final boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TokenStoreException("@LinuxSecretServiceTokenStore: 'secret-tool' did not respond within 10s");
            }
            return new ProcessResult(process.exitValue(), stdout, stderr);
        } catch (final IOException e) {
            throw new TokenStoreException("@LinuxSecretServiceTokenStore: failed to run 'secret-tool' - is it installed?", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenStoreException("@LinuxSecretServiceTokenStore: interrupted while running 'secret-tool'", e);
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

}

package de.lino.cloud.platform.rest.api.session.linux;

import de.lino.cloud.platform.rest.api.session.TokenStore;
import de.lino.cloud.platform.rest.api.session.TokenStoreException;
import de.lino.cloud.platform.rest.api.session.TokenStoreFactory;
import de.lino.cloud.platform.rest.api.session.file.FileTokenStore;

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
public final class LinuxSecretServiceTokenStore implements TokenStore {

    /** The libsecret {@code service} attribute value this store's item is keyed under. */
    private static final String SERVICE_ATTR_VALUE = "cloud-driver-desktop";

    /** The libsecret {@code account} attribute value this store's item is keyed under - fixed, since this store only ever holds one session. */
    private static final String ACCOUNT_ATTR_VALUE = "session";

    /** @return {@code true} if {@code secret-tool} is installed and reachable on {@code PATH} */
    public boolean isAvailable() {
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

    /**
     * {@inheritDoc} Shells out to {@code secret-tool store}, passing {@code token} on the
     * child process's standard input rather than as a command-line argument (so it never shows
     * up in a process listing).
     *
     * @throws TokenStoreException if the {@code secret-tool} command fails or cannot be run
     */
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

    /**
     * {@inheritDoc} Shells out to {@code secret-tool lookup}; any non-zero exit (item not found
     * included) is treated as "nothing stored" rather than an error.
     *
     * @throws TokenStoreException if the {@code secret-tool} command cannot be run at all
     */
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

    /**
     * {@inheritDoc} Shells out to {@code secret-tool clear}.
     *
     * @throws TokenStoreException if the {@code secret-tool} command cannot be run at all
     */
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

    /**
     * Runs {@code command} to completion (up to a 10-second timeout, after which the process is
     * killed), optionally writing {@code stdinInput} to its standard input first, and captures
     * its exit code plus stdout/stderr.
     *
     * @param stdinInput text to write to the process's standard input before reading its output,
     *                    or {@code null} to close standard input immediately with nothing written
     * @param command    the command and its arguments, as passed to {@link ProcessBuilder}
     * @return the process's outcome
     * @throws TokenStoreException if the process can't be started, doesn't finish within 10s, or
     *                              this thread is interrupted while waiting for it
     */
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

    /**
     * The captured outcome of running an external process via {@link #run}.
     *
     * @param exitCode the process's exit code
     * @param stdout   everything the process wrote to standard output
     * @param stderr   everything the process wrote to standard error
     */
    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

}

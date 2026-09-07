package de.lino.cloud.platform.rest.api.session.mac;

import de.lino.cloud.platform.rest.api.session.TokenStore;
import de.lino.cloud.platform.rest.api.session.TokenStoreException;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Stores the session token in the current user's macOS login keychain via the {@code security}
 * command-line tool (part of every macOS install, no extra dependency needed). The token is
 * therefore encrypted at rest by the OS itself and only readable by this user's own login
 * session - never touches a plain file on disk.
 */
public final class MacKeychainTokenStore implements TokenStore {

    /**
     * The keychain item's service identifier. Deliberately still the literal string from a
     * deleted earlier JavaFX module (see {@code cloud-driver}'s own CLAUDE.md) rather than this
     * package's current name - renaming it would orphan any token a user already has stored
     * under the old identifier.
     */
    private static final String SERVICE = "de.lino.cloud.platform.desktop";

    /** The keychain item's account identifier - fixed, since this store only ever holds one session. */
    private static final String ACCOUNT = "session";

    /**
     * {@inheritDoc} Shells out to {@code security add-generic-password -U}, which updates the
     * item in place if one already exists under {@link #SERVICE}/{@link #ACCOUNT}.
     *
     * @throws TokenStoreException if the {@code security} command fails or cannot be run
     */
    @Override
    public void save(final String token) throws TokenStoreException {
        // -U: update the item in place if one already exists, instead of failing with "already exists".
        final ProcessResult result = run(
                "security", "add-generic-password",
                "-U", "-a", ACCOUNT, "-s", SERVICE, "-w", token
        );
        if (result.exitCode() != 0) {
            throw new TokenStoreException("@MacKeychainTokenStore.save: 'security add-generic-password' failed: " + result.stderr());
        }
    }

    /**
     * {@inheritDoc} Shells out to {@code security find-generic-password}; any non-zero exit
     * (item not found included) is treated as "nothing stored" rather than an error.
     *
     * @throws TokenStoreException if the {@code security} command cannot be run at all
     */
    @Override
    public Optional<String> load() throws TokenStoreException {
        final ProcessResult result = run(
                "security", "find-generic-password",
                "-a", ACCOUNT, "-s", SERVICE, "-w"
        );
        if (result.exitCode() != 0) {
            // Not found is the overwhelmingly common non-zero case (exit 44) - treat any
            // failure here as "nothing stored" rather than guessing at exit code meanings.
            return Optional.empty();
        }
        final String token = result.stdout().strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    /**
     * {@inheritDoc} Shells out to {@code security delete-generic-password}; a non-zero exit
     * whose stderr indicates the item simply wasn't found is treated as success (the desired end
     * state - "nothing stored" - is already true).
     *
     * @throws TokenStoreException if {@code security delete-generic-password} fails for any
     *                              other reason, or cannot be run at all
     */
    @Override
    public void clear() throws TokenStoreException {
        final ProcessResult result = run(
                "security", "delete-generic-password",
                "-a", ACCOUNT, "-s", SERVICE
        );
        // A non-zero exit here almost always just means "nothing was stored" - not an error
        // worth surfacing to the caller, who only wanted the end state "nothing stored" anyway.
        if (result.exitCode() != 0 && !result.stderr().toLowerCase().contains("could not be found")) {
            throw new TokenStoreException("@MacKeychainTokenStore.clear: 'security delete-generic-password' failed: " + result.stderr());
        }
    }

    /**
     * Runs {@code command} to completion (up to a 10-second timeout, after which the process is
     * killed) and captures its exit code plus stdout/stderr.
     *
     * @param command the command and its arguments, as passed to {@link ProcessBuilder}
     * @return the process's outcome
     * @throws TokenStoreException if the process can't be started, doesn't finish within 10s, or
     *                              this thread is interrupted while waiting for it
     */
    private static ProcessResult run(final String... command) throws TokenStoreException {
        try {
            final Process process = new ProcessBuilder(command).start();
            final String stdout = new String(process.getInputStream().readAllBytes());
            final String stderr = new String(process.getErrorStream().readAllBytes());
            final boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TokenStoreException("@MacKeychainTokenStore: 'security' did not respond within 10s");
            }
            return new ProcessResult(process.exitValue(), stdout, stderr);
        } catch (final IOException e) {
            throw new TokenStoreException("@MacKeychainTokenStore: failed to run 'security' - is it on PATH?", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenStoreException("@MacKeychainTokenStore: interrupted while running 'security'", e);
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

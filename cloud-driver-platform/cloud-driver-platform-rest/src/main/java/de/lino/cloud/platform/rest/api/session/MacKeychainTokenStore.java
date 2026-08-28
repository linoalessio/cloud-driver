package de.lino.cloud.platform.rest.api.session;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Stores the session token in the current user's macOS login keychain via the {@code security}
 * command-line tool (part of every macOS install, no extra dependency needed). The token is
 * therefore encrypted at rest by the OS itself and only readable by this user's own login
 * session - never touches a plain file on disk.
 */
final class MacKeychainTokenStore implements TokenStore {

    private static final String SERVICE = "de.lino.cloud.platform.app";
    private static final String ACCOUNT = "session";

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

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

}

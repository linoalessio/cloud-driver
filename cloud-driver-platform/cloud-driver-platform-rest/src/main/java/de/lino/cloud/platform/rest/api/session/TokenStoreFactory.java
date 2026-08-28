package de.lino.cloud.platform.rest.api.session;

/**
 * Picks the right {@link TokenStore} for the current OS. Callers should check {@link
 * Result#usedFallback()} once at startup and, if {@code true}, surface a warning to the user
 * (e.g. "no system keychain found - your session token will be stored less securely") rather
 * than silently degrading security.
 */
public final class TokenStoreFactory {

    private TokenStoreFactory() {
    }

    /** @return the best available {@link TokenStore} for the current OS, plus whether it's the plain-file fallback */
    public static Result create() {
        final String osName = System.getProperty("os.name", "").toLowerCase();

        if (osName.contains("mac") || osName.contains("darwin")) {
            return new Result(new MacKeychainTokenStore(), false);
        }

        if (osName.contains("win")) {
            return new Result(new WindowsDpapiTokenStore(), false);
        }

        // Linux/other Unix: prefer the desktop keyring if it's actually present, otherwise fall
        // back to a permission-restricted plain file rather than failing outright - a headless
        // or minimal Linux install with no libsecret is a real, expected case for a personal
        // cloud server's desktop client.
        final LinuxSecretServiceTokenStore secretService = new LinuxSecretServiceTokenStore();
        if (secretService.isAvailable()) {
            return new Result(secretService, false);
        }
        return new Result(new FileTokenStore(), true);
    }

    /** @param usedFallback {@code true} if {@link #store} is {@link FileTokenStore}, not a real OS keychain */
    public record Result(TokenStore store, boolean usedFallback) {
    }

}

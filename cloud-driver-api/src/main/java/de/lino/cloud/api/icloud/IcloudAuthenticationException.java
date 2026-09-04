package de.lino.cloud.api.icloud;

/**
 * Thrown by {@link IcloudBridge#login}/{@link IcloudBridge#confirmTwoFactorCode} when Apple itself
 * rejected the presented Apple ID/password or two-factor code - distinct from {@link
 * IcloudBridgeException}, which means the bridge process/protocol itself failed rather than Apple
 * turning down valid-looking credentials. Unchecked, mirroring {@link
 * de.lino.cloud.api.jwt.InvalidCredentialsException}'s shape - {@code
 * DefaultRestFactory#folderFailureOrPropagate} maps this to {@code 401 Unauthorized}.
 */
public final class IcloudAuthenticationException extends RuntimeException {

    /**
     * @param message a detail message - never echoes the rejected password/code itself
     */
    public IcloudAuthenticationException(final String message) {
        super(message);
    }

}

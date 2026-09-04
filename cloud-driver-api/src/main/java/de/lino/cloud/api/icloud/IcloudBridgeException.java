package de.lino.cloud.api.icloud;

/**
 * Thrown by {@link IcloudBridge} when the bridge itself failed - the Python process couldn't be
 * started, timed out, exited non-zero, or returned a response the caller couldn't parse. Checked,
 * like {@link de.lino.cloud.api.mail.EmailDeliveryException} - a caller has a real decision to make
 * (fail the import job, or in the case of a missing {@code python3}/{@code pyicloud} at startup,
 * disable the feature entirely) rather than having this swallowed silently. Distinct from {@link
 * IcloudAuthenticationException}, which means the bridge itself worked fine but Apple rejected the
 * credentials/code presented to it.
 */
public final class IcloudBridgeException extends Exception {

    /**
     * @param message a detail message describing what failed
     */
    public IcloudBridgeException(final String message) {
        super(message);
    }

    /**
     * @param message a detail message describing what failed
     * @param cause the underlying failure (e.g. an {@link java.io.IOException} starting the process)
     */
    public IcloudBridgeException(final String message, final Throwable cause) {
        super(message, cause);
    }

}

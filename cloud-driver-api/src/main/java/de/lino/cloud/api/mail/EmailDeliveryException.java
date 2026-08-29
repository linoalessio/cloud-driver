package de.lino.cloud.api.mail;

/**
 * Thrown by {@link EmailSender#send} when an e-mail could not be delivered (e.g. the SMTP
 * server rejected the connection or the credentials). Checked, like {@code
 * DatabaseClientException} - a caller (e.g. {@code AuthService#register}) has a real decision
 * to make when sending fails, so this isn't swallowed silently.
 */
public final class EmailDeliveryException extends Exception {

    /**
     * @param message a detail message describing what failed
     * @param cause the underlying failure from the mail library
     */
    public EmailDeliveryException(final String message, final Throwable cause) {
        super(message, cause);
    }

}

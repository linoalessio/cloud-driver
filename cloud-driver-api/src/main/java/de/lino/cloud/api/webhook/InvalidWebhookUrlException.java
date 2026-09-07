package de.lino.cloud.api.webhook;

/**
 * Thrown by {@link WebhookService#registerWebhook} when a caller-supplied URL is rejected -
 * either it isn't a well-formed {@code https://} URL at all, or it resolves to a private/loopback/
 * link-local address (a best-effort SSRF mitigation - see {@code WebhookService}'s own Javadoc for
 * why this is "good enough, not a hard perimeter", the same trade-off this codebase already
 * accepts for {@code InternetConnectivityChecker}/the rate limiter's IP-based identity). Translated
 * to a {@code 400} by {@code DefaultRestFactory}.
 */
public final class InvalidWebhookUrlException extends RuntimeException {

    public InvalidWebhookUrlException(final String message) {
        super(message);
    }

}

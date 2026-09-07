package de.lino.cloud.api.webhook;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The one-time, secret-carrying response to {@link WebhookService#registerWebhook} - the receiving
 * account must record {@link #secret()} now, since no later call ever redisplays it, the same
 * "raw value handed back exactly once at creation" precedent {@code ApiKey} already established.
 * Unlike {@code ApiKey}, this secret is never independently verified by anything in this codebase
 * - it exists purely for the *receiver* to verify the {@code X-Webhook-Signature} header {@link
 * WebhookService} signs every delivery with.
 *
 * @param id this subscription's id
 * @param url the {@code https://} URL events are delivered to
 * @param eventTypes which {@link WebhookEventType}s this subscription is registered for
 * @param secret the HMAC-SHA256 signing secret, base64url-encoded - shown once, never again
 * @param createdAtEpochMillis when this subscription was created, as epoch millis
 */
public record WebhookSubscriptionCreated(@NotNull String id, @NotNull String url, @NotNull Set<WebhookEventType> eventTypes,
                                          @NotNull String secret, long createdAtEpochMillis) {
}

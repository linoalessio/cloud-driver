package de.lino.cloud.api.webhook;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * One registered webhook, without its signing secret - the shape {@link
 * WebhookService#listWebhooks} returns. See {@link WebhookSubscriptionCreated} for the one-time
 * shape that does carry the secret, returned only from {@link WebhookService#registerWebhook}.
 *
 * @param id this subscription's id
 * @param url the {@code https://} URL events are delivered to
 * @param eventTypes which {@link WebhookEventType}s this subscription is registered for
 * @param createdAtEpochMillis when this subscription was created, as epoch millis
 */
public record WebhookSubscriptionSummary(@NotNull String id, @NotNull String url,
                                          @NotNull Set<WebhookEventType> eventTypes, long createdAtEpochMillis) {
}

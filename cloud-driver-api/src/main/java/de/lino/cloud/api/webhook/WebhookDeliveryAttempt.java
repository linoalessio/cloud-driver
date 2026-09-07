package de.lino.cloud.api.webhook;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One recorded delivery attempt for a webhook subscription, as returned by {@link
 * WebhookService#listRecentDeliveries} - purely in-memory/informational (see that method's own
 * Javadoc), backing a "view delivery status" UI.
 *
 * @param webhookId the subscription this attempt was made for
 * @param eventType the event that was being delivered
 * @param targetId the {@code CloudUserService}-supplied id the event concerns (a {@code
 *     StoredFile#fileId()})
 * @param attemptedAtEpochMillis when this attempt was made, as epoch millis
 * @param attemptNumber 1 for the first attempt, 2/3 for a retry
 * @param succeeded whether the receiving endpoint returned a 2xx status
 * @param responseStatusCode the HTTP status code received, or {@code null} if the request itself failed (timeout, connection refused, DNS failure, etc.)
 */
public record WebhookDeliveryAttempt(@NotNull String webhookId, @NotNull WebhookEventType eventType, @NotNull String targetId,
                                      long attemptedAtEpochMillis, int attemptNumber, boolean succeeded,
                                      @Nullable Integer responseStatusCode) {
}

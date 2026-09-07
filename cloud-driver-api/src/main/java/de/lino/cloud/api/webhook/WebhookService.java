package de.lino.cloud.api.webhook;

import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.file.StoredFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Lets an account subscribe an {@code https://} URL to file events, delivered as an HMAC-signed
 * HTTP POST - section 8 of {@code architecture/MICRO.md}, reached via {@link
 * IServiceContainer#getWebhookService()} - {@code null} until {@code
 * cloud-driver-extensions-webhooks}'s {@code CloudWebhooksExtension} has published one (not
 * started, or this deployment doesn't run that extension at all), the same "may not exist yet"
 * contract every other addon-shaped {@link IServiceContainer} facet already carries.
 *
 * <p><b>{@link #dispatchEvent} is called synchronously, directly from {@code
 * de.lino.cloud.auth.CloudUserService}'s own upload/delete/share methods</b> - the same "reach the
 * optional service directly from the call site, no-op if unpublished, never throw" shape {@code
 * FileVersioningService}/{@code SearchIndexService} already established (section 5's own
 * Javadoc explains why the async {@code FileChangeListener} mechanism isn't used here either: a
 * soft-delete is an {@code UPDATE} of {@code StoredFileOwnership}, not {@code StoredFile}, and a
 * share grant touches neither table that mechanism watches at all). {@link #dispatchEvent} itself
 * must be cheap and must never block the calling request thread on network I/O - the real HTTP
 * delivery (with retry/backoff) always happens on the implementation's own background dispatch
 * worker, never synchronously.
 *
 * <p><b>Best-effort SSRF mitigation, not a hard perimeter</b> - {@link #registerWebhook} and every
 * delivery attempt both reject a URL that isn't {@code https://} or that resolves to a private/
 * loopback/link-local address, the same "good enough, not bulletproof" trade-off this codebase
 * already accepts for {@code InternetConnectivityChecker}. Re-checked at delivery time (not just
 * registration) specifically to narrow the DNS-rebinding window (a hostname resolving to a public
 * address at registration time, then to a private one by the time delivery actually runs).
 */
public interface WebhookService {

    /**
     * Registers a new webhook subscription for {@code authUserId}, generating a fresh HMAC-SHA256
     * signing secret.
     *
     * @param authUserId the account this subscription belongs to
     * @param url the {@code https://} URL to deliver events to
     * @param eventTypes which {@link WebhookEventType}s to subscribe to - must be non-empty
     * @return the newly-created subscription, including its signing secret (shown only this once - see {@link WebhookSubscriptionCreated}'s own Javadoc)
     * @throws InvalidWebhookUrlException if {@code url} isn't a well-formed {@code https://} URL,
     *     or resolves to a private/loopback/link-local address
     * @throws IllegalArgumentException if {@code eventTypes} is empty
     */
    @NotNull
    WebhookSubscriptionCreated registerWebhook(@NotNull String authUserId, @NotNull String url, @NotNull Set<WebhookEventType> eventTypes);

    /**
     * Revokes a previously-registered webhook - owner-only. Idempotent: a no-op if {@code
     * webhookId} doesn't exist, or exists but doesn't belong to {@code authUserId}.
     *
     * @param authUserId the account revoking the subscription
     * @param webhookId the subscription to revoke
     */
    void revokeWebhook(@NotNull String authUserId, @NotNull String webhookId);

    /**
     * Lists every webhook {@code authUserId} has registered, without their signing secrets.
     *
     * @param authUserId the account whose subscriptions to list
     * @return every currently-registered {@link WebhookSubscriptionSummary} for {@code authUserId}
     */
    @NotNull
    List<WebhookSubscriptionSummary> listWebhooks(@NotNull String authUserId);

    /**
     * Lists the most recent delivery attempts across all of {@code authUserId}'s webhooks
     * (implementations may bound how many are retained - purely in-memory/informational, not a
     * durable audit trail; lost on restart, the same trade-off {@code InMemorySearchIndexService}
     * already accepts for the same reason).
     *
     * @param authUserId the account whose delivery history to list
     * @return the most recent {@link WebhookDeliveryAttempt}s, newest first
     */
    @NotNull
    List<WebhookDeliveryAttempt> listRecentDeliveries(@NotNull String authUserId);

    /**
     * Notifies every one of {@code authUserId}'s webhooks subscribed to {@code eventType} - called
     * synchronously from {@code CloudUserService}, must return quickly and never throw (a
     * misbehaving/overloaded dispatcher must never block or fail the real file operation it's
     * reporting on). Real HTTP delivery (signing, the request itself, retry/backoff) always
     * happens asynchronously on this service's own background worker.
     *
     * @param authUserId the account whose webhooks to notify
     * @param eventType the event that occurred
     * @param targetId the {@link StoredFile#fileId()} the event concerns
     */
    void dispatchEvent(@NotNull String authUserId, @NotNull WebhookEventType eventType, @NotNull String targetId);

}

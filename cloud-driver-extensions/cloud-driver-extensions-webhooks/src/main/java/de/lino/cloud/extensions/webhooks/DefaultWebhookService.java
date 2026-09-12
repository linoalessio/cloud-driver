package de.lino.cloud.extensions.webhooks;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.webhook.InvalidWebhookUrlException;
import de.lino.cloud.api.webhook.WebhookDeliveryAttempt;
import de.lino.cloud.api.webhook.WebhookEventType;
import de.lino.cloud.api.webhook.WebhookService;
import de.lino.cloud.api.webhook.WebhookSubscriptionCreated;
import de.lino.cloud.api.webhook.WebhookSubscriptionSummary;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one {@link WebhookService} implementation. Delivery runs entirely on {@link
 * #dispatchExecutor}/{@link #retryScheduler} - a fixed, small, daemon-threaded pool the same shape
 * {@code CloudThumbnailsExtension}'s own generation worker already established, never the calling
 * request thread ({@link #dispatchEvent}) and never the Postgres notification thread this
 * deployment might also be running.
 */
public final class DefaultWebhookService implements WebhookService {

    /** How many delivery attempts are retried before giving up - the first attempt plus two retries. */
    private static final int MAX_ATTEMPTS = 3;

    /** Delay before each retry, indexed by (attemptNumber - 1) for the retry that follows it - a fixed backoff schedule, not exponential, matching this codebase's own "simple, not maximally clever" precedent ({@code InternetConnectivityChecker}, {@code LiveUpdateClient}'s reconnect delay). */
    private static final Duration[] RETRY_DELAYS = {Duration.ofSeconds(2), Duration.ofSeconds(10)};

    /** Per-request timeout - short enough that a slow/malicious receiver can't tie up a dispatch worker thread for long. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final DataFactory dataFactory;
    private final Logger logger;
    private final HttpClient httpClient;
    private final ExecutorService dispatchExecutor;
    private final ScheduledExecutorService retryScheduler;

    /**
     * The bounded delivery-attempt history {@link #listRecentDeliveries} reads from - still an
     * in-memory ring buffer on the read path, but written through to Redis when this deployment
     * has one, so it survives a restart. See {@link WebhookDeliveryLog}'s own Javadoc.
     */
    private final WebhookDeliveryLog deliveryLog;

    /** Default {@link #dispatchExecutor} size, used when {@code webhook-dispatch-pool-size} isn't configured - the fixed value this pool always had. */
    public static final int DEFAULT_DISPATCH_POOL_SIZE = 4;

    /**
     * {@link #dispatchExecutor}'s work queue, held separately so {@link
     * #pendingDispatchQueueDepth()} can report its depth - the observable half of the pool's
     * tunability: a persistently growing depth tells an operator the
     * configured pool size no longer keeps up with this deployment's event rate.
     */
    private final LinkedBlockingQueue<Runnable> dispatchQueue;

    /** Same as {@link #DefaultWebhookService(DataFactory, Logger, int)} with {@link #DEFAULT_DISPATCH_POOL_SIZE}. */
    public DefaultWebhookService(@NotNull final DataFactory dataFactory, @NotNull final Logger logger) {
        this(dataFactory, logger, DEFAULT_DISPATCH_POOL_SIZE);
    }

    /**
     * @param dataFactory the factory {@link WebhookSubscription}s are persisted through
     * @param logger where delivery failures/retries are reported
     * @param dispatchPoolSize how many first-attempt deliveries may run concurrently ({@code
     *     webhook-dispatch-pool-size} in {@code configuration.json}); values below {@code 1} are
     *     clamped to {@code 1} rather than rejected - a misconfigured size shouldn't stop the
     *     whole extension from starting
     */
    public DefaultWebhookService(@NotNull final DataFactory dataFactory, @NotNull final Logger logger, final int dispatchPoolSize) {
        this.dataFactory = dataFactory;
        this.logger = logger;
        this.deliveryLog = new WebhookDeliveryLog(logger);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        final int poolSize = Math.max(1, dispatchPoolSize);
        this.dispatchQueue = new LinkedBlockingQueue<>();
        // Explicit ThreadPoolExecutor rather than Executors.newFixedThreadPool: identical pool
        // semantics, but this class keeps a reference to the work queue for
        // pendingDispatchQueueDepth() - the factory method hides it.
        this.dispatchExecutor = new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                this.dispatchQueue, DefaultWebhookService::newDaemonThread);
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(DefaultWebhookService::newDaemonThread);
    }

    /**
     * How many first-attempt deliveries are currently waiting for a free {@link
     * #dispatchExecutor} thread - see {@link de.lino.cloud.api.webhook.WebhookService#pendingDispatchQueueDepth()}.
     */
    @Override
    public int pendingDispatchQueueDepth() {
        return this.dispatchQueue.size();
    }

    private static Thread newDaemonThread(final Runnable runnable) {
        final Thread thread = new Thread(runnable, "webhook-dispatch");
        thread.setDaemon(true);
        return thread;
    }

    /** Shuts {@link #dispatchExecutor}/{@link #retryScheduler} down - called by {@code CloudWebhooksExtension#onEnding}/{@code #onException}. */
    public void shutdown() {
        this.dispatchExecutor.shutdown();
        this.retryScheduler.shutdown();
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public WebhookSubscriptionCreated registerWebhook(@NotNull final String authUserId, @NotNull final String url,
                                                        @NotNull final Set<WebhookEventType> eventTypes) {
        if (eventTypes.isEmpty()) {
            throw new IllegalArgumentException("@DefaultWebhookService.registerWebhook: eventTypes cannot be empty");
        }
        validateUrl(url);

        final WebhookSubscription subscription = new WebhookSubscription(authUserId, url, eventTypes);
        try {
            this.dataFactory.register(subscription);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@DefaultWebhookService.registerWebhook: failed to persist subscription for " + authUserId, e);
        }
        return new WebhookSubscriptionCreated(subscription.getId(), subscription.getUrl(), subscription.getEventTypes(),
                subscription.getSecret(), subscription.getCreatedAtEpochMillis());
    }

    /** {@inheritDoc} */
    @Override
    public void revokeWebhook(@NotNull final String authUserId, @NotNull final String webhookId) {
        try {
            final WebhookSubscription subscription = this.dataFactory.findById(webhookId, WebhookSubscription.class).orElse(null);
            if (subscription == null || !subscription.getOwnerAuthUserId().equals(authUserId)) {
                return;
            }
            this.dataFactory.delete(webhookId, WebhookSubscription.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@DefaultWebhookService.revokeWebhook: failed to revoke " + webhookId + " for " + authUserId, e);
        }
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public List<WebhookSubscriptionSummary> listWebhooks(@NotNull final String authUserId) {
        return ownedSubscriptions(authUserId).stream()
                .map(subscription -> new WebhookSubscriptionSummary(
                        subscription.getId(), subscription.getUrl(), subscription.getEventTypes(), subscription.getCreatedAtEpochMillis()))
                .toList();
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public List<WebhookDeliveryAttempt> listRecentDeliveries(@NotNull final String authUserId) {
        final Set<String> ownedWebhookIds = ownedSubscriptions(authUserId).stream()
                .map(WebhookSubscription::getId)
                .collect(java.util.stream.Collectors.toSet());
        return this.deliveryLog.snapshot().stream()
                .filter(attempt -> ownedWebhookIds.contains(attempt.webhookId()))
                .toList();
    }

    private List<WebhookSubscription> ownedSubscriptions(final String authUserId) {
        try {
            return this.dataFactory.getEntitiesByIndex(WebhookSubscription.class, WebhookSubscription.INDEX_OWNER_AUTH_USER_ID, authUserId).stream()
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@DefaultWebhookService.ownedSubscriptions: failed to list subscriptions for " + authUserId, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void dispatchEvent(@NotNull final String authUserId, @NotNull final WebhookEventType eventType, @NotNull final String targetId) {
        try {
            final List<WebhookSubscription> matching = ownedSubscriptions(authUserId).stream()
                    .filter(subscription -> subscription.getEventTypes().contains(eventType))
                    .toList();
            for (final WebhookSubscription subscription : matching) {
                this.dispatchExecutor.execute(() -> attemptDelivery(subscription, eventType, targetId, 1));
            }
        } catch (final RuntimeException ignored) {
            // Best-effort only - see this method's own Javadoc.
        }
    }

    /**
     * Builds and sends one delivery attempt, recording the outcome (see {@link #recordAttempt}) and
     * scheduling a retry via {@link #retryScheduler} (see {@link #RETRY_DELAYS}) if it failed and
     * {@code attemptNumber} hasn't yet reached {@link #MAX_ATTEMPTS}. Runs on {@link
     * #dispatchExecutor} (the first attempt) or {@link #retryScheduler} (every retry) - never the
     * thread that called {@link #dispatchEvent}.
     */
    private void attemptDelivery(final WebhookSubscription subscription, final WebhookEventType eventType,
                                  final String targetId, final int attemptNumber) {
        // Re-validated at delivery time, not just at registration - narrows the DNS-rebinding
        // window where a hostname resolved to a public address at registration but a private one
        // by the time this actually runs. A failure here is recorded as a failed attempt, not
        // retried (retrying wouldn't change a URL that's now permanently invalid/malicious).
        try {
            validateUrl(subscription.getUrl());
        } catch (final InvalidWebhookUrlException stillInvalid) {
            recordAttempt(subscription.getId(), eventType, targetId, attemptNumber, false, null);
            return;
        }

        final long timestamp = System.currentTimeMillis();
        final String body = "{\"eventType\":\"" + eventType + "\",\"targetId\":\"" + targetId + "\",\"timestampEpochMillis\":" + timestamp + "}";
        final String signature = hmacSha256Hex(subscription.getSecret(), body);

        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(subscription.getUrl()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Signature", signature)
                    .header("X-Webhook-Event", eventType.name())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (final RuntimeException malformed) {
            recordAttempt(subscription.getId(), eventType, targetId, attemptNumber, false, null);
            return;
        }

        final boolean succeeded;
        Integer statusCode = null;
        try {
            final HttpResponse<Void> response = this.httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            statusCode = response.statusCode();
            succeeded = statusCode >= 200 && statusCode < 300;
        } catch (final Exception deliveryFailed) {
            recordAttempt(subscription.getId(), eventType, targetId, attemptNumber, false, null);
            scheduleRetryIfEligible(subscription, eventType, targetId, attemptNumber);
            return;
        }

        recordAttempt(subscription.getId(), eventType, targetId, attemptNumber, succeeded, statusCode);
        if (!succeeded) {
            scheduleRetryIfEligible(subscription, eventType, targetId, attemptNumber);
        }
    }

    private void scheduleRetryIfEligible(final WebhookSubscription subscription, final WebhookEventType eventType,
                                          final String targetId, final int attemptNumber) {
        if (attemptNumber >= MAX_ATTEMPTS) {
            return;
        }
        final Duration delay = RETRY_DELAYS[attemptNumber - 1];
        try {
            this.retryScheduler.schedule(
                    () -> attemptDelivery(subscription, eventType, targetId, attemptNumber + 1),
                    delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final RuntimeException schedulingFailed) {
            this.logger.log(Level.WARNING, "@DefaultWebhookService: failed to schedule retry for webhook " + subscription.getId(), schedulingFailed);
        }
    }

    /** Appends one attempt to {@link #deliveryLog}, which owns both the bound and the write-through to Redis. */
    private void recordAttempt(final String webhookId, final WebhookEventType eventType, final String targetId,
                                final int attemptNumber, final boolean succeeded, final Integer statusCode) {
        this.deliveryLog.record(new WebhookDeliveryAttempt(
                webhookId, eventType, targetId, System.currentTimeMillis(), attemptNumber, succeeded, statusCode));
    }

    /** @return whether {@link #deliveryLog} is currently persisting attempts - read by {@code CloudWebhooksExtension} purely to say so at startup */
    boolean isDeliveryHistoryDurable() {
        return this.deliveryLog.isDurable();
    }

    /** Computes the lowercase-hex HMAC-SHA256 of {@code body} under {@code secret}. */
    private static String hmacSha256Hex(final String secret, final String body) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            throw new IllegalStateException("@DefaultWebhookService.hmacSha256Hex: HmacSHA256 unavailable", e);
        }
    }

    /**
     * Rejects a URL that isn't a well-formed {@code https://} URL, or that resolves to a private/
     * loopback/link-local/multicast address - see {@link WebhookService}'s own Javadoc for the
     * "best-effort, not a hard perimeter" framing.
     *
     * @throws InvalidWebhookUrlException if {@code url} fails either check
     */
    private static void validateUrl(final String url) {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (final URISyntaxException e) {
            throw new InvalidWebhookUrlException("Malformed URL: " + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new InvalidWebhookUrlException("Webhook URL must use https://");
        }
        final String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidWebhookUrlException("URL must have a host: " + url);
        }

        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (final UnknownHostException e) {
            throw new InvalidWebhookUrlException("Could not resolve host: " + host);
        }
        for (final InetAddress address : addresses) {
            if (isPrivateOrReserved(address)) {
                throw new InvalidWebhookUrlException(
                        "URL resolves to a private/reserved address (" + address.getHostAddress() + "), not allowed");
            }
        }
    }

    /**
     * @return {@code true} if {@code address} is loopback/link-local/site-local(RFC1918)/multicast/
     * any-local, or an IPv6 unique-local address ({@code fc00::/7} - not covered by {@link
     * InetAddress#isSiteLocalAddress()}, which only recognizes the deprecated {@code fec0::/10}
     * range for IPv6, so this is checked explicitly).
     */
    private static boolean isPrivateOrReserved(final InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        final byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

}

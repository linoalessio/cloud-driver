package de.lino.cloud.extensions.webhooks;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.redis.RedisSupport;
import de.lino.cloud.api.webhook.WebhookDeliveryAttempt;
import de.lino.cloud.api.webhook.WebhookEventType;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The bounded, newest-last delivery-attempt log backing {@code
 * DefaultWebhookService#listRecentDeliveries} - an in-memory ring buffer that additionally writes
 * through to Redis whenever this deployment has a reachable one, so a restart no longer silently
 * empties the "did my webhook actually fire?" view an operator opens precisely <em>after</em>
 * something went wrong (which, on a process that auto-restarts on crash, is exactly when the
 * history was most likely to have just been discarded).
 *
 * <p><b>Reads never touch Redis.</b> {@link #snapshot()} serves from {@link #recentDeliveries}, and
 * that deque is the authority at runtime - Redis only ever receives writes and repopulates the
 * deque once, at construction. So this costs nothing on the read path relative to the purely
 * in-memory version it replaced, and the whole feature degrades to exactly that version when Redis
 * is absent.
 *
 * <p><b>Why this is a safe thing to persist</b>, unlike most state in this codebase: a delivery
 * attempt records only a webhook id, an event type, a {@code StoredFile} id, a timestamp, and an
 * HTTP status - no file content, no filename, no e-mail address, no token. Redis holds it
 * unencrypted (see {@link RedisSupport}'s own Javadoc for why nothing sensitive may go there), and
 * that is acceptable here specifically because none of these fields is sensitive on its own.
 *
 * <p>Every Redis interaction below is best-effort and individually guarded: a Redis failure
 * degrades this log to in-memory-only for the rest of the process' life rather than failing a
 * delivery, which must never be blocked by its own bookkeeping.
 */
final class WebhookDeliveryLog {

    /** The {@link RedisSupport#section(String)} key prefix every persisted attempt is stored under. */
    private static final String SECTION_NAME = "cloud-driver-webhook-deliveries";

    /**
     * How many attempts are retained, across every account this process serves - unchanged from the
     * purely in-memory version this replaced. Also the hard bound that keeps this log's Redis key
     * count small enough to satisfy {@link RedisSupport}'s own "keep the keyspace small" contract,
     * which is what makes a per-attempt Redis key (rather than one big serialized blob rewritten on
     * every attempt) the right layout here.
     */
    private static final int MAX_RETAINED_DELIVERIES = 500;

    private final Logger logger;

    /** The authority for every read; also mirrored to {@link #section} when one is available. */
    private final Deque<WebhookDeliveryAttempt> recentDeliveries = new ConcurrentLinkedDeque<>();

    /** The Redis-backed section attempts are written through to, or {@code null} for in-memory-only operation. */
    @Nullable
    private volatile DatabaseSection section;

    /**
     * Resolves Redis (if any) and, when present, repopulates {@link #recentDeliveries} from it so
     * history carried over from previous runs is visible immediately.
     *
     * @param logger where a Redis failure is reported before falling back to in-memory-only
     */
    WebhookDeliveryLog(@NotNull final Logger logger) {
        this.logger = logger;
        this.section = resolveSection(logger);
        if (this.section != null) this.restoreFromSection();
    }

    /** @return whether attempts are currently being persisted, purely so the extension can say so at startup */
    boolean isDurable() {
        return this.section != null;
    }

    /**
     * Appends one attempt, trimming the oldest past {@link #MAX_RETAINED_DELIVERIES} from both the
     * in-memory deque and (if durable) Redis, so the two never drift in size.
     */
    void record(@NotNull final WebhookDeliveryAttempt attempt) {

        this.recentDeliveries.addLast(attempt);
        while (this.recentDeliveries.size() > MAX_RETAINED_DELIVERIES) {
            this.recentDeliveries.pollFirst();
        }

        this.write(attempt.attemptedAtEpochMillis() + "-" + UUID.randomUUID(), attempt);
        this.trimSection();

    }

    /** @return every retained attempt, oldest first - the exact shape the in-memory-only version returned */
    @NotNull
    List<WebhookDeliveryAttempt> snapshot() {
        return List.copyOf(this.recentDeliveries);
    }

    /**
     * Resolves the Redis-backed section, or {@code null} if this deployment has no reachable Redis
     * (the ordinary case for a deployment that never configured one) or the section itself could
     * not be created.
     */
    @Nullable
    private static DatabaseSection resolveSection(final Logger logger) {
        try {
            final RedisSupport redisSupport = CloudDriver.getInstance().getFactoryContainer().getRedisSupport();
            return redisSupport == null ? null : redisSupport.section(SECTION_NAME);
        } catch (final Exception redisUnavailable) {
            logger.log(Level.WARNING, "@WebhookDeliveryLog: could not open the Redis-backed delivery log - "
                    + "delivery history stays in-process and is lost on restart.", redisUnavailable);
            return null;
        }
    }

    /**
     * Loads every persisted attempt into {@link #recentDeliveries}, oldest first, dropping anything
     * past {@link #MAX_RETAINED_DELIVERIES} (a previous run may have been configured with a larger
     * bound, and an entry whose JSON no longer deserializes is skipped rather than aborting the
     * whole restore).
     */
    private void restoreFromSection() {

        final DatabaseSection currentSection = this.section;
        if (currentSection == null) return;

        try {

            final List<WebhookDeliveryAttempt> restored = new ArrayList<>();
            for (final DatabaseEntry entry : currentSection.getEntries()) {
                final WebhookDeliveryAttempt attempt = deserialize(entry);
                if (attempt != null) restored.add(attempt);
            }

            restored.sort(Comparator.comparingLong(WebhookDeliveryAttempt::attemptedAtEpochMillis));
            restored.stream()
                    .skip(Math.max(0, restored.size() - MAX_RETAINED_DELIVERIES))
                    .forEach(this.recentDeliveries::addLast);

        } catch (final Exception restoreFailed) {
            this.logger.log(Level.WARNING, "@WebhookDeliveryLog: could not restore persisted delivery history - "
                    + "starting empty; new attempts will still be persisted.", restoreFailed);
        }

    }

    /**
     * Persists one attempt, disabling durability for the rest of this process' life on failure -
     * a Redis that has started failing writes will keep failing them, and retrying on every single
     * delivery would turn one broken dependency into a per-delivery latency cost.
     */
    private void write(final String entryId, final WebhookDeliveryAttempt attempt) {

        final DatabaseSection currentSection = this.section;
        if (currentSection == null) return;

        try {
            currentSection.insert(new DatabaseEntry(entryId, new JsonDocument().append("data", serialize(attempt))));
        } catch (final Exception writeFailed) {
            this.section = null;
            this.logger.log(Level.WARNING, "@WebhookDeliveryLog: failed to persist a delivery attempt - "
                    + "delivery history falls back to in-process only for the rest of this run.", writeFailed);
        }

    }

    /**
     * Deletes oldest-first until the persisted history is back within {@link
     * #MAX_RETAINED_DELIVERIES}, keeping Redis bounded by the same rule the in-memory deque
     * already enforces.
     *
     * <p><b>Trims down to the bound rather than deleting exactly one per recorded attempt</b>,
     * which is what makes this actually converge: since {@link #record} adds one entry and would
     * remove one, a section that ever ended up <em>above</em> the bound - a previous run built with
     * a larger one, or a single earlier delete that failed and was logged - would otherwise stay
     * permanently over it, one-in-one-out forever, never shrinking back. In steady state this still
     * deletes exactly one entry, so the normal path costs the same either way.
     *
     * <p>The oldest entry is resolved by scanning the section's own in-process entry mirror (a
     * local map read, not a Redis round trip) rather than by tracking evicted ids alongside the
     * deque, so an entry restored from a previous run - which this process never saw written - is
     * still evictable.
     */
    private void trimSection() {

        final DatabaseSection currentSection = this.section;
        if (currentSection == null) return;

        try {

            final List<String> ids = currentSection.getEntries().stream()
                    .map(DatabaseEntry::getId)
                    .sorted(Comparator.comparingLong(WebhookDeliveryLog::timestampOf).thenComparing(Comparator.naturalOrder()))
                    .toList();

            for (int index = 0; index < ids.size() - MAX_RETAINED_DELIVERIES; index++) {
                currentSection.delete(ids.get(index));
            }

        } catch (final Exception deleteFailed) {
            this.logger.log(Level.WARNING, "@WebhookDeliveryLog: failed to trim the persisted delivery history - "
                    + "it may temporarily exceed " + MAX_RETAINED_DELIVERIES + " entries; the next recorded "
                    + "attempt trims it back down.", deleteFailed);
        }

    }

    /**
     * Reads the timestamp back out of an entry id ({@code "<epochMillis>-<uuid>"}), falling back to
     * {@link Long#MAX_VALUE} for an id that doesn't parse - so a malformed id sorts newest and is
     * never picked as "the oldest" to evict, keeping a real attempt from being deleted in its place.
     */
    private static long timestampOf(final String entryId) {
        final int separator = entryId.indexOf('-');
        if (separator <= 0) return Long.MAX_VALUE;
        try {
            return Long.parseLong(entryId.substring(0, separator));
        } catch (final NumberFormatException malformed) {
            return Long.MAX_VALUE;
        }
    }

    /** Field-for-field JSON, deliberately hand-written rather than reflective - see {@link #deserialize} for why the two must stay symmetric. */
    private static JsonDocument serialize(final WebhookDeliveryAttempt attempt) {
        final JsonDocument document = new JsonDocument()
                .append("webhookId", attempt.webhookId())
                .append("eventType", attempt.eventType().name())
                .append("targetId", attempt.targetId())
                .append("attemptedAtEpochMillis", attempt.attemptedAtEpochMillis())
                .append("attemptNumber", attempt.attemptNumber())
                .append("succeeded", attempt.succeeded());
        if (attempt.responseStatusCode() != null) document.append("responseStatusCode", attempt.responseStatusCode());
        return document;
    }

    /**
     * Rebuilds an attempt from its persisted JSON, or {@code null} if the entry is unreadable -
     * including a {@code eventType} this build no longer knows, which is exactly what a
     * newer-then-rolled-back deployment leaves behind and must not turn into a restore-wide failure.
     * {@code responseStatusCode} is genuinely optional (absent when the request itself failed
     * before any status was received), so its absence is normal rather than a parse error.
     */
    @Nullable
    private static WebhookDeliveryAttempt deserialize(final DatabaseEntry entry) {

        final JsonDocument document = entry.getMetaData();
        if (document == null) return null;

        try {
            return new WebhookDeliveryAttempt(
                    document.getString("webhookId"),
                    WebhookEventType.valueOf(document.getString("eventType")),
                    document.getString("targetId"),
                    document.getLong("attemptedAtEpochMillis"),
                    document.getInteger("attemptNumber"),
                    document.getBoolean("succeeded"),
                    document.contains("responseStatusCode") ? document.getInteger("responseStatusCode") : null
            );
        } catch (final Exception unreadable) {
            return null;
        }

    }

}

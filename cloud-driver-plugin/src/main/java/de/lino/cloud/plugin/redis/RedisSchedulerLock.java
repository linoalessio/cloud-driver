package de.lino.cloud.plugin.redis;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.redis.RedisSupport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * Distributed once-per-window scheduler lock: with several {@code CloudBootstrap} instances behind a load balancer, each periodic
 * scheduler's tick should run on <b>one</b> instance per window, not on all of them in parallel.
 * Built entirely on the already-present {@link
 * de.lino.database.database.notification.RedisCounterService#incrementAndGetWithExpiry} "once
 * per time window" primitive (the same one rate limiting uses) - a scheduler lock is
 * structurally a rate limit of one execution per tick interval, so no new Redis primitive was
 * needed anywhere.
 *
 * <p><b>Deliberately not a real mutex</b> (no lease, no renewal): the window is simply the tick
 * interval, and the five schedulers this guards are all idempotent against a duplicate run - a
 * trash/version purge finds nothing left the second time, a pending-upload flush re-checks its
 * cache, a duplicate backup is wasted work but harmless. The worst a short window can cost is
 * one extra, harmless execution; never data loss or inconsistency.
 *
 * <p><b>Degrades to "every instance runs", never blocks:</b> no Redis configured → {@code true}
 * (single-instance deployments behave exactly as they always did); any Redis error → {@code
 * true} (a broken Redis must never stop maintenance work from happening at all). Redis is never
 * a hard dependency, per the clustering document's non-goals.
 */
public final class RedisSchedulerLock {

    /** Redis key prefix for every scheduler-lock window counter. */
    private static final String KEY_PREFIX = "cloud-driver:scheduler-lock:";

    /** Not instantiable - a pure namespace for the static lock check. */
    private RedisSchedulerLock() {
    }

    /**
     * Whether this instance is the one that runs {@code schedulerName}'s tick in the current
     * window - the instance whose increment lands first in a fresh window wins it.
     *
     * @param redisSupport the deployment's Redis facet, or {@code null} when none is configured
     *     (every instance runs, the single-instance behavior)
     * @param schedulerName the scheduler's unique lock name (e.g. {@code "trash-purge"})
     * @param tickInterval the scheduler's own tick interval - the lock window's length
     * @return {@code true} if this instance should run the tick
     */
    public static boolean tryAcquire(@Nullable final RedisSupport redisSupport,
                                      @NotNull final String schedulerName,
                                      @NotNull final Duration tickInterval) {
        if (redisSupport == null) {
            return true; // no Redis -> every instance runs, exactly the pre-clustering behavior
        }
        try {
            final long windowSeconds = Math.max(1, tickInterval.toSeconds());
            final long count = redisSupport.counterService()
                    .incrementAndGetWithExpiry(KEY_PREFIX + schedulerName, windowSeconds);
            return count == 1L;
        } catch (final Exception redisFailed) {
            // A Redis failure must never prevent the tick - degrade to "every instance runs",
            // the same as having no Redis at all (see WebhookDeliveryLog's precedent posture).
            return true;
        }
    }

    /**
     * {@link #tryAcquire(RedisSupport, String, Duration)} with the {@link RedisSupport} resolved
     * off the process-wide {@link CloudDriver} facade at call time - what the five schedulers
     * actually call from their tick methods. Resolved lazily (per tick, not captured at
     * construction) so a scheduler built before the Redis facet was wired still participates,
     * and defensively ({@code CloudDriver} not installed at all - a standalone sample - simply
     * means "run").
     *
     * @param schedulerName the scheduler's unique lock name
     * @param tickInterval the scheduler's own tick interval
     * @return {@code true} if this instance should run the tick
     */
    public static boolean tryAcquireProcessWide(@NotNull final String schedulerName, @NotNull final Duration tickInterval) {
        return tryAcquire(resolveRedisSupport(), schedulerName, tickInterval);
    }

    /** The process-wide {@link RedisSupport}, or {@code null} if none is installed/reachable - never throws. */
    @Nullable
    private static RedisSupport resolveRedisSupport() {
        try {
            return CloudDriver.getInstance().getFactoryContainer().getRedisSupport();
        } catch (final Exception cloudDriverUnavailable) {
            return null;
        }
    }
}

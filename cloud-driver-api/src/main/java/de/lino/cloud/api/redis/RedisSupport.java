package de.lino.cloud.api.redis;

import de.lino.database.database.DatabaseSection;
import de.lino.database.database.notification.RedisCounterService;
import org.jetbrains.annotations.NotNull;

/**
 * The optional Redis facet, reached through {@link
 * de.lino.cloud.api.factory.container.IFactoryContainer#getRedisSupport()} - {@code null} on any
 * deployment that hasn't configured Redis, which every caller must handle by falling back to its
 * own in-process behavior rather than failing. Redis is deliberately <b>not</b> a required
 * dependency of this system: it backs two things that each already work without it (a rate limiter
 * and a delivery-history log), just not across restarts or across more than one process.
 *
 * <p><b>What this is for, and what it is deliberately not for.</b> This deployment runs a single
 * {@code CloudBootstrap} JVM against a co-located Postgres, so Redis buys nothing as a general
 * cache - Postgres is already local, and {@code EntityDatabaseClient} already caches decrypted
 * entities in process memory. What Redis genuinely adds here is (1) state that survives a process
 * restart without being lost, and (2) state that would still be correct if a second instance were
 * ever run behind a load balancer - two properties a plain {@code ConcurrentHashMap} cannot have.
 * Only state with those needs belongs here.
 *
 * <p><b>Never store anything sensitive through this facet.</b> Every other persistence path in this
 * codebase envelope-encrypts its payload before it reaches storage (see {@code
 * SecureEntityChannel}); this one does not, and Redis persists to disk (RDB/AOF) by default. File
 * content, extracted file text, e-mail addresses, tokens, and key material must therefore never be
 * written through {@link #section(String)}. This is why the in-memory search index - whose valuable
 * half is extracted file <em>content</em> - was deliberately left in memory rather than moved here,
 * even though it is the most obviously "lost on restart" structure in this codebase; its filenames
 * are already restored at boot by {@code CloudSearchExtension}'s own backfill, so persisting it
 * here would trade a real at-rest exposure for almost no durability win.
 *
 * <p><b>Keep this Redis database's total key count small - a few hundred, not thousands.</b> This
 * is a hard constraint, not a style preference: {@code RedisDatabaseProvider}'s constructor scans
 * the entire keyspace and builds a {@code RedisDatabaseSection} - each of which runs its own full
 * scan - for every key it finds, so the cost of opening this facet at boot grows <em>quadratically</em>
 * with the number of keys already present. Measured against a local Redis while this facet was
 * built: 0 keys -> 2ms, 100 -> 21ms, 500 -> 235ms, 2,000 -> 3,274ms. Both current callers are
 * bounded by construction (rate-limit counters expire via TTL within their own window; webhook
 * delivery history is explicitly capped at 500 entries), and any future caller must be too - a
 * caller that writes one key per stored file would put boot into the minutes.
 */
public interface RedisSupport {

    /**
     * Returns the shared atomic-counter primitive backing cross-process fixed-window rate limiting
     * - see {@link RedisCounterService}'s own Javadoc for why a plain read-then-write against
     * {@link #section(String)} could not provide the same guarantee.
     *
     * @return this deployment's shared {@link RedisCounterService}, never {@code null}
     */
    @NotNull
    RedisCounterService counterService();

    /**
     * Returns the {@link DatabaseSection} backing {@code name}'s key prefix, creating it on first
     * call and returning that same instance afterward. Reads are served from the section's own
     * in-process mirror (populated once, when the section is first created) and never touch Redis;
     * only writes do - so a caller gets in-memory read performance plus durability across restarts,
     * which is the whole reason this facet exists.
     *
     * @param name the key prefix to back the section with
     * @return the section for {@code name}, never {@code null}
     */
    @NotNull
    DatabaseSection section(@NotNull String name);

    /**
     * Releases the underlying connection pool. Idempotent; after this returns, every other method
     * on this instance must be considered unusable.
     */
    void shutdown();

}

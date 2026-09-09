package de.lino.cloud.plugin.redis;

import de.lino.cloud.api.redis.RedisSupport;
import de.lino.cloud.api.utility.Constraints;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.notification.RedisCounterService;
import de.lino.database.database.nosql.redis.RedisDatabaseProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one {@link RedisSupport} implementation, wrapping {@code database-driver-plugin}'s own
 * {@link RedisDatabaseProvider} - so both the counter service and every section share that
 * provider's single connection pool rather than each opening one of their own.
 *
 * <p>Constructed exclusively through {@link #fromConfiguration(Logger)}, never directly by a
 * caller: Redis is optional, and that factory is what turns "no {@code redis-database.json}", "the
 * file is malformed", and "Redis is configured but unreachable right now" all into the same {@code
 * null} result, so every caller only has one case to handle (fall back to in-process behavior)
 * instead of three. Deliberately not the {@code KeyEncryptionService}/{@code ObjectStorageService}
 * convention of resolving AWS-style credentials out-of-band - Redis has no such chain, so this
 * follows the {@code postgres-database.json} precedent instead and reads a sibling {@code
 * redis-database.json} through the same {@link Credentials} shape.
 */
public final class JedisRedisSupport implements RedisSupport {

    /**
     * The {@link Constraints#CONFIGURATION_PATH} file this deployment's Redis connection details
     * are read from - a sibling of {@code postgres-database.json}, in the exact same {@link
     * Credentials} JSON shape ({@code address}/{@code userName}/{@code password}/{@code port}/{@code
     * database}/{@code fileRepository}), since that is what {@link Credentials#of(Path)} parses.
     * {@code database} is a Redis logical database <em>index</em> here (e.g. {@code "0"}), not a
     * name - {@link RedisDatabaseProvider} passes it straight to {@code Integer.parseInt}, so a
     * non-numeric value fails at construction rather than being ignored.
     */
    private static final String CONFIGURATION_FILE_NAME = "redis-database.json";

    /** The provider owning the shared connection pool every call below runs through. */
    private final RedisDatabaseProvider provider;

    private JedisRedisSupport(@NotNull final RedisDatabaseProvider provider) {
        this.provider = Objects.requireNonNull(provider, "@JedisRedisSupport.init: provider cannot be null");
    }

    /**
     * Resolves this deployment's Redis facet, or {@code null} if Redis is not usable for any
     * reason - a missing/unreadable/malformed {@code redis-database.json}, or a configured Redis
     * that cannot actually be reached or authenticated against right now.
     *
     * <p><b>Never throws.</b> Redis is optional infrastructure backing behavior that already works
     * without it, so a Redis problem must degrade this deployment (rate-limit state and webhook
     * delivery history stay in-process, as they were before Redis existed) rather than prevent it
     * from booting - the opposite of the {@code AwsKmsKeyEncryptionService} precedent, where a
     * misconfigured KMS genuinely must fail loudly because nothing can be encrypted without it.
     *
     * <p>Connectivity is proven eagerly here, not left until first use: {@link
     * RedisDatabaseProvider}'s own constructor issues real commands, so a wrong password or an
     * unreachable host surfaces as a caught exception at this one well-defined point instead of as
     * a scattered failure inside a request handler later.
     *
     * @param logger where a resolution failure is reported; a failure is logged at {@link
     *     Level#WARNING} (with the reason) rather than silently swallowed, since "Redis is
     *     configured but not actually working" is worth seeing in the console
     * @return the resolved facet, or {@code null} if Redis isn't configured or isn't reachable
     */
    @Nullable
    public static RedisSupport fromConfiguration(@NotNull final Logger logger) {

        final Path configurationFile = Constraints.CONFIGURATION_PATH.resolve(CONFIGURATION_FILE_NAME);
        if (Files.notExists(configurationFile)) {
            return null;
        }

        final Credentials credentials = Credentials.of(configurationFile).orElse(null);
        if (credentials == null) {
            logger.warning("@JedisRedisSupport.fromConfiguration: '" + CONFIGURATION_FILE_NAME + "' exists but could not be "
                    + "parsed - Redis stays disabled. Expected the same field shape as postgres-database.json "
                    + "(address/userName/password/port/database/fileRepository), with 'database' a numeric Redis database index.");
            return null;
        }

        try {
            return new JedisRedisSupport(new RedisDatabaseProvider(credentials));
        } catch (final Exception redisUnavailable) {
            logger.log(Level.WARNING, "@JedisRedisSupport.fromConfiguration: Redis is configured but could not be reached - "
                    + "rate limiting and webhook delivery history stay in-process for this run.", redisUnavailable);
            return null;
        }

    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public RedisCounterService counterService() {
        return this.provider.counterService();
    }

    /** {@inheritDoc} */
    @NotNull
    @Override
    public DatabaseSection section(@NotNull final String name) {
        Objects.requireNonNull(name, "@JedisRedisSupport.section: name cannot be null");
        return this.provider.createSection(name);
    }

    /** {@inheritDoc} */
    @Override
    public void shutdown() {
        this.provider.shutdown();
    }

}

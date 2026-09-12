package de.lino.cloud.plugin.redis;

import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.redis.RedisSupport;
import de.lino.cloud.plugin.file.RedisPendingUploadCache;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.exception.DataAlreadyExist;
import de.lino.database.database.exception.NoSuchEntryFound;
import de.lino.database.database.notification.RedisCounterService;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standalone, runnable worked example (same convention as the other samples) exercising the
 * multi-instance clustering pieces against
 * in-memory fakes, with the no-content-in-Redis security property made falsifiable. Prints
 * pass/fail per check and exits
 * non-zero on any failure.
 *
 * <ul>
 *     <li>{@code RedisSchedulerLock}: first acquire in a window wins, the second loses; a fresh
 *     window is winnable again; {@code null} support and a throwing counter both degrade to
 *     "run" - never blocking a tick</li>
 *     <li>{@code RedisPendingUploadCache}: an enqueue on "instance A" is visible (size) through
 *     "instance B" over the same shared section; only the receiving instance can drain it
 *     (snapshot); removal propagates</li>
 *     <li><b>Security:</b> the raw bytes that reach the fake Redis contain neither the file's
 *     content (base64 included) nor its name/checksum - only the sanctioned metadata</li>
 *     <li>a failing section degrades the cache to in-memory-only, never throwing into enqueue</li>
 * </ul>
 */
public final class RedisClusteringSample {

    /** Tracks whether every check so far passed; {@link #main} exits non-zero if any failed. */
    private static boolean allPassed = true;

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private RedisClusteringSample() {
    }

    /**
     * Runs every check described in this class's own Javadoc and reports pass/fail to stdout.
     *
     * @param args unused
     * @throws Exception on any unexpected failure - the sample makes no attempt to continue past one
     */
    public static void main(final String[] args) throws Exception {

        // --- the scheduler lock ---
        final FakeRedisSupport redis = new FakeRedisSupport();
        final Duration window = Duration.ofSeconds(1);
        check("the first acquire in a window wins", RedisSchedulerLock.tryAcquire(redis, "trash-purge", window));
        check("the second acquire in the same window loses", !RedisSchedulerLock.tryAcquire(redis, "trash-purge", window));
        check("a different scheduler's window is independent", RedisSchedulerLock.tryAcquire(redis, "database-backup", window));
        Thread.sleep(1100);
        check("a fresh window is winnable again", RedisSchedulerLock.tryAcquire(redis, "trash-purge", window));
        check("no Redis configured means every instance runs", RedisSchedulerLock.tryAcquire(null, "trash-purge", window));
        redis.counterThrows = true;
        check("a failing Redis degrades to run, never blocks the tick", RedisSchedulerLock.tryAcquire(redis, "trash-purge", window));
        redis.counterThrows = false;

        // --- the pending-upload cache, two "instances" over one shared section ---
        final RedisPendingUploadCache instanceA = new RedisPendingUploadCache(redis);
        final RedisPendingUploadCache instanceB = new RedisPendingUploadCache(redis);

        final StoredFile queued = new StoredFile("pending-file-1", "queued.txt",
                "this content must never reach redis".getBytes(StandardCharsets.UTF_8));
        instanceA.enqueue(queued);

        check("instance B sees instance A's enqueue (shared size)", instanceB.size() == 1 && !instanceB.isEmpty());
        check("only the receiving instance can drain the entry (content stays local)",
                instanceA.snapshot().size() == 1 && instanceB.snapshot().isEmpty());
        check("the local snapshot still carries the full content",
                new String(instanceA.snapshot().get(0).content(), StandardCharsets.UTF_8).contains("must never reach redis"));

        // --- security: what actually crossed Redis ---
        final String rawRedisPayload = redis.rawSectionContents();
        check("no file content (raw or base64) ever reached Redis",
                !rawRedisPayload.contains("must never reach redis")
                        && !rawRedisPayload.contains(java.util.Base64.getEncoder().encodeToString(queued.content()))
                        && !rawRedisPayload.contains("contentBase64"));
        check("neither the file name nor the checksum reached Redis",
                !rawRedisPayload.contains("queued.txt") && !rawRedisPayload.contains(queued.checksum().hexDigest()));
        check("the sanctioned metadata did reach Redis", rawRedisPayload.contains("pending-file-1"));

        // --- removal propagates ---
        instanceA.remove("pending-file-1");
        check("removal clears the shared entry for every instance", instanceB.size() == 0 && instanceA.size() == 0);

        // --- degradation: a failing section never breaks an enqueue ---
        redis.sectionThrows = true;
        final RedisPendingUploadCache degraded = new RedisPendingUploadCache(redis);
        degraded.enqueue(new StoredFile("pending-file-2", "still-works.txt", new byte[]{1, 2, 3}));
        check("a failing Redis degrades the cache to in-memory-only, enqueue still works",
                degraded.size() == 1 && degraded.snapshot().size() == 1);
        redis.sectionThrows = false;

        System.out.println(allPassed ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    /** Prints one check's outcome and folds it into {@link #allPassed}. */
    private static void check(final String description, final boolean passed) {
        System.out.println((passed ? "PASS  " : "FAIL  ") + description);
        allPassed &= passed;
    }

    /** In-memory fake {@link RedisSupport}: a real windowed counter and one shared section, both inspectable and failable. */
    private static final class FakeRedisSupport implements RedisSupport {

        /** Makes {@link #counterService()} calls fail, for the degradation checks. */
        volatile boolean counterThrows = false;
        /** Makes {@link #section(String)} lookups fail, for the degradation checks. */
        volatile boolean sectionThrows = false;

        /** {@code key → (count, windowEndEpochMillis)} - honors the expiry the way Redis would. */
        private final Map<String, long[]> counters = new ConcurrentHashMap<>();
        /** The one shared section's entries, by id. */
        private final Map<String, DatabaseEntry> entries = new LinkedHashMap<>();

        /** Every stored entry's raw JSON, concatenated - what the security checks inspect. */
        String rawSectionContents() {
            final StringBuilder raw = new StringBuilder();
            synchronized (this.entries) {
                for (final DatabaseEntry entry : this.entries.values()) {
                    raw.append(entry.getId()).append('=').append(entry.getDocument().toString()).append('\n');
                }
            }
            return raw.toString();
        }

        @NotNull
        @Override
        public RedisCounterService counterService() {
            if (this.counterThrows) {
                throw new IllegalStateException("fake Redis counter failure");
            }
            return new RedisCounterService() {
                @Override
                public long incrementAndGetWithExpiry(@NotNull final String key, final long windowSeconds) {
                    synchronized (counters) {
                        final long now = System.currentTimeMillis();
                        final long[] state = counters.get(key);
                        if (state == null || now >= state[1]) {
                            counters.put(key, new long[]{1, now + windowSeconds * 1000});
                            return 1;
                        }
                        return ++state[0];
                    }
                }

                @Override
                public long getCount(@NotNull final String key) {
                    final long[] state = counters.get(key);
                    return state == null || System.currentTimeMillis() >= state[1] ? 0 : state[0];
                }

                @Override
                public void reset(@NotNull final String key) {
                    counters.remove(key);
                }
            };
        }

        @NotNull
        @Override
        public DatabaseSection section(@NotNull final String name) {
            if (this.sectionThrows) {
                throw new IllegalStateException("fake Redis section failure");
            }
            return new DatabaseSection() {
                @Override
                public String getName() {
                    return name;
                }

                @Override
                public void insert(@NotNull final DatabaseEntry databaseEntry) {
                    synchronized (entries) {
                        if (entries.containsKey(databaseEntry.getId())) throw new DataAlreadyExist(databaseEntry.getId());
                        entries.put(databaseEntry.getId(), databaseEntry);
                    }
                }

                @Override
                public void update(@NotNull final DatabaseEntry databaseEntry) {
                    synchronized (entries) {
                        if (!entries.containsKey(databaseEntry.getId())) throw new NoSuchEntryFound(databaseEntry.getId());
                        entries.put(databaseEntry.getId(), databaseEntry);
                    }
                }

                @Override
                public void delete(@NotNull final String id) {
                    synchronized (entries) {
                        entries.remove(id);
                    }
                }

                @Override
                public long count() {
                    synchronized (entries) {
                        return entries.size();
                    }
                }

                @Override
                public void clear() {
                    synchronized (entries) {
                        entries.clear();
                    }
                }

                @Override
                public void reload() {
                    // The map IS the backing store - nothing external to re-read.
                }

                @Override
                public boolean exists(@NotNull final String id) {
                    synchronized (entries) {
                        return entries.containsKey(id);
                    }
                }

                @Override
                public Optional<DatabaseEntry> findEntryById(@NotNull final String id) {
                    synchronized (entries) {
                        return Optional.ofNullable(entries.get(id));
                    }
                }

                @Override
                public List<DatabaseEntry> getEntries() {
                    synchronized (entries) {
                        return List.copyOf(new ArrayList<>(entries.values()));
                    }
                }
            };
        }

        @Override
        public void shutdown() {
            // Nothing to release - fully in-memory.
        }
    }
}

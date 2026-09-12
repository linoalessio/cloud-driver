package de.lino.cloud.plugin.security.database;

import de.lino.cloud.api.factory.SecondaryIndexed;
import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.plugin.security.crypto.AesGcmEncryptionService;
import de.lino.cloud.plugin.security.crypto.ChunkedAesGcmStreamingService;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.develop.DataEncryptionKeyGenerator;
import de.lino.cloud.plugin.security.keys.develop.InMemoryKeyEncryptionService;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseSection;
import de.lino.database.database.SectionConfig;
import de.lino.database.database.entity.DatabaseEntry;
import de.lino.database.database.entity.Serialized;
import de.lino.database.database.exception.DataAlreadyExist;
import de.lino.database.database.exception.NoSuchEntryFound;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standalone, runnable worked example (not an {@code mvn test} target - see "Testing" in {@code
 * CLAUDE.md}) exercising {@link EntityDatabaseClient#getEntitiesByIndex} - the in-memory
 * secondary-index engine behind the hand-declared indexed lookups - end to end against an
 * in-memory fake {@link DatabaseProvider} and {@link InMemoryKeyEncryptionService} (no
 * Postgres/AWS needed), printing pass/fail per check to stdout and exiting non-zero on any
 * failure.
 *
 * <ul>
 *     <li>keyed lookups return exactly the matching entities, per key</li>
 *     <li>an unknown key answers empty; an undeclared index name throws; a type that doesn't
 *     implement {@link SecondaryIndexed} throws</li>
 *     <li>the snapshot is reused across calls while nothing changes (same returned instance)</li>
 *     <li>a write (store/delete) invalidates the snapshot via the list cache, so the next lookup
 *     reflects it</li>
 * </ul>
 */
public final class IndexedLookupSample {

    /** Tracks whether every check so far passed; {@link #main} exits non-zero if any failed. */
    private static boolean allPassed = true;

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private IndexedLookupSample() {
    }

    /** Minimal indexed entity: an item owned by one owner, indexed by that owner. */
    public static final class IndexedItem extends Serialized implements SecondaryIndexed {

        /** Secondary-index name for lookups by {@link #ownerId}. */
        public static final String INDEX_OWNER_ID = "ownerId";

        /** This item's unique id, its {@link #primaryKey()}. */
        private final String itemId;
        /** The owner this item is indexed under. */
        private final String ownerId;

        /** @param itemId this item's unique id; @param ownerId the owner this item is indexed under */
        public IndexedItem(final String itemId, final String ownerId) {
            this.itemId = itemId;
            this.ownerId = ownerId;
        }

        @Override
        public List<String> keysOf() {
            return List.of(this.itemId);
        }

        /** {@inheritDoc} Hand-declared: {@link #INDEX_OWNER_ID} → this item's owner. */
        @NotNull
        @Override
        public Map<String, String> secondaryIndexKeys() {
            return this.ownerId == null ? Map.of() : Map.of(INDEX_OWNER_ID, this.ownerId);
        }
    }

    /** Entity type deliberately NOT implementing {@link SecondaryIndexed}, for the failure check. */
    public static final class UnindexedItem extends Serialized {

        /** This item's unique id, its {@link #primaryKey()}. */
        private final String itemId;

        /** @param itemId this item's unique id */
        public UnindexedItem(final String itemId) {
            this.itemId = itemId;
        }

        @Override
        public List<String> keysOf() {
            return List.of(this.itemId);
        }
    }

    /**
     * Runs every check described in this class's own Javadoc and reports pass/fail to stdout.
     *
     * @param args unused
     * @throws Exception on any unexpected failure - the sample makes no attempt to continue past one
     */
    public static void main(final String[] args) throws Exception {

        final EnvelopeEncryptionService envelopeService = new EnvelopeEncryptionService(
                new DataEncryptionKeyGenerator(),
                new AesGcmEncryptionService(),
                new ChunkedAesGcmStreamingService(CryptoAlgorithm.AES_256_GCM, 64 * 1024),
                new InMemoryKeyEncryptionService(),
                CryptoAlgorithm.AES_256_GCM
        );
        final EntityDatabaseClient client = new EntityDatabaseClient(new InMemoryDatabaseProvider(), envelopeService);

        client.store(new IndexedItem("a-1", "alice"));
        client.store(new IndexedItem("a-2", "alice"));
        client.store(new IndexedItem("b-1", "bob"));

        // --- keyed lookups ---
        check("alice's items found by index",
                client.getEntitiesByIndex(IndexedItem.class, IndexedItem.INDEX_OWNER_ID, "alice").size() == 2);
        check("bob's items found by index",
                client.getEntitiesByIndex(IndexedItem.class, IndexedItem.INDEX_OWNER_ID, "bob").size() == 1);
        check("unknown key answers empty",
                client.getEntitiesByIndex(IndexedItem.class, IndexedItem.INDEX_OWNER_ID, "nobody").isEmpty());

        // --- snapshot reuse while nothing changes ---
        final List<IndexedItem> first = client.getEntitiesByIndex(IndexedItem.class, IndexedItem.INDEX_OWNER_ID, "alice");
        final List<IndexedItem> second = client.getEntitiesByIndex(IndexedItem.class, IndexedItem.INDEX_OWNER_ID, "alice");
        check("snapshot reused across calls (same instance)", first == second);

        // --- write invalidation ---
        client.store(new IndexedItem("a-3", "alice"));
        check("a store is visible to the next indexed lookup",
                client.getEntitiesByIndex(IndexedItem.class, IndexedItem.INDEX_OWNER_ID, "alice").size() == 3);
        client.delete("b-1", IndexedItem.class);
        check("a delete is visible to the next indexed lookup",
                client.getEntitiesByIndex(IndexedItem.class, IndexedItem.INDEX_OWNER_ID, "bob").isEmpty());

        // --- misuse fails loudly ---
        try {
            client.getEntitiesByIndex(IndexedItem.class, "no-such-index", "alice");
            check("undeclared index name throws", false);
        } catch (final IllegalArgumentException expected) {
            check("undeclared index name throws", true);
        }
        try {
            client.store(new UnindexedItem("u-1"));
            client.getEntitiesByIndex(UnindexedItem.class, "anything", "x");
            check("non-SecondaryIndexed type throws", false);
        } catch (final IllegalArgumentException expected) {
            check("non-SecondaryIndexed type throws", true);
        }

        System.out.println(allPassed ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        if (!allPassed) {
            System.exit(1);
        }
    }

    /** Prints one check's outcome and folds it into {@link #allPassed}. */
    private static void check(final String description, final boolean passed) {
        System.out.println((passed ? "PASS  " : "FAIL  ") + description);
        allPassed &= passed;
    }

    /** In-memory fake {@link DatabaseProvider}: one {@link InMemoryDatabaseSection} per name, no real database anywhere. */
    private static final class InMemoryDatabaseProvider implements DatabaseProvider {

        /** Every created section, by name. */
        private final Map<String, DatabaseSection> sections = new ConcurrentHashMap<>();

        @Override
        public DatabaseSection createSection(@NotNull final String name) {
            return this.sections.computeIfAbsent(name, InMemoryDatabaseSection::new);
        }

        @Override
        public DatabaseSection createSection(@NotNull final String name, @NotNull final SectionConfig config) {
            return this.createSection(name);
        }

        @Override
        public void deleteSection(@NotNull final String name) {
            this.sections.remove(name);
        }

        @Override
        public List<DatabaseSection> getSections() {
            return List.copyOf(this.sections.values());
        }

        @Override
        public Optional<DatabaseSection> getSection(@NotNull final String name) {
            return Optional.ofNullable(this.sections.get(name));
        }

        @Override
        public boolean existsSection(@NotNull final String name) {
            return this.sections.containsKey(name);
        }

        @Override
        public void clear() {
            this.sections.values().forEach(DatabaseSection::clear);
        }

        @Override
        public void reload() {
            // Nothing external to re-read - the map IS the backing store.
        }

        @Override
        public void shutdown() {
            this.sections.clear();
        }
    }

    /** In-memory fake {@link DatabaseSection}: a map of entries, honoring the insert/update exception contract. */
    private static final class InMemoryDatabaseSection implements DatabaseSection {

        /** This section's name. */
        private final String name;
        /** Every stored entry, by primary key, in insertion order. */
        private final Map<String, DatabaseEntry> entries = new LinkedHashMap<>();

        /** @param name this section's name */
        private InMemoryDatabaseSection(final String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public synchronized void insert(@NotNull final DatabaseEntry databaseEntry) {
            if (this.entries.containsKey(databaseEntry.getId())) {
                throw new DataAlreadyExist(databaseEntry.getId());
            }
            this.entries.put(databaseEntry.getId(), databaseEntry);
        }

        @Override
        public synchronized void update(@NotNull final DatabaseEntry databaseEntry) {
            if (!this.entries.containsKey(databaseEntry.getId())) {
                throw new NoSuchEntryFound(databaseEntry.getId());
            }
            this.entries.put(databaseEntry.getId(), databaseEntry);
        }

        @Override
        public synchronized void delete(@NotNull final String id) {
            this.entries.remove(id);
        }

        @Override
        public synchronized long count() {
            return this.entries.size();
        }

        @Override
        public synchronized void clear() {
            this.entries.clear();
        }

        @Override
        public void reload() {
            // Nothing external to re-read - the map IS the backing store.
        }

        @Override
        public synchronized boolean exists(@NotNull final String id) {
            return this.entries.containsKey(id);
        }

        @Override
        public synchronized Optional<DatabaseEntry> findEntryById(@NotNull final String id) {
            return Optional.ofNullable(this.entries.get(id));
        }

        @Override
        public synchronized List<DatabaseEntry> getEntries() {
            return List.copyOf(new ArrayList<>(this.entries.values()));
        }
    }
}

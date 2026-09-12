package de.lino.cloud.api.factory;

import de.lino.database.database.entity.Serialized;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * A {@link Serialized} entity that declares keyed secondary-index values for {@link
 * DataFactory#getEntitiesByIndex} - the contract behind the indexed per-entity lookups that
 * replaced the old "decrypt and scan every row of the type" pattern.
 *
 * <p><b>Hand-written, never reflective.</b> Each implementing entity spells out its own index
 * names and values explicitly in {@link #secondaryIndexKeys()} - typically alongside {@code
 * public static final String INDEX_*} name constants its lookup call sites share - so every
 * indexed field is a visible, deliberate declaration in the entity's own source, matching this
 * codebase's no-reflection convention.
 *
 * <p><b>Where the index actually lives.</b> Database rows are ciphertext ({@code id TEXT, data
 * BYTEA} - nothing plaintext ever reaches the database, see {@code docs/security.md}), so these
 * are deliberately <em>not</em> SQL indexes: {@code EntityDatabaseClient} builds an in-memory
 * {@code index name → key → entities} map over the type's already-decrypted {@code getEntities}
 * list cache, and rebuilds it whenever that cached list changes (any write to the type, a
 * reload, or the list cache's own TTL expiry). A lookup is O(1) against the current snapshot;
 * the full decrypt cost is paid only when the underlying list itself had to be re-read - exactly
 * as often as a single {@code getEntities} call already paid it.
 *
 * <p>An entity whose value for some index is absent (e.g. an {@code AuditEvent} with no target)
 * simply omits that name from the returned map - it is then not found under that index at all,
 * matching what the equivalent {@code filter(value::equals)} scan produced for a {@code null}
 * field.
 */
public interface SecondaryIndexed {

    /**
     * This entity's secondary-index values, keyed by index name - see this interface's own
     * Javadoc for the contract. Called by {@code EntityDatabaseClient} once per entity per index
     * rebuild; implementations should be cheap, allocation-light field reads.
     *
     * @return every {@code index name → index key} pair this entity is findable under; names with
     *     no value for this entity are omitted, never mapped to {@code null}
     */
    @NotNull
    Map<String, String> secondaryIndexKeys();
}

package de.lino.cloud.api.intelligence;

import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.search.SearchIndexService;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * Semantic (embedding-based) search over {@link StoredFile} content, reached via {@link
 * IServiceContainer#getIntelligenceService()} - {@code null} until {@code
 * cloud-driver-extensions-intelligence}'s {@code CloudIntelligenceExtension} has published one
 * (not started, or this deployment doesn't run that extension at all), the same "may not exist
 * yet" contract every other {@link IServiceContainer} facet already carries.
 *
 * <p>Complements, never replaces, the keyword-based {@link SearchIndexService}: that one matches
 * literal substrings of a file's name/extracted text, this one matches <em>meaning</em>, so a
 * query like "Rechnung Autowerkstatt" can surface a file named {@code scan_0042.pdf}. Both are
 * independently optional; a deployment may run either, both, or neither.
 *
 * <p><b>Deployment dependency, not merely a Maven dependency</b> - the one shipped implementation
 * is a thin HTTP bridge (mirroring {@code ClamAvClient}'s own role for {@code clamd}) to a
 * separately-run Python service, {@code cloud-driver-intelligence}, which owns the embedding model
 * and the vector store. That service holds its own, completely separate data store and never
 * touches Postgres - see this repository's {@code cloud-driver-intelligence/README.md}.
 *
 * <h2>The security invariant - read this before touching any implementation or caller</h2>
 *
 * <b>The Python service does not know, and must never be permitted to decide, what a user is
 * allowed to see.</b> Its notion of ownership is a stale hint recorded at index time; sharing
 * grants, revocations, moves into someone else's folder, trashing and hard deletion all happen in
 * {@code cloud-driver} long after a vector was written, and none of them are guaranteed to have
 * reached the vector store. A search is therefore always <b>two-staged</b>:
 *
 * <ol>
 *   <li><b>Pre-filter.</b> The caller resolves the complete set of file ids the searching account
 *       currently has access to (owned plus shared) from the real, authoritative data, and passes
 *       exactly that set as {@code candidateFileIds}. The Python service ranks only within it.</li>
 *   <li><b>Post-check.</b> Every returned {@link SemanticMatch} is re-validated against that same
 *       authoritative access check before it may reach a client - the returned ids are treated as
 *       untrusted input, never as a result the pre-filter already vouched for.</li>
 * </ol>
 *
 * The redundancy is deliberate and is not an optimization candidate: with both stages in place, a
 * compromised, buggy, or simply out-of-date Python service can at worst return <em>nothing</em>,
 * never another account's files. Removing either stage turns a non-security component into a
 * security-critical one. {@code CloudUserService#semanticSearch} is the one place in this codebase
 * that performs both, and every client-facing route goes through it.
 *
 * <h2>Fail-open</h2>
 *
 * Indexing is always best-effort and asynchronous: {@link #indexAsync}/{@link #removeAsync} must
 * return promptly and must never throw, so an unreachable Python service leaves a file simply
 * un-indexed (logged, retried a bounded number of times, then given up on) rather than failing the
 * upload that triggered it. This upholds the same "must keep working with every microservice
 * turned off" constraint {@code ContentScanService} documents. Unlike a scan verdict, an
 * indexing outcome gates nothing, so - deliberately - no state for it is persisted on {@link
 * StoredFile} at all.
 */
public interface IntelligenceService {

    /**
     * Queues {@code document} for embedding and storage in the vector store, replacing any vector
     * previously stored under the same {@link IntelligenceDocument#storedFileId()}. Must return
     * quickly and never throw - the real work always happens on this service's own background
     * worker, never the calling request thread.
     *
     * @param document the file's current state to embed
     */
    void indexAsync(@NotNull IntelligenceDocument document);

    /**
     * Queues removal of {@code storedFileId}'s vector - called when a file is trashed or
     * permanently deleted, so it stops surfacing in results. Must return quickly and never throw.
     *
     * <p>Removal is <b>not</b> what makes deletion safe: a vector that outlives its file is
     * harmless, because the pre-filter (stage 1 above) would never offer that id as a candidate
     * and the post-check (stage 2) would reject it anyway. This exists purely to keep the store
     * from growing without bound and to avoid ranking effort spent on dead entries.
     *
     * @param storedFileId the file whose vector to remove
     */
    void removeAsync(@NotNull String storedFileId);

    /**
     * Ranks {@code candidateFileIds} by semantic similarity to {@code queryText}, most similar
     * first. <b>Synchronous</b>, unlike the two indexing methods - a search is a request the caller
     * is actively waiting on; callers are expected to run it off the request thread themselves
     * (every route in this codebase already dispatches via {@code MultiTaskingFactory}).
     *
     * <p>Never throws: an unreachable Python service, a malformed response, or an unembeddable
     * query all return an empty list, so a semantic-search route degrades to "no results" rather
     * than an error - matching the fail-open philosophy above.
     *
     * <p><b>Implementations must treat {@code candidateFileIds} as a hard restriction, never a
     * ranking hint</b>, and callers must still re-check every returned id regardless - see this
     * interface's own "security invariant" section for why both halves are required.
     *
     * @param queryText the caller's search text
     * @param candidateFileIds the only ids that may be matched - the caller's currently-accessible
     * set, resolved from authoritative data. An empty collection returns an empty list without
     * contacting the Python service at all.
     * @param limit the maximum number of matches to return
     * @return matches, most similar first, capped at {@code limit}; empty if nothing matches or the
     * service is unavailable
     */
    @NotNull
    List<SemanticMatch> search(@NotNull String queryText, @NotNull Collection<String> candidateFileIds, int limit);

}

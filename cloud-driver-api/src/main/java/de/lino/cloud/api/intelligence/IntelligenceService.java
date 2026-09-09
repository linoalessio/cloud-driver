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

    /**
     * Queues an update of {@code storedFileId}'s recorded owner, without re-embedding its content.
     *
     * <p><b>This is bookkeeping, never an access decision</b> - the recorded owner remains exactly
     * the non-authoritative hint this interface's security-invariant section describes, and no
     * search path consults it. What it does affect is {@link #findDuplicates}, which is scoped per
     * account: a stale owner there produces a wrong (never a leaked) grouping, since candidate ids
     * are still supplied by the caller's own authoritative pre-filter.
     *
     * <p>Cheap by construction - the stored vector is untouched, only its metadata - so callers may
     * invoke it on ordinary sharing changes without paying an embedding cost. Must return quickly
     * and never throw, like the other two queueing methods here.
     *
     * @param storedFileId the file whose recorded owner to refresh
     * @param ownerAuthUserId the account that currently owns it
     */
    void refreshOwnerAsync(@NotNull String storedFileId, @NotNull String ownerAuthUserId);

    /**
     * Groups {@code candidateFileIds} into sets whose vectors are at least {@code
     * minimumSimilarity} alike - "these look like the same document" for a human to review.
     *
     * <p><b>Bound by the same security invariant as {@link #search}</b>, for the same reason and in
     * the same two stages: {@code candidateFileIds} is a hard restriction resolved by the caller
     * from authoritative access data, and every returned id must still be re-checked by the caller
     * before it may reach a client. A grouping is a strictly more sensitive result than a search
     * hit - it asserts a relationship <em>between</em> two files - so neither stage may be relaxed
     * here on the grounds that the ids "came from" the caller.
     *
     * <p>Never throws: an unavailable service returns an empty list, exactly as {@link #search}
     * does.
     *
     * @param candidateFileIds the only ids that may be grouped; an empty collection returns an
     * empty list without contacting the backing service
     * @param minimumSimilarity the cosine-similarity floor in {@code [0, 1]} every pair within a
     * group must meet. Similarity is not linear in perceived sameness - a sensible floor is high
     * (0.9+); a low one groups everything that shares a topic.
     * @param limit the maximum number of groups to return
     * @return near-duplicate groups, most similar first; empty if nothing qualifies or the service
     * is unavailable
     */
    @NotNull
    List<DuplicateGroup> findDuplicates(@NotNull Collection<String> candidateFileIds, double minimumSimilarity, int limit);

    /**
     * Suggests descriptive labels for {@code storedFileId} from the backing service's fixed
     * vocabulary - see {@link TagSuggestion}'s own Javadoc for how they are derived and, more
     * importantly, what their confidence does not mean.
     *
     * <p>Takes only a file id and therefore performs <b>no access check of its own</b>: the caller
     * must have already established that the requesting account may see this file. This mirrors
     * every other method here - none of them is an authorization boundary.
     *
     * <p>Returns an empty list for a file that was never indexed (there is no vector to compare),
     * for an unavailable service, or when the backing model cannot embed at all. Never throws.
     *
     * @param storedFileId the file to label
     * @param limit the maximum number of suggestions to return
     * @return suggestions, most confident first; empty if none can be produced
     */
    @NotNull
    List<TagSuggestion> suggestTags(@NotNull String storedFileId, int limit);

    /**
     * Probes whether the backing service is reachable and actually able to embed right now.
     *
     * <p>Distinct from "this facet is published", which only means the extension loaded and says
     * nothing about the separately-run service behind it. Because indexing fails open, an
     * unreachable service produces no error anywhere a user can see - files simply stop being
     * indexed - so this probe is the only way to notice before someone reports that search has
     * quietly stopped returning new files.
     *
     * <p>Must never throw - unreachable is an answer, not a failure.
     *
     * @return {@code true} only if the service answered <em>and</em> reported a usable embedding
     * backend; {@code false} if it is unreachable, or up but unable to embed (an optional
     * dependency missing), which is a state that otherwise looks identical to "nothing matched"
     */
    boolean isServiceHealthy();

}

package de.lino.cloud.api.intelligence;

import org.jetbrains.annotations.NotNull;

/**
 * One suggested descriptive label for a file, as returned by {@link
 * IntelligenceService#suggestTags}.
 *
 * <h2>How these are produced, and why that matters to a caller</h2>
 *
 * Zero-shot: the embedding service holds a fixed vocabulary of candidate labels, embeds each one,
 * and scores a file's own vector against them. There is no training step, no per-deployment model,
 * and no learning from user behaviour - which means a suggestion is only ever as good as the
 * overlap between that fixed vocabulary and what a given account actually stores, and a file
 * whose subject is outside the vocabulary will still return the least-bad labels in it rather
 * than nothing.
 *
 * <p>Consequently {@link #confidence} is a <em>relative</em> similarity, not a calibrated
 * probability, and must not be rendered to an end user as a percentage of correctness. Callers
 * are expected to apply their own floor and to present the result as a suggestion a human accepts
 * or rejects - nothing in this codebase persists a tag automatically.
 *
 * @param tag the suggested label, drawn from the service's fixed vocabulary
 * @param confidence cosine similarity between the file's vector and the label's, in {@code [0, 1]};
 * higher is a closer match, but see this record's own Javadoc on what that does and does not mean
 */
public record TagSuggestion(@NotNull String tag, double confidence) {
}

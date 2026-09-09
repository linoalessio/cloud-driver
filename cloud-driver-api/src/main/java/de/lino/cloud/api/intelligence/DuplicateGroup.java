package de.lino.cloud.api.intelligence;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A set of files the embedding model considers near-identical in meaning, as returned by {@link
 * IntelligenceService#findDuplicates}.
 *
 * <h2>"Near-duplicate", not "duplicate"</h2>
 *
 * This is deliberately <b>not</b> the same thing as {@code StoredFile}'s content deduplication,
 * and the two must never be conflated. Deduplication matches on an exact SHA-256 of the raw bytes
 * and is therefore a fact: two files either are byte-identical or they are not. This is a
 * <em>similarity</em> judgement made by a language model over a file's name and extracted text -
 * a scan of an invoice and a re-scan of the same invoice at a different resolution are not
 * byte-identical and will never deduplicate, but they should absolutely surface here.
 *
 * <p>The practical consequence: a result from this API is a <b>suggestion for a human</b>, never
 * grounds for deleting anything automatically. Nothing in this codebase acts on it unattended.
 *
 * @param storedFileIds every file in this group, most representative first; always at least two,
 * since a group of one is not a duplicate of anything
 * @param similarity the lowest pairwise cosine similarity within the group, in {@code [0, 1]} -
 * the group's weakest link, so a caller comparing against its own threshold is judging the whole
 * group rather than its best pair
 */
public record DuplicateGroup(@NotNull List<String> storedFileIds, double similarity) {
}

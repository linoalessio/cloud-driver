package de.lino.cloud.api.versioning;

import de.lino.cloud.api.file.StoredFile;

/**
 * One prior version of a {@link StoredFile}'s content, without the content itself - the shape
 * {@link FileVersioningService#listVersions} returns. Mirrors {@code StoredFileSummary}'s own
 * "descriptive fields, no content" convention.
 *
 * @param versionNumber a 1-based, per-file sequence number - {@code 1} is the oldest captured version
 * @param capturedAtEpochMillis when this version was captured (i.e. when the overwrite that superseded it happened), as epoch milliseconds
 * @param sizeBytes this version's content size, in bytes
 */
public record FileVersionSummary(int versionNumber, long capturedAtEpochMillis, long sizeBytes) {
}

package de.lino.cloud.extensions.versioning;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.FileChunkManifest;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.versioning.FileVersionContent;
import de.lino.cloud.api.versioning.FileVersionSummary;
import de.lino.cloud.api.versioning.FileVersioningService;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The one {@link FileVersioningService} implementation - see that interface's own Javadoc for the full contract. */
final class DefaultFileVersioningService implements FileVersioningService {

    private final DataFactory dataFactory;
    private final FileFactory fileFactory;
    private final Logger logger;

    DefaultFileVersioningService(@NonNull final DataFactory dataFactory, @NonNull final FileFactory fileFactory, @NonNull final Logger logger) {
        this.dataFactory = dataFactory;
        this.fileFactory = fileFactory;
        this.logger = logger;
    }

    /**
     * How many versions a delta chain may hold before the next capture stores a full keyframe
     * again - caps how many deltas {@link #reconstructContent} ever has to replay, per the
     * roadmap Phase-4 design signed off 2026-09-12.
     */
    static final int KEYFRAME_INTERVAL = 10;

    /**
     * {@inheritDoc}
     *
     * <p><b>Delta storage (roadmap Phase 4):</b> when the previous retained version carries a
     * chunk-hash list, only the chunks of {@code previousContent} that differ from it are stored
     * (as their concatenation, in one versioned {@link StoredFile}) - a full copy ("keyframe")
     * is stored instead for the first version, after any legacy/chunk-size-mismatched
     * predecessor, every {@link #KEYFRAME_INTERVAL} versions, or when the delta wouldn't
     * actually be smaller. Version numbers are strictly monotonic ({@code max + 1}, <b>not</b>
     * {@code count + 1}) - reusing a purged number would both break the oldest-first ordering
     * and corrupt a delta chain's contiguous-number invariant.
     *
     * <p>Never throws - a failure to capture a version must never block the real content
     * replacement it's capturing history for (see this interface's own Javadoc), so any failure
     * here is caught and logged.
     */
    @Override
    public void captureVersion(@NonNull final String sourceFileId, @NonNull final StoredFile previousContent) {
        try {
            final List<FileVersion> retained = this.retainedVersions(sourceFileId);
            final FileVersion previous = retained.isEmpty() ? null : retained.get(retained.size() - 1);
            final int nextVersionNumber = previous == null ? 1 : previous.getVersionNumber() + 1;
            final String versionedFileId = UUID.randomUUID().toString();

            final byte[] content = previousContent.content();
            final String chunkHashesBase64 = FileChunkManifest.of(sourceFileId, content).chunkHashesBase64();
            final List<Integer> changedChunks = this.deltaChunkIndicesAgainst(previous, retained, content, chunkHashesBase64);

            if (changedChunks == null) {
                // Keyframe: full copy, exactly the pre-delta behavior - plus the hash list, so
                // the NEXT capture can be a delta.
                this.fileFactory.upload(new StoredFile(versionedFileId, previousContent.fileName(), content));
                this.dataFactory.register(new FileVersion(
                        sourceFileId, nextVersionNumber, versionedFileId,
                        previousContent.fileName(), previousContent.contentType(),
                        previousContent.sizeBytes(), previousContent.checksum(), System.currentTimeMillis(),
                        true, null, null, Constraints.CONTENT_CHUNK_SIZE_BYTES, chunkHashesBase64
                ));
                return;
            }

            final byte[] deltaContent = concatenateChunks(content, changedChunks);
            this.fileFactory.upload(new StoredFile(versionedFileId, previousContent.fileName(), deltaContent));
            this.dataFactory.register(new FileVersion(
                    sourceFileId, nextVersionNumber, versionedFileId,
                    previousContent.fileName(), previousContent.contentType(),
                    previousContent.sizeBytes(), previousContent.checksum(), System.currentTimeMillis(),
                    false, changedChunks, previous.getVersionNumber(), Constraints.CONTENT_CHUNK_SIZE_BYTES, chunkHashesBase64
            ));
        } catch (final DatabaseClientException | KeyWrapException e) {
            this.logger.log(Level.WARNING, "@DefaultFileVersioningService.captureVersion: failed to capture a version of file '" + sourceFileId + "'", e);
        }
    }

    /**
     * The chunk indices of {@code content} that differ from {@code previous}'s recorded hash
     * list - or {@code null} when this capture must be a keyframe instead: no previous version,
     * a legacy/mismatched predecessor (no hash list, or a different chunk size), a chain already
     * {@link #KEYFRAME_INTERVAL} long, a broken chain (its keyframe purged out from under it),
     * or a delta that wouldn't be smaller than the full content anyway.
     */
    private List<Integer> deltaChunkIndicesAgainst(final FileVersion previous, final List<FileVersion> retained,
                                                    final byte[] content, final String chunkHashesBase64) {
        if (previous == null || previous.getChunkHashesBase64() == null
                || previous.getChunkSizeBytes() == null || previous.getChunkSizeBytes() != Constraints.CONTENT_CHUNK_SIZE_BYTES) {
            return null;
        }
        if (this.chainLengthEndingAt(previous, retained) >= KEYFRAME_INTERVAL) {
            return null;
        }
        final int chunkCount = FileChunkManifest.chunkCountFor(content.length, Constraints.CONTENT_CHUNK_SIZE_BYTES);
        final List<Integer> changed = new ArrayList<>();
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            if (!FileChunkManifest.chunkHashEquals(chunkHashesBase64, previous.getChunkHashesBase64(), chunk)) {
                changed.add(chunk);
            }
        }
        // A delta of every chunk stores as much as a keyframe while costing a replay - and an
        // empty delta (a same-content overwrite) still needs a row, which a zero-chunk delta
        // represents fine, but degenerate whole-file changes go keyframe.
        return changed.size() >= chunkCount && chunkCount > 0 ? null : changed;
    }

    /**
     * How many rows the delta chain ending at {@code newest} holds, keyframe included - {@link
     * #KEYFRAME_INTERVAL} (i.e. "start a new one") if the walk hits a gap or runs out of rows
     * before finding a keyframe, since a chain whose keyframe is gone must never be extended.
     */
    private int chainLengthEndingAt(final FileVersion newest, final List<FileVersion> retained) {
        final Map<Integer, FileVersion> byNumber = new HashMap<>();
        for (final FileVersion version : retained) {
            byNumber.put(version.getVersionNumber(), version);
        }
        int length = 0;
        FileVersion cursor = newest;
        while (cursor != null) {
            length++;
            if (cursor.isKeyframe()) {
                return length;
            }
            cursor = cursor.getBaseVersionNumber() == null ? null : byNumber.get(cursor.getBaseVersionNumber());
        }
        return KEYFRAME_INTERVAL;
    }

    /** The chunks of {@code content} named by {@code chunkIndices} (ascending), concatenated - a delta's stored payload. */
    private static byte[] concatenateChunks(final byte[] content, final List<Integer> chunkIndices) {
        final int chunkSize = Constraints.CONTENT_CHUNK_SIZE_BYTES;
        int totalLength = 0;
        for (final int chunk : chunkIndices) {
            totalLength += (int) Math.min(chunkSize, (long) content.length - (long) chunk * chunkSize);
        }
        final byte[] delta = new byte[totalLength];
        int position = 0;
        for (final int chunk : chunkIndices) {
            final int offset = chunk * chunkSize;
            final int length = Math.min(chunkSize, content.length - offset);
            System.arraycopy(content, offset, delta, position, length);
            position += length;
        }
        return delta;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public List<FileVersionSummary> listVersions(@NonNull final String sourceFileId) {
        return this.retainedVersions(sourceFileId).stream()
                .map(version -> new FileVersionSummary(version.getVersionNumber(), version.getCapturedAtEpochMillis(), version.getSizeBytes()))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>A keyframe resolves directly; a delta is reconstructed by walking its chain back to the
     * keyframe and replaying at most {@link #KEYFRAME_INTERVAL} chunk splices forward. The
     * reconstructed content is verified against the row's recorded {@link
     * FileVersion#getContentHash() checksum} - a mismatch (or a broken chain) answers empty and
     * logs, never silently wrong bytes.
     */
    @NonNull
    @Override
    public Optional<FileVersionContent> getVersionContent(@NonNull final String sourceFileId, final int versionNumber) {
        try {
            final Optional<FileVersion> version = this.dataFactory.findById(
                    FileVersion.compositeKey(sourceFileId, versionNumber), FileVersion.class);
            if (version.isEmpty()) return Optional.empty();
            final FileVersion row = version.get();
            final byte[] content = this.reconstructContent(row);
            if (content == null) {
                return Optional.empty();
            }
            if (!row.getContentHash().matches(content)) {
                this.logger.log(Level.WARNING, "@DefaultFileVersioningService.getVersionContent: reconstructed content of version "
                        + versionNumber + " of file '" + sourceFileId + "' fails its recorded checksum - refusing to serve it");
                return Optional.empty();
            }
            return Optional.of(new FileVersionContent(row.getFileName(), row.getContentType(), content));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            this.logger.log(Level.WARNING, "@DefaultFileVersioningService.getVersionContent: failed to resolve version "
                    + versionNumber + " of file '" + sourceFileId + "'", e);
            return Optional.empty();
        }
    }

    /**
     * {@code row}'s full content bytes - the stored copy directly for a keyframe, or the chain
     * replay for a delta: walk {@link FileVersion#getBaseVersionNumber()} links back to the
     * keyframe, then splice each delta's chunks forward in order. {@code null} (with a log line)
     * on a broken chain - a missing link, or a row whose delta payload doesn't match its
     * recorded indices.
     */
    private byte[] reconstructContent(final FileVersion row)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        if (row.isKeyframe()) {
            final Optional<StoredFile> stored = this.fileFactory.findById(row.getVersionedFileId());
            return stored.map(StoredFile::content).orElse(null);
        }

        // Collect the chain target ← ... ← keyframe, then replay oldest-first.
        final List<FileVersion> chain = new ArrayList<>();
        FileVersion cursor = row;
        while (true) {
            chain.add(cursor);
            if (cursor.isKeyframe()) {
                break;
            }
            if (cursor.getBaseVersionNumber() == null || chain.size() > KEYFRAME_INTERVAL) {
                this.logger.log(Level.WARNING, "@DefaultFileVersioningService.reconstructContent: version "
                        + row.getVersionNumber() + " of file '" + row.getSourceFileId() + "' has a broken delta chain");
                return null;
            }
            final Optional<FileVersion> base = this.dataFactory.findById(
                    FileVersion.compositeKey(cursor.getSourceFileId(), cursor.getBaseVersionNumber()), FileVersion.class);
            if (base.isEmpty()) {
                this.logger.log(Level.WARNING, "@DefaultFileVersioningService.reconstructContent: version "
                        + row.getVersionNumber() + " of file '" + row.getSourceFileId() + "' depends on purged version "
                        + cursor.getBaseVersionNumber() + " - chain is broken");
                return null;
            }
            cursor = base.get();
        }

        byte[] content = null;
        for (int index = chain.size() - 1; index >= 0; index--) {
            final FileVersion step = chain.get(index);
            final Optional<StoredFile> stored = this.fileFactory.findById(step.getVersionedFileId());
            if (stored.isEmpty()) {
                return null;
            }
            final byte[] payload = stored.get().content();
            if (step.isKeyframe()) {
                content = payload;
                continue;
            }
            content = spliceDelta(content, payload, step);
            if (content == null) {
                this.logger.log(Level.WARNING, "@DefaultFileVersioningService.reconstructContent: version "
                        + step.getVersionNumber() + " of file '" + step.getSourceFileId() + "' carries an inconsistent delta payload");
                return null;
            }
        }
        return content;
    }

    /**
     * Applies one delta step: the new content is {@code base} resized to {@code step}'s recorded
     * size, with {@code step}'s stored chunks spliced in at their recorded indices. {@code null}
     * if the payload's length doesn't match what the indices and size imply.
     */
    private static byte[] spliceDelta(final byte[] base, final byte[] deltaPayload, final FileVersion step) {
        final int chunkSize = step.getChunkSizeBytes() != null ? step.getChunkSizeBytes() : Constraints.CONTENT_CHUNK_SIZE_BYTES;
        final long totalSize = step.getSizeBytes();
        if (base == null || totalSize < 0 || totalSize > Integer.MAX_VALUE || step.getDeltaChunkIndices() == null) {
            return null;
        }
        final byte[] content = new byte[(int) totalSize];
        System.arraycopy(base, 0, content, 0, Math.min(base.length, content.length));

        int position = 0;
        for (final Integer chunk : step.getDeltaChunkIndices()) {
            if (chunk == null || chunk < 0) {
                return null;
            }
            final long offset = (long) chunk * chunkSize;
            if (offset >= totalSize) {
                return null;
            }
            final int length = (int) Math.min(chunkSize, totalSize - offset);
            if (position + length > deltaPayload.length) {
                return null;
            }
            System.arraycopy(deltaPayload, position, content, (int) offset, length);
            position += length;
        }
        return position == deltaPayload.length ? content : null;
    }

    /**
     * {@inheritDoc} Answered off the {@link FileVersion} row's own {@link
     * FileVersion#getContentHash() captured checksum} - the row is metadata-only (the content
     * lives in a separate versioned {@code StoredFile}), so this never resolves content. A
     * lookup failure is reported as empty rather than thrown, matching {@link
     * #getVersionContent}'s own defensive shape - the caller then simply serves the content
     * unconditionally.
     */
    @NotNull
    @Override
    public Optional<String> versionChecksumHex(@NonNull final String sourceFileId, final int versionNumber) {
        try {
            return this.dataFactory.findById(FileVersion.compositeKey(sourceFileId, versionNumber), FileVersion.class)
                    .map(version -> version.getContentHash().hexDigest());
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            this.logger.log(Level.WARNING, "@DefaultFileVersioningService.versionChecksumHex: failed to look up version "
                    + versionNumber + " of file '" + sourceFileId + "'", e);
            return Optional.empty();
        }
    }

    /**
     * Every currently-retained {@link FileVersion} row for {@code sourceFileId}, oldest first -
     * the full-scan-and-filter this class's own Javadoc documents as an accepted trade-off. A
     * failed scan is reported as "no versions" rather than thrown, since every caller of this
     * private helper already treats an empty result as a normal, valid outcome.
     */
    private List<FileVersion> retainedVersions(final String sourceFileId) {
        try {
            return this.dataFactory.getEntitiesByIndex(FileVersion.class, FileVersion.INDEX_SOURCE_FILE_ID, sourceFileId).stream()
                    .sorted(Comparator.comparingInt(FileVersion::getVersionNumber))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            this.logger.log(Level.WARNING, "@DefaultFileVersioningService.retainedVersions: failed to scan versions of file '" + sourceFileId + "'", e);
            return List.of();
        }
    }

}

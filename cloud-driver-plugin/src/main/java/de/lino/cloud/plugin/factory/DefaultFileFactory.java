package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.meta.FileMetadata;
import de.lino.cloud.api.file.pending.PendingUploadCache;
import de.lino.cloud.api.metrics.MetricsRecorder;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.plugin.connectivity.InternetConnectivityChecker;
import de.lino.cloud.plugin.file.InMemoryPendingUploadCache;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.s3storage.StoredFileContentChannel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;

/**
 * {@link FileFactory} implementation backed by a {@link DataFactory}: since
 * {@link StoredFile} is itself a {@code Serialized} entity, {@link
 * #download}/{@link #findById}/{@link #delete} are a thin pass-through to
 * it, plus a {@link #verifyIntegrity} checksum check on top of {@link
 * DataFactory}'s own AES-256-GCM authentication.
 *
 * <p><b>Offline-safe {@link #upload}.</b> If {@code connectivityChecker}
 * reports no connectivity, the file(s) are queued into {@code
 * pendingUploadCache} instead of attempting a database call - a {@code
 * PendingUploadScheduler} sharing the same cache is expected to drain it
 * later. A {@link DatabaseClientException} thrown mid-call is treated the
 * same way if connectivity has since dropped, otherwise it is rethrown.
 *
 * <p><b>Optional S3-backed content.</b> If {@code
 * objectStorageService} is non-{@code null} (an operator has opted into it - see {@link
 * #DefaultFileFactory(DataFactory, PendingUploadCache, ConnectivityChecker, ObjectStorageService,
 * EnvelopeEncryptionService)}), {@link #upload} moves a file's content out of {@link
 * DataFactory#register}'s own entity JSON and into {@code objectStorageService} first (see {@link
 * #prepareForPersistence}), and {@link #download}/{@link #findById}/{@link #getEntities}
 * transparently resolve an S3-backed file's content back before verifying its checksum. {@code
 * null} (the default, via the three-argument constructor) behaves exactly as before this feature
 * existed - every file stays inline in {@link DataFactory}'s own s3storage.
 *
 * <p>{@code *Async} variants need no override - they're inherited from
 * {@link FileFactory}, implemented generically on top of the sync methods
 * here, dispatched onto {@link MultiTaskingFactory}'s virtual-thread
 * executor.
 */
public final class DefaultFileFactory extends FileFactory {

    /** The {@link DataFactory} {@link StoredFile}s are persisted through. */
    private final DataFactory dataFactory;
    /** Where files are queued while connectivity is unavailable; see {@link #getPendingUploadCache()}. */
    private final PendingUploadCache pendingUploadCache;
    /** Reports whether connectivity is currently available; see {@link #getConnectivityChecker()}. */
    private final ConnectivityChecker connectivityChecker;
    /** Backs an S3-backed {@link StoredFile}'s content, or {@code null} if this deployment hasn't opted into it - see this class's own Javadoc. */
    private final ObjectStorageService objectStorageService;
    /** Encrypts/decrypts a file's raw content bytes for {@link #objectStorageService} - {@code null} iff {@link #objectStorageService} is. */
    private final StoredFileContentChannel contentChannel;

    /**
     * Defaults {@link #getPendingUploadCache()} to a fresh {@link InMemoryPendingUploadCache}
     * and {@link #getConnectivityChecker()} to a fresh {@link InternetConnectivityChecker}.
     * S3-backed content is not configured - every file stays inline.
     *
     * @param dataFactory the {@link DataFactory} {@link StoredFile}s are persisted through
     * @throws NullPointerException if {@code dataFactory} is {@code null}
     */
    public DefaultFileFactory(@NotNull final DataFactory dataFactory) {
        this(dataFactory, new InMemoryPendingUploadCache(), new InternetConnectivityChecker());
    }

    /**
     * S3-backed content is not configured - every file stays inline, exactly as {@link
     * #DefaultFileFactory(DataFactory)} defaults it.
     *
     * @param dataFactory the {@link DataFactory} {@link StoredFile}s are persisted through
     * @param pendingUploadCache where files are queued while connectivity is unavailable
     * @param connectivityChecker reports whether connectivity is currently available
     * @throws NullPointerException if any argument is {@code null}
     */
    public DefaultFileFactory(@NotNull final DataFactory dataFactory, @NotNull final PendingUploadCache pendingUploadCache,
                               @NotNull final ConnectivityChecker connectivityChecker) {
        this(dataFactory, pendingUploadCache, connectivityChecker, null, null);
    }

    /**
     * @param dataFactory the {@link DataFactory} {@link StoredFile}s are persisted through
     * @param pendingUploadCache where files are queued while connectivity is unavailable
     * @param connectivityChecker reports whether connectivity is currently available
     * @param objectStorageService backs an S3-backed file's content, or {@code null} to keep every
     *     file inline (this deployment's default, opted out of S3-backed s3storage)
     * @param envelopeEncryptionService encrypts/decrypts a file's raw content bytes for {@code
     *     objectStorageService} - required (non-{@code null}) iff {@code objectStorageService} is
     *     itself non-{@code null}
     * @throws NullPointerException if {@code dataFactory}/{@code pendingUploadCache}/{@code
     *     connectivityChecker} is {@code null}, or if {@code objectStorageService} is non-{@code
     *     null} while {@code envelopeEncryptionService} is {@code null}
     */
    public DefaultFileFactory(@NotNull final DataFactory dataFactory, @NotNull final PendingUploadCache pendingUploadCache,
                               @NotNull final ConnectivityChecker connectivityChecker,
                               @Nullable final ObjectStorageService objectStorageService,
                               @Nullable final EnvelopeEncryptionService envelopeEncryptionService) {
        this.dataFactory = Asserts.requireNonNull(dataFactory, "@DefaultFileFactory: dataFactory cannot be null");
        this.pendingUploadCache = Asserts.requireNonNull(pendingUploadCache, "@DefaultFileFactory: pendingUploadCache cannot be null");
        this.connectivityChecker = Asserts.requireNonNull(connectivityChecker, "@DefaultFileFactory: connectivityChecker cannot be null");
        this.objectStorageService = objectStorageService;
        this.contentChannel = objectStorageService == null ? null : new StoredFileContentChannel(
                Asserts.requireNonNull(envelopeEncryptionService,
                        "@DefaultFileFactory: envelopeEncryptionService cannot be null when objectStorageService is set")
        );
    }

    /**
     * Delegates to {@link DataFactory#register(Serialized)}, deferring to {@link
     * #pendingUploadCache} while offline. If S3-backed s3storage is configured, {@code file}'s
     * content is moved there first via {@link #prepareForPersistence} - a failure at that step
     * (an {@link ObjectStorageException} or {@link KeyWrapException}) propagates directly, not
     * queued for retry, matching this codebase's "a genuine infrastructure problem should fail
     * loudly, not masquerade as success" convention (see {@code CloudBootstrap}'s own {@code
     * ALWAYS_AVAILABLE_CONNECTIVITY_CHECKER} Javadoc for the precedent).
     */
    @Override
    public void upload(@NotNull final StoredFile file) throws DatabaseClientException, KeyWrapException {
        if (!this.connectivityChecker.isAvailable()) {
            this.pendingUploadCache.enqueue(file);
            recordMetric(MetricsRecorder::recordUploadQueued);
            return;
        }

        final StoredFile toRegister = prepareForPersistence(file);

        try {
            this.dataFactory.register(toRegister);
            recordMetric(MetricsRecorder::recordUploadSuccess);
        } catch (final DatabaseClientException uploadFailed) {
            if (this.connectivityChecker.isAvailable()) {
                if (toRegister != file) {
                    // the S3 write above succeeded but the database write didn't - clean up the
                    // now-orphaned object rather than leaving it behind forever (best-effort,
                    // never masks the original DatabaseClientException).
                    deleteObjectQuietly(file.fileId());
                }
                recordMetric(MetricsRecorder::recordUploadFailure);
                throw uploadFailed;
            }
            // Re-queue the original, still content-carrying file (not toRegister) - PendingUploadScheduler
            // retries via prepareForPersistence again later, which simply overwrites the same S3 key.
            this.pendingUploadCache.enqueue(file);
            recordMetric(MetricsRecorder::recordUploadQueued);
        }
    }

    /**
     * Delegates to {@link DataFactory#register(Serialized...)}, deferring to {@link
     * #pendingUploadCache} while offline - see {@link #upload(StoredFile)}'s own Javadoc for how
     * S3-backed s3storage/failure handling applies to each file in the batch.
     */
    @Override
    public void upload(@NotNull final StoredFile... files) throws DatabaseClientException, KeyWrapException {
        if (!this.connectivityChecker.isAvailable()) {
            this.pendingUploadCache.enqueue(files);
            recordMetric(MetricsRecorder::recordUploadQueued, files.length);
            return;
        }

        final StoredFile[] toRegister = new StoredFile[files.length];
        for (int i = 0; i < files.length; i++) {
            toRegister[i] = prepareForPersistence(files[i]);
        }

        try {
            this.dataFactory.register(toRegister);
            recordMetric(MetricsRecorder::recordUploadSuccess, files.length);
        } catch (final DatabaseClientException uploadFailed) {
            if (this.connectivityChecker.isAvailable()) {
                for (int i = 0; i < files.length; i++) {
                    if (toRegister[i] != files[i]) deleteObjectQuietly(files[i].fileId());
                }
                recordMetric(MetricsRecorder::recordUploadFailure, files.length);
                throw uploadFailed;
            }
            // some files in the batch may already have been stored successfully -
            // re-queueing all of them is harmless, since a retried upload is the
            // same insert-or-update StoredFile#fileId() operation either way.
            this.pendingUploadCache.enqueue(files);
            recordMetric(MetricsRecorder::recordUploadQueued, files.length);
        }
    }

    /**
     * If S3-backed s3storage is configured ({@link #objectStorageService} non-{@code null}),
     * chunk-encrypts {@code file}'s raw content bytes ({@link StoredFile#rawStorableBytes()}) via
     * {@link StoredFileContentChannel#sendStream}, streams the result to {@link
     * #objectStorageService} under {@code file.fileId()} (the ciphertext is never held in memory
     * as one array), and returns a metadata-only copy ({@link
     * StoredFile#withObjectStorageKey(String)}) ready for {@link DataFactory#register} - otherwise
     * returns {@code file} itself unchanged.
     *
     * <p>Exposed (not just called internally by {@link #upload}) so {@code PendingUploadScheduler}
     * can apply the exact same sequence when retrying a queued file directly via {@code
     * DataFactory#registerAsync} - deliberately not through {@link #upload} itself, whose own
     * offline-recovery branch would silently re-queue a still-failing retry right back into the
     * cache the scheduler is draining.
     *
     * @param file the file about to be persisted
     * @return {@code file} itself if S3-backed s3storage isn't configured, otherwise a metadata-only copy
     * @throws NullPointerException if {@code file} is {@code null}
     * @throws KeyWrapException if encrypting the content for object s3storage fails
     * @throws ObjectStorageException if the object-s3storage write itself fails
     */
    @NotNull
    public StoredFile prepareForPersistence(@NotNull final StoredFile file) throws KeyWrapException {
        Asserts.requireNonNull(file, "@DefaultFileFactory.prepareForPersistence: file cannot be null");
        if (file.isS3Backed()) {
            // Already S3-backed (e.g. a direct-transfer file built by CloudUserService#completePresignedUpload,
            // whose content already lives in the object store and was never handed to this class at
            // all) - nothing left for this method to do. Defensive: no production call site actually
            // reaches this with such a file today, since that path registers via DataFactory directly.
            return file;
        }
        if (this.objectStorageService == null) {
            return file;
        }
        // Streamed, not one-shot: sendStream chunk-encrypts on the fly, so the ciphertext is
        // never materialized as a second full-size array next to rawBytes - see
        // StoredFileContentChannel's Javadoc on the two coexisting stored layouts.
        final byte[] rawBytes = file.rawStorableBytes();
        final StoredFileContentChannel.StreamingPayload payload = this.contentChannel.sendStream(
                file.fileId(), new ByteArrayInputStream(rawBytes), rawBytes.length
        );
        this.objectStorageService.putObject(file.fileId(), payload.content(), payload.contentLength());
        return file.withObjectStorageKey(file.fileId());
    }

    /**
     * Best-effort delete of {@code fileId}'s object from {@link #objectStorageService} - a failure
     * is logged (via {@link CloudDriver#getLogger()}) rather than thrown, since every caller of
     * this method has already committed to a different outcome (an orphan-cleanup after a failed
     * database write, or the second half of a real {@link #delete(String)} whose database row is
     * already gone either way). A no-op if {@link #objectStorageService} isn't configured.
     *
     * @param fileId the id of the file whose object should be removed
     */
    private void deleteObjectQuietly(final String fileId) {
        if (this.objectStorageService == null) {
            return;
        }
        try {
            this.objectStorageService.deleteObject(fileId);
        } catch (final ObjectStorageException cleanupFailed) {
            CloudDriver.getInstance().getLogger().log(
                    Level.WARNING, "@DefaultFileFactory: failed to delete object s3storage content for file '" + fileId + "'", cleanupFailed
            );
        }
    }

    /** Same as {@link #recordMetric(Consumer, int)} with {@code times = 1}, for the single-file {@link #upload(StoredFile)}. */
    private static void recordMetric(@NotNull final Consumer<MetricsRecorder> action) {
        recordMetric(action, 1);
    }

    /**
     * Forwards one metric event to {@link CloudDriver#getInstance()}'s {@link MetricsRecorder},
     * {@code times} times, if {@code cloud-driver-extensions-metrics} has published one - a no-op
     * otherwise (e.g. this deployment doesn't run that extension). Never throws: a
     * missing/misbehaving metrics sink must never affect a real upload, matching {@link
     * MetricsRecorder}'s own "must never throw" contract, enforced here defensively too in case an
     * implementation ever violates it.
     *
     * @param action the {@link MetricsRecorder} method to invoke, e.g. {@code
     *     MetricsRecorder::recordUploadSuccess}
     * @param times how many times to invoke {@code action} - the batch {@link #upload(StoredFile...)}
     *     counts every file in the batch as its own event, not the batch call itself as one event
     */
    private static void recordMetric(@NotNull final Consumer<MetricsRecorder> action, final int times) {
        try {
            final MetricsRecorder recorder = CloudDriver.getInstance().getServiceContainer().getMetricsRecorder();
            if (recorder == null) return;
            for (int i = 0; i < times; i++) action.accept(recorder);
        } catch (final RuntimeException ignored) {
            // Best-effort only - see this method's own Javadoc.
        }
    }

    /**
     * The {@link PendingUploadCache} {@link #upload} defers files into while
     * offline - exposed so external infrastructure (e.g. {@code
     * PendingUploadScheduler}) can be wired to drain the very same cache.
     */
    @NotNull
    public PendingUploadCache getPendingUploadCache() {
        return this.pendingUploadCache;
    }

    /**
     * The {@link ConnectivityChecker} {@link #upload} checks before
     * attempting a database call - exposed so external infrastructure can
     * query the very same instance rather than constructing its own.
     */
    @NotNull
    public ConnectivityChecker getConnectivityChecker() {
        return this.connectivityChecker;
    }

    /** Fetches via {@link #dataFactory}, resolves deduplicated/S3-backed content if either applies (see {@link #resolveContent}), and checks the result's checksum. */
    @NotNull
    @Override
    public StoredFile download(@NotNull final String fileId)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        return verifyIntegrity(resolveContent(this.dataFactory.fetch(fileId, StoredFile.class)));
    }

    /** Fetches via {@link #dataFactory} and resolves/checks every result concurrently - see {@link #verifyAll}. */
    @NotNull
    @Override
    public List<StoredFile> download(@NotNull final String[] fileIds)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        return verifyAll(this.dataFactory.fetch(fileIds, StoredFile.class));
    }

    /** Looks up via {@link #dataFactory}, resolves deduplicated/S3-backed content if either applies, and checks the result's checksum, if present. */
    @NotNull
    @Override
    public Optional<StoredFile> findById(@NotNull final String fileId)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        final Optional<StoredFile> file = this.dataFactory.findById(fileId, StoredFile.class);
        if (file.isPresent()) {
            return Optional.of(verifyIntegrity(resolveContent(file.get())));
        }
        return file;
    }

    /** Lists via {@link #dataFactory} and resolves/checks every result concurrently - see {@link #verifyAll}. */
    @NotNull
    @Override
    public List<StoredFile> getEntities()
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        return verifyAll(this.dataFactory.getEntities(StoredFile.class));
    }

    /**
     * Content-free fast path over the generic {@link FileFactory#getEntitiesMetadata()}: lists
     * the rows via {@link #dataFactory} and answers each file's size from the row alone ({@link
     * StoredFile#sizeBytesIfKnown()}) - no object-store fetch, no per-file content decryption, no
     * checksum verification. <b>The fix (2026-09-10) for the terminal's {@code stats} command
     * taking minutes:</b> it only ever needed a row count and a size sum, but reached them through
     * {@link #getEntities()}, which since S3-backed content went live meant re-downloading,
     * KMS-unwrapping, decrypting, and checksum-verifying the entire corpus on every invocation.
     *
     * <p>The only rows that can't answer locally are app-encrypted S3-backed rows persisted before
     * {@link StoredFile#withObjectStorageKey(String)} started recording {@code declaredSizeBytes}
     * (2026-09-10). Those legacy rows are resolved the old way - through {@link #verifyAll}'s
     * bounded pipeline - and their learned size is then <b>backfilled</b> onto the row ({@link
     * StoredFile#withDeclaredSizeBytes(long)}, best-effort, see {@link #backfillDeclaredSize}),
     * so each such row pays the resolve at most once ever, after which it takes the fast path
     * like every other row. A corpus of only legacy rows therefore makes the first call as slow
     * as before and every later call fast.
     */
    @NotNull
    @Override
    public List<FileMetadata> getEntitiesMetadata()
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException, FileIntegrityException {
        final List<StoredFile> rows = this.dataFactory.getEntities(StoredFile.class);

        final List<FileMetadata> result = new ArrayList<>(rows.size());
        final List<StoredFile> legacyRowsWithoutSize = new ArrayList<>();
        for (final StoredFile row : rows) {
            final Long knownSize = row.sizeBytesIfKnown();
            if (knownSize != null) {
                result.add(new FileMetadata(row.fileId(), row.fileName(), row.contentType(),
                        knownSize, row.checksum(), row.createdAt(), row.updatedAt()));
            } else {
                legacyRowsWithoutSize.add(row);
            }
        }

        if (!legacyRowsWithoutSize.isEmpty()) {
            final List<StoredFile> resolved = verifyAll(legacyRowsWithoutSize);
            for (int i = 0; i < resolved.size(); i++) {
                final StoredFile resolvedFile = resolved.get(i);
                result.add(resolvedFile.metadata());
                backfillDeclaredSize(legacyRowsWithoutSize.get(i), resolvedFile.sizeBytes());
            }
        }
        return result;
    }

    /**
     * Persists {@code sizeBytes} onto {@code legacyRow} (via {@link
     * StoredFile#withDeclaredSizeBytes(long)} on the original, unhydrated row - so the update
     * stays metadata-only, no content re-serialized) so future {@link #getEntitiesMetadata()}
     * calls never have to resolve this row's content again. Best-effort: a failure is logged
     * rather than thrown, since the caller already has the answer it needed - the backfill is an
     * optimization for next time, never worth failing this call over.
     *
     * @param legacyRow the row as read from {@link #dataFactory}, still without a recorded size
     * @param sizeBytes the row's now-known original, uncompressed content size in bytes
     */
    private void backfillDeclaredSize(final StoredFile legacyRow, final long sizeBytes) {
        try {
            this.dataFactory.update(legacyRow.withDeclaredSizeBytes(sizeBytes));
        } catch (final Exception backfillFailed) {
            CloudDriver.getInstance().getLogger().log(
                    Level.WARNING, "@DefaultFileFactory: failed to backfill declaredSizeBytes for file '" + legacyRow.fileId() + "'", backfillFailed
            );
        }
    }

    /**
     * Delegates to {@link DataFactory#delete(String, Class)}, then - if S3-backed s3storage is
     * configured - best-effort deletes {@code fileId}'s object too ({@link
     * #deleteObjectQuietly}). Order matters: the database row goes first, the object second - an
     * orphaned S3 object is a cheap cleanup problem, a database row pointing at a deleted S3
     * object is a broken download. Unconditional rather
     * than checked against {@code isS3Backed()} first - {@code ObjectStorageService#deleteObject}
     * is itself a no-op for a file that was never S3-backed, so the extra check would just cost a
     * round trip for nothing.
     */
    @Override
    public void delete(@NotNull final String fileId) throws DatabaseClientException {
        this.dataFactory.delete(fileId, StoredFile.class);
        deleteObjectQuietly(fileId);
    }

    /** Same as {@link #delete(String)}, batched - see its own Javadoc. */
    @Override
    public void delete(@NotNull final String[] fileIds) throws DatabaseClientException {
        this.dataFactory.delete(fileIds, StoredFile.class);
        for (final String fileId : fileIds) {
            deleteObjectQuietly(fileId);
        }
    }

    /**
     * Delegates to {@link DataFactory#clear}. <b>Does not purge S3-backed objects</b> - out of
     * scope for this class's S3-backed-content support (which only covers {@link #upload}/
     * {@link #download}/{@link #findById}/{@link #getEntities}/{@link #delete}), a known,
     * deliberately unaddressed gap for any caller reaching this method directly. Flagged here
     * rather than silently expanding this change's scope to also purge a whole bucket. Note this
     * gap does <b>not</b> apply to {@code DefaultCloudDriver#reset()} (added 2026-09-08) - that
     * method never actually called this one (it always went straight to {@code
     * DataFactory#deleteSectionAsync}), and now does its own S3 purge first, see its own Javadoc.
     */
    @Override
    public void clear() {
        this.dataFactory.clear(StoredFile.class);
    }

    /** Delegates to {@link DataFactory#deleteSection}. Same S3-purge gap as {@link #clear()} - see its own Javadoc. */
    @Override
    public void deleteSection() {
        this.dataFactory.deleteSection(StoredFile.class);
    }

    /**
     * Resolves {@code file}'s content from {@link #objectStorageService} if it is {@link
     * StoredFile#isS3Backed()}, otherwise returns it unchanged - for each such file, streams via
     * {@code objectStorageService.getObjectStream(...)} through {@link
     * StoredFileContentChannel#receiveFully} (decrypting chunk by chunk, either stored layout)
     * and attaches the result via {@code withResolvedContent} before {@code verifyIntegrity} runs.
     *
     * <p>Branches on {@link StoredFile#isDirectTransfer()}: a direct-transfer file was never
     * DEFLATE-compressed, so no {@link StoredFile#decompressIfNeeded(byte[])} step ever applies.
     * If it {@link StoredFile#isContentKeyProtected()}, the uploading client encrypted the object
     * itself in the standard chunked streaming layout, so {@link
     * StoredFileContentChannel#receiveFully} decrypts it like any server-encrypted object; a
     * legacy (pre-client-side-encryption) direct transfer is plaintext in the store (decrypted
     * transparently by S3's own server-side encryption on the way out) and is handed straight to
     * {@link StoredFile#withResolvedContent(byte[])}.
     *
     * @param file the file, as read back from {@link #dataFactory} - possibly S3-backed
     * @return {@code file} itself if not S3-backed, otherwise a hydrated copy with content resolved
     * @throws IllegalStateException if {@code file} is S3-backed but {@link #objectStorageService} isn't configured
     * @throws ObjectStorageException if fetching the object fails
     * @throws KeyWrapException if unwrapping the content's data-encryption key fails
     * @throws AuthenticationFailedException if the content's authentication tag verification fails
     */
    private StoredFile resolveFromObjectStorage(final StoredFile file) throws KeyWrapException, AuthenticationFailedException {
        if (!file.isS3Backed()) {
            return file;
        }
        if (this.objectStorageService == null) {
            throw new IllegalStateException(
                    "@DefaultFileFactory: file '" + file.fileId() + "' is S3-backed (object key '" + file.objectStorageKey()
                            + "') but this DefaultFileFactory instance has no ObjectStorageService configured"
            );
        }
        if (file.isDirectTransfer()) {
            if (file.isContentKeyProtected()) {
                // Client-encrypted direct transfer: the object is the standard chunked streaming
                // layout (the client wrote the server-issued header + chunk frames), so the very
                // same receive path decrypts it - the one difference from the server-encrypted
                // branch below is that a direct transfer is never DEFLATE-compressed, so no
                // decompressIfNeeded step follows.
                final byte[] plaintext;
                try (InputStream storedContent = this.objectStorageService.getObjectStream(file.objectStorageKey())) {
                    plaintext = this.contentChannel.receiveFully(file.fileId(), storedContent);
                } catch (final IOException e) {
                    throw new ObjectStorageException(
                            "@DefaultFileFactory: failed reading object s3storage content for file '" + file.fileId() + "'", e
                    );
                }
                return file.withResolvedContent(plaintext);
            }
            final byte[] plaintext = this.objectStorageService.getObject(file.objectStorageKey());
            return file.withResolvedContent(plaintext);
        }
        // Streamed, not getObject: receiveFully decrypts chunk by chunk straight off the S3
        // stream, so only the plaintext is ever materialized - never the full ciphertext too.
        final byte[] rawBytes;
        try (InputStream storedContent = this.objectStorageService.getObjectStream(file.objectStorageKey())) {
            rawBytes = this.contentChannel.receiveFully(file.fileId(), storedContent);
        } catch (final IOException e) {
            throw new ObjectStorageException(
                    "@DefaultFileFactory: failed reading object s3storage content for file '" + file.fileId() + "'", e
            );
        }
        return file.withResolvedContent(file.decompressIfNeeded(rawBytes));
    }

    /**
     * Resolves {@code file}'s content end to end - first as a per-account deduplication alias (see
     * {@link #resolveDedupAlias}), then as S3-backed content (see
     * {@link #resolveFromObjectStorage}) - either step is a no-op if it doesn't apply to {@code
     * file}. A file is never both at once (an alias carries no {@link StoredFile#objectStorageKey()}
     * of its own), but resolving the alias first is what lets {@link #resolveFromObjectStorage}'s
     * own {@code isS3Backed()} check on the *canonical* file's content still apply correctly if the
     * canonical itself happens to be S3-backed.
     *
     * @param file the file, as read back from {@link #dataFactory} - possibly a dedup alias and/or S3-backed
     * @return {@code file} itself if neither applies, otherwise a hydrated copy with content resolved
     */
    private StoredFile resolveContent(final StoredFile file)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        return resolveFromObjectStorage(resolveDedupAlias(file));
    }

    /**
     * Resolves {@code file}'s content from the canonical file it aliases
     * if {@link StoredFile#isDedupAlias()}, otherwise returns it unchanged. The canonical
     * file's own content is resolved the same way any other read of it would be ({@link
     * #resolveFromObjectStorage}, in case the canonical itself is S3-backed) before being handed to
     * {@link StoredFile#withResolvedContent(byte[])} - a dedup alias is never itself chained to
     * another alias (see {@link StoredFile#dedupOfFileId()}'s own Javadoc), so this never recurses.
     *
     * @param file the file, as read back from {@link #dataFactory} - possibly a deduplication alias
     * @return {@code file} itself if not an alias, otherwise a hydrated copy with content resolved
     * @throws DatabaseClientException if fetching the canonical file fails
     * @throws KeyWrapException if unwrapping the canonical content's data-encryption key fails
     * @throws AuthenticationFailedException if the canonical content's authentication tag verification fails
     */
    private StoredFile resolveDedupAlias(final StoredFile file)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        if (!file.isDedupAlias()) {
            return file;
        }
        final StoredFile canonical = resolveFromObjectStorage(this.dataFactory.fetch(file.dedupOfFileId(), StoredFile.class));
        return file.withResolvedContent(canonical.content());
    }

    /**
     * Same as {@link #resolveContent(StoredFile)}, but rethrows a checked failure wrapped in a
     * {@link CompletionException} so it can run inside a {@link CompletableFuture} task by {@link
     * #verifyAll}.
     */
    private StoredFile resolveContentUnchecked(final StoredFile file) {
        try {
            return resolveContent(file);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Caps how many files {@link #verifyAll} resolves-from-object-s3storage/decodes/decompresses at
     * once - each in-flight task holds its own decoded (and, for a compressed file, decompressed)
     * {@code byte[]} copy of the file's full content in memory for its duration (see {@link
     * #verifyAll}'s own Javadoc for why), so an unbounded one-task-per-file fan-out spikes peak
     * memory with the *total* size of whichever files happen to finish resolving at the same
     * moment - unrelated to how many files are in the batch overall. Also bounds how many
     * concurrent S3 GETs {@link #resolveFromObjectStorage} can have in flight at once, for the
     * same reason. Matches {@code cloud-driver-platforms-rest}'s own {@code
     * ApiClient.DEFAULT_MAX_CONCURRENT_TRANSFERS} cap (8), the same "bound concurrent per-file
     * work, don't just fan out unbounded" convention applied there for uploads/downloads.
     */
    private static final int MAX_CONCURRENT_VERIFICATIONS = 8;

    /**
     * Resolves (see {@link #resolveFromObjectStorage}) and verifies every file in {@code files}
     * via {@link #verifyIntegrity}, concurrently but capped at {@link
     * #MAX_CONCURRENT_VERIFICATIONS} in flight at once via a {@link Semaphore} - not unbounded,
     * one task per file, the way this used to run.
     *
     * <p><b>Fixed a real, server-crashing {@code OutOfMemoryError} (2026-09-02):</b> every file
     * already sits in {@code files} fully decrypted (its base64-encoded content included) by the
     * time this method runs - {@link DataFactory#getEntities}/{@code #fetch} already resolved that
     * much. {@link StoredFile#verifyChecksum()} additionally base64-decodes (and, for a compressed
     * file, DEFLATE-inflates) that content into a second, separate, typically larger {@code byte[]}
     * - cached on the {@link StoredFile} instance afterward, but very much a fresh, real allocation
     * at the moment this call happens. Dispatching one such decode per file, for every file in the
     * batch, with no concurrency limit at all, meant the terminal's {@code stats} command - which
     * calls {@link de.lino.cloud.api.factory.FileFactory#getEntities()} to sum every uploaded
     * file's size - decoded the *entire* account's file corpus simultaneously, all at once, the
     * moment enough real file content existed in the database (confirmed live: extracting a ~700 MB
     * zip archive in the desktop app grew the corpus enough to trip this). Capping concurrency here
     * bounds how many of those decode buffers can exist at the same instant, regardless of how many
     * files the batch contains overall - it does not reduce the *total* memory `verifyAll` will
     * eventually touch (every file is still decoded, just not all simultaneously), which is why
     * {@code StatisticsCommand} was separately fixed to stop calling this path twice per invocation.
     */
    private List<StoredFile> verifyAll(final List<StoredFile> files)
            throws FileIntegrityException, KeyWrapException, AuthenticationFailedException, DatabaseClientException {
        final Semaphore concurrencyLimit = new Semaphore(MAX_CONCURRENT_VERIFICATIONS);
        final List<CompletableFuture<StoredFile>> resolutions = files.stream()
                .map(file -> MultiTaskingFactory.getInstance().supplyAsync(() -> resolveAndVerifyBounded(file, concurrencyLimit)))
                .toList();
        return joinAllVerifications(resolutions);
    }

    /**
     * Acquires {@code concurrencyLimit} before resolving-and-verifying {@code file} (via {@link
     * #resolveContentUnchecked}/{@link #verifyIntegrityUnchecked}), releasing it afterward
     * regardless of outcome - see {@link #verifyAll}'s own Javadoc.
     *
     * @throws CompletionException wrapping an {@link InterruptedException} if interrupted while waiting for a permit
     */
    private StoredFile resolveAndVerifyBounded(final StoredFile file, final Semaphore concurrencyLimit) {
        try {
            concurrencyLimit.acquire();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
        try {
            return verifyIntegrityUnchecked(resolveContentUnchecked(file));
        } finally {
            concurrencyLimit.release();
        }
    }

    /**
     * Checks {@code file}'s content against its recorded checksum.
     *
     * @throws FileIntegrityException if the content does not match
     */
    private static StoredFile verifyIntegrity(final StoredFile file) throws FileIntegrityException {
        if (!file.verifyChecksum()) {
            throw new FileIntegrityException(
                    "@DefaultFileFactory: checksum mismatch for file '" + file.primaryKey()
                            + "' - decrypted content does not match its recorded " + file.checksum().algorithm() + " checksum"
            );
        }
        return file;
    }

    /**
     * Same as {@link #verifyIntegrity(StoredFile)}, but rethrows a checksum
     * mismatch wrapped in a {@link CompletionException} so it can be run inside
     * a {@link CompletableFuture} task by {@link #verifyAll}.
     *
     * @param file the file to verify
     * @return {@code file} itself, once verified
     * @throws CompletionException wrapping a {@link FileIntegrityException} if the content does not match
     */
    private static StoredFile verifyIntegrityUnchecked(final StoredFile file) {
        try {
            return verifyIntegrity(file);
        } catch (final FileIntegrityException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Waits for every task in {@code futures} to complete and collects their results, unwrapping
     * the first failure encountered from its {@link CompletionException} wrapper.
     *
     * @param futures the in-flight resolve-and-verify tasks, as produced by {@link #verifyAll}
     * @return every task's resolved, verified {@link StoredFile}, in the same order as {@code futures}
     * @throws FileIntegrityException if any task failed a checksum check
     * @throws KeyWrapException if any task failed to unwrap a content data-encryption key
     * @throws AuthenticationFailedException if any task failed content authentication tag verification
     * @throws DatabaseClientException if any task failed to fetch a deduplication alias's canonical file
     * @throws RuntimeException the original unchecked cause, if a task failed with something else
     * @throws IllegalStateException if a task failed with a non-{@link RuntimeException} cause
     */
    private static List<StoredFile> joinAllVerifications(final List<CompletableFuture<StoredFile>> futures)
            throws FileIntegrityException, KeyWrapException, AuthenticationFailedException, DatabaseClientException {
        try {
            return futures.stream().map(CompletableFuture::join).toList();
        } catch (final CompletionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof FileIntegrityException fileIntegrityException) {
                throw fileIntegrityException;
            }
            if (cause instanceof KeyWrapException keyWrapException) {
                throw keyWrapException;
            }
            if (cause instanceof AuthenticationFailedException authenticationFailedException) {
                throw authenticationFailedException;
            }
            if (cause instanceof DatabaseClientException databaseClientException) {
                throw databaseClientException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("@DefaultFileFactory: unexpected failure while verifying integrity", cause);
        }
    }

}

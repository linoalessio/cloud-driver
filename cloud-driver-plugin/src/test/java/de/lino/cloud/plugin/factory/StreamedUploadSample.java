package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.ScanStatus;
import de.lino.cloud.api.file.meta.FileChecksum;
import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.hash.HashAlgorithm;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.plugin.file.InMemoryPendingUploadCache;
import de.lino.cloud.plugin.s3storage.StoredFileContentChannel;
import de.lino.cloud.plugin.security.crypto.AesGcmEncryptionService;
import de.lino.cloud.plugin.security.crypto.ChunkedAesGcmStreamingService;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.develop.DataEncryptionKeyGenerator;
import de.lino.cloud.plugin.security.keys.develop.InMemoryKeyEncryptionService;
import de.lino.database.database.entity.Serialized;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Standalone, runnable worked example (not an {@code mvn test} target - see "Testing" in {@code
 * CLAUDE.md}) exercising the content-file-backed streaming upload path added for the roadmap's
 * Phase 0 V1 fix, end to end against an in-memory object store and {@link
 * InMemoryKeyEncryptionService} (no AWS/KMS/S3 needed), printing pass/fail per check to stdout
 * and exiting non-zero on any failure.
 *
 * <ul>
 *     <li>{@link FileChecksum#of(HashAlgorithm, InputStream)} matches the in-memory computation</li>
 *     <li>{@link StoredFile#createFromContentFile} produces the expected transient shape
 *     (uncompressed, sized, not yet S3-backed), and {@code withScanStatus} preserves the source</li>
 *     <li>{@link DefaultFileFactory#prepareForPersistence} streams a content-file-backed file
 *     into the object store (v2 chunked layout), and the stored object decrypts back to the
 *     original bytes with a passing checksum</li>
 *     <li>with no object store configured, the same call falls back to a correct inline copy</li>
 *     <li>the offline-enqueue path materializes the content before queueing, so the queued file
 *     outlives its scratch source</li>
 * </ul>
 */
public final class StreamedUploadSample {

    /** Tracks whether every check so far passed; {@link #main} exits non-zero if any failed. */
    private static boolean allPassed = true;

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private StreamedUploadSample() {
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
        final StoredFileContentChannel channel = new StoredFileContentChannel(envelopeService);

        // Multi-chunk plaintext (several 64 KiB chunks plus a partial final one).
        final byte[] plaintext = new byte[5 * 64 * 1024 + 12_345];
        new SecureRandom().nextBytes(plaintext);

        final Path scratchFile = Files.createTempFile("streamed-upload-sample-", ".tmp");
        try {
            Files.write(scratchFile, plaintext);

            // --- streaming checksum matches the in-memory one ---
            final FileChecksum streamed;
            try (InputStream content = Files.newInputStream(scratchFile)) {
                streamed = FileChecksum.of(HashAlgorithm.SHA_256, content);
            }
            check("streaming checksum matches in-memory checksum",
                    streamed.equals(FileChecksum.of(HashAlgorithm.SHA_256, plaintext)));

            // --- the transient content-file-backed shape ---
            StoredFile file = StoredFile.createFromContentFile(
                    "sample-streamed-file", "sample.bin", plaintext.length, streamed, scratchFile);
            check("content-file-backed shape", file.isContentFileBacked() && !file.isS3Backed()
                    && !file.isCompressed() && file.sizeBytes() == plaintext.length);
            file = file.withScanStatus(ScanStatus.PENDING);
            check("withScanStatus preserves the content source", file.isContentFileBacked());

            // --- streamed to the object store, decrypts back byte-identical ---
            final InMemoryObjectStorageService objectStorage = new InMemoryObjectStorageService();
            final DefaultFileFactory factory = new DefaultFileFactory(
                    new UnusedDataFactory(), new InMemoryPendingUploadCache(), () -> true, objectStorage, envelopeService);
            final StoredFile persisted = factory.prepareForPersistence(file);
            check("prepareForPersistence returns the S3-backed shape",
                    persisted.isS3Backed() && !persisted.isContentFileBacked()
                            && persisted.sizeBytes() == plaintext.length);
            final byte[] decrypted = channel.receiveFully(
                    file.fileId(), new ByteArrayInputStream(objectStorage.storedBytes(file.fileId())));
            check("stored object decrypts back to the original bytes", Arrays.equals(plaintext, decrypted));
            check("hydrated copy passes checksum verification",
                    persisted.withResolvedContent(decrypted).verifyChecksum());

            // --- inline fallback without an object store ---
            final DefaultFileFactory inlineFactory = new DefaultFileFactory(
                    new UnusedDataFactory(), new InMemoryPendingUploadCache(), () -> true, null, null);
            final StoredFile inline = inlineFactory.prepareForPersistence(file);
            check("no-object-store fallback produces a correct inline copy",
                    !inline.isS3Backed() && !inline.isContentFileBacked()
                            && Arrays.equals(plaintext, inline.content()) && inline.verifyChecksum()
                            && inline.scanStatus() == ScanStatus.PENDING);

            // --- offline enqueue materializes before the scratch file goes away ---
            final InMemoryPendingUploadCache offlineQueue = new InMemoryPendingUploadCache();
            final DefaultFileFactory offlineFactory = new DefaultFileFactory(
                    new UnusedDataFactory(), offlineQueue, () -> false, objectStorage, envelopeService);
            offlineFactory.upload(file);
            final StoredFile queued = offlineQueue.snapshot().get(0);
            check("offline enqueue holds a materialized copy, independent of the scratch file",
                    !queued.isContentFileBacked() && Arrays.equals(plaintext, queued.content()));
        } finally {
            Files.deleteIfExists(scratchFile);
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

    /** Object store used by the sample: a plain in-memory map, no S3 anywhere. */
    private static final class InMemoryObjectStorageService implements ObjectStorageService {

        /** Every stored object, by its object key. */
        private final Map<String, byte[]> objects = new HashMap<>();

        /** The exact bytes stored under {@code objectKey}, for the sample's decrypt-back check. */
        byte[] storedBytes(final String objectKey) {
            return this.objects.get(objectKey);
        }

        @Override
        public void putObject(@NotNull final String objectKey, final byte[] content) {
            this.objects.put(objectKey, content.clone());
        }

        @Override
        public void putObject(@NotNull final String objectKey, @NotNull final InputStream content, final long contentLength) {
            try {
                final byte[] bytes = content.readAllBytes();
                if (bytes.length != contentLength) {
                    throw new ObjectStorageException("@InMemoryObjectStorageService: declared length " + contentLength
                            + " but the stream yielded " + bytes.length + " bytes");
                }
                this.objects.put(objectKey, bytes);
            } catch (final IOException e) {
                throw new ObjectStorageException("@InMemoryObjectStorageService: failed reading the content stream", e);
            }
        }

        @NotNull
        @Override
        public byte[] getObject(@NotNull final String objectKey) {
            final byte[] stored = this.objects.get(objectKey);
            if (stored == null) throw new ObjectStorageException("@InMemoryObjectStorageService: no object '" + objectKey + "'");
            return stored.clone();
        }

        @NotNull
        @Override
        public InputStream getObjectStream(@NotNull final String objectKey) {
            return new ByteArrayInputStream(getObject(objectKey));
        }

        @Override
        public void deleteObject(@NotNull final String objectKey) {
            this.objects.remove(objectKey);
        }

        @Override
        public boolean exists(@NotNull final String objectKey) {
            return this.objects.containsKey(objectKey);
        }

        @NotNull
        @Override
        public ObjectListing listObjects(@Nullable final String continuationToken, final int maxKeys) {
            return new ObjectListing(List.copyOf(this.objects.keySet()), null);
        }
    }

    /** {@link DataFactory} stub for wiring {@link DefaultFileFactory} - the sample never persists an entity. */
    private static final class UnusedDataFactory extends DataFactory {
        @Override
        public <T extends Serialized> void register(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @SafeVarargs
        @Override
        public final <T extends Serialized> void register(@NotNull final T... entities) throws DatabaseClientException, KeyWrapException {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public <T extends Serialized> void update(@NotNull final T entity) throws DatabaseClientException, KeyWrapException {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @SafeVarargs
        @Override
        public final <T extends Serialized> void update(@NotNull final T... entities) throws DatabaseClientException, KeyWrapException {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public <T extends Serialized> T fetch(@NotNull final String objectId, @NotNull final Class<T> type) {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public <T extends Serialized> List<T> fetch(@NotNull final String[] objectIds, @NotNull final Class<T> type) {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public <T extends Serialized> Optional<T> findById(@NotNull final String objectId, @NotNull final Class<T> type) {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public <T extends Serialized> List<T> getEntities(@NotNull final Class<T> type) {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @NotNull
        @Override
        public <T extends Serialized> List<T> getEntitiesByIndex(@NotNull final Class<T> type,
                                                                  @NotNull final String indexName, @NotNull final String indexKey) {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public <T extends Serialized> void delete(@NotNull final String objectId, @NotNull final Class<T> type) {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public <T extends Serialized> void delete(@NotNull final String[] objectIds, @NotNull final Class<T> type) {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public <T extends Serialized> void clear(@NotNull final Class<T> type) {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public <T extends Serialized> void deleteSection(@NotNull final Class<T> type) {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public <T extends Serialized> void reload(@NotNull final Class<T> type) {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException("not used by this sample");
        }
    }
}

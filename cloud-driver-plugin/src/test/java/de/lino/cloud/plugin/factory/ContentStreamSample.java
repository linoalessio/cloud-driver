package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.exception.FileIntegrityException;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Standalone, runnable worked example (not an {@code mvn test} target - see "Testing" in {@code
 * CLAUDE.md}) exercising {@link DefaultFileFactory#openContentStream}, the streaming counterpart
 * of {@code findById} that a consumer reading content sequentially (the malware scan, above all)
 * uses instead of materializing a whole file. Runs against an in-memory object store and {@link
 * InMemoryKeyEncryptionService} (no AWS/KMS/S3 needed), printing pass/fail per check to stdout and
 * exiting non-zero on any failure.
 *
 * <ul>
 *     <li>a server-encrypted, DEFLATE-compressed, S3-backed file streams back byte-identically -
 *     the inflate happens on the way out</li>
 *     <li>so does an uncompressed, multi-chunk one, decrypting chunk by chunk</li>
 *     <li>a client-encrypted direct transfer decrypts through the same chunked path</li>
 *     <li>a legacy plaintext direct transfer is streamed straight from the store, untouched -
 *     that object carries no layout tag of ours and must never reach the chunked reader</li>
 *     <li>a deduplication alias streams its canonical file's content</li>
 *     <li>an inline file (no object store configured) is served from the resolved entity</li>
 *     <li>an unknown file id answers empty rather than throwing</li>
 *     <li>content that does not match its recorded checksum fails at the end of the stream, as an
 *     {@code IOException} caused by {@link FileIntegrityException} - the streamed position of the
 *     check {@code findById} performs eagerly</li>
 * </ul>
 */
public final class ContentStreamSample {

    /** Chunk size used throughout the sample - small, so a few hundred KiB of plaintext already spans many chunks. */
    private static final int CHUNK_SIZE_BYTES = 64 * 1024;

    /** Tracks whether every check so far passed; {@link #main} exits non-zero if any failed. */
    private static boolean allPassed = true;

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private ContentStreamSample() {
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
                new ChunkedAesGcmStreamingService(CryptoAlgorithm.AES_256_GCM, CHUNK_SIZE_BYTES),
                new InMemoryKeyEncryptionService(),
                CryptoAlgorithm.AES_256_GCM
        );
        final StoredFileContentChannel channel = new StoredFileContentChannel(envelopeService);
        final InMemoryObjectStorageService objectStorage = new InMemoryObjectStorageService();
        final SampleDataFactory dataFactory = new SampleDataFactory();
        final DefaultFileFactory factory = new DefaultFileFactory(
                dataFactory, new InMemoryPendingUploadCache(), () -> true, objectStorage, envelopeService);

        // --- server-encrypted, DEFLATE-compressed, S3-backed ---
        final byte[] compressible = new byte[3 * CHUNK_SIZE_BYTES];
        Arrays.fill(compressible, (byte) 'a');
        final StoredFile compressedRow = factory.prepareForPersistence(
                new StoredFile("sample-compressed", "text.txt", compressible));
        dataFactory.put(compressedRow);
        check("a compressed, server-encrypted S3-backed file is stored compressed", compressedRow.isCompressed());
        check("a compressed, server-encrypted S3-backed file streams back byte-identically",
                Arrays.equals(compressible, drain(factory, "sample-compressed")));

        // --- server-encrypted, uncompressed, multi-chunk ---
        final byte[] incompressible = new byte[3 * CHUNK_SIZE_BYTES + 4_321];
        new SecureRandom().nextBytes(incompressible);
        final StoredFile multiChunkRow = factory.prepareForPersistence(
                new StoredFile("sample-multi-chunk", "random.bin", incompressible));
        dataFactory.put(multiChunkRow);
        check("an uncompressed, multi-chunk S3-backed file streams back byte-identically",
                Arrays.equals(incompressible, drain(factory, "sample-multi-chunk")));

        // --- client-encrypted direct transfer (the object is the chunked streaming layout) ---
        final byte[] clientEncrypted = new byte[2 * CHUNK_SIZE_BYTES + 77];
        new SecureRandom().nextBytes(clientEncrypted);
        storeStreamingObject(channel, objectStorage, "sample-direct-encrypted", clientEncrypted);
        dataFactory.put(new StoredFile("sample-direct-encrypted", "direct.bin", clientEncrypted.length,
                FileChecksum.of(HashAlgorithm.SHA_256, clientEncrypted), Instant.now(), Instant.now(),
                "sample-direct-encrypted", "sample-content-key-header"));
        check("a client-encrypted direct transfer streams back byte-identically",
                Arrays.equals(clientEncrypted, drain(factory, "sample-direct-encrypted")));

        // --- legacy plaintext direct transfer (no layout tag of ours at all) ---
        final byte[] legacyPlaintext = new byte[CHUNK_SIZE_BYTES + 11];
        new SecureRandom().nextBytes(legacyPlaintext);
        objectStorage.putObject("sample-direct-legacy", legacyPlaintext);
        dataFactory.put(new StoredFile("sample-direct-legacy", "legacy.bin", legacyPlaintext.length,
                FileChecksum.of(HashAlgorithm.SHA_256, legacyPlaintext), Instant.now(), Instant.now(),
                "sample-direct-legacy"));
        check("a legacy plaintext direct transfer is streamed straight from the store",
                Arrays.equals(legacyPlaintext, drain(factory, "sample-direct-legacy")));

        // --- deduplication alias of the compressed file ---
        dataFactory.put(StoredFile.createDedupAlias("sample-alias", "copy.txt", compressible.length,
                FileChecksum.of(HashAlgorithm.SHA_256, compressible), Instant.now(), Instant.now(), "sample-compressed"));
        check("a deduplication alias streams its canonical file's content",
                Arrays.equals(compressible, drain(factory, "sample-alias")));

        // --- inline file, on a deployment with no object store at all ---
        final byte[] inlineContent = new byte[5_000];
        new SecureRandom().nextBytes(inlineContent);
        final SampleDataFactory inlineDataFactory = new SampleDataFactory();
        inlineDataFactory.put(new StoredFile("sample-inline", "inline.bin", inlineContent));
        final DefaultFileFactory inlineFactory = new DefaultFileFactory(
                inlineDataFactory, new InMemoryPendingUploadCache(), () -> true, null, null);
        check("an inline file is served from the resolved entity",
                Arrays.equals(inlineContent, drain(inlineFactory, "sample-inline")));

        // --- an unknown id is absence, not a failure ---
        check("an unknown file id answers empty", factory.openContentStream("no-such-file").isEmpty());

        // --- a wrong recorded checksum fails at the end of the stream ---
        final byte[] realContent = new byte[4_096];
        new SecureRandom().nextBytes(realContent);
        objectStorage.putObject("sample-corrupt", realContent);
        dataFactory.put(new StoredFile("sample-corrupt", "corrupt.bin", realContent.length,
                FileChecksum.of(HashAlgorithm.SHA_256, new byte[]{1, 2, 3}), Instant.now(), Instant.now(),
                "sample-corrupt"));
        boolean integrityFailureReported = false;
        try (InputStream content = factory.openContentStream("sample-corrupt").orElseThrow()) {
            content.readAllBytes();
        } catch (final IOException checksumMismatch) {
            integrityFailureReported = checksumMismatch.getCause() instanceof FileIntegrityException;
        }
        check("content that does not match its recorded checksum fails the stream with a FileIntegrityException cause",
                integrityFailureReported);

        System.out.println(allPassed ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        if (!allPassed) {
            System.exit(1);
        }
    }

    /**
     * Drains {@code fileId}'s content stream fully, closing it afterwards.
     *
     * @param factory the factory to open the stream on
     * @param fileId the file whose content to read
     * @return every byte the stream yielded
     * @throws Exception if opening or reading the stream fails
     */
    private static byte[] drain(final DefaultFileFactory factory, final String fileId) throws Exception {
        try (InputStream content = factory.openContentStream(fileId).orElseThrow()) {
            return content.readAllBytes();
        }
    }

    /**
     * Writes {@code plaintext} into {@code objectStorage} as a chunked streaming-layout object
     * bound to {@code fileId} - the exact shape a client-encrypted direct transfer leaves behind.
     *
     * @param channel the channel producing the streaming layout
     * @param objectStorage the store to write into
     * @param fileId the file id bound into every chunk's associated data, also the object key
     * @param plaintext the content to encrypt
     * @throws Exception if encrypting or storing the object fails
     */
    private static void storeStreamingObject(final StoredFileContentChannel channel,
                                              final InMemoryObjectStorageService objectStorage,
                                              final String fileId, final byte[] plaintext) throws Exception {
        final StoredFileContentChannel.StreamingPayload payload = channel.sendStream(
                fileId, new ByteArrayInputStream(plaintext), plaintext.length);
        try (InputStream encrypted = payload.content()) {
            objectStorage.putObject(fileId, encrypted.readAllBytes());
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

    /** {@link DataFactory} stub serving the rows this sample registers; every other operation is unused. */
    private static final class SampleDataFactory extends DataFactory {

        /** Every row this sample registered, by primary key. */
        private final Map<String, Serialized> rows = new HashMap<>();

        /** Registers {@code file} as this factory's row for its own file id. */
        void put(final StoredFile file) {
            this.rows.put(file.fileId(), file);
        }

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
            final Serialized row = this.rows.get(objectId);
            if (row == null) {
                throw new UnsupportedOperationException("@SampleDataFactory: no row '" + objectId + "'");
            }
            return type.cast(row);
        }

        @Override
        public <T extends Serialized> List<T> fetch(@NotNull final String[] objectIds, @NotNull final Class<T> type) {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public <T extends Serialized> Optional<T> findById(@NotNull final String objectId, @NotNull final Class<T> type) {
            return Optional.ofNullable(this.rows.get(objectId)).map(type::cast);
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

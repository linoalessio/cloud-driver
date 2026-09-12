package de.lino.cloud.plugin.sample;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.file.ResumableUploadBegin;
import de.lino.cloud.api.file.ResumableUploadStatus;
import de.lino.cloud.api.file.ResumableUploadTicket;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.StoredFileSummary;
import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.s3storage.PresignedDownload;
import de.lino.cloud.api.s3storage.PresignedTransferService;
import de.lino.cloud.api.s3storage.PresignedUpload;
import de.lino.cloud.api.s3storage.ResumableUploadService;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.auth.CloudUserService;
import de.lino.cloud.auth.audit.AuditLogServiceImpl;
import de.lino.cloud.auth.pending.PendingPresignedUpload;
import de.lino.cloud.plugin.factory.DefaultDataFactory;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.cloud.plugin.factory.container.ServiceContainer;
import de.lino.cloud.plugin.file.InMemoryPendingUploadCache;
import de.lino.cloud.plugin.file.PendingPresignedUploadPurgeScheduler;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.develop.InMemoryKeyEncryptionService;
import de.lino.cloud.plugin.security.secrets.SecretRedactor;
import de.lino.database.DatabaseRepository;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.file.DefaultFileProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Standalone, runnable worked example (same convention as the other samples) exercising the
 * resumable multipart upload sessions at the service level, against in-memory
 * fakes of the two thin object-store boundaries ({@link ResumableUploadService}/{@link
 * PresignedTransferService}) - the real {@code S3ResumableUploadService} is a direct AWS-SDK
 * wrapper, so everything worth testing (session persistence, dedup precheck, status merge,
 * completion validation and registration, abort, the purge sweep's multipart abort) lives in the
 * code this sample drives. Prints pass/fail per check and exits non-zero on any failure.
 */
public final class ResumableUploadSample {

    /** Tracks whether every check so far passed; {@link #main} exits non-zero if any failed. */
    private static boolean allPassed = true;

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private ResumableUploadSample() {
    }

    /**
     * Runs every check described in this class's own Javadoc and reports pass/fail to stdout.
     *
     * @param args unused
     * @throws Exception on any unexpected failure - the sample makes no attempt to continue past one
     */
    public static void main(final String[] args) throws Exception {

        final Path sampleDirectory = Path.of("cloud-driver-resumable-upload-sample");
        deleteRecursivelyQuietly(sampleDirectory);

        new DefaultFileProvider();
        new DatabaseRepositoryRegistry(false);
        final DatabaseProvider databaseProvider = DatabaseRepository.getInstance()
                .registerDatabaseProviderAsync(0, DatabaseType.JSON,
                        new Credentials(sampleDirectory.resolve("database.json"), sampleDirectory.resolve("data")))
                .join();

        final KeyEncryptionService keyEncryptionService = new InMemoryKeyEncryptionService();
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(keyEncryptionService);
        final DataFactory dataFactory = new DefaultDataFactory(new EntityDatabaseClient(databaseProvider, envelopeEncryptionService));
        final FileFactory fileFactory = new DefaultFileFactory(dataFactory, new InMemoryPendingUploadCache(), () -> true);
        final AuditLogService auditLogService = new AuditLogServiceImpl(dataFactory, SecretRedactor::redact);

        SampleCloudDriver.install();

        final FakeObjectStore store = new FakeObjectStore();
        final CloudUserService cloudUserService = new CloudUserService(
                dataFactory, fileFactory, auditLogService, store.presignedTransferService, null, store.resumableUploadService);

        final String userId = "resumable-sample-user";
        try {
            // --- a fresh session: 20.5 MiB -> 3 parts of 8 MiB ---
            final byte[] content = new byte[20 * 1024 * 1024 + 512 * 1024];
            new SecureRandom().nextBytes(content);
            final String checksumHex = sha256Hex(content);

            final ResumableUploadBegin begin = cloudUserService.beginResumableUpload(
                    userId, "big-file.bin", content.length, checksumHex, null);
            check("an unknown checksum begins a real session", begin.ticket() != null && begin.alreadyStored() == null);
            final ResumableUploadTicket ticket = begin.ticket();
            check("session geometry: 3 parts of 8 MiB covering the declared size",
                    ticket.partCount() == 3 && ticket.partSizeBytes() == CloudUserService.RESUMABLE_PART_SIZE_BYTES
                            && ticket.totalObjectBytes() == content.length);

            // --- upload parts 1 and 3, "crash", then resume off the reported status ---
            store.putPart(ticket.fileId(), partOf(content, 1, ticket), 1);
            store.putPart(ticket.fileId(), partOf(content, 3, ticket), 3);
            final ResumableUploadStatus status = cloudUserService.getResumableUploadStatus(userId, ticket.fileId());
            check("status reports exactly the parts the store holds", status.uploadedPartNumbers().equals(java.util.List.of(1, 3)));

            boolean completeRejected = false;
            try {
                cloudUserService.completeResumableUpload(userId, ticket.fileId(), "big-file.bin", checksumHex, null);
            } catch (final IllegalArgumentException missingParts) {
                completeRejected = true;
            }
            check("completing with a missing part is rejected", completeRejected);

            store.putPart(ticket.fileId(), partOf(content, 2, ticket), 2);
            final StoredFileSummary summary = cloudUserService.completeResumableUpload(
                    userId, ticket.fileId(), "big-file.bin", checksumHex, null);
            check("completion registers the file at the real size", summary.sizeBytes() == content.length);
            check("the assembled object is byte-identical to the original",
                    Arrays.equals(content, store.objects.get(ticket.fileId())));
            final StoredFile registered = dataFactory.findById(ticket.fileId(), StoredFile.class).orElseThrow();
            check("the registered row is a direct-transfer S3-backed file",
                    registered.isS3Backed() && registered.isDirectTransfer());
            check("the session's tracking row is gone after completion",
                    dataFactory.findById(ticket.fileId(), PendingPresignedUpload.class).isEmpty());

            // --- dedup precheck: the same checksum now short-circuits, zero bytes uploaded ---
            final ResumableUploadBegin dedup = cloudUserService.beginResumableUpload(
                    userId, "big-file-copy.bin", content.length, checksumHex, null);
            check("a known checksum answers alreadyStored (dedup precheck)",
                    dedup.alreadyStored() != null && dedup.ticket() == null);
            check("the alias carries the canonical content's size", dedup.alreadyStored().sizeBytes() == content.length);
            final StoredFile alias = dataFactory.findById(dedup.alreadyStored().fileId(), StoredFile.class).orElseThrow();
            check("the dedup result is a real alias row", alias.isDedupAlias());

            // --- abort: parts discarded, row removed ---
            final ResumableUploadTicket abortMe = cloudUserService.beginResumableUpload(
                    userId, "aborted.bin", content.length, sha256Hex(new byte[]{1, 2, 3}), null).ticket();
            store.putPart(abortMe.fileId(), partOf(content, 1, abortMe), 1);
            cloudUserService.abortResumableUpload(userId, abortMe.fileId());
            check("abort discards the store's parts", !store.multipartParts.containsKey(abortMe.fileId()));
            check("abort removes the tracking row",
                    dataFactory.findById(abortMe.fileId(), PendingPresignedUpload.class).isEmpty());

            // --- another account can't touch a session ---
            final ResumableUploadTicket foreign = cloudUserService.beginResumableUpload(
                    userId, "mine.bin", content.length, sha256Hex(new byte[]{9}), null).ticket();
            boolean foreignRejected = false;
            try {
                cloudUserService.getResumableUploadStatus("someone-else", foreign.fileId());
            } catch (final IllegalArgumentException expected) {
                foreignRejected = true;
            }
            check("another account's status request is rejected without confirming existence", foreignRejected);

            // --- the purge sweep aborts an abandoned session's multipart upload ---
            final PendingPresignedUploadPurgeScheduler purge = new PendingPresignedUploadPurgeScheduler(
                    dataFactory, new NoOpObjectStorage(), Duration.ZERO, store.resumableUploadService);
            purge.start(Duration.ofMillis(50));
            Thread.sleep(400);
            purge.shutdown();
            check("the purge sweep aborted the abandoned session's parts",
                    !store.multipartParts.containsKey(foreign.fileId()));
            check("the purge sweep removed the abandoned session's row",
                    dataFactory.findById(foreign.fileId(), PendingPresignedUpload.class).isEmpty());
        } finally {
            deleteRecursivelyQuietly(sampleDirectory);
        }

        System.out.println(allPassed ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    /** Part {@code partNumber}'s byte range of {@code content}, per {@code ticket}'s geometry. */
    private static byte[] partOf(final byte[] content, final int partNumber, final ResumableUploadTicket ticket) {
        final long offset = (long) (partNumber - 1) * ticket.partSizeBytes();
        final int length = (int) Math.min(ticket.partSizeBytes(), content.length - offset);
        return Arrays.copyOfRange(content, (int) offset, (int) offset + length);
    }

    /** The SHA-256 of {@code content} as lowercase hex. */
    private static String sha256Hex(final byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** Prints one check's outcome and folds it into {@link #allPassed}. */
    private static void check(final String description, final boolean passed) {
        System.out.println((passed ? "PASS  " : "FAIL  ") + description);
        allPassed &= passed;
    }

    /**
     * In-memory fake of the two thin object-store boundaries, sharing one state: multipart
     * parts by object key, and assembled objects. "Uploading" a part is a direct {@link
     * #putPart} call - the sample stands in for the client's presigned {@code PUT}.
     */
    private static final class FakeObjectStore {

        /** {@code objectKey → (partNumber → bytes)} for in-progress multipart uploads. */
        final Map<String, TreeMap<Integer, byte[]>> multipartParts = new ConcurrentHashMap<>();
        /** {@code objectKey → uploadId} for in-progress multipart uploads. */
        final Map<String, String> uploadIds = new ConcurrentHashMap<>();
        /** Assembled/completed objects. */
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        /** Stands in for the client's presigned part {@code PUT}. */
        void putPart(final String objectKey, final byte[] bytes, final int partNumber) {
            this.multipartParts.get(objectKey).put(partNumber, bytes);
        }

        final ResumableUploadService resumableUploadService = new ResumableUploadService() {
            @NotNull
            @Override
            public String createMultipartUpload(@NotNull final String objectKey) {
                final String uploadId = UUID.randomUUID().toString();
                multipartParts.put(objectKey, new TreeMap<>());
                uploadIds.put(objectKey, uploadId);
                return uploadId;
            }

            @NotNull
            @Override
            public PresignedUpload presignPart(@NotNull final String objectKey, @NotNull final String uploadId,
                                                final int partNumber, @NotNull final Duration expiry) {
                try {
                    return new PresignedUpload(URI.create("https://fake-store.invalid/" + objectKey + "/" + partNumber).toURL(),
                            Map.of(), Instant.now().plus(expiry));
                } catch (final java.net.MalformedURLException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }

            @NotNull
            @Override
            public Map<Integer, String> listUploadedParts(@NotNull final String objectKey, @NotNull final String uploadId) {
                final TreeMap<Integer, byte[]> parts = multipartParts.get(objectKey);
                if (parts == null) throw new ObjectStorageException("no such multipart upload: " + objectKey);
                final Map<Integer, String> etags = new TreeMap<>();
                parts.forEach((number, bytes) -> etags.put(number, "etag-" + number));
                return etags;
            }

            @Override
            public void completeMultipartUpload(@NotNull final String objectKey, @NotNull final String uploadId,
                                                 @NotNull final Map<Integer, String> partETags) {
                final TreeMap<Integer, byte[]> parts = multipartParts.remove(objectKey);
                if (parts == null) throw new ObjectStorageException("no such multipart upload: " + objectKey);
                uploadIds.remove(objectKey);
                final ByteArrayOutputStream assembled = new ByteArrayOutputStream();
                parts.values().forEach(assembled::writeBytes);
                objects.put(objectKey, assembled.toByteArray());
            }

            @Override
            public void abortMultipartUpload(@NotNull final String objectKey, @NotNull final String uploadId) {
                multipartParts.remove(objectKey);
                uploadIds.remove(objectKey);
            }
        };

        final PresignedTransferService presignedTransferService = new PresignedTransferService() {
            @NotNull
            @Override
            public PresignedUpload presignUpload(@NotNull final String objectKey, final long contentLength, @NotNull final Duration expiry) {
                throw new UnsupportedOperationException("not used by this sample");
            }

            @NotNull
            @Override
            public PresignedDownload presignDownload(@NotNull final String objectKey, @NotNull final Duration expiry) {
                throw new UnsupportedOperationException("not used by this sample");
            }

            @Override
            public long headObjectContentLength(@NotNull final String objectKey) {
                final byte[] object = objects.get(objectKey);
                if (object == null) throw new ObjectStorageException("no object under: " + objectKey);
                return object.length;
            }

            @Override
            public void deleteObject(@NotNull final String objectKey) {
                objects.remove(objectKey);
            }
        };
    }

    /** All-no-op {@link de.lino.cloud.api.s3storage.ObjectStorageService} - the purge sweep in this sample only ever handles session rows. */
    private static final class NoOpObjectStorage implements de.lino.cloud.api.s3storage.ObjectStorageService {
        @Override public void putObject(@NotNull final String objectKey, final byte[] content) { }
        @Override public void putObject(@NotNull final String objectKey, @NotNull final java.io.InputStream content, final long contentLength) { }
        @NotNull @Override public byte[] getObject(@NotNull final String objectKey) { throw new ObjectStorageException("not used"); }
        @NotNull @Override public java.io.InputStream getObjectStream(@NotNull final String objectKey) { throw new ObjectStorageException("not used"); }
        @Override public void deleteObject(@NotNull final String objectKey) { }
        @Override public boolean exists(@NotNull final String objectKey) { return false; }
        @NotNull @Override public ObjectListing listObjects(@Nullable final String continuationToken, final int maxKeys) {
            return new ObjectListing(java.util.List.of(), null);
        }
    }

    /** Minimal {@link de.lino.cloud.api.CloudDriver} stub, mirroring the other samples' - empty-but-roomy config, all-null factories, empty service container. */
    private static final class SampleCloudDriver extends de.lino.cloud.api.CloudDriver {

        /** The empty service container every optional-facet consumer reads. */
        private final de.lino.cloud.api.factory.service.IServiceContainer serviceContainer = new ServiceContainer();

        /** All-{@code null} factory container - the sample wires its factories directly, never through this. */
        private final de.lino.cloud.api.factory.container.IFactoryContainer factoryContainer =
                new de.lino.cloud.api.factory.container.IFactoryContainer() {
                    @Override public DataFactory getDataFactory() { return null; }
                    @Override public FileFactory getFileFactory() { return null; }
                    @Override public de.lino.cloud.api.factory.ExtensionFactory getExtensionFactory() { return null; }
                    @Override public de.lino.cloud.api.factory.EventFactory getEventFactory() { return null; }
                    @Override public RestFactory getRestFactory() { return null; }
                    @Override public de.lino.cloud.api.s3storage.ObjectStorageService getObjectStorageService() { return null; }
                    @Override public de.lino.cloud.api.s3storage.ContentKeyService getContentKeyService() { return null; }
                    @Override public de.lino.cloud.api.event.database.FileChangeListenerRegistry getFileChangeListenerRegistry() { return null; }
                    @Override public de.lino.cloud.api.redis.RedisSupport getRedisSupport() { return null; }
                };

        /** Installs a fresh stub as the process-wide {@code CloudDriver} instance. */
        static void install() {
            INSTANCE = new SampleCloudDriver();
        }

        @Override
        public de.lino.database.json.JsonDocument getConfiguration() {
            // A roomy upload quota - the strict 1 MiB default would reject this sample's multi-MiB sessions.
            return new de.lino.database.json.JsonDocument().append("cloud-user-max-bytes-to-upload", 1024L * 1024 * 1024);
        }

        @Override
        public de.lino.cloud.api.security.connectivity.ConnectivityChecker getConnectivityChecker() {
            return () -> true;
        }

        @Override
        public de.lino.cloud.api.factory.container.IFactoryContainer getFactoryContainer() {
            return this.factoryContainer;
        }

        @Override
        public de.lino.cloud.api.factory.service.IServiceContainer getServiceContainer() {
            return this.serviceContainer;
        }

        @Override
        public de.lino.cloud.api.terminal.Terminal getTerminal() {
            throw new UnsupportedOperationException("not used by this sample");
        }

        @Override
        public void shutdown() {
            // Nothing to shut down - the sample owns its resources directly.
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException("not used by this sample");
        }
    }

    /** Best-effort recursive delete of this sample's throwaway directory, so every run starts fresh. */
    private static void deleteRecursivelyQuietly(final Path directory) {
        if (!java.nio.file.Files.exists(directory)) {
            return;
        }
        try (Stream<Path> tree = java.nio.file.Files.walk(directory)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    java.nio.file.Files.deleteIfExists(path);
                } catch (final java.io.IOException ignored) {
                    // Best-effort cleanup only - a leftover throwaway directory is harmless.
                }
            });
        } catch (final java.io.IOException ignored) {
            // Best-effort cleanup only.
        }
    }
}

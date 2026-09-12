package de.lino.cloud.extensions.versioning;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.jwt.JwtSigner;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.mail.EmailSender;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.auth.AuthService;
import de.lino.cloud.auth.CloudUserService;
import de.lino.cloud.auth.audit.AuditLogServiceImpl;
import de.lino.cloud.auth.jwt.JjwtSigner;
import de.lino.cloud.auth.mail.LoggingEmailSender;
import de.lino.cloud.plugin.factory.DefaultDataFactory;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.cloud.plugin.factory.DefaultRestFactory;
import de.lino.cloud.plugin.factory.container.ServiceContainer;
import de.lino.cloud.plugin.file.InMemoryPendingUploadCache;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.develop.InMemoryKeyEncryptionService;
import de.lino.cloud.plugin.security.password.Argon2idPasswordHasher;
import de.lino.cloud.plugin.security.secrets.SecretRedactor;
import de.lino.database.DatabaseRepository;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.file.DefaultFileProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Standalone, runnable worked example (same convention as the other samples) exercising the
 * chunk-diff mechanism end to end: the chunk
 * manifest route, {@code PATCH /files/{id}/content} splicing, and - with a real {@link
 * DefaultFileVersioningService} published - version delta capture, chain reconstruction, and
 * restore. Boots the full JWT-gated REST stack against a throwaway local JSON database and acts
 * as its own HTTP client, printing pass/fail per check and exiting non-zero on any failure.
 */
public final class ChunkPatchSample {

    /** The port this sample's throwaway HTTP server listens on - distinct from every other sample's. */
    private static final int PORT = 7074;

    /** Fixed JWT signing key for this throwaway sample only - never reuse for anything real. */
    private static final String DUMMY_SIGNING_KEY = "sample-signing-key-do-not-use-in-prod!!";

    /** Tracks whether every check so far passed; {@link #main} exits non-zero if any failed. */
    private static boolean allPassed = true;

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private ChunkPatchSample() {
    }

    /**
     * Boots the stack, runs every check described in this class's own Javadoc, and exits.
     *
     * @param args unused
     * @throws Exception on any unexpected failure - the sample makes no attempt to continue past one
     */
    public static void main(final String[] args) throws Exception {

        final Path sampleDirectory = Path.of("cloud-driver-chunk-patch-sample");
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

        final PasswordHasher passwordHasher = new Argon2idPasswordHasher();
        final JwtSigner jwtSigner = new JjwtSigner(DUMMY_SIGNING_KEY);
        final EmailSender emailSender = new LoggingEmailSender(Logger.getLogger(ChunkPatchSample.class.getName()));
        final AuditLogService auditLogService = new AuditLogServiceImpl(dataFactory, SecretRedactor::redact);
        final CloudUserService cloudUserService = new CloudUserService(dataFactory, fileFactory, auditLogService);
        final AuthService authService = new AuthService(dataFactory, passwordHasher, jwtSigner, emailSender, cloudUserService, auditLogService);

        final String email = "chunk-patch-sample@example.com";
        final String password = "chunk-patch-password-123";
        dataFactory.register(new AuthUser(UUID.randomUUID().toString(), email, passwordHasher.hash(password.toCharArray())));

        // Minimal CloudDriver stub (rate-limit filters + captureFileVersion resolve services
        // through it) - with a REAL versioning service published, so PATCHes capture versions.
        SampleCloudDriver.install();
        SampleCloudDriver.SERVICE_CONTAINER.setFileVersioningService(
                new DefaultFileVersioningService(dataFactory, fileFactory, Logger.getLogger(ChunkPatchSample.class.getName())));

        final RestFactory restFactory = new DefaultRestFactory(dataFactory, authService, cloudUserService);
        restFactory.start(PORT);

        try {
            final HttpClient http = HttpClient.newHttpClient();
            final Gson gson = new Gson();
            final String base = "http://localhost:" + PORT;
            final int chunkSize = Constraints.CONTENT_CHUNK_SIZE_BYTES;

            final HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/auth/login"))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"username\":\"" + email + "\",\"password\":\"" + password + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (login.statusCode() != 200) {
                throw new IllegalStateException("login failed: HTTP " + login.statusCode() + " - " + login.body());
            }
            final String token = gson.fromJson(login.body(), JsonObject.class).get("token").getAsString();

            // --- upload a 3.5-chunk file ---
            final byte[] original = new byte[3 * chunkSize + chunkSize / 2];
            new SecureRandom().nextBytes(original);
            final HttpResponse<String> upload = http.send(HttpRequest.newBuilder(URI.create(base + "/files?fileName=chunked.bin"))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(original)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (upload.statusCode() != 201) {
                throw new IllegalStateException("upload failed: HTTP " + upload.statusCode() + " - " + upload.body());
            }
            final String fileId = gson.fromJson(upload.body(), JsonObject.class).get("fileId").getAsString();

            // --- manifest ---
            final JsonObject manifest = gson.fromJson(http.send(authorizedGet(base + "/files/" + fileId + "/chunk-manifest", token),
                    HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
            check("manifest reports 4 chunks at the shared chunk size",
                    manifest.get("chunkSizeBytes").getAsInt() == chunkSize
                            && manifest.get("totalSizeBytes").getAsLong() == original.length
                            && manifest.getAsJsonArray("chunkHashes").size() == 4);

            // --- PATCH: change chunk 1, grow by one appended chunk ---
            final byte[] afterFirstPatch = Arrays.copyOf(original, 4 * chunkSize + chunkSize / 2);
            new SecureRandom().nextBytes(afterFirstPatch); // start fresh, then re-copy the unchanged chunks
            System.arraycopy(original, 0, afterFirstPatch, 0, original.length);
            final byte[] newChunk1 = randomChunk(chunkSize);
            System.arraycopy(newChunk1, 0, afterFirstPatch, chunkSize, chunkSize);
            final byte[] tail = new byte[chunkSize / 2 + chunkSize];
            new SecureRandom().nextBytes(tail);
            System.arraycopy(tail, 0, afterFirstPatch, 3 * chunkSize, tail.length);
            // Changed relative to original: chunk 1 (rewritten), chunk 3 (old partial final,
            // now full) and chunk 4 (appended).
            final JsonObject patch1 = patchBody(afterFirstPatch.length,
                    chunkOf(afterFirstPatch, 1, chunkSize), 1,
                    chunkOf(afterFirstPatch, 3, chunkSize), 3,
                    chunkOf(afterFirstPatch, 4, chunkSize), 4);
            final HttpResponse<String> patched1 = http.send(patchRequest(base, fileId, token, gson.toJson(patch1), null),
                    HttpResponse.BodyHandlers.ofString());
            check("first PATCH succeeds", patched1.statusCode() == 200);

            final byte[] downloaded1 = http.send(authorizedGet(base + "/files/" + fileId + "/content", token),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
            check("content after first PATCH is the correctly spliced result", Arrays.equals(afterFirstPatch, downloaded1));

            final JsonObject manifest2 = gson.fromJson(http.send(authorizedGet(base + "/files/" + fileId + "/chunk-manifest", token),
                    HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
            check("manifest tracked the PATCH (5 chunks, chunk 0 hash unchanged, chunk 1 changed)",
                    manifest2.getAsJsonArray("chunkHashes").size() == 5
                            && hashAt(manifest2, 0).equals(hashAt(manifest, 0))
                            && !hashAt(manifest2, 1).equals(hashAt(manifest, 1)));

            // --- version 1 (keyframe) holds the original ---
            final JsonArray versions1 = gson.fromJson(http.send(authorizedGet(base + "/files/" + fileId + "/versions", token),
                    HttpResponse.BodyHandlers.ofString()).body(), JsonArray.class);
            check("first PATCH captured version 1", versions1.size() == 1);
            final byte[] version1Content = http.send(authorizedGet(base + "/files/" + fileId + "/versions/1/content", token),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
            check("version 1 (keyframe) reconstructs the original content", Arrays.equals(original, version1Content));

            // --- two more single-chunk PATCHes -> delta-stored versions ---
            final byte[] afterSecondPatch = afterFirstPatch.clone();
            System.arraycopy(randomChunk(chunkSize), 0, afterSecondPatch, 0, chunkSize);
            check("second PATCH succeeds", http.send(patchRequest(base, fileId, token,
                            gson.toJson(patchBody(afterSecondPatch.length, chunkOf(afterSecondPatch, 0, chunkSize), 0)), null),
                    HttpResponse.BodyHandlers.ofString()).statusCode() == 200);

            final byte[] afterThirdPatch = afterSecondPatch.clone();
            System.arraycopy(randomChunk(chunkSize), 0, afterThirdPatch, 2 * chunkSize, chunkSize);
            check("third PATCH succeeds", http.send(patchRequest(base, fileId, token,
                            gson.toJson(patchBody(afterThirdPatch.length, chunkOf(afterThirdPatch, 2, chunkSize), 2)), null),
                    HttpResponse.BodyHandlers.ofString()).statusCode() == 200);

            final byte[] version2Content = http.send(authorizedGet(base + "/files/" + fileId + "/versions/2/content", token),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
            check("version 2 (delta) reconstructs the first-patch content", Arrays.equals(afterFirstPatch, version2Content));
            final byte[] version3Content = http.send(authorizedGet(base + "/files/" + fileId + "/versions/3/content", token),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
            check("version 3 (delta chain of 2) reconstructs the second-patch content", Arrays.equals(afterSecondPatch, version3Content));

            // --- delta rows really store deltas, not full copies ---
            final FileVersion version3Row = dataFactory.findById(FileVersion.compositeKey(fileId, 3), FileVersion.class).orElseThrow();
            check("version 3 is stored as a single-chunk delta",
                    !version3Row.isKeyframe() && version3Row.getDeltaChunkIndices() != null
                            && version3Row.getDeltaChunkIndices().equals(java.util.List.of(0)));

            // --- restore an old version through the delta chain ---
            check("restoring version 1 succeeds", http.send(HttpRequest.newBuilder(
                                    URI.create(base + "/files/" + fileId + "/versions/1/restore"))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode() == 200);
            final byte[] restored = http.send(authorizedGet(base + "/files/" + fileId + "/content", token),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
            check("restored content equals the original", Arrays.equals(original, restored));

            // --- optimistic concurrency: a stale PATCH conflicts, canonical untouched ---
            final HttpResponse<String> stale = http.send(patchRequest(base, fileId, token,
                            gson.toJson(patchBody(original.length, chunkOf(original, 0, chunkSize), 0)), 1L),
                    HttpResponse.BodyHandlers.ofString());
            check("stale expectedUpdatedAt answers 409 with a conflicted copy",
                    stale.statusCode() == 409 && gson.fromJson(stale.body(), JsonObject.class).has("fileId"));

            // --- an inconsistent chunk set answers 400 ---
            final JsonObject badPatch = patchBody(original.length + chunkSize, chunkOf(original, 0, chunkSize), 0);
            final HttpResponse<String> bad = http.send(patchRequest(base, fileId, token, gson.toJson(badPatch), null),
                    HttpResponse.BodyHandlers.ofString());
            check("a chunk set missing the new tail answers 400", bad.statusCode() == 400);
        } finally {
            restFactory.stop();
            deleteRecursivelyQuietly(sampleDirectory);
        }

        System.out.println(allPassed ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    /** Builds an authorized GET request. */
    private static HttpRequest authorizedGet(final String url, final String token) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).GET().build();
    }

    /** Builds the PATCH request, optionally with the {@code expectedUpdatedAt} precondition. */
    private static HttpRequest patchRequest(final String base, final String fileId, final String token,
                                             final String body, final Long expectedUpdatedAt) {
        final String url = base + "/files/" + fileId + "/content"
                + (expectedUpdatedAt == null ? "" : "?expectedUpdatedAt=" + expectedUpdatedAt);
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /** Builds a PATCH body from {@code (chunkBytes, index)} pairs. */
    private static JsonObject patchBody(final long totalSizeBytes, final Object... chunkAndIndexPairs) {
        final JsonObject body = new JsonObject();
        body.addProperty("totalSizeBytes", totalSizeBytes);
        final JsonArray chunks = new JsonArray();
        for (int i = 0; i < chunkAndIndexPairs.length; i += 2) {
            final JsonObject chunk = new JsonObject();
            chunk.addProperty("index", (Integer) chunkAndIndexPairs[i + 1]);
            chunk.addProperty("contentBase64", java.util.Base64.getEncoder().encodeToString((byte[]) chunkAndIndexPairs[i]));
            chunks.add(chunk);
        }
        body.add("changedChunks", chunks);
        return body;
    }

    /** Chunk {@code index} of {@code content} at {@code chunkSize} - shorter for the final chunk. */
    private static byte[] chunkOf(final byte[] content, final int index, final int chunkSize) {
        final int offset = index * chunkSize;
        return Arrays.copyOfRange(content, offset, Math.min(content.length, offset + chunkSize));
    }

    /** One chunk of random bytes. */
    private static byte[] randomChunk(final int chunkSize) {
        final byte[] chunk = new byte[chunkSize];
        new SecureRandom().nextBytes(chunk);
        return chunk;
    }

    /** Chunk hash {@code index} out of a manifest response body. */
    private static String hashAt(final JsonObject manifest, final int index) {
        return manifest.getAsJsonArray("chunkHashes").get(index).getAsString();
    }

    /** Prints one check's outcome and folds it into {@link #allPassed}. */
    private static void check(final String description, final boolean passed) {
        System.out.println((passed ? "PASS  " : "FAIL  ") + description);
        allPassed &= passed;
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

    /**
     * Minimal {@link de.lino.cloud.api.CloudDriver} stub, mirroring {@code
     * ConditionalDownloadSample}'s: empty configuration, all-{@code null} factory container, and
     * a mutable {@link ServiceContainer} this sample publishes a real versioning service into.
     */
    private static final class SampleCloudDriver extends de.lino.cloud.api.CloudDriver {

        /** The mutable container the sample publishes its real versioning service into. */
        static final ServiceContainer SERVICE_CONTAINER = new ServiceContainer();

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
            // A roomy upload quota - the default without the key is a strict 1 MiB, and this
            // sample deliberately uploads multi-chunk (multi-MiB) files.
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
            return SERVICE_CONTAINER;
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
}

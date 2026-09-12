package de.lino.cloud.plugin.sample;

import com.google.gson.Gson;
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
import de.lino.cloud.auth.AuthService;
import de.lino.cloud.auth.CloudUserService;
import de.lino.cloud.auth.audit.AuditLogServiceImpl;
import de.lino.cloud.auth.jwt.JjwtSigner;
import de.lino.cloud.auth.mail.LoggingEmailSender;
import de.lino.cloud.plugin.factory.DefaultDataFactory;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.cloud.plugin.factory.DefaultRestFactory;
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
import java.util.Comparator;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Standalone, runnable worked example (same convention as {@link RestFactoryCloudUserSample})
 * exercising the {@code ETag}/{@code If-None-Match} conditional-download handshake -
 * self-driving, unlike that sample: it boots the full JWT-gated REST stack
 * against a throwaway local JSON database, then acts as its own HTTP client, printing pass/fail
 * per check to stdout and exiting non-zero on any failure.
 *
 * <ul>
 *     <li>{@code GET /files/{id}/content} answers {@code 200} with an {@code ETag} (the file's
 *     checksum hex, quoted) and {@code Cache-Control: private, must-revalidate}</li>
 *     <li>repeating the request with {@code If-None-Match} answers {@code 304} with an empty
 *     body, the same {@code ETag}, and no {@code Content-Disposition}</li>
 *     <li>after {@code PUT /files/{id}/content} replaces the content, the old {@code ETag}
 *     no longer matches - full {@code 200} with the new bytes and a new {@code ETag}</li>
 * </ul>
 */
public final class ConditionalDownloadSample {

    /** The port this sample's throwaway HTTP server listens on - distinct from every other sample's. */
    private static final int PORT = 7073;

    /** Fixed JWT signing key for this throwaway sample only - never reuse for anything real. */
    private static final String DUMMY_SIGNING_KEY = "sample-signing-key-do-not-use-in-prod!!";

    /** Tracks whether every check so far passed; {@link #main} exits non-zero if any failed. */
    private static boolean allPassed = true;

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private ConditionalDownloadSample() {
    }

    /**
     * Boots the stack, runs every check described in this class's own Javadoc, and exits.
     *
     * @param args unused
     * @throws Exception on any unexpected failure - the sample makes no attempt to continue past one
     */
    public static void main(final String[] args) throws Exception {

        final Path sampleDirectory = Path.of("cloud-driver-conditional-download-sample");
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
        final EmailSender emailSender = new LoggingEmailSender(Logger.getLogger(ConditionalDownloadSample.class.getName()));
        final AuditLogService auditLogService = new AuditLogServiceImpl(dataFactory, SecretRedactor::redact);
        final CloudUserService cloudUserService = new CloudUserService(dataFactory, fileFactory, auditLogService);
        final AuthService authService = new AuthService(dataFactory, passwordHasher, jwtSigner, emailSender, cloudUserService, auditLogService);

        final String email = "etag-sample@example.com";
        final String password = "etag-sample-password-123";
        dataFactory.register(new AuthUser(UUID.randomUUID().toString(), email, passwordHasher.hash(password.toCharArray())));

        // DefaultRestFactory's rate-limit filters resolve Redis/config via
        // CloudDriver.getInstance() on every request - install a minimal stub so this
        // standalone sample (no real CloudBootstrap) doesn't 500 on that lookup.
        SampleCloudDriver.install();

        final RestFactory restFactory = new DefaultRestFactory(dataFactory, authService, cloudUserService);
        restFactory.start(PORT);

        try {
            final HttpClient http = HttpClient.newHttpClient();
            final Gson gson = new Gson();
            final String base = "http://localhost:" + PORT;

            // --- login ---
            final HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/auth/login"))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"username\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (login.statusCode() != 200) {
                throw new IllegalStateException("login failed: HTTP " + login.statusCode() + " - " + login.body());
            }
            final String token = gson.fromJson(login.body(), JsonObject.class).get("token").getAsString();

            // --- upload ---
            final byte[] originalContent = "conditional download sample content, version one".getBytes(StandardCharsets.UTF_8);
            final HttpResponse<String> upload = http.send(HttpRequest.newBuilder(URI.create(base + "/files?fileName=etag-sample.txt"))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(originalContent))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            final String fileId = gson.fromJson(upload.body(), JsonObject.class).get("fileId").getAsString();

            // --- 200 with ETag + Cache-Control ---
            final HttpResponse<byte[]> first = http.send(HttpRequest.newBuilder(URI.create(base + "/files/" + fileId + "/content"))
                            .header("Authorization", "Bearer " + token)
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            final String etag = first.headers().firstValue("ETag").orElse(null);
            check("200 carries the content", first.statusCode() == 200 && java.util.Arrays.equals(originalContent, first.body()));
            check("200 carries a quoted ETag", etag != null && etag.startsWith("\"") && etag.endsWith("\""));
            check("200 carries Cache-Control private, must-revalidate",
                    "private, must-revalidate".equals(first.headers().firstValue("Cache-Control").orElse(null)));

            // --- 304 on a matching If-None-Match ---
            final HttpResponse<byte[]> conditional = http.send(HttpRequest.newBuilder(URI.create(base + "/files/" + fileId + "/content"))
                            .header("Authorization", "Bearer " + token)
                            .header("If-None-Match", etag)
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            check("matching If-None-Match answers 304", conditional.statusCode() == 304);
            check("304 body is empty", conditional.body().length == 0);
            check("304 repeats the ETag", etag.equals(conditional.headers().firstValue("ETag").orElse(null)));
            check("304 carries no Content-Disposition", conditional.headers().firstValue("Content-Disposition").isEmpty());

            // --- content replaced: the old ETag must stop matching ---
            final byte[] replacedContent = "conditional download sample content, VERSION TWO".getBytes(StandardCharsets.UTF_8);
            final HttpResponse<String> replace = http.send(HttpRequest.newBuilder(URI.create(base + "/files/" + fileId + "/content"))
                            .header("Authorization", "Bearer " + token)
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(replacedContent))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            check("content replace succeeds", replace.statusCode() == 200);

            final HttpResponse<byte[]> afterReplace = http.send(HttpRequest.newBuilder(URI.create(base + "/files/" + fileId + "/content"))
                            .header("Authorization", "Bearer " + token)
                            .header("If-None-Match", etag)
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            check("stale If-None-Match answers a full 200 with the new bytes",
                    afterReplace.statusCode() == 200 && java.util.Arrays.equals(replacedContent, afterReplace.body()));
            check("the replaced content carries a different ETag",
                    !etag.equals(afterReplace.headers().firstValue("ETag").orElse(null)));
        } finally {
            restFactory.stop();
            deleteRecursivelyQuietly(sampleDirectory);
        }

        System.out.println(allPassed ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    /** Prints one check's outcome and folds it into {@link #allPassed}. */
    private static void check(final String description, final boolean passed) {
        System.out.println((passed ? "PASS  " : "FAIL  ") + description);
        allPassed &= passed;
    }

    /**
     * Minimal {@link de.lino.cloud.api.CloudDriver} stub for this standalone sample: an empty
     * configuration (every rate-limit knob falls back to its default), a factory container whose
     * every facet is {@code null} (Redis included - the rate limiter then uses its in-process
     * buckets), and an empty {@link de.lino.cloud.plugin.factory.container.ServiceContainer}
     * (every optional service facet absent, which all consumers already degrade gracefully on).
     */
    private static final class SampleCloudDriver extends de.lino.cloud.api.CloudDriver {

        /** The empty service container every optional-facet consumer reads. */
        private final de.lino.cloud.api.factory.service.IServiceContainer serviceContainer =
                new de.lino.cloud.plugin.factory.container.ServiceContainer();

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
            return new de.lino.database.json.JsonDocument();
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

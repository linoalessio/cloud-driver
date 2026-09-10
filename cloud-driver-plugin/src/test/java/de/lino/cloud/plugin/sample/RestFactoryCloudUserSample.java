package de.lino.cloud.plugin.sample;

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

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Standalone, runnable worked example (same "sample under {@code src/test}, run {@code
 * main} directly" convention as {@link RestFactorySample} - see "Build" in {@code
 * CLAUDE.md}) demonstrating {@link RestFactory} combined with {@link CloudUserService}:
 * an end user logs in with a username/password, gets a JWT back, and every {@code /files}
 * request after that is scoped to their own uploads - see {@link
 * DefaultRestFactory#DefaultRestFactory(DataFactory, AuthService, CloudUserService)}.
 *
 * <p>Unlike {@link RestFactorySample} (unauthenticated, generic {@code /notes} CRUD),
 * this sample exercises the JWT-gated constructor and mounts no generic resource at all
 * - {@code /auth/login} and {@code /files} are the only two routes, both mounted
 * automatically by that constructor.
 *
 * <p>Wires the same kind of throwaway, local JSON-file-based {@code DatabaseProvider}
 * {@link RestFactorySample} does (a different directory, so the two samples never
 * collide), an {@link InMemoryKeyEncryptionService}, and a {@link FileFactory} backed by
 * an always-{@code true} {@code ConnectivityChecker} - a real {@code
 * InternetConnectivityChecker} would defer uploads into the pending-upload queue instead
 * of actually storing them on a machine/sandbox with no outbound network access, which
 * would make this sample's own upload silently "succeed" without persisting anything.
 * Seeds one fixed dummy account at startup ({@link #DEMO_EMAIL}/{@link #DEMO_PASSWORD}) by
 * registering an {@link AuthUser} directly rather than driving {@link AuthService#register}/
 * {@link AuthService#confirmRegistration}'s full e-mail-verification round trip - this sample
 * has no real mailbox to receive the verification code in, so it shortcuts straight to the
 * same {@link AuthUser} shape {@link AuthService#confirmRegistration} itself would produce -
 * delete this sample's throwaway directory between runs, or re-seeding the same email adds a
 * second, separate account under a fresh id rather than reusing the first.
 *
 * <p>Run directly from an IDE, or via {@code mvn -pl cloud-driver-plugin -am
 * test-compile} followed by running this class on the built classpath. Once running,
 * try (from a separate terminal):
 * <pre>{@code
 * # 1. log in, copy the "token" value from the response
 * curl -X POST localhost:7071/auth/login \
 *   -d '{"username":"demo@example.com","password":"demo-password-123"}'
 *
 * # 2. use it - every response/listing below is scoped to this one user
 * TOKEN="<paste the token here>"
 * curl -X POST localhost:7071/files -H "Authorization: Bearer $TOKEN" \
 *   -d '{"fileName":"hello.txt","contentBase64":"aGVsbG8gd29ybGQ="}'
 * curl localhost:7071/files -H "Authorization: Bearer $TOKEN"
 * curl -X DELETE localhost:7071/files/<id from the upload response> \
 *   -H "Authorization: Bearer $TOKEN"
 * }</pre>
 */
public final class RestFactoryCloudUserSample {

    /** The port this sample's HTTP server listens on. */
    private static final int PORT = 7071;

    /** The seeded demo account's username/email. */
    private static final String DEMO_EMAIL = "demo@example.com";

    /** The seeded demo account's password. */
    private static final String DEMO_PASSWORD = "demo-password-123";

    // A fixed 32+ byte key is fine for a throwaway sample - never do this for anything
    // real, see CLAUDE.md's "Local dev secrets" (JWT_SIGNING_KEY must come from the
    // environment, generated via `openssl rand -base64 32`).
    /** Fixed JWT signing key for this throwaway sample only - never reuse for anything real. */
    private static final String DUMMY_SIGNING_KEY = "sample-signing-key-do-not-use-in-prod!!";

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private RestFactoryCloudUserSample() {
    }

    /**
     * Wires a JWT-gated {@link RestFactory} (login + per-user {@code /files} routes) and
     * starts listening on {@link #PORT} - see this class's own Javadoc for the full
     * walkthrough.
     *
     * @param args unused
     * @throws Exception if any of the throwaway local database/database-driver setup fails
     */
    public static void main(final String[] args) throws Exception {

        new DefaultFileProvider();
        new DatabaseRepositoryRegistry(false);

        final Credentials credentials = new Credentials(
                Path.of("cloud-driver-rest-cloud-user-sample", "database.json"),
                Path.of("cloud-driver-rest-cloud-user-sample", "data")
        );

        final DatabaseProvider databaseProvider = DatabaseRepository.getInstance()
                .registerDatabaseProviderAsync(0, DatabaseType.JSON, credentials)
                .join();

        final KeyEncryptionService keyEncryptionService = new InMemoryKeyEncryptionService();
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(keyEncryptionService);
        final DataFactory dataFactory = new DefaultDataFactory(new EntityDatabaseClient(databaseProvider, envelopeEncryptionService));
        final FileFactory fileFactory = new DefaultFileFactory(dataFactory, new InMemoryPendingUploadCache(), () -> true);

        final PasswordHasher passwordHasher = new Argon2idPasswordHasher();
        final JwtSigner jwtSigner = new JjwtSigner(DUMMY_SIGNING_KEY);
        final EmailSender emailSender = new LoggingEmailSender(Logger.getLogger(RestFactoryCloudUserSample.class.getName()));
        final AuditLogService auditLogService = new AuditLogServiceImpl(dataFactory, SecretRedactor::redact);
        final CloudUserService cloudUserService = new CloudUserService(dataFactory, fileFactory, auditLogService);
        final AuthService authService = new AuthService(dataFactory, passwordHasher, jwtSigner, emailSender, cloudUserService, auditLogService);

        // See this class's own Javadoc - seeded directly rather than through
        // AuthService#register/#confirmRegistration's e-mail-verification round trip.
        dataFactory.register(new AuthUser(UUID.randomUUID().toString(), DEMO_EMAIL, passwordHasher.hash(DEMO_PASSWORD.toCharArray())));

        final RestFactory restFactory = new DefaultRestFactory(dataFactory, authService, cloudUserService);
        restFactory.start(PORT);

        System.out.println("RestFactoryCloudUserSample listening on http://localhost:" + PORT);
        System.out.println("Demo account: " + DEMO_EMAIL + " / " + DEMO_PASSWORD);
        System.out.println();
        System.out.println("1. Log in, copy the \"token\" value from the response:");
        System.out.println("   curl -X POST localhost:" + PORT + "/auth/login -d '{\"username\":\"" + DEMO_EMAIL + "\",\"password\":\"" + DEMO_PASSWORD + "\"}'");
        System.out.println();
        System.out.println("2. Use it - every call below is scoped to this one user:");
        System.out.println("   TOKEN=\"<paste the token here>\"");
        System.out.println("   curl -X POST localhost:" + PORT + "/files -H \"Authorization: Bearer $TOKEN\" -d '{\"fileName\":\"hello.txt\",\"contentBase64\":\"aGVsbG8gd29ybGQ=\"}'");
        System.out.println("   curl localhost:" + PORT + "/files -H \"Authorization: Bearer $TOKEN\"");
        System.out.println("   curl -X DELETE localhost:" + PORT + "/files/<id from the upload response> -H \"Authorization: Bearer $TOKEN\"");

    }

}

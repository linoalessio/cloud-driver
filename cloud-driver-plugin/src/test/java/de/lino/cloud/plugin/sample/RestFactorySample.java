package de.lino.cloud.plugin.sample;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.plugin.factory.DefaultDataFactory;
import de.lino.cloud.plugin.factory.DefaultRestFactory;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.develop.InMemoryKeyEncryptionService;
import de.lino.database.DatabaseRepository;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.file.DefaultFileProvider;

import java.nio.file.Path;

/**
 * Standalone, runnable worked example (not an {@code mvn test} target - see "Build" in
 * {@code CLAUDE.md} for this repo's "sample under {@code src/test}, run {@code main}
 * directly" convention) demonstrating {@link RestFactory} on its own, deliberately
 * without going through {@code CloudDriver}/{@code DefaultCloudDriver} - which would
 * additionally construct a {@code Terminal} requiring a real pseudo-terminal (see {@code
 * Terminal}'s own Javadoc), unnecessary just to show {@code RestFactory} itself and a
 * likely surprise if run from an IDE's Run window rather than a real terminal.
 *
 * <p>Wires a throwaway, local JSON-file-based {@code DatabaseProvider} (no live external
 * database needed) and an {@link InMemoryKeyEncryptionService} (KEK material lost on
 * restart - fine for a disposable sample, not for anything meant to persist across runs),
 * then mounts a dummy {@link Note} entity on all four verbs via the unauthenticated
 * {@link DefaultRestFactory} constructor - local development only, see that class's own
 * Javadoc for the {@code ApiKey}/{@code AuthService}-gated alternatives it also offers.
 *
 * <p>Run directly from an IDE, or via {@code mvn -pl cloud-driver-plugin -am
 * test-compile} followed by running this class on the built classpath. Once running,
 * try (from a separate terminal):
 * <pre>{@code
 * curl -X POST localhost:7070/notes -d '{"id":"1","text":"hello"}'
 * curl localhost:7070/notes
 * curl localhost:7070/notes/1
 * curl -X PUT localhost:7070/notes/1 -d '{"id":"1","text":"updated"}'
 * curl -X DELETE localhost:7070/notes/1
 * }</pre>
 */
public final class RestFactorySample {

    /** The port this sample's HTTP server listens on. */
    private static final int PORT = 7070;

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private RestFactorySample() {
    }

    /**
     * Wires an unauthenticated {@link RestFactory} exposing {@link Note} and starts listening
     * on {@link #PORT} - see this class's own Javadoc for the full walkthrough.
     *
     * @param args unused
     */
    public static void main(final String[] args) {

        // Registers the DatabaseProvider/DatabaseSection implementations
        // database-driver-plugin ships (JSON included) - needed before
        // DatabaseRepository.getInstance() can hand one out.
        new DefaultFileProvider();
        new DatabaseRepositoryRegistry(false);

        // Throwaway local directory - deleted/recreated freely between runs, holds no
        // real data. See Credentials(Path, Path) for the JSON-provider-only constructor.
        final Credentials credentials = new Credentials(
                Path.of("cloud-driver-rest-sample", "database.json"),
                Path.of("cloud-driver-rest-sample", "data")
        );

        final DatabaseProvider databaseProvider = DatabaseRepository.getInstance()
                .registerDatabaseProviderAsync(0, DatabaseType.JSON, credentials)
                .join();

        final KeyEncryptionService keyEncryptionService = new InMemoryKeyEncryptionService();
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(keyEncryptionService);
        final DataFactory dataFactory = new DefaultDataFactory(new EntityDatabaseClient(databaseProvider, envelopeEncryptionService));

        final RestFactory restFactory = new DefaultRestFactory(dataFactory);

        // All four verbs must be registered before start() - Javalin builds every route
        // up front, in the config block passed to Javalin.create.
        restFactory.register("/notes", Note.class);
        restFactory.fetch("/notes", Note.class);
        restFactory.update("/notes", Note.class);
        restFactory.delete("/notes", Note.class);

        restFactory.start(PORT);

        System.out.println("RestFactorySample listening on http://localhost:" + PORT);
        System.out.println("  curl -X POST localhost:" + PORT + "/notes -d '{\"id\":\"1\",\"text\":\"hello\"}'");
        System.out.println("  curl localhost:" + PORT + "/notes");
        System.out.println("  curl localhost:" + PORT + "/notes/1");
        System.out.println("  curl -X PUT localhost:" + PORT + "/notes/1 -d '{\"id\":\"1\",\"text\":\"updated\"}'");
        System.out.println("  curl -X DELETE localhost:" + PORT + "/notes/1");

    }

}

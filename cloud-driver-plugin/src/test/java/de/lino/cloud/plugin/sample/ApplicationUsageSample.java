package de.lino.cloud.plugin.sample;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.application.Application;
import de.lino.cloud.api.factory.ApplicationFactory;
import de.lino.cloud.plugin.DefaultCloudAPI;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.InMemoryKeyEncryptionService;
import de.lino.database.DatabaseRepository;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.file.DefaultFileProvider;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Worked example of the {@code de.lino.cloud.api.application} package: define
 * an {@link Application} extension backed by the {@code application.json} in
 * this module's test resources, let it self-register with {@link
 * CloudAPI#getInstance()}'s {@link ApplicationFactory} on construction, then
 * start and stop it through that factory rather than by calling its
 * lifecycle methods directly.
 *
 * <p>{@link Application}'s constructor requires a {@link CloudAPI}
 * implementation to already be installed (see {@link Application#Application()}),
 * so this sample lives here rather than in {@code cloud-driver-api} - only
 * {@code cloud-driver-plugin} has one ({@link DefaultCloudAPI}). The database/
 * encryption setup below is otherwise identical to {@link CloudAPIUsageSample}.
 *
 * <p>This class is demo code, not part of the module's published API - it
 * lives under {@code src/test} so it is never shipped in the built jar. Run
 * {@link #main} directly to see it end-to-end.
 */
public final class ApplicationUsageSample {

    /**
     * A minimal {@link Application} extension. Its {@link
     * Application#getApplicationProperties()} come entirely from {@code
     * application.json} on the classpath - nothing is hardcoded here.
     */
    static final class DemoApplication extends Application {

        @Override
        public void onLoading() {
            System.out.println(getApplicationProperties().getApplicationName() + ": loading");
        }

        @Override
        public void onRunning(final String[] args) {
            System.out.println(getApplicationProperties().getApplicationName() + ": running with args " + Arrays.toString(args));
        }

        @Override
        public void onEnding() {
            System.out.println(getApplicationProperties().getApplicationName() + ": ending");
        }

        @Override
        public void onException(final RuntimeException reason) {
            System.out.println(getApplicationProperties().getApplicationName() + ": exception - " + reason.getMessage());
        }
    }

    public static void main(final String[] args) {

        // --- 1. Install a CloudAPI implementation - required before any
        // Application subclass can be constructed. Same JSON file-based
        // setup as CloudAPIUsageSample; only the ApplicationFactory half of
        // it is actually used below.
        new DefaultFileProvider();
        new DatabaseRepositoryRegistry(true);

        final Path repositoryRoot = Path.of(System.getProperty("java.io.tmpdir"), "cloud-driver-application-sample-db");
        final Credentials credentials = new Credentials(repositoryRoot.resolve("credentials.json"), repositoryRoot.resolve("data"));
        final DatabaseProvider databaseProvider = DatabaseRepository.getInstance().registerDatabaseProvider(0, DatabaseType.JSON, credentials);
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(new InMemoryKeyEncryptionService());
        final CloudAPI cloudAPI = DefaultCloudAPI.setInstance(databaseProvider, envelopeEncryptionService);
        final ApplicationFactory applicationFactory = cloudAPI.getApplicationFactory();

        // --- 2. Construct the extension - this alone loads application.json
        // into ApplicationProperties, detects the build tool, and registers
        // the instance with applicationFactory. No manual wiring needed.
        final DemoApplication application = new DemoApplication();
        System.out.println("Loaded properties: " + application.getApplicationProperties());
        System.out.println("Detected build type: " + application.getProjectBuildType());

        // --- 3. applicationFactory knows about it without being told directly ---
        System.out.println("Registered applications: " + applicationFactory.getApplications());
        System.out.println("Looked up by name: " + applicationFactory.find("demo-application"));

        // --- 4. Starting/stopping goes through the factory, not the instance ---
        applicationFactory.startAll(args);
        System.out.println("Status after start: " + application.getApplicationProperties().getApplicationStatus());

        applicationFactory.stopAll();
        System.out.println("Status after stop: " + application.getApplicationProperties().getApplicationStatus());
    }
}

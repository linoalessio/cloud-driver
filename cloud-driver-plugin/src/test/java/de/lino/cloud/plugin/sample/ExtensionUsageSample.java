package de.lino.cloud.plugin.sample;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.plugin.DefaultCloudAPI;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.InMemoryKeyEncryptionService;
import de.lino.database.DatabaseRepository;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.file.DefaultFileProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Worked example of the {@code de.lino.cloud.api.extension} package: define
 * an {@link Extension} extension backed by the {@code extension.json} in
 * this module's test resources, construct it and register it explicitly with
 * {@link ExtensionFactory#register(Extension)}, then start and stop it
 * through {@link ExtensionFactory} rather than by calling its lifecycle
 * methods directly.
 *
 * <p>{@link Extension}'s constructor requires no {@link CloudAPI}
 * implementation to be installed, but registering it does, so this sample
 * lives here rather than in {@code cloud-driver-api} - only {@code
 * cloud-driver-plugin} has one ({@link DefaultCloudAPI}). The database/
 * encryption setup below is otherwise identical to {@link CloudAPIUsageSample}.
 *
 * <p>This class is demo code, not part of the module's published API - it
 * lives under {@code src/test} so it is never shipped in the built jar. Run
 * {@link #main} directly to see it end-to-end.
 */
public final class ExtensionUsageSample {

    /**
     * A minimal {@link Extension} extension. Its {@link
     * Extension#getExtensionProperties()} come entirely from {@code
     * extension.json} on the classpath - nothing is hardcoded here.
     */
    static final class DemoExtension extends Extension {

        @Override
        public void onLoading() {
            System.out.println(getExtensionProperties().getExtensionName() + ": loading");
        }

        @Override
        public void onRunning(final String[] args) {
            System.out.println(getExtensionProperties().getExtensionName() + ": running with args " + Arrays.toString(args));
        }

        @Override
        public void onEnding() {
            System.out.println(getExtensionProperties().getExtensionName() + ": ending");
        }

        @Override
        public void onException(final RuntimeException reason) {
            System.out.println(getExtensionProperties().getExtensionName() + ": exception - " + reason.getMessage());
        }
    }

    public static void main(final String[] args) throws IOException {

        // --- 1. Install a CloudAPI implementation. Same JSON file-based setup
        // as CloudAPIUsageSample; only the ExtensionFactory half of it is
        // actually used below.
        new DefaultFileProvider();
        new DatabaseRepositoryRegistry(true);

        final Path repositoryRoot = Path.of(System.getProperty("java.io.tmpdir"), "cloud-driver-extension-sample-db");
        final Credentials credentials = new Credentials(repositoryRoot.resolve("credentials.json"), repositoryRoot.resolve("data"));
        final DatabaseProvider databaseProvider = DatabaseRepository.getInstance().registerDatabaseProvider(0, DatabaseType.JSON, credentials);
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(new InMemoryKeyEncryptionService());
        final CloudAPI cloudAPI = DefaultCloudAPI.setInstance(databaseProvider, envelopeEncryptionService);
        final ExtensionFactory extensionFactory = cloudAPI.getExtensionFactory();

        // --- 2. Construct the extension - this loads extension.json into
        // ExtensionProperties and detects the build tool - then register it
        // explicitly. extensionFactory does not know about it until this call.
        final DemoExtension extension = new DemoExtension();
        extensionFactory.register(extension);
        System.out.println("Loaded properties: " + extension.getExtensionProperties());
        System.out.println("Detected build type: " + extension.getProjectBuildType());

        // --- 3. extensionFactory now knows about it ---
        System.out.println("Registered extensions: " + extensionFactory.getExtensions());
        System.out.println("Looked up by name: " + extensionFactory.findByName("demo-extension"));

        // --- 4. Starting/stopping goes through the factory, not the instance ---
        extensionFactory.startAll(args);
        System.out.println("Status after start: " + extension.getExtensionProperties().getExtensionStatus());

        extensionFactory.stopAll();
        System.out.println("Status after stop: " + extension.getExtensionProperties().getExtensionStatus());
    }
}

package de.lino.cloud.bootstrap;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.plugin.DefaultCloudAPI;
import de.lino.cloud.plugin.factory.DefaultFileFactory;
import de.lino.cloud.plugin.file.pending.PendingUploadScheduler;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.DatabaseKeyEncryptionService;
import de.lino.database.DatabaseRepository;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;

import java.time.Duration;

public final class CloudBootstrapSample {

    public static void main(String[] args) throws Exception {

        new DatabaseRepositoryRegistry(false);

        final Credentials credentials = new Credentials(Constraints.CONFIGURATION_PATH.resolve("database.json"), "82.165.48.39", "cloud_driver_postgres", "XWd8C5HmN2n3bpI5cAMr", 11042, "cloud_driver");
        //final Credentials credentials = new Credentials(Constraints.CONFIGURATION_PATH.resolve("database.json"), Constraints.CONFIGURATION_PATH.resolve("data"));

        final DatabaseProvider databaseProvider = DatabaseRepository.getInstance().registerDatabaseProvider(0, DatabaseType.POSTGRES_SQL, credentials);

        final KeyEncryptionService keyEncryptionService = new DatabaseKeyEncryptionService(databaseProvider.createSection("kek"));
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(keyEncryptionService);

        final CloudAPI cloudAPI = DefaultCloudAPI.setInstance(databaseProvider, envelopeEncryptionService);

        // This Postgres instance is reached over the network - an upload attempted
        // while offline would otherwise just fail. DefaultFileFactory (returned by
        // getFileFactory()) already defers an upload into its own PendingUploadCache
        // instead of failing it when CloudAPI#getConnectivityChecker() reports no
        // connectivity; a PendingUploadScheduler retries everything queued there once
        // connectivity returns, checked every 30 seconds. It retries via DataFactory
        // directly rather than through the FileFactory - see its class Javadoc.
        final FileFactory fileFactory = cloudAPI.getFileFactory();
        final DataFactory dataFactory = cloudAPI.getDataFactory();

        final PendingUploadScheduler pendingUploadScheduler = new PendingUploadScheduler(
                dataFactory, ((DefaultFileFactory) fileFactory).getPendingUploadCache(), cloudAPI.getConnectivityChecker()
        );
        pendingUploadScheduler.start(Duration.ofSeconds(30));

    }

}

package de.lino.cloud.bootstrap;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.plugin.DefaultCloudAPI;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.DatabaseKeyEncryptionService;
import de.lino.database.DatabaseRepository;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.entity.Serialized;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.util.List;

public class CloudBootstrap {

    @Getter @ToString
    @RequiredArgsConstructor
    public static class TestData extends Serialized {

        private final int id;
        private final String name;

        @Override
        public List<String> keysOf() {
            return List.of(String.valueOf(id));
        }

    }

    public static void main(String[] args) {

        new DatabaseRepositoryRegistry(false);

        final Credentials credentials = new Credentials(Constraints.CONFIGURATION_PATH.resolve("database.json"), "82.165.48.39", "cloud_driver_postgres", "XWd8C5HmN2n3bpI5cAMr", 11042, "cloud_driver");
        final DatabaseProvider databaseProvider = DatabaseRepository.getInstance().registerDatabaseProvider(0, DatabaseType.POSTGRES_SQL, credentials);

        final KeyEncryptionService keyEncryptionService = new DatabaseKeyEncryptionService(databaseProvider.createSection("kek"));
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(keyEncryptionService);

        final CloudAPI cloudAPI = DefaultCloudAPI.setInstance(databaseProvider, envelopeEncryptionService);

        final TestData testData = new TestData(1, "Lino Alessio Kauschinger");

        try {

            //cloudAPI.getDataFactory().register(testData);
            //cloudAPI.getDataFactory().delete("1", TestData.class);
            final TestData receivedData = cloudAPI.getDataFactory().findById("1", TestData.class).orElseThrow();
            System.out.println("Received data: " + receivedData);

        } catch (DatabaseClientException | KeyWrapException | AuthenticationFailedException exception) {
            throw new RuntimeException(exception);
        }

    }

}

package de.lino.cloud.bootstrap;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.FileIntegrityException;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.utility.Asserts;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class CloudBootstrapSample {

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

    public static void main(String[] args) throws Exception {

        new DatabaseRepositoryRegistry(false);

        final Credentials credentials = new Credentials(Constraints.CONFIGURATION_PATH.resolve("database.json"), "82.165.48.39", "cloud_driver_postgres", "XWd8C5HmN2n3bpI5cAMr", 11042, "cloud_driver");
        final DatabaseProvider databaseProvider = DatabaseRepository.getInstance().registerDatabaseProvider(0, DatabaseType.POSTGRES_SQL, credentials);

        final KeyEncryptionService keyEncryptionService = new DatabaseKeyEncryptionService(databaseProvider.createSection("kek"));
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(keyEncryptionService);

        final CloudAPI cloudAPI = DefaultCloudAPI.setInstance(databaseProvider, envelopeEncryptionService);
        final FileFactory fileFactory = cloudAPI.getFileFactory();

        Asserts.runWallTimeTest(() -> {

            try {

                final Path path = Path.of("CLAUDE.md");
                final StoredFile storedFile = new StoredFile(
                        UUID.randomUUID().toString(),
                        path.getFileName().toString(),
                        Files.readAllBytes(path)
                );

                fileFactory.upload(storedFile);
                System.out.println("Uploaded " + storedFile);

                final StoredFile downloaded = fileFactory.download(storedFile.fileId());
                System.out.println("Downloaded " + downloaded + " (content matches original: "
                        + Arrays.equals(storedFile.content(), downloaded.content()) + ")");

                downloaded.downloadToDevice(Path.of(System.getProperty("user.home"), "Downloads"));

                fileFactory.delete(storedFile.fileId());

            } catch (DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException | IOException exception) {
                throw new RuntimeException(exception);
            }

        });

    }

}

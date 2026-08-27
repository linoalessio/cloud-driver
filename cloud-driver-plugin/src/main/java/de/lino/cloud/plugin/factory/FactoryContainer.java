package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.factory.*;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.plugin.file.InMemoryPendingUploadCache;
import de.lino.cloud.plugin.security.database.EntityDatabaseClient;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.database.database.DatabaseProvider;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;

@Getter
public class FactoryContainer implements IFactoryContainer {

    private final DataFactory dataFactory;
    private final FileFactory fileFactory;
    private final ExtensionFactory extensionFactory;
    private final EventFactory eventFactory;
    private final RestFactory restFactory;

    @SneakyThrows
    public FactoryContainer(@NonNull final DatabaseProvider databaseProvider, @NonNull final EnvelopeEncryptionService envelopeEncryptionService, @NonNull final ConnectivityChecker connectivityChecker) {

        this.dataFactory = new DefaultDataFactory(new EntityDatabaseClient(databaseProvider, envelopeEncryptionService));
        this.fileFactory = new DefaultFileFactory(dataFactory, new InMemoryPendingUploadCache(), connectivityChecker);
        this.extensionFactory = new DefaultExtensionFactory();
        this.eventFactory = new DefaultEventFactory();
        this.restFactory = new DefaultRestFactory(dataFactory);

    }

}

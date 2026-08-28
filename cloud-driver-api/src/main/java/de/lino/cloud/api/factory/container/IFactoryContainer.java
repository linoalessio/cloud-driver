package de.lino.cloud.api.factory.container;

import de.lino.cloud.api.factory.*;
import de.lino.cloud.api.user.ICloudUserService;

/**
 * Bundles every persistence/extension/event/REST facet a {@link
 * de.lino.cloud.api.CloudDriver} implementation is constructed with, reached
 * through {@link de.lino.cloud.api.CloudDriver#getFactoryContainer()}.
 */
public interface
IFactoryContainer {
    
    /**
     * Returns the entity-persistence facet.
     *
     * @return the {@link DataFactory}
     */
    DataFactory getDataFactory();

    /**
     * Returns the file-persistence facet.
     *
     * @return the {@link FileFactory}
     */
    FileFactory getFileFactory();

    /**
     * Returns the extension-lifecycle facet.
     *
     * @return the {@link ExtensionFactory}
     */
    ExtensionFactory getExtensionFactory();

    /**
     * Returns the event facet.
     *
     * @return the {@link EventFactory}
     */
    EventFactory getEventFactory();

    /**
     * Returns the REST-exposure facet. Unauthenticated by default.
     *
     * @return the {@link RestFactory}
     */
    RestFactory getRestFactory();

}

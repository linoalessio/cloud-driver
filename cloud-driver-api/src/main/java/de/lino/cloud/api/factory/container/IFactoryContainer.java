package de.lino.cloud.api.factory.container;

import de.lino.cloud.api.factory.*;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Returns the object-storage facet backing {@link #getFileFactory()}'s optional S3-backed
     * {@code StoredFile} content path, or {@code null} if this deployment doesn't have one
     * configured - the same "may not exist yet"/opt-in contract {@code
     * de.lino.cloud.api.factory.service.IServiceContainer}'s facets already carry, except this one
     * is fixed for the container's whole lifetime rather than published later by an extension.
     *
     * @return the {@link ObjectStorageService}, or {@code null} if S3-backed storage isn't configured
     */
    @Nullable
    ObjectStorageService getObjectStorageService();

}

package de.lino.cloud.api.factory.container;

import de.lino.cloud.api.event.database.FileChangeListenerRegistry;
import de.lino.cloud.api.factory.*;
import de.lino.cloud.api.redis.RedisSupport;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import org.jetbrains.annotations.Nullable;

/**
 * Bundles every persistence/extension/event/REST facet a {@link
 * de.lino.cloud.api.CloudDriver} implementation is constructed with, reached
 * through {@link de.lino.cloud.api.CloudDriver#getFactoryContainer()}.
 */
public interface IFactoryContainer {
    
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
     * Returns the object-s3storage facet backing {@link #getFileFactory()}'s optional S3-backed
     * {@code StoredFile} content path, or {@code null} if this deployment doesn't have one
     * configured - the same "may not exist yet"/opt-in contract {@code
     * de.lino.cloud.api.factory.service.IServiceContainer}'s facets already carry, except this one
     * is fixed for the container's whole lifetime rather than published later by an extension.
     *
     * @return the {@link ObjectStorageService}, or {@code null} if S3-backed s3storage isn't configured
     */
    @Nullable
    ObjectStorageService getObjectStorageService();

    /**
     * Returns the fan-out registry every {@link de.lino.cloud.api.event.database.DatabaseWatchEvent}
     * notification is routed through, so multiple independent addons can each react to a {@code
     * StoredFile} table change without touching {@link EventFactory}'s own one-handler-per-class
     * contract - see {@link FileChangeListenerRegistry}'s own Javadoc for the full reasoning.
     * Always non-{@code null}, unlike every {@code IServiceContainer} facet - constructed
     * unconditionally alongside every other facet here, regardless of whether {@code
     * cloud-driver-watcher} is actually running on this deployment.
     *
     * @return the {@link FileChangeListenerRegistry}, never {@code null}
     */
    FileChangeListenerRegistry getFileChangeListenerRegistry();

    /**
     * Returns the optional Redis facet, or {@code null} if this deployment has no reachable Redis
     * configured - the same fixed-for-the-container's-lifetime, opt-in contract {@link
     * #getObjectStorageService()} carries, and like it, every caller must handle {@code null} by
     * falling back to its own in-process behavior rather than failing. See {@link RedisSupport}'s
     * own Javadoc for what belongs behind this facet, and in particular what must never be stored
     * through it.
     *
     * @return the {@link RedisSupport}, or {@code null} if Redis isn't configured or wasn't reachable at boot
     */
    @Nullable
    RedisSupport getRedisSupport();

}

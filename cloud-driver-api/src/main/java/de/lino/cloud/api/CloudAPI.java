package de.lino.cloud.api;

import de.lino.cloud.api.factory.*;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.database.database.entity.Serialized;
import org.jetbrains.annotations.Nullable;

/**
 * Facade over the six things a {@code cloud-driver} embedder needs: meta
 * persistence/encryption via {@link #getDataFactory()}, file upload/download
 * via {@link #getFileFactory()}, extension lifecycle management via {@link
 * #getExtensionFactory()}, outbound-connectivity reporting via {@link
 * #getConnectivityChecker()}, event registration/dispatch via {@link
 * #getEventFactory()}, and REST exposure of entities via {@link
 * #getRestFactory()}. {@link CloudAPI} itself holds no persistence or
 * lifecycle logic - it is deliberately thin, exposing only these six
 * abstract getters plus the shared-instance accessor below; a concrete
 * implementation (e.g. {@code DefaultCloudAPI} in {@code cloud-driver-plugin})
 * supplies the actual facets.
 *
 * <p>{@link de.lino.cloud.api.file.StoredFile} - a file of any content type -
 * is itself a {@code Serialized} domain meta, so {@link #getFileFactory()}
 * is persisted through the same underlying mechanism as {@link
 * #getDataFactory()} rather than a second, independent persistence path -
 * see {@link FileFactory}'s class Javadoc.
 *
 * <p>Exactly one implementation is installed process-wide via a
 * static factory method on that implementation (e.g. {@code
 * DefaultCloudAPI.setInstance(DatabaseProvider, EnvelopeEncryptionService)}),
 * which assigns {@link #INSTANCE} and makes it retrievable through {@link
 * #getInstance()}. Nothing must call {@link #getInstance()} before that
 * installation has happened - notably, an {@code Extension} subclass's
 * constructor self-registers with {@link #getExtensionFactory()} and will
 * throw a {@link NullPointerException} if constructed too early.
 */
public abstract class CloudAPI {

    /**
     * Backing field for {@link #getInstance()}, assigned once by a concrete
     * implementation's static installer (e.g. {@code
     * DefaultCloudAPI.setInstance}). {@code volatile} so the installing
     * thread's write is visible to every other thread's subsequent {@link
     * #getInstance()} read without further synchronization.
     */
    protected static volatile CloudAPI INSTANCE;

    /**
     * The shared {@link CloudAPI} instance, or {@code null} if no
     * implementation has installed itself yet (e.g. {@code
     * DefaultCloudAPI.setInstance} has not been called).
     */
    @Nullable
    public synchronized static CloudAPI getInstance() {
        return INSTANCE;
    }

    /**
     * The meta-persistence facet of this API: register/update/fetch/delete
     * (single, batch, and async variants) for encrypted entities. See {@link
     * DataFactory} for the full contract.
     */
    public abstract DataFactory getDataFactory();

    /**
     * The file-persistence facet of this API: upload/download/delete (single,
     * batch, and async variants) for encrypted files of any content type. See
     * {@link FileFactory} for the full contract.
     */
    public abstract FileFactory getFileFactory();

    /**
     * The extension-lifecycle facet of this API: registers, starts, and
     * stops {@code Extension} extensions. See {@link ExtensionFactory}
     * for the full contract.
     */
    public abstract ExtensionFactory getExtensionFactory();

    /**
     * The outbound-connectivity-reporting facet of this API: whether a
     * network connection is currently available. See {@link
     * ConnectivityChecker} for the full contract.
     */
    public abstract ConnectivityChecker getConnectivityChecker();

    /**
     * The event facet of this API: registers, looks up, unregisters, and
     * dispatches {@code Event} events. See {@link EventFactory} for the full
     * contract.
     */
    public abstract EventFactory getEventFactory();

    /**
     * The REST-exposure facet of this API: mounts {@link Serialized}
     * entities already reachable through {@link #getDataFactory()} onto an
     * HTTP API. Unlike the other five facets, the returned {@link
     * RestFactory} is unauthenticated by default - wrap the {@code
     * register}/{@code fetch}/{@code update}/{@code delete} calls this
     * exposes behind an {@code ApiKeyAuthenticator} check (or construct a
     * {@code DefaultRestFactory} directly with one) before exposing it off
     * of {@code localhost}. See {@link RestFactory} for the full contract.
     */
    public abstract RestFactory getRestFactory();

}

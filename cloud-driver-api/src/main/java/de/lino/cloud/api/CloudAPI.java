package de.lino.cloud.api;

import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.DataFactory;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;

/**
 * Facade over the two things a {@code cloud-driver} embedder needs: entity
 * persistence/encryption via {@link #getDataFactory()} and extension
 * lifecycle management via {@link #getExtensionFactory()}. {@link CloudAPI}
 * itself holds no persistence or lifecycle logic - it is deliberately thin,
 * exposing only these two abstract getters plus the shared-instance accessor
 * below; a concrete implementation (e.g. {@code DefaultCloudAPI} in {@code
 * cloud-driver-plugin}) supplies the actual factories.
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
     * The entity-persistence facet of this API: register/update/fetch/delete
     * (single, batch, and async variants) for encrypted entities. See {@link
     * DataFactory} for the full contract.
     */
    public abstract DataFactory getDataFactory();

    /**
     * The extension-lifecycle facet of this API: registers, starts, and
     * stops {@code Extension} extensions. See {@link ExtensionFactory}
     * for the full contract.
     */
    public abstract ExtensionFactory getExtensionFactory();

}

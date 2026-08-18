package de.lino.cloud.api;

import de.lino.cloud.api.factory.ApplicationFactory;
import de.lino.cloud.api.factory.DataFactory;
import org.jetbrains.annotations.Nullable;

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

    public abstract DataFactory getDataFactory();

    public abstract ApplicationFactory getApplicationFactory();

}

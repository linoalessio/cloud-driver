package de.lino.cloud.api.factory.service;

import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.user.ICloudUserService;
import lombok.NonNull;

/**
 * Bundles higher-level, cross-cutting services built on top of the raw
 * persistence facets in {@link de.lino.cloud.api.factory.container.IFactoryContainer} -
 * currently just the {@link ICloudUserService} used to scope end-user file
 * ownership over the REST API.
 */
public interface IServiceContainer {

    /**
     * Returns the end-user file-ownership service.
     *
     * @return the {@link ICloudUserService}
     */
    @NonNull
    ICloudUserService getCloudUserService();

    @NonNull
    IAuthService getAuthService();

}

package de.lino.cloud.plugin.factory.container;

import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.user.ICloudUserService;
import lombok.NonNull;

/**
 * Starts out empty - {@code cloudUserService}/{@code authService} are only ever
 * published later, by {@code cloud-driver-extensions-rest}'s {@code CloudRestExtension},
 * once it has actually built the JWT-authenticated {@code RestFactory} those two are
 * backing. See {@link IServiceContainer}'s Javadoc for why this container can never be
 * built eagerly from the {@code CloudDriver}-level {@code RestFactory} (that one is
 * deliberately unauthenticated and never carries real {@code AuthService}/{@code
 * CloudUserService} instances).
 */
public class ServiceContainer implements IServiceContainer {

    /** The end-user file-ownership service, {@code null} until {@link #setCloudUserService} publishes one. */
    private volatile ICloudUserService cloudUserService;
    /** The login/JWT service, {@code null} until {@link #setAuthService} publishes one. */
    private volatile IAuthService authService;

    /** {@inheritDoc} */
    @Override
    public ICloudUserService getCloudUserService() {
        return this.cloudUserService;
    }

    /** {@inheritDoc} */
    @Override
    public void setCloudUserService(@NonNull final ICloudUserService cloudUserService) {
        this.cloudUserService = cloudUserService;
    }

    /** {@inheritDoc} */
    @Override
    public IAuthService getAuthService() {
        return this.authService;
    }

    /** {@inheritDoc} */
    @Override
    public void setAuthService(@NonNull final IAuthService authService) {
        this.authService = authService;
    }

}

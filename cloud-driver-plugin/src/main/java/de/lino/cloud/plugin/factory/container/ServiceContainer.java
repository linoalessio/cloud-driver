package de.lino.cloud.plugin.factory.container;

import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.user.ICloudUserService;
import lombok.Getter;
import lombok.NonNull;

@Getter
public class ServiceContainer implements IServiceContainer {

    private final ICloudUserService cloudUserService;

    public ServiceContainer(@NonNull final RestFactory restFactory) {
        this.cloudUserService = restFactory.getCloudUserService();
    }

}

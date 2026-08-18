package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.application.Application;
import de.lino.cloud.api.application.info.ApplicationProperties;
import de.lino.cloud.api.factory.ApplicationFactory;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ApplicationFactory} implementation storing registered applications
 * in a {@link ConcurrentHashMap} keyed by {@link
 * ApplicationProperties#getApplicationName()} - safe to register from
 * multiple threads concurrently without external synchronization. Every
 * lifecycle-driving method ({@code start}/{@code stop}/{@code startAll}/...)
 * is inherited from {@link ApplicationFactory}, which implements them
 * generically in terms of {@link #register}/{@link #find}/{@link
 * #getApplications} below.
 */
public final class DefaultApplicationFactory extends ApplicationFactory {

    private final Map<String, Application> applications = new ConcurrentHashMap<>();

    @Override
    public void register(@NonNull final Application application) {
        final String name = application.getApplicationProperties().getApplicationName();
        if (applications.putIfAbsent(name, application) != null) {
            throw new IllegalStateException(
                    "@DefaultApplicationFactory.register: an application named '" + name + "' is already registered"
            );
        }
    }

    @NotNull
    @Override
    public Optional<Application> find(@NonNull final String applicationName) {
        return Optional.ofNullable(applications.get(applicationName));
    }

    @NotNull
    @Override
    public Collection<Application> getApplications() {
        return List.copyOf(applications.values());
    }

}

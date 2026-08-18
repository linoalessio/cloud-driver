package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.cloud.api.factory.ExtensionFactory;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ExtensionFactory} implementation storing registered extensions
 * in a {@link ConcurrentHashMap} keyed by {@link
 * ExtensionProperties#getExtensionName()} - safe to register from
 * multiple threads concurrently without external synchronization. Every
 * lifecycle-driving method ({@code start}/{@code stop}/{@code startAll}/...)
 * is inherited from {@link ExtensionFactory}, which implements them
 * generically in terms of {@link #register}/{@link #findByName}/{@link
 * #getExtensions} below.
 */
public final class DefaultExtensionFactory extends ExtensionFactory {

    private final Map<String, Extension> extensions = new ConcurrentHashMap<>();

    @Override
    public void register(@NonNull final Extension extension) {
        final String name = extension.getExtensionProperties().getExtensionName();
        if (extensions.putIfAbsent(name, extension) != null) {
            throw new IllegalStateException(
                    "@DefaultExtensionFactory.register: an extension named '" + name + "' is already registered"
            );
        }
    }

    @NotNull
    @Override
    public Optional<Extension> findByName(@NonNull final String extensionName) {
        return Optional.ofNullable(extensions.get(extensionName));
    }

    @NotNull
    @Override
    public Collection<Extension> getExtensions() {
        return List.copyOf(extensions.values());
    }

}

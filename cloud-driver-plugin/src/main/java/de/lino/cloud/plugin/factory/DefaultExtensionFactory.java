package de.lino.cloud.plugin.factory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.utility.Constraints;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ExtensionFactory} implementation storing registered extensions in a
 * {@code LinkedHashMap} (via Guava's {@link Maps#newLinkedHashMap()}), keyed
 * by {@link ExtensionProperties#getExtensionName()}, so {@link
 * #getExtensions()} iterates in registration order. Not thread-safe -
 * concurrent {@link #register} calls need external synchronization. Every
 * lifecycle-driving method ({@code start}/{@code stop}/{@code startAll}/...)
 * is inherited from {@link ExtensionFactory}, implemented generically on top
 * of {@link #register}/{@link #findByName}/{@link #getExtensions} below.
 */
public final class DefaultExtensionFactory extends ExtensionFactory {

    /** Registered extensions, keyed by {@link ExtensionProperties#getExtensionName()}, in registration order. */
    private final Map<String, Extension> extensions = Maps.newLinkedHashMap();

    /**
     * Creates {@code Constraints#EXTENSIONS_PATH} if it doesn't already exist.
     *
     * @throws IOException if the directory cannot be created
     */
    public DefaultExtensionFactory() throws IOException {
        if (Files.exists(Constraints.EXTENSIONS_PATH)) return;
        Files.createDirectories(Constraints.EXTENSIONS_PATH);
    }

    /**
     * @throws IllegalStateException if an extension with the same name is already registered
     */
    @Override
    public void register(@NonNull final Extension extension) {
        final String name = extension.getExtensionProperties().getExtensionName();
        if (this.extensions.putIfAbsent(name, extension) != null) {
            throw new IllegalStateException(
                    "@DefaultExtensionFactory.register: an extension named '" + name + "' is already registered"
            );
        }
    }

    @NotNull
    @Override
    public Optional<Extension> findByName(@NonNull final String extensionName) {
        return Optional.ofNullable(this.extensions.get(extensionName));
    }

    @NotNull
    @Override
    public List<Extension> getExtensions() {
        return Lists.newLinkedList(this.extensions.values());
    }

}

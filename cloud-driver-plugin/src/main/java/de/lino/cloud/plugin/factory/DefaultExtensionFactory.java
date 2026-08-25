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
 * {@code LinkedHashMap} (via Guava's {@link Maps#newLinkedHashMap()}) keyed by
 * {@link ExtensionProperties#getExtensionName()} - deliberately linked, not a
 * plain {@code HashMap} or {@link ConcurrentHashMap}, so {@link #getExtensions()}
 * iterates in registration order rather than arbitrary hash order. {@link
 * ExtensionFactory#dependencyOrder()}'s own DFS already places a dependency
 * before whatever depends on it regardless of the order {@link #getExtensions()}
 * hands its extensions to it, so registration order was never required for
 * {@code startAll}'s correctness - but every <em>other</em> consumer of {@link
 * #getExtensions()} (e.g. {@code CloudBootstrap}'s {@code ExtensionRegisterEvent}
 * firing loop, which fires one event per extension by iterating it directly, not
 * through {@code dependencyOrder()}) previously saw registration-confirmation
 * output in arbitrary order under the old {@link ConcurrentHashMap}-backed
 * implementation, which read as "the wrong extension started first" even
 * though actual start order was always correct.
 *
 * <p>Trade-off: unlike the {@link ConcurrentHashMap} this replaced, a plain
 * {@code LinkedHashMap} is <em>not</em> thread-safe - concurrent {@link
 * #register} calls from multiple threads now need external synchronization.
 * Every current caller (e.g. {@code ExtensionFolderScanner.scan(...).forEach(
 * extensionFactory::register)}) registers sequentially on one thread, so this
 * doesn't bite today, but it is a real constraint on future callers.
 *
 * <p>Every lifecycle-driving method ({@code start}/{@code stop}/{@code
 * startAll}/...) is inherited from {@link ExtensionFactory}, which implements
 * them generically in terms of {@link #register}/{@link #findByName}/{@link
 * #getExtensions} below.
 */
public final class DefaultExtensionFactory extends ExtensionFactory {

    /**
     * Every registered extension, keyed by {@link ExtensionProperties#getExtensionName()} -
     * a {@code LinkedHashMap}, not a {@code HashMap}/{@code ConcurrentHashMap}, so iteration
     * order (see {@link #getExtensions()}) matches registration order. See this class's own
     * Javadoc for why that matters and the thread-safety trade-off it comes with.
     */
    private final Map<String, Extension> extensions = Maps.newLinkedHashMap();

    public DefaultExtensionFactory() throws IOException {
        if (Files.exists(Constraints.EXTENSIONS_PATH)) return;
        Files.createDirectories(Constraints.EXTENSIONS_PATH);
    }

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

package de.lino.cloud.api.factory;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.cloud.api.extension.info.ExtensionStatus;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Stores, looks up, and drives the lifecycle of every registered {@link
 * Extension} extension - the single place responsible for <em>all</em>
 * extensions, as opposed to {@link Extension} itself, which only models
 * one. Reached through {@link CloudAPI#getExtensionFactory()}.
 *
 * <p>Only {@link #register}, {@link #findByName}, and {@link #getExtensions} -
 * the actual storage of registered extensions - are abstract; every
 * lifecycle-driving method below is implemented here, generically, in terms
 * of those three, the same way {@link DataFactory}'s {@code *Async} methods
 * are implemented in terms of its abstract sync ones.
 *
 * <p>An {@link Extension} subclass is not registered automatically - after
 * constructing one, register it explicitly with {@link #register}.
 */
public abstract class ExtensionFactory {

    /**
     * Registers {@code extension} under its {@link
     * ExtensionProperties#getExtensionName() extension name}. Call this
     * explicitly once {@code extension} has been fully constructed.
     *
     * @throws NullPointerException if {@code extension} is {@code null}
     * @throws IllegalStateException if an extension with the same name is already registered
     */
    public abstract void register(@NotNull Extension extension);

    /**
     * Looks up a registered extension by its {@link
     * ExtensionProperties#getExtensionName() extension name}.
     *
     * @throws NullPointerException if {@code extensionName} is {@code null}
     */
    @NotNull
    public abstract Optional<Extension> findByName(@NotNull String extensionName);

    /**
     * Every currently registered extension, in no particular order.
     */
    @NotNull
    public abstract Collection<Extension> getExtensions();

    /**
     * Starts every registered extension in dependency order - each
     * extension's {@link ExtensionProperties#getDependencies()
     * dependencies} are started before it is, so by the time {@link #start}
     * runs for it, {@link #start}'s own dependency check always passes for
     * every dependency that is registered.
     *
     * @throws NullPointerException if {@code args} is {@code null}
     * @throws IllegalStateException if the registered extensions' dependencies form a cycle
     */
    public void startAll(@NonNull final String[] args) {
        dependencyOrder().forEach(extension -> start(extension, args));
    }

    /**
     * Async counterpart of {@link #startAll(String[])}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor so the calling
     * thread never blocks on every extension's {@code onLoading()}/{@code
     * onRunning()} - each of which may itself block on I/O. Per-extension
     * failures still surface through that extension's {@link
     * Extension#onException}, exactly as {@link #startAll} handles them
     * synchronously; the returned future only ever fails if {@link
     * #startAll} itself throws (e.g. a dependency cycle).
     *
     * @throws NullPointerException if {@code args} is {@code null}
     */
    @NotNull
    public CompletableFuture<Void> startAllAsync(@NonNull final String[] args) {
        return MultiTaskingFactory.getInstance().runAsync(() -> startAll(args));
    }

    /**
     * Registered extensions ordered so that every extension appears
     * after every other registered extension it (transitively) depends on.
     * A dependency name that is not registered is left for {@link #start} to
     * reject - it does not affect ordering here.
     */
    @NotNull
    private List<Extension> dependencyOrder() {
        final List<Extension> ordered = new ArrayList<>();
        final Set<String> visited = new HashSet<>();
        final Set<String> visiting = new HashSet<>();

        getExtensions().forEach(extension -> visit(extension, visited, visiting, ordered));
        return ordered;
    }

    private void visit(final Extension extension, final Set<String> visited, final Set<String> visiting, final List<Extension> ordered) {
        final String name = extension.getExtensionProperties().getExtensionName();

        if (visited.contains(name)) return;
        if (!visiting.add(name)) throw new IllegalStateException("@ExtensionFactory: dependency cycle detected involving '" + name + "'");

        for (final String dependencyName : extension.getExtensionProperties().getDependencies())
            findByName(dependencyName).ifPresent(dependency -> visit(dependency, visited, visiting, ordered));

        visiting.remove(name);
        visited.add(name);
        ordered.add(extension);
    }

    /**
     * Starts {@code extension}: first checks that every extension named
     * in {@link ExtensionProperties#getDependencies()} is registered and
     * already {@link ExtensionStatus#RUNNING}, then {@link
     * ExtensionStatus#LOADING} followed by {@link Extension#onLoading()},
     * then {@link ExtensionStatus#RUNNING} followed by {@link
     * Extension#onRunning(String[])} with {@code args}. A {@link
     * RuntimeException} thrown by the dependency check or either phase is
     * caught, the extension's status is set to {@link
     * ExtensionStatus#ERROR}, and it is routed to {@link
     * Extension#onException} instead of propagating - so one failing
     * extension does not prevent {@link #startAll} from starting the rest.
     *
     * @throws NullPointerException if {@code extension} or {@code args} is {@code null}
     */
    public void start(@NonNull final Extension extension, @NonNull final String[] args) {
        final ExtensionProperties properties = extension.getExtensionProperties();
        try {
            requireDependenciesRunning(properties);

            properties.updateExtensionStatus(ExtensionStatus.LOADING);
            extension.onLoading();

            properties.updateExtensionStatus(ExtensionStatus.RUNNING);
            extension.onRunning(args);
        } catch (final RuntimeException reason) {
            properties.updateExtensionStatus(ExtensionStatus.ERROR);
            extension.onException(reason);
        }
    }

    /**
     * Async counterpart of {@link #start(Extension, String[])}, running on
     * {@link MultiTaskingFactory}'s shared virtual-thread executor.
     *
     * @throws NullPointerException if {@code extension} or {@code args} is {@code null}
     */
    @NotNull
    public CompletableFuture<Void> startAsync(@NonNull final Extension extension, @NonNull final String[] args) {
        return MultiTaskingFactory.getInstance().runAsync(() -> start(extension, args));
    }

    private void requireDependenciesRunning(final ExtensionProperties properties) {
        for (final String dependencyName : properties.getDependencies()) {
            final Extension dependency = findByName(dependencyName).orElseThrow(() -> new IllegalStateException(
                    "@ExtensionFactory.start: '" + properties.getExtensionName() + "' depends on '"
                            + dependencyName + "', which is not registered"
            ));
            if (dependency.getExtensionProperties().getExtensionStatus() != ExtensionStatus.RUNNING) {
                throw new IllegalStateException(
                        "@ExtensionFactory.start: '" + properties.getExtensionName() + "' depends on '"
                                + dependencyName + "', which is not running yet"
                );
            }
        }
    }

    /**
     * Ends every registered extension the same way {@link #stop} ends a
     * single one.
     */
    public void stopAll() {
        getExtensions().forEach(this::stop);
    }

    /**
     * Async counterpart of {@link #stopAll()}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor.
     */
    @NotNull
    public CompletableFuture<Void> stopAllAsync() {
        return MultiTaskingFactory.getInstance().runAsync(this::stopAll);
    }

    /**
     * Ends {@code extension}: {@link ExtensionStatus#ENDING} then {@link
     * Extension#onEnding()}.
     *
     * @throws NullPointerException if {@code extension} is {@code null}
     */
    public void stop(@NonNull final Extension extension) {
        extension.getExtensionProperties().updateExtensionStatus(ExtensionStatus.ENDING);
        extension.onEnding();
    }

    /**
     * Async counterpart of {@link #stop(Extension)}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor.
     *
     * @throws NullPointerException if {@code extension} is {@code null}
     */
    @NotNull
    public CompletableFuture<Void> stopAsync(@NonNull final Extension extension) {
        return MultiTaskingFactory.getInstance().runAsync(() -> stop(extension));
    }
}

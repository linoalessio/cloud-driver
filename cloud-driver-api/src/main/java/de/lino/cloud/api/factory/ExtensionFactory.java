package de.lino.cloud.api.factory;

import com.google.common.collect.Maps;
import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.cloud.api.extension.info.ExtensionStatus;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Stores, looks up, and drives the lifecycle of every registered {@link
 * Extension} - reached through {@link CloudDriver#getExtensionFactory()}. An
 * {@link Extension} is not registered automatically on construction; call
 * {@link #register} explicitly once it is built.
 *
 * <p>{@link #register}, {@link #findByName}, and {@link #getExtensions} are
 * abstract; every lifecycle-driving method below is implemented here
 * generically in terms of those three.
 */
public abstract class ExtensionFactory {

    /**
     * The running {@link Thread} for each currently-running or -starting
     * extension, keyed by extension name. Populated by {@link #start},
     * drained by {@link #stop} or by the thread itself once {@link
     * Extension#onRunning} returns.
     */
    private final Map<String, Thread> runningThreads = Maps.newConcurrentMap();

    /**
     * Registers {@code extension} under its {@link
     * ExtensionProperties#getExtensionName() extension name}.
     *
     * @param extension the fully-constructed extension to register
     * @throws NullPointerException if {@code extension} is {@code null}
     * @throws IllegalStateException if an extension with the same name is already registered
     */
    public abstract void register(@NotNull Extension extension);

    /**
     * Looks up a registered extension by its {@link
     * ExtensionProperties#getExtensionName() extension name}.
     *
     * @param extensionName the name to look up
     * @return the registered extension, or {@link Optional#empty()} if none matches
     * @throws NullPointerException if {@code extensionName} is {@code null}
     */
    @NotNull
    public abstract Optional<Extension> findByName(@NotNull String extensionName);

    /** Every currently registered extension, in no particular order. */
    @NotNull
    public abstract List<Extension> getExtensions();

    /**
     * Starts every registered extension in dependency order, so each
     * extension's own dependencies are already {@link
     * ExtensionStatus#RUNNING} by the time {@link #start} runs for it.
     *
     * @param args the arguments passed to every extension's {@link Extension#onRunning}
     * @throws NullPointerException if {@code args} is {@code null}
     * @throws IllegalStateException if the registered extensions' dependencies form a cycle
     */
    public void startAll(@NonNull final String[] args) {
        dependencyOrder().forEach(extension -> start(extension, args));
    }

    /**
     * Async counterpart of {@link #startAll(String[])}. Per-extension
     * failures still surface through that extension's {@link
     * Extension#onException}; the returned future only fails if {@link
     * #startAll} itself throws (e.g. a dependency cycle).
     *
     * @throws NullPointerException if {@code args} is {@code null}
     */
    @NotNull
    public CompletableFuture<Void> startAllAsync(@NonNull final String[] args) {
        return MultiTaskingFactory.getInstance().runAsync(() -> startAll(args));
    }

    /**
     * Registered extensions ordered so each appears after every registered
     * extension it (transitively) depends on. A dependency name that is not
     * registered is left for {@link #start} to reject.
     */
    @NotNull
    private List<Extension> dependencyOrder() {
        final List<Extension> ordered = new ArrayList<>();
        final Set<String> visited = new HashSet<>();
        final Set<String> visiting = new HashSet<>();

        getExtensions().forEach(extension -> visit(extension, visited, visiting, ordered));
        return ordered;
    }

    /**
     * Depth-first visits {@code extension} and every extension it
     * (transitively) depends on, appending each to {@code ordered} only
     * after all of its own dependencies have already been appended - the
     * core step of {@link #dependencyOrder()}'s topological sort. A
     * dependency name with no registered match is silently skipped here
     * (left for {@link #start} to reject later). Already-visited extensions
     * are skipped; an extension still on the current DFS path being
     * revisited indicates a dependency cycle.
     *
     * @param extension the extension to visit
     * @param visited names already fully processed and appended to {@code ordered}
     * @param visiting names currently on the DFS call stack, used for cycle detection
     * @param ordered the output list being built in dependency order
     * @throws IllegalStateException if {@code extension}'s dependencies form a cycle back to itself
     */
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
     * Starts {@code extension}: checks its declared dependencies are
     * registered and running, then drives it through {@link
     * ExtensionStatus#LOADING}/{@link Extension#onLoading()} and {@link
     * ExtensionStatus#RUNNING}/{@link Extension#onRunning(String[])}. Runs
     * on its own dedicated, named, daemon {@link Thread} rather than the
     * shared virtual-thread executor, since {@code onRunning} may block
     * indefinitely; the thread is tracked (by extension name) so {@link
     * #stop} can later signal it. A {@link RuntimeException} from the
     * dependency check or either phase is caught, the extension's status is
     * set to {@link ExtensionStatus#ERROR}, and it is routed to {@link
     * Extension#onException} instead of propagating.
     *
     * @param extension the extension to start
     * @param args the arguments passed to {@link Extension#onRunning}
     * @throws NullPointerException if {@code extension} or {@code args} is {@code null}
     */
    public void start(@NonNull final Extension extension, @NonNull final String[] args) {

        final ExtensionProperties properties = extension.getExtensionProperties();
        final String name = properties.getExtensionName();
        final Thread thread = new Thread(() -> {

            try {
                requireDependenciesRunning(properties);

                properties.updateExtensionStatus(ExtensionStatus.LOADING);
                extension.onLoading();

                properties.updateExtensionStatus(ExtensionStatus.RUNNING);
                extension.onRunning(args);
            } catch (final RuntimeException reason) {

                properties.updateExtensionStatus(ExtensionStatus.ERROR);
                extension.onException(reason);

            } finally {
                this.runningThreads.remove(name, Thread.currentThread());
            }

        }, "extension-" + name);
        thread.setDaemon(true);
        this.runningThreads.put(name, thread);
        thread.start();

    }

    /**
     * Async counterpart of {@link #start(Extension, String[])}.
     *
     * @throws NullPointerException if {@code extension} or {@code args} is {@code null}
     */
    @NotNull
    public CompletableFuture<Void> startAsync(@NonNull final Extension extension, @NonNull final String[] args) {
        return MultiTaskingFactory.getInstance().runAsync(() -> start(extension, args));
    }

    /**
     * Checks that every dependency {@code properties} declares is both
     * registered and currently {@link ExtensionStatus#RUNNING}, called by
     * {@link #start} before driving an extension through {@code onLoading}/
     * {@code onRunning}.
     *
     * @param properties the properties of the extension whose dependencies to check
     * @throws IllegalStateException if a declared dependency is not registered, or is registered but not yet running
     */
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
     * single one. A {@link RuntimeException} from one extension's {@link
     * #stop} is caught and routed to that extension's {@link
     * Extension#onException} instead of propagating, so one misbehaving
     * extension cannot prevent the rest from being stopped.
     */
    public void stopAll() {
        getExtensions().forEach(extension -> {
            try {
                stop(extension);
            } catch (final RuntimeException reason) {
                extension.onException(reason);
            }
        });
    }

    /** Async counterpart of {@link #stopAll()}. */
    @NotNull
    public CompletableFuture<Void> stopAllAsync() {
        return MultiTaskingFactory.getInstance().runAsync(this::stopAll);
    }

    /**
     * Ends {@code extension}: {@link ExtensionStatus#ENDING} then {@link
     * Extension#onEnding()}, then interrupts and untracks its {@link
     * #start started} thread, if still running. The thread is always
     * removed/interrupted, even if {@code onEnding()} itself throws. This
     * is cooperative, in-process signaling only - a thread blocked in
     * native I/O will not respond to the interrupt until that call itself
     * returns.
     *
     * <p><b>Idempotent</b>: a no-op if {@code extension} is already {@link
     * ExtensionStatus#ENDING} - there is no further status {@link #stop}
     * ever transitions an extension to once it reaches {@code ENDING}, so
     * that status alone is enough to detect "already stopped, or already
     * being stopped". Without this guard, a caller that ends up invoking
     * {@link #stop}/{@link #stopAll} twice on the same already-stopped
     * extension (e.g. once directly, and again via a JVM shutdown hook
     * triggered by the first call's own {@code System.exit}) would run
     * {@code onEnding()} a second time - unsafe for an extension whose
     * {@code onEnding()} isn't itself idempotent, such as one that writes
     * to a resource (like a terminal) the first {@code onEnding()} call
     * may have already torn down.
     *
     * @param extension the extension to stop
     * @throws NullPointerException if {@code extension} is {@code null}
     */
    @SneakyThrows
    public void stop(@NonNull final Extension extension) {
        final ExtensionProperties properties = extension.getExtensionProperties();

        if (properties.getExtensionStatus() == ExtensionStatus.ENDING) return;
        properties.updateExtensionStatus(ExtensionStatus.ENDING);

        try {
            extension.onEnding();
        } finally {
            final Thread thread = this.runningThreads.remove(properties.getExtensionName());
            if (thread != null) thread.interrupt();
        }
    }

    /**
     * Async counterpart of {@link #stop(Extension)}.
     *
     * @throws NullPointerException if {@code extension} is {@code null}
     */
    @NotNull
    public CompletableFuture<Void> stopAsync(@NonNull final Extension extension) {
        return MultiTaskingFactory.getInstance().runAsync(() -> stop(extension));
    }
}

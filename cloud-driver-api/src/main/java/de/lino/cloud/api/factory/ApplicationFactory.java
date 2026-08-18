package de.lino.cloud.api.factory;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.application.Application;
import de.lino.cloud.api.application.info.ApplicationProperties;
import de.lino.cloud.api.application.info.ApplicationStatus;
import de.lino.cloud.api.task.MultiTaskingFactory;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Stores, looks up, and drives the lifecycle of every registered {@link
 * Application} extension - the single place responsible for <em>all</em>
 * applications, as opposed to {@link Application} itself, which only models
 * one. Reached through {@link CloudAPI#getApplicationFactory()}.
 *
 * <p>Only {@link #register}, {@link #find}, and {@link #getApplications} -
 * the actual storage of registered applications - are abstract; every
 * lifecycle-driving method below is implemented here, generically, in terms
 * of those three, the same way {@link DataFactory}'s {@code *Async} methods
 * are implemented in terms of its abstract sync ones.
 *
 * <p>An {@link Application} subclass registers itself automatically the
 * moment it is constructed (see {@link Application#Application()}, via
 * {@link CloudAPI#getInstance()}); an extension never calls {@link #register}
 * itself.
 */
public abstract class ApplicationFactory {

    /**
     * Registers {@code application} under its {@link
     * ApplicationProperties#getApplicationName() application name}. Called
     * automatically from {@link Application}'s constructor.
     *
     * @throws NullPointerException if {@code application} is {@code null}
     * @throws IllegalStateException if an application with the same name is already registered
     */
    public abstract void register(@NotNull Application application);

    /**
     * Looks up a registered application by its {@link
     * ApplicationProperties#getApplicationName() application name}.
     *
     * @throws NullPointerException if {@code applicationName} is {@code null}
     */
    @NotNull
    public abstract Optional<Application> find(@NotNull String applicationName);

    /**
     * Every currently registered application, in no particular order.
     */
    @NotNull
    public abstract Collection<Application> getApplications();

    /**
     * Starts every registered application in dependency order - each
     * application's {@link ApplicationProperties#getDependencies()
     * dependencies} are started before it is, so by the time {@link #start}
     * runs for it, {@link #start}'s own dependency check always passes for
     * every dependency that is registered.
     *
     * @throws NullPointerException if {@code args} is {@code null}
     * @throws IllegalStateException if the registered applications' dependencies form a cycle
     */
    public void startAll(@NonNull final String[] args) {
        dependencyOrder().forEach(application -> start(application, args));
    }

    /**
     * Async counterpart of {@link #startAll(String[])}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor so the calling
     * thread never blocks on every application's {@code onLoading()}/{@code
     * onRunning()} - each of which may itself block on I/O. Per-application
     * failures still surface through that application's {@link
     * Application#onException}, exactly as {@link #startAll} handles them
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
     * Registered applications ordered so that every application appears
     * after every other registered application it (transitively) depends on.
     * A dependency name that is not registered is left for {@link #start} to
     * reject - it does not affect ordering here.
     */
    @NotNull
    private List<Application> dependencyOrder() {
        final List<Application> ordered = new ArrayList<>();
        final Set<String> visited = new HashSet<>();
        final Set<String> visiting = new HashSet<>();

        getApplications().forEach(application -> visit(application, visited, visiting, ordered));
        return ordered;
    }

    private void visit(final Application application, final Set<String> visited, final Set<String> visiting, final List<Application> ordered) {
        final String name = application.getApplicationProperties().getApplicationName();
        if (visited.contains(name)) {
            return;
        }
        if (!visiting.add(name)) {
            throw new IllegalStateException("@ApplicationFactory: dependency cycle detected involving '" + name + "'");
        }

        for (final String dependencyName : application.getApplicationProperties().getDependencies()) {
            find(dependencyName).ifPresent(dependency -> visit(dependency, visited, visiting, ordered));
        }

        visiting.remove(name);
        visited.add(name);
        ordered.add(application);
    }

    /**
     * Starts {@code application}: first checks that every application named
     * in {@link ApplicationProperties#getDependencies()} is registered and
     * already {@link ApplicationStatus#RUNNING}, then {@link
     * ApplicationStatus#LOADING} followed by {@link Application#onLoading()},
     * then {@link ApplicationStatus#RUNNING} followed by {@link
     * Application#onRunning(String[])} with {@code args}. A {@link
     * RuntimeException} thrown by the dependency check or either phase is
     * caught, the application's status is set to {@link
     * ApplicationStatus#ERROR}, and it is routed to {@link
     * Application#onException} instead of propagating - so one failing
     * application does not prevent {@link #startAll} from starting the rest.
     *
     * @throws NullPointerException if {@code application} or {@code args} is {@code null}
     */
    public void start(@NonNull final Application application, @NonNull final String[] args) {
        final ApplicationProperties properties = application.getApplicationProperties();
        try {
            requireDependenciesRunning(properties);

            properties.updateApplicationStatus(ApplicationStatus.LOADING);
            application.onLoading();

            properties.updateApplicationStatus(ApplicationStatus.RUNNING);
            application.onRunning(args);
        } catch (final RuntimeException reason) {
            properties.updateApplicationStatus(ApplicationStatus.ERROR);
            application.onException(reason);
        }
    }

    /**
     * Async counterpart of {@link #start(Application, String[])}, running on
     * {@link MultiTaskingFactory}'s shared virtual-thread executor.
     *
     * @throws NullPointerException if {@code application} or {@code args} is {@code null}
     */
    @NotNull
    public CompletableFuture<Void> startAsync(@NonNull final Application application, @NonNull final String[] args) {
        return MultiTaskingFactory.getInstance().runAsync(() -> start(application, args));
    }

    private void requireDependenciesRunning(final ApplicationProperties properties) {
        for (final String dependencyName : properties.getDependencies()) {
            final Application dependency = find(dependencyName).orElseThrow(() -> new IllegalStateException(
                    "@ApplicationFactory.start: '" + properties.getApplicationName() + "' depends on '"
                            + dependencyName + "', which is not registered"
            ));
            if (dependency.getApplicationProperties().getApplicationStatus() != ApplicationStatus.RUNNING) {
                throw new IllegalStateException(
                        "@ApplicationFactory.start: '" + properties.getApplicationName() + "' depends on '"
                                + dependencyName + "', which is not running yet"
                );
            }
        }
    }

    /**
     * Ends every registered application the same way {@link #stop} ends a
     * single one.
     */
    public void stopAll() {
        getApplications().forEach(this::stop);
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
     * Ends {@code application}: {@link ApplicationStatus#ENDING} then {@link
     * Application#onEnding()}.
     *
     * @throws NullPointerException if {@code application} is {@code null}
     */
    public void stop(@NonNull final Application application) {
        application.getApplicationProperties().updateApplicationStatus(ApplicationStatus.ENDING);
        application.onEnding();
    }

    /**
     * Async counterpart of {@link #stop(Application)}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor.
     *
     * @throws NullPointerException if {@code application} is {@code null}
     */
    @NotNull
    public CompletableFuture<Void> stopAsync(@NonNull final Application application) {
        return MultiTaskingFactory.getInstance().runAsync(() -> stop(application));
    }
}

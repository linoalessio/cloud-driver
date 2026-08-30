package de.lino.cloud.api.terminal.service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry and dispatcher for a {@link Terminal}'s {@link Command}s. Lookup is a direct,
 * case-insensitive map read; {@link #dispatchAsync(String, String[])} runs the matched service
 * on {@link MultiTaskingFactory}'s shared virtual-thread executor.
 */
public final class CommandService {

    /** Logs a service's uncaught {@link RuntimeException} in {@link #dispatchAsync(String, String[])}. */
    private static final Logger LOGGER = Logger.getLogger(CommandService.class.getName());

    /** Every registered service, keyed by its lowercase name and every lowercase alias. */
    private final Map<String, Command> commandsByLookupKey = Maps.newConcurrentMap();

    /** Cached, immutable snapshot backing {@link #snapshot()}; rebuilt only on register/unregister. */
    private volatile List<Command> snapshot = List.of();

    /** Every distinct registered service, in registration order, backing {@link #snapshot}'s rebuild. */
    private final List<Command> registrationOrder = Lists.newLinkedList();

    /**
     * Registers every service in {@code commands}.
     *
     * @param commands the commands to register
     */
    public void register(@NonNull final Command... commands) {
        Arrays.stream(commands).forEach(this::register);
    }

    /**
     * Registers {@code service} under its name and every alias (case-insensitively).
     *
     * @param command the service to register
     * @throws NullPointerException  if {@code service} is {@code null}
     * @throws IllegalStateException if {@code service}'s name or any of its aliases is already
     *                                registered
     */
    public void register(@NotNull final Command command) {
        Asserts.requireNonNull(command, "@CommandService.register: service must not be null");

        this.registerLookupKey(command.name(), command);
        command.aliases().forEach(alias -> registerLookupKey(alias, command));

        this.registrationOrder.add(command);
        this.snapshot = List.copyOf(this.registrationOrder);
    }

    /**
     * Registers {@code command} under {@code key}, lowercased, in {@link #commandsByLookupKey}.
     *
     * @param key     the name or alias to register
     * @param command the service it should resolve to
     * @throws IllegalStateException if {@code key} (lowercased) is already registered
     */
    private void registerLookupKey(final String key, final Command command) {
        final String lookupKey = key.toLowerCase(Locale.ROOT);
        if (this.commandsByLookupKey.putIfAbsent(lookupKey, command) != null) {
            throw new IllegalStateException("@CommandService.register: '" + key + "' is already registered");
        }
    }

    /**
     * Unregisters every service in {@code commands}.
     *
     * @param commands the commands to unregister
     */
    public void unregister(@NonNull final Command... commands) {
        Arrays.stream(commands).forEach(this::unregister);
    }

    /**
     * Unregisters {@code service}, freeing its name and every alias for reuse.
     *
     * @param command the service to unregister
     * @throws NullPointerException if {@code service} is {@code null}
     */
    public void unregister(@NotNull final Command command) {
        Asserts.requireNonNull(command, "@CommandService.unregister: service must not be null");

        this.commandsByLookupKey.remove(command.name().toLowerCase(Locale.ROOT));
        command.aliases().forEach(alias -> this.commandsByLookupKey.remove(alias.toLowerCase(Locale.ROOT)));

        this.registrationOrder.remove(command);
        this.snapshot = List.copyOf(this.registrationOrder);
    }

    /**
     * Looks a service up by its name or one of its aliases, case-insensitively.
     *
     * @param name the name or alias to look up
     * @return the matching service, or {@link Optional#empty()} if none is registered under it
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @NotNull
    public Optional<Command> findByName(@NotNull final String name) {
        Asserts.requireNonNull(name, "@CommandService.findByName: name must not be null");
        return Optional.ofNullable(this.commandsByLookupKey.get(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * @return every currently registered service, in registration order - a cached snapshot,
     * not recomputed on every call (see this class's Javadoc)
     */
    @NotNull
    public List<Command> snapshot() {
        return this.snapshot;
    }

    /**
     * Looks {@code name} up and runs it synchronously on the calling thread. Prefer
     * {@link #dispatchAsync(String, String[])} from a terminal's reading loop.
     *
     * @param name the service name or alias to dispatch
     * @param args the arguments following the service name
     * @return {@code true} if a service was found and run, {@code false} otherwise
     * @throws NullPointerException if {@code name} or {@code args} is {@code null}
     */
    public boolean dispatch(@NotNull final String name, @NotNull final String[] args) {
        Asserts.requireNonNull(args, "@CommandService.dispatch: args must not be null");

        final Optional<Command> command = findByName(name);
        command.ifPresent(value -> value.execute(new Command.CommandArguments(args)));
        return command.isPresent();
    }

    /**
     * Looks {@code name} up and, if found, runs it on {@link MultiTaskingFactory}'s shared
     * virtual-thread executor. A thrown {@link RuntimeException} is caught and logged rather
     * than propagated.
     *
     * @param name the service name or alias to dispatch
     * @param args the arguments following the service name
     * @return a future completing with {@code true} if a service ran, {@code false} otherwise
     * @throws NullPointerException if {@code name} or {@code args} is {@code null}
     */
    @NotNull
    public CompletableFuture<Boolean> dispatchAsync(@NotNull final String name, @NotNull final String[] args) {
        Asserts.requireNonNull(args, "@CommandService.dispatchAsync: args must not be null");

        final Optional<Command> command = this.findByName(name);

        return command.map(value -> MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                value.execute(new  Command.CommandArguments(args));
            } catch (final RuntimeException exception) {
                LOGGER.log(Level.SEVERE, "@CommandService.dispatchAsync: '" + name + "' threw an exception", exception);
            }
            return true;
        })).orElseGet(() -> CompletableFuture.completedFuture(false));

    }

}

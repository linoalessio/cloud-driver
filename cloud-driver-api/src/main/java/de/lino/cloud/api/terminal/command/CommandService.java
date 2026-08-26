package de.lino.cloud.api.terminal.command;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry and dispatcher for a {@link Terminal}'s {@link Command}s.
 *
 * <p><b>Caching.</b> Lookup ({@link #findByName(String)}) is a direct, case-insensitive map
 * read - every registered command's name and every alias is indexed once, at {@link
 * #register(Command)} time, rather than scanning the registry on every keystroke a {@code
 * TabCompleter} or dispatch asks about. {@link #registeredCommands()} returns a snapshot list
 * that is only ever rebuilt when the registry actually changes ({@link #register(Command)}/
 * {@link #unregister(Command)}), not reconstructed on every call - a {@code TabCompleter}
 * calls this once per {@code Tab} press, so keeping it O(1) between mutations matters.
 *
 * <p><b>Async.</b> {@link #dispatchAsync(String, String[])} runs the matched {@link
 * Command#execute(String[])} on {@link MultiTaskingFactory}'s shared virtual-thread executor,
 * the same dispatch primitive every other {@code *Async} method in this codebase is built on
 * ({@code DataFactory}, {@code EventFactory}, {@code ExtensionFactory}, ...) - so a
 * long-running command never blocks the terminal's reading thread from accepting the next
 * line, and a failing command's exception is caught and logged rather than silently dropped
 * or left to crash the reading thread.
 */
public final class CommandService {

    private static final Logger LOGGER = Logger.getLogger(CommandService.class.getName());

    /**
     * Every registered command, keyed by its lowercase name and every lowercase alias, so
     * {@link #findByName(String)} is a single, direct map read regardless of how many aliases
     * a command declares.
     */
    private final Map<String, Command> commandsByLookupKey = Maps.newConcurrentMap();

    /**
     * Cached, immutable snapshot backing {@link #registeredCommands()} - rebuilt only inside
     * {@link #register(Command)}/{@link #unregister(Command)}, never on a plain read.
     */
    private volatile List<Command> snapshot = List.of();

    /**
     * Every distinct registered command, in registration order, backing {@link #snapshot}'s
     * rebuild - kept separately from {@link #commandsByLookupKey} since one command occupies
     * several lookup keys (its name plus every alias) but should only ever appear once here.
     */
    private final List<Command> registrationOrder = Lists.newLinkedList();

    /**
     * Registers {@code command} under its name and every alias (case-insensitively).
     *
     * @param command the command to register
     * @throws NullPointerException  if {@code command} is {@code null}
     * @throws IllegalStateException if {@code command}'s name or any of its aliases is already
     *                                registered
     */
    public void register(@NotNull final Command command) {
        Asserts.requireNonNull(command, "@CommandService.register: command must not be null");

        this.registerLookupKey(command.name(), command);
        command.aliases().forEach(alias -> registerLookupKey(alias, command));

        this.registrationOrder.add(command);
        this.snapshot = List.copyOf(this.registrationOrder);
    }

    private void registerLookupKey(final String key, final Command command) {
        final String lookupKey = key.toLowerCase(Locale.ROOT);
        if (this.commandsByLookupKey.putIfAbsent(lookupKey, command) != null) {
            throw new IllegalStateException("@CommandService.register: '" + key + "' is already registered");
        }
    }

    /**
     * Unregisters {@code command}, freeing its name and every alias for reuse.
     *
     * @param command the command to unregister
     * @throws NullPointerException if {@code command} is {@code null}
     */
    public void unregister(@NotNull final Command command) {
        Asserts.requireNonNull(command, "@CommandService.unregister: command must not be null");

        this.commandsByLookupKey.remove(command.name().toLowerCase(Locale.ROOT));
        command.aliases().forEach(alias -> this.commandsByLookupKey.remove(alias.toLowerCase(Locale.ROOT)));

        this.registrationOrder.remove(command);
        this.snapshot = List.copyOf(this.registrationOrder);
    }

    /**
     * Looks a command up by its name or one of its aliases, case-insensitively.
     *
     * @param name the name or alias to look up
     * @return the matching command, or {@link Optional#empty()} if none is registered under it
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @NotNull
    public Optional<Command> findByName(@NotNull final String name) {
        Asserts.requireNonNull(name, "@CommandService.findByName: name must not be null");
        return Optional.ofNullable(this.commandsByLookupKey.get(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * @return every currently registered command, in registration order - a cached snapshot,
     * not recomputed on every call (see this class's Javadoc)
     */
    @NotNull
    public List<Command> registeredCommands() {
        return this.snapshot;
    }

    /**
     * Looks {@code name} up and runs it synchronously, on the calling thread, with {@code
     * args}. Prefer {@link #dispatchAsync(String, String[])} from a terminal's reading loop -
     * this synchronous variant exists for callers (e.g. tests, or a caller already off the
     * reading thread) that need to observe completion or a thrown exception directly.
     *
     * @param name the command name or alias to dispatch
     * @param args the arguments following the command name
     * @return {@code true} if a command was found and run, {@code false} if nothing is
     * registered under {@code name}
     * @throws NullPointerException if {@code name} or {@code args} is {@code null}
     */
    public boolean dispatch(@NotNull final String name, @NotNull final String[] args) {
        Asserts.requireNonNull(args, "@CommandService.dispatch: args must not be null");

        final Optional<Command> command = findByName(name);
        command.ifPresent(value -> value.execute(args));
        return command.isPresent();
    }

    /**
     * Looks {@code name} up and, if found, runs it on {@link MultiTaskingFactory}'s shared
     * virtual-thread executor - never blocking the calling thread. Any {@link RuntimeException}
     * thrown by the command is caught and logged rather than propagated, so one failing command
     * can never take down the caller (typically a terminal's reading loop).
     *
     * @param name the command name or alias to dispatch
     * @param args the arguments following the command name
     * @return a future completing with {@code true} once the command has run, or {@code false}
     * immediately if nothing is registered under {@code name}
     * @throws NullPointerException if {@code name} or {@code args} is {@code null}
     */
    @NotNull
    public CompletableFuture<Boolean> dispatchAsync(@NotNull final String name, @NotNull final String[] args) {
        Asserts.requireNonNull(args, "@CommandService.dispatchAsync: args must not be null");

        final Optional<Command> command = this.findByName(name);

        return command.map(value -> MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                value.execute(args);
            } catch (final RuntimeException exception) {
                LOGGER.log(Level.SEVERE, "@CommandService.dispatchAsync: '" + name + "' threw an exception", exception);
            }
            return true;
        })).orElseGet(() -> CompletableFuture.completedFuture(false));

    }

}

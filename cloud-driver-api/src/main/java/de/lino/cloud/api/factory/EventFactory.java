package de.lino.cloud.api.factory;

import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Registers, looks up, and dispatches every registered {@link Event} - the
 * single place responsible for <em>all</em> events, as opposed to {@link
 * Event} itself, which only models one. Reached through {@code
 * CloudAPI#getEventFactory()}.
 *
 * <p>Only {@link #registerEvent}, {@link #unregisterEvent}, {@link
 * #callEvent}, {@link #findEventByClass}, and {@link #getEvents} - the
 * actual storage and dispatch of registered events - are abstract; every
 * {@code *Async} variant below is implemented here, generically, in terms of
 * those, the same "abstract primitives + generic concrete methods" shape
 * {@link DataFactory}, {@link FileFactory}, and {@link ExtensionFactory} use.
 *
 * <p>Unlike {@link ExtensionFactory#register}, which takes an
 * already-constructed {@link de.lino.cloud.api.extension.Extension}, {@link
 * #registerEvent} takes a {@code Class} - construction is this factory's
 * responsibility, so exactly one instance ever exists per registered event
 * type (a singleton per class), reused for every {@link #callEvent} of that
 * type.
 */
public abstract class EventFactory {

    /**
     * Registers an event of type {@code eventClass}: constructs one
     * instance (via its no-arg constructor - see {@link Event}'s class
     * Javadoc) and stores it, keyed by {@code eventClass}, for every future
     * {@link #callEvent}/{@link #findEventByClass}/{@link #unregisterEvent}
     * of the same type to reuse.
     *
     * @param eventClass the concrete {@link Event} type to register
     * @param <T> the event type
     * @return the newly constructed and registered instance
     * @throws NullPointerException if {@code eventClass} is {@code null}
     * @throws IllegalStateException if an event of type {@code eventClass} is already registered, or {@code eventClass} has no accessible no-arg constructor
     */
    @NotNull
    public abstract <T extends Event> T registerEvent(@NotNull Class<T> eventClass);

    /**
     * Unregisters the event of type {@code eventClass} - unlike {@link
     * #findEventByClass}, this means "this must currently be registered",
     * not "does this exist?".
     *
     * @param eventClass the concrete {@link Event} type to unregister
     * @param <T> the event type
     * @return the removed instance
     * @throws NullPointerException if {@code eventClass} is {@code null}
     * @throws IllegalStateException if no event of type {@code eventClass} is registered
     */
    @NotNull
    public abstract <T extends Event> T unregisterEvent(@NotNull Class<T> eventClass);

    /**
     * Dispatches {@code properties} to the registered event of type {@code
     * eventClass}'s {@link Event#handle(JsonDocument)}.
     *
     * @param eventClass the concrete {@link Event} type to dispatch to
     * @param properties the payload passed to {@link Event#handle(JsonDocument)}
     * @param <T> the event type
     * @return the event instance {@code properties} was dispatched to
     * @throws NullPointerException if {@code eventClass} or {@code properties} is {@code null}
     * @throws IllegalStateException if no event of type {@code eventClass} is registered
     */
    @NotNull
    public abstract <T extends Event> T callEvent(@NotNull Class<T> eventClass, @NotNull JsonDocument properties);

    /**
     * Looks up the registered event of type {@code eventClass} the same way
     * {@link #callEvent} would dispatch to it, but returns {@link
     * Optional#empty()} instead of throwing when none is registered - for
     * callers that mean "does this exist?" rather than "this must exist".
     *
     * @param eventClass the concrete {@link Event} type to look up
     * @param <T> the event type
     * @return the registered instance, or {@link Optional#empty()} if no event of type {@code eventClass} is registered
     * @throws NullPointerException if {@code eventClass} is {@code null}
     */
    @NotNull
    public abstract <T extends Event> Optional<T> findEventByClass(@NotNull Class<T> eventClass);

    /**
     * Every currently registered event, in no particular order.
     */
    @NotNull
    public abstract Collection<Event> getEvents();

    /**
     * Async counterpart of {@link #registerEvent(Class)}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor so the calling
     * thread never blocks on {@code eventClass}'s construction.
     *
     * @throws NullPointerException if {@code eventClass} is {@code null}
     */
    @NotNull
    public <T extends Event> CompletableFuture<T> registerEventAsync(@NonNull final Class<T> eventClass) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> registerEvent(eventClass));
    }

    /**
     * Async counterpart of {@link #unregisterEvent(Class)}, running on
     * {@link MultiTaskingFactory}'s shared virtual-thread executor.
     *
     * @throws NullPointerException if {@code eventClass} is {@code null}
     */
    @NotNull
    public <T extends Event> CompletableFuture<T> unregisterEventAsync(@NonNull final Class<T> eventClass) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> unregisterEvent(eventClass));
    }

    /**
     * Async counterpart of {@link #callEvent(Class, JsonDocument)}, running
     * on {@link MultiTaskingFactory}'s shared virtual-thread executor so the
     * calling thread never blocks on {@link Event#handle(JsonDocument)},
     * which may itself do arbitrary, potentially blocking work.
     *
     * @throws NullPointerException if {@code eventClass} or {@code properties} is {@code null}
     */
    @NotNull
    public <T extends Event> CompletableFuture<T> callEventAsync(@NonNull final Class<T> eventClass, @NonNull final JsonDocument properties) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> callEvent(eventClass, properties));
    }

    /**
     * Batch counterpart of {@link #callEvent(Class, JsonDocument)}: dispatches
     * every payload in {@code propertiesBatch} to the registered event of
     * type {@code eventClass}, one {@link Event#handle(JsonDocument)} call
     * per payload, concurrently on {@link MultiTaskingFactory}'s shared
     * virtual-thread executor rather than one at a time - for a caller
     * processing a large volume of payloads through the same event type
     * ("big data"), this keeps per-payload latency from being paid
     * serially, the same reasoning {@code EntityDatabaseClient}'s and {@link
     * DataFactory}'s batch operations apply. Every payload is dispatched to
     * the very same registered singleton instance (see {@link Event}'s class
     * Javadoc) - concurrent {@code handle} calls on that one instance are
     * therefore only as thread-safe as {@code eventClass}'s own
     * implementation makes them; {@link Event} itself makes no thread-safety
     * guarantee beyond that.
     *
     * @param eventClass the concrete {@link Event} type to dispatch to
     * @param propertiesBatch the payloads passed to {@link Event#handle(JsonDocument)}, one call each
     * @param <T> the event type
     * @return the same registered instance, once per element of {@code propertiesBatch}, in the same order
     * @throws NullPointerException if {@code eventClass} or {@code propertiesBatch} is {@code null}
     * @throws IllegalStateException if no event of type {@code eventClass} is registered
     */
    @NotNull
    public <T extends Event> List<T> callEvent(@NonNull final Class<T> eventClass, @NonNull final JsonDocument[] propertiesBatch) {
        final List<CompletableFuture<T>> dispatches = Arrays.stream(propertiesBatch)
                .map(properties -> MultiTaskingFactory.getInstance().supplyAsync(() -> callEvent(eventClass, properties)))
                .toList();
        return dispatches.stream().map(CompletableFuture::join).toList();
    }

    /**
     * Async counterpart of {@link #callEvent(Class, JsonDocument[])}, running
     * on {@link MultiTaskingFactory}'s shared virtual-thread executor so the
     * calling thread never blocks waiting for the whole batch to finish.
     *
     * @throws NullPointerException if {@code eventClass} or {@code propertiesBatch} is {@code null}
     */
    @NotNull
    public <T extends Event> CompletableFuture<List<T>> callEventAsync(@NonNull final Class<T> eventClass, @NonNull final JsonDocument[] propertiesBatch) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> callEvent(eventClass, propertiesBatch));
    }

    /**
     * Async counterpart of {@link #findEventByClass(Class)}, running on
     * {@link MultiTaskingFactory}'s shared virtual-thread executor.
     *
     * @throws NullPointerException if {@code eventClass} is {@code null}
     */
    @NotNull
    public <T extends Event> CompletableFuture<Optional<T>> findEventByClassAsync(@NonNull final Class<T> eventClass) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> findEventByClass(eventClass));
    }

    /**
     * Async counterpart of {@link #getEvents()}, running on {@link
     * MultiTaskingFactory}'s shared virtual-thread executor.
     */
    @NotNull
    public CompletableFuture<Collection<Event>> getEventsAsync() {
        return MultiTaskingFactory.getInstance().supplyAsync(this::getEvents);
    }

}

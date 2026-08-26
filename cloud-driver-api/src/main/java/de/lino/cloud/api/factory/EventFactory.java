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
 * Registers, looks up, and dispatches every registered {@link Event} -
 * reached through {@code CloudDriver#getEventFactory()}. Exactly one
 * instance exists per registered event class, constructed by {@link
 * #registerEvent} and reused for every {@link #dispatch}.
 *
 * <p>{@link #registerEvent}, {@link #unregisterEvent}, {@link #dispatch},
 * {@link #findEventByClass}, and {@link #getEvents} are abstract; every
 * {@code *Async} variant below is implemented here generically in terms of
 * those.
 */
public abstract class EventFactory {

    /**
     * Constructs (via its no-arg constructor) and registers one instance of
     * {@code eventClass}, reused by every future {@link #dispatch}/{@link
     * #findEventByClass}/{@link #unregisterEvent} of the same type.
     *
     * @param eventClass the concrete {@link Event} type to register
     * @param <T> the event type
     * @return the newly constructed and registered instance
     * @throws NullPointerException if {@code eventClass} is {@code null}
     * @throws IllegalStateException if {@code eventClass} is already registered, or has no accessible no-arg constructor
     */
    @NotNull
    public abstract <T extends Event> T registerEvent(@NotNull Class<T> eventClass);

    /**
     * Unregisters the event of type {@code eventClass}.
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
    public abstract <T extends Event> T dispatch(@NotNull Class<T> eventClass, @NotNull JsonDocument properties);

    /**
     * Looks up the registered event of type {@code eventClass}, returning
     * {@link Optional#empty()} instead of throwing when none is registered.
     *
     * @param eventClass the concrete {@link Event} type to look up
     * @param <T> the event type
     * @return the registered instance, or {@link Optional#empty()} if none is registered
     * @throws NullPointerException if {@code eventClass} is {@code null}
     */
    @NotNull
    public abstract <T extends Event> Optional<T> findEventByClass(@NotNull Class<T> eventClass);

    /** Every currently registered event, in no particular order. */
    @NotNull
    public abstract List<Event> getEvents();

    /**
     * Async counterpart of {@link #registerEvent(Class)}.
     *
     * @throws NullPointerException if {@code eventClass} is {@code null}
     */
    @NotNull
    public <T extends Event> CompletableFuture<T> registerEventAsync(@NonNull final Class<T> eventClass) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> registerEvent(eventClass));
    }

    /**
     * Async counterpart of {@link #unregisterEvent(Class)}.
     *
     * @throws NullPointerException if {@code eventClass} is {@code null}
     */
    @NotNull
    public <T extends Event> CompletableFuture<T> unregisterEventAsync(@NonNull final Class<T> eventClass) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> unregisterEvent(eventClass));
    }

    /**
     * Async counterpart of {@link #dispatch(Class, JsonDocument)}.
     *
     * @throws NullPointerException if {@code eventClass} or {@code properties} is {@code null}
     */
    @NotNull
    public <T extends Event> CompletableFuture<T> dispatchAsync(@NonNull final Class<T> eventClass, @NonNull final JsonDocument properties) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> dispatch(eventClass, properties));
    }

    /**
     * Dispatches every payload in {@code propertiesBatch} to the registered
     * event of type {@code eventClass}, one {@link
     * Event#handle(JsonDocument)} call per payload, concurrently.
     *
     * @param eventClass the concrete {@link Event} type to dispatch to
     * @param propertiesBatch the payloads passed to {@link Event#handle(JsonDocument)}, one call each
     * @param <T> the event type
     * @return the same registered instance, once per element of {@code propertiesBatch}, in the same order
     * @throws NullPointerException if {@code eventClass} or {@code propertiesBatch} is {@code null}
     * @throws IllegalStateException if no event of type {@code eventClass} is registered
     */
    @NotNull
    public <T extends Event> List<T> dispatch(@NonNull final Class<T> eventClass, @NonNull final JsonDocument[] propertiesBatch) {
        final List<CompletableFuture<T>> dispatches = Arrays.stream(propertiesBatch)
                .map(properties -> MultiTaskingFactory.getInstance().supplyAsync(() -> dispatch(eventClass, properties)))
                .toList();
        return dispatches.stream().map(CompletableFuture::join).toList();
    }

    /**
     * Async counterpart of {@link #dispatch(Class, JsonDocument[])}.
     *
     * @throws NullPointerException if {@code eventClass} or {@code propertiesBatch} is {@code null}
     */
    @NotNull
    public <T extends Event> CompletableFuture<List<T>> dispatchAsync(@NonNull final Class<T> eventClass, @NonNull final JsonDocument[] propertiesBatch) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> dispatch(eventClass, propertiesBatch));
    }

    /**
     * Async counterpart of {@link #findEventByClass(Class)}.
     *
     * @throws NullPointerException if {@code eventClass} is {@code null}
     */
    @NotNull
    public <T extends Event> CompletableFuture<Optional<T>> findEventByClassAsync(@NonNull final Class<T> eventClass) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> findEventByClass(eventClass));
    }

    /** Async counterpart of {@link #getEvents()}. */
    @NotNull
    public CompletableFuture<List<Event>> getEventsAsync() {
        return MultiTaskingFactory.getInstance().supplyAsync(this::getEvents);
    }

}

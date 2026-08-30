package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.factory.EventFactory;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;
import de.lino.database.json.JsonDocument;
import de.lino.database.utils.cache.Cache;
import de.lino.database.utils.cache.provider.Caches;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link EventFactory} implementation storing registered events in a
 * {@link Cache} keyed by the event's own {@code Class}, with no TTL/size
 * bound - a registered event is a long-lived singleton. {@link
 * #registerEvent} resolves through {@link Cache#get}'s stampede-protected
 * loader so two threads racing to register the same class construct it at
 * most once; every other method reads {@link Cache#snapshot()} directly and
 * never triggers construction.
 */
public final class DefaultEventFactory extends EventFactory {

    /** Registered events, keyed by their own {@code Class}; no TTL/size bound, backed by {@link #construct} as its loader. */
    private final Cache<Class<? extends Event>, Event> events =
            Caches.newCache(DefaultEventFactory::construct, null, -1);

    /** Constructs and caches one instance of {@code eventClass} via {@link #events}'s loader. */
    @NotNull
    @Override
    public <T extends Event> T registerEvent(@NonNull final Class<T> eventClass) {
        if (this.events.snapshot().containsKey(eventClass)) {
            throw new IllegalStateException(
                    "@DefaultEventFactory.registerEvent: an event of type '" + eventClass.getName() + "' is already registered"
            );
        }
        return eventClass.cast(join(this.events.get(eventClass)));
    }

    /** Invalidates the cached instance of {@code eventClass}. */
    @NotNull
    @Override
    public <T extends Event> T unregisterEvent(@NonNull final Class<T> eventClass) {
        final T event = requireRegistered(eventClass);
        this.events.invalidate(eventClass);
        return event;
    }

    /** Looks up the registered instance and calls its {@link Event#handle(JsonDocument)}. */
    @NotNull
    @Override
    public <T extends Event> T dispatch(@NonNull final Class<T> eventClass, @NonNull final JsonDocument properties) {
        final T event = requireRegistered(eventClass);
        event.handle(properties);
        return event;
    }

    /** Reads the cached instance directly, never triggering construction. */
    @NotNull
    @Override
    public <T extends Event> Optional<T> findEventByClass(@NonNull final Class<T> eventClass) {
        return Optional.ofNullable(this.events.snapshot().get(eventClass)).map(eventClass::cast);
    }

    /** Returns a snapshot copy of every currently registered event, without triggering construction. */
    @NotNull
    @Override
    public List<Event> getEvents() {
        return List.copyOf(this.events.snapshot().values());
    }

    /**
     * Looks up {@code eventClass}'s registered instance.
     *
     * @throws IllegalStateException if no event of that type is registered
     */
    @NotNull
    private <T extends Event> T requireRegistered(final Class<T> eventClass) {
        return findEventByClass(eventClass).orElseThrow(() -> new IllegalStateException(
                "@DefaultEventFactory: no event of type '" + eventClass.getName() + "' is registered - register it first via registerEvent"
        ));
    }

    /**
     * The {@link #events} cache's loader: constructs {@code eventClass} via its no-arg
     * constructor, dispatched on {@link MultiTaskingFactory}'s virtual-thread executor
     * since a {@link Cache} loader must never block the calling thread.
     */
    private static CompletableFuture<Event> construct(final Class<? extends Event> eventClass) {
        return MultiTaskingFactory.getInstance().supplyAsync(() -> {
            try {
                final Constructor<? extends Event> constructor = eventClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "@DefaultEventFactory: '" + eventClass.getName() + "' has no accessible no-arg constructor", e
                );
            }
        });
    }

    /** Joins {@code future}, unwrapping a {@link CompletionException} back to its {@link RuntimeException} cause. */
    private static <T> T join(final CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (final CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

}

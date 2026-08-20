package de.lino.cloud.plugin.factory;

import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.factory.EventFactory;
import de.lino.cloud.api.task.MultiTaskingFactory;
import de.lino.database.json.JsonDocument;
import de.lino.database.utils.cache.Cache;
import de.lino.database.utils.cache.provider.Caches;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link EventFactory} implementation storing registered events in {@code
 * database-driver-api}'s own {@link Cache}, keyed by the event's own {@code
 * Class} - the same thread-safe, amortized-{@code O(1)} {@link
 * Cache#put}/{@link Cache#invalidate} contract {@code EntityDatabaseClient}
 * and {@code InMemoryPendingUploadCache} already build on, rather than a
 * hand-rolled map or list.
 *
 * <p>Unlike those two, this class actively uses {@link Cache#get}'s async,
 * stampede-protected loader: {@link #registerEvent} resolves through it
 * rather than constructing {@code eventClass} itself, so if two threads race
 * to register the very same, not-yet-registered event type concurrently,
 * {@code database-driver-api}'s {@link Cache} guarantees the loader - and
 * therefore {@code eventClass}'s no-arg constructor - runs at most once, and
 * both callers observe the same resulting instance, rather than each
 * constructing (and one silently overwriting the other's) instance. {@link
 * #findEventByClass}/{@link #callEvent}/{@link #unregisterEvent}/{@link
 * #getEvents}, in contrast, must never trigger construction of an event that
 * was never registered, so they read {@link Cache#snapshot()} directly and
 * never call {@link Cache#get}.
 *
 * <p>Constructed with no TTL and no size bound: a registered event is a
 * long-lived singleton, not something that should silently expire or be
 * evicted while still in use.
 */
public final class DefaultEventFactory extends EventFactory {

    private final Cache<Class<? extends Event>, Event> events =
            Caches.newCache(DefaultEventFactory::construct, null, -1);

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

    @NotNull
    @Override
    public <T extends Event> T unregisterEvent(@NonNull final Class<T> eventClass) {
        final T event = requireRegistered(eventClass);
        this.events.invalidate(eventClass);
        return event;
    }

    @NotNull
    @Override
    public <T extends Event> T callEvent(@NonNull final Class<T> eventClass, @NonNull final JsonDocument properties) {
        final T event = requireRegistered(eventClass);
        event.handle(properties);
        return event;
    }

    @NotNull
    @Override
    public <T extends Event> Optional<T> findEventByClass(@NonNull final Class<T> eventClass) {
        return Optional.ofNullable(this.events.snapshot().get(eventClass)).map(eventClass::cast);
    }

    @NotNull
    @Override
    public Collection<Event> getEvents() {
        return List.copyOf(this.events.snapshot().values());
    }

    @NotNull
    private <T extends Event> T requireRegistered(final Class<T> eventClass) {
        return findEventByClass(eventClass).orElseThrow(() -> new IllegalStateException(
                "@DefaultEventFactory: no event of type '" + eventClass.getName() + "' is registered - register it first via registerEvent"
        ));
    }

    /**
     * The {@link #events} cache's loader: constructs {@code eventClass} via
     * its no-arg constructor (see {@link Event}'s class Javadoc), dispatched
     * on {@link MultiTaskingFactory}'s shared virtual-thread executor - a
     * {@link Cache} loader must never block the calling thread (see {@link
     * Cache}'s class Javadoc), and reflective construction, while normally
     * fast, is arbitrary code as far as this class is concerned.
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

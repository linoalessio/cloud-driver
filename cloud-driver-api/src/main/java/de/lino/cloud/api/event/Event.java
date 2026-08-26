package de.lino.cloud.api.event;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.factory.EventFactory;
import de.lino.cloud.api.utility.Asserts;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;

/**
 * One event type and its handling logic combined - a subclass models both
 * "what this event is" and "what happens when it fires", the same shape
 * {@link de.lino.cloud.api.extension.Extension} uses for one extension.
 * Registered and looked up by {@code Class}, not by name, through {@link
 * EventFactory}: {@link EventFactory#registerEvent} reflectively
 * instantiates a subclass via its no-arg constructor - so every concrete
 * {@link Event} subclass must expose one (implicitly, by declaring no
 * constructor of its own, or explicitly).
 *
 * <p>Unlike {@link de.lino.cloud.api.extension.Extension}, where the caller
 * constructs the instance itself and only registration is deferred to a
 * factory, an {@link Event} is never constructed directly by a caller -
 * {@link EventFactory#registerEvent} owns construction, so exactly one
 * instance ever exists per registered subclass, reused for every {@link
 * EventFactory#callEvent} invocation of that type.
 */
public abstract class Event {

    /**
     * Runs this event's handling logic against {@code properties} - invoked
     * by {@link EventFactory#callEvent} on the single instance {@link
     * EventFactory#registerEvent} created for this event's class.
     *
     * @param properties the event's payload
     * @throws NullPointerException if {@code properties} is {@code null}
     */
    public abstract void handle(@NonNull final JsonDocument properties);

    @NonNull
    public CloudAPI cloudAPI() {
        return Asserts.requireNonNull(CloudAPI.getInstance());
    }

}

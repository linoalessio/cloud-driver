package de.lino.cloud.api.event;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.EventFactory;
import de.lino.cloud.api.utility.Asserts;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;

/**
 * One event type and its handling logic combined. Registered and looked up by
 * {@code Class} through {@link EventFactory}, which reflectively instantiates
 * a subclass via its no-arg constructor - exactly one instance ever exists
 * per registered subclass.
 */
public abstract class Event {

    /**
     * Runs this event's handling logic against {@code properties}.
     *
     * @param properties the event's payload
     * @throws NullPointerException if {@code properties} is {@code null}
     */
    public abstract void handle(@NonNull final JsonDocument properties);

    /**
     * Returns the host process's {@link CloudDriver} singleton.
     *
     * @return the {@link CloudDriver} singleton
     * @throws NullPointerException if {@link CloudDriver#getInstance()} has not been set up yet
     */
    @NonNull
    public CloudDriver cloudDriver() {
        return Asserts.requireNonNull(CloudDriver.getInstance());
    }

}

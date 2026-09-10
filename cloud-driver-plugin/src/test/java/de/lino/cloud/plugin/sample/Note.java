package de.lino.cloud.plugin.sample;

import de.lino.database.database.entity.Serialized;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Dummy {@link Serialized} entity, used only by {@link RestFactorySample} to demonstrate
 * mounting a resource on {@link de.lino.cloud.api.factory.RestFactory} - not part of the
 * real application, has no meaning outside this sample.
 */
@Getter @ToString
public final class Note extends Serialized {

    /** This note's primary key. */
    private final String id;

    /** This note's body text. */
    private final String text;

    /**
     * @param id this note's primary key
     * @param text this note's body text
     * @throws NullPointerException if {@code id} or {@code text} is {@code null}
     */
    public Note(@NotNull final String id, @NotNull final String text) {
        this.id = Objects.requireNonNull(id, "@Note.init: id cannot be null");
        this.text = Objects.requireNonNull(text, "@Note.init: text cannot be null");
    }

    /** @return this entity's primary key, {@link #id} */
    @Override
    public List<String> keysOf() {
        return List.of(this.id);
    }

}

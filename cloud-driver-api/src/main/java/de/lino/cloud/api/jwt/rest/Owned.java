package de.lino.cloud.api.jwt.rest;

import de.lino.database.database.entity.Serialized;
import org.jetbrains.annotations.NotNull;

/**
 * Marks a {@link Serialized} entity as belonging to a specific end user, so
 * a JWT-authenticated {@code DefaultRestFactory} route can scope reads and
 * writes to the caller's own data instead of exposing every record of that
 * type to every logged-in user.
 *
 * <p>An entity implementing this interface is expected to (de)serialize its
 * owner id under the JSON field name {@code "ownerId"} - {@code
 * DefaultRestFactory}'s {@code register}/{@code update} handlers overwrite
 * that field in the request body with the authenticated caller's user id
 * before deserializing, so a client can never write a record under someone
 * else's ownership, even by sending a spoofed {@code ownerId} of its own.
 *
 * <p>Only takes effect on a {@code DefaultRestFactory} instance constructed
 * with an {@code AuthService} (JWT auth); the unauthenticated and {@code
 * ApiKey}-gated constructors have no per-request user identity to scope by,
 * so an {@code Owned} entity exposed through either of those is not filtered.
 */
public interface Owned {

    /**
     * @return the id of the {@link de.lino.cloud.api.jwt.user.AuthUser} that owns this entity
     */
    @NotNull
    String ownerId();
}

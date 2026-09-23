package de.lino.cloud.api.jwt;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * What verifying an access token yields: the identity it asserts, and the session generation it
 * was signed at.
 *
 * @param subject the {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} the token asserts
 * @param tokenVersion the account's session generation at signing time - {@code 0} for a token
 *     signed before the claim existed, which is also where every account starts, so a token
 *     already in a client's hands keeps working until it expires
 */
public record VerifiedAccessToken(@NotNull String subject, int tokenVersion) {

    /**
     * @throws NullPointerException if {@code subject} is {@code null}
     */
    public VerifiedAccessToken {
        Objects.requireNonNull(subject, "@VerifiedAccessToken.init: subject cannot be null");
    }

}

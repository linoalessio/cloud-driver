package de.lino.cloud.api.file;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A public, unauthenticated share link on a {@link StoredFile} - section 6 of {@code
 * architecture/MICRO.md}. {@code token} is the whole of what's needed to resolve the file's
 * content through the public {@code GET /public/files/{token}} route (no login at all) - it is
 * never split into a separate raw/hash pair the way {@code ApiKey} is, the same "the token itself
 * is its own primary key, envelope-encrypted at rest, never redisplayed a second time after
 * creation" shape {@code RefreshToken} already established.
 *
 * @param token the unguessable, {@code SecureRandom}-generated token identifying this link
 * @param createdAtEpochMillis when this link was created, as epoch millis
 * @param expiresAtEpochMillis when this link expires, as epoch millis, or {@code null} if it never does
 */
public record PublicFileLinkSummary(@NotNull String token, long createdAtEpochMillis, @Nullable Long expiresAtEpochMillis) {
}

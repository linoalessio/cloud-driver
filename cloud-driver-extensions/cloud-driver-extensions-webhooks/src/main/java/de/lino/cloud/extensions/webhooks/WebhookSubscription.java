package de.lino.cloud.extensions.webhooks;

import de.lino.cloud.api.jwt.rest.Owned;
import de.lino.cloud.api.webhook.WebhookEventType;
import de.lino.cloud.api.factory.SecondaryIndexed;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One account's webhook subscription. Persisted the
 * same envelope-encrypted way as any other {@link Serialized} entity, so {@link #secret} is
 * protected at rest by this codebase's usual guarantee - never mounted through {@code
 * DefaultRestFactory}'s generic {@link Owned}-based routes (the same reasoning {@code
 * SharedFileGrant}/{@code PublicShareLink} already document for themselves), only through the
 * bespoke {@code /webhooks*} routes reaching {@link de.lino.cloud.api.webhook.WebhookService} directly.
 */
@Getter @ToString(exclude = "secret")
@EqualsAndHashCode(callSuper = false)
public final class WebhookSubscription extends Serialized implements Owned, SecondaryIndexed {

    /** Secondary-index name for lookups by {@link #getOwnerAuthUserId()} - every per-user subscription listing/dispatch. */
    public static final String INDEX_OWNER_AUTH_USER_ID = "ownerAuthUserId";

    /**
     * {@inheritDoc} Hand-declared: {@link #INDEX_OWNER_AUTH_USER_ID} → the subscribing account.
     * Defensive against a {@code null} field (Gson rehydration bypasses the constructor's null
     * checks).
     */
    @NotNull
    @Override
    public java.util.Map<String, String> secondaryIndexKeys() {
        return this.ownerAuthUserId == null ? java.util.Map.of() : java.util.Map.of(INDEX_OWNER_AUTH_USER_ID, this.ownerAuthUserId);
    }


    /** Length, in bytes, of the random signing-secret material generated for a fresh subscription. */
    private static final int SECRET_LENGTH_BYTES = 32;

    /** This subscription's own id, its {@link #primaryKey()}. */
    private final String id;

    /** The account this subscription belongs to - also this row's {@link #ownerId()}. */
    private final String ownerAuthUserId;

    /** The {@code https://} URL events are delivered to. */
    private final String url;

    /** The HMAC-SHA256 signing secret every delivery to {@link #url} is signed with - see {@link #ownerAuthUserId}'s own Javadoc for why this is never mounted generically. */
    private final String secret;

    /** Which {@link WebhookEventType}s this subscription is registered for. */
    private final Set<WebhookEventType> eventTypes;

    /** When this subscription was created, as epoch millis. */
    private final long createdAtEpochMillis;

    /**
     * Creates a fresh subscription, generating a random signing secret and stamping {@link
     * #createdAtEpochMillis} with the current time.
     *
     * @param ownerAuthUserId the account this subscription belongs to
     * @param url the {@code https://} URL to deliver events to - already validated by the caller
     * @param eventTypes which {@link WebhookEventType}s to subscribe to
     */
    public WebhookSubscription(@NotNull final String ownerAuthUserId, @NotNull final String url,
                                @NotNull final Set<WebhookEventType> eventTypes) {
        this(UUID.randomUUID().toString(), ownerAuthUserId, url, generateSecret(), eventTypes, System.currentTimeMillis());
    }

    /**
     * Full constructor, for re-hydrating a subscription with a known id/secret/timestamp (Gson deserialization).
     */
    public WebhookSubscription(@NotNull final String id, @NotNull final String ownerAuthUserId, @NotNull final String url,
                                @NotNull final String secret, @NotNull final Set<WebhookEventType> eventTypes,
                                final long createdAtEpochMillis) {
        this.id = Objects.requireNonNull(id, "@WebhookSubscription.init: id cannot be null");
        this.ownerAuthUserId = Objects.requireNonNull(ownerAuthUserId, "@WebhookSubscription.init: ownerAuthUserId cannot be null");
        this.url = Objects.requireNonNull(url, "@WebhookSubscription.init: url cannot be null");
        this.secret = Objects.requireNonNull(secret, "@WebhookSubscription.init: secret cannot be null");
        this.eventTypes = Objects.requireNonNull(eventTypes, "@WebhookSubscription.init: eventTypes cannot be null");
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    /** @return a fresh, random, base64url-encoded signing secret of {@link #SECRET_LENGTH_BYTES} bytes */
    private static String generateSecret() {
        final byte[] raw = new byte[SECRET_LENGTH_BYTES];
        new SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** @return this entity's primary key, {@link #id} */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(this.id);
    }

    /** @return {@link #ownerAuthUserId} */
    @NotNull
    @Override
    public String ownerId() {
        return this.ownerAuthUserId;
    }

}

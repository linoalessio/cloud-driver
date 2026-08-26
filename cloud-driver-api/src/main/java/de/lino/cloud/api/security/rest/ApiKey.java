package de.lino.cloud.api.security.rest;

import de.lino.cloud.api.security.hash.HashAlgorithm;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * A persisted, high-entropy static API key that verifies a candidate against
 * itself - backs the optional {@code X-API-Key} check on Javalin-based entry
 * points in this codebase. A {@link Serialized} domain entity, so it is
 * envelope-encrypted (AES-256-GCM) before it ever reaches the database like
 * any other entity.
 */
@Getter @ToString(exclude = {"apiKeyRaw"})
@EqualsAndHashCode(callSuper = false)
public final class ApiKey extends Serialized {

    private static final int RAW_KEY_LENGTH_BYTES = 32;

    /** This key's primary key - lets a deployment keep more than one named key. */
    private final String id;

    /** The plaintext key value; excluded from {@link #toString()}. */
    private final String apiKeyRaw;

    /** SHA-256 digest of {@link #apiKeyRaw}, used by {@link #isValid} for verification. */
    private final String apiKeyHashHex;

    /**
     * Generates a fresh, random API key (32 bytes via {@link SecureRandom},
     * base64url-encoded), computing both the raw value and its SHA-256
     * digest. Not called by Gson when reconstructing an already-persisted
     * key (fields are set via reflection instead).
     *
     * @param id this key's primary key
     */
    public ApiKey(@NotNull final String id) {

        this.id = Objects.requireNonNull(id, "@ApiKey.init: id cannot be null");

        final byte[] rawKey = new byte[RAW_KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(rawKey);

        this.apiKeyRaw = Base64.getUrlEncoder().withoutPadding().encodeToString(rawKey);
        this.apiKeyHashHex = HexFormat.of().formatHex(sha256(this.apiKeyRaw.getBytes(StandardCharsets.UTF_8)));

    }

    /** @return this entity's primary key, {@link #id} */
    @Override
    public List<String> keysOf() {
        return List.of(this.id);
    }

    /**
     * Checks {@code candidateApiKey} against this key's digest, in constant time.
     *
     * @param candidateApiKey the key to verify
     * @return {@code true} if it matches, {@code false} otherwise
     */
    public boolean isValid(@NotNull final String candidateApiKey) {
        Objects.requireNonNull(candidateApiKey, "@ApiKey.isValid: candidateApiKey cannot be null");
        final byte[] candidateHash = sha256(candidateApiKey.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(candidateHash, HexFormat.of().parseHex(this.apiKeyHashHex));
    }

    /** Computes a {@link HashAlgorithm#SHA_256} digest of {@code data}. */
    private static byte[] sha256(final byte[] data) {
        try {
            return MessageDigest.getInstance(HashAlgorithm.SHA_256.jcaName()).digest(data);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("@ApiKey.sha256: JVM does not provide " + HashAlgorithm.SHA_256.jcaName(), e);
        }
    }

}

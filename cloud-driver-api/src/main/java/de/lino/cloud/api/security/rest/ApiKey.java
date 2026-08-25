package de.lino.cloud.api.security.rest;

import de.lino.cloud.api.security.hash.HashAlgorithm;
import de.lino.database.database.entity.Serialized;
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
 * A persisted, high-entropy static API key that verifies a candidate
 * against itself - intended to back {@code
 * de.lino.cloud.plugin.factory.DefaultRestFactory}'s optional {@code
 * X-API-Key} check, and any other Javalin-based entry point in this
 * codebase that needs the same check, so they all verify requests the
 * same way.
 *
 * <p>A {@link Serialized} domain entity so it goes through {@code
 * DataFactory} the same way any other entity does - envelope-encrypted
 * (AES-256-GCM) before it ever reaches the database, per this repo's
 * security requirements, rather than sitting on a filesystem in a
 * gitignored-but-still-plaintext JSON file. A caller that needs an
 * existing-or-fresh key looks it up first (e.g. {@code
 * dataFactory.findById(id, ApiKey.class)}) and only constructs a new one -
 * via {@link #ApiKey(String)}, which generates a fresh key immediately -
 * on a miss, then persists it via {@code dataFactory.register(...)}; this
 * class deliberately has no {@code DataFactory} dependency of its own,
 * the same "entities are dumb data, factories orchestrate persistence"
 * split every other entity in this codebase follows.
 *
 * <p>The key is high-entropy and machine-generated (32 random bytes via
 * {@link SecureRandom}, base64url-encoded), so - unlike a user-chosen
 * password - it doesn't need {@code Argon2idPasswordHasher}'s deliberately
 * slow KDF (that would add needless latency to every request); {@link
 * #isValid} instead hashes the candidate once per check with {@link
 * #sha256}, a fast/deterministic digest - the same choice {@code
 * Hasher}'s own Javadoc (in {@code cloud-driver-plugin}) points to for
 * anything other than password storage, computed directly here via
 * {@link MessageDigest} rather than through {@code Hasher} itself, since
 * this class lives in {@code cloud-driver-api}, which never depends on
 * {@code cloud-driver-plugin}. Only the digest is ever persisted - never
 * the raw key, which is discarded the moment {@link #ApiKey(String)}
 * returns - and {@link #isValid} compares via {@link
 * MessageDigest#isEqual}, the same constant-time-comparison idiom {@code
 * Argon2idPasswordHasher#verify} uses, to avoid a timing side-channel.
 */
public final class ApiKey extends Serialized {

    private static final int RAW_KEY_LENGTH_BYTES = 32;

    private final String id;
    private final String apiKeyHashHex;

    /**
     * Generates a fresh, random API key and stores only its SHA-256
     * digest - the raw key itself is never held past this constructor and
     * is never persisted. Gson does not call this constructor when
     * reconstructing an already-persisted {@link ApiKey} (it sets fields
     * via reflection instead, the same pattern {@code StoredFile} uses),
     * so this only ever runs when a caller explicitly wants a brand-new
     * key.
     *
     * @param id this key's primary key - lets a deployment keep more than one named key
     */
    public ApiKey(@NotNull final String id) {
        this.id = Objects.requireNonNull(id, "@ApiKey.<init>: id cannot be null");

        final byte[] rawKey = new byte[RAW_KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(rawKey);
        final String rawApiKey = Base64.getUrlEncoder().withoutPadding().encodeToString(rawKey);

        this.apiKeyHashHex = HexFormat.of().formatHex(sha256(rawApiKey.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public List<String> keysOf() {
        return List.of(this.id);
    }

    /**
     * Whether {@code candidateApiKey} matches this key.
     */
    public boolean isValid(@NotNull final String candidateApiKey) {
        Objects.requireNonNull(candidateApiKey, "@ApiKey.isValid: candidateApiKey cannot be null");
        final byte[] candidateHash = sha256(candidateApiKey.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(candidateHash, HexFormat.of().parseHex(this.apiKeyHashHex));
    }

    /**
     * {@link HashAlgorithm#SHA_256}, computed directly via {@link
     * MessageDigest} - see the class Javadoc for why this doesn't go
     * through {@code Hasher} the way the rest of the codebase's hashing
     * does.
     */
    private static byte[] sha256(final byte[] data) {
        try {
            return MessageDigest.getInstance(HashAlgorithm.SHA_256.jcaName()).digest(data);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("@ApiKey.sha256: JVM does not provide " + HashAlgorithm.SHA_256.jcaName(), e);
        }
    }

}

package de.lino.cloud.plugin.security.password;

import de.lino.cloud.api.security.password.PasswordHasher;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import de.lino.cloud.api.utility.Asserts;

/**
 * {@link PasswordHasher} implementation using Argon2id. Defaults follow the
 * OWASP baseline (19 MiB memory, 2 iterations, 1 degree of parallelism);
 * tune via the constructor. Encodes as a PHC-style string (e.g. {@code
 * $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>}), so its parameters travel
 * with the hash and can change over time without invalidating old hashes.
 */
public final class Argon2idPasswordHasher implements PasswordHasher {

    /** Length, in bytes, of each freshly generated salt. */
    private static final int SALT_LENGTH_BYTES = 16;

    /** Length, in bytes, of the raw Argon2id output. */
    private static final int HASH_LENGTH_BYTES = 32;

    /** OWASP-baseline default memory cost, in KiB (19 MiB). */
    private static final int DEFAULT_MEMORY_KIB = 19 * 1024;

    /** OWASP-baseline default iteration count. */
    private static final int DEFAULT_ITERATIONS = 2;

    /** OWASP-baseline default degree of parallelism. */
    private static final int DEFAULT_PARALLELISM = 1;

    /** Memory cost, in KiB, this instance hashes new passwords with. */
    private final int memoryKib;

    /** Iteration count this instance hashes new passwords with. */
    private final int iterations;

    /** Degree of parallelism this instance hashes new passwords with. */
    private final int parallelism;

    /** Source of each fresh random salt {@link #hash} draws from. */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Constructs a hasher using the OWASP Argon2id baseline defaults
     * ({@link #DEFAULT_MEMORY_KIB}/{@link #DEFAULT_ITERATIONS}/{@link #DEFAULT_PARALLELISM}).
     */
    public Argon2idPasswordHasher() {
        this(DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS, DEFAULT_PARALLELISM);
    }

    /**
     * Constructs a hasher with explicit Argon2id cost parameters, rather than the OWASP baseline defaults.
     *
     * @param memoryKib memory cost, in KiB
     * @param iterations number of iterations
     * @param parallelism degree of parallelism
     */
    public Argon2idPasswordHasher(final int memoryKib, final int iterations, final int parallelism) {
        this.memoryKib = memoryKib;
        this.iterations = iterations;
        this.parallelism = parallelism;
    }

    /**
     * Hashes {@code password} with a fresh random salt.
     *
     * @param password the password to hash
     * @return the PHC-encoded hash
     * @throws NullPointerException if {@code password} is {@code null}
     */
    @Override
    public String hash(final char[] password) {
        Asserts.requireNonNull(password, "@Argon2idPasswordHasher.hash: password cannot be null");

        final byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);

        final byte[] hash = rawHash(password, salt, memoryKib, iterations, parallelism);
        return encode(salt, hash, memoryKib, iterations, parallelism);
    }

    /**
     * Verifies {@code password} against a previously hashed value, using the
     * parameters encoded in it. Compares via {@link MessageDigest#isEqual}
     * (constant-time), the same idiom {@code ApiKey#isValid} uses in
     * {@code cloud-driver-api}.
     *
     * @param password the password to check
     * @param encodedHash a PHC-encoded hash produced by {@link #hash}
     * @return {@code true} if {@code password} matches {@code encodedHash}
     * @throws NullPointerException if {@code password} or {@code encodedHash} is {@code null}
     * @throws IllegalArgumentException if {@code encodedHash} is not a valid argon2id encoded hash
     */
    @Override
    public boolean verify(final char[] password, final String encodedHash) {
        Asserts.requireNonNull(password, "@Argon2idPasswordHasher.verify: password cannot be null");
        Asserts.requireNonNull(encodedHash, "@Argon2idPasswordHasher.verify: encodedHash cannot be null");

        final Decoded decoded = decode(encodedHash);
        final byte[] candidate = rawHash(password, decoded.salt, decoded.memoryKib, decoded.iterations, decoded.parallelism);
        return MessageDigest.isEqual(candidate, decoded.hash);
    }

    /**
     * Runs Argon2id (version 0x13, mode {@code ARGON2_id}) over {@code
     * password} with the given salt and cost parameters.
     *
     * @param password the password to hash
     * @param salt the salt to hash with
     * @param memoryKib memory cost, in KiB
     * @param iterations number of iterations
     * @param parallelism degree of parallelism
     * @return the raw digest bytes, {@link #HASH_LENGTH_BYTES} long
     */
    private byte[] rawHash(final char[] password, final byte[] salt, final int memoryKib,
                            final int iterations, final int parallelism) {
        final Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build();

        final Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);

        final byte[] hash = new byte[HASH_LENGTH_BYTES];
        generator.generateBytes(password, hash);
        return hash;
    }

    /**
     * Formats a PHC-style Argon2id string ({@code
     * $argon2id$v=19$m=<memoryKib>,t=<iterations>,p=<parallelism>$<salt>$<hash>},
     * salt/hash unpadded base64) from its raw components.
     *
     * @param salt the salt used
     * @param hash the raw digest, as produced by {@link #rawHash}
     * @param memoryKib memory cost, in KiB
     * @param iterations number of iterations
     * @param parallelism degree of parallelism
     * @return the PHC-encoded string
     */
    private static String encode(final byte[] salt, final byte[] hash, final int memoryKib,
                                  final int iterations, final int parallelism) {
        final Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        return "$argon2id$v=19$m=" + memoryKib + ",t=" + iterations + ",p=" + parallelism
                + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    /**
     * Parses a PHC-style Argon2id string produced by {@link #encode} back
     * into its salt, hash, and cost parameters.
     *
     * @param encodedHash the PHC-encoded string to parse
     * @return the decoded parts
     * @throws IllegalArgumentException if {@code encodedHash} is not a valid argon2id encoded hash
     */
    private static Decoded decode(final String encodedHash) {
        final String[] parts = encodedHash.split("\\$");
        if (parts.length != 6 || !"argon2id".equals(parts[1])) {
            throw new IllegalArgumentException("@Argon2idPasswordHasher.decode: not a valid argon2id encoded hash");
        }

        int memoryKib = 0;
        int iterations = 0;
        int parallelism = 0;
        for (final String param : parts[3].split(",")) {
            final String[] keyValue = param.split("=", 2);
            switch (keyValue[0]) {
                case "m" -> memoryKib = Integer.parseInt(keyValue[1]);
                case "t" -> iterations = Integer.parseInt(keyValue[1]);
                case "p" -> parallelism = Integer.parseInt(keyValue[1]);
                default -> { /* ignore unknown/forward-compatible parameters */ }
            }
        }

        final Base64.Decoder decoder = Base64.getDecoder();
        return new Decoded(decoder.decode(parts[4]), decoder.decode(parts[5]), memoryKib, iterations, parallelism);
    }

    /**
     * The parsed components of a PHC-style Argon2id encoded hash string, as produced by {@link #decode}.
     *
     * @param salt the salt the hash was computed with
     * @param hash the raw digest bytes
     * @param memoryKib memory cost, in KiB, the hash was computed with
     * @param iterations number of iterations the hash was computed with
     * @param parallelism degree of parallelism the hash was computed with
     */
    private record Decoded(byte[] salt, byte[] hash, int memoryKib, int iterations, int parallelism) {
    }
}

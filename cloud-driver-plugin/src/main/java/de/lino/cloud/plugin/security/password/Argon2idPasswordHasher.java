package de.lino.cloud.plugin.security.password;

import de.lino.cloud.api.security.password.PasswordHasher;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import de.lino.cloud.api.utility.Asserts;

/**
 * {@link PasswordHasher} implementation using Argon2id, per section 5
 * (PASSWORDS). Defaults follow the OWASP Password Storage Cheat Sheet
 * Argon2id baseline (19 MiB memory, 2 iterations, 1 degree of parallelism);
 * tune via the constructor for the target deployment's hardware and latency
 * budget.
 *
 * <p>Encodes as a PHC-style string, e.g.
 * {@code $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>}, so the parameters
 * used to produce a given hash travel with it and can be changed over time
 * without invalidating previously stored hashes.
 */
public final class Argon2idPasswordHasher implements PasswordHasher {

    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int DEFAULT_MEMORY_KIB = 19 * 1024;
    private static final int DEFAULT_ITERATIONS = 2;
    private static final int DEFAULT_PARALLELISM = 1;

    private final int memoryKib;
    private final int iterations;
    private final int parallelism;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Constructs a hasher using the OWASP Argon2id baseline defaults
     * ({@link #DEFAULT_MEMORY_KIB}/{@link #DEFAULT_ITERATIONS}/{@link #DEFAULT_PARALLELISM}).
     */
    public Argon2idPasswordHasher() {
        this(DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS, DEFAULT_PARALLELISM);
    }

    /**
     * @param memoryKib memory cost, in KiB
     * @param iterations number of iterations
     * @param parallelism degree of parallelism
     */
    public Argon2idPasswordHasher(final int memoryKib, final int iterations, final int parallelism) {
        this.memoryKib = memoryKib;
        this.iterations = iterations;
        this.parallelism = parallelism;
    }

    @Override
    public String hash(final char[] password) {
        Asserts.requireNonNull(password, "@Argon2idPasswordHasher.hash: password cannot be null");

        final byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);

        final byte[] hash = rawHash(password, salt, memoryKib, iterations, parallelism);
        return encode(salt, hash, memoryKib, iterations, parallelism);
    }

    @Override
    public boolean verify(final char[] password, final String encodedHash) {
        Asserts.requireNonNull(password, "@Argon2idPasswordHasher.verify: password cannot be null");
        Asserts.requireNonNull(encodedHash, "@Argon2idPasswordHasher.verify: encodedHash cannot be null");

        final Decoded decoded = decode(encodedHash);
        final byte[] candidate = rawHash(password, decoded.salt, decoded.memoryKib, decoded.iterations, decoded.parallelism);
        return MessageDigest.isEqual(candidate, decoded.hash);
    }

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

    private static String encode(final byte[] salt, final byte[] hash, final int memoryKib,
                                  final int iterations, final int parallelism) {
        final Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        return "$argon2id$v=19$m=" + memoryKib + ",t=" + iterations + ",p=" + parallelism
                + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

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

    private record Decoded(byte[] salt, byte[] hash, int memoryKib, int iterations, int parallelism) {
    }
}

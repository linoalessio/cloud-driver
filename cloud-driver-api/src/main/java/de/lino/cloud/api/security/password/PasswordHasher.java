package de.lino.cloud.api.security.password;

/**
 * Non-reversible password hashing (e.g. Argon2id), for the rare case this
 * driver itself needs to store a password.
 */
public interface PasswordHasher {

    /**
     * Hashes {@code password}, returning a single self-describing string
     * (algorithm, parameters, salt, hash) suitable for storage and later
     * {@link #verify}.
     *
     * @param password the password to hash
     * @return the encoded hash
     */
    String hash(char[] password);

    /**
     * Verifies {@code password} against a previously {@link #hash(char[]) hashed}
     * value, using the algorithm and parameters embedded in {@code encodedHash}.
     *
     * @param password the candidate password
     * @param encodedHash a previously produced {@link #hash(char[])} result
     * @return {@code true} if {@code password} matches, {@code false} otherwise
     */
    boolean verify(char[] password, String encodedHash);
}

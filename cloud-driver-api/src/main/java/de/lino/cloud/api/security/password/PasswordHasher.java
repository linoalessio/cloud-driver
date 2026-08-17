package de.lino.cloud.api.security.password;

/**
 * Section 5 (PASSWORDS): "Passwords SHALL NOT be stored using reversible
 * encryption. If passwords must be stored, use Argon2id [...]." Only relevant
 * if this driver itself ever needs to store a password; prefer OAuth 2.0
 * client credentials or mTLS for service-to-service authentication instead
 * (section 2A).
 */
public interface PasswordHasher {

    /**
     * Hashes {@code password}, returning a single self-describing string
     * (algorithm, parameters, salt, hash) suitable for storage and later
     * {@link #verify}.
     */
    String hash(char[] password);

    /**
     * Verifies {@code password} against a previously {@link #hash(char[]) hashed}
     * value, using the algorithm and parameters embedded in {@code encodedHash}.
     */
    boolean verify(char[] password, String encodedHash);
}

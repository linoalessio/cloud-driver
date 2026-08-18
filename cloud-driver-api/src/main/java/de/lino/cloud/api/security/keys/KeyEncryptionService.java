package de.lino.cloud.api.security.keys;

import org.jetbrains.annotations.NotNull;

/**
 * Abstraction over a centralized KMS or HSM managing key-encryption keys
 * (KEKs), per section 4 (KEY MANAGEMENT): "The DEK SHALL be protected/wrapped
 * by a key-encryption key (KEK) managed by a KMS/HSM." and "Key rotation
 * SHALL be supported."
 *
 * <p>Implementations SHOULD delegate the actual wrap/unwrap operation to the
 * KMS/HSM itself (so KEK material never leaves it) rather than fetching raw
 * KEK bytes into the extension process. {@link InMemoryKeyEncryptionService}
 * is the exception - a development/test stand-in only, clearly documented as
 * such.
 */
public interface KeyEncryptionService {

    /**
     * Wraps (encrypts) a {@link DataEncryptionKey} under the currently active
     * key-encryption key.
     */
    @NotNull
    WrappedKey wrap(@NotNull DataEncryptionKey dataEncryptionKey) throws KeyWrapException;

    /**
     * Unwraps (decrypts) a previously {@link #wrap(DataEncryptionKey) wrapped}
     * key, using whichever key-encryption key version wrapped it - including
     * versions superseded by a later {@link #rotate()}.
     */
    @NotNull
    DataEncryptionKey unwrap(@NotNull WrappedKey wrappedKey) throws KeyWrapException;

    /**
     * The identifier of the key-encryption key version currently used for new
     * {@link #wrap(DataEncryptionKey)} calls.
     */
    @NotNull
    String activeKeyEncryptionKeyId();

    /**
     * Activates a new key-encryption key version for future {@link
     * #wrap(DataEncryptionKey)} calls, without invalidating the ability to
     * {@link #unwrap(WrappedKey)} data wrapped under earlier versions.
     *
     * @return the identifier of the newly active key-encryption key
     */
    @NotNull
    String rotate();
}

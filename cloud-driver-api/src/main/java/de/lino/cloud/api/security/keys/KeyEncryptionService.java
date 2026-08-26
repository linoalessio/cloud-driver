package de.lino.cloud.api.security.keys;

import org.jetbrains.annotations.NotNull;

/**
 * Abstraction over a centralized KMS/HSM managing key-encryption keys (KEKs)
 * that wrap/unwrap DEKs and support key rotation. Implementations should
 * delegate the actual wrap/unwrap to the KMS/HSM itself, so KEK material
 * never leaves it.
 */
public interface KeyEncryptionService {

    /**
     * Wraps (encrypts) a {@link DataEncryptionKey} under the currently active
     * key-encryption key.
     *
     * @param dataEncryptionKey the key to wrap
     * @return the wrapped key
     * @throws KeyWrapException if wrapping fails
     */
    @NotNull
    WrappedKey wrap(@NotNull DataEncryptionKey dataEncryptionKey) throws KeyWrapException;

    /**
     * Unwraps (decrypts) a previously {@link #wrap(DataEncryptionKey) wrapped}
     * key, using whichever key-encryption key version wrapped it - including
     * versions superseded by a later {@link #rotate()}.
     *
     * @param wrappedKey the key to unwrap
     * @return the unwrapped key
     * @throws KeyWrapException if unwrapping fails
     */
    @NotNull
    DataEncryptionKey unwrap(@NotNull WrappedKey wrappedKey) throws KeyWrapException;

    /**
     * Returns the identifier of the key-encryption key version currently used
     * for new {@link #wrap(DataEncryptionKey)} calls.
     *
     * @return the active key-encryption key identifier
     */
    @NotNull
    String activeKeyEncryptionKeyId();

    /**
     * Activates a new key-encryption key version for future {@link
     * #wrap(DataEncryptionKey)} calls, without invalidating the ability to
     * {@link #unwrap(WrappedKey)} data wrapped under earlier versions.
     *
     * @return the newly active key-encryption key identifier
     */
    @NotNull
    String rotate();
}

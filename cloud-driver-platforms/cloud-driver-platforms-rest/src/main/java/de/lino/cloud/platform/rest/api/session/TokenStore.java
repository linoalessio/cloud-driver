package de.lino.cloud.platform.rest.api.session;

import java.util.Optional;

/**
 * Persists a single session JWT somewhere safer than a plain settings file - the concrete
 * mechanism is entirely OS-specific (see {@link TokenStoreFactory}). Every implementation
 * stores at most one token at a time under a fixed, hardcoded service/account identifier -
 * this desktop client only ever has one logged-in session, matching {@code ApiClient}'s own
 * single-session design.
 */
public interface TokenStore {

    /**
     * Persists {@code token}, overwriting any previously stored value.
     *
     * @param token the session JWT to store
     * @throws TokenStoreException if the underlying storage mechanism fails to write the value
     */
    void save(String token) throws TokenStoreException;

    /**
     * @return the previously stored token, or {@link Optional#empty()} if none is stored
     * @throws TokenStoreException if the underlying storage mechanism fails to read the value
     */
    Optional<String> load() throws TokenStoreException;

    /**
     * Removes any previously stored token; a no-op (not an error) if none was stored.
     *
     * @throws TokenStoreException if the underlying storage mechanism fails to remove the value
     */
    void clear() throws TokenStoreException;

}

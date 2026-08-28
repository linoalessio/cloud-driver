package de.lino.clouddriver.desktop.api.session;

import java.util.Optional;

/**
 * Persists a single session JWT somewhere safer than a plain settings file - the concrete
 * mechanism is entirely OS-specific (see {@link TokenStoreFactory}). Every implementation
 * stores at most one token at a time under a fixed, hardcoded service/account identifier -
 * this desktop app only ever has one logged-in session, matching {@code ApiClient}'s own
 * single-session design.
 */
public interface TokenStore {

    /** Persists {@code token}, overwriting any previously stored value. */
    void save(String token) throws TokenStoreException;

    /** @return the previously stored token, or {@link Optional#empty()} if none is stored */
    Optional<String> load() throws TokenStoreException;

    /** Removes any previously stored token; a no-op (not an error) if none was stored. */
    void clear() throws TokenStoreException;

}

package de.lino.cloud.api.connectivity;

/**
 * Reports whether outbound network connectivity is currently available -
 * used by {@code cloud-driver-plugin}'s pending-upload machinery to decide
 * whether a {@link de.lino.cloud.api.file.StoredFile} upload should be
 * attempted against the configured database or deferred instead. Deliberately
 * independent of any particular database driver: a database call failing
 * does not by itself distinguish "no internet connection" from any other
 * persistence failure, so implementations answer this question directly
 * (e.g. by probing a well-known external host) rather than by inspecting a
 * database exception.
 *
 * <p>Only one production implementation is expected per deployment, the same
 * "interface in {@code cloud-driver-api}, concrete implementation(s) in
 * {@code cloud-driver-plugin}" shape as {@link
 * de.lino.cloud.api.security.keys.KeyEncryptionService}.
 */
public interface ConnectivityChecker {

    /**
     * @return {@code true} if outbound network connectivity is currently available, {@code false} otherwise
     */
    boolean isAvailable();

}

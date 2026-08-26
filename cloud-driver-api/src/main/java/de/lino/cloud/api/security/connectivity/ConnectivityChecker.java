package de.lino.cloud.api.security.connectivity;

/**
 * Reports whether outbound network connectivity is currently available,
 * independently of any particular database driver.
 */
public interface ConnectivityChecker {

    /**
     * Checks whether outbound network connectivity is currently available.
     *
     * @return {@code true} if a connection is available, {@code false} otherwise
     */
    boolean isAvailable();

}

package de.lino.cloud.api.utility;

import java.nio.file.Path;

/**
 * Shared filesystem constants for {@code cloud-driver}, so consumers resolve
 * local config files (e.g. database credentials) against one well-known
 * location instead of each hardcoding its own path.
 */
public final class Constraints {

    /**
     * Not instantiable; all functionality is exposed through static fields.
     */
    private Constraints() {}

    /**
     * The directory local configuration files are resolved against: a {@code
     * cloud-driver} subdirectory of the JVM's working directory ({@code
     * user.dir}). Callers resolve specific files against it, e.g. {@code
     * Constraints.CONFIGURATION_PATH.resolve("database.json")}.
     */
    public static final Path CONFIGURATION_PATH = Path.of(System.getProperty("user.dir"), "cloud-driver");

}

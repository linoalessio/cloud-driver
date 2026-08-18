package de.lino.cloud.api.application.info;

import com.google.gson.reflect.TypeToken;
import de.lino.cloud.api.application.Application;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Reads an {@link Application} extension's {@code application.json} classpath
 * resource - shipped in that extension's own {@code resources} folder - into
 * an {@link ApplicationProperties} instance, so an extension never has to
 * assemble its own properties in Java code.
 *
 * <p>Expected shape:
 * <pre>{@code
 * {
 *   "name": "my-application",
 *   "version": "1.0.0",
 *   "authors": ["Jane Doe", "John Smith"],
 *   "dependencies": ["some-other-application"]
 * }
 * }</pre>
 *
 * <p>{@code authors} and {@code dependencies} are optional and default to an
 * empty list; {@code name} and {@code version} are required. {@code
 * dependencies} names other applications' {@link ApplicationProperties#getApplicationName()
 * application name} - {@link de.lino.cloud.api.factory.ApplicationFactory}
 * checks that each one is registered and already running before starting an
 * application that declares it.
 */
public final class ApplicationPropertiesLoader {

    /**
     * The classpath-resource name every {@link Application} extension is expected
     * to ship, resolved relative to the extension class's own class loader.
     */
    public static final String RESOURCE_NAME = "application.json";

    private static final String NAME_FIELD = "name";
    private static final String VERSION_FIELD = "version";
    private static final String AUTHORS_FIELD = "authors";
    private static final String DEPENDENCIES_FIELD = "dependencies";

    /**
     * Not instantiable; all functionality is exposed through static methods.
     */
    private ApplicationPropertiesLoader() {
    }

    /**
     * Loads {@link #RESOURCE_NAME} from {@code applicationClass}'s class loader
     * and parses it into an {@link ApplicationProperties}.
     *
     * @param applicationClass the extension class whose class loader {@link #RESOURCE_NAME} is resolved against
     * @return the parsed {@link ApplicationProperties}
     * @throws NullPointerException if {@code applicationClass} is {@code null}
     * @throws IllegalStateException if {@link #RESOURCE_NAME} is missing, unreadable, or missing a required field
     */
    @NotNull
    public static ApplicationProperties load(@NonNull final Class<? extends Application> applicationClass) {
        try (InputStream resource = applicationClass.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (resource == null) {
                throw new IllegalStateException(
                        "@ApplicationPropertiesLoader.load: no '" + RESOURCE_NAME + "' resource found on "
                                + applicationClass.getName() + "'s classpath - every Application extension must "
                                + "ship one in its resources folder"
                );
            }
            return fromDocument(applicationClass, new JsonDocument(resource));
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "@ApplicationPropertiesLoader.load: failed to read '" + RESOURCE_NAME + "' for " + applicationClass.getName(), e
            );
        }
    }

    @NotNull
    private static ApplicationProperties fromDocument(final Class<? extends Application> applicationClass, final JsonDocument document) {
        final String name = requireField(applicationClass, document, NAME_FIELD);
        final String version = requireField(applicationClass, document, VERSION_FIELD);

        final List<String> authors = document.get(AUTHORS_FIELD, new TypeToken<>() {});
        final List<String> dependencies = document.get(DEPENDENCIES_FIELD, new TypeToken<>() {});

        return new ApplicationProperties(
                name, version,
                authors == null ? new String[0] : authors.toArray(new String[0]),
                dependencies == null ? new String[0] : dependencies.toArray(new String[0])
        );
    }

    private static String requireField(final Class<? extends Application> applicationClass, final JsonDocument document, final String field) {
        final String value = document.getString(field);
        if (value == null) {
            throw new IllegalStateException(
                    "@ApplicationPropertiesLoader.load: '" + RESOURCE_NAME + "' for " + applicationClass.getName()
                            + " is missing required field '" + field + "'"
            );
        }
        return value;
    }
}

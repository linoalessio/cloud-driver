package de.lino.cloud.api.extension.info;

import com.google.gson.reflect.TypeToken;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Reads an {@link Extension}'s {@code extension.json} classpath resource into
 * an {@link ExtensionProperties} instance.
 *
 * <p>Expected shape:
 * <pre>{@code
 * {
 *   "name": "my-extension",
 *   "version": "1.0.0",
 *   "description": "What this extension does",
 *   "authors": ["Jane Doe", "John Smith"],
 *   "dependencies": ["some-other-extension"]
 * }
 * }</pre>
 *
 * <p>{@code name}, {@code version}, and {@code description} are required
 * (though {@code description} may be blank); {@code authors} and
 * {@code dependencies} are optional and default to an empty list.
 * {@code dependencies} names other extensions {@link ExtensionFactory} checks
 * are registered and running before this one starts.
 */
public final class ExtensionPropertiesLoader {

    /**
     * The classpath-resource name every {@link Extension} extension is expected
     * to ship, resolved relative to the extension class's own class loader.
     */
    public static final String RESOURCE_NAME = "extension.json";

    /** JSON field name for the extension's required {@code name}. */
    private static final String NAME_FIELD = "name";

    /** JSON field name for the extension's required {@code version}. */
    private static final String VERSION_FIELD = "version";

    /** JSON field name for the extension's optional {@code authors} list; defaults to an empty list if absent. */
    private static final String AUTHORS_FIELD = "authors";

    /** JSON field name for the extension's optional {@code dependencies} list; defaults to an empty list if absent. */
    private static final String DEPENDENCIES_FIELD = "dependencies";

    /** JSON field name for the extension's required {@code description} (may be blank, but must be present). */
    private static final String DESCRIPTION_FIELD = "description";

    /**
     * Not instantiable; all functionality is exposed through static methods.
     */
    private ExtensionPropertiesLoader() {
    }

    /**
     * Loads {@link #RESOURCE_NAME} from {@code extensionClass}'s class loader
     * and parses it into an {@link ExtensionProperties}.
     *
     * @param extensionClass the extension class whose class loader {@link #RESOURCE_NAME} is resolved against
     * @return the parsed {@link ExtensionProperties}
     * @throws NullPointerException if {@code extensionClass} is {@code null}
     * @throws IllegalStateException if {@link #RESOURCE_NAME} is missing, unreadable, or missing a required field
     */
    @NotNull
    public static ExtensionProperties load(@NonNull final Class<? extends Extension> extensionClass) {
        try (InputStream resource = extensionClass.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (resource == null) {
                throw new IllegalStateException(
                        "@ExtensionPropertiesLoader.load: no '" + RESOURCE_NAME + "' resource found on "
                                + extensionClass.getName() + "'s classpath - every Extension extension must "
                                + "ship one in its resources folder"
                );
            }
            return fromDocument(extensionClass, new JsonDocument(resource));
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "@ExtensionPropertiesLoader.load: failed to read '" + RESOURCE_NAME + "' for " + extensionClass.getName(), e
            );
        }
    }

    /**
     * Parses an already-loaded {@code extension.json} document into an {@link ExtensionProperties},
     * defaulting {@code authors}/{@code dependencies} to an empty array when absent from the document.
     *
     * @param extensionClass the extension class the document was loaded for, used only in error messages
     * @param document the parsed {@code extension.json} document
     * @return the resulting {@link ExtensionProperties}, with an initial status of {@link ExtensionStatus#LOADING}
     * @throws IllegalStateException if {@code name}, {@code version}, or {@code description} is missing from {@code document}
     */
    @NotNull
    private static ExtensionProperties fromDocument(final Class<? extends Extension> extensionClass, final JsonDocument document) {
        final String name = requireField(extensionClass, document, NAME_FIELD);
        final String version = requireField(extensionClass, document, VERSION_FIELD);
        final String description = requireField(extensionClass, document, DESCRIPTION_FIELD);

        final List<String> authors = document.get(AUTHORS_FIELD, new TypeToken<>() {});
        final List<String> dependencies = document.get(DEPENDENCIES_FIELD, new TypeToken<>() {});

        return new ExtensionProperties(
                name, version,
                description.isBlank() ? "" : description,
                authors == null ? new String[0] : authors.toArray(new String[0]),
                dependencies == null ? new String[0] : dependencies.toArray(new String[0]));
    }

    /**
     * Reads {@code field} from {@code document} as a string, requiring it to be present.
     *
     * @param extensionClass the extension class the document was loaded for, used only in the error message
     * @param document the parsed {@code extension.json} document
     * @param field the JSON field name to read
     * @return the field's string value
     * @throws IllegalStateException if {@code field} is missing (i.e. {@code null}) in {@code document}
     */
    private static String requireField(final Class<? extends Extension> extensionClass, final JsonDocument document, final String field) {
        final String value = document.getString(field);
        if (value == null) {
            throw new IllegalStateException(
                    "@ExtensionPropertiesLoader.load: '" + RESOURCE_NAME + "' for " + extensionClass.getName()
                            + " is missing required field '" + field + "'"
            );
        }
        return value;
    }
}

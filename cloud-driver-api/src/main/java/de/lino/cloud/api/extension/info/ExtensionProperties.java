package de.lino.cloud.api.extension.info;

import de.lino.cloud.api.extension.Extension;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Arrays;
import java.util.List;
import de.lino.cloud.api.utility.Asserts;

/**
 * An {@link Extension}'s name, version, authors,
 * and current {@link ExtensionStatus}.
 */
@Getter
@ToString @EqualsAndHashCode
public class ExtensionProperties {

    /**
     * The extension's name.
     */
    private final String extensionName;

    /**
     * The extension's version.
     */
    private final String extensionVersion;

    /**
     * The extension's description
     */
    private final String description;

    /**
     * The extension's authors.
     */
    private final List<String> authors;

    /**
     * Before loading the module, that system checks IF the dependencies are loaded (Other Extensions)
     */
    private final List<String> dependencies;

    /**
     * The extension's current status, {@code volatile} so an update from
     * {@link #updateExtensionStatus(ExtensionStatus)} is immediately visible to
     * any thread calling {@link #getExtensionStatus()}.
     */
    private volatile ExtensionStatus extensionStatus;

    /**
     * Constructs the extension's properties, with an initial status of
     * {@link ExtensionStatus#LOADING}.
     *
     * @param extensionName    the extension's name
     * @param extensionVersion the extension's version
     * @param description
     * @param authors          the extension's authors
     * @throws NullPointerException if any argument, or any element of {@code authors}, is {@code null}
     */
    public ExtensionProperties(final String extensionName, final String extensionVersion, String description, final String[] authors, final String... dependencies) {

        this.extensionName = Asserts.requireNonNull(extensionName, "@ExtensionProperties.init: ExtensionName must not be null");
        this.extensionVersion = Asserts.requireNonNull(extensionVersion, "@ExtensionProperties.init: ExtensionVersion must not be null");
        this.description = Asserts.requireNonNull(description, "@ExtensionProperties.init: Description must not be null");

        Asserts.requireNonNull(authors, "@ExtensionProperties.init: Authors must not be null");
        Arrays.stream(authors).forEach(author -> Asserts.requireNonNull(author, "@ExtensionProperties.init: Author must not be null"));
        this.authors = List.of(authors);
        this.dependencies = List.of(dependencies);

        this.extensionStatus = ExtensionStatus.LOADING;

    }

    /**
     * Updates the extension's status.
     *
     * @param extensionStatus the status to update to
     * @throws NullPointerException if {@code extensionStatus} is {@code null}
     */
    public void updateExtensionStatus(final ExtensionStatus extensionStatus) {
        this.extensionStatus = Asserts.requireNonNull(extensionStatus, "@ExtensionProperties.updateExtensionStatus: ExtensionStatus must not be null");
    }

}

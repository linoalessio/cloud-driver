package de.lino.cloud.api.application.info;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An {@link de.lino.cloud.api.application.Application}'s name, version, authors,
 * and current {@link ApplicationStatus}.
 */
@Getter
@ToString @EqualsAndHashCode
public class ApplicationProperties {

    /**
     * The application's name.
     */
    private final String applicationName;

    /**
     * The application's version.
     */
    private final String applicationVersion;

    /**
     * The application's authors.
     */
    private final List<String> authors;

    /**
     * Before loading the module, that system checks IF the dependencies are loaded (Other Applications)
     */
    private final List<String> dependencies;

    /**
     * The application's current status, {@code volatile} so an update from
     * {@link #updateApplicationStatus(ApplicationStatus)} is immediately visible to
     * any thread calling {@link #getApplicationStatus()}.
     */
    private volatile ApplicationStatus applicationStatus;

    /**
     * Constructs the application's properties, with an initial status of
     * {@link ApplicationStatus#LOADING}.
     *
     * @param applicationName the application's name
     * @param applicationVersion the application's version
     * @param authors the application's authors
     * @throws NullPointerException if any argument, or any element of {@code authors}, is {@code null}
     */
    public ApplicationProperties(final String applicationName, final String applicationVersion, final String[] authors, final String... dependencies) {

        this.applicationName = Objects.requireNonNull(applicationName, "@ApplicationProperties.init: ApplicationName cannot be null");
        this.applicationVersion = Objects.requireNonNull(applicationVersion, "@ApplicationProperties.init: ApplicationVersion cannot be null");

        Objects.requireNonNull(authors, "@ApplicationProperties.init: Authors cannot be null");
        Arrays.stream(authors).forEach(author -> Objects.requireNonNull(author, "@ApplicationProperties.init: Author cannot be null"));
        this.authors = List.of(authors);
        this.dependencies = List.of(dependencies);

        this.applicationStatus = ApplicationStatus.LOADING;

    }

    /**
     * Updates the application's status.
     *
     * @param applicationStatus the status to update to
     * @throws NullPointerException if {@code applicationStatus} is {@code null}
     */
    public void updateApplicationStatus(final ApplicationStatus applicationStatus) {
        this.applicationStatus = Objects.requireNonNull(applicationStatus, "@ApplicationProperties.updateApplicationStatus: ApplicationStatus cannot be null");
    }

}

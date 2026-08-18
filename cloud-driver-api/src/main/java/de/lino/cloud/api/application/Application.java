package de.lino.cloud.api.application;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.application.detection.ProjectBuildDetection;
import de.lino.cloud.api.application.detection.ProjectType;
import de.lino.cloud.api.application.info.ApplicationProperties;
import de.lino.cloud.api.application.info.ApplicationPropertiesLoader;
import de.lino.cloud.api.factory.ApplicationFactory;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;

/**
 * Base class for an application-extension: subclass it, implement the lifecycle
 * hooks below, and ship an {@code application.json} in the subclass's own {@code
 * resources} folder (see {@link ApplicationPropertiesLoader} for its shape).
 *
 * <p>A subclass registers itself with {@link CloudAPI#getInstance()}'s {@link
 * ApplicationFactory} - the API responsible for storing, looking up, and
 * starting every application - the moment it is constructed; there is
 * nothing else to wire up. {@link #getApplicationProperties()} and {@link
 * #getProjectBuildType()} are resolved once at that point, from {@code
 * application.json} and the working directory's build descriptor
 * respectively, so a subclass never assembles either itself.
 *
 * <p>Because registration goes through {@link CloudAPI#getInstance()}, a
 * concrete {@link CloudAPI} implementation must already be installed (e.g.
 * via {@code DefaultCloudAPI.setInstance}) before any {@link Application}
 * subclass is constructed.
 */
@Getter @Setter @ToString
public abstract class Application {

    /**
     * This application's properties, loaded once from {@code application.json}
     * at construction time via {@link ApplicationPropertiesLoader}.
     */
    private final ApplicationProperties applicationProperties;

    /**
     * The build tool managing the current project, detected once at construction
     * time via {@link ProjectBuildDetection#detectProjectBuildType()}.
     */
    private final ProjectType projectBuildType;

    private String prefix = "";

    /**
     * Loads this application's {@link ApplicationProperties} and {@link
     * ProjectType}, then registers {@code this} with {@link
     * CloudAPI#getInstance()}'s {@link ApplicationFactory}.
     *
     * @throws IllegalStateException if this class has no {@code application.json} resource, or it is malformed
     * @throws NullPointerException if no {@link CloudAPI} implementation has been installed yet
     */
    protected Application() {
        this.applicationProperties = ApplicationPropertiesLoader.load(getClass());
        this.projectBuildType = ProjectBuildDetection.detectProjectBuildType();

        final CloudAPI cloudAPI = CloudAPI.getInstance();
        if (cloudAPI == null) {
            throw new NullPointerException(
                    "@Application.init: no CloudAPI implementation is installed yet - install one "
                            + "(e.g. via DefaultCloudAPI.setInstance) before constructing an Application"
            );
        }
        cloudAPI.getApplicationFactory().register(this);
    }

    /**
     * Called once, before {@link #onRunning(String[])}, to prepare and load whatever
     * the application needs before it can run, such as a database connection.
     */
    public abstract void onLoading();

    /**
     * Called once the application is shutting down, to release whatever
     * {@link #onLoading()} acquired.
     */
    public abstract void onEnding();

    /**
     * Called once loading has finished, with the arguments passed to the JVM's
     * {@code main(String[])} entry point.
     *
     * @param args the arguments passed from the command line
     */
    public abstract void onRunning(final String[] args);

    /**
     * Called when a {@link RuntimeException} occurs while loading or running the
     * application, so it can be reported or recovered from.
     *
     * @param reason the exception that occurred
     */
    public abstract void onException(final RuntimeException reason);

    /**
     * Returns this application's properties, as loaded from its {@code application.json}.
     *
     * @return this application's {@link ApplicationProperties}
     */
    public final ApplicationProperties getApplicationProperties() {
        return applicationProperties;
    }

    /**
     * Returns the build tool managing the current project.
     *
     * @return {@code ProjectType.JAVA_PLUGIN}, {@code ProjectType.MAVEN_PLUGIN}, or {@code ProjectType.GRADLE_PLUGIN}
     */
    public final ProjectType getProjectBuildType() {
        return projectBuildType;
    }

    /**
     * Sets the CLI prefix.
     *
     * @param prefix the new prefix
     */
    public void setPrefix(@NonNull final String prefix) {
        this.prefix = prefix;
    }

}

package de.lino.cloud.api.extension;

import de.lino.cloud.api.extension.detection.ProjectBuildDetection;
import de.lino.cloud.api.extension.detection.ProjectType;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.cloud.api.extension.info.ExtensionPropertiesLoader;
import de.lino.cloud.api.factory.ExtensionFactory;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;

/**
 * Base class for an extension: subclass it, implement the lifecycle
 * hooks below, and ship an {@code extension.json} in the subclass's own {@code
 * resources} folder (see {@link ExtensionPropertiesLoader} for its shape).
 *
 * <p>A subclass does not register itself. After constructing one, register
 * it explicitly with {@link ExtensionFactory#register(Extension)} (e.g.
 * {@code CloudAPI.getInstance().getExtensionFactory().register(new
 * DemoExtension())}) - there is no automatic registration.
 *
 * <p>{@link #getExtensionProperties()} and {@link #getProjectBuildType()} are
 * resolved once, at construction time, from {@code extension.json} and the
 * working directory's build descriptor respectively, so a subclass never
 * assembles either itself.
 */
@Getter @Setter @ToString
public abstract class Extension {

    /**
     * This extension's properties, loaded once from {@code extension.json}
     * at construction time via {@link ExtensionPropertiesLoader}.
     */
    private final ExtensionProperties extensionProperties;

    /**
     * The build tool managing the current project, detected once at construction
     * time via {@link ProjectBuildDetection#detectProjectBuildType()}.
     */
    private final ProjectType projectBuildType;

    /**
     * The CLI prefix this extension's own commands are namespaced under, if any.
     * Empty by default; set explicitly via {@link #setPrefix(String)}.
     */
    private String prefix = "";

    /**
     * Loads this extension's {@link ExtensionProperties} and {@link
     * ProjectType}. Does not register {@code this} - call {@link
     * ExtensionFactory#register(Extension)} explicitly once construction is
     * complete.
     *
     * @throws IllegalStateException if this class has no {@code extension.json} resource, or it is malformed
     */
    protected Extension() {
        this.extensionProperties = ExtensionPropertiesLoader.load(getClass());
        this.projectBuildType = ProjectBuildDetection.detectProjectBuildType();
    }

    /**
     * Called once, before {@link #onRunning(String[])}, to prepare and load whatever
     * the extension needs before it can run, such as a database connection.
     */
    public abstract void onLoading();

    /**
     * Called once the extension is shutting down, to release whatever
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
     * extension, so it can be reported or recovered from.
     *
     * @param reason the exception that occurred
     */
    public abstract void onException(final RuntimeException reason);

    /**
     * Returns this extension's properties, as loaded from its {@code extension.json}.
     *
     * @return this extension's {@link ExtensionProperties}
     */
    public final ExtensionProperties getExtensionProperties() {
        return extensionProperties;
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

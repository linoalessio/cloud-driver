package de.lino.cloud.api.extension.detection;

import lombok.Getter;

import de.lino.cloud.api.utility.Asserts;

/**
 * The build tool managing the current project, as detected by
 * {@link ProjectBuildDetection#detectProjectBuildType()}.
 */
@Getter
public enum ProjectType {

    /**
     * No recognized build tool descriptor was found; the project is assumed to be plain Java.
     */
    JAVA_PLUGIN("Java"),

    /**
     * A {@code pom.xml} was found; the project is managed by Maven.
     */
    MAVEN_PLUGIN("Maven"),

    /**
     * A {@code build.gradle} or {@code build.gradle.kts} was found; the project is managed by Gradle.
     */
    GRADLE_PLUGIN("Gradle");

    /**
     * This project type's formatted, human-readable name.
     */
    private final String name;

    /**
     * @param name this project type's formatted, human-readable name
     * @throws NullPointerException if {@code name} is {@code null}
     */
    ProjectType(final String name) {
        this.name = Asserts.assertNotNull(name, "@ProjectType.init: name cannot be null");
    }

}

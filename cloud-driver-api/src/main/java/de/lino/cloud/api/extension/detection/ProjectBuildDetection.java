package de.lino.cloud.api.extension.detection;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects which build tool manages the project in the current working directory, by
 * checking for the presence of that tool's standard project descriptor file.
 */
public final class ProjectBuildDetection {

    /**
     * Path to the {@code pom.xml} a standard Maven project would have in its root.
     */
    private static final Path PROJECT_MAVEN_PATH = Path.of(System.getProperty("user.dir"), "pom.xml");

    /**
     * Path to the {@code build.gradle} a standard Groovy-DSL Gradle project would have in its root.
     */
    private static final Path PROJECT_GRADLE_PATH = Path.of(System.getProperty("user.dir"), "build.gradle");

    /**
     * Path to the {@code build.gradle.kts} a standard Kotlin-DSL Gradle project would have in its root.
     */
    private static final Path PROJECT_GRADLE_KTS_PATH = Path.of(System.getProperty("user.dir"), "build.gradle.kts");

    /**
     * Not instantiable; all functionality is exposed through static methods.
     */
    private ProjectBuildDetection() {
    }

    /**
     * Checks whether the current working directory is a Maven project.
     *
     * @return {@code true} if {@code pom.xml} is found, {@code false} otherwise
     */
    private static boolean isMavenDetected() {
        return Files.exists(PROJECT_MAVEN_PATH);
    }

    /**
     * Checks whether the current working directory is a Groovy-DSL Gradle project.
     *
     * @return {@code true} if {@code build.gradle} is found, {@code false} otherwise
     */
    private static boolean isGradleDetected() {
        return Files.exists(PROJECT_GRADLE_PATH);
    }

    /**
     * Checks whether the current working directory is a Kotlin-DSL Gradle project.
     *
     * @return {@code true} if {@code build.gradle.kts} is found, {@code false} otherwise
     */
    private static boolean isGradleKtsDetected() {
        return Files.exists(PROJECT_GRADLE_KTS_PATH);
    }

    /**
     * Detects which build tool manages the project in the current working directory.
     * Maven takes priority if both a {@code pom.xml} and a Gradle descriptor are present.
     *
     * @return {@code ProjectType.MAVEN_PLUGIN} or {@code ProjectType.GRADLE_PLUGIN} if a matching descriptor is found, {@code ProjectType.JAVA_PLUGIN} otherwise
     */
    public static ProjectType detectProjectBuildType() {

        if (isMavenDetected()) return ProjectType.MAVEN_PLUGIN;
        if (isGradleDetected() || isGradleKtsDetected()) return ProjectType.GRADLE_PLUGIN;

        return ProjectType.JAVA_PLUGIN;

    }

}

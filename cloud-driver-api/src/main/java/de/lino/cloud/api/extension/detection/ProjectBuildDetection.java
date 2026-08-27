package de.lino.cloud.api.extension.detection;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.jar.JarFile;

/**
 * Detects which build tool manages a given extension's project, by checking for the
 * presence of that tool's standard project descriptor file - either alongside the
 * JVM's current working directory (the case when running unpackaged, e.g. from an
 * IDE run configuration whose working directory is the module root) or embedded in
 * the extension class's own jar (the case for an {@link de.lino.cloud.api.extension.Extension}
 * loaded from a packaged jar dropped into the extensions folder, whose working
 * directory at runtime has no relation to the jar's own build - see {@code
 * ExtensionFolderScanner}/{@code ExtensionJarLoader} in {@code cloud-driver-plugin}).
 */
public final class ProjectBuildDetection {

    /** Path to the root {@code pom.xml} of a standard Maven project. */
    private static final Path PROJECT_MAVEN_PATH = Path.of(System.getProperty("user.dir"), "pom.xml");

    /** Path to the root {@code build.gradle} of a standard Groovy-DSL Gradle project. */
    private static final Path PROJECT_GRADLE_PATH = Path.of(System.getProperty("user.dir"), "build.gradle");

    /** Path to the root {@code build.gradle.kts} of a standard Kotlin-DSL Gradle project. */
    private static final Path PROJECT_GRADLE_KTS_PATH = Path.of(System.getProperty("user.dir"), "build.gradle.kts");

    /** Not instantiable; all functionality is exposed through static methods. */
    private ProjectBuildDetection() {
    }

    /** @return {@code true} if {@code pom.xml} is found next to the JVM's working directory */
    private static boolean isMavenDetected() {
        return Files.exists(PROJECT_MAVEN_PATH);
    }

    /** @return {@code true} if {@code build.gradle} is found next to the JVM's working directory */
    private static boolean isGradleDetected() {
        return Files.exists(PROJECT_GRADLE_PATH);
    }

    /** @return {@code true} if {@code build.gradle.kts} is found next to the JVM's working directory */
    private static boolean isGradleKtsDetected() {
        return Files.exists(PROJECT_GRADLE_KTS_PATH);
    }

    /**
     * Reports whether {@code extensionClass} was loaded from a jar carrying the
     * {@code META-INF/maven/<groupId>/<artifactId>/pom.xml} descriptor {@code
     * maven-jar-plugin} embeds by default - the only reliable, working-directory-independent
     * signal available once an extension is loaded from a packaged jar rather than run
     * straight from an IDE's compiled classes.
     *
     * @return {@code true} if {@code extensionClass}'s own jar embeds a Maven descriptor
     */
    private static boolean isMavenDetected(final Class<?> extensionClass) {
        final CodeSource codeSource = extensionClass.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return false;
        }

        final URL location = codeSource.getLocation();
        try {
            final Path path = Path.of(location.toURI());
            if (!Files.isRegularFile(path) || !path.toString().endsWith(".jar")) {
                return false;
            }

            try (JarFile jarFile = new JarFile(path.toFile())) {
                return jarFile.stream().anyMatch(entry ->
                        entry.getName().startsWith("META-INF/maven/") && entry.getName().endsWith("pom.xml"));
            }
        } catch (final URISyntaxException | IOException e) {
            return false;
        }
    }

    /**
     * Detects which build tool manages {@code extensionClass}'s project: first checking
     * whether that class's own jar embeds a Maven descriptor (the case for a packaged
     * extension jar, whose runtime working directory has no relation to its own build),
     * then falling back to the JVM's working directory (the case for an extension run
     * unpackaged, e.g. directly from an IDE). Maven takes priority if both a Maven and a
     * Gradle descriptor are found.
     *
     * @param extensionClass the extension class whose build tool is being detected
     * @return {@code ProjectType.MAVEN_PLUGIN} or {@code ProjectType.GRADLE_PLUGIN} if a matching descriptor is found, {@code ProjectType.JAVA_PLUGIN} otherwise
     */
    public static ProjectType detectProjectBuildType(final Class<?> extensionClass) {

        if (isMavenDetected(extensionClass) || isMavenDetected()) return ProjectType.MAVEN_PLUGIN;
        if (isGradleDetected() || isGradleKtsDetected()) return ProjectType.GRADLE_PLUGIN;

        return ProjectType.JAVA_PLUGIN;

    }

}

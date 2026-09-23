package de.lino.cloud.plugin.extension;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans one folder's top-level {@code *.jar} files (non-recursive) and loads
 * every {@link Extension} out of each via {@link ExtensionJarLoader}. A
 * missing folder yields an empty list rather than throwing.
 *
 * <p>Jars are visited in file-name order, not in whatever order the filesystem happens to hand
 * them back, so the load order - and any report about two jars claiming the same thing - is the
 * same on every host.
 */
public final class ExtensionFolderScanner {

    /** The file-name suffix a top-level entry must have to be scanned as a jar. */
    private static final String JAR_SUFFIX = ".jar";

    /** Not instantiable - every member is static. */
    private ExtensionFolderScanner() {}

    /**
     * The same scan as {@link #scan(Path)}, keeping which jar each extension came from - so a
     * caller that has to report two jars claiming the same extension can name both files.
     *
     * @param folder the folder to scan for {@code *.jar} files - see {@code Constraints#EXTENSIONS_PATH} for the default
     * @return each jar directly inside {@code folder}, in file-name order, mapped to the
     *     extensions it declares - a jar declaring none maps to an empty list
     * @throws NullPointerException if {@code folder} is {@code null}
     * @throws UncheckedIOException if {@code folder} exists but cannot be listed
     */
    @NotNull
    public static Map<Path, List<Extension>> scanByJar(@NotNull final Path folder) {
        Asserts.requireNonNull(folder, "@ExtensionFolderScanner.scanByJar: folder cannot be null");

        if (!Files.isDirectory(folder)) return Map.of();

        final Map<Path, List<Extension>> extensionsByJar = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(folder)) {

            entries
                    .filter(path -> path.getFileName().toString().endsWith(JAR_SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(jarPath -> extensionsByJar.put(jarPath, ExtensionJarLoader.load(jarPath)));

        } catch (final IOException e) {
            throw new UncheckedIOException("@ExtensionFolderScanner.scanByJar: failed to list '" + folder + "'", e);
        }

        return extensionsByJar;
    }

    /**
     * @param folder the folder to scan for {@code *.jar} files - see {@code Constraints#EXTENSIONS_PATH} for the default
     * @return every {@link Extension} successfully loaded from every jar directly inside {@code folder}, in file-name order of the jar that declares it
     * @throws NullPointerException if {@code folder} is {@code null}
     * @throws UncheckedIOException if {@code folder} exists but cannot be listed
     * @see #scanByJar(Path)
     */
    @NotNull
    public static List<Extension> scan(@NotNull final Path folder) {
        return scanByJar(folder).values().stream().flatMap(List::stream).toList();
    }

}

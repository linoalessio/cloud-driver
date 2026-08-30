package de.lino.cloud.plugin.extension;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans one folder's top-level {@code *.jar} files (non-recursive) and loads
 * every {@link Extension} out of each via {@link ExtensionJarLoader}. A
 * missing folder yields an empty list rather than throwing.
 */
public final class ExtensionFolderScanner {

    /** The file-name suffix a top-level entry must have to be scanned as a jar. */
    private static final String JAR_SUFFIX = ".jar";

    /** Not instantiable - every member is static. */
    private ExtensionFolderScanner() {}

    /**
     * @param folder the folder to scan for {@code *.jar} files - see {@code Constraints#EXTENSIONS_PATH} for the default
     * @return every {@link Extension} successfully loaded from every jar directly inside {@code folder}, in no particular order
     * @throws NullPointerException if {@code folder} is {@code null}
     * @throws UncheckedIOException if {@code folder} exists but cannot be listed
     */
    @NotNull
    public static List<Extension> scan(@NotNull final Path folder) {
        Asserts.requireNonNull(folder, "@ExtensionFolderScanner.scan: folder cannot be null");

        if (!Files.isDirectory(folder)) return List.of();

        final List<Extension> extensions = new ArrayList<>();
        try (Stream<Path> entries = Files.list(folder)) {

            entries
                    .filter(path -> path.getFileName().toString().endsWith(JAR_SUFFIX))
                    .forEach(jarPath -> extensions.addAll(ExtensionJarLoader.load(jarPath)));

        } catch (final IOException e) {
            throw new UncheckedIOException("@ExtensionFolderScanner.scan: failed to list '" + folder + "'", e);
        }

        return extensions;
    }

}

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
 * every {@link Extension} out of each via {@link ExtensionJarLoader}, once,
 * at call time - there is no folder-watching/hot-reload here; re-scan by
 * calling {@link #scan(Path)} again. A missing folder yields an empty list
 * rather than throwing, since "no extensions folder yet" is a normal, not
 * exceptional, state for a fresh deployment.
 */
public final class ExtensionFolderScanner {

    private static final String JAR_SUFFIX = ".jar";

    private ExtensionFolderScanner() {}

    /**
     * @param folder the folder to scan for {@code *.jar} files - see {@code Constraints#EXTENSIONS_PATH} for the default
     * @return every {@link Extension} successfully loaded from every jar directly inside {@code folder}, in no particular order
     * @throws NullPointerException if {@code folder} is {@code null}
     * @throws UncheckedIOException if {@code folder} exists but cannot be listed
     */
    @NotNull
    public static List<Extension> scan(@NotNull final Path folder) {
        Asserts.assertNotNull(folder, "@ExtensionFolderScanner.scan: folder cannot be null");

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

package de.lino.cloud.plugin.extension;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads every concrete {@link Extension} subclass out of one jar file, each
 * jar given its own {@link URLClassLoader} (parent-first class loading, so
 * shared types like {@link Extension} resolve to the same {@code Class}
 * across the boundary; child-first resource loading, so a same-named
 * resource bundled in the host process - e.g. {@code extension.json} -
 * doesn't shadow the jar's own). A class that cannot be loaded, isn't a
 * concrete {@link Extension} subclass, or fails to construct (most commonly:
 * missing {@code extension.json}) is skipped rather than failing the jar.
 */
public final class ExtensionJarLoader {

    private static final String CLASS_SUFFIX = ".class";
    private static final String MODULE_INFO = "module-info.class";

    private ExtensionJarLoader() {}

    /**
     * @param jarPath the jar file to scan
     * @return every successfully constructed {@link Extension} found in {@code jarPath}, in no particular order
     * @throws NullPointerException if {@code jarPath} is {@code null}
     * @throws UncheckedIOException if {@code jarPath} cannot be opened as a jar file at all
     */
    @NotNull
    public static List<Extension> load(@NotNull final Path jarPath) {
        Asserts.requireNonNull(jarPath, "@ExtensionJarLoader.load: jarPath cannot be null");

        final URLClassLoader classLoader = newClassLoader(jarPath);
        final List<Extension> extensions = new ArrayList<>();

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            final Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(CLASS_SUFFIX) || entry.getName().equals(MODULE_INFO)) continue;

                final String className = entry.getName().substring(0, entry.getName().length() - CLASS_SUFFIX.length()).replace('/', '.');
                extensionClassNamed(classLoader, className).flatMap(ExtensionJarLoader::instantiate).ifPresent(extensions::add);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("@ExtensionJarLoader.load: failed to read jar '" + jarPath + "'", e);
        }

        return extensions;
    }

    @NotNull
    private static URLClassLoader newClassLoader(@NotNull final Path jarPath) {
        try {
            final URL jarUrl = jarPath.toUri().toURL();
            return new URLClassLoader(new URL[] {jarUrl}, Extension.class.getClassLoader()) {
                // Resource loading is child-first: check this jar's own URL before
                // falling back to the parent, so a same-named host resource (e.g.
                // extension.json) never shadows this jar's own.
                @Override
                public URL getResource(final String name) {
                    final URL own = findResource(name);
                    return own != null ? own : super.getResource(name);
                }
            };
        } catch (final MalformedURLException e) {
            throw new IllegalArgumentException("@ExtensionJarLoader.newClassLoader: '" + jarPath + "' is not a valid jar path", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<Class<? extends Extension>> extensionClassNamed(final URLClassLoader classLoader, final String className) {
        try {
            final Class<?> loaded = Class.forName(className, false, classLoader);
            if (loaded != Extension.class && Extension.class.isAssignableFrom(loaded) && !Modifier.isAbstract(loaded.getModifiers())) {
                return Optional.of((Class<? extends Extension>) loaded);
            }
            return Optional.empty();
        } catch (final ClassNotFoundException | LinkageError unresolvable) {
            // A class this jar declares but cannot actually resolve (e.g. a missing
            // dependency) is not usable as an Extension either way - skip it.
            return Optional.empty();
        }
    }

    @NotNull
    private static Optional<Extension> instantiate(@NotNull final Class<? extends Extension> extensionClass) {
        try {
            final Constructor<? extends Extension> constructor = extensionClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return Optional.of(constructor.newInstance());
        } catch (final ReflectiveOperationException | RuntimeException failure) {
            return Optional.empty();
        }
    }

}

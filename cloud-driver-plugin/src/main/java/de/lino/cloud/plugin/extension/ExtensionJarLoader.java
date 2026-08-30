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

    /** The file-name suffix a jar entry must have to be considered a compiled class. */
    private static final String CLASS_SUFFIX = ".class";
    /** The single class-file entry name skipped even though it ends with {@link #CLASS_SUFFIX} - a module descriptor, never an {@link Extension}. */
    private static final String MODULE_INFO = "module-info.class";

    /** Not instantiable - every member is static. */
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

    /**
     * Builds a dedicated {@link URLClassLoader} for one jar: class loading stays
     * parent-first (delegating to {@link Extension}'s own class loader), but
     * resource loading is overridden to be child-first, checking this jar's own
     * URL via {@link URLClassLoader#findResource(String)} before falling back to
     * the parent, so a same-named resource already on the host classpath (e.g.
     * another jar's {@code extension.json}) can never shadow this jar's own.
     *
     * @param jarPath the jar file to build a class loader for
     * @return a new class loader scoped to {@code jarPath}, parented at {@link Extension}'s own class loader
     * @throws IllegalArgumentException if {@code jarPath} cannot be converted to a valid URL
     */
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

    /**
     * Resolves {@code className} through {@code classLoader} (without running its
     * static initializer) and reports it back only if it is a concrete (non-abstract)
     * {@link Extension} subclass other than {@link Extension} itself.
     *
     * @param classLoader the class loader to resolve {@code className} through
     * @param className the fully-qualified class name to resolve
     * @return the resolved class, if it is a usable concrete {@link Extension} subclass; {@link Optional#empty()} otherwise, including if it cannot be resolved at all
     */
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

    /**
     * Reflectively constructs {@code extensionClass} via its declared no-arg
     * constructor, forcing it accessible first. {@link Extension}'s own
     * constructor throws if the class has no bundled {@code extension.json},
     * which surfaces here as a {@link ReflectiveOperationException} and is
     * treated as "skip this class" rather than propagated.
     *
     * @param extensionClass the concrete {@link Extension} subclass to construct
     * @return the constructed instance, or {@link Optional#empty()} if construction failed for any reason
     */
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

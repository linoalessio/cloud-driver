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
 * jar in its own {@link URLClassLoader} - isolating that jar's own bundled
 * classes/libraries from every other loaded jar's, while still sharing {@code
 * cloud-driver-api} (and everything else already on this process's own
 * classpath) via the parent classloader, so an {@code instanceof Extension}/
 * {@link de.lino.cloud.api.factory.ExtensionFactory#register} check across
 * the boundary sees the very same {@link Extension} class rather than two
 * distinct ones with identical bytecode but different runtime identity (two
 * classloaders that each independently define a class of the same name
 * produce two unrelated {@code Class} objects in the JVM - hence the shared
 * parent). Concrete infrastructure with no {@code cloud-driver-api} contract
 * of its own - real classloading/reflection I/O, not a swappable behavior,
 * the same reasoning {@code EntityDatabaseClient}/{@code
 * PendingUploadScheduler} follow.
 *
 * <p>Class loading on that classloader is left parent-first (the JDK
 * default), for the type-identity reason above, but {@link #newClassLoader}
 * overrides resource loading ({@code getResource}/{@code
 * getResourceAsStream}) to be child-first instead: the parent is the host
 * process's own classloader, which may itself ship a same-named resource
 * (e.g. the host bundles its own {@code extension.json}, as {@code
 * cloud-driver-bootstrap} does) - parent-first resource delegation would
 * silently hand that back instead of this jar's own, misattributing this
 * extension's properties to the host's.
 *
 * <p>A jar only yields an {@link Extension} instance if it both declares a
 * concrete (non-abstract) subclass of {@link Extension} <em>and</em> ships an
 * {@code extension.json} - {@link Extension}'s own constructor already
 * enforces the second half of that (via {@link
 * de.lino.cloud.api.extension.info.ExtensionPropertiesLoader}, resolved
 * against the class's own classloader - this jar's {@link URLClassLoader} -
 * so it correctly finds an {@code extension.json} bundled in this very jar,
 * not the host's, thanks to the child-first resource override above), so
 * {@link #load} only needs to check the first half itself. A class that
 * cannot be loaded, is not a concrete {@link Extension} subclass, or fails to
 * construct (most commonly: missing {@code extension.json}) is skipped
 * rather than failing the whole jar - the same "one failure does not abort
 * the rest" philosophy {@link de.lino.cloud.api.factory.ExtensionFactory#startAll}
 * applies to starting extensions.
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
        Asserts.assertNotNull(jarPath, "@ExtensionJarLoader.load: jarPath cannot be null");

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
                // Class loading stays parent-first (the default) so shared types like
                // Extension resolve to the same Class object on both sides of the
                // boundary - see this class's own Javadoc. Resource loading, however,
                // must be child-first: two independently-loaded jars can each ship a
                // same-named resource (e.g. extension.json), and the parent classloader
                // here is the running host process itself, which may ship one too - the
                // default parent-first ClassLoader#getResource would silently hand back
                // the host's own resource instead of this jar's, misattributing this
                // extension's properties to the host's. Checking findResource (this
                // jar's own URL only) before falling back to the parent avoids that.
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

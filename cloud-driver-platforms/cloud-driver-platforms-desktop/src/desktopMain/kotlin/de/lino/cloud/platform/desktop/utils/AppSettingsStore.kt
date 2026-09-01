package de.lino.cloud.platform.desktop.utils

import de.lino.cloud.platform.desktop.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Persists small local app preferences - currently just [ThemeMode] - to a local config file
 * under the user's home directory (`~/.cloud-driver-desktop/settings.properties`), so the choice
 * survives a restart. Plain `java.util.Properties` on local disk, not envelope-encrypted/synced
 * through the server at all - this is a UI preference, not account data, so it has nothing to do
 * with `cloud-driver-platforms-rest`'s `TokenStore`/session handling.
 */
object AppSettingsStore {

    private val configDir: Path = Path.of(System.getProperty("user.home"), ".cloud-driver-desktop")
    private val configFile: Path = configDir.resolve("settings.properties")
    private const val THEME_KEY = "theme"

    /**
     * Reads the persisted [ThemeMode], defaulting to [ThemeMode.LIGHT] if nothing is stored yet
     * or the file can't be read. Deliberately synchronous (not `suspend`) - called once from
     * `main()` before any Compose UI (and therefore any UI thread to block) exists yet, reading a
     * config file of a handful of bytes; there is no dispatcher to be careless about at that
     * point in startup.
     */
    fun loadThemeMode(): ThemeMode {
        if (!Files.isReadable(configFile)) return ThemeMode.LIGHT
        return try {
            val props = Properties()
            Files.newInputStream(configFile).use { props.load(it) }
            if (props.getProperty(THEME_KEY) == ThemeMode.DARK.name) ThemeMode.DARK else ThemeMode.LIGHT
        } catch (e: Exception) {
            ThemeMode.LIGHT
        }
    }

    /**
     * Persists [mode], creating `~/.cloud-driver-desktop` if needed. `suspend`, dispatched on
     * [Dispatchers.IO] - unlike [loadThemeMode], this is called from the running UI (the
     * dark/light toggle button), where blocking the Compose UI dispatcher would freeze the window.
     */
    suspend fun saveThemeMode(mode: ThemeMode) = withContext(Dispatchers.IO) {
        Files.createDirectories(configDir)
        val props = Properties()
        props.setProperty(THEME_KEY, mode.name)
        Files.newOutputStream(configFile).use { props.store(it, "cloud-driver-platforms-desktop local settings") }
    }
}

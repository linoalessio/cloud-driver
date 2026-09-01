package de.lino.cloud.platform.desktop.utils

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

private const val APP_NAME = "CloudDriver"

/**
 * Best-effort reversal of `build-app.sh`'s install step, run from the Dashboard's "Uninstall"
 * action (after the user has already confirmed via a dialog - this function itself performs no
 * confirmation of its own). Mirrors that script's own per-OS install locations exactly, since
 * nothing else in this repo records where an install actually landed:
 *  - macOS:   `/Applications/CloudDriver.app` and the `~/Desktop/CloudDriver.app` symlink.
 *  - Linux:   `/opt/CloudDriver` *and* `~/.local/share/CloudDriver` (the script only ever uses
 *             one or the other depending on `/opt`'s writability at install time, but nothing
 *             here knows which - trying both quietly is simpler and safe, since deleting a path
 *             that was never installed to is a no-op), plus both desktop-entry files.
 *  - Windows: `%LOCALAPPDATA%\Programs\CloudDriver` plus the Desktop/Start Menu `.lnk` shortcuts.
 *
 * Also deletes [AppSettingsStore]'s own local settings directory (`~/.cloud-driver-desktop`) on
 * every platform, per this action's "including the settings" contract.
 *
 * Every individual removal is isolated (its own try/catch) so one missing/permission-denied path
 * never aborts the rest - the same "one failure shouldn't block the others" convention this
 * module's own batch operations ([mapConcurrently] callers) already follow. Failures are silently
 * swallowed rather than surfaced: by the time this runs the caller is about to exit the process
 * anyway (see [de.lino.cloud.platform.desktop.viewmodel.AppViewModel.uninstall]), so there is no
 * useful place left to report a partial failure to.
 */
fun uninstallApp() {
    val home = System.getProperty("user.home")
    val osName = System.getProperty("os.name").lowercase()

    when {
        osName.contains("mac") -> {
            deleteQuietly(Paths.get("/Applications", "$APP_NAME.app"))
            deleteQuietly(Paths.get(home, "Desktop", "$APP_NAME.app"))
        }

        osName.contains("win") -> {
            System.getenv("LOCALAPPDATA")?.let { localAppData ->
                deleteWindowsInstallDirQuietly(Paths.get(localAppData, "Programs", APP_NAME))
            }
            deleteQuietly(Paths.get(home, "Desktop", "$APP_NAME.lnk"))
            System.getenv("APPDATA")?.let { appData ->
                deleteQuietly(Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs", "$APP_NAME.lnk"))
            }
        }

        else -> {
            deleteQuietly(Paths.get("/opt", APP_NAME))
            deleteQuietly(Paths.get(home, ".local", "share", APP_NAME))
            deleteQuietly(Paths.get(home, ".local", "share", "applications", "clouddriver.desktop"))
            deleteQuietly(Paths.get(home, "Desktop", "clouddriver.desktop"))
        }
    }

    deleteQuietly(Paths.get(home, ".cloud-driver-desktop"))
}

/**
 * Deletes [path], recursively if it's a real directory (not a symlink - `~/Desktop/CloudDriver.app`
 * on macOS is one, and must be unlinked itself rather than walked into). Any failure (missing
 * path, permission denied, a locked file) is swallowed - see [uninstallApp]'s own Javadoc for why.
 */
private fun deleteQuietly(path: Path) {
    try {
        deleteRecursively(path)
    } catch (e: Exception) {
        // best-effort only
    }
}

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) {
        Files.deleteIfExists(path)
        return
    }
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

/**
 * Windows can't delete a running process's own `.exe` - it stays locked for as long as this JVM
 * (launched from inside [installDir]) is alive, the standard reason a self-uninstaller on Windows
 * can't just delete its own install directory in-process. If the direct delete fails, this falls
 * back to a short detached `cmd.exe` command that waits a couple of seconds (long enough for this
 * process to exit, since [de.lino.cloud.platform.desktop.viewmodel.AppViewModel.uninstall] calls
 * [kotlin.system.exitProcess] right after this returns) and then removes the directory itself -
 * the same "spawn a helper that outlives this process" pattern every Windows uninstaller uses.
 */
private fun deleteWindowsInstallDirQuietly(installDir: Path) {
    try {
        deleteRecursively(installDir)
    } catch (e: Exception) {
        try {
            ProcessBuilder(
                "cmd", "/c",
                "timeout /t 2 /nobreak >nul & rmdir /s /q \"$installDir\"",
            )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e2: Exception) {
            // best-effort only
        }
    }
}

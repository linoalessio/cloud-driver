#!/usr/bin/env bash
#
# build-app.sh - builds cloud-driver-platforms-desktop into a real native app image and installs
# it into the current OS's normal application location, with a desktop-visible shortcut/icon,
# exactly the way a regular downloaded app would end up - not just a build artifact left under
# build/.
#
# Works on macOS, Linux, and Windows - this is a POSIX shell script, not a native .bat, so on
# Windows run it from Git Bash (ships with Git for Windows and provides the bash/coreutils this
# script needs) or WSL, not double-clicked from Explorer or run under plain cmd.exe/PowerShell.
#
# Per OS, once the app image itself is built (step 2 below):
#   - macOS:   copies the .app bundle into /Applications (replacing any previous install), and
#              symlinks it onto ~/Desktop so it shows up there too.
#   - Linux:   copies the app image into /opt/CloudDriver if writable (falling back to
#              ~/.local/share/CloudDriver, no root needed, if not), writes a .desktop launcher
#              into ~/.local/share/applications (so it shows up in the system app menu) and a
#              second copy onto ~/Desktop (marked executable/trusted so it's clickable there,
#              not just visible).
#   - Windows: copies the app image into %LOCALAPPDATA%\Programs\CloudDriver (the same per-user,
#              no-admin-needed location most modern Windows apps - VS Code included - install
#              into) and creates a real .lnk shortcut on the Desktop and in the Start Menu via a
#              short PowerShell snippet (Windows has no file-copy equivalent of a "shortcut" the
#              way symlinks/.desktop files serve on macOS/Linux - an actual .lnk has to be built).
#
# Only the macOS path has been exercised end-to-end while building this script (this repo's own
# dev environment is macOS) - the Linux/Windows install steps are written against each platform's
# normal conventions but have not been run for real; if either misbehaves on your machine, the
# app image itself (see step 2's own output path) is still there to install/run by hand.
#
# Two build steps, always in this order, before any installing happens:
#   1. Maven builds and installs this app's one in-repo dependency, cloud-driver-platforms-rest,
#      into the local Maven repository (~/.m2) - the Gradle build below resolves it from there
#      (see settings.gradle.kts's mavenLocal()), since that module is Maven-built, not part of
#      any Gradle build.
#   2. Gradle (via the committed wrapper - no local Gradle install needed) packages this module
#      into a native application image via the Compose Desktop plugin's `createDistributable`
#      task.
#
# macOS-only quirk this script works around automatically: `createDistributable` internally
# ad-hoc-codesigns the produced .app bundle, which fails ("resource fork, Finder information, or
# similar detritus not allowed") if the freshly-created .app directory has picked up a
# com.apple.FinderInfo extended attribute in the moment between its creation and signing (a real,
# reproducible issue hit while building this very app - confirmed the bundle itself is otherwise
# complete when this happens, just unsigned). If the Gradle build fails and a .app is present,
# this script strips extended attributes and re-signs it directly, then re-checks the result,
# rather than treating that specific failure as fatal - in a short retry loop with a brief pause
# between attempts, since whatever re-tags the directory (Finder/Spotlight indexing, most likely)
# does so asynchronously and can otherwise re-contaminate it faster than a single immediate
# clear-then-sign attempt: an immediate retry was observed to lose that race during testing, while
# a retry a couple of seconds later succeeded reliably.

set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/../.." && pwd)"
APP_NAME="CloudDriver"

echo "==> [1/3] Building cloud-driver-platforms-rest with Maven..."
if ! command -v mvn >/dev/null 2>&1; then
    echo "error: Maven ('mvn') not found on PATH - install it first (see this repo's CLAUDE.md, 'Build' section)." >&2
    exit 1
fi
( cd "$REPO_ROOT" && mvn -q -pl cloud-driver-platforms/cloud-driver-platforms-rest -am install -DskipTests )

echo "==> [2/3] Building the native app image with Gradle..."
cd "$MODULE_DIR"

GRADLEW="./gradlew"
OS_NAME="$(uname -s)"
case "$OS_NAME" in
    MINGW*|MSYS*|CYGWIN*) GRADLEW="./gradlew.bat" ;;
esac

APP_IMAGE_DIR="build/compose/binaries/main/app"

gradle_build_ok=true
"$GRADLEW" createDistributable || gradle_build_ok=false

if [ "$gradle_build_ok" = false ]; then
    if [ "$OS_NAME" = "Darwin" ] && [ -d "$APP_IMAGE_DIR" ]; then
        APP_BUNDLE="$(find "$APP_IMAGE_DIR" -mindepth 1 -maxdepth 1 -name "*.app" | head -n 1)"
        if [ -n "$APP_BUNDLE" ]; then
            echo "==> Gradle build failed, but a .app bundle exists - retrying the macOS codesign workaround..."
            for attempt in 1 2 3 4 5; do
                xattr -cr "$APP_BUNDLE"
                if codesign -s - -vvvv --force "$APP_BUNDLE"; then
                    echo "==> Re-signed successfully (attempt $attempt); treating the build as complete."
                    gradle_build_ok=true
                    break
                fi
                sleep 2
            done
        fi
    fi
fi

if [ "$gradle_build_ok" = false ]; then
    echo "error: build failed - see the Gradle output above." >&2
    exit 1
fi

if [ ! -d "$APP_IMAGE_DIR" ]; then
    echo "error: expected output directory '$APP_IMAGE_DIR' not found - check the Gradle output above." >&2
    exit 1
fi

APP_PATH="$(find "$APP_IMAGE_DIR" -mindepth 1 -maxdepth 1 | head -n 1)"
echo "Build complete: $APP_PATH"

echo "==> [3/3] Installing..."

case "$OS_NAME" in

Darwin)
    DEST="/Applications/$APP_NAME.app"
    if [ -w /Applications ]; then
        rm -rf "$DEST"
        cp -R "$APP_PATH" "$DEST"
    else
        echo "==> /Applications isn't writable without elevated privileges - using sudo (you may be prompted for your password)."
        sudo rm -rf "$DEST"
        sudo cp -R "$APP_PATH" "$DEST"
        sudo chown -R "$(id -u):$(id -g)" "$DEST"
    fi
    echo "Installed to $DEST"

    ln -sf "$DEST" "$HOME/Desktop/$APP_NAME.app"
    echo "Desktop shortcut: $HOME/Desktop/$APP_NAME.app"

    echo "Launch it from Launchpad/Applications, or: open \"$DEST\""
    ;;

Linux)
    if [ -w /opt ]; then
        INSTALL_DIR="/opt/$APP_NAME"
        rm -rf "$INSTALL_DIR"
        cp -R "$APP_PATH" "$INSTALL_DIR"
    else
        INSTALL_DIR="$HOME/.local/share/$APP_NAME"
        echo "==> /opt isn't writable - installing to $INSTALL_DIR instead (no root needed)."
        mkdir -p "$(dirname "$INSTALL_DIR")"
        rm -rf "$INSTALL_DIR"
        cp -R "$APP_PATH" "$INSTALL_DIR"
    fi
    echo "Installed to $INSTALL_DIR"

    LAUNCHER="$INSTALL_DIR/bin/$APP_NAME"
    ICON="$INSTALL_DIR/lib/$APP_NAME.png"
    [ -f "$ICON" ] || ICON="$MODULE_DIR/icons/app_icon.png"

    DESKTOP_ENTRY="[Desktop Entry]
Type=Application
Name=$APP_NAME
Comment=cloud-driver desktop client
Exec=\"$LAUNCHER\"
Icon=$ICON
Terminal=false
Categories=Network;FileTransfer;
"

    APPLICATIONS_DIR="$HOME/.local/share/applications"
    mkdir -p "$APPLICATIONS_DIR"
    DESKTOP_FILE="$APPLICATIONS_DIR/clouddriver.desktop"
    printf '%s' "$DESKTOP_ENTRY" > "$DESKTOP_FILE"
    chmod +x "$DESKTOP_FILE"
    echo "App menu entry: $DESKTOP_FILE"

    if [ -d "$HOME/Desktop" ]; then
        DESKTOP_SHORTCUT="$HOME/Desktop/clouddriver.desktop"
        printf '%s' "$DESKTOP_ENTRY" > "$DESKTOP_SHORTCUT"
        chmod +x "$DESKTOP_SHORTCUT"
        # GNOME/Nautilus refuses to treat a desktop entry as launchable until it's marked
        # "trusted" - best-effort only, harmless (and silently skipped) if gio isn't installed
        # or the desktop environment doesn't use this mechanism (e.g. KDE/XFCE don't need it).
        command -v gio >/dev/null 2>&1 && gio set "$DESKTOP_SHORTCUT" metadata::trusted true 2>/dev/null || true
        echo "Desktop shortcut: $DESKTOP_SHORTCUT"
    fi

    echo "Launch it from your application menu/Desktop, or directly: \"$LAUNCHER\""
    ;;

MINGW*|MSYS*|CYGWIN*)
    # %LOCALAPPDATA%\Programs\<App> - the same per-user, no-admin-needed location most modern
    # Windows apps (VS Code included) install into.
    LOCALAPPDATA_UNIX="${LOCALAPPDATA:-}"
    if [ -z "$LOCALAPPDATA_UNIX" ]; then
        echo "error: \$LOCALAPPDATA is not set - are you running this from Git Bash/WSL on Windows?" >&2
        exit 1
    fi
    if command -v cygpath >/dev/null 2>&1; then
        LOCALAPPDATA_UNIX="$(cygpath -u "$LOCALAPPDATA")"
    fi
    INSTALL_DIR="$LOCALAPPDATA_UNIX/Programs/$APP_NAME"
    rm -rf "$INSTALL_DIR"
    mkdir -p "$(dirname "$INSTALL_DIR")"
    cp -R "$APP_PATH" "$INSTALL_DIR"
    echo "Installed to $INSTALL_DIR"

    EXE_PATH="$INSTALL_DIR/$APP_NAME.exe"
    EXE_PATH_WIN="$(command -v cygpath >/dev/null 2>&1 && cygpath -w "$EXE_PATH" || echo "$EXE_PATH")"

    DESKTOP_LNK_WIN="$(command -v cygpath >/dev/null 2>&1 && cygpath -w "$HOME/Desktop/$APP_NAME.lnk" || echo "$HOME/Desktop/$APP_NAME.lnk")"
    STARTMENU_DIR="${APPDATA:-}/Microsoft/Windows/Start Menu/Programs"
    STARTMENU_LNK_WIN="$STARTMENU_DIR/$APP_NAME.lnk"

    POWERSHELL_SCRIPT="
\$shell = New-Object -ComObject WScript.Shell
foreach (\$linkPath in @('$DESKTOP_LNK_WIN', '$STARTMENU_LNK_WIN')) {
    \$shortcut = \$shell.CreateShortcut(\$linkPath)
    \$shortcut.TargetPath = '$EXE_PATH_WIN'
    \$shortcut.WorkingDirectory = (Split-Path '$EXE_PATH_WIN')
    \$shortcut.Save()
}
"
    if command -v powershell.exe >/dev/null 2>&1; then
        powershell.exe -NoProfile -Command "$POWERSHELL_SCRIPT"
        echo "Desktop shortcut: $HOME/Desktop/$APP_NAME.lnk"
        echo "Start Menu shortcut created."
    else
        echo "warning: powershell.exe not found on PATH - could not create Desktop/Start Menu shortcuts automatically." >&2
        echo "Run the app directly: \"$EXE_PATH\""
    fi
    ;;

*)
    echo "warning: unrecognized OS '$OS_NAME' - skipping install; the app image is at $APP_PATH" >&2
    ;;

esac

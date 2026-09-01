#!/usr/bin/env bash
#
# build-app.sh - builds cloud-driver-platforms-desktop into a real, directly-runnable native app
# image (not just a dev-mode `./gradlew run`) for the current OS: a `.app` on macOS, a folder
# with a launcher `.exe` on Windows, a folder with a launcher binary on Linux.
#
# Works on macOS, Linux, and Windows - this is a POSIX shell script, not a native .bat, so on
# Windows run it from Git Bash (ships with Git for Windows and provides the bash/coreutils this
# script needs) or WSL, not double-clicked from Explorer or run under plain cmd.exe/PowerShell.
#
# Two steps, always in this order:
#   1. Maven builds and installs this app's one in-repo dependency, cloud-driver-platforms-rest,
#      into the local Maven repository (~/.m2) - the Gradle build below resolves it from there
#      (see settings.gradle.kts's mavenLocal()), since that module is Maven-built, not part of
#      any Gradle build.
#   2. Gradle (via the committed wrapper - no local Gradle install needed) packages this module
#      into a native application image via the Compose Desktop plugin's `createDistributable`
#      task. Use `./gradlew packageDistributionForCurrentOS` instead (not wired up here) if you
#      want an installer (.dmg/.msi/.deb) rather than a ready-to-run app image.
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

echo "==> [1/2] Building cloud-driver-platforms-rest with Maven..."
if ! command -v mvn >/dev/null 2>&1; then
    echo "error: Maven ('mvn') not found on PATH - install it first (see this repo's CLAUDE.md, 'Build' section)." >&2
    exit 1
fi
( cd "$REPO_ROOT" && mvn -q -pl cloud-driver-platforms/cloud-driver-platforms-rest -am install -DskipTests )

echo "==> [2/2] Building the native app image with Gradle..."
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
echo ""
echo "Build complete: $APP_PATH"
case "$OS_NAME" in
    Darwin) echo "Run it:  open \"$APP_PATH\"" ;;
    Linux)  echo "Run it:  \"$APP_PATH/bin/$(basename "$APP_PATH")\"" ;;
    MINGW*|MSYS*|CYGWIN*) echo "Run it:  double-click the .exe inside \"$APP_PATH\"" ;;
    *) echo "Run the launcher binary inside \"$APP_PATH\"." ;;
esac

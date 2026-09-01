import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

group = "de.lino.cloud.platforms.desktop"
version = "1.0.1"

repositories {
    google()
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvmToolchain(21)

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        val desktopMain = getByName("desktopMain")

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            // Material's full icon set (Icons.Filled.Folder/InsertDriveFile/Image/PictureAsPdf/...) -
            // compose.material3 alone only ships a handful of "core" glyphs, not enough to give
            // folders/files distinct per-type icons.
            implementation(compose.materialIconsExtended)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)

            // The HTTP client - Maven-built, resolved from the local Maven repository
            // (mavenLocal(), declared in settings.gradle.kts). `de.lino.cloud.platforms` was
            // renamed from the earlier singular "de.lino.cloud.platform" groupId - see this
            // module's README for that history.
            implementation("de.lino.cloud.platforms.rest:cloud-driver-platforms-rest:1.0.1")

            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
        }
    }
}

compose.desktop {
    application {
        mainClass = "de.lino.cloud.platform.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CloudDriver"
            packageVersion = "1.0.1"
            description = "cloud-driver desktop client"

            // The jlink-built runtime image only bundles JDK modules jdeps' static bytecode
            // analysis finds a direct reference to (see the `suggestRuntimeModules` Gradle task) -
            // it missed `java.net.http` entirely even though `ApiClient` (cloud-driver-platforms-rest)
            // directly calls `HttpClient.newBuilder()`, which crashed every packaged build (though
            // never `./gradlew run`, which uses the full JDK, not a trimmed one) with
            // `NoClassDefFoundError: java/net/http/HttpClient` the moment `ApiClient`'s constructor
            // ran - confirmed against a real built .app. `jdk.crypto.ec` isn't in that suggested
            // list at all (jdeps can't see it - TLS cipher-suite/elliptic-curve providers are
            // loaded via SPI, not a bytecode reference) but is a well-known second trap on top of
            // the first: without it, HTTPS handshakes against a real server (this app only ever
            // talks to `https://` URLs) fail with "no cipher suites in common" once the missing
            // `java.net.http` module itself is fixed. Both confirmed necessary by actually running
            // the packaged app end to end, not guessed.
            modules("java.net.http", "java.instrument", "java.sql", "jdk.unsupported", "jdk.crypto.ec")

            macOS { iconFile.set(project.file("icons/app_icon.icns")) }
            windows { iconFile.set(project.file("icons/app_icon.ico")) }
            linux { iconFile.set(project.file("icons/app_icon.png")) }
        }
    }
}

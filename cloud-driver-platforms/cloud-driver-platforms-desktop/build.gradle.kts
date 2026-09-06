import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

group = "de.lino.cloud.platforms.desktop"
version = "1.0.5"

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
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)

            // Material's icon set beyond compose.material3's own small "core" set (Icons.Filled.Folder/
            // Image/PictureAsPdf/CloudUpload/etc., plus Icons.AutoMirrored.Filled.InsertDriveFile/
            // Sort/Logout/DriveFileMove) - fixed a real bug (2026-09-06): this used to be
            // `implementation(compose.materialIconsExtended)`, the full JetBrains icon library
            // (thousands of icons across 5 style variants) shipped in every packaged build purely to
            // reach the 47 specific icons this app actually references - 36MB out of a 188MB total
            // app bundle for icons nobody ever saw, since Compose Desktop's jpackage pipeline does
            // not tree-shake dependency jars. `libs/material-icons-extended-trimmed.jar` (~160KB) is
            // a hand-built jar containing only those 47 icons' own `<Name>Kt.class` files (43 under
            // `androidx/compose/material/icons/filled/`, 4 under `.../automirrored/filled/` - the
            // `Icons.AutoMirrored.*` ones), extracted directly from the real
            // `material-icons-extended-desktop` jar (found via the Gradle cache:
            // `~/.gradle/caches/modules-2/files-2.1/org.jetbrains.compose.material/
            // material-icons-extended-desktop/<version>/.../material-icons-extended-desktop-<version>.jar`)
            // - each icon file only references the shared `androidx.compose.material.icons.Icons`/
            // `Icons.Filled`/`Icons.AutoMirrored.Filled`/`IconsKt` classes (confirmed via `javap -v`'s
            // constant-pool dump), which `compose.material3` already pulls in transitively via
            // `material-icons-core`, so nothing else needs bundling.
            //
            // **The original `META-INF/material-icons-extended.kotlin_module` facade file IS
            // required in the trimmed jar, unfiltered/whole, even though it lists thousands of
            // classes the trimmed jar doesn't actually contain.** A first attempt omitting it (on
            // the theory that each class's own embedded `@Metadata` annotation ought to be enough
            // for the compiler to resolve a plain top-level Kotlin file) failed with "Unresolved
            // reference" on every single icon, including ones already present as class files - the
            // Kotlin 2.1.0 compiler evidently needs the module-level facade listing to resolve these
            // extension-property-shaped icon accessors from a binary dependency at all, not just the
            // per-class metadata. Confirmed by reproducing the failure, then fixing it by copying the
            // untouched `.kotlin_module` file in alongside the 47 trimmed classes - the compiler does
            // not seem to validate that every class the manifest lists actually exists in the jar, it
            // only resolves what's actually referenced from this module's own source.
            //
            // To add a NEW extended-only icon in the future: find the same source jar in the Gradle
            // cache above, `unzip -o material-icons-extended-desktop-<version>.jar
            // "androidx/compose/material/icons/filled/<Name>Kt.class" -d /tmp/x` (or
            // `.../automirrored/filled/<Name>Kt.class` for an `Icons.AutoMirrored.*` icon), then
            // `cd /tmp/x && jar uf <path-to>/libs/material-icons-extended-trimmed.jar
            // androidx/compose/material/icons/filled/<Name>Kt.class` to append it to the existing
            // trimmed jar in place (the `.kotlin_module` file already inside it needs no change -
            // it already lists every icon in the real library, trimmed or not) - check
            // `Icons.Filled.<Name>` doesn't already resolve from `compose.material3`'s own core set
            // first (see this module's icon list check via `grep -rhoE
            // "Icons\.(AutoMirrored\.)?(Filled|Outlined|Rounded|TwoTone|Sharp)\.[A-Za-z0-9]+"
            // src/desktopMain/kotlin` - note the `(AutoMirrored\.)?` group, easy to miss since it
            // caused a real miss the first time this trimming was done), since 10 common icons
            // already ship there for free.
            implementation(files("libs/material-icons-extended-trimmed.jar"))

            // The HTTP client - Maven-built, resolved from the local Maven repository
            // (mavenLocal(), declared in settings.gradle.kts). `de.lino.cloud.platforms` was
            // renamed from the earlier singular "de.lino.cloud.platform" groupId - see this
            // module's README for that history.
            implementation("de.lino.cloud.platforms.rest:cloud-driver-platforms-rest:1.0.5")

            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

            // FilePreviewDialog's in-app PDF/DOCX preview - PDFBox rasterizes a PDF page to a
            // BufferedImage (converted to a Compose ImageBitmap), POI's XWPFWordExtractor pulls
            // plain text out of a .docx (formatting is not reproduced - this is a content preview,
            // not a document renderer). Neither library was previously a dependency of this module.
            implementation("org.apache.pdfbox:pdfbox:2.0.29")
            implementation("org.apache.poi:poi-ooxml:5.2.5")
        }
    }
}

compose.desktop {
    application {
        mainClass = "de.lino.cloud.platform.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CloudDriver"
            packageVersion = "1.0.5"
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

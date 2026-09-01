rootProject.name = "cloud-driver-platforms-desktop"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // cloud-driver-platforms-rest is built and installed by the root repo's own Maven
        // reactor (`mvn install`, or the root `mvn clean install`) - this Gradle build resolves
        // it from the same local Maven repository rather than duplicating its source, since it
        // is not itself a Gradle module. Run `mvn -pl cloud-driver-platforms/cloud-driver-platforms-rest -am install`
        // (or a full `mvn clean install` from the repo root) before building this module.
        mavenLocal()
    }
}

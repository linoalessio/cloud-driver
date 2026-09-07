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
        // cloud-driver-multiplatform-java (cloud-driver-multiplatform/cloud-driver-multiplatform-java,
        // formerly cloud-driver-platforms-rest, then briefly cloud-driver-maven) is built and
        // installed by the root repo's own Maven
        // reactor (`mvn install`, or the root `mvn clean install`) - this Gradle build resolves
        // it from the same local Maven repository rather than duplicating its source, since it
        // is not itself a Gradle module. Run `mvn -pl cloud-driver-multiplatform/cloud-driver-multiplatform-java -am install`
        // (or a full `mvn clean install` from the repo root) before building this module.
        mavenLocal()
    }
}

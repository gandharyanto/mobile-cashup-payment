pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // AAR vendor hasil build edc-sdk. flatDir, bukan files(), karena AGP
        // menolak dependensi berkas .aar lokal di dalam project yang sendirinya
        // membangun AAR.
        flatDir { dirs("$rootDir/aarlib") }
    }
}
rootProject.name = "mobile-cashup-payment"

include(":common-core")
include(":device-sdk-api")
include(":signing-core")
include(":device-sdk-edcsdk")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
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

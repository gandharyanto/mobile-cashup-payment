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

        // Maven privat CashUp, hanya dipasang saat cashup.sdk.source=maven.
        // URL dan kredensial dari Gradle properties atau environment, bukan dari repo.
        if (providers.gradleProperty("cashup.sdk.source").orNull == "maven") {
            val mavenUrl = providers.gradleProperty("CASHUP_MAVEN_URL").orElse(providers.environmentVariable("CASHUP_MAVEN_URL"))
            val mavenUser = providers.gradleProperty("CASHUP_MAVEN_USERNAME").orElse(providers.environmentVariable("CASHUP_MAVEN_USERNAME"))
            val mavenToken = providers.gradleProperty("CASHUP_MAVEN_TOKEN").orElse(providers.environmentVariable("CASHUP_MAVEN_TOKEN"))
            // Hanya group yang dibutuhkan module vendor device; selain itu tidak boleh dari JitPack.
            maven("https://jitpack.io") {
                content {
                    includeGroup("com.github.hannesa2")
                    includeGroup("com.github.yuriy-budiyev")
                    includeGroup("com.github.AppDevNext.Logcat")
                    includeGroup("com.github.felHR85")
                }
            }
            maven {
                name = "CashUpPrivate"
                url = uri(mavenUrl.orNull ?: "https://pkg.cashup.id/repositories/cashup/cashup-sdk")
                credentials {
                    username = mavenUser.orNull
                    password = mavenToken.orNull
                }
            }
        }
    }
}
rootProject.name = "mobile-cashup-payment"

include(":common-core")
include(":device-sdk-api")
include(":signing-core")
include(":device-sdk-edcsdk")
include(":device-sdk-mpos")
include(":device-sdk-factory")
include(":reader-session")
include(":bc-relocated")
include(":provisioning-core")
include(":cdcp-core")
include(":feature-card-payment")
include(":notification-core")
include(":qris-core")
include(":feature-qris")
include(":app")

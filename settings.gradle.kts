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
        // Group SDK CashUp hanya boleh datang dari Maven privat. Tanpa filter ini, artifact yang
        // belum ada di sana dicari ke repo publik dulu, dan siapa pun bisa mendaftarkan group itu di
        // sana (dependency confusion) lalu menyuntikkan kode ke jalur provisioning kunci.
        val cashupGroups = listOf(
            "kernel", "common-core", "provisioning-core", "cdcp-core", "device-sdk", "device-sdk-edc",
            "feature-card-payment", "notification-core", "qris-core", "feature-qris", "com.cashup.payment",
        )
        google {
            content { cashupGroups.forEach { excludeGroup(it) } }
        }
        mavenCentral {
            content { cashupGroups.forEach { excludeGroup(it) } }
        }
        // AAR vendor hasil build edc-sdk. flatDir, bukan files(), karena AGP
        // menolak dependensi berkas .aar lokal di dalam project yang sendirinya
        // membangun AAR.
        flatDir { dirs("$rootDir/aarlib") }

        // Maven privat CashUp. URL dan kredensial dari Gradle properties atau environment, bukan dari repo.
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
rootProject.name = "mobile-cashup-payment"

include(":app")

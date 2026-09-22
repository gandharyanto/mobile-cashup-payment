plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.devicesdk.mpos"
    compileSdk = (project.property("cashup.compileSdk") as String).toInt()

    defaultConfig {
        minSdk = (project.property("cashup.minSdk") as String).toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")

    testOptions { unitTests { isIncludeAndroidResources = false } }
}

dependencies {
    api(project(":device-sdk-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.10.1")
    // AAR logger milik edc-sdk (dipakai bersama device-sdk-edcsdk) membawa
    // layout yang merujuk atribut AppCompat (selectableItemBackgroundBorderless,
    // dst) -- gagal link resource release tanpa ini, sama seperti device-sdk-edcsdk.
    implementation("androidx.appcompat:appcompat:1.6.1")

    // AAR vendor dari edc-sdk (Task 1). Selalu implementation, tidak pernah
    // api: tipe com.lib.core.*/com.lib.device.* tidak boleh bocor melewati
    // module ini.
    implementation(group = "", name = "core-release_1.0.63", ext = "aar")
    implementation(group = "", name = "logger-release_1.0.2", ext = "aar")
    implementation(group = "", name = "other-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "newland-mpos-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "topwise-mpos-release-core_1.0.63", ext = "aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

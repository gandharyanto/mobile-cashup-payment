plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.devicesdk.edcsdk"
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

    // Resource Android sengaja tidak disertakan di unit test: AAR logger milik
    // edc-sdk membawa layout yang merujuk atribut AppCompat, dan itu gagal
    // di-link tanpa AppCompat. Tidak ada tes di sini yang menyentuh resource.
    testOptions { unitTests { isIncludeAndroidResources = false } }
}

dependencies {
    api(project(":device-sdk-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.10.1")

    // AAR vendor hasil build edc-sdk. Selalu implementation, tidak pernah api:
    // tipe vendor tidak boleh bocor melewati module ini.
    implementation(group = "", name = "core-release_1.0.63", ext = "aar")
    implementation(group = "", name = "logger-release_1.0.2", ext = "aar")
    implementation(group = "", name = "pax-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "sunmi-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "centerm-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "nexgo-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "topwize-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "szanfu-release-core_1.0.63", ext = "aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
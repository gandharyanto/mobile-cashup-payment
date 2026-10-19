plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.reader"
    compileSdk = (project.property("cashup.compileSdk") as String).toInt()
    defaultConfig { minSdk = (project.property("cashup.minSdk") as String).toInt() }
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
    // Satu modul untuk terminal EDC bawaan (T6D) dan reader mPOS (ANFU): fan-out ke kedua adapter vendor tetap
    // lewat device-sdk-factory, modul ini tidak melihat adapter vendor sama sekali.
    api(project(":device-sdk-api"))
    implementation(project(":device-sdk-factory"))
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

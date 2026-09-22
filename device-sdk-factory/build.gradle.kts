plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.devicesdk.factory"
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
    // Satu-satunya module yang boleh depend ke device-sdk-edcsdk DAN
    // device-sdk-mpos sekaligus (spec §3, ditegakkan Task 9).
    api(project(":device-sdk-api"))
    implementation(project(":device-sdk-edcsdk"))
    implementation(project(":device-sdk-mpos"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

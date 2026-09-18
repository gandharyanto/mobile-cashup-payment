plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.cdcp"
    compileSdk = (project.property("cashup.compileSdk") as String).toInt()
    defaultConfig { minSdk = (project.property("cashup.minSdk") as String).toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
    testOptions { unitTests { isReturnDefaultValues = true } }
}

dependencies {
    api(project(":common-core"))
    api(project(":device-sdk-api"))
    api(project(":signing-core"))
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
}

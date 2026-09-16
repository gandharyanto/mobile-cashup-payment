plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.cashup.app"
    compileSdk = (project.property("cashup.compileSdk") as String).toInt()

    defaultConfig {
        applicationId = "com.cashup.payment"
        minSdk = (project.property("cashup.minSdk") as String).toInt()
        targetSdk = (project.property("cashup.targetSdk") as String).toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { viewBinding = true }

    buildTypes {
        debug {
            // Jurnal provisioning hanya diisi di debug; lihat AppContainer.
            buildConfigField("boolean", "PROVISIONING_JOURNAL", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "PROVISIONING_JOURNAL", "false")
        }
    }
    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")

    testOptions {
        // PERINGATAN -- `isReturnDefaultValues = true` membuat setiap method
        // android.jar yang tidak di-stub mengembalikan null/0/false alih-alih
        // melempar "not mocked". Digabung dengan `android.util.Base64` di kode
        // produksi, unit test yang TIDAK beranotasi
        // @RunWith(RobolectricTestRunner::class) akan menerima null dari
        // encode/decode dan gagal di tempat yang jauh dari sebabnya -- atau
        // lebih buruk, lulus dengan nilai kosong. Test apa pun yang menyentuh
        // jalur Base64 harus memakai Robolectric.
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":provisioning-core"))
    implementation(project(":device-sdk-edcsdk"))

    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")

    implementation("androidx.camera:camera-core:1.2.3")
    implementation("androidx.camera:camera-camera2:1.2.3")
    implementation("androidx.camera:camera-lifecycle:1.2.3")
    implementation("androidx.camera:camera-view:1.2.3")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

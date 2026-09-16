plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.provisioning"
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
    api(project(":common-core"))
    api(project(":device-sdk-api"))
    // api, bukan implementation: ProvisioningHttp.create menerima DeviceSigner,
    // jadi tipe signing-core muncul di permukaan publik module ini. Sebagai
    // implementation, :app tidak bisa menyebut tipe itu dan gagal compile --
    // persis cacat yang sudah pernah ditemukan review Foundation (commit 9707c53).
    api(project(":signing-core"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation(testFixtures(project(":device-sdk-api")))
}
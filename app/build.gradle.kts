import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.cashup.app"
    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
    compileSdk = (project.property("cashup.compileSdk") as String).toInt()

    defaultConfig {
        applicationId = "com.cashup.payment"
        minSdk = (project.property("cashup.minSdk") as String).toInt()
        targetSdk = (project.property("cashup.targetSdk") as String).toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { viewBinding = true }

    // Debug dan release memakai keystore yang sama: ANDROID_ID (nomor seri HP)
    // dihitung per sertifikat signing, jadi keystore berbeda = serial berbeda.
    // Tanpa keystore.properties, build jatuh ke debug.keystore bawaan.
    val keystoreFile = rootProject.file("keystore.properties")
    val cashupSigning = if (keystoreFile.exists()) {
        val props = Properties()
        keystoreFile.inputStream().use { props.load(it) }
        signingConfigs.create("cashup") {
            storeFile = rootProject.file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else null

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            if (cashupSigning != null) signingConfig = cashupSigning
            // Jurnal provisioning hanya diisi di debug; lihat AppContainer.
            buildConfigField("boolean", "PROVISIONING_JOURNAL", "true")
        }
        release {
            if (cashupSigning != null) signingConfig = cashupSigning
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

// SDK CashUp datang dari Maven privat (lihat settings.gradle.kts). Group id = nama artifact;
// device-sdk memuat ReaderSession dan mPOS.
val sdkVersion = providers.gradleProperty("cashup.sdk.version").orElse("0.1.0").get()
val emvlibVersion = providers.gradleProperty("cashup.sdk.emvlibVersion").orElse("1.0.2").get()

dependencies {
    listOf(
        "provisioning-core",
        "cdcp-core",
        "device-sdk-edc",
        "feature-card-payment",
        "device-sdk",
        "notification-core",
        "qris-core",
        "feature-qris",
    ).forEach { artifact -> implementation("$artifact:$artifact:$sdkVersion") }
    // Biner EMV vendor tanpa source; tidak ada di POM module mana pun, jadi app wajib mendeklarasikan.
    implementation("com.lib.emv:emvlib-aar:$emvlibVersion")

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

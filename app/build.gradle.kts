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

    // Property values become Java string literals in BuildConfig, so a quote or backslash would break or alter them.
    fun buildProperty(name: String): String? = providers.gradleProperty(name).orNull?.also {
        require('"' !in it && '\\' !in it && '\n' !in it && '\r' !in it) { "Gradle property $name must not contain quotes, backslashes or newlines" }
    }
    // None of these values is committed: they come from Gradle properties (e.g. the user-level gradle.properties).
    // Empty means "not configured" and the app fails closed (no backend, no cleartext, no trusted package signer).
    val baseUrlProperty = buildProperty("cashup.baseUrl") ?: ""
    // Plain http hosts, honoured in debug builds only (see src/debug/res/xml/network_security_config.xml).
    val cleartextHosts = buildProperty("cashup.cleartextHosts") ?: ""

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            if (cashupSigning != null) signingConfig = cashupSigning
            // Jurnal provisioning hanya diisi di debug; lihat AppContainer.
            buildConfigField("boolean", "PROVISIONING_JOURNAL", "true")
            buildConfigField("String", "CLEARTEXT_HOSTS", "\"${cleartextHosts.ifEmpty { "192.168.90.46,100.114.27.64,avatar.bakso.site,czavatar.staging.cashlez.com" }}\"")
            buildConfigField("String", "DEFAULT_BASE_URL", "\"${baseUrlProperty.ifEmpty { " https://czavatar.staging.cashlez.com" }}\"")
        }
        release {
            if (cashupSigning != null) signingConfig = cashupSigning
            isMinifyEnabled = false
            buildConfigField("boolean", "PROVISIONING_JOURNAL", "false")
            // Release talks HTTPS only: no cleartext hosts, whatever the properties say.
            buildConfigField("String", "CLEARTEXT_HOSTS", "\"\"")
            buildConfigField("String", "DEFAULT_BASE_URL", "\"$baseUrlProperty\"")
        }
    }
    // Public keys that may sign key packages (N-3); unset means provisioning rejects every package.
    val packageSigningKeys = buildProperty("cashup.packageSigningKeys") ?: ""
    // Public RSA keys (X.509, Base64, comma separated) of the TR-34 KDH leaf certificate (N-3); unset rejects every TR-34 package.
    val kdhPublicKeys = buildProperty("cashup.kdhPublicKeys") ?: ""
    // Certificate pins for the payment hosts (N-2), `host=sha256/<b64>,sha256/<b64>;host2=...`.
    // Non-debug builds refuse to talk to an https host that has no pin (see AppContainer).
    val certificatePins = buildProperty("cashup.certificatePins") ?: ""
    defaultConfig {
        buildConfigField("String", "PACKAGE_SIGNING_KEYS", "\"$packageSigningKeys\"")
        buildConfigField("String", "KDH_PUBLIC_KEYS", "\"$kdhPublicKeys\"")
        buildConfigField("String", "CERTIFICATE_PINS", "\"$certificatePins\"")
    }
    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    // APK file name: cashup-payment-<buildType>-<versionName>.apk
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "cashup-avatar-${variant.buildType.name}-${variant.versionName}.apk"
        }
    }

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

// DEBUG-only Newland key injection. Values come from the git-ignored, owner-created
// local config file (see its .example); they are never printed or logged here.
// The generated source lives under build/ and is added to the debug source set only, so
// release builds never contain it and leave the provider uninstalled (fail closed).
val debugKeysDir = layout.buildDirectory.dir("generated/cashup/debugKeys")
val generateDebugKeys = tasks.register("generateDebugKeys") {
    val configFile = rootProject.file("config.properties")
    outputs.dir(debugKeysDir)
    outputs.upToDateWhen { false }
    doLast {
        val hexOnly = Regex("[0-9A-Fa-f]+")
        // Empty/absent = not configured (provider stays uninstalled). A non-empty value must be
        // hex of an allowed byte length; otherwise fail the build WITHOUT echoing the value.
        fun readHex(props: Properties, name: String, allowedBytes: (Int) -> Boolean, rule: String): String? {
            val v = props.getProperty(name)?.trim().orEmpty()
            if (v.isEmpty()) return null
            if (v.length % 2 != 0 || !hexOnly.matches(v) || !allowedBytes(v.length / 2)) {
                throw GradleException("config.properties: '$name' is invalid ($rule). The value is not shown.")
            }
            return v
        }
        val props = Properties()
        if (configFile.exists()) configFile.inputStream().use { props.load(it) }
        val kek = readHex(props, "newland.kek", { it == 16 || it == 24 }, "expected hex of 16 or 24 bytes")
        val dataKey = readHex(props, "newland.dataEncryptKey", { it == 16 || it == 24 }, "expected hex of 16 or 24 bytes")
        fun lit(v: String?) = if (v == null) "null" else "\"$v\""
        val out = debugKeysDir.get().asFile.resolve("com/cashup/app/DebugKeys.kt")
        out.parentFile.mkdirs()
        out.writeText(
            """
            package com.cashup.app

            // Generated into build/ by :app:generateDebugKeys. Debug builds only. Do not commit.
            internal object DebugKeys {
                val newlandKekHex: String? = ${lit(kek)}
                val newlandDataEncryptKeyHex: String? = ${lit(dataKey)}
            }
            """.trimIndent() + "\n",
        )
    }
}
android.sourceSets.getByName("debug").java.srcDir(debugKeysDir)
tasks.configureEach {
    val debugOnly = name.contains("Debug") && !name.contains("AndroidTest") && name != "generateDebugKeys"
    if (debugOnly && (name.startsWith("compile") || name.startsWith("lint") || name.startsWith("kapt"))) {
        dependsOn(generateDebugKeys)
    }
}

// SDK CashUp datang dari Maven privat (lihat settings.gradle.kts). Group id = nama artifact;
// device-sdk memuat ReaderSession dan mPOS.
val sdkVersion = providers.gradleProperty("cashup.sdk.version").orElse("0.1.3").get()
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

    // Dev-only HTTP inspector. Absent from release: see DevNetworkInspector in src/debug and src/release.
    debugImplementation("com.github.chuckerteam.chucker:library:4.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

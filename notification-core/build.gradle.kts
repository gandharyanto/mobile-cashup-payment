// PERINGATAN API LEVEL -- berlaku untuk seluruh source set `main` module ini.
//
// Module ini `kotlin("jvm")` murni: Android Lint tidak pernah menganalisisnya,
// jadi pemeriksaan `NewApi` TIDAK berlaku di sini. Tapi class hasil compile-nya
// dikemas ke dalam `:app` dan berjalan di device dengan `minSdk` 23. Artinya
// kode di `src/main` harus tetap berada di dalam subset stdlib Android API 23 /
// Java 8: TANPA `java.time.*` (API 26), `java.util.Base64` (API 26),
// `java.util.function.*` (API 24), `Optional` (API 24), atau default/static
// method pada interface yang bergantung desugaring -- tidak ada core library
// desugaring yang dikonfigurasi di repo ini.
//
// Repo ini sudah 3x kena bug kelas ini (lihat common-core/build.gradle.kts).

plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

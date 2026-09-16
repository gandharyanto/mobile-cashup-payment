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
// Branch ini sudah mengirim tiga bug dari kelas yang sama (AppCompat di
// device-sdk-edcsdk, java.util.Base64 di signing-core, java.time di
// common-core). Semua lolos karena tidak ada satu pun yang diperiksa lint.

plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio:3.9.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

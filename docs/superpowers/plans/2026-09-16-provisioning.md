# Provisioning (J1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sebuah APK yang, di terminal EDC yang belum terprovisioning, bisa memindai QR provisioning, menjalankan ceremony tiga langkah ke backend, memasang key DUKPT ke modul aman vendor, dan menandatangani setiap request sesudahnya dengan Ed25519.

**Architecture:** Empat lapis dengan batas tegas. `device-sdk-api` memegang kontrak hardware murni (Kotlin/JVM, tanpa Android); `device-sdk-edcsdk` satu-satunya module yang mengimpor `com.lib.core.*` dari AAR `edc-sdk`; `provisioning-core` mengorkestrasi ceremony tanpa tahu UI maupun vendor; `app` menyediakan empat layar di atas MVVM. `signing-core` yang sudah ada ditulis ulang dari ECDSA P-256 ke Ed25519 karena backend sudah mengunci algoritma itu.

**Tech Stack:** Kotlin 2.0.21, Gradle 8.9, AGP 8.4.0, JDK 17 toolchain (module Android meng-compile bytecode Java 8), compileSdk 33 / minSdk 23 / targetSdk 33, Retrofit 2.11.0 + Gson, OkHttp 4.12.0, BouncyCastle 1.78.1, AndroidX Security Crypto, Navigation Component + ViewBinding, CameraX + ZXing.

**Spec:** `docs/superpowers/specs/2026-09-16-provisioning-design.md` — plan ini berargumen dari spec itu; eksekutor membaca keduanya.

---

## Global Constraints

Setiap task secara implisit tunduk pada semua ini.

- **Kotlin 2.0.21, Gradle 8.9, JDK 17 toolchain.** Module Android meng-compile ke bytecode Java 8 (`sourceCompatibility`/`targetCompatibility` = `VERSION_1_8`, `jvmTarget = "1.8"`).
- **AGP 8.4.0** — versi yang sudah terbukti kompatibel dengan AAR vendor ini dan kombinasi JDK/Gradle ini di `edc-tms-agent`.
- **`compileSdk = 33`, `minSdk = 23`, `targetSdk = 33`.** `minSdk` 23 karena `EncryptedSharedPreferences` dan pembuatan key non-extractable di Android Keystore mensyaratkannya. StrongBox tetap oportunistik di API 28+.
- **Tidak ada version catalog.** Repo ini mendeklarasikan dependensi dengan versi literal langsung di `build.gradle.kts` tiap module. Ikuti pola itu; jangan memperkenalkan `libs.versions.toml` di plan ini.
- **AAR vendor selalu `implementation`, tidak pernah `api`.** Tipe vendor tidak boleh bocor melewati `device-sdk-edcsdk`.
- **`flatDir` wajib di `settings.gradle.kts`**, bukan di `build.gradle.kts` module — `dependencyResolutionManagement` repo ini memakai `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, yang menolak repository yang dideklarasikan di level project.
- **Framework tes terbelah, dan ini disengaja.** Module Kotlin/JVM murni (`common-core`, `device-sdk-api`, `signing-core`) memakai **JUnit 5 Jupiter** seperti sekarang. Module Android (`device-sdk-edcsdk`, `provisioning-core`, `app`) memakai **JUnit 4 + Robolectric + MockK**, konfigurasi yang sudah terbukti jalan dengan AAR yang sama persis di `edc-tms-agent`. Menyeragamkan ke JUnit 5 di sisi Android menuntut plugin pihak ketiga tanpa manfaat sepadan.
- **Jangan pernah menyalin, merujuk, atau meng-commit** `D:\gandha_cashup\projects\edc-tms-agent\key\busways.jks` atau `config.properties` miliknya. Itu keystore penandatanganan produksi beserta passwordnya, tidak ada hubungannya dengan task ini.
- **Key material tidak pernah masuk log, tidak pernah jadi `String`.** `ByteArray` di-`fill(0)` segera setelah dipakai. `HttpLoggingInterceptor.Level.BODY` hanya boleh di build `debug`.
- **Commit tiap akhir langkah yang menyebutnya.** Pesan commit bahasa Inggris, imperative mood, konsisten dengan riwayat repo.

---

## Peta Berkas

Apa yang dibuat/diubah, dan tanggung jawab tiap berkas.

**Task 1 — fondasi build**

| Berkas | Tanggung jawab |
|---|---|
| `build.gradle.kts` | Mendaftarkan plugin AGP + Kotlin Android (`apply false`) |
| `settings.gradle.kts` | Repository `flatDir` ke `aarlib/`, `include` module baru |
| `gradle.properties` | `android.useAndroidX`, `android.nonTransitiveRClass`, SDK level bersama |
| `.gitignore` | Mengabaikan biner `.aar`, README tetap terlacak |
| `aarlib/README.md` | Menjelaskan asal biner pihak ketiga |

**Task 2 — kontrak hardware**

| Berkas | Tanggung jawab |
|---|---|
| `device-sdk-api/.../SerialNumberProvider.kt` | Satu fungsi: nomor seri terminal |
| `device-sdk-api/.../TerminalKeyMaterial.kt` | Satu key DUKPT hasil unwrap; `toString()` yang tidak bocor |
| `device-sdk-api/.../TerminalKeyInstaller.kt` | Pemasangan key + rollback, beserta tipe hasilnya |
| `device-sdk-api/.../DeviceSdkRegistry.kt` | **Dihapus** — digantikan `SDKManager.autoDetectDevice()` |

**Task 3–4 — jaringan & tanda tangan**

| Berkas | Tanggung jawab |
|---|---|
| `common-core/.../RequestHeadersInterceptor.kt` | `X-Timestamp` + `X-Correlation-Id` untuk semua request |
| `signing-core/.../Ed25519RequestSigner.kt` | Canonical string, byte-exact |
| `signing-core/.../DeviceSigner.kt` | Sumber `deviceId` + operasi tanda tangan |
| `signing-core/.../SigningInterceptor.kt` | Menyuntik `X-Device-Id`/`X-Nonce`/`X-Signature` |

**Task 5–10** dipetakan di masing-masing task.

---

## Task 1: Fondasi build Android dan `aarlib/`

Repo ini belum pernah memakai Android Gradle Plugin. Task ini menyiapkannya tanpa menambah module apa pun, sehingga bisa diverifikasi berdiri sendiri.

**Files:**
- Modify: `build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `gradle.properties`
- Modify: `.gitignore`
- Create: `aarlib/README.md`
- Create: `aarlib/*.aar` (8 biner, disalin, git-ignored)

**Interfaces:**
- Consumes: tidak ada.
- Produces: build root yang menyediakan `com.android.library`, `com.android.application`, dan `kotlin("android")` untuk di-`apply` module; repository `flatDir` yang menunjuk `aarlib/`; properti `cashup.compileSdk` / `cashup.minSdk` / `cashup.targetSdk` yang dibaca setiap module Android di task berikutnya.

- [ ] **Step 1: Salin AAR vendor ke `aarlib/`**

Jalankan (PowerShell):

```powershell
New-Item -ItemType Directory -Force -Path "aarlib" | Out-Null
$src = "D:\gandha_cashup\projects\edc-tms-agent\aarlib"
foreach ($f in @(
  "core-release_1.0.63.aar",
  "logger-release_1.0.2.aar",
  "pax-release-core_1.0.63.aar",
  "sunmi-release-core_1.0.63.aar",
  "centerm-release-core_1.0.63.aar",
  "nexgo-release-core_1.0.63.aar",
  "topwize-release-core_1.0.63.aar",
  "szanfu-release-core_1.0.63.aar"
)) { Copy-Item "$src\$f" "aarlib\" }
Get-ChildItem aarlib
```

Harapan: `aarlib/` berisi tepat delapan berkas `.aar` itu. **Jangan** menyalin apa pun dari folder `key\` di repo sumber.

- [ ] **Step 2: Buat `aarlib/README.md`**

```markdown
# `aarlib/` — biner SDK vendor

Berkas `.aar` di sini adalah hasil build repo `edc-sdk`
(`D:\gandha_cashup\projects\edc-sdk`), disalin dari
`D:\gandha_cashup\projects\edc-tms-agent\aarlib`. Repo ini **tidak** membangunnya
dan biner itu **tidak** di-commit.

| Berkas | Dipakai oleh |
|---|---|
| `core-release_1.0.63.aar` | `:device-sdk-edcsdk` — `SDKManager`, `KeyManager`, `BaseSystemKey` |
| `logger-release_1.0.2.aar` | `:device-sdk-edcsdk` — dependensi `core` |
| `pax-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `sunmi-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `centerm-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `nexgo-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `topwize-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `szanfu-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |

## Memperbarui

Build ulang di `edc-sdk` lalu salin hasilnya ke sini. Naikkan nomor versi di
`device-sdk-edcsdk/build.gradle.kts` bersamaan — nama berkas adalah koordinat
dependensinya.

Feitian, Urovo, Newland, dan Tianyu tidak punya AAR di sini dan tidak punya
implementasi `SystemKey` di `edc-sdk`.
```

- [ ] **Step 3: Abaikan biner di `.gitignore`**

Tambahkan di akhir `.gitignore`:

```
aarlib/*.aar
```

- [ ] **Step 4: Daftarkan plugin Android di `build.gradle.kts`**

Ganti seluruh isi `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("com.android.library") version "8.4.0" apply false
    id("com.android.application") version "8.4.0" apply false
}

allprojects {
    group = "com.cashup"
    version = "0.1.0"
}
```

- [ ] **Step 5: Tambahkan repository `flatDir` di `settings.gradle.kts`**

Di dalam blok `dependencyResolutionManagement.repositories`, setelah `mavenCentral()`, tambahkan:

```kotlin
        // AAR vendor hasil build edc-sdk. flatDir, bukan files(), karena AGP
        // menolak dependensi berkas .aar lokal di dalam project yang sendirinya
        // membangun AAR.
        flatDir { dirs("$rootDir/aarlib") }
```

Repository ini harus di sini, bukan di `build.gradle.kts` module — `RepositoriesMode.FAIL_ON_PROJECT_REPOS` menolak repository di level project.

- [ ] **Step 6: Tambahkan properti Android di `gradle.properties`**

Tambahkan di akhir berkas:

```properties
android.useAndroidX=true
android.nonTransitiveRClass=true

# Level SDK bersama. Ditulis sekali di sini supaya tidak melenceng antar module.
# minSdk 23, bukan 21: EncryptedSharedPreferences dan pembuatan key
# non-extractable di Android Keystore sama-sama mensyaratkannya.
cashup.compileSdk=33
cashup.minSdk=23
cashup.targetSdk=33
```

- [ ] **Step 7: Verifikasi build masih waras**

Run: `./gradlew projects --no-daemon`
Expected: BUILD SUCCESSFUL, mendaftarkan `:common-core`, `:device-sdk-api`, `:signing-core`.

Run: `./gradlew test --no-daemon`
Expected: BUILD SUCCESSFUL, 28 tes lolos seperti sebelumnya.

- [ ] **Step 8: Commit**

```bash
git add build.gradle.kts settings.gradle.kts gradle.properties .gitignore aarlib/README.md
git commit -m "build: add Android Gradle Plugin and the edc-sdk AAR repository

Registers AGP 8.4.0 and kotlin-android as apply-false plugins, points a
flatDir repository at aarlib/, and pins the shared SDK levels in
gradle.properties so they cannot drift between modules.

The AAR binaries are edc-sdk build outputs copied from edc-tms-agent.
They are third-party, git-ignored, and never rebuilt here; aarlib/README.md
records where they come from and how to refresh them.

flatDir lives in settings.gradle.kts because dependencyResolutionManagement
runs with FAIL_ON_PROJECT_REPOS, which rejects project-level repositories."
```

---

## Task 2: Kontrak provisioning di `device-sdk-api`

Menambahkan dua interface dan dua tipe data yang dipakai `provisioning-core`, lalu membuang `DeviceSdkRegistry` yang sudah tidak ada gunanya.

**Files:**
- Create: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/SerialNumberProvider.kt`
- Create: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/TerminalKeyMaterial.kt`
- Create: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/TerminalKeyInstaller.kt`
- Delete: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdkRegistry.kt`
- Delete: `device-sdk-api/src/test/kotlin/com/cashup/devicesdk/DeviceSdkRegistryTest.kt`
- Create: `device-sdk-api/src/testFixtures/kotlin/com/cashup/devicesdk/fake/FakeSerialNumberProvider.kt`
- Create: `device-sdk-api/src/testFixtures/kotlin/com/cashup/devicesdk/fake/FakeTerminalKeyInstaller.kt`
- Create: `device-sdk-api/src/test/kotlin/com/cashup/devicesdk/TerminalKeyMaterialTest.kt`
- Modify: `device-sdk-api/build.gradle.kts`

**Interfaces:**
- Consumes: tidak ada.
- Produces:
  - `fun interface SerialNumberProvider { suspend fun serialNumber(): String? }`
  - `class TerminalKeyMaterial(val purpose: String, val ipek: ByteArray, val ksn: ByteArray)` dengan `fun zeroize()`
  - `enum class KeyBacking { VENDOR_SECURE_MODULE, TEE_VAULT_ONLY }`
  - `data class KeyInstallOutcome(val purpose: String, val backing: KeyBacking)`
  - `sealed interface TerminalKeyInstallResult` dengan `data class Installed(val outcomes: List<KeyInstallOutcome>)` dan `data class Failed(val purpose: String, val reason: String)`
  - `interface TerminalKeyInstaller { suspend fun install(materials: List<TerminalKeyMaterial>): TerminalKeyInstallResult; suspend fun wipe() }`
  - Test fixture `FakeSerialNumberProvider(value: String?)` dan `FakeTerminalKeyInstaller(vendorSlotPurpose: String?, failOnPurpose: String?)` dengan properti terbaca `wipeCount: Int` dan `installedPurposes: List<String>`

- [ ] **Step 1: Pastikan `DeviceSdkRegistry` tidak dipakai siapa pun**

Run: `grep -rn "DeviceSdkRegistry" --include=*.kt .`
Expected: hanya dua berkas yang akan dihapus (`DeviceSdkRegistry.kt`, `DeviceSdkRegistryTest.kt`). Kalau ada hasil lain — khususnya di `testFixtures` — hentikan dan laporkan; plan ini mengasumsikan tidak ada pemakai lain.

- [ ] **Step 2: Hapus `DeviceSdkRegistry` beserta tesnya**

```bash
git rm device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdkRegistry.kt
git rm device-sdk-api/src/test/kotlin/com/cashup/devicesdk/DeviceSdkRegistryTest.kt
```

- [ ] **Step 3: Tulis tes yang gagal untuk `TerminalKeyMaterial`**

`device-sdk-api/src/test/kotlin/com/cashup/devicesdk/TerminalKeyMaterialTest.kt`:

```kotlin
package com.cashup.devicesdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TerminalKeyMaterialTest {

    private fun material() = TerminalKeyMaterial(
        purpose = "PIN",
        ipek = byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D),
        ksn = byteArrayOf(0x4E, 0x5F),
    )

    @Test
    fun `toString names the purpose but never the key bytes`() {
        val rendered = material().toString()

        assertEquals("TerminalKeyMaterial(purpose=PIN, ipek=<redacted>, ksn=<redacted>)", rendered)
        assertFalse(rendered.contains("0a", ignoreCase = true), "ipek byte leaked into toString")
        assertFalse(rendered.contains("4e", ignoreCase = true), "ksn byte leaked into toString")
    }

    @Test
    fun `zeroize clears both arrays in place`() {
        val subject = material()
        val ipekRef = subject.ipek
        val ksnRef = subject.ksn

        subject.zeroize()

        assertEquals(0, ipekRef.count { it != 0.toByte() }, "ipek still holds non-zero bytes")
        assertEquals(0, ksnRef.count { it != 0.toByte() }, "ksn still holds non-zero bytes")
    }
}
```

Alasan tes pertama ada: kalau `TerminalKeyMaterial` ditulis sebagai `data class`, `toString()` bawaannya akan mencetak isi array dan key material bocor ke tiap log yang kebetulan mencetak objeknya.

- [ ] **Step 4: Jalankan tes, pastikan gagal**

Run: `./gradlew :device-sdk-api:test --tests "com.cashup.devicesdk.TerminalKeyMaterialTest" --no-daemon`
Expected: FAIL — kompilasi gagal, `Unresolved reference: TerminalKeyMaterial`.

- [ ] **Step 5: Tulis `TerminalKeyMaterial.kt`**

```kotlin
package com.cashup.devicesdk

/**
 * Satu key DUKPT hasil unwrap paket provisioning, siap diinjeksi.
 *
 * Sengaja **bukan** `data class`: `toString()` bawaan `data class` akan
 * mencetak isi array, sehingga key material bocor ke setiap log yang
 * kebetulan mencetak objek ini.
 *
 * [ipek] dan [ksn] adalah byte mentah, bukan `String`, supaya bisa dihapus
 * dari memori lewat [zeroize] segera setelah dipakai.
 */
class TerminalKeyMaterial(
    val purpose: String,
    val ipek: ByteArray,
    val ksn: ByteArray,
) {
    /** Menimpa kedua array dengan nol, di tempat. Panggil segera setelah injeksi. */
    fun zeroize() {
        ipek.fill(0)
        ksn.fill(0)
    }

    override fun toString(): String =
        "TerminalKeyMaterial(purpose=$purpose, ipek=<redacted>, ksn=<redacted>)"
}
```

- [ ] **Step 6: Jalankan tes, pastikan lolos**

Run: `./gradlew :device-sdk-api:test --tests "com.cashup.devicesdk.TerminalKeyMaterialTest" --no-daemon`
Expected: PASS, 2 tes.

- [ ] **Step 7: Tulis `SerialNumberProvider.kt`**

```kotlin
package com.cashup.devicesdk

/**
 * Nomor seri hardware terminal. Mengembalikan `null` kalau device tidak
 * dikenali SDK vendor mana pun — dalam hal itu provisioning tidak boleh
 * dilanjutkan, karena nomor seri adalah identitas device di backend.
 */
fun interface SerialNumberProvider {
    suspend fun serialNumber(): String?
}
```

- [ ] **Step 8: Tulis `TerminalKeyInstaller.kt`**

```kotlin
package com.cashup.devicesdk

/** Di mana sebuah key benar-benar berakhir. Dilaporkan per purpose, tidak pernah diasumsikan. */
enum class KeyBacking {
    /** Modul aman vendor. Key masuk dan tidak pernah keluar. */
    VENDOR_SECURE_MODULE,

    /** Vault ter-enkripsi Keystore. Terlindungi saat diam; key melewati RAM saat dipakai. */
    TEE_VAULT_ONLY,
}

data class KeyInstallOutcome(val purpose: String, val backing: KeyBacking)

sealed interface TerminalKeyInstallResult {
    data class Installed(val outcomes: List<KeyInstallOutcome>) : TerminalKeyInstallResult
    data class Failed(val purpose: String, val reason: String) : TerminalKeyInstallResult
}

/**
 * Memasang key DUKPT ke penyimpanan device.
 *
 * [install] mengembalikan [TerminalKeyInstallResult.Installed] hanya kalau
 * SEMUA material terpasang, dan melaporkan per purpose di mana masing-masing
 * berakhir — pemanggil wajib menampilkan/mencatat itu, karena tidak semua
 * purpose bisa mendapat perlindungan hardware (lihat spec §4.3).
 *
 * [wipe] dipanggil saat rollback atomic: gagal di langkah mana pun membuat
 * device kembali ke keadaan belum terprovisioning, tanpa sisa key separuh jalan.
 */
interface TerminalKeyInstaller {
    suspend fun install(materials: List<TerminalKeyMaterial>): TerminalKeyInstallResult
    suspend fun wipe()
}
```

- [ ] **Step 9: Tulis test fixture**

`device-sdk-api/src/testFixtures/kotlin/com/cashup/devicesdk/fake/FakeSerialNumberProvider.kt`:

```kotlin
package com.cashup.devicesdk.fake

import com.cashup.devicesdk.SerialNumberProvider

class FakeSerialNumberProvider(private val value: String?) : SerialNumberProvider {
    override suspend fun serialNumber(): String? = value
}
```

`device-sdk-api/src/testFixtures/kotlin/com/cashup/devicesdk/fake/FakeTerminalKeyInstaller.kt`:

```kotlin
package com.cashup.devicesdk.fake

import com.cashup.devicesdk.KeyBacking
import com.cashup.devicesdk.KeyInstallOutcome
import com.cashup.devicesdk.TerminalKeyInstallResult
import com.cashup.devicesdk.TerminalKeyInstaller
import com.cashup.devicesdk.TerminalKeyMaterial

/**
 * [vendorSlotPurpose] meniru batas nyata di spec §4.3: hanya satu purpose yang
 * bisa masuk modul aman vendor, sisanya jatuh ke vault.
 * [failOnPurpose] memicu kegagalan pemasangan untuk menguji jalur rollback.
 */
class FakeTerminalKeyInstaller(
    private val vendorSlotPurpose: String? = "PIN",
    private val failOnPurpose: String? = null,
) : TerminalKeyInstaller {

    var wipeCount: Int = 0
        private set

    private val installed = mutableListOf<String>()
    val installedPurposes: List<String> get() = installed.toList()

    override suspend fun install(materials: List<TerminalKeyMaterial>): TerminalKeyInstallResult {
        val outcomes = mutableListOf<KeyInstallOutcome>()
        for (material in materials) {
            if (material.purpose == failOnPurpose) {
                return TerminalKeyInstallResult.Failed(material.purpose, "fake failure")
            }
            installed += material.purpose
            outcomes += KeyInstallOutcome(
                purpose = material.purpose,
                backing = if (material.purpose == vendorSlotPurpose) {
                    KeyBacking.VENDOR_SECURE_MODULE
                } else {
                    KeyBacking.TEE_VAULT_ONLY
                },
            )
        }
        return TerminalKeyInstallResult.Installed(outcomes)
    }

    override suspend fun wipe() {
        wipeCount++
        installed.clear()
    }
}
```

- [ ] **Step 10: Tambahkan dependensi coroutines-test untuk tes `suspend`**

Di `device-sdk-api/build.gradle.kts`, di dalam blok `dependencies`, tambahkan:

```kotlin
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

- [ ] **Step 11: Jalankan seluruh tes module**

Run: `./gradlew :device-sdk-api:test --no-daemon`
Expected: PASS. Jumlah tes berkurang dari sebelumnya karena `DeviceSdkRegistryTest` dihapus, dan bertambah 2 dari `TerminalKeyMaterialTest`.

- [ ] **Step 12: Commit**

```bash
git add device-sdk-api/
git commit -m "feat(device-sdk-api): add provisioning contracts, drop DeviceSdkRegistry

SerialNumberProvider and TerminalKeyInstaller are the only two things
provisioning-core will know about the hardware; device-sdk-edcsdk
translates them to edc-sdk's SDKManager and KeyManager.

TerminalKeyInstaller reports where each key actually landed rather than
assuming, because only one IPEK can reach the vendor secure module while
the rest fall back to the Keystore vault (spec 4.3). Callers must surface
that, so it is in the return type instead of being discoverable only by
reading the adapter.

TerminalKeyMaterial is deliberately not a data class: the generated
toString would print the key arrays into any log that renders the object.

DeviceSdkRegistry goes because SDKManager.autoDetectDevice already
resolves the vendor from Build.BRAND, making Build.MODEL prefix matching
redundant."
```

---

## Task 3: Header lintas-request di `common-core`

`X-Timestamp` dan `X-Correlation-Id` wajib ada di **semua** request `/v1/`, termasuk `qr-redeem` yang tidak ditandatangani. Karena itu tempatnya di `common-core`, bukan `signing-core`.

Interceptor tanda tangan di Task 4 membaca `X-Timestamp` yang ditulis interceptor ini, jadi urutan pemasangannya mengikat.

**Files:**
- Create: `common-core/src/main/kotlin/com/cashup/common/network/RequestHeadersInterceptor.kt`
- Create: `common-core/src/test/kotlin/com/cashup/common/network/RequestHeadersInterceptorTest.kt`

**Interfaces:**
- Consumes: tidak ada.
- Produces: `class RequestHeadersInterceptor(clock: () -> OffsetDateTime = OffsetDateTime::now, correlationIdFactory: () -> String = { "edc-" + UUID.randomUUID() }) : Interceptor`. Menulis header `X-Timestamp` (ISO-8601 offset) dan `X-Correlation-Id`. Task 4 membaca `X-Timestamp` dari request yang sudah dilewatkan interceptor ini.

- [ ] **Step 1: Tulis tes yang gagal**

`common-core/src/test/kotlin/com/cashup/common/network/RequestHeadersInterceptorTest.kt`:

```kotlin
package com.cashup.common.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class RequestHeadersInterceptorTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    private fun clientWith(interceptor: RequestHeadersInterceptor) =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    @Test
    fun `writes an ISO-8601 offset timestamp and a correlation id`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val fixed = OffsetDateTime.of(2026, 9, 16, 10, 30, 0, 0, ZoneOffset.ofHours(7))
        val client = clientWith(
            RequestHeadersInterceptor(clock = { fixed }, correlationIdFactory = { "corr-1" })
        )

        client.newCall(Request.Builder().url(server.url("/v1/ping")).build()).execute().close()

        val recorded = server.takeRequest()
        assertEquals("2026-09-16T10:30+07:00", recorded.getHeader("X-Timestamp"))
        assertEquals("corr-1", recorded.getHeader("X-Correlation-Id"))
    }

    @Test
    fun `each attempt gets a fresh correlation id`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        var counter = 0
        val client = clientWith(
            RequestHeadersInterceptor(correlationIdFactory = { "corr-" + counter++ })
        )

        repeat(2) {
            client.newCall(Request.Builder().url(server.url("/v1/ping")).build()).execute().close()
        }

        assertEquals("corr-0", server.takeRequest().getHeader("X-Correlation-Id"))
        assertEquals("corr-1", server.takeRequest().getHeader("X-Correlation-Id"))
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `./gradlew :common-core:test --tests "com.cashup.common.network.RequestHeadersInterceptorTest" --no-daemon`
Expected: FAIL — `Unresolved reference: RequestHeadersInterceptor`.

- [ ] **Step 3: Tulis implementasinya**

`common-core/src/main/kotlin/com/cashup/common/network/RequestHeadersInterceptor.kt`:

```kotlin
package com.cashup.common.network

import okhttp3.Interceptor
import okhttp3.Response
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Menambahkan `X-Timestamp` (ISO-8601 offset) dan `X-Correlation-Id` ke setiap
 * request. Backend mewajibkan keduanya di semua path `/v1/`, termasuk
 * `qr-redeem` yang tidak ditandatangani — karena itu interceptor ini tinggal di
 * `common-core`, bukan di `signing-core`.
 *
 * **Urutan pemasangan mengikat**: interceptor ini harus terdaftar SEBELUM
 * `SigningInterceptor`, yang membaca `X-Timestamp` yang ditulis di sini untuk
 * menyusun canonical string. Terbalik, tanda tangannya tidak akan cocok dengan
 * timestamp yang benar-benar terkirim.
 *
 * Keduanya dibuat baru per percobaan HTTP. Retry boleh punya timestamp dan
 * correlation id baru — itu bukan bagian dari sidik jari idempotency, yang
 * dibawa header `Idempotency-Key` terpisah per aksi pengguna.
 */
class RequestHeadersInterceptor(
    private val clock: () -> OffsetDateTime = OffsetDateTime::now,
    private val correlationIdFactory: () -> String = { "edc-" + UUID.randomUUID() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-Timestamp", clock().toString())
            .header("X-Correlation-Id", correlationIdFactory())
            .build()
        return chain.proceed(request)
    }
}
```

- [ ] **Step 4: Jalankan tes, pastikan lolos**

Run: `./gradlew :common-core:test --tests "com.cashup.common.network.RequestHeadersInterceptorTest" --no-daemon`
Expected: PASS, 2 tes.

- [ ] **Step 5: Commit**

```bash
git add common-core/src/main/kotlin/com/cashup/common/network/RequestHeadersInterceptor.kt common-core/src/test/kotlin/com/cashup/common/network/RequestHeadersInterceptorTest.kt
git commit -m "feat(common-core): add RequestHeadersInterceptor

X-Timestamp and X-Correlation-Id are required on every /v1/ path,
including the unsigned qr-redeem call, so this belongs in common-core
rather than signing-core.

Its ordering relative to SigningInterceptor is binding and documented on
the class: the signer reads the X-Timestamp written here to build the
canonical string, so registering them the other way round produces a
signature over a timestamp that was never sent."
```

---

## Task 4: Tulis ulang `signing-core` ke Ed25519

`signing-core` yang ada menandatangani dengan `SHA256withECDSA` dan canonical string yang berbeda. Backend sudah mengunci Ed25519 (diagram tim: EDC membuat "RSA & EDDSA Key Pair", backend mengambil public key Ed25519 dari General Purpose HSM), jadi seluruh module ini disesuaikan.

Empat hal yang berubah: algoritma, isi canonical string (bertambah `deviceId`, path tanpa query, timestamp berupa string ISO bukan milidetik, body hash hex bukan Base64), encoding tanda tangan (Base64 URL-safe tanpa padding), dan sumber key (`KeyPair` diganti operasi tanda tangan, karena private key Ed25519 tidak akan berada di `java.security.KeyPair` yang bisa diedarkan di sisi Android).

JDK 17 mendukung Ed25519 secara native, jadi tes module ini tidak perlu BouncyCastle. BouncyCastle baru diperlukan di sisi Android, di Task 7.

**Files:**
- Create: `signing-core/src/main/kotlin/com/cashup/signing/Ed25519RequestSigner.kt`
- Create: `signing-core/src/main/kotlin/com/cashup/signing/DeviceSigner.kt`
- Delete: `signing-core/src/main/kotlin/com/cashup/signing/RequestSigner.kt`
- Delete: `signing-core/src/main/kotlin/com/cashup/signing/SigningKeyProvider.kt`
- Delete: `signing-core/src/test/kotlin/com/cashup/signing/RequestSignerTest.kt`
- Modify: `signing-core/src/main/kotlin/com/cashup/signing/SigningInterceptor.kt`
- Create: `signing-core/src/test/kotlin/com/cashup/signing/Ed25519RequestSignerTest.kt`
- Modify: `signing-core/src/test/kotlin/com/cashup/signing/SigningInterceptorTest.kt`

**Interfaces:**
- Consumes: `RequestHeadersInterceptor` dari Task 3 — hanya secara operasional. Tidak ada dependensi kode; `signing-core` tetap tidak bergantung ke `common-core`.
- Produces:
  - `class Ed25519RequestSigner` dengan `fun canonicalize(method: String, path: String, deviceId: String, timestamp: String, nonce: String, body: ByteArray): ByteArray` dan `companion object { fun encodeSignature(raw: ByteArray): String }`
  - `interface DeviceSigner { fun deviceId(): String?; fun sign(canonicalBytes: ByteArray): ByteArray }`
  - `class DeviceNotProvisionedException : IOException` (dipertahankan; alasan aslinya masih berlaku)
  - `class MissingTimestampException : IOException`
  - `class SigningInterceptor(signer: DeviceSigner, requestSigner: Ed25519RequestSigner = Ed25519RequestSigner(), nonceFactory: () -> String = { UUID.randomUUID().toString() }) : Interceptor`

- [ ] **Step 1: Tulis tes yang gagal untuk canonical string**

`signing-core/src/test/kotlin/com/cashup/signing/Ed25519RequestSignerTest.kt`:

```kotlin
package com.cashup.signing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class Ed25519RequestSignerTest {

    private val signer = Ed25519RequestSigner()

    @Test
    fun `canonical string keeps the agreed field order`() {
        val canonical = signer.canonicalize(
            method = "post",
            path = "/v1/terminal-key-provisioning/orders/abc/package",
            deviceId = "PAX-A920-0012938",
            timestamp = "2026-09-16T10:30+07:00",
            nonce = "nonce-1",
            body = "{}".toByteArray(),
        ).decodeToString()

        val lines = canonical.lines()
        assertEquals(6, lines.size)
        assertEquals("POST", lines[0])
        assertEquals("/v1/terminal-key-provisioning/orders/abc/package", lines[1])
        assertEquals("PAX-A920-0012938", lines[2])
        assertEquals("2026-09-16T10:30+07:00", lines[3])
        assertEquals("nonce-1", lines[4])
        assertEquals(64, lines[5].length, "body hash must be 64 hex chars")
        assertTrue(lines[5].all { it in "0123456789abcdef" }, "body hash must be lowercase hex")
    }

    @Test
    fun `an empty body hashes the empty byte array`() {
        val canonical = signer.canonicalize("GET", "/v1/ping", "dev-1", "t", "n", ByteArray(0))

        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            canonical.decodeToString().lines().last(),
        )
    }

    @Test
    fun `the path is passed through verbatim, query included`() {
        val canonical = signer.canonicalize("GET", "/v1/payments?status=PENDING", "d", "t", "n", ByteArray(0))

        // Kontrak backend menandatangani encodedPath saja. Memisahkan query
        // adalah tanggung jawab pemanggil, bukan signer -- dikunci di sini
        // supaya kesalahan pemanggil muncul sebagai tanda tangan yang tidak
        // cocok, bukan diperbaiki diam-diam di lapisan ini.
        assertEquals("/v1/payments?status=PENDING", canonical.decodeToString().lines()[1])
    }

    @Test
    fun `signature encodes URL-safe without padding and verifies`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val canonical = signer.canonicalize("POST", "/v1/x", "dev-1", "t", "n", "{}".toByteArray())

        val raw = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(canonical)
            sign()
        }
        val encoded = Ed25519RequestSigner.encodeSignature(raw)

        assertFalse(encoded.contains('='), "signature must not be padded")
        assertFalse(encoded.contains('+') || encoded.contains('/'), "signature must be URL-safe")

        val verified = Signature.getInstance("Ed25519").run {
            initVerify(keyPair.public)
            update(canonical)
            verify(Base64.getUrlDecoder().decode(encoded))
        }
        assertTrue(verified)
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `./gradlew :signing-core:test --tests "com.cashup.signing.Ed25519RequestSignerTest" --no-daemon`
Expected: FAIL — `Unresolved reference: Ed25519RequestSigner`.

- [ ] **Step 3: Tulis `Ed25519RequestSigner.kt`**

```kotlin
package com.cashup.signing

import java.security.MessageDigest
import java.util.Base64

/**
 * Menyusun bentuk kanonik sebuah request untuk ditandatangani Ed25519.
 *
 * Susunannya, dipisah newline, mengikuti kontrak backend persis:
 *
 * ```
 * METHOD
 * path                 encodedPath saja, TANPA query string
 * deviceId
 * timestamp            nilai header X-Timestamp, ISO-8601 offset
 * nonce
 * sha256hex(body)      hex huruf kecil
 * ```
 *
 * Stabilitas byte-for-byte itu intinya: menukar urutan field, mengganti hex
 * jadi Base64, atau menambah field baru membatalkan setiap tanda tangan yang
 * sudah pernah diterima backend.
 *
 * [path] diteruskan apa adanya. Memisahkan query string adalah tanggung jawab
 * pemanggil ([SigningInterceptor] memakai `request.url.encodedPath`), supaya
 * kesalahan di sana muncul sebagai tanda tangan yang tidak cocok, bukan
 * diperbaiki diam-diam di sini.
 */
class Ed25519RequestSigner {

    fun canonicalize(
        method: String,
        path: String,
        deviceId: String,
        timestamp: String,
        nonce: String,
        body: ByteArray,
    ): ByteArray = listOf(
        method.uppercase(),
        path,
        deviceId,
        timestamp,
        nonce,
        sha256Hex(body),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** Base64 URL-safe tanpa padding, sesuai yang diharapkan backend di `X-Signature`. */
        fun encodeSignature(raw: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
}
```

- [ ] **Step 4: Jalankan tes, pastikan lolos**

Run: `./gradlew :signing-core:test --tests "com.cashup.signing.Ed25519RequestSignerTest" --no-daemon`
Expected: PASS, 4 tes.

- [ ] **Step 5: Ganti `SigningKeyProvider` dengan `DeviceSigner`**

Hapus `signing-core/src/main/kotlin/com/cashup/signing/SigningKeyProvider.kt`, lalu buat `signing-core/src/main/kotlin/com/cashup/signing/DeviceSigner.kt`:

```kotlin
package com.cashup.signing

/**
 * Identitas penandatangan milik device.
 *
 * Mengekspos operasi tanda tangan, bukan `java.security.KeyPair`, karena di
 * Android private key Ed25519 tidak tinggal di objek `KeyPair` yang bisa
 * diedarkan — implementasinya memuat blob PKCS8 dari penyimpanan ter-enkripsi,
 * memakainya, lalu membuangnya. Antarmuka yang mengembalikan `KeyPair` akan
 * memaksa key material itu hidup lebih lama dari yang perlu.
 *
 * [deviceId] mengembalikan `null` sebelum device terprovisioning. Sesuai
 * keputusan di spec §3.3, nilainya adalah nomor seri hardware, bukan UUID
 * terbitan backend.
 */
interface DeviceSigner {
    fun deviceId(): String?

    /** Tanda tangan Ed25519 mentah (64 byte) atas [canonicalBytes]. */
    fun sign(canonicalBytes: ByteArray): ByteArray
}
```

- [ ] **Step 6: Tulis ulang `SigningInterceptor.kt`**

```kotlin
package com.cashup.signing

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.UUID

/**
 * Dilempar kalau [SigningInterceptor] dipanggil sebelum device terprovisioning.
 *
 * Sengaja [IOException], bukan [IllegalStateException]: di jalur async OkHttp
 * (`enqueue`, yang dipakai fungsi `suspend` Retrofit) exception non-IOException
 * dari sebuah interceptor dilaporkan lewat `onFailure` LALU dilempar ulang,
 * sehingga proses mati lewat uncaught-exception handler milik thread
 * dispatcher. IOException muncul rapi sebagai kegagalan pemanggilan biasa.
 */
class DeviceNotProvisionedException :
    IOException("SigningInterceptor dipanggil sebelum device terprovisioning")

/**
 * Dilempar kalau `X-Timestamp` belum ada saat request sampai ke sini — artinya
 * `RequestHeadersInterceptor` tidak terpasang, atau terpasang setelah
 * interceptor ini. IOException dengan alasan yang sama seperti di atas.
 */
class MissingTimestampException :
    IOException("X-Timestamp tidak ada; RequestHeadersInterceptor harus terpasang lebih dulu")

/**
 * Menandatangani setiap request yang melewatinya dengan `X-Device-Id`,
 * `X-Nonce`, dan `X-Signature`.
 *
 * Pasang ini HANYA pada client yang bicara ke endpoint bertanda tangan.
 * `qr-redeem` tidak boleh melewatinya: saat itu backend belum mengenal public
 * key device, karena kunci itu justru baru dikirim di request tersebut.
 *
 * Gagal keras (melempar) alih-alih mengirim request tanpa tanda tangan —
 * request tak bertanda tangan yang sampai ke sini adalah bug wiring, bukan
 * keadaan yang bisa dipulihkan.
 */
class SigningInterceptor(
    private val signer: DeviceSigner,
    private val requestSigner: Ed25519RequestSigner = Ed25519RequestSigner(),
    private val nonceFactory: () -> String = { UUID.randomUUID().toString() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val deviceId = signer.deviceId() ?: throw DeviceNotProvisionedException()
        val timestamp = request.header("X-Timestamp") ?: throw MissingTimestampException()

        // Body one-shot/duplex tidak bisa dibaca dua kali (sekali di sini untuk
        // ditandatangani, sekali saat OkHttp menuliskannya ke wire), jadi
        // ditandatangani sebagai body kosong sementara body aslinya tetap
        // terkirim utuh. Body biasa -- yang berlaku untuk seluruh alur
        // provisioning -- ditandatangani dengan benar.
        val bodyBytes = request.body
            ?.takeIf { !it.isOneShot() && !it.isDuplex() }
            ?.let { body -> Buffer().also { body.writeTo(it) }.readByteArray() }
            ?: ByteArray(0)

        val nonce = nonceFactory()
        val canonical = requestSigner.canonicalize(
            method = request.method,
            path = request.url.encodedPath,
            deviceId = deviceId,
            timestamp = timestamp,
            nonce = nonce,
            body = bodyBytes,
        )
        val signature = Ed25519RequestSigner.encodeSignature(signer.sign(canonical))

        val signed = request.newBuilder()
            .header("X-Device-Id", deviceId)
            .header("X-Nonce", nonce)
            .header("X-Signature", signature)
            .build()
        return chain.proceed(signed)
    }
}
```

- [ ] **Step 7: Hapus berkas lama**

```bash
git rm signing-core/src/main/kotlin/com/cashup/signing/RequestSigner.kt
git rm signing-core/src/main/kotlin/com/cashup/signing/SigningKeyProvider.kt
git rm signing-core/src/test/kotlin/com/cashup/signing/RequestSignerTest.kt
```

- [ ] **Step 8: Tulis ulang `SigningInterceptorTest.kt`**

Ganti seluruh isi berkas itu:

```kotlin
package com.cashup.signing

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class SigningInterceptorTest {

    private lateinit var server: MockWebServer
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private inner class TestSigner(private val id: String?) : DeviceSigner {
        override fun deviceId(): String? = id
        override fun sign(canonicalBytes: ByteArray): ByteArray =
            Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(canonicalBytes)
                sign()
            }
    }

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    private fun clientFor(signer: DeviceSigner) =
        OkHttpClient.Builder()
            .addInterceptor(SigningInterceptor(signer, nonceFactory = { "nonce-1" }))
            .build()

    private fun post(url: String, body: String) = Request.Builder()
        .url(url)
        .header("X-Timestamp", "2026-09-16T10:30+07:00")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    private fun verifies(path: String, body: String, signatureHeader: String?): Boolean {
        val canonical = Ed25519RequestSigner().canonicalize(
            method = "POST",
            path = path,
            deviceId = "dev-1",
            timestamp = "2026-09-16T10:30+07:00",
            nonce = "nonce-1",
            body = body.toByteArray(),
        )
        return Signature.getInstance("Ed25519").run {
            initVerify(keyPair.public)
            update(canonical)
            verify(Base64.getUrlDecoder().decode(signatureHeader))
        }
    }

    @Test
    fun `adds device id, nonce and a signature the public key verifies`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val body = """{"orderId":"abc"}"""

        clientFor(TestSigner("dev-1"))
            .newCall(post(server.url("/v1/orders/abc/package").toString(), body))
            .execute().close()

        val recorded = server.takeRequest()
        assertEquals("dev-1", recorded.getHeader("X-Device-Id"))
        assertEquals("nonce-1", recorded.getHeader("X-Nonce"))
        assertNotNull(recorded.getHeader("X-Signature"))
        assertTrue(verifies("/v1/orders/abc/package", body, recorded.getHeader("X-Signature")))
    }

    @Test
    fun `throws DeviceNotProvisionedException when there is no device id`() {
        assertThrows(DeviceNotProvisionedException::class.java) {
            clientFor(TestSigner(null))
                .newCall(post(server.url("/v1/x").toString(), "{}"))
                .execute()
        }
    }

    @Test
    fun `throws MissingTimestampException when RequestHeadersInterceptor did not run`() {
        val request = Request.Builder()
            .url(server.url("/v1/x"))
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        assertThrows(MissingTimestampException::class.java) {
            clientFor(TestSigner("dev-1")).newCall(request).execute()
        }
    }

    @Test
    fun `the signed path excludes the query string`() {
        server.enqueue(MockResponse().setResponseCode(200))

        clientFor(TestSigner("dev-1"))
            .newCall(post(server.url("/v1/payments?status=PENDING").toString(), "{}"))
            .execute().close()

        val recorded = server.takeRequest()
        assertTrue(verifies("/v1/payments", "{}", recorded.getHeader("X-Signature")))
    }
}
```

- [ ] **Step 9: Jalankan seluruh tes module**

Run: `./gradlew :signing-core:test --no-daemon`
Expected: PASS, 8 tes — 4 dari `Ed25519RequestSignerTest`, 4 dari `SigningInterceptorTest`.

- [ ] **Step 10: Commit**

```bash
git add signing-core/
git commit -m "feat(signing-core)!: replace ECDSA P-256 with Ed25519

The backend has locked the algorithm: the team's provisioning diagram has
the terminal generating an EDDSA key pair and the backend fetching its
Ed25519 public key from a general-purpose HSM. P-256 was never ours to
keep.

Four things change beyond the algorithm. The canonical string gains
deviceId and drops the query string from the path, the timestamp is the
ISO-8601 X-Timestamp header value rather than milliseconds, and the body
hash is lowercase hex rather than Base64 -- all to match the only format
proven against the real backend.  Signatures encode Base64 URL-safe
without padding.

SigningKeyProvider becomes DeviceSigner, exposing a sign operation
instead of a KeyPair. On Android the Ed25519 private key is a PKCS8 blob
loaded from encrypted storage, used, and dropped; an interface handing
back a KeyPair would keep that material alive longer than necessary.

MissingTimestampException is new and is an IOException for the same
reason DeviceNotProvisionedException is: a non-IOException thrown from an
interceptor on OkHttp's async path kills the process rather than failing
the call."
```

---

## Task 5: `device-sdk-edcsdk` — adapter ke AAR `edc-sdk`

Module Android pertama di repo ini, dan satu-satunya yang boleh mengimpor `com.lib.core.*`.

Logika yang benar-benar perlu diuji di sini adalah **perutean slot**: purpose mana yang dapat slot modul aman vendor dan mana yang jatuh ke vault. Itu dipisahkan di balik `KeyManagerGateway` supaya bisa diuji tanpa hardware. Binder nyata yang memanggil `SDKManager`/`KeyManager` tidak diuji unit — divalidasi manual di terminal fisik.

**Files:**
- Create: `device-sdk-edcsdk/build.gradle.kts`
- Create: `device-sdk-edcsdk/src/main/AndroidManifest.xml`
- Create: `device-sdk-edcsdk/src/main/kotlin/com/cashup/devicesdk/edcsdk/EdcSdkSerialNumberProvider.kt`
- Create: `device-sdk-edcsdk/src/main/kotlin/com/cashup/devicesdk/edcsdk/KeyManagerGateway.kt`
- Create: `device-sdk-edcsdk/src/main/kotlin/com/cashup/devicesdk/edcsdk/EdcSdkTerminalKeyInstaller.kt`
- Create: `device-sdk-edcsdk/src/test/kotlin/com/cashup/devicesdk/edcsdk/EdcSdkTerminalKeyInstallerTest.kt`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `SerialNumberProvider`, `TerminalKeyInstaller`, `TerminalKeyMaterial`, `KeyBacking`, `KeyInstallOutcome`, `TerminalKeyInstallResult` dari Task 2.
- Produces:
  - `class EdcSdkSerialNumberProvider(context: Context) : SerialNumberProvider`
  - `internal interface KeyManagerGateway` dengan `fun hasVendorModule(): Boolean`, `fun writeToVendorModule(ipek: ByteArray, ksn: ByteArray): Boolean`, `fun writeToVaultSlot(slot: Int, ipek: ByteArray, ksn: ByteArray): Boolean`, `fun clearAll()`
  - `class EdcSdkTerminalKeyInstaller` dengan konstruktor publik `(context: Context, vendorSlotPurpose: String = "PIN")` dan konstruktor `internal` `(gateway: KeyManagerGateway, vendorSlotPurpose: String)` untuk tes
  - `const val VAULT_SLOT_BASE = 10`

- [ ] **Step 1: Daftarkan module di `settings.gradle.kts`**

Tambahkan setelah `include(":signing-core")`:

```kotlin
include(":device-sdk-edcsdk")
```

- [ ] **Step 2: Buat `device-sdk-edcsdk/build.gradle.kts`**

```kotlin
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
```

Catatan: module Android memakai JUnit 4, bukan JUnit 5 seperti module Kotlin/JVM — lihat Global Constraints.

- [ ] **Step 3: Buat `device-sdk-edcsdk/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 4: Verifikasi module ter-build sebelum ada kode di dalamnya**

Run: `./gradlew :device-sdk-edcsdk:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL. Kalau gagal dengan `Could not find :core-release_1.0.63:`, berarti AAR belum tersalin (Task 1 Step 1) atau `flatDir` salah tempat (Task 1 Step 5).

- [ ] **Step 5: Tulis tes yang gagal untuk perutean slot**

`device-sdk-edcsdk/src/test/kotlin/com/cashup/devicesdk/edcsdk/EdcSdkTerminalKeyInstallerTest.kt`:

```kotlin
package com.cashup.devicesdk.edcsdk

import com.cashup.devicesdk.KeyBacking
import com.cashup.devicesdk.TerminalKeyInstallResult
import com.cashup.devicesdk.TerminalKeyMaterial
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdcSdkTerminalKeyInstallerTest {

    private class FakeGateway(
        private val vendorPresent: Boolean = true,
        private val failVendorWrite: Boolean = false,
    ) : KeyManagerGateway {
        val vendorWrites = mutableListOf<String>()
        val vaultWrites = mutableListOf<Int>()
        var clearCount = 0

        override fun hasVendorModule(): Boolean = vendorPresent

        override fun writeToVendorModule(ipek: ByteArray, ksn: ByteArray): Boolean {
            if (failVendorWrite) return false
            vendorWrites += ipek.joinToString("") { "%02x".format(it) }
            return true
        }

        override fun writeToVaultSlot(slot: Int, ipek: ByteArray, ksn: ByteArray): Boolean {
            vaultWrites += slot
            return true
        }

        override fun clearAll() {
            clearCount++
        }
    }

    private fun material(purpose: String) = TerminalKeyMaterial(
        purpose = purpose,
        ipek = byteArrayOf(0x11, 0x22),
        ksn = byteArrayOf(0x33),
    )

    private val materials = listOf(material("TRACK"), material("PIN"), material("AMOUNT"), material("EMV"))

    @Test
    fun `the nominated purpose takes the vendor slot and the rest go to the vault`() = runTest {
        val gateway = FakeGateway()
        val installer = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN")

        val result = installer.install(materials)

        assertTrue(result is TerminalKeyInstallResult.Installed)
        val outcomes = (result as TerminalKeyInstallResult.Installed).outcomes
        assertEquals(4, outcomes.size)
        assertEquals(
            KeyBacking.VENDOR_SECURE_MODULE,
            outcomes.single { it.purpose == "PIN" }.backing,
        )
        outcomes.filter { it.purpose != "PIN" }.forEach {
            assertEquals(KeyBacking.TEE_VAULT_ONLY, it.backing)
        }
        assertEquals(1, gateway.vendorWrites.size)
        assertEquals(listOf(10, 11, 12), gateway.vaultWrites)
    }

    @Test
    fun `every purpose falls back to the vault when there is no vendor module`() = runTest {
        val gateway = FakeGateway(vendorPresent = false)
        val installer = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN")

        val result = installer.install(materials)

        val outcomes = (result as TerminalKeyInstallResult.Installed).outcomes
        assertTrue(outcomes.all { it.backing == KeyBacking.TEE_VAULT_ONLY })
        assertEquals(0, gateway.vendorWrites.size)
        assertEquals(listOf(10, 11, 12, 13), gateway.vaultWrites)
    }

    @Test
    fun `a failed vendor write fails the whole install and names the purpose`() = runTest {
        val gateway = FakeGateway(failVendorWrite = true)
        val installer = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN")

        val result = installer.install(materials)

        assertTrue(result is TerminalKeyInstallResult.Failed)
        assertEquals("PIN", (result as TerminalKeyInstallResult.Failed).purpose)
    }

    @Test
    fun `install reports the vendor slot purpose even when it is not listed first`() = runTest {
        val gateway = FakeGateway()
        val installer = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "EMV")

        val result = installer.install(materials)

        val outcomes = (result as TerminalKeyInstallResult.Installed).outcomes
        assertEquals(
            KeyBacking.VENDOR_SECURE_MODULE,
            outcomes.single { it.purpose == "EMV" }.backing,
        )
    }

    @Test
    fun `wipe clears everything`() = runTest {
        val gateway = FakeGateway()

        EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN").wipe()

        assertEquals(1, gateway.clearCount)
    }
}
```

- [ ] **Step 6: Jalankan tes, pastikan gagal**

Run: `./gradlew :device-sdk-edcsdk:testDebugUnitTest --no-daemon`
Expected: FAIL — `Unresolved reference: KeyManagerGateway`.

- [ ] **Step 7: Tulis `KeyManagerGateway.kt`**

```kotlin
package com.cashup.devicesdk.edcsdk

import android.content.Context
import android.os.Build
import com.lib.core.DeviceManager
import com.lib.core.KeyManager
import java.io.File

/**
 * Batas tipis di atas `KeyManager` milik `edc-sdk`, ada semata supaya logika
 * perutean slot di [EdcSdkTerminalKeyInstaller] bisa diuji tanpa hardware.
 *
 * Dua metode tulis di sini memang berbeda tujuan, bukan duplikat:
 *
 * - [writeToVendorModule] memakai `KeyManager.writeIPEK(ipek, ksn)` — menulis
 *   ke modul aman vendor lalu mencerminkannya ke vault, dan hanya mengembalikan
 *   true kalau modul vendor mengonfirmasi. Ini jalur yang benar, tapi vendor
 *   hanya punya SATU slot DUKPT (`keyIndex` tetap per vendor).
 * - [writeToVaultSlot] memakai `KeyManager.writeIPEK(slot, ipek, ksn)` — sudah
 *   diperiksa di source `edc-sdk`: varian ini menulis HANYA ke vault dan
 *   melewati modul vendor sepenuhnya.
 *
 * Lihat spec §4.3.
 */
internal interface KeyManagerGateway {
    fun hasVendorModule(): Boolean
    fun writeToVendorModule(ipek: ByteArray, ksn: ByteArray): Boolean
    fun writeToVaultSlot(slot: Int, ipek: ByteArray, ksn: ByteArray): Boolean
    fun clearAll()
}

internal class RealKeyManagerGateway(context: Context) : KeyManagerGateway {

    private val appContext = context.applicationContext
    private val keyManager by lazy { KeyManager.getInstance(appContext) }

    override fun hasVendorModule(): Boolean = DeviceManager.systemKey != null

    override fun writeToVendorModule(ipek: ByteArray, ksn: ByteArray): Boolean =
        keyManager.writeIPEK(ipek, ksn)

    override fun writeToVaultSlot(slot: Int, ipek: ByteArray, ksn: ByteArray): Boolean =
        keyManager.writeIPEK(slot, ipek, ksn)

    /**
     * `DuktpVaultCompat` tidak mengekspos API hapus apa pun — hanya store/load
     * (dikonfirmasi dari isi `core-release_1.0.63.aar`). Rollback atomic
     * menuntutnya, jadi sementara ini menghapus berkas SharedPreferences-nya
     * langsung berdasarkan nama.
     *
     * Ini kopling rapuh ke detail internal `edc-sdk` dan dicatat sebagai utang
     * di spec §4.3: begitu `edc-sdk` menyediakan `clear()` resmi, ganti ke sana.
     */
    override fun clearAll() {
        VAULT_PREFS.forEach { deletePrefs(it) }
    }

    private fun deletePrefs(name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.deleteSharedPreferences(name)
        } else {
            // deleteSharedPreferences baru ada di API 24; minSdk kita 23.
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            File(appContext.applicationInfo.dataDir, "shared_prefs/$name.xml").delete()
        }
    }

    private companion object {
        val VAULT_PREFS = listOf("duktp.vault", "KeyManager")
    }
}
```

- [ ] **Step 8: Tulis `EdcSdkTerminalKeyInstaller.kt`**

```kotlin
package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.KeyBacking
import com.cashup.devicesdk.KeyInstallOutcome
import com.cashup.devicesdk.TerminalKeyInstallResult
import com.cashup.devicesdk.TerminalKeyInstaller
import com.cashup.devicesdk.TerminalKeyMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Slot vault untuk purpose yang tidak kebagian modul vendor. Dimulai dari 10
 * supaya tidak bertabrakan dengan `keyIndex` milik vendor mana pun (PAX 3,
 * Sunmi 2, dan sejenisnya semuanya satu digit).
 */
internal const val VAULT_SLOT_BASE = 10

/**
 * Memasang key DUKPT lewat `KeyManager` milik `edc-sdk`.
 *
 * Modul aman vendor hanya menampung SATU key set DUKPT — `BaseSystemKey`
 * bahkan tidak punya parameter slot. Jadi tepat satu purpose, [vendorSlotPurpose],
 * mendapat perlindungan hardware; sisanya masuk vault ter-enkripsi Keystore.
 * Keputusan siapa yang dapat slot itu ada di satu tempat ini, tidak tersebar.
 *
 * Hasilnya dilaporkan per purpose lewat [KeyInstallOutcome] supaya pemanggil
 * bisa menampilkan dan mencatat mana yang hardware-backed — degradasi ini tidak
 * boleh diam (spec §4.3).
 */
class EdcSdkTerminalKeyInstaller internal constructor(
    private val gateway: KeyManagerGateway,
    private val vendorSlotPurpose: String,
) : TerminalKeyInstaller {

    constructor(context: Context, vendorSlotPurpose: String = "PIN") :
        this(RealKeyManagerGateway(context), vendorSlotPurpose)

    override suspend fun install(
        materials: List<TerminalKeyMaterial>,
    ): TerminalKeyInstallResult = withContext(Dispatchers.IO) {
        val vendorAvailable = gateway.hasVendorModule()
        val outcomes = mutableListOf<KeyInstallOutcome>()
        var nextVaultSlot = VAULT_SLOT_BASE

        for (material in materials) {
            val takesVendorSlot = vendorAvailable && material.purpose == vendorSlotPurpose
            val ok = if (takesVendorSlot) {
                gateway.writeToVendorModule(material.ipek, material.ksn)
            } else {
                gateway.writeToVaultSlot(nextVaultSlot++, material.ipek, material.ksn)
            }

            if (!ok) {
                return@withContext TerminalKeyInstallResult.Failed(
                    purpose = material.purpose,
                    reason = if (takesVendorSlot) {
                        "modul aman vendor menolak key"
                    } else {
                        "vault menolak key"
                    },
                )
            }

            outcomes += KeyInstallOutcome(
                purpose = material.purpose,
                backing = if (takesVendorSlot) {
                    KeyBacking.VENDOR_SECURE_MODULE
                } else {
                    KeyBacking.TEE_VAULT_ONLY
                },
            )
        }

        TerminalKeyInstallResult.Installed(outcomes)
    }

    override suspend fun wipe() = withContext(Dispatchers.IO) {
        gateway.clearAll()
    }
}
```

- [ ] **Step 9: Jalankan tes, pastikan lolos**

Run: `./gradlew :device-sdk-edcsdk:testDebugUnitTest --no-daemon`
Expected: PASS, 5 tes.

- [ ] **Step 10: Tulis `EdcSdkSerialNumberProvider.kt`**

```kotlin
package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.SerialNumberProvider
import com.lib.core.SDKManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Nomor seri hardware lewat `SDKManager` milik `edc-sdk`.
 *
 * `SDKManager.autoDetectDevice` mencocokkan `Build.BRAND` ke module vendor yang
 * terpasang lalu melapor lewat callback — itulah kenapa `DeviceSdkRegistry`
 * (pencocokan prefix `Build.MODEL`) dihapus di Task 2; pekerjaan itu sudah
 * dilakukan di sini, dengan sinyal yang lebih baik.
 *
 * Mengembalikan `null` kalau tidak ada module vendor yang cocok dengan hardware
 * ini. Provisioning tidak boleh dilanjutkan dalam keadaan itu: nomor seri adalah
 * identitas device di backend (spec §3.3).
 *
 * Tidak ada tes unit untuk kelas ini — `autoDetectDevice` menyentuh binder
 * Android dan hardware vendor sungguhan. Divalidasi manual di terminal fisik.
 */
class EdcSdkSerialNumberProvider(context: Context) : SerialNumberProvider {

    private val appContext = context.applicationContext

    override suspend fun serialNumber(): String? = withContext(Dispatchers.IO) {
        val connected = suspendCancellableCoroutine { continuation ->
            SDKManager.autoDetectDevice(appContext) { ok ->
                if (continuation.isActive) continuation.resume(ok)
            }
        }
        if (!connected) return@withContext null
        runCatching { SDKManager.requireHelper().serialNumber }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
```

- [ ] **Step 11: Verifikasi seluruh module ter-build dan tesnya lolos**

Run: `./gradlew :device-sdk-edcsdk:assembleDebug :device-sdk-edcsdk:testDebugUnitTest --no-daemon`
Expected: BUILD SUCCESSFUL, 5 tes lolos.

- [ ] **Step 12: Commit**

```bash
git add settings.gradle.kts device-sdk-edcsdk/
git commit -m "feat(device-sdk-edcsdk): adapt edc-sdk behind the device-sdk-api contracts

The only module allowed to import com.lib.core.*. Vendor AARs are
implementation-scoped so none of those types leak past this boundary.

Slot routing is the part worth testing, so it sits behind an internal
KeyManagerGateway seam and is covered by five unit tests with a fake. The
real binder that calls SDKManager and KeyManager is not unit tested --
autoDetectDevice touches Android binder and vendor hardware, and is
validated by hand on a physical terminal.

The routing itself follows a limit in edc-sdk confirmed by reading both
the AAR and its source: BaseSystemKey.writeIPEK takes no slot parameter,
so the vendor secure module holds exactly one DUKPT key set, and
KeyManager's three-argument overload writes only to the vault, bypassing
the vendor entirely. Exactly one nominated purpose therefore gets hardware
protection and the rest land in the Keystore vault. The installer reports
which is which rather than letting that degradation pass silently.

wipe deletes the vault SharedPreferences files by name because
DuktpVaultCompat exposes no delete API at all. That is fragile coupling to
edc-sdk internals and is recorded as debt in spec 4.3."
```

---

## Task 6: Amplop respons backend di `common-core`

Backend membungkus setiap respons dalam `{data, error, meta}`, dan amplop itu berisi informasi paling berguna justru di respons **gagal** (401/403/409/410/422/503). `safeApiCall` yang ada tidak tahu bentuk itu: pada non-2xx ia memasukkan `errorBody` mentah sebagai `message` dan memberi kode sintetis `HTTP_409`, sehingga kode error backend yang sebenarnya hilang.

Tempatnya di `common-core`, bukan `provisioning-core`, karena amplop ini konvensi API backend — CDCP dan QRIS akan memakai yang sama.

**Files:**
- Create: `common-core/src/main/kotlin/com/cashup/common/network/ApiEnvelope.kt`
- Create: `common-core/src/main/kotlin/com/cashup/common/network/SafeEnvelopeCall.kt`
- Create: `common-core/src/test/kotlin/com/cashup/common/network/SafeEnvelopeCallTest.kt`

**Interfaces:**
- Consumes: `ApiResult`, `ApiError` yang sudah ada.
- Produces:
  - `data class ApiEnvelope<T>(val data: T?, val error: EnvelopeError?, val meta: EnvelopeMeta?)`
  - `data class EnvelopeError(val code: String, val message: String)`
  - `data class EnvelopeMeta(val correlationId: String?)`
  - `suspend fun <T> safeEnvelopeCall(gson: Gson = Gson(), block: suspend () -> Response<ApiEnvelope<T>>): ApiResult<T>`

- [ ] **Step 1: Tulis tes yang gagal**

`common-core/src/test/kotlin/com/cashup/common/network/SafeEnvelopeCallTest.kt`:

```kotlin
package com.cashup.common.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

data class Payload(val orderId: String)

interface EnvelopeTestApi {
    @GET("/probe")
    suspend fun probe(): Response<ApiEnvelope<Payload>>
}

class SafeEnvelopeCallTest {

    private lateinit var server: MockWebServer
    private lateinit var api: EnvelopeTestApi

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EnvelopeTestApi::class.java)
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `unwraps data on success`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"orderId":"o-1"},"meta":{"correlationId":"c-1"}}""")
        )

        val result = safeEnvelopeCall { api.probe() }

        assertInstanceOf(ApiResult.Success::class.java, result)
        assertEquals("o-1", (result as ApiResult.Success).data.orderId)
    }

    @Test
    fun `reads the backend error code out of a non-2xx envelope`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(410)
                .setBody("""{"error":{"code":"PROVISIONING_TOKEN_INVALID","message":"Kode QR kedaluwarsa"}}""")
        )

        val result = safeEnvelopeCall { api.probe() }

        assertInstanceOf(ApiResult.Failure::class.java, result)
        val error = (result as ApiResult.Failure).error
        assertEquals("PROVISIONING_TOKEN_INVALID", error.code)
        assertEquals("Kode QR kedaluwarsa", error.message)
        assertEquals(410, error.httpStatus)
    }

    @Test
    fun `reads an error envelope that arrives with a 2xx status`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"error":{"code":"TERMINAL_INACTIVE","message":"Device tidak aktif"}}""")
        )

        val result = safeEnvelopeCall { api.probe() }

        assertEquals("TERMINAL_INACTIVE", (result as ApiResult.Failure).error.code)
    }

    @Test
    fun `falls back to a readable code when the body is not JSON`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))

        val result = safeEnvelopeCall { api.probe() }

        val error = (result as ApiResult.Failure).error
        assertEquals("RESPONSE_UNREADABLE", error.code)
        assertEquals(502, error.httpStatus)
    }

    @Test
    fun `flags a 2xx response that carries neither data nor error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"meta":{"correlationId":"c-9"}}"""))

        val result = safeEnvelopeCall { api.probe() }

        assertEquals("RESPONSE_UNEXPECTED", (result as ApiResult.Failure).error.code)
    }

    @Test
    fun `maps a transport failure to NETWORK`() = runBlocking {
        server.shutdown()

        val result = safeEnvelopeCall { api.probe() }

        assertEquals("NETWORK", (result as ApiResult.Failure).error.code)
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `./gradlew :common-core:test --tests "com.cashup.common.network.SafeEnvelopeCallTest" --no-daemon`
Expected: FAIL — `Unresolved reference: ApiEnvelope`.

- [ ] **Step 3: Tambahkan dependensi coroutines untuk tes**

Di `common-core/build.gradle.kts`, di dalam `dependencies`:

```kotlin
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

- [ ] **Step 4: Tulis `ApiEnvelope.kt`**

```kotlin
package com.cashup.common.network

/**
 * Amplop respons backend: `{ "data": ..., "error": ..., "meta": ... }`.
 *
 * Selalu bercabang pada [EnvelopeError.code], **tidak pernah** pada
 * [EnvelopeError.message] — `message` berbahasa Indonesia, ditujukan untuk
 * manusia, dan boleh berubah kapan saja.
 */
data class ApiEnvelope<T>(
    val data: T? = null,
    val error: EnvelopeError? = null,
    val meta: EnvelopeMeta? = null,
)

data class EnvelopeError(
    val code: String,
    val message: String,
)

data class EnvelopeMeta(
    val correlationId: String? = null,
)
```

- [ ] **Step 5: Tulis `SafeEnvelopeCall.kt`**

```kotlin
package com.cashup.common.network

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import retrofit2.Response
import java.io.IOException

/**
 * Versi [safeApiCall] yang sadar amplop backend.
 *
 * Perbedaan yang menentukan: amplop di-parse untuk respons 2xx **maupun**
 * non-2xx. Backend mengirim `error.code` yang berguna justru pada 401/403/409/
 * 410/422/503, dan Retrofit tidak mem-parsing body pada respons gagal — jadi
 * `errorBody` harus dibaca manual di sini. Tanpa itu, kode error backend hilang
 * dan tergantikan kode sintetis seperti `HTTP_410`, yang tidak bisa dipetakan
 * ke pesan operator.
 *
 * Amplop error yang datang dengan status 2xx juga diperlakukan sebagai
 * kegagalan: `data` dan `error` saling meniadakan, dan kepercayaan pada status
 * HTTP saja pernah jadi sumber bug di app lama.
 */
suspend fun <T> safeEnvelopeCall(
    gson: Gson = Gson(),
    block: suspend () -> Response<ApiEnvelope<T>>,
): ApiResult<T> = try {
    val response = block()
    val status = response.code()

    if (response.isSuccessful) {
        val envelope = response.body()
        val error = envelope?.error
        val data = envelope?.data
        when {
            error != null -> ApiResult.Failure(ApiError(error.code, error.message, status))
            data != null -> ApiResult.Success(data)
            else -> ApiResult.Failure(
                ApiError("RESPONSE_UNEXPECTED", "Respons tanpa data maupun error", status)
            )
        }
    } else {
        val raw = response.errorBody()?.string()
        val parsed = raw?.let {
            try {
                gson.fromJson(it, ApiEnvelope::class.java)?.error
            } catch (_: JsonSyntaxException) {
                null
            }
        }
        if (parsed != null) {
            ApiResult.Failure(ApiError(parsed.code, parsed.message, status))
        } else {
            ApiResult.Failure(
                ApiError(
                    code = "RESPONSE_UNREADABLE",
                    message = "Respons bukan JSON (HTTP $status): ${raw?.take(300) ?: "<body kosong>"}",
                    httpStatus = status,
                )
            )
        }
    }
} catch (e: IOException) {
    ApiResult.Failure(ApiError("NETWORK", e.message ?: "Network error", cause = e))
} catch (e: Exception) {
    ApiResult.Failure(ApiError("UNKNOWN", e.message ?: "Unknown error", cause = e))
}
```

- [ ] **Step 6: Jalankan tes, pastikan lolos**

Run: `./gradlew :common-core:test --tests "com.cashup.common.network.SafeEnvelopeCallTest" --no-daemon`
Expected: PASS, 6 tes.

- [ ] **Step 7: Jalankan seluruh tes module, pastikan tidak ada regresi**

Run: `./gradlew :common-core:test --no-daemon`
Expected: PASS semuanya.

- [ ] **Step 8: Commit**

```bash
git add common-core/
git commit -m "feat(common-core): add envelope-aware safeEnvelopeCall

The backend wraps every response in {data, error, meta} and puts its most
useful information in the error branch of non-2xx responses. Retrofit does
not parse a body on a failed response, so safeApiCall was replacing real
backend codes like PROVISIONING_TOKEN_INVALID with synthetic ones like
HTTP_410 -- codes nothing can map to an operator message.

safeEnvelopeCall reads the envelope on both paths. An error envelope
arriving with a 2xx status is treated as a failure too: data and error are
mutually exclusive, and trusting the HTTP status alone has burned this
codebase before.

It lives in common-core rather than provisioning-core because the envelope
is the backend's API convention; CDCP and QRIS will unwrap the same shape."
```

---

## Task 7: `provisioning-core` — module, DTO, dan lapisan HTTP

Membuat module dan seluruh permukaan jaringannya. Kripto dan orkestrasi menyusul di Task 8 dan 9.

Dua client Retrofit dibangun, bukan satu: `qr-redeem` **tidak boleh** melewati `SigningInterceptor` karena saat itu backend belum mengenal public key device. Memisahkannya di level client jauh lebih aman daripada mengandalkan pengecualian per-endpoint di dalam satu interceptor.

**Files:**
- Create: `provisioning-core/build.gradle.kts`
- Create: `provisioning-core/src/main/AndroidManifest.xml`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/remote/ProvisioningDtos.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/remote/ProvisioningApi.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/remote/ProvisioningHttp.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/ProvisioningRepository.kt`
- Create: `provisioning-core/src/test/kotlin/com/cashup/provisioning/data/ProvisioningRepositoryTest.kt`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `ApiResult`, `ApiError`, `ApiEnvelope`, `safeEnvelopeCall`, `RequestHeadersInterceptor` (Task 3, 6); `DeviceSigner`, `SigningInterceptor` (Task 4).
- Produces:
  - `data class QrRedeemRequest(challengeCode, serialNumber, rsaPublicKey, eddsaPublicKey)`
  - `data class QrRedeemResponse(orderId, activationToken)`
  - `data class KeyPackageRequest(orderId, activationToken)`
  - `data class KeyPackageResponse(orderId, wrappedPackageKey, appEddsaPublicKey)`
  - `data class ActivateRequest(activationToken, keyCheckValues: Map<String, String>)`
  - `data class ActivateResponse(status)`
  - `class ProvisioningRepository` dengan `suspend fun redeem(QrRedeemRequest): ApiResult<QrRedeemResponse>`, `suspend fun downloadKeyPackage(KeyPackageRequest): ApiResult<KeyPackageResponse>`, `suspend fun activate(orderId: String, ActivateRequest): ApiResult<ActivateResponse>`
  - `object ProvisioningHttp` dengan `const val TIMEOUT_SECONDS = 65L` dan `fun create(baseUrl: String, deviceSigner: DeviceSigner, debugLogging: Boolean = false): ProvisioningRepository`

- [ ] **Step 1: Daftarkan module**

Di `settings.gradle.kts`, tambahkan:

```kotlin
include(":provisioning-core")
```

- [ ] **Step 2: Buat `provisioning-core/build.gradle.kts`**

```kotlin
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
```

- [ ] **Step 3: Buat `provisioning-core/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 4: Tulis `ProvisioningDtos.kt`**

```kotlin
package com.cashup.provisioning.data.remote

/**
 * Bentuk body mengikuti diagram provisioning tim (`Provisioning.drawio`), yang
 * berwenang atas ini; nama path mengikuti `corepayment`. Di mana keduanya
 * berbeda, spec §1 menetapkan siapa yang menang, dan §8 mencatat apa yang masih
 * perlu dikonfirmasi backend.
 */

/**
 * Satu-satunya request yang TIDAK ditandatangani: backend belum mengenal public
 * key device, karena dua kunci itu justru baru dikirim di sini.
 *
 * [rsaPublicKey] adalah SPKI X.509 Base64, dipakai backend untuk membungkus
 * paket key. [eddsaPublicKey] adalah Ed25519 raw 32 byte Base64, dipakai backend
 * untuk memverifikasi tanda tangan setiap request sesudah ini.
 */
data class QrRedeemRequest(
    val challengeCode: String,
    val serialNumber: String,
    val rsaPublicKey: String,
    val eddsaPublicKey: String,
)

data class QrRedeemResponse(
    val orderId: String,
    val activationToken: String,
)

/**
 * `orderId` muncul di path DAN di body — konsekuensi dari memakai path
 * `corepayment` dengan body diagram tim. Tercatat di spec §8 item 5 sebagai
 * hal yang perlu dikonfirmasi backend.
 */
data class KeyPackageRequest(
    val orderId: String,
    val activationToken: String,
)

/**
 * [wrappedPackageKey] membawa material DUKPT terbungkus RSA.
 *
 * Bentuknya belum dikonfirmasi backend (spec §8 item 1): RSA-2048 OAEP-SHA256
 * hanya memuat 190 byte, jadi kalau paketnya JSON+base64 untuk empat pasang
 * IPEK/KSN, backend harus memakai skema hibrida seperti `edc-mobile` dan DTO ini
 * bertambah field. Kalau itu terjadi, yang berubah hanya berkas ini dan
 * `PackageUnwrapper` di Task 8.
 *
 * [appEddsaPublicKey] adalah gema public key yang dikirim saat redeem, plain.
 */
data class KeyPackageResponse(
    val orderId: String,
    val wrappedPackageKey: String,
    val appEddsaPublicKey: String? = null,
)

/**
 * [keyCheckValues] dikunci per nama purpose yang datang di paket — tidak ada
 * daftar purpose yang di-hardcode di mana pun (spec §7.2).
 */
data class ActivateRequest(
    val activationToken: String,
    val keyCheckValues: Map<String, String>,
)

data class ActivateResponse(
    val status: String,
)
```

- [ ] **Step 5: Tulis `ProvisioningApi.kt`**

```kotlin
package com.cashup.provisioning.data.remote

import com.cashup.common.network.ApiEnvelope
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Setiap method mengembalikan `Response<ApiEnvelope<T>>`, bukan `T` polos.
 * Retrofit tidak mem-parsing body pada respons gagal kalau tipe return-nya
 * bukan `Response<T>`, dan justru di sanalah `error.code` backend berada —
 * lihat `safeEnvelopeCall`.
 */
internal interface ProvisioningApi {

    @POST("v1/terminal-key-provisioning/qr-redeem")
    suspend fun redeem(
        @Body body: QrRedeemRequest,
    ): Response<ApiEnvelope<QrRedeemResponse>>

    @POST("v1/terminal-key-provisioning/orders/{orderId}/package")
    suspend fun keyPackage(
        @Path("orderId") orderId: String,
        @Body body: KeyPackageRequest,
    ): Response<ApiEnvelope<KeyPackageResponse>>

    @POST("v1/terminal-key-provisioning/orders/{orderId}/activate")
    suspend fun activate(
        @Path("orderId") orderId: String,
        @Body body: ActivateRequest,
    ): Response<ApiEnvelope<ActivateResponse>>
}
```

- [ ] **Step 6: Tulis `ProvisioningHttp.kt`**

```kotlin
package com.cashup.provisioning.data.remote

import com.cashup.common.network.RequestHeadersInterceptor
import com.cashup.provisioning.data.ProvisioningRepository
import com.cashup.signing.DeviceSigner
import com.cashup.signing.SigningInterceptor
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Membangun dua client, bukan satu.
 *
 * `qr-redeem` harus TIDAK ditandatangani — backend belum mengenal public key
 * device pada titik itu. Memisahkannya di level client lebih aman daripada
 * mengecualikan satu endpoint di dalam satu interceptor, karena pengecualian
 * berbasis path diam-diam rusak begitu path berubah.
 */
object ProvisioningHttp {

    /**
     * 65 detik, jauh di atas default 15 detik milik `RetrofitFactory`.
     * `/package` menyentuh Payment HSM dan General Purpose HSM di sisi backend,
     * dan `edc-mobile` sudah menetapkan angka ini terhadap backend yang sama.
     * Default bersama sengaja TIDAK dinaikkan — itu akan memperlambat deteksi
     * kegagalan untuk setiap pemakai lain.
     */
    const val TIMEOUT_SECONDS = 65L

    fun create(
        baseUrl: String,
        deviceSigner: DeviceSigner,
        debugLogging: Boolean = false,
    ): ProvisioningRepository {
        val gson = Gson()
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        fun retrofit(client: OkHttpClient) = Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ProvisioningApi::class.java)

        return ProvisioningRepository(
            unsigned = retrofit(clientBuilder(debugLogging).build()),
            signed = retrofit(
                clientBuilder(debugLogging)
                    // SigningInterceptor membaca X-Timestamp yang ditulis
                    // RequestHeadersInterceptor, jadi urutan ini mengikat.
                    .addInterceptor(SigningInterceptor(deviceSigner))
                    .build()
            ),
            gson = gson,
        )
    }

    private fun clientBuilder(debugLogging: Boolean) = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(RequestHeadersInterceptor())
        .apply {
            // Level BODY mencetak wrappedPackageKey dan seluruh keyCheckValues
            // ke logcat. Hanya untuk build debug; rilis mentok di BASIC.
            addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (debugLogging) {
                        HttpLoggingInterceptor.Level.BODY
                    } else {
                        HttpLoggingInterceptor.Level.BASIC
                    }
                    redactHeader("X-Signature")
                }
            )
        }
}
```

- [ ] **Step 7: Tulis tes yang gagal untuk repository**

`provisioning-core/src/test/kotlin/com/cashup/provisioning/data/ProvisioningRepositoryTest.kt`:

```kotlin
package com.cashup.provisioning.data

import com.cashup.common.network.ApiResult
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.ProvisioningHttp
import com.cashup.provisioning.data.remote.QrRedeemRequest
import com.cashup.signing.DeviceSigner
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProvisioningRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ProvisioningRepository

    private val signer = object : DeviceSigner {
        override fun deviceId(): String = "PAX-A920-0012938"
        override fun sign(canonicalBytes: ByteArray): ByteArray = ByteArray(64) { 7 }
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        repository = ProvisioningHttp.create(server.url("/").toString(), signer)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `redeem posts to the agreed path and is not signed`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"orderId":"o-1","activationToken":"tok-1"}}"""
            )
        )

        val result = repository.redeem(
            QrRedeemRequest("ABCD-1234", "PAX-A920-0012938", "rsa-spki", "eddsa-raw")
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/terminal-key-provisioning/qr-redeem", recorded.path)
        assertNotNull(recorded.getHeader("X-Timestamp"))
        assertNotNull(recorded.getHeader("X-Correlation-Id"))
        assertNull("qr-redeem must not be signed", recorded.getHeader("X-Signature"))

        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""challengeCode":"ABCD-1234""""))
        assertTrue(body.contains(""""eddsaPublicKey":"eddsa-raw""""))

        assertEquals("o-1", (result as ApiResult.Success).data.orderId)
    }

    @Test
    fun `downloadKeyPackage posts to the order path and is signed`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"orderId":"o-1","wrappedPackageKey":"d3JhcHBlZA"}}"""
            )
        )

        val result = repository.downloadKeyPackage(KeyPackageRequest("o-1", "tok-1"))

        val recorded = server.takeRequest()
        assertEquals("/v1/terminal-key-provisioning/orders/o-1/package", recorded.path)
        assertEquals("PAX-A920-0012938", recorded.getHeader("X-Device-Id"))
        assertNotNull(recorded.getHeader("X-Signature"))
        assertNotNull(recorded.getHeader("X-Nonce"))

        assertEquals("d3JhcHBlZA", (result as ApiResult.Success).data.wrappedPackageKey)
    }

    @Test
    fun `activate sends the key check values it was given`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":{"status":"ACTIVE"}}""")
        )

        val result = repository.activate(
            "o-1",
            ActivateRequest("tok-1", mapOf("PIN" to "A1B2C3", "TRACK" to "D4E5F6")),
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/terminal-key-provisioning/orders/o-1/activate", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""PIN":"A1B2C3""""))
        assertTrue(body.contains(""""TRACK":"D4E5F6""""))

        assertEquals("ACTIVE", (result as ApiResult.Success).data.status)
    }

    @Test
    fun `a backend error code survives to the caller`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(410).setBody(
                """{"error":{"code":"PROVISIONING_TOKEN_INVALID","message":"Kode QR kedaluwarsa"}}"""
            )
        )

        val result = repository.redeem(QrRedeemRequest("X", "S", "r", "e"))

        assertEquals("PROVISIONING_TOKEN_INVALID", (result as ApiResult.Failure).error.code)
    }
}
```

- [ ] **Step 8: Jalankan tes, pastikan gagal**

Run: `./gradlew :provisioning-core:testDebugUnitTest --no-daemon`
Expected: FAIL — `Unresolved reference: ProvisioningRepository`.

- [ ] **Step 9: Tulis `ProvisioningRepository.kt`**

```kotlin
package com.cashup.provisioning.data

import com.cashup.common.network.ApiResult
import com.cashup.common.network.safeEnvelopeCall
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.ActivateResponse
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.cashup.provisioning.data.remote.ProvisioningApi
import com.cashup.provisioning.data.remote.QrRedeemRequest
import com.cashup.provisioning.data.remote.QrRedeemResponse
import com.google.gson.Gson

/**
 * Tiga panggilan ceremony provisioning, dipetakan ke [ApiResult].
 *
 * Dua client dipisah dengan sengaja: [unsigned] hanya untuk `qr-redeem`, yang
 * tidak boleh ditandatangani karena backend belum mengenal public key device
 * pada titik itu. Salah pakai di sini akan tampak sebagai penolakan tanda
 * tangan dari backend, bukan sebagai bug lokal — karena itu pemilihannya
 * dikunci di kelas ini, bukan diserahkan ke pemanggil.
 */
class ProvisioningRepository internal constructor(
    private val unsigned: ProvisioningApi,
    private val signed: ProvisioningApi,
    private val gson: Gson = Gson(),
) {

    suspend fun redeem(request: QrRedeemRequest): ApiResult<QrRedeemResponse> =
        safeEnvelopeCall(gson) { unsigned.redeem(request) }

    suspend fun downloadKeyPackage(request: KeyPackageRequest): ApiResult<KeyPackageResponse> =
        safeEnvelopeCall(gson) { signed.keyPackage(request.orderId, request) }

    suspend fun activate(orderId: String, request: ActivateRequest): ApiResult<ActivateResponse> =
        safeEnvelopeCall(gson) { signed.activate(orderId, request) }
}
```

- [ ] **Step 10: Jalankan tes, pastikan lolos**

Run: `./gradlew :provisioning-core:testDebugUnitTest --no-daemon`
Expected: PASS, 4 tes.

- [ ] **Step 11: Commit**

```bash
git add settings.gradle.kts provisioning-core/
git commit -m "feat(provisioning-core): add the module and its HTTP layer

Paths come from corepayment, body shapes from the team's provisioning
diagram, per the authority order in spec section 1.

ProvisioningHttp builds two Retrofit clients rather than one. qr-redeem
must go out unsigned -- the backend does not know the device public key
yet, since that request is what delivers it -- and separating that at the
client is safer than excluding one endpoint inside a single interceptor,
where a path change would silently reintroduce the signature.

The read timeout is 65s, matching what edc-mobile established against
this backend, because the package call reaches the payment and
general-purpose HSMs. RetrofitFactory's shared 15s default is left alone
so this does not slow failure detection for every other caller.

HTTP logging defaults to BASIC with X-Signature redacted; BODY would put
the wrapped key package and every key check value into logcat."
```

---

## Task 8: KCV, provider BouncyCastle, dan pembongkar paket

Tiga hal yang seluruhnya bisa diuji di JVM karena tidak menyentuh Android Keystore. Operasi RSA disembunyikan di balik `RsaUnwrapper` supaya bagian yang menguraikan dan memvalidasi paket bisa diuji tanpa hardware; implementasi nyatanya menyusul di Task 9.

**Files:**
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/crypto/BcProvider.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/crypto/Kcv.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/crypto/RsaUnwrapper.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/crypto/PackageUnwrapper.kt`
- Create: `provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/KcvTest.kt`
- Create: `provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/PackageUnwrapperTest.kt`

**Interfaces:**
- Consumes: `TerminalKeyMaterial` (Task 2).
- Produces:
  - `object BcProvider` dengan `const val NAME = "BC"` dan `fun ensureInstalled()`
  - `fun keyCheckValue(key: ByteArray): String` — 6 hex huruf besar
  - `fun interface RsaUnwrapper { fun unwrap(wrapped: ByteArray): ByteArray }`
  - `class PackageUnwrapper(unwrapper: RsaUnwrapper, gson: Gson = Gson())` dengan `fun unwrap(wrappedPackageKeyBase64: String): List<TerminalKeyMaterial>`
  - `class PackageIntegrityException(message: String) : Exception`

- [ ] **Step 1: Tulis `BcProvider.kt`**

```kotlin
package com.cashup.provisioning.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Memasang BouncyCastle sekali, dipakai HANYA lewat penyebutan nama eksplisit.
 *
 * Dua jebakan di sini sudah pernah dibayar mahal di `edc-mobile`; keduanya
 * gagal dengan gejala yang menyesatkan, jadi jangan disederhanakan.
 *
 * **Satu: provider bawaan harus dicopot dulu.** Android sudah menyertakan
 * provider bernama `"BC"` sejak lama, tapi versi yang sengaja dipotong Google
 * dan tidak punya banyak algoritma yang kita butuhkan. Akibatnya
 * `Security.getProvider("BC") == null` SELALU false di Android, sehingga pola
 * lazim `if (provider == null) addProvider(...)` tidak pernah benar-benar
 * memasang BC lengkap — app diam-diam memakai versi terpotong dan gagal dengan
 * `NoSuchAlgorithmException`. Karena itu [Security.removeProvider] dipanggil
 * lebih dulu, tanpa syarat.
 *
 * **Dua: prioritasnya harus PALING RENDAH.** `insertProviderAt(bc, 1)` pernah
 * dipakai dan justru merusak jalur lain: setiap `Cipher.getInstance(...)` yang
 * tidak menyebut nama provider ikut dialihkan ke BC, termasuk RSA-OAEP, yang
 * lalu gagal dengan `InvalidCipherTextException: unable to decrypt block` —
 * pesan khas BC yang terlihat seperti masalah data, bukan masalah provider.
 * [Security.addProvider] menaruhnya di urutan terakhir: tersedia kalau dipanggil
 * dengan nama [NAME], tidak mengambil alih resolusi siapa pun.
 */
object BcProvider {

    const val NAME = "BC"

    @Volatile
    private var installed = false

    fun ensureInstalled() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            Security.removeProvider(NAME)
            Security.addProvider(BouncyCastleProvider())
            installed = true
        }
    }
}
```

- [ ] **Step 2: Tulis tes yang gagal untuk KCV**

`provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/KcvTest.kt`:

```kotlin
package com.cashup.provisioning.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KcvTest {

    private fun key16() = byteArrayOf(
        0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
        0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0x98.toByte(), 0x76, 0x54, 0x32, 0x10,
    )

    @Test
    fun `returns six uppercase hex characters`() {
        val kcv = keyCheckValue(key16())

        assertEquals(6, kcv.length)
        assertTrue(kcv, kcv.all { it in "0123456789ABCDEF" })
    }

    @Test
    fun `a 16-byte key gives the same KCV as its K1K2K1 expansion`() {
        val double = key16()
        val triple = key16() + key16().copyOfRange(0, 8)

        // Mengunci aturan perluasan. Kalau kelak diubah jadi K1K2K2 atau tidak
        // diperluas sama sekali, tes ini yang jatuh -- bukan bank yang menolak
        // transaksi dengan response code 81 berbulan-bulan kemudian.
        assertEquals(keyCheckValue(triple), keyCheckValue(double))
    }

    @Test
    fun `different keys give different check values`() {
        val other = key16().also { it[0] = 0x02 }

        assertNotEquals(keyCheckValue(key16()), keyCheckValue(other))
    }

    @Test
    fun `the caller's key array is zeroed afterwards`() {
        val key = key16()

        keyCheckValue(key)

        assertEquals(0, key.count { it != 0.toByte() })
    }
}
```

- [ ] **Step 3: Jalankan tes, pastikan gagal**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*KcvTest" --no-daemon`
Expected: FAIL — `Unresolved reference: keyCheckValue`.

- [ ] **Step 4: Tulis `Kcv.kt`**

```kotlin
package com.cashup.provisioning.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Key Check Value 3DES: enkripsi ECB delapan byte nol dengan [key], ambil tiga
 * byte pertama, hex huruf besar. Ini yang dikirim ke backend di langkah
 * `activate` sebagai bukti bahwa key yang diterima device sama dengan yang
 * diterbitkan HSM.
 *
 * Key 16 byte (K1K2) diperluas jadi K1K2K1 sebelum dipakai — aturan TDES yang
 * baku. Key 24 byte dipakai apa adanya.
 *
 * [key] di-nol-kan sebelum fungsi ini kembali, termasuk saat gagal. Pemanggil
 * tidak boleh memakainya lagi setelah ini.
 */
fun keyCheckValue(key: ByteArray): String {
    val normalized = if (key.size == 24) key.copyOf() else key + key.copyOfRange(0, 8)
    return try {
        Cipher.getInstance("DESede/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(normalized, "DESede"))
            doFinal(ByteArray(8))
        }.take(3).joinToString("") { "%02X".format(it) }
    } finally {
        key.fill(0)
        normalized.fill(0)
    }
}
```

- [ ] **Step 5: Jalankan tes, pastikan lolos**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*KcvTest" --no-daemon`
Expected: PASS, 4 tes.

- [ ] **Step 6: Tulis `RsaUnwrapper.kt`**

```kotlin
package com.cashup.provisioning.crypto

/**
 * Membuka bungkusan RSA dari paket key.
 *
 * Interface, bukan kelas langsung, karena implementasi nyatanya memakai private
 * key non-extractable di Android Keystore yang tidak bisa dijalankan di unit
 * test JVM. Semua penguraian dan validasi paket diuji terhadap implementasi
 * palsu; lihat Task 9 untuk yang nyata.
 */
fun interface RsaUnwrapper {
    fun unwrap(wrapped: ByteArray): ByteArray
}
```

- [ ] **Step 7: Tulis tes yang gagal untuk `PackageUnwrapper`**

`provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/PackageUnwrapperTest.kt`:

```kotlin
package com.cashup.provisioning.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PackageUnwrapperTest {

    private val ipek = ByteArray(16) { (it + 1).toByte() }
    private val ksn = ByteArray(10) { (it + 100).toByte() }

    private fun plaintextJson(kcvOverride: String? = null): String {
        val kcv = kcvOverride ?: keyCheckValue(ipek.copyOf())
        val ipekB64 = Base64.getEncoder().encodeToString(ipek)
        val ksnB64 = Base64.getEncoder().encodeToString(ksn)
        return """
            {"materials":{
              "PIN":{"ipek":"$ipekB64","ksn":"$ksnB64","kcv":"$kcv"},
              "TRACK":{"ipek":"$ipekB64","ksn":"$ksnB64","kcv":"$kcv"}
            }}
        """.trimIndent()
    }

    private fun unwrapperReturning(json: String) =
        PackageUnwrapper(RsaUnwrapper { json.toByteArray() })

    private fun wrappedInput() = Base64.getEncoder().encodeToString("ignored".toByteArray())

    @Test
    fun `returns one material per purpose in the package`() {
        val materials = unwrapperReturning(plaintextJson()).unwrap(wrappedInput())

        assertEquals(setOf("PIN", "TRACK"), materials.map { it.purpose }.toSet())
        assertTrue(materials.all { it.ipek.size == 16 })
        assertTrue(materials.all { it.ksn.size == 10 })
    }

    @Test
    fun `purposes are not hard-coded -- an unfamiliar purpose still comes through`() {
        val ipekB64 = Base64.getEncoder().encodeToString(ipek)
        val ksnB64 = Base64.getEncoder().encodeToString(ksn)
        val kcv = keyCheckValue(ipek.copyOf())
        val json = """{"materials":{"SOMETHING_NEW":{"ipek":"$ipekB64","ksn":"$ksnB64","kcv":"$kcv"}}}"""

        val materials = unwrapperReturning(json).unwrap(wrappedInput())

        assertEquals(listOf("SOMETHING_NEW"), materials.map { it.purpose })
    }

    @Test
    fun `a mismatched KCV rejects the whole package`() {
        val failure = assertThrows(PackageIntegrityException::class.java) {
            unwrapperReturning(plaintextJson(kcvOverride = "000000")).unwrap(wrappedInput())
        }

        assertTrue(failure.message!!.contains("KCV"))
    }

    @Test
    fun `a package with no materials is rejected rather than silently installing nothing`() {
        assertThrows(PackageIntegrityException::class.java) {
            unwrapperReturning("""{"materials":{}}""").unwrap(wrappedInput())
        }
    }

    @Test
    fun `plaintext that is not the expected JSON is rejected`() {
        assertThrows(PackageIntegrityException::class.java) {
            unwrapperReturning("not json at all").unwrap(wrappedInput())
        }
    }
}
```

- [ ] **Step 8: Jalankan tes, pastikan gagal**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*PackageUnwrapperTest" --no-daemon`
Expected: FAIL — `Unresolved reference: PackageUnwrapper`.

- [ ] **Step 9: Tulis `PackageUnwrapper.kt`**

```kotlin
package com.cashup.provisioning.crypto

import com.cashup.devicesdk.TerminalKeyMaterial
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.util.Base64

class PackageIntegrityException(message: String) : Exception(message)

/**
 * Bentuk plaintext di dalam bungkusan RSA.
 *
 * **Belum dikonfirmasi backend** (spec §8 item 1). Kalau ternyata backend
 * memakai skema hibrida seperti `edc-mobile` — RSA membungkus kunci AES,
 * payload sesungguhnya di AES-GCM — yang berubah hanya berkas ini dan
 * `KeyPackageResponse`. Batasnya sengaja sempit supaya perubahan itu murah.
 */
internal data class PlainKeyPackage(val materials: Map<String, PlainKeyMaterial>?)

internal data class PlainKeyMaterial(val ipek: String, val ksn: String, val kcv: String)

/**
 * Membuka paket key dan memverifikasinya sebelum apa pun dipasang.
 *
 * Purpose diperlakukan **data-driven**: apa pun nama purpose yang datang akan
 * diteruskan. Tidak ada daftar purpose yang di-hardcode di sini, karena jumlah
 * dan namanya belum dikonfirmasi backend (spec §7.2) — dan menebaknya berarti
 * paket yang sah ditolak diam-diam saat backend menambah satu.
 *
 * KCV tiap purpose dihitung ulang dari IPEK dan dibandingkan dengan yang
 * dikirim backend. Ini satu-satunya kesempatan mendeteksi key yang rusak di
 * perjalanan: begitu IPEK masuk modul aman vendor, ia tidak bisa dibaca lagi.
 * Karena itu satu KCV yang tidak cocok membatalkan **seluruh** paket, bukan
 * hanya purpose itu — pemasangan separuh adalah keadaan yang dilarang spec §7.1.
 */
class PackageUnwrapper(
    private val unwrapper: RsaUnwrapper,
    private val gson: Gson = Gson(),
) {

    fun unwrap(wrappedPackageKeyBase64: String): List<TerminalKeyMaterial> {
        val wrapped = try {
            Base64.getDecoder().decode(wrappedPackageKeyBase64)
        } catch (e: IllegalArgumentException) {
            throw PackageIntegrityException("wrappedPackageKey bukan Base64 yang sah")
        }

        val plaintext = unwrapper.unwrap(wrapped)
        val parsed = try {
            gson.fromJson(plaintext.decodeToString(), PlainKeyPackage::class.java)
        } catch (e: JsonSyntaxException) {
            throw PackageIntegrityException("Isi paket key bukan JSON yang dikenali")
        } finally {
            plaintext.fill(0)
        }

        val materials = parsed?.materials
        if (materials.isNullOrEmpty()) {
            throw PackageIntegrityException("Paket key tidak memuat material DUKPT satu pun")
        }

        return materials.map { (purpose, material) ->
            val ipek = decode(purpose, "ipek", material.ipek)
            val ksn = decode(purpose, "ksn", material.ksn)

            // keyCheckValue menol-kan array yang diberikan, jadi dihitung dari
            // salinan -- ipek aslinya masih harus diinjeksi setelah ini.
            val computed = keyCheckValue(ipek.copyOf())
            if (!computed.equals(material.kcv, ignoreCase = true)) {
                ipek.fill(0)
                ksn.fill(0)
                throw PackageIntegrityException("KCV tidak cocok untuk purpose $purpose")
            }

            TerminalKeyMaterial(purpose = purpose, ipek = ipek, ksn = ksn)
        }
    }

    private fun decode(purpose: String, field: String, value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        throw PackageIntegrityException("Field $field untuk purpose $purpose bukan Base64 yang sah")
    }
}
```

- [ ] **Step 10: Jalankan tes, pastikan lolos**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*PackageUnwrapperTest" --no-daemon`
Expected: PASS, 5 tes.

- [ ] **Step 11: Commit**

```bash
git add provisioning-core/
git commit -m "feat(provisioning-core): add KCV, the BouncyCastle provider and the package unwrapper

BcProvider encodes two traps edc-mobile already paid for, both of which
fail with misleading symptoms. Android ships a stripped provider named
\"BC\", so the usual null check never fires and the full provider never
installs -- it is removed unconditionally first. And installing it at
highest priority reroutes every unqualified Cipher.getInstance call,
breaking RSA-OAEP with an InvalidCipherTextException that reads like bad
data rather than a wrong provider -- so it goes in at lowest priority,
reachable only by name.

PackageUnwrapper treats purposes as data. Neither their names nor their
count is confirmed yet, and hard-coding a list would mean silently
rejecting a valid package the day the backend adds one.

A mismatched KCV rejects the entire package rather than the one purpose.
This is the only moment a corrupted key can be caught: once an IPEK
enters the vendor secure module it cannot be read back, and a partial
install is a state the spec forbids.

RSA is behind an RsaUnwrapper seam so all of this is testable on the JVM;
the real Keystore-backed implementation lands in the next task."
```

---

## Task 9: Penyimpanan key di Android

Tiga penyimpanan: keypair RSA untuk membuka paket, keypair Ed25519 untuk menandatangani request, dan state provisioning.

Yang benar-benar bisa diuji unit di sini hanyalah **pemilihan jalur RSA** — TEE atau software — karena itu keputusan logika, dan salah memilihnya berarti device mendaftarkan public key yang tidak akan pernah bisa membuka paketnya sendiri. Operasi Keystore dan BouncyCastle-nya sendiri divalidasi manual; Robolectric tidak mengemulasi Android Keystore dengan setia, dan tes yang berpura-pura melakukannya hanya memberi rasa aman palsu.

**Files:**
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/local/SecurePrefs.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/crypto/RsaKeyStore.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/crypto/Ed25519KeyStore.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/local/ProvisioningStateStore.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/StoredDeviceSigner.kt`
- Create: `provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/RsaKeyLocationTest.kt`

**Interfaces:**
- Consumes: `BcProvider`, `RsaUnwrapper` (Task 8); `DeviceSigner` (Task 4).
- Produces:
  - `enum class Mgf1Digest { SHA1, SHA256 }`
  - `enum class RsaKeyLocation { ANDROID_KEYSTORE, SOFTWARE }`
  - `object RsaKeyLocationPolicy` dengan `fun choose(required: Mgf1Digest, apiLevel: Int): RsaKeyLocation`
  - `class RsaKeyStore(context: Context, requiredMgf1: Mgf1Digest = Mgf1Digest.SHA1)` dengan `fun ensureKeyPair(): RsaKeyInfo`, `fun unwrapper(): RsaUnwrapper`, `fun clear()`
  - `data class RsaKeyInfo(val publicKeySpkiBase64: String, val location: RsaKeyLocation)`
  - `class Ed25519KeyStore(context: Context)` dengan `fun ensureKeyPair(): String` (raw 32 byte Base64), `fun sign(bytes: ByteArray): ByteArray`, `fun hasKeyPair(): Boolean`, `fun clear()`
  - `class ProvisioningStateStore(context: Context)` dengan `fun serialNumber(): String?`, `fun save(state: ProvisioningState)`, `fun current(): ProvisioningState?`, `fun clear()`
  - `data class ProvisioningState(val serialNumber: String, val orderId: String, val backings: Map<String, String>)`
  - `class StoredDeviceSigner(stateStore: ProvisioningStateStore, ed25519: Ed25519KeyStore) : DeviceSigner`

- [ ] **Step 1: Tulis tes yang gagal untuk pemilihan jalur RSA**

`provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/RsaKeyLocationTest.kt`:

```kotlin
package com.cashup.provisioning.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class RsaKeyLocationTest {

    @Test
    fun `MGF1-SHA1 uses the Keystore on every supported API level`() {
        // AndroidKeyStore memakai MGF1-SHA1 secara default, jadi tidak ada yang
        // perlu dikonfigurasi -- jalur TEE terbuka sampai ke minSdk.
        listOf(23, 24, 28, 30, 33).forEach { api ->
            assertEquals(
                "API $api",
                RsaKeyLocation.ANDROID_KEYSTORE,
                RsaKeyLocationPolicy.choose(Mgf1Digest.SHA1, api),
            )
        }
    }

    @Test
    fun `MGF1-SHA256 falls back to software below API 35`() {
        // setMgf1Digests baru ada di API 35. Di bawah itu digest MGF1 di
        // AndroidKeyStore terkunci SHA-1 dan tidak bisa diubah, jadi key TEE
        // tidak akan pernah bisa membuka paket yang dibungkus MGF1-SHA256.
        listOf(23, 24, 28, 30, 33, 34).forEach { api ->
            assertEquals(
                "API $api",
                RsaKeyLocation.SOFTWARE,
                RsaKeyLocationPolicy.choose(Mgf1Digest.SHA256, api),
            )
        }
    }

    @Test
    fun `MGF1-SHA256 uses the Keystore from API 35 upward`() {
        listOf(35, 36).forEach { api ->
            assertEquals(
                "API $api",
                RsaKeyLocation.ANDROID_KEYSTORE,
                RsaKeyLocationPolicy.choose(Mgf1Digest.SHA256, api),
            )
        }
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*RsaKeyLocationTest" --no-daemon`
Expected: FAIL — `Unresolved reference: RsaKeyLocationPolicy`.

- [ ] **Step 3: Tulis `SecurePrefs.kt` lebih dulu**

`RsaKeyStore` di langkah berikutnya mengimpornya, jadi ia harus ada duluan.

```kotlin
package com.cashup.provisioning.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * `SharedPreferences` ter-enkripsi AES-256-GCM dengan master key yang dipegang
 * Android Keystore. Dipakai untuk apa pun yang tidak bisa masuk hardware:
 * blob private key Ed25519 dan, di jalur fallback, blob RSA.
 */
internal object SecurePrefs {
    fun open(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
```

- [ ] **Step 4: Tulis `RsaKeyStore.kt`**

```kotlin
package com.cashup.provisioning.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.cashup.provisioning.data.local.SecurePrefs
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.security.auth.x500.X500Principal

/** Digest MGF1 yang dipakai backend saat membungkus paket key. */
enum class Mgf1Digest { SHA1, SHA256 }

enum class RsaKeyLocation { ANDROID_KEYSTORE, SOFTWARE }

data class RsaKeyInfo(val publicKeySpkiBase64: String, val location: RsaKeyLocation)

/**
 * Menentukan di mana keypair RSA dibuat.
 *
 * Keputusan ini diambil SEKALI, sebelum `qr-redeem`, dan tidak boleh berubah
 * setelahnya: public key yang didaftarkan ke backend harus milik key yang
 * nantinya benar-benar dipakai membuka paket.
 *
 * Batasannya nyata, sudah diverifikasi terhadap `android.jar`:
 * `KeyGenParameterSpec.Builder.setMgf1Digests` baru ada di **API 35**. Di bawah
 * itu, digest MGF1 di AndroidKeyStore terkunci SHA-1. Jadi kalau backend
 * membungkus dengan MGF1-SHA256, key TEE tidak akan pernah bisa membukanya di
 * terminal Android 7–11 — dan tidak seperti `edc-mobile`, kita tidak bisa
 * mencoba dua kombinasi karena key hardware hanya punya satu.
 *
 * Lihat spec §4.5.
 */
object RsaKeyLocationPolicy {
    const val MGF1_DIGESTS_API = 35

    fun choose(required: Mgf1Digest, apiLevel: Int): RsaKeyLocation = when {
        required == Mgf1Digest.SHA1 -> RsaKeyLocation.ANDROID_KEYSTORE
        apiLevel >= MGF1_DIGESTS_API -> RsaKeyLocation.ANDROID_KEYSTORE
        else -> RsaKeyLocation.SOFTWARE
    }
}

/**
 * Keypair RSA-2048 yang membuka bungkusan paket key.
 *
 * Jalur AndroidKeyStore memakai key non-extractable dengan `PURPOSE_DECRYPT`:
 * unwrap terjadi di dalam TEE dan private key tidak pernah ada di RAM. StrongBox
 * dicoba lebih dulu dan gagalnya ditangani, karena banyak SoC EDC tidak punya.
 *
 * Jalur software ada semata karena batas MGF1 di [RsaKeyLocationPolicy], bukan
 * karena dipilih — dan [RsaKeyInfo.location] melaporkannya supaya kondisi itu
 * terlihat, tidak diam.
 *
 * Tidak ada tes unit untuk kelas ini. Robolectric tidak mengemulasi Android
 * Keystore dengan setia; yang diuji adalah kebijakannya, dan kripto-nya
 * divalidasi manual di terminal fisik.
 */
class RsaKeyStore(
    context: Context,
    private val requiredMgf1: Mgf1Digest = Mgf1Digest.SHA1,
) {
    private val appContext = context.applicationContext
    private val prefs by lazy { SecurePrefs.open(appContext, PREFS) }

    private val location: RsaKeyLocation
        get() = RsaKeyLocationPolicy.choose(requiredMgf1, Build.VERSION.SDK_INT)

    @Synchronized
    fun ensureKeyPair(): RsaKeyInfo = when (location) {
        RsaKeyLocation.ANDROID_KEYSTORE -> RsaKeyInfo(ensureKeystoreKey(), RsaKeyLocation.ANDROID_KEYSTORE)
        RsaKeyLocation.SOFTWARE -> RsaKeyInfo(ensureSoftwareKey(), RsaKeyLocation.SOFTWARE)
    }

    fun unwrapper(): RsaUnwrapper = RsaUnwrapper { wrapped ->
        val cipher = when (location) {
            RsaKeyLocation.ANDROID_KEYSTORE ->
                Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                    init(Cipher.DECRYPT_MODE, keystorePrivateKey(), oaepSpec())
                }
            RsaKeyLocation.SOFTWARE -> {
                BcProvider.ensureInstalled()
                Cipher.getInstance("RSA/ECB/OAEPPadding", BcProvider.NAME).apply {
                    init(Cipher.DECRYPT_MODE, softwarePrivateKey(), oaepSpec())
                }
            }
        }
        cipher.doFinal(wrapped)
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_PRIVATE).remove(KEY_PUBLIC).commit()
        runCatching { androidKeyStore().deleteEntry(ALIAS) }
    }

    private fun oaepSpec() = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        when (requiredMgf1) {
            Mgf1Digest.SHA1 -> MGF1ParameterSpec.SHA1
            Mgf1Digest.SHA256 -> MGF1ParameterSpec.SHA256
        },
        PSource.PSpecified.DEFAULT,
    )

    private fun androidKeyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKeystoreKey(): String {
        val store = androidKeyStore()
        store.getCertificate(ALIAS)?.let { return it.publicKey.encoded.base64() }

        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setCertificateSubject(X500Principal("CN=cashup-provisioning"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Calendar.getInstance().time)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        val pair = try {
            generator.initialize(spec(strongBox = true))
            generator.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            // Banyak SoC EDC tidak punya StrongBox. TEE biasa tetap jauh lebih
            // baik daripada blob software, jadi ini turun satu tingkat, bukan
            // gagal.
            generator.initialize(spec(strongBox = false))
            generator.generateKeyPair()
        }
        return pair.public.encoded.base64()
    }

    private fun keystorePrivateKey(): PrivateKey =
        androidKeyStore().getKey(ALIAS, null) as PrivateKey

    private fun ensureSoftwareKey(): String {
        prefs.getString(KEY_PUBLIC, null)?.let { return it }
        BcProvider.ensureInstalled()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, pair.private.encoded.base64())
            .putString(KEY_PUBLIC, pair.public.encoded.base64())
            .commit()
        return pair.public.encoded.base64()
    }

    private fun softwarePrivateKey(): PrivateKey {
        val stored = prefs.getString(KEY_PRIVATE, null)
            ?: error("Keypair RSA belum dibuat")
        return KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored)))
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private companion object {
        const val ALIAS = "cashup_provisioning_rsa"
        const val PREFS = "provisioning_rsa"
        const val KEY_PRIVATE = "private_key"
        const val KEY_PUBLIC = "public_key"
    }
}
```

- [ ] **Step 5: Jalankan tes, pastikan lolos**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*RsaKeyLocationTest" --no-daemon`
Expected: PASS, 3 tes.

<!-- SecurePrefs sudah ditulis di Step 3; blok di bawah dipertahankan sebagai rujukan isinya. -->
<details><summary>Isi <code>SecurePrefs.kt</code> (rujukan)</summary>

```kotlin
package com.cashup.provisioning.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * `SharedPreferences` ter-enkripsi AES-256-GCM dengan master key yang dipegang
 * Android Keystore. Dipakai untuk apa pun yang tidak bisa masuk hardware:
 * blob private key Ed25519 dan, di jalur fallback, blob RSA.
 */
internal object SecurePrefs {
    fun open(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
```

</details>

- [ ] **Step 6: Tulis `Ed25519KeyStore.kt`**

```kotlin
package com.cashup.provisioning.crypto

import android.content.Context
import com.cashup.provisioning.data.local.SecurePrefs
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Keypair Ed25519 yang menandatangani setiap request setelah provisioning.
 *
 * **Ini bukan key hardware-backed, dan tidak bisa dibuat begitu.**
 * `KeyProperties.KEY_ALGORITHM_ED25519` tidak ada di Android — diverifikasi
 * terhadap `android.jar` API 33, 36, dan 37; API 37 menambah ML-DSA dan tetap
 * tanpa Ed25519. Jadi keypair dibuat lewat BouncyCastle dan private key-nya
 * disimpan sebagai blob PKCS8 di [SecurePrefs].
 *
 * Tingkat perlindungannya setara `EncryptedSharedPreferences` biasa, bukan
 * lebih. Ini konsekuensi dari algoritma yang sudah dikunci backend, bukan
 * pilihan — lihat spec §4.2.
 *
 * [ensureKeyPair] idempoten: kalau key sudah ada, tidak dibuat ulang, supaya
 * device tetap dikenali backend.
 */
class Ed25519KeyStore(context: Context) {

    private val prefs by lazy { SecurePrefs.open(context.applicationContext, PREFS) }

    fun hasKeyPair(): Boolean = prefs.contains(KEY_PRIVATE)

    /** Public key raw 32 byte, Base64 — bentuk yang diharapkan backend. */
    @Synchronized
    fun ensureKeyPair(): String {
        prefs.getString(KEY_PUBLIC_RAW, null)?.let { return it }

        BcProvider.ensureInstalled()
        val pair = KeyPairGenerator.getInstance("Ed25519", BcProvider.NAME).generateKeyPair()
        val raw = rawFromX509(pair.public.encoded)
        val rawBase64 = Base64.getEncoder().encodeToString(raw)

        prefs.edit()
            .putString(KEY_PRIVATE, Base64.getEncoder().encodeToString(pair.private.encoded))
            .putString(KEY_PUBLIC_RAW, rawBase64)
            .commit()
        return rawBase64
    }

    fun sign(bytes: ByteArray): ByteArray {
        val stored = prefs.getString(KEY_PRIVATE, null)
            ?: error("Keypair Ed25519 belum dibuat")
        BcProvider.ensureInstalled()
        val privateKey = KeyFactory.getInstance("Ed25519", BcProvider.NAME)
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored)))
        return Signature.getInstance("Ed25519", BcProvider.NAME).run {
            initSign(privateKey)
            update(bytes)
            sign()
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_PRIVATE).remove(KEY_PUBLIC_RAW).commit()
    }

    /**
     * SubjectPublicKeyInfo X.509 untuk Ed25519 selalu 44 byte: prefix ASN.1
     * tetap 12 byte diikuti 32 byte key mentah. Backend memakai konvensi raw
     * yang sama di arah sebaliknya.
     */
    private fun rawFromX509(encoded: ByteArray): ByteArray =
        encoded.copyOfRange(encoded.size - 32, encoded.size)

    private companion object {
        const val PREFS = "provisioning_ed25519"
        const val KEY_PRIVATE = "private_key"
        const val KEY_PUBLIC_RAW = "public_key_raw"
    }
}
```

- [ ] **Step 7: Tulis `ProvisioningStateStore.kt`**

```kotlin
package com.cashup.provisioning.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * [backings] memetakan purpose ke nama [com.cashup.devicesdk.KeyBacking],
 * disimpan sebagai string supaya penambahan nilai enum kelak tidak membuat
 * state lama tidak terbaca.
 */
data class ProvisioningState(
    val serialNumber: String,
    val orderId: String,
    val backings: Map<String, String>,
)

class ProvisioningStateStore(context: Context) {

    private val prefs by lazy { SecurePrefs.open(context.applicationContext, PREFS) }
    private val gson = Gson()

    /**
     * Mengembalikan `null` juga kalau isi tersimpan tidak lagi cocok skema saat
     * ini — entri dibuang, bukan didiamkan, supaya pembacaan berikutnya tidak
     * mengulang error yang sama selamanya.
     */
    fun current(): ProvisioningState? {
        val raw = prefs.getString(KEY, null) ?: return null
        return try {
            gson.fromJson(raw, ProvisioningState::class.java)
        } catch (e: JsonSyntaxException) {
            prefs.edit().remove(KEY).commit()
            null
        }
    }

    fun serialNumber(): String? = current()?.serialNumber

    @Synchronized
    fun save(state: ProvisioningState) {
        prefs.edit().putString(KEY, gson.toJson(state)).commit()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private companion object {
        const val PREFS = "provisioning_state"
        const val KEY = "current"
    }
}
```

- [ ] **Step 8: Tulis `StoredDeviceSigner.kt`**

```kotlin
package com.cashup.provisioning

import com.cashup.provisioning.crypto.Ed25519KeyStore
import com.cashup.provisioning.data.local.ProvisioningStateStore
import com.cashup.signing.DeviceSigner

/**
 * Menyambungkan penyimpanan ke kontrak [DeviceSigner] milik `signing-core`.
 *
 * [deviceId] baru punya nilai setelah provisioning berhasil — sebelum itu
 * `SigningInterceptor` melempar `DeviceNotProvisionedException`, yang memang
 * yang diinginkan: request bertanda tangan tidak punya urusan berjalan sebelum
 * backend mengenal device ini.
 */
class StoredDeviceSigner(
    private val stateStore: ProvisioningStateStore,
    private val ed25519: Ed25519KeyStore,
) : DeviceSigner {

    override fun deviceId(): String? = stateStore.serialNumber()

    override fun sign(canonicalBytes: ByteArray): ByteArray = ed25519.sign(canonicalBytes)
}
```

- [ ] **Step 9: Verifikasi module ter-build dan seluruh tesnya lolos**

Run: `./gradlew :provisioning-core:assembleDebug :provisioning-core:testDebugUnitTest --no-daemon`
Expected: BUILD SUCCESSFUL, 16 tes lolos (4 repository + 4 KCV + 5 unwrapper + 3 lokasi RSA).

- [ ] **Step 10: Commit**

```bash
git add provisioning-core/
git commit -m "feat(provisioning-core): add the Android key stores

RsaKeyLocationPolicy is the part with unit tests, because getting it wrong
means the device registers a public key that can never open its own key
package. setMgf1Digests is API 35+ (verified against android.jar), so
below that AndroidKeyStore's MGF1 digest is locked to SHA-1: if the
backend wraps with MGF1-SHA256, a TEE key simply cannot decrypt on the
Android 7-11 terminals we target, and unlike edc-mobile we cannot try both
combinations because a hardware key only has one. The choice is made once,
before qr-redeem, since the registered public key must belong to the key
that will actually unwrap.

The RSA Keystore path uses a non-extractable PURPOSE_DECRYPT key so
unwrapping happens inside the TEE. StrongBox is attempted and its absence
handled rather than fatal, because many EDC SoCs lack it.

Ed25519 cannot be hardware-backed at all -- KEY_ALGORITHM_ED25519 does not
exist through API 37 -- so it is a BouncyCastle key stored as a PKCS8 blob
in EncryptedSharedPreferences. That is a consequence of the algorithm the
backend locked, not a preference, and it is written on the class so nobody
later assumes otherwise.

No unit tests cover the Keystore operations themselves: Robolectric does
not emulate Android Keystore faithfully, and tests that pretend otherwise
buy false confidence. They are validated by hand on a physical terminal."
```

---

## Task 10: Jurnal provisioning — log per langkah dengan bukti nilai

> ### ⏳ SEMENTARA — dijadwalkan dihapus sebelum produksi
>
> Ini alat bantu tahap awal: untuk membuktikan alur berjalan dan menyusun
> laporan selama uji coba di terminal. **Bukan** bagian dari produk akhir.
>
> Seluruhnya sengaja ditaruh di satu package, `com.cashup.provisioning.audit`,
> dan masuk ke alur utama lewat **satu parameter konstruktor** saja. Checklist
> pencabutannya ada di akhir task ini — kalau nanti ada bagian jurnal yang bocor
> ke luar package `audit/`, itu pelanggaran desain task ini, bukan sekadar
> kerapian.

Provisioning harus bisa dilaporkan selama tahap ini: apa yang terjadi di tiap langkah, nilai apa yang mengalir, dan bukti bahwa key yang dipasang memang key yang diterbitkan HSM. Sekaligus, key material tidak boleh bocor ke log — sifat sementaranya **tidak** melonggarkan itu. Uji coba berjalan di terminal sungguhan dengan key sungguhan, dan logcat di sana bukan tempat yang kita kendalikan.

Kedua tuntutan itu didamaikan dengan aturan yang dikodekan, bukan diserahkan ke disiplin penulis kode: **jurnal hanya menerima nilai lewat fungsi pembungkus yang sudah menentukan cara merendernya.** Tidak ada jalan untuk menaruh `ByteArray` mentah ke dalam entri.

| Jenis nilai | Yang dicatat | Kenapa aman |
|---|---|---|
| IPEK, private key | `len=16 fp=a3f9c1d2` | Sidik jari SHA-256 dipotong 8 hex. Cukup membuktikan dua nilai sama/berbeda, tidak cukup membalikkannya |
| KSN | `len=10 fp=…` | Sama. KSN tidak rahasia tapi diperlakukan sama demi keseragaman |
| KCV | nilai penuh | KCV **memang** dirancang sebagai bukti publik atas sebuah key |
| Public key | `len=294 fp=…` | Publik, tapi tetap dipotong supaya entri tetap pendek |
| `challengeCode` | 4 karakter pertama + `…` | Sekali pakai, tapi masih hidup saat log ditulis |
| `orderId`, `correlationId`, `status`, `serialNumber` | nilai penuh | Justru ini yang dibutuhkan laporan untuk dicocokkan dengan sisi backend |

**Files:**
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/audit/ProvisioningJournal.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/audit/Evidence.kt`
- Create: `provisioning-core/src/test/kotlin/com/cashup/provisioning/audit/ProvisioningJournalTest.kt`
- Create: `provisioning-core/src/test/kotlin/com/cashup/provisioning/audit/EvidenceTest.kt`

**Interfaces:**
- Consumes: `PaymentLogger`, `NoOpPaymentLogger` dari `common-core`.
- Produces:
  - `enum class ProvisioningStep { DETECT_DEVICE, GENERATE_KEYS, SCAN_QR, REDEEM, DOWNLOAD_PACKAGE, UNWRAP_PACKAGE, VERIFY_KCV, INSTALL_KEYS, ACTIVATE, PERSIST_STATE, ROLLBACK }`
  - `enum class StepStatus { STARTED, OK, FAILED }`
  - `data class JournalEntry(val step: ProvisioningStep, val status: StepStatus, val atMillis: Long, val durationMillis: Long?, val evidence: Map<String, String>, val errorCode: String?)`
  - `object Evidence` dengan `fun secret(bytes: ByteArray): String`, `fun secret(text: String): String`, `fun publicKey(base64: String): String`, `fun masked(value: String, visible: Int = 4): String`, `fun fingerprint(bytes: ByteArray): String`
  - `class ProvisioningJournal(logger: PaymentLogger = NoOpPaymentLogger, clock: () -> Long = System::currentTimeMillis)` dengan `fun start(step, evidence)`, `fun ok(step, evidence)`, `fun failed(step, errorCode, evidence)`, `val entries: List<JournalEntry>`, `fun render(): String`, `fun clear()`

- [ ] **Step 1: Tulis tes yang gagal untuk `Evidence`**

`provisioning-core/src/test/kotlin/com/cashup/provisioning/audit/EvidenceTest.kt`:

```kotlin
package com.cashup.provisioning.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceTest {

    private val ipek = byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F, 0x60, 0x71)

    @Test
    fun `secret renders length and a short fingerprint, never the bytes`() {
        val rendered = Evidence.secret(ipek)

        assertTrue(rendered, rendered.startsWith("len=8 fp="))
        assertFalse("raw byte leaked", rendered.contains("0a1b", ignoreCase = true))
        assertEquals(8, rendered.substringAfter("fp=").length)
    }

    @Test
    fun `the same bytes always fingerprint the same way`() {
        assertEquals(Evidence.secret(ipek), Evidence.secret(ipek.copyOf()))
    }

    @Test
    fun `one changed byte changes the fingerprint`() {
        val other = ipek.copyOf().also { it[0] = 0x0B }

        assertNotEquals(Evidence.secret(ipek), Evidence.secret(other))
    }

    @Test
    fun `secret does not mutate or zero the caller's array`() {
        val original = ipek.copyOf()

        Evidence.secret(ipek)

        // Berbeda dari keyCheckValue, yang memang menol-kan. Jurnal tidak boleh
        // punya efek samping terhadap nilai yang sedang dipakai alur utama.
        assertTrue(original.contentEquals(ipek))
    }

    @Test
    fun `masked keeps only the first few characters`() {
        assertEquals("ABCD…", Evidence.masked("ABCD-1234-EFGH"))
        assertEquals("AB…", Evidence.masked("ABCD-1234", visible = 2))
    }

    @Test
    fun `masked does not pad out a value shorter than the window`() {
        assertEquals("AB…", Evidence.masked("AB"))
    }

    @Test
    fun `publicKey reports length and fingerprint`() {
        val rendered = Evidence.publicKey("TUZrd0V3WUhLb1pJemowQ0FR")

        assertTrue(rendered, rendered.startsWith("len=24 fp="))
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*EvidenceTest" --no-daemon`
Expected: FAIL — `Unresolved reference: Evidence`.

- [ ] **Step 3: Tulis `Evidence.kt`**

```kotlin
package com.cashup.provisioning.audit

import java.security.MessageDigest

/**
 * Satu-satunya jalan menaruh nilai ke dalam [ProvisioningJournal].
 *
 * Ada supaya aturan "key material tidak pernah masuk log" jadi properti tipe,
 * bukan disiplin: [ProvisioningJournal] hanya menerima `Map<String, String>`,
 * dan satu-satunya cara waras membuat string itu dari sebuah key adalah lewat
 * [secret], yang memang tidak mampu mengeluarkan byte aslinya.
 *
 * Sidik jari dipotong 8 hex (32 bit). Itu cukup untuk keperluan laporan —
 * membuktikan bahwa IPEK yang dipasang sama dengan yang di-unwrap, atau bahwa
 * dua device menerima key berbeda — dan terlalu pendek untuk dibalikkan menjadi
 * key-nya.
 *
 * KCV sengaja TIDAK lewat sini: nilai itu memang dirancang sebagai bukti publik
 * atas sebuah key, dan laporan justru butuh nilai penuhnya untuk dicocokkan
 * dengan catatan HSM.
 */
object Evidence {

    private const val FINGERPRINT_HEX_CHARS = 8

    /** `len=<n> fp=<8 hex>`. Tidak menyentuh isi [bytes]. */
    fun secret(bytes: ByteArray): String = "len=${bytes.size} fp=${fingerprint(bytes)}"

    fun secret(text: String): String = secret(text.toByteArray(Charsets.UTF_8))

    /** Public key boleh dicatat utuh, tapi dipendekkan supaya entri tetap terbaca. */
    fun publicKey(base64: String): String = "len=${base64.length} fp=${fingerprint(base64.toByteArray())}"

    /** Beberapa karakter pertama saja — untuk nilai sekali pakai yang masih hidup. */
    fun masked(value: String, visible: Int = 4): String = value.take(visible) + "…"

    fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(FINGERPRINT_HEX_CHARS)
}
```

- [ ] **Step 4: Jalankan tes, pastikan lolos**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*EvidenceTest" --no-daemon`
Expected: PASS, 7 tes.

- [ ] **Step 5: Tulis tes yang gagal untuk `ProvisioningJournal`**

`provisioning-core/src/test/kotlin/com/cashup/provisioning/audit/ProvisioningJournalTest.kt`:

```kotlin
package com.cashup.provisioning.audit

import com.cashup.common.logging.PaymentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningJournalTest {

    private class RecordingLogger : PaymentLogger {
        val lines = mutableListOf<String>()
        override fun debug(tag: String, message: String) { lines += message }
        override fun warn(tag: String, message: String, throwable: Throwable?) { lines += message }
        override fun error(tag: String, message: String, throwable: Throwable?) { lines += message }
    }

    private fun journalWithClock(vararg times: Long): Pair<ProvisioningJournal, RecordingLogger> {
        val logger = RecordingLogger()
        var index = 0
        val journal = ProvisioningJournal(logger) { times[index++.coerceAtMost(times.size - 1)] }
        return journal to logger
    }

    @Test
    fun `records steps in order with their evidence`() {
        val (journal, _) = journalWithClock(1000, 1200)

        journal.start(ProvisioningStep.REDEEM, mapOf("challengeCode" to Evidence.masked("ABCD-1234")))
        journal.ok(ProvisioningStep.REDEEM, mapOf("orderId" to "o-1"))

        assertEquals(2, journal.entries.size)
        assertEquals(StepStatus.STARTED, journal.entries[0].status)
        assertEquals("ABCD…", journal.entries[0].evidence["challengeCode"])
        assertEquals(StepStatus.OK, journal.entries[1].status)
        assertEquals("o-1", journal.entries[1].evidence["orderId"])
    }

    @Test
    fun `a completed step reports how long it took`() {
        val (journal, _) = journalWithClock(1000, 1200)

        journal.start(ProvisioningStep.DOWNLOAD_PACKAGE, emptyMap())
        journal.ok(ProvisioningStep.DOWNLOAD_PACKAGE, emptyMap())

        assertNull(journal.entries[0].durationMillis)
        assertEquals(200L, journal.entries[1].durationMillis)
    }

    @Test
    fun `a failure keeps the backend error code`() {
        val (journal, _) = journalWithClock(1000, 1100)

        journal.start(ProvisioningStep.REDEEM, emptyMap())
        journal.failed(ProvisioningStep.REDEEM, "PROVISIONING_TOKEN_INVALID", emptyMap())

        assertEquals("PROVISIONING_TOKEN_INVALID", journal.entries[1].errorCode)
        assertEquals(StepStatus.FAILED, journal.entries[1].status)
    }

    @Test
    fun `every entry is also handed to the logger`() {
        val (journal, logger) = journalWithClock(1000)

        journal.ok(ProvisioningStep.INSTALL_KEYS, mapOf("PIN" to "VENDOR_SECURE_MODULE"))

        assertEquals(1, logger.lines.size)
        assertTrue(logger.lines[0], logger.lines[0].contains("INSTALL_KEYS"))
        assertTrue(logger.lines[0], logger.lines[0].contains("VENDOR_SECURE_MODULE"))
    }

    @Test
    fun `render produces one readable line per entry for the report`() {
        val (journal, _) = journalWithClock(1000, 1050, 1300)

        journal.start(ProvisioningStep.UNWRAP_PACKAGE, emptyMap())
        journal.ok(
            ProvisioningStep.UNWRAP_PACKAGE,
            mapOf("PIN.ipek" to "len=16 fp=a3f9c1d2", "PIN.kcv" to "A1B2C3"),
        )

        val lines = journal.render().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1], lines[1].contains("UNWRAP_PACKAGE"))
        assertTrue(lines[1], lines[1].contains("PIN.kcv=A1B2C3"))
        assertTrue(lines[1], lines[1].contains("fp=a3f9c1d2"))
    }

    @Test
    fun `clear empties the journal for a fresh attempt`() {
        val (journal, _) = journalWithClock(1000)

        journal.ok(ProvisioningStep.SCAN_QR, emptyMap())
        journal.clear()

        assertTrue(journal.entries.isEmpty())
    }
}
```

- [ ] **Step 6: Jalankan tes, pastikan gagal**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*ProvisioningJournalTest" --no-daemon`
Expected: FAIL — `Unresolved reference: ProvisioningJournal`.

- [ ] **Step 7: Tulis `ProvisioningJournal.kt`**

```kotlin
package com.cashup.provisioning.audit

import com.cashup.common.logging.NoOpPaymentLogger
import com.cashup.common.logging.PaymentLogger
import java.util.Collections

enum class ProvisioningStep {
    DETECT_DEVICE,
    GENERATE_KEYS,
    SCAN_QR,
    REDEEM,
    DOWNLOAD_PACKAGE,
    UNWRAP_PACKAGE,
    VERIFY_KCV,
    INSTALL_KEYS,
    ACTIVATE,
    PERSIST_STATE,
    ROLLBACK,
}

enum class StepStatus { STARTED, OK, FAILED }

data class JournalEntry(
    val step: ProvisioningStep,
    val status: StepStatus,
    val atMillis: Long,
    val durationMillis: Long?,
    val evidence: Map<String, String>,
    val errorCode: String?,
)

/**
 * **SEMENTARA — dihapus sebelum produksi.** Alat bantu tahap awal untuk
 * membuktikan alur berjalan dan menyusun laporan selama uji coba di terminal.
 * Seluruh package `audit/` dicabut bersamaan; lihat checklist di
 * `docs/superpowers/plans/2026-09-16-provisioning.md` Task 10.
 *
 * Catatan berurutan tentang apa yang terjadi selama provisioning, beserta bukti
 * nilai yang cukup untuk dilaporkan dan dicocokkan dengan sisi backend.
 *
 * Isinya aman dibaca dan disalin: nilai rahasia masuk lewat [Evidence], yang
 * hanya bisa mengeluarkan panjang dan sidik jari terpotong. KCV dicatat utuh
 * karena memang itu fungsinya — bukti publik atas sebuah key.
 *
 * Jurnal ini **bukan** pengganti log aplikasi; tiap entri juga diteruskan ke
 * [PaymentLogger]. Bedanya, jurnal tetap hidup di memori sebagai satu kesatuan
 * sehingga layar Result bisa menampilkannya dan laporan bisa mengambilnya utuh,
 * tanpa mengais logcat.
 *
 * Durasi dihitung dari [start] ke [ok]/[failed] untuk langkah yang sama, jadi
 * laporan bisa menunjukkan langkah mana yang lambat — biasanya
 * [ProvisioningStep.DOWNLOAD_PACKAGE], yang menunggu dua HSM.
 */
class ProvisioningJournal(
    private val logger: PaymentLogger = NoOpPaymentLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val mutableEntries = Collections.synchronizedList(mutableListOf<JournalEntry>())
    private val startedAt = mutableMapOf<ProvisioningStep, Long>()

    val entries: List<JournalEntry> get() = mutableEntries.toList()

    fun start(step: ProvisioningStep, evidence: Map<String, String> = emptyMap()) {
        val now = clock()
        synchronized(startedAt) { startedAt[step] = now }
        record(step, StepStatus.STARTED, now, null, evidence, null)
    }

    fun ok(step: ProvisioningStep, evidence: Map<String, String> = emptyMap()) {
        val now = clock()
        record(step, StepStatus.OK, now, durationFor(step, now), evidence, null)
    }

    fun failed(step: ProvisioningStep, errorCode: String?, evidence: Map<String, String> = emptyMap()) {
        val now = clock()
        record(step, StepStatus.FAILED, now, durationFor(step, now), evidence, errorCode)
    }

    /** Satu baris per entri, siap disalin ke laporan. */
    fun render(): String = entries.joinToString("\n") { entry ->
        buildString {
            append(entry.atMillis)
            append(' ')
            append(entry.status.name.padEnd(7))
            append(' ')
            append(entry.step.name)
            entry.durationMillis?.let { append(" (${it}ms)") }
            entry.errorCode?.let { append(" error=").append(it) }
            entry.evidence.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        }
    }

    fun clear() {
        mutableEntries.clear()
        synchronized(startedAt) { startedAt.clear() }
    }

    private fun durationFor(step: ProvisioningStep, now: Long): Long? =
        synchronized(startedAt) { startedAt.remove(step) }?.let { now - it }

    private fun record(
        step: ProvisioningStep,
        status: StepStatus,
        atMillis: Long,
        durationMillis: Long?,
        evidence: Map<String, String>,
        errorCode: String?,
    ) {
        val entry = JournalEntry(step, status, atMillis, durationMillis, evidence.toMap(), errorCode)
        mutableEntries += entry
        val line = renderEntry(entry)
        when (status) {
            StepStatus.FAILED -> logger.error(TAG, line)
            else -> logger.debug(TAG, line)
        }
    }

    private fun renderEntry(entry: JournalEntry): String = buildString {
        append(entry.status.name)
        append(' ')
        append(entry.step.name)
        entry.durationMillis?.let { append(" (${it}ms)") }
        entry.errorCode?.let { append(" error=").append(it) }
        entry.evidence.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
    }

    private companion object {
        const val TAG = "Provisioning"
    }
}
```

- [ ] **Step 8: Jalankan tes, pastikan lolos**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*ProvisioningJournalTest" --no-daemon`
Expected: PASS, 6 tes.

- [ ] **Step 9: Commit**

```bash
git add provisioning-core/
git commit -m "feat(provisioning-core): add a provisioning journal with redacted evidence

Provisioning has to be reportable -- what happened at each step, which
values flowed, and proof that the keys installed are the keys the HSM
issued. It also must not leak key material into logs that leave a device
we do not control.

Evidence reconciles the two by making the rule a property of the type
rather than of the author's discipline: the journal only accepts strings,
and the only sane way to turn a key into one is Evidence.secret, which is
incapable of emitting the bytes. Fingerprints are 32 bits -- enough to
prove the IPEK installed is the IPEK unwrapped, far too short to invert.

KCV is deliberately recorded in full. It is designed to be public proof of
a key, and the report needs it to reconcile against the HSM's own record.

Durations come from pairing start with ok/failed, so a report can show
which step was slow -- usually the package download, which waits on two
HSMs.

The whole audit package is temporary scaffolding for terminal trials and
is scheduled for removal before production; the plan carries the removal
checklist."
```

### Checklist pencabutan (dijalankan sebelum rilis produksi)

Ditulis sekarang, selagi alasannya masih segar, supaya pencabutannya tidak jadi pekerjaan arkeologi.

- [ ] Hapus `provisioning-core/src/main/kotlin/com/cashup/provisioning/audit/` seluruhnya
- [ ] Hapus `provisioning-core/src/test/kotlin/com/cashup/provisioning/audit/` seluruhnya
- [ ] Hapus parameter `journal` dari konstruktor `ProvisionDeviceUseCase` (Task 11) beserta setiap pemanggilan `journal.start/ok/failed` di dalamnya
- [ ] Hapus `journalText` dari `ProvisioningUiState.Success` dan `ProvisioningUiState.Failure` (Task 12), beserta panel yang menampilkannya di layar Result
- [ ] Jalankan `grep -rn "audit\|Journal\|Evidence" --include=*.kt provisioning-core app` — harus tidak ada sisa
- [ ] `./gradlew test` dan `./gradlew testDebugUnitTest` harus tetap hijau setelahnya

Verifikasi bahwa pencabutan itu memang murah, dilakukan sekarang, bukan nanti: jurnal masuk ke `ProvisionDeviceUseCase` lewat satu parameter dengan nilai default, dan tidak ada tipe dari package `audit/` yang muncul di tanda tangan publik module lain.

---

## Task 11: `ProvisionDeviceUseCase` — ceremony lengkap, atomic

Inti dari plan ini. Sepuluh langkah spec §7.1, dengan satu aturan yang tidak boleh dilanggar: **gagal di langkah mana pun mengembalikan device ke keadaan belum terprovisioning.** Tidak ada state setengah jalan, tidak ada key yang tertinggal.

Tiga interface kecil diperkenalkan supaya seluruh alur ini bisa diuji di JVM tanpa Android sama sekali. Tanpa itu, satu-satunya cara menguji rollback adalah di hardware — dan rollback justru jalur yang paling jarang dijalankan sekaligus paling mahal kalau salah.

**Files:**
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/domain/ProvisioningGateway.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/domain/ProvisioningKeys.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/domain/ProvisioningOutcome.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/domain/ProvisionDeviceUseCase.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/AndroidProvisioningKeys.kt`
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/ProvisioningRepository.kt` — implement `ProvisioningGateway`
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/local/ProvisioningStateStore.kt` — implement `ProvisioningStateRepository`
- Create: `provisioning-core/src/test/kotlin/com/cashup/provisioning/domain/ProvisionDeviceUseCaseTest.kt`

**Interfaces:**
- Consumes: `ProvisioningRepository` (Task 7); `PackageUnwrapper`, `RsaUnwrapper`, `PackageIntegrityException` (Task 8); `RsaKeyInfo`, `RsaKeyLocation`, `ProvisioningState`, `ProvisioningStateStore`, `Ed25519KeyStore`, `RsaKeyStore` (Task 9); `ProvisioningJournal`, `ProvisioningStep`, `Evidence` (Task 10); `SerialNumberProvider`, `TerminalKeyInstaller`, `TerminalKeyInstallResult`, `KeyInstallOutcome`, `TerminalKeyMaterial` (Task 2).
- Produces:
  - `interface ProvisioningGateway` — `suspend fun redeem(QrRedeemRequest): ApiResult<QrRedeemResponse>`, `suspend fun downloadKeyPackage(KeyPackageRequest): ApiResult<KeyPackageResponse>`, `suspend fun activate(orderId: String, ActivateRequest): ApiResult<ActivateResponse>`
  - `interface ProvisioningKeys` — `fun ensureRsaKeyPair(): RsaKeyInfo`, `fun ensureEd25519KeyPair(): String`, `fun unwrapper(): RsaUnwrapper`, `fun clearAll()`
  - `interface ProvisioningStateRepository` — `fun current(): ProvisioningState?`, `fun save(state: ProvisioningState)`, `fun clear()`
  - `sealed interface ProvisioningOutcome` dengan `Success(serialNumber, orderId, installed: List<KeyInstallOutcome>, journalText: String)` dan `Failure(code: String, message: String, journalText: String)`
  - `class ProvisionDeviceUseCase(gateway, serialNumbers, keys, installer, state, unwrapperFactory, journal)` dengan `suspend operator fun invoke(rawChallengeCode: String): ProvisioningOutcome`
  - `class AndroidProvisioningKeys(rsa: RsaKeyStore, ed25519: Ed25519KeyStore) : ProvisioningKeys`

- [ ] **Step 1: Tulis interface-nya**

`provisioning-core/src/main/kotlin/com/cashup/provisioning/domain/ProvisioningGateway.kt`:

```kotlin
package com.cashup.provisioning.domain

import com.cashup.common.network.ApiResult
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.ActivateResponse
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.cashup.provisioning.data.remote.QrRedeemRequest
import com.cashup.provisioning.data.remote.QrRedeemResponse

/**
 * Tiga panggilan ceremony, tanpa menyebut Retrofit.
 *
 * Ada supaya [ProvisionDeviceUseCase] — termasuk seluruh jalur rollback-nya —
 * bisa diuji di JVM dengan implementasi palsu. Rollback adalah jalur yang paling
 * jarang dijalankan sekaligus paling mahal kalau salah, jadi ia harus bisa diuji
 * tanpa hardware.
 */
interface ProvisioningGateway {
    suspend fun redeem(request: QrRedeemRequest): ApiResult<QrRedeemResponse>
    suspend fun downloadKeyPackage(request: KeyPackageRequest): ApiResult<KeyPackageResponse>
    suspend fun activate(orderId: String, request: ActivateRequest): ApiResult<ActivateResponse>
}
```

`provisioning-core/src/main/kotlin/com/cashup/provisioning/domain/ProvisioningKeys.kt`:

```kotlin
package com.cashup.provisioning.domain

import com.cashup.provisioning.crypto.RsaKeyInfo
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.data.local.ProvisioningState

/**
 * Keypair milik device, tanpa menyebut Android Keystore.
 *
 * [ensureRsaKeyPair] dan [ensureEd25519KeyPair] idempoten: memanggil ulang tidak
 * membuat key baru. Itu penting karena public key yang sudah didaftarkan ke
 * backend harus tetap milik key yang sama.
 */
interface ProvisioningKeys {
    fun ensureRsaKeyPair(): RsaKeyInfo
    /** Public key Ed25519 raw 32 byte, Base64. */
    fun ensureEd25519KeyPair(): String
    fun unwrapper(): RsaUnwrapper
    fun clearAll()
}

interface ProvisioningStateRepository {
    fun current(): ProvisioningState?
    fun save(state: ProvisioningState)
    fun clear()
}
```

- [ ] **Step 2: Sambungkan implementasi yang sudah ada ke interface baru**

Di `ProvisioningRepository.kt`, ubah deklarasi kelasnya:

```kotlin
class ProvisioningRepository internal constructor(
    private val unsigned: ProvisioningApi,
    private val signed: ProvisioningApi,
    private val gson: Gson = Gson(),
) : ProvisioningGateway {
```

dan tambahkan `override` pada ketiga method-nya.

Di `ProvisioningStateStore.kt`, ubah deklarasi kelasnya:

```kotlin
class ProvisioningStateStore(context: Context) : ProvisioningStateRepository {
```

dan tambahkan `override` pada `current`, `save`, dan `clear`. `serialNumber()` tetap tanpa `override` — itu kemudahan lokal, bukan bagian kontrak.

Buat `provisioning-core/src/main/kotlin/com/cashup/provisioning/AndroidProvisioningKeys.kt`:

```kotlin
package com.cashup.provisioning

import com.cashup.provisioning.crypto.Ed25519KeyStore
import com.cashup.provisioning.crypto.RsaKeyInfo
import com.cashup.provisioning.crypto.RsaKeyStore
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.domain.ProvisioningKeys

class AndroidProvisioningKeys(
    private val rsa: RsaKeyStore,
    private val ed25519: Ed25519KeyStore,
) : ProvisioningKeys {

    override fun ensureRsaKeyPair(): RsaKeyInfo = rsa.ensureKeyPair()

    override fun ensureEd25519KeyPair(): String = ed25519.ensureKeyPair()

    override fun unwrapper(): RsaUnwrapper = rsa.unwrapper()

    override fun clearAll() {
        rsa.clear()
        ed25519.clear()
    }
}
```

- [ ] **Step 3: Tulis `ProvisioningOutcome.kt`**

```kotlin
package com.cashup.provisioning.domain

import com.cashup.devicesdk.KeyInstallOutcome

/**
 * `journalText` di kedua cabang adalah **sementara** — bagian dari alat bantu
 * pelaporan tahap awal, dijadwalkan dihapus bersama package `audit/` sebelum
 * produksi. Lihat checklist di Task 10.
 */
sealed interface ProvisioningOutcome {

    data class Success(
        val serialNumber: String,
        val orderId: String,
        val installed: List<KeyInstallOutcome>,
        val journalText: String,
    ) : ProvisioningOutcome

    /**
     * [code] adalah `error.code` dari backend kalau kegagalannya datang dari
     * sana, atau kode lokal (`DEVICE_UNKNOWN`, `PACKAGE_INVALID`,
     * `KEY_INSTALL_FAILED`) kalau bukan. Selalu bercabang pada [code], tidak
     * pernah pada [message].
     */
    data class Failure(
        val code: String,
        val message: String,
        val journalText: String,
    ) : ProvisioningOutcome
}
```

- [ ] **Step 4: Tulis tes yang gagal untuk use case**

`provisioning-core/src/test/kotlin/com/cashup/provisioning/domain/ProvisionDeviceUseCaseTest.kt`:

```kotlin
package com.cashup.provisioning.domain

import com.cashup.common.network.ApiError
import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.KeyBacking
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.devicesdk.fake.FakeSerialNumberProvider
import com.cashup.devicesdk.fake.FakeTerminalKeyInstaller
import com.cashup.provisioning.crypto.PackageIntegrityException
import com.cashup.provisioning.crypto.PackageUnwrapper
import com.cashup.provisioning.crypto.RsaKeyInfo
import com.cashup.provisioning.crypto.RsaKeyLocation
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.data.local.ProvisioningState
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.ActivateResponse
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.cashup.provisioning.data.remote.QrRedeemRequest
import com.cashup.provisioning.data.remote.QrRedeemResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionDeviceUseCaseTest {

    private class FakeGateway(
        var redeemResult: ApiResult<QrRedeemResponse> =
            ApiResult.Success(QrRedeemResponse("o-1", "tok-1")),
        var packageResult: ApiResult<KeyPackageResponse> =
            ApiResult.Success(KeyPackageResponse("o-1", "d3JhcHBlZA==")),
        var activateResult: ApiResult<ActivateResponse> =
            ApiResult.Success(ActivateResponse("ACTIVE")),
    ) : ProvisioningGateway {
        var redeemRequest: QrRedeemRequest? = null
        var activateRequest: ActivateRequest? = null

        override suspend fun redeem(request: QrRedeemRequest) =
            redeemResult.also { redeemRequest = request }

        override suspend fun downloadKeyPackage(request: KeyPackageRequest) = packageResult

        override suspend fun activate(orderId: String, request: ActivateRequest) =
            activateResult.also { activateRequest = request }
    }

    private class FakeKeys : ProvisioningKeys {
        var clearCount = 0
        override fun ensureRsaKeyPair() = RsaKeyInfo("rsa-spki", RsaKeyLocation.ANDROID_KEYSTORE)
        override fun ensureEd25519KeyPair() = "eddsa-raw"
        override fun unwrapper() = RsaUnwrapper { it }
        override fun clearAll() { clearCount++ }
    }

    private class FakeState : ProvisioningStateRepository {
        var saved: ProvisioningState? = null
        var clearCount = 0
        override fun current() = saved
        override fun save(state: ProvisioningState) { saved = state }
        override fun clear() { saved = null; clearCount++ }
    }

    private fun materials() = listOf(
        TerminalKeyMaterial("PIN", ByteArray(16) { 1 }, ByteArray(10) { 2 }),
        TerminalKeyMaterial("TRACK", ByteArray(16) { 3 }, ByteArray(10) { 4 }),
    )

    private fun useCase(
        gateway: ProvisioningGateway = FakeGateway(),
        keys: ProvisioningKeys = FakeKeys(),
        state: ProvisioningStateRepository = FakeState(),
        installer: FakeTerminalKeyInstaller = FakeTerminalKeyInstaller(),
        serial: String? = "PAX-A920-0012938",
        unwrap: () -> List<TerminalKeyMaterial> = ::materials,
    ) = ProvisionDeviceUseCase(
        gateway = gateway,
        serialNumbers = FakeSerialNumberProvider(serial),
        keys = keys,
        installer = installer,
        state = state,
        unwrapperFactory = {
            object : PackageUnwrapper(RsaUnwrapper { it }) {
                override fun unwrap(wrappedPackageKeyBase64: String) = unwrap()
            }
        },
    )

    @Test
    fun `a clean run installs the keys and reports where each landed`() = runTest {
        val state = FakeState()
        val gateway = FakeGateway()

        val outcome = useCase(gateway = gateway, state = state)("ABCD-1234")

        assertTrue(outcome.toString(), outcome is ProvisioningOutcome.Success)
        val success = outcome as ProvisioningOutcome.Success
        assertEquals("PAX-A920-0012938", success.serialNumber)
        assertEquals("o-1", success.orderId)
        assertEquals(
            KeyBacking.VENDOR_SECURE_MODULE,
            success.installed.single { it.purpose == "PIN" }.backing,
        )
        assertEquals("PAX-A920-0012938", state.saved?.serialNumber)
    }

    @Test
    fun `redeem carries the serial number and both public keys`() = runTest {
        val gateway = FakeGateway()

        useCase(gateway = gateway)("ABCD-1234")

        val request = gateway.redeemRequest!!
        assertEquals("PAX-A920-0012938", request.serialNumber)
        assertEquals("rsa-spki", request.rsaPublicKey)
        assertEquals("eddsa-raw", request.eddsaPublicKey)
    }

    @Test
    fun `the challenge code is normalized before it is sent`() = runTest {
        val gateway = FakeGateway()

        useCase(gateway = gateway)("  abcd-1234 ")

        // Server menormalkan dengan cara yang sama, jadi input manual bertanda
        // hubung atau berspasi tetap cocok dengan hasil scan.
        assertEquals("abcd1234", gateway.redeemRequest!!.challengeCode)
    }

    @Test
    fun `activate sends a KCV for every purpose that was installed`() = runTest {
        val gateway = FakeGateway()

        useCase(gateway = gateway)("ABCD-1234")

        assertEquals(setOf("PIN", "TRACK"), gateway.activateRequest!!.keyCheckValues.keys)
    }

    @Test
    fun `an unknown device never reaches the network`() = runTest {
        val gateway = FakeGateway()

        val outcome = useCase(gateway = gateway, serial = null)("ABCD-1234")

        assertEquals("DEVICE_UNKNOWN", (outcome as ProvisioningOutcome.Failure).code)
        assertNull(gateway.redeemRequest)
    }

    @Test
    fun `a rejected challenge code keeps the backend error code`() = runTest {
        val gateway = FakeGateway(
            redeemResult = ApiResult.Failure(
                ApiError("PROVISIONING_TOKEN_INVALID", "Kode QR kedaluwarsa", 410)
            )
        )

        val outcome = useCase(gateway = gateway)("ABCD-1234")

        assertEquals("PROVISIONING_TOKEN_INVALID", (outcome as ProvisioningOutcome.Failure).code)
    }

    @Test
    fun `a failed activate wipes every key that was already installed`() = runTest {
        val installer = FakeTerminalKeyInstaller()
        val keys = FakeKeys()
        val state = FakeState()
        val gateway = FakeGateway(
            activateResult = ApiResult.Failure(ApiError("TERMINAL_INACTIVE", "Tidak aktif", 403))
        )

        val outcome = useCase(gateway = gateway, keys = keys, state = state, installer = installer)("ABCD-1234")

        // Ini inti aturan atomic: activate gagal SETELAH key terpasang, jadi
        // rollback harus benar-benar mencabutnya -- bukan meninggalkan device
        // dengan key yang backend tidak tahu keberadaannya.
        assertEquals("TERMINAL_INACTIVE", (outcome as ProvisioningOutcome.Failure).code)
        assertEquals(1, installer.wipeCount)
        assertEquals(1, keys.clearCount)
        assertNull(state.saved)
    }

    @Test
    fun `a corrupted key package rolls back before anything is installed`() = runTest {
        val installer = FakeTerminalKeyInstaller()
        val keys = FakeKeys()

        val outcome = useCase(keys = keys, installer = installer, unwrap = {
            throw PackageIntegrityException("KCV tidak cocok untuk purpose PIN")
        })("ABCD-1234")

        assertEquals("PACKAGE_INVALID", (outcome as ProvisioningOutcome.Failure).code)
        assertTrue(installer.installedPurposes.isEmpty())
        assertEquals(1, installer.wipeCount)
        assertEquals(1, keys.clearCount)
    }

    @Test
    fun `a refused key installation rolls back and names the purpose`() = runTest {
        val installer = FakeTerminalKeyInstaller(failOnPurpose = "TRACK")
        val state = FakeState()

        val outcome = useCase(installer = installer, state = state)("ABCD-1234")

        val failure = outcome as ProvisioningOutcome.Failure
        assertEquals("KEY_INSTALL_FAILED", failure.code)
        assertTrue(failure.message, failure.message.contains("TRACK"))
        assertEquals(1, installer.wipeCount)
        assertNull(state.saved)
    }

    @Test
    fun `the journal records every step and never leaks key bytes`() = runTest {
        val outcome = useCase()("ABCD-1234")

        val journal = (outcome as ProvisioningOutcome.Success).journalText
        listOf("DETECT_DEVICE", "GENERATE_KEYS", "REDEEM", "DOWNLOAD_PACKAGE", "UNWRAP_PACKAGE", "INSTALL_KEYS", "ACTIVATE", "PERSIST_STATE")
            .forEach { assertTrue("missing $it", journal.contains(it)) }

        // IPEK di tes ini seluruhnya byte 0x01; kalau pernah dirender mentah,
        // pola itu akan muncul.
        assertTrue(journal, !journal.contains("01010101"))
    }
}
```

- [ ] **Step 5: Jalankan tes, pastikan gagal**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*ProvisionDeviceUseCaseTest" --no-daemon`
Expected: FAIL — `Unresolved reference: ProvisionDeviceUseCase`.

- [ ] **Step 6: Jadikan `PackageUnwrapper` bisa di-override untuk tes**

Di `PackageUnwrapper.kt`, ubah deklarasi kelas dan method-nya:

```kotlin
open class PackageUnwrapper(
    private val unwrapper: RsaUnwrapper,
    private val gson: Gson = Gson(),
) {

    open fun unwrap(wrappedPackageKeyBase64: String): List<TerminalKeyMaterial> {
```

Sisanya tidak berubah.

- [ ] **Step 7: Tulis `ProvisionDeviceUseCase.kt`**

```kotlin
package com.cashup.provisioning.domain

import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.KeyInstallOutcome
import com.cashup.devicesdk.SerialNumberProvider
import com.cashup.devicesdk.TerminalKeyInstallResult
import com.cashup.devicesdk.TerminalKeyInstaller
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.provisioning.audit.Evidence
import com.cashup.provisioning.audit.ProvisioningJournal
import com.cashup.provisioning.audit.ProvisioningStep
import com.cashup.provisioning.crypto.PackageIntegrityException
import com.cashup.provisioning.crypto.PackageUnwrapper
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.crypto.keyCheckValue
import com.cashup.provisioning.data.local.ProvisioningState
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.QrRedeemRequest

/**
 * Ceremony provisioning J1, sepuluh langkah, atomic.
 *
 * Aturan yang mengikat seluruh kelas ini: **gagal di langkah mana pun
 * mengembalikan device ke keadaan belum terprovisioning.** Tidak ada state
 * setengah jalan. Kegagalan sebagian — DUKPT terpasang tapi `activate` ditolak,
 * misalnya — akan meninggalkan terminal memegang key yang backend tidak tahu
 * keberadaannya, dan itu baru ketahuan saat transaksi pertama ditolak host.
 * Karena itu setiap jalur keluar yang bukan sukses melewati [rollback].
 *
 * Parameter [journal] **sementara** — alat bantu pelaporan tahap awal, dicabut
 * bersama package `audit/` sebelum produksi. Ia punya nilai default supaya
 * pencabutannya tidak menyentuh pemanggil mana pun.
 */
class ProvisionDeviceUseCase(
    private val gateway: ProvisioningGateway,
    private val serialNumbers: SerialNumberProvider,
    private val keys: ProvisioningKeys,
    private val installer: TerminalKeyInstaller,
    private val state: ProvisioningStateRepository,
    private val unwrapperFactory: (RsaUnwrapper) -> PackageUnwrapper = { PackageUnwrapper(it) },
    private val journal: ProvisioningJournal = ProvisioningJournal(),
) {

    suspend operator fun invoke(rawChallengeCode: String): ProvisioningOutcome {
        journal.clear()

        // 1. Nomor seri. Tanpa ini backend tidak punya identitas untuk device
        //    ini, jadi jaringan tidak perlu disentuh sama sekali.
        journal.start(ProvisioningStep.DETECT_DEVICE)
        val serialNumber = serialNumbers.serialNumber()
        if (serialNumber.isNullOrBlank()) {
            journal.failed(ProvisioningStep.DETECT_DEVICE, DEVICE_UNKNOWN)
            return fail(DEVICE_UNKNOWN, "Perangkat tidak dikenali SDK vendor mana pun")
        }
        journal.ok(ProvisioningStep.DETECT_DEVICE, mapOf("serialNumber" to serialNumber))

        // 2. Keypair. Idempoten: percobaan ulang setelah gagal memakai key yang
        //    sama, sehingga public key yang didaftarkan tetap konsisten.
        journal.start(ProvisioningStep.GENERATE_KEYS)
        val rsa = keys.ensureRsaKeyPair()
        val eddsaPublicKey = keys.ensureEd25519KeyPair()
        journal.ok(
            ProvisioningStep.GENERATE_KEYS,
            mapOf(
                "rsa.location" to rsa.location.name,
                "rsa.publicKey" to Evidence.publicKey(rsa.publicKeySpkiBase64),
                "eddsa.publicKey" to Evidence.publicKey(eddsaPublicKey),
            ),
        )

        // 3. Kode QR. Dinormalisasi sama seperti di sisi server, supaya input
        //    manual bertanda hubung atau berspasi tetap cocok dengan hasil scan.
        val challengeCode = rawChallengeCode.filter(Char::isLetterOrDigit)
        if (challengeCode.isEmpty()) {
            journal.failed(ProvisioningStep.SCAN_QR, CHALLENGE_EMPTY)
            return fail(CHALLENGE_EMPTY, "Kode provisioning kosong")
        }
        journal.ok(ProvisioningStep.SCAN_QR, mapOf("challengeCode" to Evidence.masked(challengeCode)))

        // 4. Redeem. Satu-satunya panggilan yang tidak ditandatangani.
        journal.start(ProvisioningStep.REDEEM)
        val redeemed = when (
            val result = gateway.redeem(
                QrRedeemRequest(
                    challengeCode = challengeCode,
                    serialNumber = serialNumber,
                    rsaPublicKey = rsa.publicKeySpkiBase64,
                    eddsaPublicKey = eddsaPublicKey,
                )
            )
        ) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                journal.failed(ProvisioningStep.REDEEM, result.error.code)
                return fail(result.error.code, result.error.message)
            }
        }
        journal.ok(ProvisioningStep.REDEEM, mapOf("orderId" to redeemed.orderId))

        // Mulai di sini backend sudah menerbitkan order, jadi setiap kegagalan
        // harus melewati rollback.
        journal.start(ProvisioningStep.DOWNLOAD_PACKAGE)
        val keyPackage = when (
            val result = gateway.downloadKeyPackage(
                KeyPackageRequest(redeemed.orderId, redeemed.activationToken)
            )
        ) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                journal.failed(ProvisioningStep.DOWNLOAD_PACKAGE, result.error.code)
                return rollbackAndFail(result.error.code, result.error.message)
            }
        }
        journal.ok(
            ProvisioningStep.DOWNLOAD_PACKAGE,
            mapOf("wrappedPackageKey" to Evidence.secret(keyPackage.wrappedPackageKey)),
        )

        // 6-7. Buka paket dan verifikasi KCV tiap purpose. Ini satu-satunya
        //      kesempatan mendeteksi key rusak: setelah masuk modul vendor, IPEK
        //      tidak bisa dibaca kembali.
        journal.start(ProvisioningStep.UNWRAP_PACKAGE)
        val materials: List<TerminalKeyMaterial> = try {
            unwrapperFactory(keys.unwrapper()).unwrap(keyPackage.wrappedPackageKey)
        } catch (e: PackageIntegrityException) {
            journal.failed(ProvisioningStep.UNWRAP_PACKAGE, PACKAGE_INVALID)
            return rollbackAndFail(PACKAGE_INVALID, e.message ?: "Paket key tidak sah")
        }
        val checkValues = materials.associate { it.purpose to keyCheckValue(it.ipek.copyOf()) }
        journal.ok(
            ProvisioningStep.UNWRAP_PACKAGE,
            materials.associate { "${it.purpose}.ipek" to Evidence.secret(it.ipek) } +
                materials.associate { "${it.purpose}.ksn" to Evidence.secret(it.ksn) },
        )
        journal.ok(ProvisioningStep.VERIFY_KCV, checkValues.mapKeys { "${it.key}.kcv" })

        // 8. Pasang. Modul vendor dulu untuk purpose yang dinominasikan, sisanya
        //    ke vault.
        journal.start(ProvisioningStep.INSTALL_KEYS)
        val installed: List<KeyInstallOutcome> = when (val result = installer.install(materials)) {
            is TerminalKeyInstallResult.Installed -> result.outcomes
            is TerminalKeyInstallResult.Failed -> {
                journal.failed(ProvisioningStep.INSTALL_KEYS, KEY_INSTALL_FAILED, mapOf("purpose" to result.purpose))
                return rollbackAndFail(
                    KEY_INSTALL_FAILED,
                    "Gagal memasang key untuk purpose ${result.purpose}: ${result.reason}",
                )
            }
        }
        materials.forEach { it.zeroize() }
        journal.ok(
            ProvisioningStep.INSTALL_KEYS,
            installed.associate { it.purpose to it.backing.name },
        )

        // 9. Activate. Mengirim KCV sebagai bukti ke backend.
        journal.start(ProvisioningStep.ACTIVATE)
        val activated = when (
            val result = gateway.activate(
                redeemed.orderId,
                ActivateRequest(redeemed.activationToken, checkValues),
            )
        ) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                journal.failed(ProvisioningStep.ACTIVATE, result.error.code)
                return rollbackAndFail(result.error.code, result.error.message)
            }
        }
        journal.ok(ProvisioningStep.ACTIVATE, mapOf("status" to activated.status))

        // 10. Simpan. Baru di sini device dianggap terprovisioning.
        journal.start(ProvisioningStep.PERSIST_STATE)
        state.save(
            ProvisioningState(
                serialNumber = serialNumber,
                orderId = redeemed.orderId,
                backings = installed.associate { it.purpose to it.backing.name },
            )
        )
        journal.ok(ProvisioningStep.PERSIST_STATE)

        return ProvisioningOutcome.Success(
            serialNumber = serialNumber,
            orderId = redeemed.orderId,
            installed = installed,
            journalText = journal.render(),
        )
    }

    /**
     * Mengembalikan device ke keadaan belum terprovisioning.
     *
     * Ketiganya dijalankan tanpa syarat, tanpa berhenti di kegagalan pertama:
     * rollback separuh jalan adalah keadaan yang justru hendak dicegah.
     */
    private suspend fun rollback() {
        journal.start(ProvisioningStep.ROLLBACK)
        runCatching { installer.wipe() }
        runCatching { keys.clearAll() }
        runCatching { state.clear() }
        journal.ok(ProvisioningStep.ROLLBACK)
    }

    private suspend fun rollbackAndFail(code: String, message: String): ProvisioningOutcome {
        rollback()
        return fail(code, message)
    }

    private fun fail(code: String, message: String) =
        ProvisioningOutcome.Failure(code, message, journal.render())

    companion object {
        const val DEVICE_UNKNOWN = "DEVICE_UNKNOWN"
        const val CHALLENGE_EMPTY = "CHALLENGE_EMPTY"
        const val PACKAGE_INVALID = "PACKAGE_INVALID"
        const val KEY_INSTALL_FAILED = "KEY_INSTALL_FAILED"
    }
}
```

- [ ] **Step 8: Jalankan tes, pastikan lolos**

Run: `./gradlew :provisioning-core:testDebugUnitTest --tests "*ProvisionDeviceUseCaseTest" --no-daemon`
Expected: PASS, 10 tes.

- [ ] **Step 9: Jalankan seluruh tes module**

Run: `./gradlew :provisioning-core:testDebugUnitTest --no-daemon`
Expected: PASS, 39 tes (4 repository + 4 KCV + 5 unwrapper + 3 lokasi RSA + 7 evidence + 6 jurnal + 10 use case).

- [ ] **Step 10: Commit**

```bash
git add provisioning-core/
git commit -m "feat(provisioning-core): add the atomic provisioning use case

Ten steps, with one binding rule: a failure at any step returns the device
to unprovisioned. A partial success -- DUKPT installed but activate
refused, say -- would leave a terminal holding keys the backend does not
know about, and that surfaces as the first transaction being declined by
the host rather than as a provisioning error. Every non-success exit
therefore goes through rollback, which runs all three cleanups
unconditionally rather than stopping at the first failure; a half-finished
rollback is the state it exists to prevent.

Three small interfaces -- ProvisioningGateway, ProvisioningKeys,
ProvisioningStateRepository -- let the whole flow, rollback included, run
on the JVM with fakes. Rollback is both the least exercised path and the
most expensive one to get wrong, so it should not need hardware to test.
Ten tests cover the clean run and every failure point.

The challenge code is normalized to letters and digits before it is sent,
matching what the server does, so a manually typed code with hyphens or
spaces still matches the scanned one.

Key material is zeroed immediately after installation, and the journal
only ever receives values through Evidence."
```

---

## Task 12: Pemindaian QR — scanner vendor dulu, kamera sebagai cadangan

> **Urutan:** task ini menaruh berkas di module `:app`, yang baru dibuat di
> Task 13. Kerjakan **Task 13 Step 1–3 lebih dulu** (daftarkan module, tulis
> `app/build.gradle.kts` dan manifest-nya), lalu kembali ke sini. Sisa Task 13
> dikerjakan setelah task ini selesai.

Banyak terminal EDC punya scanner hardware. Memakainya lebih murah daripada CameraX: tidak ada preview yang harus dirender, tidak ada dekoder yang jalan per frame, dan itu berarti sesuatu di device 1 GB.

Cadangannya CameraX + **ZXing**, bukan ML Kit. ML Kit membawa model on-device sekitar 3–4 MB beserta pemakaian memorinya; `edc-mobile` memakainya karena berjalan di HP modern dengan `minSdk 33`, bukan di terminal yang kita tuju.

**Files:**
- Create: `device-sdk-edcsdk/src/main/kotlin/com/cashup/devicesdk/edcsdk/EdcSdkScanner.kt`
- Create: `app/src/main/kotlin/com/cashup/app/scan/CameraQrScanner.kt`
- Create: `app/src/main/kotlin/com/cashup/app/scan/QrScanSource.kt`
- Create: `app/src/test/kotlin/com/cashup/app/scan/QrScanSourceTest.kt`

**Interfaces:**
- Consumes: `Scanner` (sudah ada di `device-sdk-api`, `suspend fun scanQr(timeoutMillis: Long): String?`).
- Produces:
  - `class EdcSdkScanner(context: Context) : Scanner` — memakai `SDKManager.getQrCodeCamera()`
  - `class CameraQrScanner(previewView: PreviewView, lifecycleOwner: LifecycleOwner) : Scanner`
  - `class QrScanSource(vendor: Scanner?, camera: () -> Scanner)` dengan `suspend fun scan(timeoutMillis: Long): String?`

- [ ] **Step 1: Tulis `EdcSdkScanner.kt`**

```kotlin
package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.Scanner
import com.lib.core.SDKManager
import com.lib.core.interfaces.QRCodeListener
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Scanner QR hardware vendor lewat `SDKManager`.
 *
 * `BaseQRCodeCamera.start` meminta `Activity`, bukan `Context`, jadi activity
 * yang sedang aktif diambil dari `SDKManager.currentActivity` — SDK melacaknya
 * sendiri lewat `ActivityLifecycleCallbacks`.
 *
 * Mengembalikan `null` kalau device tidak punya scanner, atau kalau tidak ada
 * hasil sampai [timeoutMillis] habis. Pemanggil yang memutuskan apa artinya;
 * lihat `QrScanSource`.
 *
 * Kamera ditutup di setiap jalur keluar, termasuk saat coroutine dibatalkan —
 * scanner vendor yang dibiarkan terbuka menahan kameranya dan percobaan
 * berikutnya gagal tanpa pesan yang jelas.
 */
class EdcSdkScanner(context: Context) : Scanner {

    private val appContext = context.applicationContext

    override suspend fun scanQr(timeoutMillis: Long): String? {
        val camera = SDKManager.qrCodeCamera ?: return null
        val activity = SDKManager.currentActivity ?: return null

        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    camera.init(appContext)
                    camera.qrCodeListener = QRCodeListener { value ->
                        if (continuation.isActive) continuation.resume(value)
                    }
                    continuation.invokeOnCancellation { runCatching { camera.close() } }
                    camera.start(activity)
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            runCatching { camera.close() }
        }
    }
}
```

- [ ] **Step 2: Tulis tes yang gagal untuk `QrScanSource`**

`app/src/test/kotlin/com/cashup/app/scan/QrScanSourceTest.kt`:

```kotlin
package com.cashup.app.scan

import com.cashup.devicesdk.Scanner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanSourceTest {

    private class FakeScanner(private val result: String?) : Scanner {
        var called = false
        override suspend fun scanQr(timeoutMillis: Long): String? {
            called = true
            return result
        }
    }

    @Test
    fun `uses the vendor scanner when it returns a code`() = runTest {
        val vendor = FakeScanner("ABCD-1234")
        val camera = FakeScanner("WRONG")

        val result = QrScanSource(vendor) { camera }.scan(10_000)

        assertEquals("ABCD-1234", result)
        assertFalse("camera must stay off when the vendor scanner worked", camera.called)
    }

    @Test
    fun `falls back to the camera when there is no vendor scanner`() = runTest {
        val camera = FakeScanner("ABCD-1234")

        val result = QrScanSource(vendor = null) { camera }.scan(10_000)

        assertEquals("ABCD-1234", result)
        assertTrue(camera.called)
    }

    @Test
    fun `falls back to the camera when the vendor scanner yields nothing`() = runTest {
        val vendor = FakeScanner(null)
        val camera = FakeScanner("ABCD-1234")

        val result = QrScanSource(vendor) { camera }.scan(10_000)

        assertEquals("ABCD-1234", result)
        assertTrue(camera.called)
    }

    @Test
    fun `returns null when neither source produces a code`() = runTest {
        val result = QrScanSource(FakeScanner(null)) { FakeScanner(null) }.scan(10_000)

        assertNull(result)
    }
}
```

- [ ] **Step 3: Jalankan tes, pastikan gagal**

Run: `./gradlew :app:testDebugUnitTest --tests "*QrScanSourceTest" --no-daemon`
Expected: FAIL — module `:app` belum ada. Task 13 membuatnya; **kerjakan Task 13 Step 1–3 lebih dulu**, lalu kembali ke sini.

- [ ] **Step 4: Tulis `QrScanSource.kt`**

```kotlin
package com.cashup.app.scan

import com.cashup.devicesdk.Scanner

/**
 * Mencoba scanner hardware vendor dulu, jatuh ke kamera kalau perlu.
 *
 * Kamera dibuat lewat lambda, bukan diterima jadi, supaya CameraX tidak
 * di-inisialisasi sama sekali di terminal yang punya scanner hardware — di
 * device 1 GB, preview yang tidak pernah dipakai tetap memakan memori.
 *
 * Vendor mengembalikan `null` baik saat scanner tidak ada maupun saat waktunya
 * habis. Keduanya diperlakukan sama: coba kamera. Teknisi yang menunggu lebih
 * peduli pada QR-nya terbaca daripada pada alat mana yang membacanya.
 */
class QrScanSource(
    private val vendor: Scanner?,
    private val camera: () -> Scanner,
) {
    suspend fun scan(timeoutMillis: Long): String? =
        vendor?.scanQr(timeoutMillis) ?: camera().scanQr(timeoutMillis)
}
```

- [ ] **Step 5: Tulis `CameraQrScanner.kt`**

```kotlin
package com.cashup.app.scan

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.cashup.devicesdk.Scanner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Cadangan untuk terminal tanpa scanner hardware.
 *
 * ZXing, bukan ML Kit: ML Kit membawa model on-device beberapa megabyte beserta
 * pemakaian memorinya, dan target kita terminal 1 GB. QR provisioning adalah
 * kode cetak beresolusi tinggi dari dashboard — ZXing lebih dari cukup.
 *
 * Analyzer dibatasi ke satu frame terakhir (`STRATEGY_KEEP_ONLY_LATEST`) supaya
 * frame tidak menumpuk saat CPU lambat, dan setiap `ImageProxy` ditutup di
 * `finally` — ImageProxy yang bocor akan menghentikan aliran frame sepenuhnya.
 */
class CameraQrScanner(
    private val previewView: PreviewView,
    private val lifecycleOwner: LifecycleOwner,
) : Scanner {

    override suspend fun scanQr(timeoutMillis: Long): String? {
        val context = previewView.context
        val executor = Executors.newSingleThreadExecutor()
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true))
        }

        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val providerFuture = ProcessCameraProvider.getInstance(context)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().apply {
                            setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .apply {
                                setAnalyzer(executor) { proxy ->
                                    val decoded = decode(proxy, reader)
                                    if (decoded != null && continuation.isActive) {
                                        continuation.resume(decoded)
                                    }
                                }
                            }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                        continuation.invokeOnCancellation { provider.unbindAll() }
                    }, ContextCompat.getMainExecutor(context))
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            executor.shutdown()
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }
    }

    private fun decode(proxy: ImageProxy, reader: MultiFormatReader): String? = try {
        val buffer = proxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val source = PlanarYUVLuminanceSource(
            bytes, proxy.width, proxy.height, 0, 0, proxy.width, proxy.height, false,
        )
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
    } catch (e: Exception) {
        // Frame tanpa QR melempar NotFoundException -- itu keadaan normal, bukan
        // kesalahan, dan terjadi puluhan kali per detik. Jangan dicatat.
        null
    } finally {
        proxy.close()
    }
}
```

- [ ] **Step 6: Jalankan tes, pastikan lolos**

Run: `./gradlew :app:testDebugUnitTest --tests "*QrScanSourceTest" --no-daemon`
Expected: PASS, 4 tes.

- [ ] **Step 7: Commit**

```bash
git add device-sdk-edcsdk/src/main/kotlin/com/cashup/devicesdk/edcsdk/EdcSdkScanner.kt app/src/main/kotlin/com/cashup/app/scan/ app/src/test/kotlin/com/cashup/app/scan/
git commit -m "feat: scan provisioning QR with the vendor scanner, camera as fallback

Most EDC terminals have a hardware scanner, and using it avoids rendering
a preview and running a decoder per frame -- which matters on a 1 GB
device. QrScanSource builds the camera through a lambda so CameraX is
never initialised at all on terminals that have one.

The fallback uses ZXing rather than ML Kit. ML Kit ships a multi-megabyte
on-device model; edc-mobile can afford it because it runs on modern phones
at minSdk 33, and these terminals are neither. A provisioning QR is a
high-resolution printed code, which ZXing reads comfortably.

Both scanners close their camera on every exit path including
cancellation: a vendor scanner left open holds the camera and the next
attempt fails without a clear message. The analyzer keeps only the latest
frame and always closes its ImageProxy, since a leaked one stops the
frame stream entirely."
```

---

## Task 13: Module `app` — shell, DI, dan ViewModel

Module `com.android.application` pertama di repo ini. XML Views + ViewBinding, bukan Compose — alasannya di spec §6.

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/network_security_config.xml`
- Create: `app/src/main/kotlin/com/cashup/app/CashupApp.kt`
- Create: `app/src/main/kotlin/com/cashup/app/di/AppContainer.kt`
- Create: `app/src/main/kotlin/com/cashup/app/AppConfig.kt`
- Create: `app/src/main/kotlin/com/cashup/app/ui/provisioning/ProvisioningUiState.kt`
- Create: `app/src/main/kotlin/com/cashup/app/ui/provisioning/ProvisioningViewModel.kt`
- Create: `app/src/test/kotlin/com/cashup/app/ui/provisioning/ProvisioningViewModelTest.kt`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: semua yang dihasilkan Task 2, 4, 5, 7, 9, 10, 11, 12.
- Produces:
  - `class AppConfig(context: Context)` dengan `var baseUrl: String`
  - `class AppContainer(context: Context)` — wiring manual
  - `sealed interface ProvisioningUiState` dengan `Idle`, `Scanning`, `Processing`, `Success`, `Failure`
  - `class ProvisioningViewModel(container: AppContainer)` dengan `val state: StateFlow<ProvisioningUiState>`, `fun provision(challengeCode: String)`, `fun reset()`

- [ ] **Step 1: Daftarkan module**

Di `settings.gradle.kts`:

```kotlin
include(":app")
```

- [ ] **Step 2: Buat `app/build.gradle.kts`**

```kotlin
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
```

Navigation 2.5.3 dan CameraX 1.2.3, bukan versi terbaru: rilis setelahnya menuntut `compileSdk 34`, sementara kita di 33.

- [ ] **Step 3: Buat manifest dan konfigurasi jaringan**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application
        android:name=".CashupApp"
        android:allowBackup="false"
        android:label="Cashup Payment"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Cleartext diizinkan HANYA untuk host pengembangan di Tailnet. Backend produksi
  wajib HTTPS; jangan menambahkan host publik ke daftar ini.
-->
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">100.103.104.38</domain>
    </domain-config>
</network-security-config>
```

- [ ] **Step 4: Tulis `AppConfig.kt`**

```kotlin
package com.cashup.app

import android.content.Context

/**
 * Base URL Front-facing API. Bukan rahasia — boleh di `SharedPreferences` biasa.
 *
 * Default menunjuk ke corepayment pengembangan di Raspberry Pi lewat Tailnet,
 * host yang sama yang dipakai `edc-mobile`. Produksi mengarah ke Front-facing
 * API lewat HTTPS.
 */
class AppConfig(context: Context) {

    private val prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trimEnd('/')).apply()

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val DEFAULT_BASE_URL = "http://100.103.104.38:8080"
    }
}
```

- [ ] **Step 5: Tulis `AppContainer.kt` dan `CashupApp.kt`**

`app/src/main/kotlin/com/cashup/app/di/AppContainer.kt`:

```kotlin
package com.cashup.app.di

import android.content.Context
import com.cashup.app.AppConfig
import com.cashup.app.BuildConfig
import com.cashup.common.logging.NoOpPaymentLogger
import com.cashup.common.logging.PaymentLogger
import com.cashup.devicesdk.edcsdk.EdcSdkScanner
import com.cashup.devicesdk.edcsdk.EdcSdkSerialNumberProvider
import com.cashup.devicesdk.edcsdk.EdcSdkTerminalKeyInstaller
import com.cashup.provisioning.AndroidProvisioningKeys
import com.cashup.provisioning.StoredDeviceSigner
import com.cashup.provisioning.audit.ProvisioningJournal
import com.cashup.provisioning.crypto.Ed25519KeyStore
import com.cashup.provisioning.crypto.Mgf1Digest
import com.cashup.provisioning.crypto.RsaKeyStore
import com.cashup.provisioning.data.local.ProvisioningStateStore
import com.cashup.provisioning.data.remote.ProvisioningHttp
import com.cashup.provisioning.domain.ProvisionDeviceUseCase

/**
 * Wiring manual di satu tempat, tanpa framework DI.
 *
 * Hilt akan menambah kapt/ksp dan biaya startup demi keuntungan yang tidak
 * terasa pada app sekecil ini, sementara targetnya terminal 1 GB (spec §6).
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val config = AppConfig(appContext)

    private val logger: PaymentLogger = NoOpPaymentLogger

    private val stateStore = ProvisioningStateStore(appContext)
    private val ed25519 = Ed25519KeyStore(appContext)

    /**
     * `Mgf1Digest.SHA1` adalah asumsi sampai backend mengonfirmasi (spec §8
     * item 1b). Ini yang menentukan apakah key RSA bisa dibuat di TEE — salah
     * menebaknya berarti device mendaftarkan public key yang tidak akan pernah
     * bisa membuka paketnya sendiri, jadi jangan diubah tanpa jawaban dari
     * backend.
     */
    private val rsa = RsaKeyStore(appContext, requiredMgf1 = Mgf1Digest.SHA1)

    val deviceSigner = StoredDeviceSigner(stateStore, ed25519)

    val serialNumbers = EdcSdkSerialNumberProvider(appContext)

    val vendorScanner = EdcSdkScanner(appContext)

    fun isProvisioned(): Boolean = stateStore.current() != null

    fun provisionDeviceUseCase(): ProvisionDeviceUseCase {
        val repository = ProvisioningHttp.create(
            baseUrl = config.baseUrl,
            deviceSigner = deviceSigner,
            debugLogging = BuildConfig.DEBUG,
        )
        return ProvisionDeviceUseCase(
            gateway = repository,
            serialNumbers = serialNumbers,
            keys = AndroidProvisioningKeys(rsa, ed25519),
            installer = EdcSdkTerminalKeyInstaller(appContext),
            state = stateStore,
            // SEMENTARA -- jurnal hanya diisi di build debug. Dicabut bersama
            // package audit/ sebelum produksi; lihat Task 10.
            journal = ProvisioningJournal(if (BuildConfig.PROVISIONING_JOURNAL) logger else NoOpPaymentLogger),
        )
    }
}
```

`app/src/main/kotlin/com/cashup/app/CashupApp.kt`:

```kotlin
package com.cashup.app

import android.app.Application
import com.cashup.app.di.AppContainer

class CashupApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
```

- [ ] **Step 6: Tulis `ProvisioningUiState.kt`**

```kotlin
package com.cashup.app.ui.provisioning

import com.cashup.devicesdk.KeyInstallOutcome

/**
 * Sealed interface, bukan satu data class dengan `isLoading`/`error`/`data`
 * nullable sekaligus — dengan begitu kombinasi mustahil seperti "sedang memuat
 * sekaligus gagal" tidak bisa dibentuk sama sekali.
 *
 * `journalText` **sementara**, dicabut bersama package `audit/` sebelum
 * produksi (Task 10).
 */
sealed interface ProvisioningUiState {

    data object Idle : ProvisioningUiState

    data object Scanning : ProvisioningUiState

    data object Processing : ProvisioningUiState

    data class Success(
        val serialNumber: String,
        val orderId: String,
        val installed: List<KeyInstallOutcome>,
        val journalText: String,
    ) : ProvisioningUiState

    data class Failure(
        val code: String,
        val message: String,
        val journalText: String,
    ) : ProvisioningUiState
}
```

- [ ] **Step 7: Tulis tes yang gagal untuk ViewModel**

`app/src/test/kotlin/com/cashup/app/ui/provisioning/ProvisioningViewModelTest.kt`:

```kotlin
package com.cashup.app.ui.provisioning

import com.cashup.devicesdk.KeyBacking
import com.cashup.devicesdk.KeyInstallOutcome
import com.cashup.provisioning.domain.ProvisioningOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProvisioningViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun successOutcome() = ProvisioningOutcome.Success(
        serialNumber = "PAX-A920-0012938",
        orderId = "o-1",
        installed = listOf(KeyInstallOutcome("PIN", KeyBacking.VENDOR_SECURE_MODULE)),
        journalText = "OK REDEEM",
    )

    @Test
    fun `starts idle`() = runTest {
        val viewModel = ProvisioningViewModel { successOutcome() }

        assertEquals(ProvisioningUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `moves through Processing to Success`() = runTest {
        var provisioned = 0
        val viewModel = ProvisioningViewModel { provisioned++; successOutcome() }

        viewModel.provision("ABCD-1234")
        assertEquals(ProvisioningUiState.Processing, viewModel.state.value)

        advanceUntilIdle()
        val state = viewModel.state.value
        assertTrue(state.toString(), state is ProvisioningUiState.Success)
        assertEquals("PAX-A920-0012938", (state as ProvisioningUiState.Success).serialNumber)
        assertEquals(1, provisioned)
    }

    @Test
    fun `a second call while one is running is ignored`() = runTest {
        var provisioned = 0
        val viewModel = ProvisioningViewModel { provisioned++; successOutcome() }

        // Scanner QR mendeteksi frame yang sama berkali-kali dalam sepersekian
        // detik. Tanpa guard ini, kode sekali-pakai ditembak dua kali: yang
        // pertama berhasil, yang kedua ditolak -- dan penolakan itulah yang
        // dilihat teknisi.
        viewModel.provision("ABCD-1234")
        viewModel.provision("ABCD-1234")
        advanceUntilIdle()

        assertEquals(1, provisioned)
    }

    @Test
    fun `a failure surfaces the backend code`() = runTest {
        val viewModel = ProvisioningViewModel {
            ProvisioningOutcome.Failure("PROVISIONING_TOKEN_INVALID", "Kedaluwarsa", "FAILED REDEEM")
        }

        viewModel.provision("ABCD-1234")
        advanceUntilIdle()

        val state = viewModel.state.value as ProvisioningUiState.Failure
        assertEquals("PROVISIONING_TOKEN_INVALID", state.code)
    }

    @Test
    fun `reset returns to idle so the technician can retry`() = runTest {
        val viewModel = ProvisioningViewModel {
            ProvisioningOutcome.Failure("X", "y", "")
        }

        viewModel.provision("ABCD-1234")
        advanceUntilIdle()
        viewModel.reset()

        assertEquals(ProvisioningUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `a retry after a failure is allowed`() = runTest {
        var attempts = 0
        val viewModel = ProvisioningViewModel {
            attempts++
            ProvisioningOutcome.Failure("X", "y", "")
        }

        viewModel.provision("ABCD-1234")
        advanceUntilIdle()
        viewModel.reset()
        viewModel.provision("ABCD-1234")
        advanceUntilIdle()

        assertEquals(2, attempts)
    }
}
```

- [ ] **Step 8: Jalankan tes, pastikan gagal**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProvisioningViewModelTest" --no-daemon`
Expected: FAIL — `Unresolved reference: ProvisioningViewModel`.

- [ ] **Step 9: Tulis `ProvisioningViewModel.kt`**

```kotlin
package com.cashup.app.ui.provisioning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cashup.app.di.AppContainer
import com.cashup.provisioning.domain.ProvisioningOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Menerima lambda, bukan [AppContainer], supaya seluruh mesin state bisa diuji
 * di JVM tanpa Android sama sekali. [Factory] yang menyambungkannya ke container
 * sungguhan.
 */
class ProvisioningViewModel(
    private val provisionDevice: suspend (String) -> ProvisioningOutcome,
) : ViewModel() {

    private val _state = MutableStateFlow<ProvisioningUiState>(ProvisioningUiState.Idle)
    val state: StateFlow<ProvisioningUiState> = _state.asStateFlow()

    /**
     * Guard re-entrancy, dan ini bukan kehati-hatian berlebih: scanner QR
     * mendeteksi frame yang sama berkali-kali dalam sepersekian detik, sebelum
     * hasil percobaan pertama sempat masuk state. Tanpa guard ini kode
     * sekali-pakai ditembak dua kali — percobaan pertama **berhasil**, yang
     * kedua ditolak karena kodenya sudah terpakai, dan penolakan itulah yang
     * dilihat teknisi.
     *
     * Flag di sini, bukan `isEnabled = false` di UI: yang terakhir baru berlaku
     * setelah render berikutnya, dan frame kedua sudah datang sebelum itu.
     */
    private var inFlight = false

    fun provision(challengeCode: String) {
        if (inFlight) return
        inFlight = true
        _state.value = ProvisioningUiState.Processing

        viewModelScope.launch {
            try {
                _state.value = when (val outcome = provisionDevice(challengeCode)) {
                    is ProvisioningOutcome.Success -> ProvisioningUiState.Success(
                        serialNumber = outcome.serialNumber,
                        orderId = outcome.orderId,
                        installed = outcome.installed,
                        journalText = outcome.journalText,
                    )
                    is ProvisioningOutcome.Failure -> ProvisioningUiState.Failure(
                        code = outcome.code,
                        message = outcome.message,
                        journalText = outcome.journalText,
                    )
                }
            } finally {
                inFlight = false
            }
        }
    }

    fun scanning() {
        if (!inFlight) _state.value = ProvisioningUiState.Scanning
    }

    fun reset() {
        if (!inFlight) _state.value = ProvisioningUiState.Idle
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProvisioningViewModel { code -> container.provisionDeviceUseCase().invoke(code) } as T
    }
}
```

- [ ] **Step 10: Jalankan tes, pastikan lolos**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProvisioningViewModelTest" --no-daemon`
Expected: PASS, 6 tes.

- [ ] **Step 11: Commit**

```bash
git add settings.gradle.kts app/
git commit -m "feat(app): add the application shell, manual DI and the provisioning ViewModel

XML Views with ViewBinding rather than Compose: the target is 1 GB EDC
terminals on Android 7-11, where Compose loads thousands of classes before
first frame and its remedy, Baseline Profiles, only fully applies from API
28. edc-mobile's Compose choice came with minSdk 33 and was never meant
for this hardware.

Dependencies are pinned below their latest releases on purpose --
Navigation 2.5.3 and CameraX 1.2.3 are the last versions that build
against compileSdk 33.

ProvisioningViewModel takes a lambda rather than the container so the
whole state machine runs on the JVM without Android. Its re-entrancy guard
is a field rather than a disabled button: the QR scanner reports the same
frame several times within a fraction of a second, and disabling a control
only takes effect on the next render, by which point the one-shot code has
already been redeemed twice -- the first succeeding, the second failing,
and only the failure reaching the technician.

Cleartext HTTP is permitted for the single Tailnet development host and
nothing else."
```


---

## Task 14: Empat layar provisioning

Gate → Scan QR → Processing → Result. Layout dibuat datar dengan `ConstraintLayout` tunggal per layar; hierarki berlapis mahal di-`measure` pada GPU/CPU lemah (spec §11).

**Files:**
- Create: `app/src/main/kotlin/com/cashup/app/MainActivity.kt`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/navigation/nav_provisioning.xml`
- Create: `app/src/main/kotlin/com/cashup/app/ui/provisioning/GateFragment.kt`
- Create: `app/src/main/kotlin/com/cashup/app/ui/provisioning/ScanQrFragment.kt` + `app/src/main/res/layout/fragment_scan_qr.xml`
- Create: `app/src/main/kotlin/com/cashup/app/ui/provisioning/ProcessingFragment.kt` + `app/src/main/res/layout/fragment_processing.xml`
- Create: `app/src/main/kotlin/com/cashup/app/ui/provisioning/ResultFragment.kt` + `app/src/main/res/layout/fragment_result.xml`
- Create: `app/src/main/kotlin/com/cashup/app/ui/provisioning/ErrorHints.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/kotlin/com/cashup/app/ui/provisioning/ErrorHintsTest.kt`

**Interfaces:**
- Consumes: `ProvisioningViewModel`, `ProvisioningUiState` (Task 13); `QrScanSource`, `CameraQrScanner` (Task 12).
- Produces: `fun hintFor(code: String, fallback: String): Int` — id string resource untuk pesan operator.

- [ ] **Step 1: Tulis tes yang gagal untuk `ErrorHints`**

`app/src/test/kotlin/com/cashup/app/ui/provisioning/ErrorHintsTest.kt`:

```kotlin
package com.cashup.app.ui.provisioning

import com.cashup.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorHintsTest {

    @Test
    fun `known backend codes map to their own message`() {
        assertEquals(R.string.err_token_invalid, hintFor("PROVISIONING_TOKEN_INVALID"))
        assertEquals(R.string.err_already_provisioned, hintFor("TERMINAL_KEY_ALREADY_PROVISIONED"))
        assertEquals(R.string.err_not_registered, hintFor("TERMINAL_NOT_REGISTERED"))
    }

    @Test
    fun `local failure codes map too`() {
        assertEquals(R.string.err_device_unknown, hintFor("DEVICE_UNKNOWN"))
        assertEquals(R.string.err_package_invalid, hintFor("PACKAGE_INVALID"))
        assertEquals(R.string.err_key_install_failed, hintFor("KEY_INSTALL_FAILED"))
    }

    @Test
    fun `an unknown code falls back so the backend message still reaches the technician`() {
        // Daftar kode belum final (spec §8 item 8). Memetakan kode tak dikenal
        // ke pesan generik akan menyembunyikan justru keterangan yang berguna.
        assertEquals(0, hintFor("SOMETHING_NEW"))
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `./gradlew :app:testDebugUnitTest --tests "*ErrorHintsTest" --no-daemon`
Expected: FAIL — `Unresolved reference: hintFor`.

- [ ] **Step 3: Tulis `strings.xml` dan `ErrorHints.kt`**

`app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Cashup Payment</string>

    <string name="scan_title">Pindai QR Provisioning</string>
    <string name="scan_hint">Arahkan ke kode QR dari Cashup backoffice</string>
    <string name="scan_manual_label">Atau ketik kode provisioning</string>
    <string name="scan_manual_action">Lanjut</string>

    <string name="processing_title">Memproses provisioning…</string>

    <string name="result_success_title">Provisioning berhasil</string>
    <string name="result_failure_title">Provisioning gagal</string>
    <string name="result_serial">Serial: %1$s</string>
    <string name="result_order">Order: %1$s</string>
    <string name="result_retry">Coba lagi</string>
    <string name="result_continue">Lanjut</string>
    <string name="result_journal_label">Log proses (khusus tahap uji coba)</string>

    <string name="err_token_invalid">Kode QR tidak valid, sudah dipakai, atau kedaluwarsa. Minta QR baru dari backoffice.</string>
    <string name="err_already_provisioned">Perangkat ini sudah pernah diprovisioning penuh.</string>
    <string name="err_not_registered">Perangkat belum terdaftar atau tidak aktif di backend.</string>
    <string name="err_device_unknown">Perangkat tidak dikenali SDK vendor mana pun.</string>
    <string name="err_package_invalid">Paket key dari backend tidak lolos pemeriksaan. Tidak ada key yang dipasang.</string>
    <string name="err_key_install_failed">Gagal memasang key ke perangkat. Semua key sudah dibersihkan.</string>
</resources>
```

`app/src/main/kotlin/com/cashup/app/ui/provisioning/ErrorHints.kt`:

```kotlin
package com.cashup.app.ui.provisioning

import androidx.annotation.StringRes
import com.cashup.app.R

/**
 * Memetakan kode kegagalan ke pesan operator.
 *
 * Mengembalikan `0` untuk kode yang tidak dikenal, dan pemanggil menampilkan
 * `message` dari backend apa adanya. Daftar kode definitif belum ada (spec §8
 * item 8), jadi memetakan yang tak dikenal ke pesan generik justru akan
 * menyembunyikan keterangan yang paling berguna saat itu.
 *
 * Selalu dipetakan dari `code`, tidak pernah dari `message` — `message`
 * ditujukan untuk manusia dan boleh berubah kapan saja.
 */
@StringRes
fun hintFor(code: String): Int = when (code) {
    "PROVISIONING_TOKEN_INVALID" -> R.string.err_token_invalid
    "TERMINAL_KEY_ALREADY_PROVISIONED" -> R.string.err_already_provisioned
    "TERMINAL_NOT_REGISTERED", "TERMINAL_INACTIVE" -> R.string.err_not_registered
    "DEVICE_UNKNOWN" -> R.string.err_device_unknown
    "PACKAGE_INVALID" -> R.string.err_package_invalid
    "KEY_INSTALL_FAILED" -> R.string.err_key_install_failed
    else -> 0
}
```

- [ ] **Step 4: Jalankan tes, pastikan lolos**

Run: `./gradlew :app:testDebugUnitTest --tests "*ErrorHintsTest" --no-daemon`
Expected: PASS, 3 tes.

- [ ] **Step 5: Tulis `MainActivity` dan grafik navigasi**

`app/src/main/res/layout/activity_main.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.fragment.app.FragmentContainerView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_host"
    android:name="androidx.navigation.fragment.NavHostFragment"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:defaultNavHost="true"
    app:navGraph="@navigation/nav_provisioning" />
```

`app/src/main/res/navigation/nav_provisioning.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_provisioning"
    app:startDestination="@id/gateFragment">

    <fragment
        android:id="@+id/gateFragment"
        android:name="com.cashup.app.ui.provisioning.GateFragment">
        <action android:id="@+id/to_scan" app:destination="@id/scanQrFragment"
            app:popUpTo="@id/gateFragment" app:popUpToInclusive="true" />
    </fragment>

    <fragment
        android:id="@+id/scanQrFragment"
        android:name="com.cashup.app.ui.provisioning.ScanQrFragment"
        tools:layout="@layout/fragment_scan_qr"
        xmlns:tools="http://schemas.android.com/tools">
        <action android:id="@+id/to_processing" app:destination="@id/processingFragment" />
    </fragment>

    <fragment
        android:id="@+id/processingFragment"
        android:name="com.cashup.app.ui.provisioning.ProcessingFragment">
        <action android:id="@+id/to_result" app:destination="@id/resultFragment"
            app:popUpTo="@id/scanQrFragment" app:popUpToInclusive="true" />
    </fragment>

    <fragment
        android:id="@+id/resultFragment"
        android:name="com.cashup.app.ui.provisioning.ResultFragment">
        <action android:id="@+id/to_scan" app:destination="@id/scanQrFragment"
            app:popUpTo="@id/resultFragment" app:popUpToInclusive="true" />
    </fragment>
</navigation>
```

`app/src/main/kotlin/com/cashup/app/MainActivity.kt`:

```kotlin
package com.cashup.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
```

- [ ] **Step 6: Tulis `GateFragment.kt`**

```kotlin
package com.cashup.app.ui.provisioning

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.cashup.app.CashupApp
import com.cashup.app.R

/**
 * Layar tanpa tampilan: menentukan ke mana device pergi saat dibuka.
 *
 * Belum terprovisioning → Scan QR. Sudah → Home. Home belum ada di plan ini,
 * jadi sementara tetap ke Scan QR; yang penting keputusannya sudah punya satu
 * tempat, bukan tersebar di beberapa layar nanti.
 */
class GateFragment : Fragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = (requireActivity().application as CashupApp).container
        if (container.isProvisioned()) {
            // TODO(App Shell): arahkan ke Home begitu layar itu ada.
            findNavController().navigate(R.id.to_scan)
        } else {
            findNavController().navigate(R.id.to_scan)
        }
    }
}
```

- [ ] **Step 7: Tulis layar Scan QR**

`app/src/main/res/layout/fragment_scan_qr.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="16dp">

    <TextView
        android:id="@+id/title"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="@string/scan_title"
        android:textSize="20sp"
        android:textStyle="bold"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <androidx.camera.view.PreviewView
        android:id="@+id/preview"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintDimensionRatio="1:1"
        app:layout_constraintTop_toBottomOf="@id/title"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <TextView
        android:id="@+id/manualLabel"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/scan_manual_label"
        app:layout_constraintTop_toBottomOf="@id/preview"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <EditText
        android:id="@+id/manualCode"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:autofillHints=""
        android:inputType="textCapCharacters"
        app:layout_constraintTop_toBottomOf="@id/manualLabel"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <Button
        android:id="@+id/manualSubmit"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="@string/scan_manual_action"
        app:layout_constraintTop_toBottomOf="@id/manualCode"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

`app/src/main/kotlin/com/cashup/app/ui/provisioning/ScanQrFragment.kt`:

```kotlin
package com.cashup.app.ui.provisioning

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cashup.app.CashupApp
import com.cashup.app.R
import com.cashup.app.databinding.FragmentScanQrBinding
import com.cashup.app.scan.CameraQrScanner
import com.cashup.app.scan.QrScanSource
import kotlinx.coroutines.launch

class ScanQrFragment : Fragment(R.layout.fragment_scan_qr) {

    // activityViewModels, bukan viewModels: Scan, Processing, dan Result adalah
    // tujuan bersebelahan di nav graph, bukan parent-child. ViewModel ber-scope
    // fragment akan memberi masing-masing instance sendiri, dan Processing akan
    // menunggu state yang tidak pernah berubah.
    private val viewModel: ProvisioningViewModel by activityViewModels {
        ProvisioningViewModel.Factory((requireActivity().application as CashupApp).container)
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startScanning() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentScanQrBinding.bind(view)

        binding.manualSubmit.setOnClickListener {
            viewModel.provision(binding.manualCode.text.toString())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (state is ProvisioningUiState.Processing) {
                        findNavController().navigate(R.id.to_processing)
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startScanning()
        } else {
            // Izin kamera diminta tanpa syarat karena scanner vendor belum tentu
            // ada; kalau ternyata ada, kamera tidak akan pernah dinyalakan --
            // QrScanSource membuatnya lewat lambda.
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScanning() {
        val container = (requireActivity().application as CashupApp).container
        val binding = FragmentScanQrBinding.bind(requireView())
        viewLifecycleOwner.lifecycleScope.launch {
            val source = QrScanSource(container.vendorScanner) {
                CameraQrScanner(binding.preview, viewLifecycleOwner)
            }
            val code = source.scan(SCAN_TIMEOUT_MILLIS)
            if (code != null) viewModel.provision(code)
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MILLIS = 120_000L
    }
}
```

- [ ] **Step 8: Tulis layar Processing dan Result**

`app/src/main/res/layout/fragment_processing.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <!-- Progress indicator standar, bukan animasi kustom (spec §11). -->
    <ProgressBar
        android:id="@+id/progress"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/processing_title"
        app:layout_constraintTop_toBottomOf="@id/progress"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

`app/src/main/kotlin/com/cashup/app/ui/provisioning/ProcessingFragment.kt`:

```kotlin
package com.cashup.app.ui.provisioning

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cashup.app.CashupApp
import com.cashup.app.R
import kotlinx.coroutines.launch

class ProcessingFragment : Fragment(R.layout.fragment_processing) {

    private val viewModel: ProvisioningViewModel by activityViewModels {
        ProvisioningViewModel.Factory((requireActivity().application as CashupApp).container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is ProvisioningUiState.Success,
                        is ProvisioningUiState.Failure -> findNavController().navigate(R.id.to_result)
                        else -> Unit
                    }
                }
            }
        }
    }
}
```

`app/src/main/res/layout/fragment_result.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="16dp">

    <androidx.constraintlayout.widget.ConstraintLayout
        xmlns:app="http://schemas.android.com/apk/res-auto"
        android:layout_width="match_parent"
        android:layout_height="wrap_content">

        <TextView
            android:id="@+id/title"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:textSize="20sp"
            android:textStyle="bold"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent" />

        <TextView
            android:id="@+id/detail"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            app:layout_constraintTop_toBottomOf="@id/title"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent" />

        <TextView
            android:id="@+id/backings"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:textIsSelectable="true"
            app:layout_constraintTop_toBottomOf="@id/detail"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent" />

        <!-- SEMENTARA: panel log, dicabut bersama package audit/ (Task 10). -->
        <TextView
            android:id="@+id/journalLabel"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:text="@string/result_journal_label"
            android:textStyle="bold"
            app:layout_constraintTop_toBottomOf="@id/backings"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent" />

        <TextView
            android:id="@+id/journal"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:fontFamily="monospace"
            android:textIsSelectable="true"
            android:textSize="11sp"
            app:layout_constraintTop_toBottomOf="@id/journalLabel"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent" />

        <Button
            android:id="@+id/action"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            app:layout_constraintTop_toBottomOf="@id/journal"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent" />
    </androidx.constraintlayout.widget.ConstraintLayout>
</ScrollView>
```

`app/src/main/kotlin/com/cashup/app/ui/provisioning/ResultFragment.kt`:

```kotlin
package com.cashup.app.ui.provisioning

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.cashup.app.BuildConfig
import com.cashup.app.CashupApp
import com.cashup.app.R
import com.cashup.app.databinding.FragmentResultBinding

class ResultFragment : Fragment(R.layout.fragment_result) {

    private val viewModel: ProvisioningViewModel by activityViewModels {
        ProvisioningViewModel.Factory((requireActivity().application as CashupApp).container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentResultBinding.bind(view)

        when (val state = viewModel.state.value) {
            is ProvisioningUiState.Success -> {
                binding.title.setText(R.string.result_success_title)
                binding.detail.text = buildString {
                    append(getString(R.string.result_serial, state.serialNumber))
                    append('\n')
                    append(getString(R.string.result_order, state.orderId))
                }
                // Perlindungan tiap purpose ditampilkan, tidak disembunyikan:
                // tidak semua key mendapat modul aman vendor (spec §4.3), dan
                // itu harus terlihat teknisi, bukan hanya tercatat di log.
                binding.backings.text = state.installed.joinToString("\n") {
                    "${it.purpose}: ${it.backing.name}"
                }
                showJournal(binding, state.journalText)
                binding.action.setText(R.string.result_continue)
                binding.action.setOnClickListener { requireActivity().finish() }
            }

            is ProvisioningUiState.Failure -> {
                binding.title.setText(R.string.result_failure_title)
                val hint = hintFor(state.code)
                binding.detail.text = if (hint != 0) getString(hint) else state.message
                binding.backings.text = state.code
                showJournal(binding, state.journalText)
                binding.action.setText(R.string.result_retry)
                binding.action.setOnClickListener {
                    viewModel.reset()
                    findNavController().navigate(R.id.to_scan)
                }
            }

            else -> findNavController().navigate(R.id.to_scan)
        }
    }

    /** SEMENTARA — dicabut bersama package `audit/` (Task 10). */
    private fun showJournal(binding: FragmentResultBinding, text: String) {
        val visible = BuildConfig.PROVISIONING_JOURNAL && text.isNotBlank()
        binding.journalLabel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.journal.visibility = if (visible) View.VISIBLE else View.GONE
        binding.journal.text = text
    }
}
```

- [ ] **Step 9: Build APK debug dan jalankan seluruh tes**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon`
Expected: BUILD SUCCESSFUL, 13 tes lolos (4 `QrScanSource` + 6 ViewModel + 3 `ErrorHints`). APK ada di `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 10: Commit**

```bash
git add app/
git commit -m "feat(app): add the four provisioning screens

Gate, Scan QR, Processing, Result, each a single flat ConstraintLayout --
nested hierarchies are expensive to measure on weak GPUs, and the
progress indicator is the stock one rather than a custom animation.

The Result screen shows, per purpose, whether a key reached the vendor
secure module or only the Keystore vault. Not every key can be hardware
protected, and the technician standing at the terminal is the right person
to see that rather than having it live only in a log.

hintFor returns 0 for an unrecognised code so the backend's own message
still reaches the technician. The definitive code list does not exist yet,
and mapping unknown codes to something generic would hide exactly the
detail that is useful while it is still being settled.

The journal panel is gated on a build-config flag and is temporary
scaffolding for terminal trials; it is removed with the audit package."
```


---

## Task 15: Selaraskan dokumen dan verifikasi akhir

Spec induk masih memuat keputusan yang sudah dibatalkan plan ini. Dibiarkan, dokumen itu akan menyesatkan orang berikutnya — dan diagram yang belum di-commit memuat endpoint yang tidak pernah ada.

**Files:**
- Modify: `docs/EDC_PAYMENT_APP_DESIGN.md` — §2, §3, §4 J1, §9
- Modify: `docs/diagrams/generate_drawio.py` — halaman provisioning
- Create: `docs/session_log.md` — perbarui status

**Interfaces:**
- Consumes: seluruh hasil Task 1–14.
- Produces: dokumentasi yang konsisten dengan kode yang ada.

- [ ] **Step 1: Perbaiki `EDC_PAYMENT_APP_DESIGN.md` §2**

Ganti dua butir yang salah:

- Butir tentang bootstrap lewat login teknisi → temp JWT, jadi: **"Tidak ada login. Otorisasi provisioning sepenuhnya datang dari `challengeCode` hasil scan QR yang diterbitkan Cashup backoffice."**
- Butir tentang signing key disimpan di Keystore "TEE floor, StrongBox oportunistik", jadi: **"Signing key Ed25519 TIDAK hardware-backed — `KEY_ALGORITHM_ED25519` tidak ada di Android sampai API 37. Yang berada di TEE: key RSA pembuka paket (bila digest MGF1 backend memungkinkan, lihat spec provisioning §4.5) dan vault DUKPT."**

- [ ] **Step 2: Perbaiki §3 tabel module**

Ganti baris `device-sdk-api` + 9 adapter vendor dengan:

```
| `device-sdk-api` + `device-sdk-edcsdk` | Abstraksi hardware. Kontrak umum di `device-sdk-api`; satu module adapter di atas AAR `edc-sdk`, yang sudah menyediakan deteksi device, serial number, injeksi key DUKPT, printer, card reader, dan EMV untuk tujuh vendor. Sembilan module adapter per vendor dibatalkan — lihat `docs/superpowers/plans/2026-09-16-device-sdk-vendor-adapters.md`. |
```

Tambahkan juga baris untuk `provisioning-core` yang menyebut `app` sebagai pemakainya.

- [ ] **Step 3: Perbaiki §4 J1**

Hapus langkah 1–2 (login teknisi dan temp JWT). Ganti seluruh daftar langkah dengan alur sepuluh langkah dari `docs/superpowers/specs/2026-09-16-provisioning-design.md` §2, dan tambahkan satu kalimat penunjuk:

> Kontrak HTTP lengkap, kripto, dan batasannya ada di `docs/superpowers/specs/2026-09-16-provisioning-design.md`.

- [ ] **Step 4: Perbaiki §9 open items**

- Item 1 (skema signature masih terbuka: HMAC? RSA? ECDSA?) → **"Ed25519, sudah dikunci diagram provisioning tim. Yang tersisa: konfirmasi encoding signature (raw 64 byte, Base64 URL-safe tanpa padding)."**
- Item 2 (kontrak provisioning API belum ada) → **"Terjawab; lihat spec provisioning §3. Sisa yang belum pasti terdaftar di §8 spec itu."**

- [ ] **Step 5: Perbaiki generator diagram**

Di `docs/diagrams/generate_drawio.py`, halaman provisioning memuat `POST /v1/auth/technician` dan `POST /v1/provisioning/activate` — keduanya **tidak pernah ada**; itu dikarang sebelum diagram tim ditemukan.

Ganti `page_provisioning` dan `page_reprov` supaya mencerminkan §2 dan §3 spec provisioning: tiga endpoint `v1/terminal-key-provisioning/*`, tanpa langkah login, dengan lima lifeline dari diagram tim. Halaman J2/J3 diberi catatan bahwa endpoint-nya belum ada.

Lalu jalankan ulang:

Run: `python docs/diagrams/generate_drawio.py`
Expected: `wrote docs/diagrams/edc-payment-flows.drawio (11 pages)`

- [ ] **Step 6: Perbarui `docs/session_log.md`**

Ganti bagian "What's next" dengan status sebenarnya: Foundation selesai, plan vendor-adapters dibatalkan, Provisioning J1 selesai, dan urutan berikutnya — Notification, CDCP, QRIS, ECR bridge, App Shell, lalu J2/J3 begitu backend menyediakan endpoint-nya.

Cantumkan juga delapan item konfirmasi backend dari spec provisioning §8 sebagai hal yang memblokir, dengan item 1 ditandai paling mendesak.

- [ ] **Step 7: Verifikasi seluruh repo**

Run: `./gradlew clean build --no-daemon`
Expected: BUILD SUCCESSFUL. Seluruh module ter-compile, seluruh tes lolos.

Run: `./gradlew test testDebugUnitTest --no-daemon`
Expected: PASS. Hitungan yang diharapkan:

| Module | Tes |
|---|---|
| `common-core` | 8 lama + 2 header + 6 amplop = 16 |
| `device-sdk-api` | tes lama dikurangi `DeviceSdkRegistryTest`, plus 2 `TerminalKeyMaterial` |
| `signing-core` | 8 |
| `device-sdk-edcsdk` | 5 |
| `provisioning-core` | 39 |
| `app` | 13 |

- [ ] **Step 8: Verifikasi tidak ada key material yang bisa masuk log**

Run: `grep -rn "ipek\|IPEK" --include=*.kt provisioning-core/src/main device-sdk-edcsdk/src/main app/src/main | grep -i "log\|print\|toString"`
Expected: tidak ada hasil.

Run: `grep -rn "Level.BODY" --include=*.kt .`
Expected: hanya satu hasil, di `ProvisioningHttp.kt`, di dalam cabang `debugLogging`.

- [ ] **Step 9: Commit**

```bash
git add docs/
git commit -m "docs: align the design spec and diagrams with what was built

The spec still described a technician login and a temp JWT that the team's
provisioning diagram does not have and edc-mobile removed outright; still
promised a hardware-backed signing key that Android cannot provide for
Ed25519; and still listed nine per-vendor adapter modules that were
replaced by a single adapter over the edc-sdk AAR.

The diagram generator carried two endpoints that never existed --
/v1/auth/technician and /v1/provisioning/activate -- invented before the
team's diagram surfaced. Its provisioning pages are regenerated from the
real contract.

Leaving any of this in place would mislead whoever reads these documents
next, which is the only reason they exist."
```

---

## Setelah plan ini

Provisioning J1 selesai dan bisa dijalankan di terminal. Yang belum, dan urutannya:

1. **Jawaban backend atas delapan item spec §8.** Item 1 paling mendesak: bentuk bungkusan RSA dan digest MGF1-nya menentukan apakah key bisa di TEE sama sekali, dan jawabannya bisa mengubah `KeyPackageResponse`, `PackageUnwrapper`, dan `AppContainer`. Cara tercepat membuktikannya: minta satu `wrappedPackageKey` contoh beserta plaintext-nya, lalu coba buka dengan kedua kombinasi MGF1.
2. **Validasi manual di terminal fisik** untuk semua yang tidak bisa diuji unit: `EdcSdkSerialNumberProvider`, binder `KeyManager` yang nyata, `RsaKeyStore` di Android Keystore, dan `EdcSdkScanner`.
3. **J2 Re-Provisioning dan J3 Deactivation**, begitu backend menyediakan endpoint-nya.
4. **Cabut package `audit/`** sebelum rilis produksi — checklist di Task 10.
5. **Notification, CDCP, QRIS, ECR bridge, App Shell** — masing-masing siklusnya sendiri.

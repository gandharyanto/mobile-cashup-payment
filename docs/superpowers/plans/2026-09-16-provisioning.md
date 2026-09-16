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

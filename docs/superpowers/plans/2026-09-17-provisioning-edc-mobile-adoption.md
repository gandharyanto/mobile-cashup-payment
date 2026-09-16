# Provisioning — Adopsi Kontrak `edc-mobile` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mengganti kontrak wire provisioning J1 yang sudah berjalan di `main` (otoritas diagram tim) dengan kontrak `edc-mobile` penuh — identitas `deviceId` UUID, paket key hybrid AES-GCM bertanda tangan, sertifikat proof-of-possession, dan model atomicity dua fase — tanpa meregresi apa pun yang sudah lolos review sebelumnya (rollback, zeroisasi key, batas TEE, slot vendor tunggal).

**Architecture:** Ini bukan fitur baru, ini penulisan ulang bertarget atas 11 berkas yang sudah ada di `provisioning-core`/`app`. Setiap task memodifikasi berkas yang sudah punya tes; tes lama yang berasumsi bentuk kontrak lama diganti total, bukan ditambal. Interface (`ProvisioningGateway`, `ProvisioningKeys`, `ProvisioningStateRepository`) berubah bentuk, jadi urutan task mengikuti arah ketergantungan: DTO dulu, lalu komponen kripto independen, lalu wiring interface, lalu orkestrasi besar yang menyatukan semuanya, lalu UI.

**Tech Stack:** Sama seperti sebelumnya — Kotlin 2.0.21, JDK 17 toolchain, Android modules Java 8 bytecode, JUnit 4 + MockK + Robolectric (`provisioning-core`), MockWebServer, BouncyCastle 1.78.1, Gson.

**Spec:** `docs/superpowers/specs/2026-09-17-provisioning-edc-mobile-adoption-design.md` — plan ini berargumen dari spec itu; eksekutor membaca keduanya. Spec ini SUPERSEDES `docs/superpowers/specs/2026-09-16-provisioning-design.md` §1–§4, §7 — kalau ada pertentangan, spec 17 September menang.

## Global Constraints

- **`JAVA_HOME` harus di-set manual di setiap perintah Gradle**: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1"` (atau JDK 17 mana pun yang tersedia) — PATH JDK di banyak mesin dev adalah JDK 25/26, tidak didukung Gradle 8.9. Jangan set global.
- **Tata letak direktori `provisioning-core` tidak konsisten dengan nama package, dan ini SENGAJA dipertahankan apa adanya** — `data/crypto/*.kt` mendeklarasikan `package com.cashup.provisioning.crypto` (tanpa `data.`), `data/domain/*.kt` mendeklarasikan `package com.cashup.provisioning.domain` (tanpa `data.`), tapi `data/local/*.kt` dan `data/remote/*.kt` mendeklarasikan package YANG MATCH direktorinya (`com.cashup.provisioning.data.local`, `com.cashup.provisioning.data.remote`). Berkas baru di task ini mengikuti pola yang sudah ada persis di direktori yang sama — jangan "perbaiki" inkonsistensi ini, itu bukan scope task ini.
- **`Evidence.secret()`/`Evidence.token()`/`Evidence.ciphertext()`/`Evidence.publicKey()`** (di `com.cashup.provisioning.audit.Evidence`) tetap satu-satunya jalan menaruh nilai ke jurnal — lihat kode yang sudah ada untuk konvensi mana dipakai untuk apa (rahasia vs nilai kelas-1).
- **Key material tidak pernah masuk log di luar `Evidence`, tidak pernah jadi `String` yang bisa di-zeroize** (kecuali penyimpangan tercatat di `PackageUnwrapper` untuk hasil decode JSON — pola ini dipertahankan, tidak diperluas).
- **`ByteArray` key di-`fill(0)` segera setelah dipakai.** Pola yang sudah ada di `keyCheckValue`, `PackageUnwrapper`, `ProvisionDeviceUseCase` — ikuti persis.
- **Commit tiap akhir langkah yang menyebutnya.** Bahasa Inggris, imperative mood, ending `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`.
- **Jangan hapus atau lemahkan tes yang masih relevan dengan perilaku yang TIDAK berubah** (mis. `RsaKeyLocationTest`, `KcvTest`, `EvidenceTest`, `ProvisioningJournalTest` — semua tidak disentuh spec ini). Tes yang berasumsi bentuk kontrak LAMA (DTO lama, `DeviceIdentitySink`, `ProvisioningState` gabungan) diganti total di task yang menyebutnya secara eksplisit.

---

## Task 1: DTO dan `ProvisioningApi` — kontrak `edc-mobile` penuh

Mengganti bentuk request/response ketiga endpoint. Tidak ada logika di sini — murni data class dan interface Retrofit.

**Files:**
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/remote/ProvisioningDtos.kt`
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/remote/ProvisioningApi.kt`

**Interfaces:**
- Consumes: tidak ada.
- Produces:
  - `data class QrRedeemRequest(challengeCode, serialNumber, rsaPublicKey, eddsaPublicKey, purposes: Set<String>, deviceCertificateChain: List<String>)`
  - `data class QrRedeemResponse(deviceId, credentialKeyVersion: Int, dukptProvisioningRequired: Boolean, orderId: String?, activationToken: String?, keySetVersion: Int?, expiresAt: String?, status)`
  - `data class KeyPackageResponse(orderId, deviceId, keySetVersion: Int, algorithm, activationChallenge, wrappedPackageKey, nonce, ciphertext, packageSignature, signingPublicKey)`
  - `internal data class PlainKeyPackage(deviceId: String?, keySetVersion: Int?, algorithm: String?, materials: Map<String, PlainKeyMaterial>?)`
  - `internal data class PlainKeyMaterial(ipek: String?, ksn: String?, kcv: String?)` — TIDAK berubah dari sekarang, tetap dipakai `PackageUnwrapper` di Task 4.
  - `data class ActivateRequest(activationToken, keyCheckValues: Map<String,String>, deviceSignature)`
  - `data class ActivateResponse(orderId, deviceId, keySetId: Long, keySetVersion: Int, status)`
  - `ProvisioningApi.keyPackage(orderId: String, activationToken: String): Response<ApiEnvelope<KeyPackageResponse>>` — `@GET`, `@Header("X-Activation-Token")`, TIDAK ada `@Body` lagi.
  - **`KeyPackageRequest` DIHAPUS** — `/package` tidak lagi punya body.

- [ ] **Step 1: Ganti seluruh isi `ProvisioningDtos.kt`**

```kotlin
package com.cashup.provisioning.data.remote

/**
 * Bentuk body mengikuti `edc-mobile` (`D:\gandha_cashup\projects\edc-mobile copy`)
 * penuh — spec 17 September §1–§3. Nama path tetap mengikuti `corepayment`,
 * sama seperti spec sebelumnya; yang berubah di sini murni bentuk body/method.
 */

/**
 * Satu-satunya request yang TIDAK ditandatangani: backend belum mengenal public
 * key device, karena dua kunci itu justru baru dikirim di sini.
 *
 * [rsaPublicKey] SPKI X.509 Base64 — dipakai backend membungkus paket key DAN
 * memverifikasi [deviceCertificateChain]. [eddsaPublicKey] Ed25519 raw 32 byte
 * Base64 — dipakai backend memverifikasi tanda tangan setiap request sesudah
 * ini. [purposes] SELALU tiga tetap (spec §1) — client yang menyatakan apa
 * yang diminta, bukan menunggu server memberi tahu. [deviceCertificateChain]
 * sertifikat self-signed X.509 atas [rsaPublicKey] (DER Base64, satu elemen —
 * self-signed, bukan rantai) — proof-of-possession, lihat `RsaKeyStore.kt`.
 */
data class QrRedeemRequest(
    val challengeCode: String,
    val serialNumber: String,
    val rsaPublicKey: String,
    val eddsaPublicKey: String,
    val purposes: Set<String>,
    val deviceCertificateChain: List<String>,
)

/**
 * [deviceId] (UUID) sejak response ini jadi `X-Device-Id` — PERSIST segera,
 * spec §2 langkah 5, jangan tunggu DUKPT selesai.
 *
 * [dukptProvisioningRequired] `false` berarti device sudah punya DUKPT ACTIVE
 * dan redeem ini cuma me-refresh credential Ed25519 — [orderId]/
 * [activationToken]/[keySetVersion] semuanya `null` dalam kasus itu, ceremony
 * berhenti di sini (spec §2 langkah 6a).
 */
data class QrRedeemResponse(
    val deviceId: String,
    val credentialKeyVersion: Int,
    val dukptProvisioningRequired: Boolean,
    val orderId: String? = null,
    val activationToken: String? = null,
    val keySetVersion: Int? = null,
    val expiresAt: String? = null,
    val status: String,
)

/**
 * [algorithm] WAJIB `"TDES_DUKPT"` — nilai lain ditolak eksplisit (spec §2
 * langkah 6b, `edc-mobile` juga menolak `TR34_2019`).
 *
 * [wrappedPackageKey] membungkus kunci AES-256 (32 byte) via RSA-OAEP, BUKAN
 * seluruh payload DUKPT lagi — payload sesungguhnya ada di [ciphertext],
 * AES-GCM dengan [nonce]. [packageSignature] tanda tangan Ed25519 server atas
 * AAD+nonce+wrappedPackageKey+ciphertext, diverifikasi pakai [signingPublicKey]
 * SEBELUM apa pun di atas dibuka — lihat `PackageUnwrapper.kt`.
 */
data class KeyPackageResponse(
    val orderId: String,
    val deviceId: String,
    val keySetVersion: Int,
    val algorithm: String,
    val activationChallenge: String,
    val wrappedPackageKey: String,
    val nonce: String,
    val ciphertext: String,
    val packageSignature: String,
    val signingPublicKey: String,
)

/** Plaintext hasil decrypt AES-GCM — TIDAK pernah dikirim/diterima langsung dari backend. */
internal data class PlainKeyPackage(
    val deviceId: String? = null,
    val keySetVersion: Int? = null,
    val algorithm: String? = null,
    val materials: Map<String, PlainKeyMaterial>? = null,
)

/**
 * Ketiga field sengaja nullable meski paket yang sah selalu memuatnya — lihat
 * KDoc `PackageUnwrapper` untuk alasannya (Gson lewat `Unsafe`, null-safety
 * Kotlin tidak berjalan). TIDAK berubah dari kontrak lama.
 */
internal data class PlainKeyMaterial(
    val ipek: String? = null,
    val ksn: String? = null,
    val kcv: String? = null,
)

/**
 * [keyCheckValues] dikunci per tiga purpose tetap (TRACK/AMOUNT/PIN).
 * [deviceSignature] RSA `SHA256withRSA` atas
 * `"{orderId}:{keySetVersion}:{activationChallenge}"`, Base64 — proof-of-
 * possession kedua, memakai key RSA identity yang sama dengan yang membuka
 * paket. Lihat `ProvisionDeviceUseCase.kt`.
 */
data class ActivateRequest(
    val activationToken: String,
    val keyCheckValues: Map<String, String>,
    val deviceSignature: String,
)

data class ActivateResponse(
    val orderId: String,
    val deviceId: String,
    val keySetId: Long,
    val keySetVersion: Int,
    val status: String,
)
```

- [ ] **Step 2: Ganti `ProvisioningApi.kt`**

```kotlin
package com.cashup.provisioning.data.remote

import com.cashup.common.network.ApiEnvelope
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Setiap method mengembalikan `Response<ApiEnvelope<T>>`, bukan `T` polos.
 * Retrofit tidak mem-parsing body pada respons gagal kalau tipe return-nya
 * bukan `Response<T>`, dan justru di sanalah `error.code` backend berada —
 * lihat `safeEnvelopeCall`.
 *
 * `/package` sekarang `GET` + header `X-Activation-Token`, BUKAN `POST` +
 * body — kontrak `edc-mobile`, spec 17 September §1.
 */
internal interface ProvisioningApi {

    @POST("v1/terminal-key-provisioning/qr-redeem")
    suspend fun redeem(
        @Body body: QrRedeemRequest,
    ): Response<ApiEnvelope<QrRedeemResponse>>

    @GET("v1/terminal-key-provisioning/orders/{orderId}/package")
    suspend fun keyPackage(
        @Path("orderId") orderId: String,
        @Header("X-Activation-Token") activationToken: String,
    ): Response<ApiEnvelope<KeyPackageResponse>>

    @POST("v1/terminal-key-provisioning/orders/{orderId}/activate")
    suspend fun activate(
        @Path("orderId") orderId: String,
        @Body body: ActivateRequest,
    ): Response<ApiEnvelope<ActivateResponse>>
}
```

- [ ] **Step 3: Verifikasi module compile (belum bisa penuh — task lain masih memakai bentuk lama, itu wajar)**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:compileDebugKotlin --no-daemon 2>&1 | tail -40`
Expected: GAGAL — banyak error di berkas lain (`ProvisioningRepository.kt`, `ProvisionDeviceUseCase.kt`, test lama) yang masih memakai DTO lama (`KeyPackageRequest`, `QrRedeemRequest` tanpa `purposes`, dst). Ini **diharapkan** — task-task berikutnya memperbaikinya satu per satu. Jangan perbaiki berkas lain dari task ini.

- [ ] **Step 4: Commit**

```bash
git add provisioning-core/src/main/kotlin/com/cashup/provisioning/data/remote/ProvisioningDtos.kt provisioning-core/src/main/kotlin/com/cashup/provisioning/data/remote/ProvisioningApi.kt
git commit -m "feat(provisioning-core): adopt edc-mobile's provisioning DTOs and /package as GET

Full contract change per spec 2026-09-17: deviceId (UUID) and
dukptProvisioningRequired replace the old {orderId, activationToken}
response; purposes and deviceCertificateChain are new request fields;
KeyPackageResponse gains the hybrid AES-GCM fields (nonce, ciphertext,
packageSignature, signingPublicKey) and drops the old direct-RSA
wrappedPackageKey-only shape; ActivateRequest gains deviceSignature.
/package moves from POST+body to GET+X-Activation-Token header,
dropping KeyPackageRequest entirely.

This module will not compile until later tasks in this plan update the
callers -- expected, not a regression."
```

---

## Task 2: Pecah state jadi identity/DUKPT

`ProvisioningStateStore` sekarang menyimpan dua record independen dengan titik persist berbeda (spec §2 langkah 5 vs langkah 6b-l, §5).

**Files:**
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/local/ProvisioningStateStore.kt`
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisioningKeys.kt` (bagian `ProvisioningStateRepository`)

**Interfaces:**
- Consumes: tidak ada baru.
- Produces:
  - `data class IdentityState(serialNumber: String, deviceId: String, credentialKeyVersion: Int)`
  - `data class DukptState(deviceId: String, keySetId: Long, keySetVersion: Int, backings: Map<String, String>)`
  - `interface ProvisioningStateRepository { fun identity(): IdentityState?; fun saveIdentity(state: IdentityState); fun clearIdentity(); fun dukpt(): DukptState?; fun saveDukpt(state: DukptState); fun clearDukpt() }` — **menggantikan** `current()`/`save(ProvisioningState)`/`clear()` lama sepenuhnya.
  - `ProvisioningStateStore` mengimplementasikan interface baru itu, dua `SharedPreferences` key terpisah.

- [ ] **Step 1: Ganti isi `ProvisioningStateStore.kt`**

```kotlin
package com.cashup.provisioning.data.local

import android.content.Context
import com.cashup.provisioning.domain.ProvisioningStateRepository
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/** Persist segera setelah redeem sukses (spec §2 langkah 5) — sebelum DUKPT apa pun disentuh. */
data class IdentityState(
    val serialNumber: String,
    val deviceId: String,
    val credentialKeyVersion: Int,
)

/**
 * Persist terpisah dari [IdentityState], hanya setelah `/activate` sukses.
 * [backings] memetakan purpose ke nama [com.cashup.devicesdk.KeyBacking],
 * disimpan sebagai string supaya penambahan nilai enum kelak tidak membuat
 * state lama tidak terbaca.
 */
data class DukptState(
    val deviceId: String,
    val keySetId: Long,
    val keySetVersion: Int,
    val backings: Map<String, String>,
)

/**
 * Dua record independen, dua titik persist berbeda — spec §2 langkah 5 (identity)
 * vs langkah 6b-l (DUKPT). Device bisa punya identity TANPA DUKPT (baru redeem
 * pertama kali, gagal sebelum activate, atau refresh-identitas-saja lewat
 * `dukptProvisioningRequired=false`) — itu keadaan yang SAH, bukan setengah
 * jalan. Lihat spec §2.1.
 */
class ProvisioningStateStore(context: Context) : ProvisioningStateRepository {

    private val prefs by lazy { SecurePrefs.open(context.applicationContext, PREFS) }
    private val gson = Gson()

    override fun identity(): IdentityState? = read(KEY_IDENTITY, IdentityState::class.java, KEY_IDENTITY)

    @Synchronized
    override fun saveIdentity(state: IdentityState) {
        prefs.edit().putString(KEY_IDENTITY, gson.toJson(state)).commit()
    }

    @Synchronized
    override fun clearIdentity() {
        prefs.edit().remove(KEY_IDENTITY).commit()
    }

    override fun dukpt(): DukptState? = read(KEY_DUKPT, DukptState::class.java, KEY_DUKPT)

    @Synchronized
    override fun saveDukpt(state: DukptState) {
        prefs.edit().putString(KEY_DUKPT, gson.toJson(state)).commit()
    }

    @Synchronized
    override fun clearDukpt() {
        prefs.edit().remove(KEY_DUKPT).commit()
    }

    /**
     * Mengembalikan `null` juga kalau isi tersimpan tidak lagi cocok skema saat
     * ini — entri dibuang, bukan didiamkan, supaya pembacaan berikutnya tidak
     * mengulang error yang sama selamanya.
     */
    private fun <T> read(key: String, type: Class<T>, removeKeyOnCorrupt: String): T? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            gson.fromJson(raw, type)
        } catch (e: JsonSyntaxException) {
            prefs.edit().remove(removeKeyOnCorrupt).commit()
            null
        }
    }

    private companion object {
        const val PREFS = "provisioning_state"
        const val KEY_IDENTITY = "identity"
        const val KEY_DUKPT = "dukpt"
    }
}
```

- [ ] **Step 2: Ganti bagian `ProvisioningStateRepository` di `ProvisioningKeys.kt`**

Ganti blok ini (yang lama, di bagian bawah berkas):

```kotlin
interface ProvisioningStateRepository {
    fun current(): ProvisioningState?
    fun save(state: ProvisioningState)
    fun clear()
}
```

menjadi:

```kotlin
/**
 * Dua record independen — spec 17 September §5. Identity persist segera
 * setelah redeem sukses; DUKPT persist terpisah setelah activate sukses.
 * Rollback DUKPT (spec §2.1) memanggil HANYA `clearDukpt()`, tidak
 * `clearIdentity()`.
 */
interface ProvisioningStateRepository {
    fun identity(): IdentityState?
    fun saveIdentity(state: IdentityState)
    fun clearIdentity()

    fun dukpt(): DukptState?
    fun saveDukpt(state: DukptState)
    fun clearDukpt()
}
```

Tambahkan import di atas berkas: `import com.cashup.provisioning.data.local.DukptState` dan `import com.cashup.provisioning.data.local.IdentityState`. Hapus import `com.cashup.provisioning.data.local.ProvisioningState` (tipe itu sudah tidak ada).

- [ ] **Step 3: Verifikasi kompilasi berkas yang baru saja disentuh (masih akan banyak error di tempat lain — expected)**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:compileDebugKotlin --no-daemon 2>&1 | grep -c "e: "`
Expected: jumlah error TURUN dari Task 1 (berkurang, karena `ProvisioningStateRepository` sekarang konsisten dengan `ProvisioningStateStore`), tapi belum nol — `ProvisionDeviceUseCase.kt`, `StoredDeviceSigner.kt`, `AppContainer.kt` dan test lama masih memakai API lama (`current()`/`save()`/`ProvisioningState`).

- [ ] **Step 4: Commit**

```bash
git add provisioning-core/src/main/kotlin/com/cashup/provisioning/data/local/ProvisioningStateStore.kt provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisioningKeys.kt
git commit -m "refactor(provisioning-core): split provisioning state into identity and DUKPT

ProvisioningState (one combined record, one save point) becomes
IdentityState + DukptState (two independent records, two save points),
per spec 2026-09-17 section 5. A device can hold identity without DUKPT
now -- a first redeem that fails before activate, or a
dukptProvisioningRequired=false credential-only refresh -- and that is
a valid state, not a half-finished one.

Still a compile-breaking change at this point; ProvisionDeviceUseCase,
StoredDeviceSigner, AppContainer and the old tests are updated in later
tasks."
```

---

## Task 3: `RsaKeyStore` dual-purpose + generator sertifikat X.509

RSA sekarang juga menandatangani (`deviceSignature`, sertifikat sendiri), bukan cuma membuka paket. Generator sertifikatnya dipisah jadi fungsi murni yang menerima `KeyPair` apa pun — supaya bisa dites di JVM tanpa Android Keystore, meski penggunaan sesungguhnya selalu lewat key TEE/software `RsaKeyStore` sendiri.

**Files:**
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/crypto/RsaKeyStore.kt`
- Create: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/crypto/SelfSignedCertificate.kt`
- Create: `provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/SelfSignedCertificateTest.kt`

**Interfaces:**
- Consumes: `BcProvider` (sudah ada).
- Produces:
  - `object SelfSignedCertificate { fun build(keyPair: java.security.KeyPair, subjectCn: String = "cashup-edc-device"): ByteArray }` — DER encoded, dites langsung.
  - `RsaKeyStore.sign(bytes: ByteArray): ByteArray` — RSA `SHA256withRSA`.
  - `RsaKeyStore.certificateChain(): List<String>` — daftar beranggota satu, DER Base64.

- [ ] **Step 1: Tulis tes yang gagal untuk generator sertifikat**

`provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/SelfSignedCertificateTest.kt`:

```kotlin
package com.cashup.provisioning.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

class SelfSignedCertificateTest {

    private fun rsaKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    @Test
    fun `produces a certificate that verifies against its own public key`() {
        val keyPair = rsaKeyPair()

        val der = SelfSignedCertificate.build(keyPair)

        val cert = parse(der)
        // verify() tidak melempar kalau tanda tangan cocok dengan public key --
        // ini bukti langsung bahwa sertifikat memang ditandatangani oleh
        // private key yang berpasangan dengan public key-nya sendiri.
        cert.verify(keyPair.public)
        assertEquals(keyPair.public, cert.publicKey)
    }

    @Test
    fun `subject matches the requested common name`() {
        val der = SelfSignedCertificate.build(rsaKeyPair(), subjectCn = "test-device-123")

        val cert = parse(der)

        assertTrue(cert.subjectX500Principal.name, cert.subjectX500Principal.name.contains("test-device-123"))
    }

    @Test
    fun `validity window covers now and extends roughly ten years`() {
        val before = Date()

        val der = SelfSignedCertificate.build(rsaKeyPair())

        val cert = parse(der)
        cert.checkValidity(Date()) // tidak melempar = valid sekarang
        val approxTenYearsMillis = 3650L * 86_400_000
        assertTrue(
            "notAfter harus sekitar 10 tahun dari sekarang",
            cert.notAfter.time - before.time in (approxTenYearsMillis - 86_400_000)..(approxTenYearsMillis + 86_400_000),
        )
    }

    @Test
    fun `two calls for the same key pair produce different but both-valid certificates`() {
        // Tidak idempoten di level ini -- idempotensi dijamin RsaKeyStore
        // (sertifikat dibuat sekali, disimpan). Tes ini cuma memastikan
        // memanggil dua kali tidak melempar atau menghasilkan sesuatu yang aneh.
        val keyPair = rsaKeyPair()

        val first = parse(SelfSignedCertificate.build(keyPair))
        val second = parse(SelfSignedCertificate.build(keyPair))

        first.verify(keyPair.public)
        second.verify(keyPair.public)
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:testDebugUnitTest --tests "*SelfSignedCertificateTest" --no-daemon`
Expected: FAIL — `Unresolved reference: SelfSignedCertificate`.

- [ ] **Step 3: Tulis `SelfSignedCertificate.kt`**

```kotlin
package com.cashup.provisioning.data.crypto

import java.math.BigInteger
import java.security.KeyPair
import java.time.Instant
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Sertifikat self-signed X.509 atas sebuah [KeyPair] RSA — port dari
 * `DeviceIdentityStore.certificateChain()` di `edc-mobile`.
 *
 * Fungsi murni yang menerima `KeyPair` apa pun, bukan metode `RsaKeyStore`,
 * supaya bisa diuji di JVM biasa tanpa Android Keystore. `RsaKeyStore.sign()`
 * di bawah bekerja lewat `java.security.Signature` standar — kompatibel
 * dengan handle `PrivateKey` AndroidKeyStore non-extractable, jadi
 * `JcaContentSignerBuilder` di sini juga bekerja benar untuk key TEE
 * sungguhan, bukan cuma key software yang dites di sini.
 *
 * Dikirim sebagai `deviceCertificateChain` di `qr-redeem` — proof-of-
 * possession: backend tahu device benar-benar memegang private key yang
 * berpasangan dengan `rsaPublicKey`, karena sertifikat ini hanya bisa
 * dibuat kalau punya akses tanda tangan atas key itu.
 */
object SelfSignedCertificate {

    private const val VALIDITY_DAYS = 3650L

    fun build(keyPair: KeyPair, subjectCn: String = "cashup-edc-device"): ByteArray {
        BcProvider.ensureInstalled()
        val subject = X500Name("CN=$subjectCn")
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(VALIDITY_DAYS * 86_400)),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BcProvider.NAME)
            .build(keyPair.private)
        return JcaX509CertificateConverter()
            .setProvider(BcProvider.NAME)
            .getCertificate(builder.build(signer))
            .encoded
    }
}
```

- [ ] **Step 4: Jalankan tes, pastikan lolos**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:testDebugUnitTest --tests "*SelfSignedCertificateTest" --no-daemon`
Expected: PASS, 4 tes.

- [ ] **Step 5: Tambah `sign()` dan `certificateChain()` ke `RsaKeyStore.kt`, ubah `KeyGenParameterSpec` jadi dual-purpose**

Di dalam `class RsaKeyStore`, ubah baris `private val prefs by lazy { ... }` — tambahkan satu prefs key baru untuk cache sertifikat (di jalur software) tepat di bawahnya:

```kotlin
    private val appContext = context.applicationContext
    private val prefs by lazy { SecurePrefs.open(appContext, PREFS) }
```

(baris ini TIDAK berubah — konfirmasi saja sebelum melanjutkan).

Tambahkan dua method publik baru, setelah `fun unwrapper(): RsaUnwrapper = ...` dan sebelum `@Synchronized fun clear()`:

```kotlin
    /**
     * `SHA256withRSA` — dipakai `ProvisionDeviceUseCase` untuk `deviceSignature`
     * proof-of-possession di `/activate` (spec §2 langkah 6b-i). Key yang sama
     * dengan yang membuka paket; `PURPOSE_SIGN` ditambahkan ke
     * `KeyGenParameterSpec` di bawah supaya ini bekerja di jalur TEE.
     */
    fun sign(bytes: ByteArray): ByteArray {
        val privateKey = when (location) {
            RsaKeyLocation.ANDROID_KEYSTORE -> keystorePrivateKey()
            RsaKeyLocation.SOFTWARE -> {
                BcProvider.ensureInstalled()
                softwarePrivateKey()
            }
        }
        val provider = if (location == RsaKeyLocation.SOFTWARE) BcProvider.NAME else null
        return (if (provider != null) java.security.Signature.getInstance("SHA256withRSA", provider)
                else java.security.Signature.getInstance("SHA256withRSA")).run {
            initSign(privateKey)
            update(bytes)
            sign()
        }
    }

    /**
     * Sertifikat self-signed atas keypair ini (§4.2 spec 17 September),
     * dibuat sekali dan disimpan — idempoten seperti keypair-nya sendiri,
     * supaya sertifikat yang dikirim ke backend selalu cocok dengan key yang
     * sedang dipakai. Daftar beranggota satu: self-signed, bukan rantai.
     */
    @Synchronized
    fun certificateChain(): List<String> {
        prefs.getString(KEY_CERTIFICATE, null)?.let { return listOf(it) }

        val keyPair = when (location) {
            RsaKeyLocation.ANDROID_KEYSTORE -> java.security.KeyPair(
                androidKeyStore().getCertificate(ALIAS).publicKey,
                keystorePrivateKey(),
            )
            RsaKeyLocation.SOFTWARE -> {
                BcProvider.ensureInstalled()
                java.security.KeyPair(
                    java.security.KeyFactory.getInstance("RSA")
                        .generatePublic(java.security.spec.X509EncodedKeySpec(Base64.decode(prefs.getString(KEY_PUBLIC, null), Base64.NO_WRAP))),
                    softwarePrivateKey(),
                )
            }
        }
        val der = SelfSignedCertificate.build(keyPair)
        val base64 = Base64.encodeToString(der, Base64.NO_WRAP)
        prefs.edit().putString(KEY_CERTIFICATE, base64).commit()
        return listOf(base64)
    }
```

Ubah `fun spec(strongBox: Boolean)` di dalam `ensureKeystoreKey()` — baris pertamanya sekarang:

```kotlin
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_SIGN,
        )
```

(sebelumnya `KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)` — ganti baris itu saja). Tepat di bawah `.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)` di builder yang sama, tambahkan satu baris:

```kotlin
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
```

Tambahkan `KEY_CERTIFICATE` ke `private companion object` yang sudah ada:

```kotlin
    private companion object {
        const val ALIAS = "cashup_provisioning_rsa"
        const val PREFS = "provisioning_rsa"
        const val KEY_PRIVATE = "private_key"
        const val KEY_PUBLIC = "public_key"
        const val KEY_CERTIFICATE = "certificate"
    }
```

`clear()` juga harus menghapus sertifikat cache — tambahkan `.remove(KEY_CERTIFICATE)` ke chain `prefs.edit()` yang sudah ada di `fun clear()`.

- [ ] **Step 6: Verifikasi module ter-build (masih ada error di tempat lain, expected) dan `SelfSignedCertificateTest` tetap lolos**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:testDebugUnitTest --tests "*SelfSignedCertificateTest" --no-daemon`
Expected: PASS, 4 tes — `RsaKeyStore.kt` sendiri tidak diuji unit (pola lama, Robolectric tidak mengemulasi Keystore dengan setia), jadi perubahan di dalamnya tidak menambah/mengurangi tes.

- [ ] **Step 7: Commit**

```bash
git add provisioning-core/src/main/kotlin/com/cashup/provisioning/data/crypto/RsaKeyStore.kt provisioning-core/src/main/kotlin/com/cashup/provisioning/data/crypto/SelfSignedCertificate.kt provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/SelfSignedCertificateTest.kt
git commit -m "feat(provisioning-core): make RsaKeyStore dual-purpose, add certificate generation

RsaKeyStore's Keystore key gains PURPOSE_SIGN alongside PURPOSE_DECRYPT --
the same identity key now also produces deviceSignature proof-of-possession
at /activate and signs its own self-signed X.509 certificate, per spec
2026-09-17 section 4.

SelfSignedCertificate.build is a pure function over any KeyPair, not a
RsaKeyStore method, specifically so it's unit-testable on the JVM without
Android Keystore -- the real usage always goes through RsaKeyStore's TEE
or software key, but the certificate-building logic itself (subject,
validity window, self-signature) is verified independently of which key
store backs it.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `PackageUnwrapper` — verifikasi signature + hybrid AES-GCM decrypt

Task kripto paling berisiko di plan ini — mengubah `unwrap(wrappedPackageKeyBase64: String)` jadi `unwrap(response: KeyPackageResponse)` dengan tiga lapis baru: tolak algoritma selain `TDES_DUKPT`, verifikasi tanda tangan Ed25519 server SEBELUM membuka apa pun, baru RSA-OAEP+AES-GCM hybrid decrypt. Disiplin zeroisasi yang sudah ada (rollback per-purpose kalau KCV tidak cocok) dipertahankan penuh.

**Files:**
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/crypto/PackageUnwrapper.kt`
- Modify: `provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/PackageUnwrapperTest.kt`

**Interfaces:**
- Consumes: `KeyPackageResponse`, `PlainKeyPackage`, `PlainKeyMaterial` (semua Task 1 — Task 4 TIDAK mendeklarasikan ulang dua yang terakhir, lihat ruling di ledger SDD soal duplikasi yang ditemukan review Task 1), `TerminalKeyMaterial`/`keyCheckValue` (sudah ada), `BcProvider` (sudah ada).
- Produces:
  - `open class PackageUnwrapper(unwrapper: RsaUnwrapper, gson: Gson = Gson())` dengan `open fun unwrap(response: KeyPackageResponse): List<TerminalKeyMaterial>` — signature method BERUBAH dari `unwrap(wrappedPackageKeyBase64: String)`.
  - `class PackageIntegrityException(message: String) : Exception(message)` — tidak berubah.

- [ ] **Step 1: Tulis tes yang gagal untuk bentuk baru**

Ganti seluruh isi `provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/PackageUnwrapperTest.kt`:

```kotlin
package com.cashup.provisioning.crypto

import com.cashup.provisioning.data.remote.KeyPackageResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `android.util.Base64` dipakai `PackageUnwrapper`, jadi tes ini butuh
 * Robolectric (lihat catatan di `provisioning-core/build.gradle.kts` dekat
 * `testOptions` — tanpa runner ini `Base64.decode` mengembalikan `null` diam-
 * diam, bukan gagal jelas).
 */
@RunWith(RobolectricTestRunner::class)
class PackageUnwrapperTest {

    private val ipek = ByteArray(16) { (it + 1).toByte() }
    private val ksn = ByteArray(10) { (it + 100).toByte() }
    private val serverEd25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val orderId = "o-1"
    private val deviceId = "device-uuid-1"
    private val keySetVersion = 7

    /** AES-GCM enkripsi payload, RSA-OAEP-nya diserahkan ke [RsaUnwrapper] palsu (identity). */
    private fun buildResponse(
        materialsJson: String,
        algorithm: String = "TDES_DUKPT",
        packageDeviceId: String = deviceId,
        packageKeySetVersion: Int = keySetVersion,
        signer: java.security.PrivateKey = serverEd25519.private,
        corruptSignature: Boolean = false,
    ): KeyPackageResponse {
        val aesKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val aad = "$orderId:$packageDeviceId:$packageKeySetVersion".toByteArray()

        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(materialsJson.toByteArray())
        }

        // RsaUnwrapper di tes ini adalah identity (mengembalikan input apa
        // adanya) -- jadi "wrappedPackageKey" di sini LANGSUNG aesKey, bukan
        // benar-benar dibungkus RSA. Task ini menguji signature verify + AES-GCM,
        // bukan RSA-OAEP (itu tanggung jawab RsaKeyStore, diuji terpisah).
        val wrappedPackageKey = Base64.getEncoder().encodeToString(aesKey)
        val nonceB64 = Base64.getEncoder().encodeToString(nonce)
        val ciphertextB64 = Base64.getEncoder().encodeToString(ciphertext)

        val signed = aad + nonce + aesKey + ciphertext
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signer)
            update(if (corruptSignature) signed + byteArrayOf(0) else signed)
            sign()
        }

        return KeyPackageResponse(
            orderId = orderId,
            deviceId = packageDeviceId,
            keySetVersion = packageKeySetVersion,
            algorithm = algorithm,
            activationChallenge = "challenge-1",
            wrappedPackageKey = wrappedPackageKey,
            nonce = nonceB64,
            ciphertext = ciphertextB64,
            packageSignature = Base64.getEncoder().encodeToString(signature),
            signingPublicKey = Base64.getEncoder().encodeToString(serverEd25519.public.encoded),
        )
    }

    private fun materialsJson(vararg purposes: String): String {
        val kcv = keyCheckValue(ipek.copyOf())
        val ipekB64 = Base64.getEncoder().encodeToString(ipek)
        val ksnB64 = Base64.getEncoder().encodeToString(ksn)
        val entries = purposes.joinToString(",") {
            """"$it":{"ipek":"$ipekB64","ksn":"$ksnB64","kcv":"$kcv"}"""
        }
        return """{"deviceId":"$deviceId","keySetVersion":$keySetVersion,"algorithm":"TDES_DUKPT","materials":{$entries}}"""
    }

    private fun unwrapper() = PackageUnwrapper(RsaUnwrapper { it })

    @Test
    fun `returns one material per purpose when everything checks out`() {
        val response = buildResponse(materialsJson("TRACK", "AMOUNT", "PIN"))

        val materials = unwrapper().unwrap(response)

        assertEquals(setOf("TRACK", "AMOUNT", "PIN"), materials.map { it.purpose }.toSet())
    }

    @Test
    fun `rejects any algorithm other than TDES_DUKPT before touching the signature`() {
        val response = buildResponse(materialsJson("PIN"), algorithm = "TR34_2019")

        val failure = assertThrows(PackageIntegrityException::class.java) {
            unwrapper().unwrap(response)
        }

        assertTrue(failure.message!!, failure.message!!.contains("TR34_2019") || failure.message!!.contains("TDES_DUKPT"))
    }

    @Test
    fun `rejects a package signed by the wrong key`() {
        val wrongKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val response = buildResponse(materialsJson("PIN"), signer = wrongKey.private)

        val failure = assertThrows(PackageIntegrityException::class.java) {
            unwrapper().unwrap(response)
        }

        assertTrue(failure.message!!, failure.message!!.contains("Signature") || failure.message!!.contains("signature") || failure.message!!.contains("tanda tangan"))
    }

    @Test
    fun `rejects a package whose bytes were tampered with after signing`() {
        val response = buildResponse(materialsJson("PIN"), corruptSignature = true)

        assertThrows(PackageIntegrityException::class.java) {
            unwrapper().unwrap(response)
        }
    }

    @Test
    fun `never attempts RSA unwrap when the signature is invalid`() {
        // RsaUnwrapper yang melempar kalau benar-benar dipanggil -- kalau tes
        // ini lolos, urutan verifikasi salah (buka dulu, verifikasi belakangan).
        val throwingUnwrapper = RsaUnwrapper { throw AssertionError("RSA unwrap should not run before signature verification") }
        val wrongKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val response = buildResponse(materialsJson("PIN"), signer = wrongKey.private)

        assertThrows(PackageIntegrityException::class.java) {
            PackageUnwrapper(throwingUnwrapper).unwrap(response)
        }
    }

    @Test
    fun `rejects a package bound to a different device`() {
        val response = buildResponse(materialsJson("PIN"), packageDeviceId = "someone-elses-device")

        val failure = assertThrows(PackageIntegrityException::class.java) {
            unwrapper().unwrap(response)
        }

        assertTrue(failure.message!!, failure.message!!.contains("device"))
    }

    @Test
    fun `rejects a package whose plaintext keySetVersion does not match the envelope`() {
        // AAD memakai keySetVersion envelope (7), tapi plaintext JSON mengaku 99 --
        // AES-GCM sendiri tidak menangkap ini (AAD-nya tetap cocok), jadi
        // pengecekan eksplisit di level plaintext yang wajib menangkapnya.
        val badJson = materialsJson("PIN").replace("\"keySetVersion\":$keySetVersion", "\"keySetVersion\":99")
        val response = buildResponse(badJson)

        val failure = assertThrows(PackageIntegrityException::class.java) {
            unwrapper().unwrap(response)
        }

        assertTrue(failure.message!!, failure.message!!.contains("keySetVersion") || failure.message!!.contains("versi"))
    }

    @Test
    fun `an unfamiliar purpose still comes through -- purposes stay data-driven on read`() {
        val response = buildResponse(materialsJson("SOMETHING_NEW"))

        val materials = unwrapper().unwrap(response)

        assertEquals(listOf("SOMETHING_NEW"), materials.map { it.purpose })
    }

    @Test
    fun `a mismatched KCV rejects the whole package`() {
        val kcv = "000000"
        val ipekB64 = Base64.getEncoder().encodeToString(ipek)
        val ksnB64 = Base64.getEncoder().encodeToString(ksn)
        val json = """{"deviceId":"$deviceId","keySetVersion":$keySetVersion,"algorithm":"TDES_DUKPT","materials":{"PIN":{"ipek":"$ipekB64","ksn":"$ksnB64","kcv":"$kcv"}}}"""
        val response = buildResponse(json)

        assertThrows(PackageIntegrityException::class.java) {
            unwrapper().unwrap(response)
        }
    }
}
```

- [ ] **Step 2: Jalankan tes, pastikan gagal**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:testDebugUnitTest --tests "*PackageUnwrapperTest" --no-daemon`
Expected: FAIL — kompilasi gagal, `unwrap(KeyPackageResponse)` belum ada (masih `unwrap(String)`).

- [ ] **Step 3: Ganti seluruh isi `PackageUnwrapper.kt`**

```kotlin
package com.cashup.provisioning.data.crypto

import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.cashup.provisioning.data.remote.PlainKeyMaterial
import com.cashup.provisioning.data.remote.PlainKeyPackage
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PackageIntegrityException(message: String) : Exception(message)

/**
 * `PlainKeyPackage`/`PlainKeyMaterial` diimpor dari `data.remote.ProvisioningDtos`
 * (Task 1), TIDAK dideklarasikan ulang di sini — keduanya sudah identik
 * bentuknya di kedua task ini sebelum diperbaiki (temuan review Task 1),
 * jadi satu definisi kanonik dipakai, bukan dua tipe `internal` dengan nama
 * sama di package berbeda yang kebetulan tidak bentrok compile tapi tetap
 * duplikasi murni.
 *
 * Membuka paket key hybrid dan memverifikasinya sebelum apa pun dipasang —
 * spec 17 September §2 langkah 6b, §4.3, §4.4.
 *
 * Urutan wajib, masing-masing gerbang sebelum langkah berikutnya:
 * 1. `algorithm` harus `"TDES_DUKPT"` — tolak sebelum menyentuh kripto apa pun.
 * 2. Verifikasi `packageSignature` (Ed25519 server) atas AAD+nonce+
 *    wrappedPackageKey+ciphertext RAW BYTES — SEBELUM RSA-OAEP dipanggil sama
 *    sekali. Paket yang tidak lolos di sini tidak pernah dibuka.
 * 3. RSA-OAEP membuka `wrappedPackageKey` → kunci AES-256.
 * 4. AES-GCM mendekripsi `ciphertext` dengan kunci itu → plaintext JSON.
 * 5. `plaintext.deviceId`/`keySetVersion`/`algorithm` diverifikasi terhadap
 *    envelope — AES-GCM sendiri memvalidasi AAD, tapi TIDAK memvalidasi isi
 *    plaintext-nya sendiri konsisten dengan klaim di luar.
 * 6. KCV tiap purpose dihitung ulang dari IPEK dan dibandingkan.
 *
 * Purpose diperlakukan **data-driven** saat MEMBACA (apa pun nama yang datang
 * diteruskan) meski request-nya sekarang deklaratif tiga tetap (spec §1) —
 * pembacaan yang longgar tetap pertahanan yang murah kalau backend menambah
 * purpose baru.
 */
open class PackageUnwrapper(
    private val unwrapper: RsaUnwrapper,
    private val gson: Gson = Gson(),
) {

    /**
     * **Penyimpangan tercatat dari spec §4.4 (dipertahankan dari kontrak lama).**
     * Aturan "key material tidak pernah disalin ke `String`" berlaku di seluruh
     * jalur kripto codebase ini kecuali di sini: langkah decode JSON
     * menghasilkan `String` perantara yang tidak bisa di-zeroize. Byte array
     * hasilnya tetap diperlakukan benar (di-`fill(0)` di setiap jalur keluar).
     */
    open fun unwrap(response: KeyPackageResponse): List<TerminalKeyMaterial> {
        if (response.algorithm != "TDES_DUKPT") {
            throw PackageIntegrityException(
                "Algoritma paket '${response.algorithm}' tidak didukung — hanya TDES_DUKPT"
            )
        }

        val nonce = decodeField("nonce", response.nonce)
        val wrappedPackageKey = decodeField("wrappedPackageKey", response.wrappedPackageKey)
        val ciphertext = decodeField("ciphertext", response.ciphertext)
        val signingPublicKey = decodeField("signingPublicKey", response.signingPublicKey)
        val packageSignature = decodeField("packageSignature", response.packageSignature)

        val aad = "${response.orderId}:${response.deviceId}:${response.keySetVersion}".toByteArray()
        verifySignature(aad, nonce, wrappedPackageKey, ciphertext, signingPublicKey, packageSignature)

        val aesKey = unwrapper.unwrap(wrappedPackageKey)
        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(ciphertext)
            }
        } catch (e: Exception) {
            throw PackageIntegrityException("Gagal mendekripsi payload AES-GCM: ${e.message}")
        } finally {
            aesKey.fill(0)
        }

        val parsed = try {
            gson.fromJson(plaintext.decodeToString(), PlainKeyPackage::class.java)
        } catch (e: JsonSyntaxException) {
            throw PackageIntegrityException("Isi paket key bukan JSON yang dikenali")
        } finally {
            plaintext.fill(0)
        }

        if (parsed?.deviceId != response.deviceId) {
            throw PackageIntegrityException("Paket key terikat ke device lain")
        }
        if (parsed.keySetVersion != response.keySetVersion) {
            throw PackageIntegrityException("keySetVersion di plaintext tidak cocok dengan envelope")
        }
        if (parsed.algorithm != "TDES_DUKPT") {
            throw PackageIntegrityException("Algoritma di plaintext tidak didukung: ${parsed.algorithm}")
        }

        val materials = parsed.materials
        if (materials.isNullOrEmpty()) {
            throw PackageIntegrityException("Paket key tidak memuat material DUKPT satu pun")
        }

        // Dikumpulkan berjalan supaya, kalau satu purpose gagal KCV-nya,
        // material milik purpose-purpose sebelumnya yang sudah didekode juga
        // ikut di-zeroize -- bukan hanya purpose yang gagal itu sendiri.
        val produced = mutableListOf<TerminalKeyMaterial>()
        for ((purpose, material) in materials) {
            val rawIpek = required(purpose, "ipek", material.ipek)
            val rawKsn = required(purpose, "ksn", material.ksn)
            val kcv = required(purpose, "kcv", material.kcv)

            val ipek = decode(purpose, "ipek", rawIpek)
            val ksn = decode(purpose, "ksn", rawKsn)

            val computed = keyCheckValue(ipek.copyOf())
            if (!computed.equals(kcv, ignoreCase = true)) {
                ipek.fill(0)
                ksn.fill(0)
                produced.forEach { it.zeroize() }
                throw PackageIntegrityException("KCV tidak cocok untuk purpose $purpose")
            }

            produced += TerminalKeyMaterial(purpose = purpose, ipek = ipek, ksn = ksn)
        }
        return produced
    }

    /**
     * Signed = AAD + nonce + wrappedPackageKey + ciphertext, semua raw byte.
     * Ed25519 lewat provider BC eksplisit — `KEY_ALGORITHM_ED25519` tidak ada
     * di Android (sama seperti `Ed25519KeyStore`).
     */
    private fun verifySignature(
        aad: ByteArray,
        nonce: ByteArray,
        wrappedPackageKey: ByteArray,
        ciphertext: ByteArray,
        signingPublicKey: ByteArray,
        packageSignature: ByteArray,
    ) {
        BcProvider.ensureInstalled()
        val signed = aad + nonce + wrappedPackageKey + ciphertext
        val publicKey = KeyFactory.getInstance("Ed25519", BcProvider.NAME)
            .generatePublic(X509EncodedKeySpec(signingPublicKey))
        val valid = Signature.getInstance("Ed25519", BcProvider.NAME).run {
            initVerify(publicKey)
            update(signed)
            verify(packageSignature)
        }
        if (!valid) {
            throw PackageIntegrityException("Signature paket key dari server tidak valid")
        }
    }

    private fun decodeField(field: String, value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        throw PackageIntegrityException("Field $field bukan Base64 yang sah")
    }

    /**
     * Gson menulis `null` lewat `Unsafe` untuk field yang hilang, jadi
     * ketiadaan field harus diperiksa eksplisit di sini — kalau tidak, ia
     * muncul sebagai NullPointerException tanpa konteks alih-alih kegagalan
     * integritas paket yang menyebut apa yang hilang.
     */
    private fun required(purpose: String, field: String, value: String?): String =
        value ?: throw PackageIntegrityException("Field $field untuk purpose $purpose hilang")

    private fun decode(purpose: String, field: String, value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        throw PackageIntegrityException("Field $field untuk purpose $purpose bukan Base64 yang sah")
    }
}
```

- [ ] **Step 4: Jalankan tes, pastikan lolos**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:testDebugUnitTest --tests "*PackageUnwrapperTest" --no-daemon`
Expected: PASS, 9 tes.

- [ ] **Step 5: Commit**

```bash
git add provisioning-core/src/main/kotlin/com/cashup/provisioning/data/crypto/PackageUnwrapper.kt provisioning-core/src/test/kotlin/com/cashup/provisioning/crypto/PackageUnwrapperTest.kt
git commit -m "feat(provisioning-core): verify the server's package signature before decrypting

PackageUnwrapper.unwrap now takes the whole KeyPackageResponse instead of
just the wrapped key string, per spec 2026-09-17 section 4.3-4.4: reject
any algorithm other than TDES_DUKPT first, verify the Ed25519
packageSignature over AAD+nonce+wrappedPackageKey+ciphertext before RSA-OAEP
touches anything, only then unwrap the AES-256 key and AES-GCM decrypt the
payload, then cross-check the plaintext's own deviceId/keySetVersion/
algorithm against the envelope (AES-GCM's AAD binding covers the ciphertext
but not the plaintext's own claims).

The 'never attempts RSA unwrap when the signature is invalid' test asserts
this ordering directly with an RsaUnwrapper that throws if actually called
-- a package that fails signature verification never reaches RSA-OAEP.

KCV zeroization discipline (roll back all already-decoded materials, not
just the failing purpose) carries over unchanged from the previous
implementation.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `ProvisioningGateway`/`ProvisioningRepository` — kawat ke DTO baru

`/package` tidak lagi punya body — `ProvisioningGateway.downloadKeyPackage` berubah dari menerima `KeyPackageRequest` jadi menerima `orderId`+`activationToken` langsung.

**Files:**
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisioningGateway.kt`
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/ProvisioningRepository.kt`
- Modify: `provisioning-core/src/test/kotlin/com/cashup/provisioning/ProvisioningRepositoryTest.kt`

**Interfaces:**
- Consumes: DTO baru (Task 1).
- Produces:
  - `interface ProvisioningGateway { suspend fun redeem(request: QrRedeemRequest): ApiResult<QrRedeemResponse>; suspend fun downloadKeyPackage(orderId: String, activationToken: String): ApiResult<KeyPackageResponse>; suspend fun activate(orderId: String, request: ActivateRequest): ApiResult<ActivateResponse> }` — method `downloadKeyPackage` signature BERUBAH.

- [ ] **Step 1: Ganti `ProvisioningGateway.kt`**

```kotlin
package com.cashup.provisioning.domain

import com.cashup.common.network.ApiResult
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.ActivateResponse
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
 *
 * [downloadKeyPackage] menerima `orderId`+`activationToken` langsung, bukan
 * objek request — `/package` sekarang `GET`+header, tidak punya body (spec 17
 * September §1).
 */
interface ProvisioningGateway {
    suspend fun redeem(request: QrRedeemRequest): ApiResult<QrRedeemResponse>
    suspend fun downloadKeyPackage(orderId: String, activationToken: String): ApiResult<KeyPackageResponse>
    suspend fun activate(orderId: String, request: ActivateRequest): ApiResult<ActivateResponse>
}
```

- [ ] **Step 2: Ganti `ProvisioningRepository.kt`**

```kotlin
package com.cashup.provisioning.data

import com.cashup.common.network.ApiResult
import com.cashup.common.network.safeEnvelopeCall
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.ActivateResponse
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.cashup.provisioning.data.remote.ProvisioningApi
import com.cashup.provisioning.data.remote.QrRedeemRequest
import com.cashup.provisioning.data.remote.QrRedeemResponse
import com.cashup.provisioning.domain.ProvisioningGateway
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
) : ProvisioningGateway {

    override suspend fun redeem(request: QrRedeemRequest): ApiResult<QrRedeemResponse> =
        safeEnvelopeCall(gson) { unsigned.redeem(request) }

    override suspend fun downloadKeyPackage(orderId: String, activationToken: String): ApiResult<KeyPackageResponse> =
        safeEnvelopeCall(gson) { signed.keyPackage(orderId, activationToken) }

    override suspend fun activate(orderId: String, request: ActivateRequest): ApiResult<ActivateResponse> =
        safeEnvelopeCall(gson) { signed.activate(orderId, request) }
}
```

- [ ] **Step 3: Ganti seluruh isi `ProvisioningRepositoryTest.kt`**

```kotlin
package com.cashup.provisioning.data

import com.cashup.common.network.ApiResult
import com.cashup.provisioning.data.remote.ActivateRequest
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
        override fun deviceId(): String = "device-uuid-1"
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
                """{"data":{"deviceId":"device-uuid-1","credentialKeyVersion":1,"dukptProvisioningRequired":true,"orderId":"o-1","activationToken":"tok-1","keySetVersion":7,"status":"PENDING"}}"""
            )
        )

        val result = repository.redeem(
            QrRedeemRequest(
                challengeCode = "ABCD-1234",
                serialNumber = "PAX-A920-0012938",
                rsaPublicKey = "rsa-spki",
                eddsaPublicKey = "eddsa-raw",
                purposes = setOf("TRACK", "AMOUNT", "PIN"),
                deviceCertificateChain = listOf("cert-der-b64"),
            )
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
        assertTrue(body.contains(""""purposes":["""))
        assertTrue(body.contains(""""deviceCertificateChain":["""))

        assertEquals("device-uuid-1", (result as ApiResult.Success).data.deviceId)
        assertTrue(result.data.dukptProvisioningRequired)
    }

    @Test
    fun `a credential-only redeem carries no orderId`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"deviceId":"device-uuid-1","credentialKeyVersion":2,"dukptProvisioningRequired":false,"status":"ACTIVE"}}"""
            )
        )

        val result = repository.redeem(
            QrRedeemRequest("ABCD-1234", "PAX-A920-0012938", "rsa-spki", "eddsa-raw", setOf("TRACK", "AMOUNT", "PIN"), listOf("cert"))
        )

        val data = (result as ApiResult.Success).data
        assertEquals(false, data.dukptProvisioningRequired)
        assertNull(data.orderId)
        assertNull(data.activationToken)
    }

    @Test
    fun `downloadKeyPackage is a GET carrying the activation token as a header, signed`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"orderId":"o-1","deviceId":"device-uuid-1","keySetVersion":7,"algorithm":"TDES_DUKPT","activationChallenge":"ch-1","wrappedPackageKey":"d3JhcHBlZA","nonce":"bm9uY2U","ciphertext":"Y2lwaGVy","packageSignature":"c2ln","signingPublicKey":"cHVi"}}"""
            )
        )

        val result = repository.downloadKeyPackage("o-1", "tok-1")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/terminal-key-provisioning/orders/o-1/package", recorded.path)
        assertEquals("tok-1", recorded.getHeader("X-Activation-Token"))
        assertEquals("device-uuid-1", recorded.getHeader("X-Device-Id"))
        assertNotNull(recorded.getHeader("X-Signature"))
        assertNotNull(recorded.getHeader("X-Nonce"))

        assertEquals("d3JhcHBlZA", (result as ApiResult.Success).data.wrappedPackageKey)
        assertEquals("TDES_DUKPT", result.data.algorithm)
    }

    @Test
    fun `activate sends the key check values and device signature it was given`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"orderId":"o-1","deviceId":"device-uuid-1","keySetId":42,"keySetVersion":7,"status":"ACTIVE"}}"""
            )
        )

        val result = repository.activate(
            "o-1",
            ActivateRequest("tok-1", mapOf("PIN" to "A1B2C3", "TRACK" to "D4E5F6"), "device-signature-b64"),
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/terminal-key-provisioning/orders/o-1/activate", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""PIN":"A1B2C3""""))
        assertTrue(body.contains(""""TRACK":"D4E5F6""""))
        assertTrue(body.contains(""""deviceSignature":"device-signature-b64""""))

        assertEquals("ACTIVE", (result as ApiResult.Success).data.status)
        assertEquals(42L, result.data.keySetId)
    }

    @Test
    fun `a backend error code survives to the caller`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(410).setBody(
                """{"error":{"code":"PROVISIONING_TOKEN_INVALID","message":"Kode QR kedaluwarsa"}}"""
            )
        )

        val result = repository.redeem(
            QrRedeemRequest("X", "S", "r", "e", setOf("TRACK", "AMOUNT", "PIN"), listOf("cert"))
        )

        assertEquals("PROVISIONING_TOKEN_INVALID", (result as ApiResult.Failure).error.code)
    }
}
```

- [ ] **Step 4: Jalankan tes module, pastikan lolos**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:testDebugUnitTest --tests "*ProvisioningRepositoryTest" --no-daemon`
Expected: PASS, 5 tes.

- [ ] **Step 5: Commit**

```bash
git add provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisioningGateway.kt provisioning-core/src/main/kotlin/com/cashup/provisioning/data/ProvisioningRepository.kt provisioning-core/src/test/kotlin/com/cashup/provisioning/ProvisioningRepositoryTest.kt
git commit -m "feat(provisioning-core): wire ProvisioningRepository to the edc-mobile DTOs

downloadKeyPackage now takes orderId + activationToken directly instead
of a request object, matching /package's move to GET + header (Task 1).
Rewrote ProvisioningRepositoryTest for the new request/response shapes,
including a case for the credential-only redeem response
(dukptProvisioningRequired=false, orderId/activationToken absent).

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `ProvisioningKeys` — tambah `certificateChain()`/`signWithRsa()`

**Files:**
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisioningKeys.kt` (bagian `ProvisioningKeys`, bukan `ProvisioningStateRepository` — itu sudah selesai Task 2)
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/AndroidProvisioningKeys.kt`

**Interfaces:**
- Consumes: `RsaKeyStore.sign()`/`certificateChain()` (Task 3).
- Produces:
  - `interface ProvisioningKeys { fun ensureRsaKeyPair(): RsaKeyInfo; fun ensureEd25519KeyPair(): String; fun unwrapper(): RsaUnwrapper; fun certificateChain(): List<String>; fun signWithRsa(bytes: ByteArray): ByteArray; fun clearAll() }` — dua method baru: `certificateChain`, `signWithRsa`.

- [ ] **Step 1: Ganti blok `ProvisioningKeys` di `ProvisioningKeys.kt`**

Ganti interface `ProvisioningKeys` (bagian atas berkas — `ProvisioningStateRepository` di bawahnya, hasil Task 2, TIDAK disentuh lagi di sini):

```kotlin
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
    /** Sertifikat self-signed X.509 atas keypair RSA — proof-of-possession, dikirim di `qr-redeem`. */
    fun certificateChain(): List<String>
    /** `SHA256withRSA` atas [bytes] — dipakai untuk `deviceSignature` di `/activate`. */
    fun signWithRsa(bytes: ByteArray): ByteArray
    fun clearAll()
}
```

- [ ] **Step 2: Ganti `AndroidProvisioningKeys.kt`**

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

    override fun certificateChain(): List<String> = rsa.certificateChain()

    override fun signWithRsa(bytes: ByteArray): ByteArray = rsa.sign(bytes)

    override fun clearAll() {
        rsa.clear()
        ed25519.clear()
    }
}
```

- [ ] **Step 3: Verifikasi kompilasi berkas yang disentuh (masih ada error di `ProvisionDeviceUseCase.kt` dan test lama — expected, ditangani Task 8)**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:compileDebugKotlin --no-daemon 2>&1 | grep "e: .*ProvisioningKeys.kt\|e: .*AndroidProvisioningKeys.kt"`
Expected: kosong — dua berkas ini sendiri harus compile bersih; error yang tersisa ada di berkas lain.

- [ ] **Step 4: Commit**

```bash
git add provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisioningKeys.kt provisioning-core/src/main/kotlin/com/cashup/provisioning/AndroidProvisioningKeys.kt
git commit -m "feat(provisioning-core): expose certificate generation and RSA signing on ProvisioningKeys

certificateChain() and signWithRsa() delegate to RsaKeyStore's new
PURPOSE_SIGN capability (previous task) -- ProvisionDeviceUseCase (next
task) needs both for the qr-redeem body and the /activate
deviceSignature.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `StoredDeviceSigner` disederhanakan — `DeviceIdentitySink` dibuang

Mekanisme "in-flight serial" dari fix Critical-1 (spec 16 September) ADA karena identity baru tersimpan di langkah TERAKHIR ceremony lama. Spec 17 September membalik itu: identity persist SEGERA setelah redeem sukses (langkah 5), jauh sebelum `/package`/`/activate`. Tidak ada lagi lubang untuk ditambal — `StoredDeviceSigner` cukup baca `state.identity()` langsung.

**Files:**
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/StoredDeviceSigner.kt`
- Delete: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/DeviceIdentitySink.kt`

**Interfaces:**
- Consumes: `ProvisioningStateRepository.identity()` (Task 2).
- Produces: `class StoredDeviceSigner(state: ProvisioningStateRepository, signBytes: (ByteArray) -> ByteArray) : DeviceSigner` — **TIDAK lagi** mengimplementasikan `DeviceIdentitySink`. Konstruktor produksi `StoredDeviceSigner(stateStore: ProvisioningStateStore, ed25519: Ed25519KeyStore)` dipertahankan.

- [ ] **Step 1: Hapus `DeviceIdentitySink.kt`**

```bash
git rm provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/DeviceIdentitySink.kt
```

- [ ] **Step 2: Ganti `StoredDeviceSigner.kt`**

```kotlin
package com.cashup.provisioning

import com.cashup.provisioning.crypto.Ed25519KeyStore
import com.cashup.provisioning.data.local.ProvisioningStateStore
import com.cashup.provisioning.domain.ProvisioningStateRepository
import com.cashup.signing.DeviceSigner

/**
 * Menyambungkan penyimpanan identity ke kontrak [DeviceSigner] milik
 * `signing-core`.
 *
 * [deviceId] baca `state.identity()?.deviceId` langsung — nilai itu SEGERA
 * tersimpan setelah `qr-redeem` sukses (spec 17 September §2 langkah 5),
 * jauh sebelum `/package`/`/activate` yang butuh menandatangani. Beda dari
 * kontrak 16 September: `X-Device-Id` sekarang UUID terbitan backend, bukan
 * nomor seri lokal.
 *
 * Sebelum device pernah redeem sama sekali, `identity()` null dan [deviceId]
 * ikut null — request bertanda tangan tidak punya urusan berjalan pada device
 * yang belum dikenal backend, dan itu perilaku yang diinginkan.
 */
class StoredDeviceSigner(
    private val state: ProvisioningStateRepository,
    private val signBytes: (ByteArray) -> ByteArray,
) : DeviceSigner {

    /**
     * Konstruktor produksi. Dipisah dari yang utama supaya kelas ini bisa
     * dirakit di tes JVM biasa tanpa menyeret Android Keystore lewat
     * [Ed25519KeyStore].
     */
    constructor(stateStore: ProvisioningStateStore, ed25519: Ed25519KeyStore) :
            this(stateStore, ed25519::sign)

    override fun deviceId(): String? = state.identity()?.deviceId

    override fun sign(canonicalBytes: ByteArray): ByteArray = signBytes(canonicalBytes)
}
```

- [ ] **Step 3: Verifikasi kompilasi berkas yang disentuh (masih ada error di `ProvisionDeviceUseCase.kt`, `AppContainer.kt`, test lama — expected, ditangani Task 8-9)**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:compileDebugKotlin --no-daemon 2>&1 | grep "e: .*StoredDeviceSigner.kt"`
Expected: kosong — berkas ini sendiri compile bersih.

- [ ] **Step 4: Commit**

```bash
git add provisioning-core/src/main/kotlin/com/cashup/provisioning/StoredDeviceSigner.kt provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/DeviceIdentitySink.kt
git commit -m "refactor(provisioning-core): retire DeviceIdentitySink

The in-flight-serial workaround existed because Critical-1's fix could
only persist identity at the ceremony's last step. Spec 2026-09-17
removes the reason for it outright: identity now persists genuinely
early, right after redeem succeeds, per edc-mobile's own design --
before any signed call is made. StoredDeviceSigner.deviceId() reads
state.identity() directly; there is no gap left to paper over.

X-Device-Id also changes source here: state.identity()?.deviceId is
now the backend-issued UUID, not a local serial number.

ProvisionDeviceUseCase, AppContainer and the integration test that
exercised the old sink are updated in the next task, which is what
actually writes identity that early.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: `ProvisionDeviceUseCase` — orkestrasi penuh, rollback dua tingkat

Task paling berisiko di plan ini — menyatukan semua task sebelumnya jadi ceremony 11 langkah dengan percabangan `dukptProvisioningRequired` dan rollback dua tingkat (spec §2, §2.1). Satu keputusan desain yang PERLU DICATAT: urutan validasi `challengeCode` sebelum generate key DIPERTAHANKAN dari kode lama (bukan diikuti urutan penomoran spec §2 yang menulis "generate key" sebagai langkah 1-2, "scan QR" sebagai langkah 3) — penomoran spec menjelaskan urutan konseptual `edc-mobile` (di sana scanning terjadi di UI sebelum `redeemQrToken` dipanggil), bukan memerintahkan urutan validasi parameter internal use case ini. Validasi lebih dulu menghapus anomali "gagal tapi keys sudah ada" yang sudah pernah diperbaiki final review sebelumnya (finding #4) — jangan munculkan lagi.

**Files:**
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisionDeviceUseCase.kt`
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisioningOutcome.kt`
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisioningStep.kt`
- Modify: `provisioning-core/src/test/kotlin/com/cashup/provisioning/data/remote/SignedProvisioningHeadersTest.kt`
- Modify: `provisioning-core/src/test/kotlin/com/cashup/provisioning/domain/ProvisionDeviceUseCaseTest.kt`

**Interfaces:**
- Consumes: semua yang dihasilkan Task 1–7.
- Produces:
  - `class ProvisionDeviceUseCase(gateway: ProvisioningGateway, serialNumbers: SerialNumberProvider, keys: ProvisioningKeys, installer: TerminalKeyInstaller, state: ProvisioningStateRepository, unwrapperFactory: (RsaUnwrapper) -> PackageUnwrapper = { PackageUnwrapper(it) }, journal: ProvisioningJournal = ProvisioningJournal())` — **TIDAK lagi** ada parameter `deviceIdentity`.
  - `suspend operator fun invoke(rawChallengeCode: String, onStep: (ProvisioningStep) -> Unit = {}): ProvisioningOutcome`
  - `sealed interface ProvisioningOutcome` dengan `Success(serialNumber, deviceId, dukptProvisioningRequired: Boolean, orderId: String?, installed: List<KeyInstallOutcome>, journalText)` dan `Failure(code, message, journalText)` — `Success` BERTAMBAH field `deviceId`, `dukptProvisioningRequired`.
  - `enum class ProvisioningStep { DETECT_DEVICE, GENERATE_KEYS, SCAN_QR, REDEEM, PERSIST_IDENTITY, DOWNLOAD_PACKAGE, UNWRAP_PACKAGE, VERIFY_KCV, INSTALL_KEYS, ACTIVATE, PERSIST_DUKPT, ROLLBACK }` — `PERSIST_STATE` di-rename `PERSIST_DUKPT`, tambah `PERSIST_IDENTITY` baru.
  - `companion object { const val DEVICE_UNKNOWN, CHALLENGE_EMPTY, PROTOCOL_VIOLATION, PACKAGE_INVALID, KEY_INSTALL_FAILED, UNEXPECTED_ERROR }` — `PROTOCOL_VIOLATION` baru (backend bilang `dukptProvisioningRequired=true` tapi tidak mengirim `orderId`/`activationToken`/`keySetVersion`).

- [ ] **Step 1: Ganti `ProvisioningStep.kt`**

```kotlin
package com.cashup.provisioning.domain

/**
 * Langkah-langkah ceremony provisioning (spec 2026-09-17 §2), dipakai
 * [ProvisionDeviceUseCase] untuk melaporkan progres (lewat `onStep`, ke UI)
 * dan oleh jurnal sementara (`com.cashup.provisioning.audit.ProvisioningJournal`)
 * untuk mencatat evidence.
 *
 * Sengaja tinggal di `domain`, BUKAN di package `audit` — `audit/` itu
 * scaffolding sementara yang dicabut sebelum produksi (lihat plan Task 10 di
 * plan provisioning 16 September), sementara enum ini durable.
 *
 * [PERSIST_IDENTITY] dan [PERSIST_DUKPT] terpisah karena keduanya PERSIST DI
 * TITIK BERBEDA (spec §5): identity segera setelah redeem sukses, DUKPT
 * setelah activate sukses. Rollback DUKPT (spec §2.1) tidak menyentuh
 * identity yang sudah ter-persist.
 */
enum class ProvisioningStep {
    DETECT_DEVICE,
    SCAN_QR,
    GENERATE_KEYS,
    REDEEM,
    PERSIST_IDENTITY,
    DOWNLOAD_PACKAGE,
    UNWRAP_PACKAGE,
    VERIFY_KCV,
    INSTALL_KEYS,
    ACTIVATE,
    PERSIST_DUKPT,
    ROLLBACK,
}
```

- [ ] **Step 2: Ganti `ProvisioningOutcome.kt`**

```kotlin
package com.cashup.provisioning.domain

import com.cashup.devicesdk.KeyInstallOutcome

/**
 * `journalText` di kedua cabang adalah **sementara** — bagian dari alat bantu
 * pelaporan tahap awal, dijadwalkan dihapus bersama package `audit/` sebelum
 * produksi.
 */
sealed interface ProvisioningOutcome {

    /**
     * [dukptProvisioningRequired] `false` berarti ini refresh identitas saja
     * (spec §2 langkah 6a) — [orderId] `null`, [installed] kosong. `true`
     * berarti DUKPT baru saja dipasang penuh.
     */
    data class Success(
        val serialNumber: String,
        val deviceId: String,
        val dukptProvisioningRequired: Boolean,
        val orderId: String?,
        val installed: List<KeyInstallOutcome>,
        val journalText: String,
    ) : ProvisioningOutcome

    /**
     * [code] adalah `error.code` dari backend kalau kegagalannya datang dari
     * sana, atau kode lokal (`DEVICE_UNKNOWN`, `CHALLENGE_EMPTY`,
     * `PROTOCOL_VIOLATION`, `PACKAGE_INVALID`, `KEY_INSTALL_FAILED`,
     * `UNEXPECTED_ERROR`) kalau bukan. Selalu bercabang pada [code], tidak
     * pernah pada [message].
     */
    data class Failure(
        val code: String,
        val message: String,
        val journalText: String,
    ) : ProvisioningOutcome
}
```

- [ ] **Step 3: Ganti seluruh isi `ProvisionDeviceUseCase.kt`**

```kotlin
package com.cashup.provisioning.domain

import android.util.Base64
import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.KeyInstallOutcome
import com.cashup.devicesdk.SerialNumberProvider
import com.cashup.devicesdk.TerminalKeyInstallResult
import com.cashup.devicesdk.TerminalKeyInstaller
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.provisioning.audit.Evidence
import com.cashup.provisioning.audit.ProvisioningJournal
import com.cashup.provisioning.crypto.PackageIntegrityException
import com.cashup.provisioning.crypto.PackageUnwrapper
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.crypto.keyCheckValue
import com.cashup.provisioning.data.local.DukptState
import com.cashup.provisioning.data.local.IdentityState
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.QrRedeemRequest

/**
 * Ceremony provisioning J1, sebelas langkah, rollback DUA TINGKAT — spec
 * 2026-09-17 §2, §2.1.
 *
 * **Sebelum identity ter-persist** (gagal di `DETECT_DEVICE`/`SCAN_QR`
 * sebelum key digenerate, atau `REDEEM` ditolak backend setelah key
 * digenerate): rollback TOTAL — `keys.clearAll()`, `installer.wipe()`,
 * `state.clearIdentity()`+`state.clearDukpt()`. Tidak ada state tersisa.
 *
 * **Setelah identity ter-persist** (`REDEEM` sukses, gagal di manapun
 * sesudahnya sampai sebelum `PERSIST_DUKPT`): rollback DUKPT SAJA —
 * `installer.wipe()`, `state.clearDukpt()`. Identity (deviceId,
 * credentialKeyVersion, KEDUA keypair) TETAP tersimpan — device sudah
 * "dikenal" backend, tinggal butuh redeem ulang untuk mencoba DUKPT lagi.
 *
 * Parameter [journal] **sementara** — alat bantu pelaporan tahap awal, dicabut
 * bersama package `audit/` sebelum produksi.
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

    /**
     * [onStep] dipanggil saat setiap langkah DIMULAI, di coroutine pemanggil.
     * Ia ada supaya layar Processing bisa menunjukkan langkah mana yang sedang
     * berjalan alih-alih spinner tanpa keterangan — ceremony ini menunggu dua
     * HSM di `DOWNLOAD_PACKAGE` dan bisa terasa menggantung tanpa itu.
     */
    suspend operator fun invoke(
        rawChallengeCode: String,
        onStep: (ProvisioningStep) -> Unit = {},
    ): ProvisioningOutcome {
        journal.clear()

        var currentStep = ProvisioningStep.DETECT_DEVICE
        var identityPersisted = false
        try {
            return invokeSteps(rawChallengeCode, { identityPersisted = true }) { currentStep = it; onStep(it) }
        } catch (e: Throwable) {
            // Throwable, BUKAN Exception: NoClassDefFoundError sudah terbukti
            // sebagai mode kegagalan nyata di codebase ini, dan terminal 1 GB
            // bisa melempar OutOfMemoryError. Rollback yang tepat bergantung
            // pada apakah identity sudah ter-persist saat exception terjadi.
            journal.failed(currentStep, UNEXPECTED_ERROR, mapOf("message" to (e.message ?: "-")))
            if (identityPersisted) rollbackDukpt() else rollbackAll()
            return fail(UNEXPECTED_ERROR, e.message ?: "Kesalahan tak terduga: ${e::class.simpleName}")
        }
    }

    private suspend fun invokeSteps(
        rawChallengeCode: String,
        onIdentityPersisted: () -> Unit,
        onStep: (ProvisioningStep) -> Unit,
    ): ProvisioningOutcome {
        // 1. Nomor seri. Tanpa ini backend tidak punya identitas untuk device
        //    ini, jadi jaringan tidak perlu disentuh sama sekali.
        onStep(ProvisioningStep.DETECT_DEVICE)
        journal.start(ProvisioningStep.DETECT_DEVICE)
        val serialNumber = serialNumbers.serialNumber()
        if (serialNumber.isNullOrBlank()) {
            journal.failed(ProvisioningStep.DETECT_DEVICE, DEVICE_UNKNOWN)
            return fail(DEVICE_UNKNOWN, "Perangkat tidak dikenali SDK vendor mana pun")
        }
        journal.ok(ProvisioningStep.DETECT_DEVICE, mapOf("serialNumber" to serialNumber))

        // 2. Kode QR. Divalidasi SEBELUM keypair dibuat -- kode kosong adalah
        //    satu-satunya jalur gagal yang tidak butuh rollback SAMA SEKALI
        //    (tidak ada key, tidak ada state); memvalidasi lebih dulu menjaga
        //    itu tetap benar, bukan kasus khusus.
        onStep(ProvisioningStep.SCAN_QR)
        val challengeCode = rawChallengeCode.filter(Char::isLetterOrDigit)
        if (challengeCode.isEmpty()) {
            journal.failed(ProvisioningStep.SCAN_QR, CHALLENGE_EMPTY)
            return fail(CHALLENGE_EMPTY, "Kode provisioning kosong")
        }
        journal.ok(
            ProvisioningStep.SCAN_QR,
            mapOf(
                "challengeCode.raw" to Evidence.token(rawChallengeCode),
                "challengeCode.normalized" to Evidence.token(challengeCode),
            ),
        )

        // 3. Keypair + sertifikat. Idempoten: percobaan ulang setelah gagal
        //    memakai key yang sama, sehingga public key yang didaftarkan tetap
        //    konsisten.
        onStep(ProvisioningStep.GENERATE_KEYS)
        journal.start(ProvisioningStep.GENERATE_KEYS)
        val rsa = keys.ensureRsaKeyPair()
        val eddsaPublicKey = keys.ensureEd25519KeyPair()
        val certificateChain = keys.certificateChain()
        journal.ok(
            ProvisioningStep.GENERATE_KEYS,
            mapOf(
                "rsa.location" to rsa.location.name,
                "rsa.publicKey" to Evidence.publicKey(rsa.publicKeySpkiBase64),
                "eddsa.publicKey" to Evidence.publicKey(eddsaPublicKey),
            ),
        )

        // 4. Redeem. Satu-satunya panggilan yang tidak ditandatangani. Purposes
        //    tiga tetap sisi client (spec §1) -- bukan menunggu server memberi
        //    tahu.
        onStep(ProvisioningStep.REDEEM)
        journal.start(ProvisioningStep.REDEEM)
        val redeemed = when (
            val result = gateway.redeem(
                QrRedeemRequest(
                    challengeCode = challengeCode,
                    serialNumber = serialNumber,
                    rsaPublicKey = rsa.publicKeySpkiBase64,
                    eddsaPublicKey = eddsaPublicKey,
                    purposes = PURPOSES,
                    deviceCertificateChain = certificateChain,
                )
            )
        ) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                journal.failed(
                    ProvisioningStep.REDEEM,
                    result.error.code,
                    mapOf(
                        "httpStatus" to (result.error.httpStatus?.toString() ?: "-"),
                        "message" to result.error.message,
                    ),
                )
                // Identity belum ter-persist -- backend tidak menerima key ini,
                // jadi rollback TOTAL: key lokal yang belum pernah dikonfirmasi
                // dibuang, bukan disimpan.
                rollbackAll()
                return fail(result.error.code, result.error.message)
            }
        }
        journal.ok(
            ProvisioningStep.REDEEM,
            mapOf(
                "deviceId" to Evidence.token(redeemed.deviceId),
                "credentialKeyVersion" to redeemed.credentialKeyVersion.toString(),
                "dukptProvisioningRequired" to redeemed.dukptProvisioningRequired.toString(),
            ),
        )

        // 5. PERSIST IDENTITY -- SEGERA, sebelum request bertanda tangan mana
        //    pun. Titik tanpa-jalan-kembali: dari sini, gagal di mana pun
        //    (termasuk PROTOCOL_VIOLATION di bawah) rollback DUKPT SAJA.
        onStep(ProvisioningStep.PERSIST_IDENTITY)
        journal.start(ProvisioningStep.PERSIST_IDENTITY)
        state.saveIdentity(
            IdentityState(
                serialNumber = serialNumber,
                deviceId = redeemed.deviceId,
                credentialKeyVersion = redeemed.credentialKeyVersion,
            )
        )
        onIdentityPersisted()
        journal.ok(ProvisioningStep.PERSIST_IDENTITY)

        // 6a. Refresh identitas saja -- DUKPT lama (kalau ada) tidak disentuh.
        if (!redeemed.dukptProvisioningRequired) {
            return ProvisioningOutcome.Success(
                serialNumber = serialNumber,
                deviceId = redeemed.deviceId,
                dukptProvisioningRequired = false,
                orderId = null,
                installed = emptyList(),
                journalText = journal.render(),
            )
        }

        // 6b. Backend WAJIB mengirim ketiganya kalau dukptProvisioningRequired
        //     -- pelanggaran kontrak sendiri, bukan kegagalan yang bisa
        //     ditebak jalan keluarnya.
        val orderId = redeemed.orderId
        val activationToken = redeemed.activationToken
        val expectedKeySetVersion = redeemed.keySetVersion
        if (orderId == null || activationToken == null || expectedKeySetVersion == null) {
            journal.failed(ProvisioningStep.REDEEM, PROTOCOL_VIOLATION)
            rollbackDukpt()
            return fail(
                PROTOCOL_VIOLATION,
                "Backend menyatakan dukptProvisioningRequired tapi tidak mengirim orderId/activationToken/keySetVersion",
            )
        }

        onStep(ProvisioningStep.DOWNLOAD_PACKAGE)
        journal.start(ProvisioningStep.DOWNLOAD_PACKAGE)
        val keyPackage = when (val result = gateway.downloadKeyPackage(orderId, activationToken)) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                journal.failed(
                    ProvisioningStep.DOWNLOAD_PACKAGE,
                    result.error.code,
                    mapOf(
                        "httpStatus" to (result.error.httpStatus?.toString() ?: "-"),
                        "message" to result.error.message,
                    ),
                )
                rollbackDukpt()
                return fail(result.error.code, result.error.message)
            }
        }
        journal.ok(
            ProvisioningStep.DOWNLOAD_PACKAGE,
            mapOf(
                "orderId" to Evidence.token(keyPackage.orderId),
                "algorithm" to keyPackage.algorithm,
                // Ciphertext -- kelas 1, dicatat penuh. Hanya private key device
                // yang bisa membukanya.
                "wrappedPackageKey" to Evidence.ciphertext(keyPackage.wrappedPackageKey),
                "ciphertext" to Evidence.ciphertext(keyPackage.ciphertext),
                "packageSignature" to Evidence.token(keyPackage.packageSignature),
            ),
        )

        // 7-8. Verifikasi signature + hybrid decrypt + verifikasi KCV tiap
        //      purpose -- semuanya di dalam PackageUnwrapper (Task 4). Ini
        //      satu-satunya kesempatan mendeteksi key rusak: setelah masuk
        //      modul vendor, IPEK tidak bisa dibaca kembali.
        onStep(ProvisioningStep.UNWRAP_PACKAGE)
        journal.start(ProvisioningStep.UNWRAP_PACKAGE)
        val materials: List<TerminalKeyMaterial> = try {
            unwrapperFactory(keys.unwrapper()).unwrap(keyPackage)
        } catch (e: PackageIntegrityException) {
            journal.failed(
                ProvisioningStep.UNWRAP_PACKAGE,
                PACKAGE_INVALID,
                mapOf("message" to (e.message ?: "-")),
            )
            rollbackDukpt()
            return fail(PACKAGE_INVALID, e.message ?: "Paket key tidak sah")
        }
        val checkValues = materials.associate { it.purpose to keyCheckValue(it.ipek.copyOf()) }
        journal.ok(
            ProvisioningStep.UNWRAP_PACKAGE,
            materials.associate { "${it.purpose}.ipek" to Evidence.secret(it.ipek) } +
                    materials.associate { "${it.purpose}.ksn" to Evidence.secret(it.ksn) },
        )
        journal.ok(ProvisioningStep.VERIFY_KCV, checkValues.mapKeys { "${it.key}.kcv" })

        // 9. Pasang. Modul vendor dulu untuk purpose yang dinominasikan, sisanya
        //    ke vault.
        onStep(ProvisioningStep.INSTALL_KEYS)
        journal.start(ProvisioningStep.INSTALL_KEYS)
        val installResult = installer.install(materials)
        materials.forEach { it.zeroize() }
        val installed: List<KeyInstallOutcome> = when (installResult) {
            is TerminalKeyInstallResult.Installed -> installResult.outcomes
            is TerminalKeyInstallResult.Failed -> {
                journal.failed(
                    ProvisioningStep.INSTALL_KEYS,
                    KEY_INSTALL_FAILED,
                    mapOf("purpose" to installResult.purpose),
                )
                rollbackDukpt()
                return fail(
                    KEY_INSTALL_FAILED,
                    "Gagal memasang key untuk purpose ${installResult.purpose}: ${installResult.reason}",
                )
            }
        }
        journal.ok(
            ProvisioningStep.INSTALL_KEYS,
            installed.associate { it.purpose to it.backing.name },
        )

        // 10. Activate. deviceSignature = proof-of-possession kedua, RSA key
        //     yang sama dengan yang membuka paket.
        onStep(ProvisioningStep.ACTIVATE)
        journal.start(ProvisioningStep.ACTIVATE)
        val signaturePayload = "$orderId:$expectedKeySetVersion:${keyPackage.activationChallenge}".toByteArray()
        val deviceSignature = Base64.encodeToString(keys.signWithRsa(signaturePayload), Base64.NO_WRAP)
        val activated = when (
            val result = gateway.activate(orderId, ActivateRequest(activationToken, checkValues, deviceSignature))
        ) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                journal.failed(
                    ProvisioningStep.ACTIVATE,
                    result.error.code,
                    mapOf(
                        "httpStatus" to (result.error.httpStatus?.toString() ?: "-"),
                        "message" to result.error.message,
                    ) + checkValues.mapKeys { "sent.${it.key}.kcv" },
                )
                rollbackDukpt()
                return fail(result.error.code, result.error.message)
            }
        }
        if (activated.keySetVersion != expectedKeySetVersion) {
            journal.failed(ProvisioningStep.ACTIVATE, PROTOCOL_VIOLATION)
            rollbackDukpt()
            return fail(PROTOCOL_VIOLATION, "keySetVersion hasil activate tidak cocok dengan yang dijanjikan redeem")
        }
        journal.ok(ProvisioningStep.ACTIVATE, mapOf("status" to activated.status))

        // 11. Simpan DUKPT -- terpisah dari identity yang sudah tersimpan di
        //     langkah 5.
        onStep(ProvisioningStep.PERSIST_DUKPT)
        journal.start(ProvisioningStep.PERSIST_DUKPT)
        state.saveDukpt(
            DukptState(
                deviceId = redeemed.deviceId,
                keySetId = activated.keySetId,
                keySetVersion = activated.keySetVersion,
                backings = installed.associate { it.purpose to it.backing.name },
            )
        )
        journal.ok(ProvisioningStep.PERSIST_DUKPT)

        return ProvisioningOutcome.Success(
            serialNumber = serialNumber,
            deviceId = redeemed.deviceId,
            dukptProvisioningRequired = true,
            orderId = redeemed.orderId,
            installed = installed,
            journalText = journal.render(),
        )
    }

    /** Rollback DUKPT saja -- identity (keypair, deviceId) TETAP tersimpan (spec §2.1). */
    private suspend fun rollbackDukpt() {
        journal.start(ProvisioningStep.ROLLBACK)
        runCatching { installer.wipe() }
        runCatching { state.clearDukpt() }
        journal.ok(ProvisioningStep.ROLLBACK)
    }

    /** Rollback total -- dipakai HANYA sebelum identity ter-persist. */
    private suspend fun rollbackAll() {
        journal.start(ProvisioningStep.ROLLBACK)
        runCatching { installer.wipe() }
        runCatching { keys.clearAll() }
        runCatching { state.clearIdentity() }
        runCatching { state.clearDukpt() }
        journal.ok(ProvisioningStep.ROLLBACK)
    }

    private fun fail(code: String, message: String) =
        ProvisioningOutcome.Failure(code, message, journal.render())

    companion object {
        /** Tiga purpose tetap sisi client (spec §1) -- menggantikan "#4 DUKPT" yang ambigu di diagram tim lama. */
        val PURPOSES = setOf("TRACK", "AMOUNT", "PIN")

        const val DEVICE_UNKNOWN = "DEVICE_UNKNOWN"
        const val CHALLENGE_EMPTY = "CHALLENGE_EMPTY"
        /** Backend melanggar kontraknya sendiri -- dukptProvisioningRequired tapi field wajib hilang, atau keySetVersion tidak cocok. */
        const val PROTOCOL_VIOLATION = "PROTOCOL_VIOLATION"
        const val PACKAGE_INVALID = "PACKAGE_INVALID"
        const val KEY_INSTALL_FAILED = "KEY_INSTALL_FAILED"
        const val UNEXPECTED_ERROR = "UNEXPECTED_ERROR"
    }
}
```

- [ ] **Step 4: Verifikasi `ProvisionDeviceUseCase.kt` compile bersih (test lama masih akan gagal — diperbaiki step berikutnya)**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:compileDebugKotlin --no-daemon 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL untuk `compileDebugKotlin` (source utama). Kalau masih ada error di sini, perbaiki sebelum lanjut — ini titik kritis, seluruh module `provisioning-core` harus compile bersih sekarang.

- [ ] **Step 5: Tulis ulang `SignedProvisioningHeadersTest.kt`**

Regresi Critical-1 harus tetap membuktikan hal yang sama — device bersih bisa menyelesaikan ceremony penuh dengan request bertanda tangan — tapi sekarang lewat mekanisme yang sesungguhnya lebih sederhana (persist langsung, bukan sink in-flight).

```kotlin
package com.cashup.provisioning.data.remote

import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.devicesdk.fake.FakeSerialNumberProvider
import com.cashup.devicesdk.fake.FakeTerminalKeyInstaller
import com.cashup.provisioning.StoredDeviceSigner
import com.cashup.provisioning.crypto.PackageUnwrapper
import com.cashup.provisioning.crypto.RsaKeyInfo
import com.cashup.provisioning.crypto.RsaKeyLocation
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.data.local.DukptState
import com.cashup.provisioning.data.local.IdentityState
import com.cashup.provisioning.domain.ProvisionDeviceUseCase
import com.cashup.provisioning.domain.ProvisioningKeys
import com.cashup.provisioning.domain.ProvisioningOutcome
import com.cashup.provisioning.domain.ProvisioningStateRepository
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

/**
 * Regresi untuk temuan Critical 1: `X-Device-Id` tidak pernah tersedia selama
 * ceremony, sehingga J1 mustahil selesai di device bersih.
 *
 * Diperbarui untuk spec 2026-09-17: mekanisme perbaikannya sekarang PERSIST
 * IDENTITY SEGERA setelah redeem sukses (bukan sink in-flight sementara) --
 * lebih sederhana, dan ini yang membuktikannya benar-benar bekerja lewat
 * [StoredDeviceSigner] dan [ProvisioningHttp.create] ASLI, bukan disetub.
 *
 * Tidak butuh Robolectric untuk [StoredDeviceSigner]: dirakit lewat
 * konstruktor JVM-nya (repository + lambda penandatangan). `ProvisionDeviceUseCase`
 * sendiri memakai `android.util.Base64` (deviceSignature) sehingga class ini
 * TETAP butuh Robolectric untuk jalur ceremony penuh.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class SignedProvisioningHeadersTest {

    private lateinit var server: MockWebServer

    private class InMemoryState : ProvisioningStateRepository {
        var identityState: IdentityState? = null
        var dukptState: DukptState? = null
        override fun identity() = identityState
        override fun saveIdentity(state: IdentityState) { identityState = state }
        override fun clearIdentity() { identityState = null }
        override fun dukpt() = dukptState
        override fun saveDukpt(state: DukptState) { dukptState = state }
        override fun clearDukpt() { dukptState = null }
    }

    private class FakeKeys : ProvisioningKeys {
        var clearCount = 0
        override fun ensureRsaKeyPair() = RsaKeyInfo("rsa-spki", RsaKeyLocation.ANDROID_KEYSTORE)
        override fun ensureEd25519KeyPair() = "eddsa-raw"
        override fun unwrapper() = RsaUnwrapper { it }
        override fun certificateChain() = listOf("cert-der-b64")
        override fun signWithRsa(bytes: ByteArray) = ByteArray(256) { 9 }
        override fun clearAll() { clearCount++ }
    }

    private val state = InMemoryState()

    /** Penandatangan asli. Hanya operasi Ed25519-nya yang diganti lambda. */
    private val signer = StoredDeviceSigner(state) { ByteArray(64) { 7 } }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun repository() = ProvisioningHttp.create(server.url("/").toString(), signer)

    private fun packageResponseBody(materialsJson: String, packageKey: java.security.KeyPair): String {
        val nonce = ByteArray(12)
        val aad = "o-1:device-uuid-1:7".toByteArray()
        val ciphertext = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(materialsJson.toByteArray())
        }
        val signed = aad + nonce + ByteArray(32) + ciphertext
        val signature = java.security.Signature.getInstance("Ed25519").run {
            initSign(packageKey.private)
            update(signed)
            sign()
        }
        val b64 = java.util.Base64.getEncoder()
        return """{"data":{"orderId":"o-1","deviceId":"device-uuid-1","keySetVersion":7,"algorithm":"TDES_DUKPT","activationChallenge":"ch-1",""" +
            """"wrappedPackageKey":"${b64.encodeToString(ByteArray(32))}","nonce":"${b64.encodeToString(nonce)}",""" +
            """"ciphertext":"${b64.encodeToString(ciphertext)}","packageSignature":"${b64.encodeToString(signature)}",""" +
            """"signingPublicKey":"${b64.encodeToString(packageKey.public.encoded)}"}}"""
    }

    @Test
    fun `a signed call from an empty state is refused until identity is persisted`() = runBlocking {
        val repository = repository()

        // Persis keadaan yang membuat provisioning mustahil sebelum perbaikan:
        // state kosong, jadi tidak ada identitas untuk ditandatangani.
        assertNull(signer.deviceId())
        val refused = repository.downloadKeyPackage("o-1", "tok-1")
        assertTrue(refused.toString(), refused is ApiResult.Failure)
        assertEquals(0, server.requestCount)

        // Identity sekarang dipersist LANGSUNG lewat state repository -- bukan
        // sink in-flight sementara.
        state.saveIdentity(IdentityState("PAX-A920-0012938", "device-uuid-1", 1))
        server.enqueue(MockResponse().setResponseCode(200).setBody(packageResponseBody("{}", java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair())))

        val result = repository.downloadKeyPackage("o-1", "tok-1")

        assertTrue(result.toString(), result is ApiResult.Success)
        val recorded = server.takeRequest()
        assertEquals("device-uuid-1", recorded.getHeader("X-Device-Id"))
        assertNotNull(recorded.getHeader("X-Timestamp"))
        assertNotNull(recorded.getHeader("X-Nonce"))
        assertNotNull(recorded.getHeader("X-Signature"))
    }

    @Test
    fun `the whole ceremony signs every signed call on a device that was never provisioned`() = runBlocking {
        val repository = repository()
        val keys = FakeKeys()
        val installer = FakeTerminalKeyInstaller()
        val packageKeyPair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"deviceId":"device-uuid-1","credentialKeyVersion":1,"dukptProvisioningRequired":true,"orderId":"o-1","activationToken":"tok-1","keySetVersion":7,"status":"PENDING"}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(packageResponseBody("{}", packageKeyPair)))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"orderId":"o-1","deviceId":"device-uuid-1","keySetId":42,"keySetVersion":7,"status":"ACTIVE"}}"""))

        val outcome = ProvisionDeviceUseCase(
            gateway = repository,
            serialNumbers = FakeSerialNumberProvider("PAX-A920-0012938"),
            keys = keys,
            installer = installer,
            state = state,
            unwrapperFactory = {
                object : PackageUnwrapper(RsaUnwrapper { it }) {
                    override fun unwrap(response: KeyPackageResponse) = listOf(
                        TerminalKeyMaterial("PIN", ByteArray(16) { 1 }, ByteArray(10) { 2 }),
                    )
                }
            },
        )("ABCD-1234")

        assertTrue(outcome.toString(), outcome is ProvisioningOutcome.Success)

        // 1. qr-redeem -- TIDAK ditandatangani.
        val redeem = server.takeRequest()
        assertEquals("/v1/terminal-key-provisioning/qr-redeem", redeem.path)
        assertNull(redeem.getHeader("X-Signature"))

        // 2-3. Keduanya ditandatangani -- inilah yang tidak pernah bisa
        //      berjalan tanpa identity dipersist sedini ini.
        listOf(
            "/v1/terminal-key-provisioning/orders/o-1/package",
            "/v1/terminal-key-provisioning/orders/o-1/activate",
        ).forEach { expectedPath ->
            val recorded = server.takeRequest()
            assertEquals(expectedPath, recorded.path)
            assertEquals(expectedPath, "device-uuid-1", recorded.getHeader("X-Device-Id"))
            assertNotNull(expectedPath, recorded.getHeader("X-Timestamp"))
            assertNotNull(expectedPath, recorded.getHeader("X-Nonce"))
            assertNotNull(expectedPath, recorded.getHeader("X-Signature"))
        }

        assertEquals("device-uuid-1", state.identityState?.deviceId)
        assertEquals(42L, state.dukptState?.keySetId)
        assertEquals("device-uuid-1", signer.deviceId())
    }

    @Test
    fun `a DUKPT failure keeps identity but wipes DUKPT`() = runBlocking {
        val repository = repository()

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"deviceId":"device-uuid-1","credentialKeyVersion":1,"dukptProvisioningRequired":true,"orderId":"o-1","activationToken":"tok-1","keySetVersion":7,"status":"PENDING"}}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"TERMINAL_INACTIVE","message":"Tidak aktif"}}"""))

        val outcome = ProvisionDeviceUseCase(
            gateway = repository,
            serialNumbers = FakeSerialNumberProvider("PAX-A920-0012938"),
            keys = FakeKeys(),
            installer = FakeTerminalKeyInstaller(),
            state = state,
        )("ABCD-1234")

        assertEquals("TERMINAL_INACTIVE", (outcome as ProvisioningOutcome.Failure).code)
        // Identity TETAP -- ini inti perbedaan dari model rollback lama.
        assertEquals("device-uuid-1", signer.deviceId())
        assertEquals("device-uuid-1", state.identityState?.deviceId)
        assertNull(state.dukptState)
    }
}
```

- [ ] **Step 6: Tulis ulang `ProvisionDeviceUseCaseTest.kt`**

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
import com.cashup.provisioning.data.local.DukptState
import com.cashup.provisioning.data.local.IdentityState
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.ActivateResponse
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
        var redeemResult: ApiResult<QrRedeemResponse> = ApiResult.Success(
            QrRedeemResponse("device-uuid-1", 1, true, "o-1", "tok-1", 7, null, "PENDING")
        ),
        var packageResult: ApiResult<KeyPackageResponse> = ApiResult.Success(
            KeyPackageResponse("o-1", "device-uuid-1", 7, "TDES_DUKPT", "ch-1", "wrapped", "nonce", "cipher", "sig", "pub")
        ),
        var activateResult: ApiResult<ActivateResponse> = ApiResult.Success(
            ActivateResponse("o-1", "device-uuid-1", 42, 7, "ACTIVE")
        ),
    ) : ProvisioningGateway {
        var redeemRequest: QrRedeemRequest? = null
        var activateRequest: ActivateRequest? = null

        override suspend fun redeem(request: QrRedeemRequest) = redeemResult.also { redeemRequest = request }
        override suspend fun downloadKeyPackage(orderId: String, activationToken: String) = packageResult
        override suspend fun activate(orderId: String, request: ActivateRequest) =
            activateResult.also { activateRequest = request }
    }

    private class FakeKeys : ProvisioningKeys {
        var ensureCount = 0
        var clearCount = 0
        override fun ensureRsaKeyPair(): RsaKeyInfo { ensureCount++; return RsaKeyInfo("rsa-spki", RsaKeyLocation.ANDROID_KEYSTORE) }
        override fun ensureEd25519KeyPair() = "eddsa-raw"
        override fun unwrapper() = RsaUnwrapper { it }
        override fun certificateChain() = listOf("cert-der-b64")
        override fun signWithRsa(bytes: ByteArray) = ByteArray(256) { 9 }
        override fun clearAll() { clearCount++ }
    }

    private class FakeState : ProvisioningStateRepository {
        var identityState: IdentityState? = null
        var dukptState: DukptState? = null
        var clearIdentityCount = 0
        var clearDukptCount = 0
        override fun identity() = identityState
        override fun saveIdentity(state: IdentityState) { identityState = state }
        override fun clearIdentity() { identityState = null; clearIdentityCount++ }
        override fun dukpt() = dukptState
        override fun saveDukpt(state: DukptState) { dukptState = state }
        override fun clearDukpt() { dukptState = null; clearDukptCount++ }
    }

    private fun materials() = listOf(
        TerminalKeyMaterial("PIN", ByteArray(16) { 1 }, ByteArray(10) { 2 }),
        TerminalKeyMaterial("TRACK", ByteArray(16) { 3 }, ByteArray(10) { 4 }),
        TerminalKeyMaterial("AMOUNT", ByteArray(16) { 5 }, ByteArray(10) { 6 }),
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
                override fun unwrap(response: KeyPackageResponse) = unwrap()
            }
        },
    )

    @Test
    fun `a clean run installs the keys and persists both identity and dukpt`() = runTest {
        val state = FakeState()
        val gateway = FakeGateway()

        val outcome = useCase(gateway = gateway, state = state)("ABCD-1234")

        assertTrue(outcome.toString(), outcome is ProvisioningOutcome.Success)
        val success = outcome as ProvisioningOutcome.Success
        assertEquals("device-uuid-1", success.deviceId)
        assertTrue(success.dukptProvisioningRequired)
        assertEquals("o-1", success.orderId)
        assertEquals(
            KeyBacking.VENDOR_SECURE_MODULE,
            success.installed.single { it.purpose == "PIN" }.backing,
        )
        assertEquals("device-uuid-1", state.identityState?.deviceId)
        assertEquals(42L, state.dukptState?.keySetId)
    }

    @Test
    fun `redeem sends three fixed purposes and the certificate chain`() = runTest {
        val gateway = FakeGateway()

        useCase(gateway = gateway)("ABCD-1234")

        val request = gateway.redeemRequest!!
        assertEquals(setOf("TRACK", "AMOUNT", "PIN"), request.purposes)
        assertEquals(listOf("cert-der-b64"), request.deviceCertificateChain)
        assertEquals("PAX-A920-0012938", request.serialNumber)
    }

    @Test
    fun `activate carries a base64 device signature`() = runTest {
        val gateway = FakeGateway()

        useCase(gateway = gateway)("ABCD-1234")

        val sent = gateway.activateRequest!!.deviceSignature
        assertTrue(sent.isNotBlank())
        // Base64 valid -- tidak melempar berarti valid.
        java.util.Base64.getDecoder().decode(sent)
    }

    @Test
    fun `a credential-only redeem persists identity and returns success without touching DUKPT`() = runTest {
        val state = FakeState()
        val gateway = FakeGateway(
            redeemResult = ApiResult.Success(QrRedeemResponse("device-uuid-1", 2, false, null, null, null, null, "ACTIVE"))
        )
        val installer = FakeTerminalKeyInstaller()

        val outcome = useCase(gateway = gateway, state = state, installer = installer)("ABCD-1234")

        val success = outcome as ProvisioningOutcome.Success
        assertEquals(false, success.dukptProvisioningRequired)
        assertNull(success.orderId)
        assertTrue(success.installed.isEmpty())
        assertEquals("device-uuid-1", state.identityState?.deviceId)
        assertNull(state.dukptState)
        assertTrue(installer.installedPurposes.isEmpty())
    }

    @Test
    fun `the challenge code is normalized before it is sent`() = runTest {
        val gateway = FakeGateway()

        useCase(gateway = gateway)("  abcd-1234 ")

        assertEquals("abcd1234", gateway.redeemRequest!!.challengeCode)
    }

    @Test
    fun `an unknown device never reaches the network`() = runTest {
        val gateway = FakeGateway()

        val outcome = useCase(gateway = gateway, serial = null)("ABCD-1234")

        assertEquals("DEVICE_UNKNOWN", (outcome as ProvisioningOutcome.Failure).code)
        assertNull(gateway.redeemRequest)
    }

    @Test
    fun `an empty challenge code fails before any key is generated`() = runTest {
        val keys = FakeKeys()

        val outcome = useCase(keys = keys)("   ")

        assertEquals("CHALLENGE_EMPTY", (outcome as ProvisioningOutcome.Failure).code)
        assertEquals(0, keys.ensureCount)
    }

    @Test
    fun `a rejected redeem rolls back everything -- keys and identity`() = runTest {
        val keys = FakeKeys()
        val state = FakeState()
        val gateway = FakeGateway(
            redeemResult = ApiResult.Failure(ApiError("PROVISIONING_TOKEN_INVALID", "Kedaluwarsa", 410))
        )

        val outcome = useCase(gateway = gateway, keys = keys, state = state)("ABCD-1234")

        assertEquals("PROVISIONING_TOKEN_INVALID", (outcome as ProvisioningOutcome.Failure).code)
        assertEquals(1, keys.clearCount)
        assertEquals(1, state.clearIdentityCount)
        assertNull(state.identityState)
    }

    @Test
    fun `a protocol violation is flagged when dukptProvisioningRequired is true but fields are missing`() = runTest {
        val state = FakeState()
        val gateway = FakeGateway(
            redeemResult = ApiResult.Success(QrRedeemResponse("device-uuid-1", 1, true, null, null, null, null, "PENDING"))
        )

        val outcome = useCase(gateway = gateway, state = state)("ABCD-1234")

        assertEquals("PROTOCOL_VIOLATION", (outcome as ProvisioningOutcome.Failure).code)
        // Identity SUDAH ter-persist sebelum pengecekan ini -- rollback DUKPT
        // saja, bukan total.
        assertEquals("device-uuid-1", state.identityState?.deviceId)
    }

    @Test
    fun `a failed key package download keeps identity but rolls back DUKPT`() = runTest {
        val keys = FakeKeys()
        val state = FakeState()
        val installer = FakeTerminalKeyInstaller()
        val gateway = FakeGateway(
            packageResult = ApiResult.Failure(ApiError("TERMINAL_INACTIVE", "Tidak aktif", 403))
        )

        val outcome = useCase(gateway = gateway, keys = keys, state = state, installer = installer)("ABCD-1234")

        assertEquals("TERMINAL_INACTIVE", (outcome as ProvisioningOutcome.Failure).code)
        assertEquals("device-uuid-1", state.identityState?.deviceId)
        assertNull(state.dukptState)
        assertEquals(0, keys.clearCount)
        assertEquals(1, installer.wipeCount)
    }

    @Test
    fun `a corrupted key package rolls back DUKPT before anything is installed, identity survives`() = runTest {
        val keys = FakeKeys()
        val state = FakeState()
        val installer = FakeTerminalKeyInstaller()

        val outcome = useCase(keys = keys, state = state, installer = installer, unwrap = {
            throw PackageIntegrityException("Signature paket key dari server tidak valid")
        })("ABCD-1234")

        assertEquals("PACKAGE_INVALID", (outcome as ProvisioningOutcome.Failure).code)
        assertTrue(installer.installedPurposes.isEmpty())
        assertEquals(1, installer.wipeCount)
        assertEquals(0, keys.clearCount)
        assertEquals("device-uuid-1", state.identityState?.deviceId)
    }

    @Test
    fun `a refused key installation rolls back DUKPT and names the purpose`() = runTest {
        val installer = FakeTerminalKeyInstaller(failOnPurpose = "TRACK")
        val state = FakeState()

        val outcome = useCase(installer = installer, state = state)("ABCD-1234")

        val failure = outcome as ProvisioningOutcome.Failure
        assertEquals("KEY_INSTALL_FAILED", failure.code)
        assertTrue(failure.message, failure.message.contains("TRACK"))
        assertEquals(1, installer.wipeCount)
        assertEquals("device-uuid-1", state.identityState?.deviceId)
        assertNull(state.dukptState)
    }

    @Test
    fun `an activate failure rolls back DUKPT, identity survives`() = runTest {
        val state = FakeState()
        val gateway = FakeGateway(
            activateResult = ApiResult.Failure(ApiError("TERMINAL_INACTIVE", "Tidak aktif", 403))
        )

        val outcome = useCase(gateway = gateway, state = state)("ABCD-1234")

        assertEquals("TERMINAL_INACTIVE", (outcome as ProvisioningOutcome.Failure).code)
        assertEquals("device-uuid-1", state.identityState?.deviceId)
        assertNull(state.dukptState)
    }

    @Test
    fun `an unexpected exception before identity persists rolls back everything`() = runTest {
        val keys = FakeKeys()
        val state = FakeState()
        val gateway = object : ProvisioningGateway {
            override suspend fun redeem(request: QrRedeemRequest): ApiResult<QrRedeemResponse> =
                throw RuntimeException("simulated network stack failure")
            override suspend fun downloadKeyPackage(orderId: String, activationToken: String) =
                error("not reached")
            override suspend fun activate(orderId: String, request: ActivateRequest) = error("not reached")
        }

        val outcome = useCase(gateway = gateway, keys = keys, state = state)("ABCD-1234")

        assertEquals("UNEXPECTED_ERROR", (outcome as ProvisioningOutcome.Failure).code)
        assertEquals(1, keys.clearCount)
        assertNull(state.identityState)
    }

    @Test
    fun `an Error from the installer after identity persists rolls back DUKPT only`() = runTest {
        val keys = FakeKeys()
        val state = FakeState()
        val installer = object : com.cashup.devicesdk.TerminalKeyInstaller {
            var wipeCount = 0
            override suspend fun install(materials: List<TerminalKeyMaterial>): com.cashup.devicesdk.TerminalKeyInstallResult =
                throw AssertionError("simulated binder crash")
            override suspend fun wipe() { wipeCount++ }
        }

        val outcome = ProvisionDeviceUseCase(
            gateway = FakeGateway(),
            serialNumbers = FakeSerialNumberProvider("PAX-A920-0012938"),
            keys = keys,
            installer = installer,
            state = state,
            unwrapperFactory = {
                object : PackageUnwrapper(RsaUnwrapper { it }) {
                    override fun unwrap(response: KeyPackageResponse) = materials()
                }
            },
        )("ABCD-1234")

        assertEquals("UNEXPECTED_ERROR", (outcome as ProvisioningOutcome.Failure).code)
        assertEquals(1, installer.wipeCount)
        assertEquals(0, keys.clearCount)
        assertEquals("device-uuid-1", state.identityState?.deviceId)
    }
}
```

- [ ] **Step 7: Jalankan seluruh tes module**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :provisioning-core:testDebugUnitTest --no-daemon 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. `ProvisionDeviceUseCaseTest` 14 tes, `SignedProvisioningHeadersTest` 3 tes, semua lolos.

- [ ] **Step 8: Commit**

```bash
git add provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisionDeviceUseCase.kt provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisioningOutcome.kt provisioning-core/src/main/kotlin/com/cashup/provisioning/data/domain/ProvisioningStep.kt provisioning-core/src/test/kotlin/com/cashup/provisioning/data/remote/SignedProvisioningHeadersTest.kt provisioning-core/src/test/kotlin/com/cashup/provisioning/domain/ProvisionDeviceUseCaseTest.kt
git commit -m "feat(provisioning-core): rewrite the ceremony for edc-mobile's two-phase model

The eleven-step ceremony now branches on dukptProvisioningRequired and
rolls back at two different scopes depending on whether identity has
already been persisted (spec 2026-09-17 section 2.1):

Before identity persists (redeem itself rejected): full rollback --
locally generated keys were never confirmed by the backend, so they are
discarded along with any state.

After identity persists (any failure in DOWNLOAD_PACKAGE through
ACTIVATE, including the new PROTOCOL_VIOLATION check for a backend that
claims dukptProvisioningRequired but omits the fields that requires):
DUKPT-only rollback. Identity -- deviceId, credentialKeyVersion, both
keypairs -- survives. A device that fails partway through DUKPT
provisioning is still a device the backend recognizes; it does not need
to re-scan a QR code to try again.

The challenge-code-before-keygen ordering from the previous
implementation is kept deliberately, even though the spec's own
narrative numbers key generation before QR scanning -- that numbering
describes edc-mobile's UI-level sequence, not a mandate to reopen the
validate-before-generate fix a previous review already made.

SignedProvisioningHeadersTest is rewritten to prove the same Critical-1
property against the new mechanism: a real StoredDeviceSigner reading a
real ProvisioningStateRepository, with identity written by
state.saveIdentity() as soon as redeem succeeds rather than through an
in-flight sink.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: `AppContainer` — wiring untuk model baru

`isProvisioned()` sekarang berarti DUKPT aktif, bukan sekadar ada state apa pun — device yang cuma punya identity (habis refresh-identitas-saja) belum bisa transaksi. Parameter `deviceIdentity` yang dikirim ke `ProvisionDeviceUseCase` sudah tidak ada (Task 7-8 membuangnya).

**Files:**
- Modify: `app/src/main/kotlin/com/cashup/app/di/AppContainer.kt`

**Interfaces:**
- Consumes: `ProvisionDeviceUseCase` konstruktor baru (Task 8), `ProvisioningStateStore.dukpt()` (Task 2).
- Produces: `AppContainer.isProvisioned(): Boolean` — sekarang `stateStore.dukpt() != null`.

- [ ] **Step 1: Ubah `isProvisioned()` dan `provisionDeviceUseCase()` di `AppContainer.kt`**

Ganti baris:

```kotlin
    fun isProvisioned(): Boolean = stateStore.current() != null
```

menjadi:

```kotlin
    /** DUKPT aktif, bukan sekadar identity ada -- device dengan identity saja
     * (habis refresh-identitas-saja, atau redeem pertama yang gagal sebelum
     * activate) belum bisa transaksi. */
    fun isProvisioned(): Boolean = stateStore.dukpt() != null
```

Di `fun provisionDeviceUseCase()`, hapus baris parameter `deviceIdentity = deviceSigner,` beserta komentarnya (paragraf yang menjelaskan kenapa instance `StoredDeviceSigner` harus sama — itu penjelasan untuk mekanisme yang sudah dibuang di Task 7-8). Blok `return ProvisionDeviceUseCase(...)` menjadi:

```kotlin
        return ProvisionDeviceUseCase(
            gateway = repository,
            serialNumbers = serialNumbers,
            keys = AndroidProvisioningKeys(rsa, ed25519),
            installer = EdcSdkTerminalKeyInstaller(appContext),
            state = stateStore,
            // SEMENTARA -- jurnal hanya diisi di build debug. Dicabut bersama
            // package audit/ sebelum produksi; lihat Task 10 plan 16 September.
            journal = ProvisioningJournal(if (BuildConfig.PROVISIONING_JOURNAL) logger else NoOpPaymentLogger),
        )
```

- [ ] **Step 2: Verifikasi `:app` compile bersih**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -40`
Expected: masih ada error di `ProvisioningViewModel.kt`/`ProvisioningUiState.kt`/`ResultFragment.kt` (ditangani Task 10) — tapi TIDAK ada error di `AppContainer.kt` sendiri.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/cashup/app/di/AppContainer.kt
git commit -m "refactor(app): AppContainer follows the two-phase provisioning model

isProvisioned() now checks stateStore.dukpt(), not the old combined
state -- a device that only completed an identity-only redeem
(dukptProvisioningRequired=false) or failed before activate is not yet
'provisioned' for routing purposes, since it has no DUKPT key to
transact with.

Drops the deviceIdentity wiring ProvisionDeviceUseCase's constructor no
longer takes (DeviceIdentitySink retired in an earlier task).

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: UI — bedakan hasil refresh-identitas vs provisioning penuh

**Files:**
- Modify: `app/src/main/kotlin/com/cashup/app/ui/provisioning/ProvisioningUiState.kt`
- Modify: `app/src/main/kotlin/com/cashup/app/ui/provisioning/ProvisioningViewModel.kt`
- Modify: `app/src/main/kotlin/com/cashup/app/ui/provisioning/ResultFragment.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/kotlin/com/cashup/app/ui/provisioning/ProvisioningViewModelTest.kt`

**Interfaces:**
- Consumes: `ProvisioningOutcome.Success` bentuk baru (Task 8).
- Produces: `data class Success(serialNumber, deviceId, dukptProvisioningRequired: Boolean, orderId: String?, installed, journalText)` di `ProvisioningUiState` — field baru `deviceId`, `dukptProvisioningRequired`; `orderId` jadi nullable.

- [ ] **Step 1: Ubah `ProvisioningUiState.Success`**

Ganti blok `Success` di `ProvisioningUiState.kt`:

```kotlin
    data class Success(
        val serialNumber: String,
        val deviceId: String,
        val dukptProvisioningRequired: Boolean,
        val orderId: String?,
        val installed: List<KeyInstallOutcome>,
        val journalText: String,
    ) : ProvisioningUiState
```

- [ ] **Step 2: Ubah mapping di `ProvisioningViewModel.kt`**

Ganti blok `is ProvisioningOutcome.Success -> ...`:

```kotlin
                    is ProvisioningOutcome.Success -> ProvisioningUiState.Success(
                        serialNumber = outcome.serialNumber,
                        deviceId = outcome.deviceId,
                        dukptProvisioningRequired = outcome.dukptProvisioningRequired,
                        orderId = outcome.orderId,
                        installed = outcome.installed,
                        journalText = outcome.journalText,
                    )
```

- [ ] **Step 3: Tambah string resource di `strings.xml`**

Tambahkan dua string baru di dekat `result_success_title` yang sudah ada:

```xml
    <string name="result_identity_refreshed_title">Identitas diperbarui</string>
    <string name="result_identity_refreshed_detail">Perangkat sudah dikenal backend. Key pembayaran (DUKPT) yang sudah ada tidak disentuh.</string>
```

- [ ] **Step 4: Ubah cabang `Success` di `ResultFragment.kt`**

Ganti blok `is ProvisioningUiState.Success -> { ... }`:

```kotlin
            is ProvisioningUiState.Success -> {
                if (state.dukptProvisioningRequired) {
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
                } else {
                    // Refresh identitas saja (spec 17 September §2 langkah 6a) --
                    // tidak ada orderId, tidak ada key DUKPT baru untuk ditampilkan.
                    binding.title.setText(R.string.result_identity_refreshed_title)
                    binding.detail.text = getString(R.string.result_identity_refreshed_detail)
                    binding.backings.text = ""
                }
                showJournal(binding, state.journalText)
                binding.action.setText(R.string.result_continue)
                binding.action.setOnClickListener { requireActivity().finish() }
            }
```

- [ ] **Step 5: Ubah `successOutcome()` (atau helper serupa) di `ProvisioningViewModelTest.kt`**

Cari fungsi helper yang membangun `ProvisioningOutcome.Success(...)` untuk tes (kemungkinan bernama `successOutcome()`). Tambahkan dua argumen baru sesuai constructor Task 8 (`deviceId`, `dukptProvisioningRequired`) ke setiap pemanggilan constructor itu:

```kotlin
    private fun successOutcome() = ProvisioningOutcome.Success(
        serialNumber = "PAX-A920-0012938",
        deviceId = "device-uuid-1",
        dukptProvisioningRequired = true,
        orderId = "o-1",
        installed = listOf(KeyInstallOutcome("PIN", KeyBacking.VENDOR_SECURE_MODULE)),
        journalText = "OK REDEEM",
    )
```

Sesuaikan setiap assertion di tes yang sudah ada yang membaca `state.orderId`/`state.serialNumber` pada `ProvisioningUiState.Success` — field-field itu masih ada dengan nama sama, jadi kemungkinan besar tidak ada assertion lain yang perlu berubah selain constructor call di atas.

- [ ] **Step 6: Jalankan seluruh tes `:app`**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :app:testDebugUnitTest --no-daemon 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, semua tes yang ada sebelumnya tetap lolos.

- [ ] **Step 7: Verifikasi `:app:assembleDebug` (APK utuh)**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew :app:assembleDebug --no-daemon 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/cashup/app/ui/provisioning/ProvisioningUiState.kt app/src/main/kotlin/com/cashup/app/ui/provisioning/ProvisioningViewModel.kt app/src/main/kotlin/com/cashup/app/ui/provisioning/ResultFragment.kt app/src/main/res/values/strings.xml app/src/test/kotlin/com/cashup/app/ui/provisioning/ProvisioningViewModelTest.kt
git commit -m "feat(app): show a distinct Result screen for identity-only refresh

ProvisioningUiState.Success gains deviceId and dukptProvisioningRequired,
mirroring ProvisioningOutcome.Success. ResultFragment branches on the
latter: a full provisioning success still shows the serial/order/per-
purpose backing detail, but a credential-only refresh (spec 2026-09-17
section 2 step 6a -- a device that already has an active DUKPT key
redeeming again purely to refresh its Ed25519 identity) shows a
dedicated message instead of an empty orderId/backings list.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Verifikasi penuh, penyelarasan dokumen

**Files:**
- Modify: `docs/superpowers/specs/2026-09-16-provisioning-design.md` — tambahkan catatan supersede di bagian atas
- Modify: `docs/EDC_PAYMENT_APP_DESIGN.md` §2, §4 J1 — sinkronkan dengan model baru
- Modify: `docs/session_log.md`

**Interfaces:**
- Consumes: seluruh hasil Task 1–10.
- Produces: dokumentasi yang konsisten dengan kode yang ada, `clean build` penuh.

- [ ] **Step 1: Tandai spec 16 September sebagai di-supersede sebagian**

Di paling atas `docs/superpowers/specs/2026-09-16-provisioning-design.md`, tepat setelah baris `**Status:**`, tambahkan:

```markdown
> **§1–§4, §7 DIGANTIKAN** oleh `docs/superpowers/specs/2026-09-17-provisioning-edc-mobile-adoption-design.md`
> — kontrak wire, kriptografi, ceremony, dan state model sekarang mengikuti
> `edc-mobile` penuh, bukan diagram tim. Bagian lain dokumen ini (§5 struktur
> module umum, §6 MVVM, §9 sisa item lama, §10 di luar scope) masih berlaku.
```

- [ ] **Step 2: Perbaiki `EDC_PAYMENT_APP_DESIGN.md` §2 dan §4 J1**

Di §2 (tabel "Keputusan Arsitektur Kunci"), baris "Provisioning bootstrap" — ganti isinya jadi:

```
| Provisioning bootstrap | Scan QR (challengeCode) → redeem ke backend → identitas (deviceId UUID + kedua keypair) persist SEGERA → DUKPT (kalau dibutuhkan) lewat paket hybrid AES-GCM bertanda tangan. Tidak ada login teknisi. Detail: spec provisioning-edc-mobile-adoption. |
```

Di §4 J1, ganti seluruh daftar langkah dengan pointer:

```markdown
### J1 — Provisioning (Setup Pertama Kali)

Lihat `docs/superpowers/specs/2026-09-17-provisioning-edc-mobile-adoption-design.md`
§2 untuk alur sebelas langkah lengkap (deviceId, purposes tetap, sertifikat
proof-of-possession, paket hybrid AES-GCM, rollback dua tingkat).
```

- [ ] **Step 3: Perbarui `docs/session_log.md`**

Tambahkan bagian baru di bawah bagian "Provisioning (J1) — done" yang sudah ada, sebelum "What's next":

```markdown
## Provisioning (J1) — kontrak diganti ke `edc-mobile` penuh

Spec: `docs/superpowers/specs/2026-09-17-provisioning-edc-mobile-adoption-design.md`.
Membalik otoritas dari diagram tim (spec 16 September) ke `edc-mobile`
(`D:\gandha_cashup\projects\edc-mobile copy`) — implementasi yang sudah
terbukti jalan lawan `corepayment` sungguhan, bukan sekadar sketsa tim.

Lima perubahan substantif: identitas `deviceId` UUID backend (bukan serial
number lokal) dipersist SEGERA setelah redeem sukses, bukan lewat mekanisme
in-flight sementara (`DeviceIdentitySink` dibuang); paket key jadi hybrid
AES-GCM ditandatangani Ed25519 server (RSA cuma membungkus kunci AES 32
byte, bukan payload penuh — menjawab soal kapasitas 190 byte yang sempat
jadi open item); RSA jadi dual-purpose (decrypt + sign) untuk sertifikat
self-signed X.509 proof-of-possession dan `deviceSignature` di `/activate`;
atomicity pecah dua fase (identity vs DUKPT, rollback DUKPT tidak menyentuh
identity); tiga purpose DUKPT tetap sisi client (TRACK/AMOUNT/PIN),
menjawab "#4 DUKPT" yang ambigu di diagram tim lama.

Dieksekusi sebagai plan terpisah:
`docs/superpowers/plans/2026-09-17-provisioning-edc-mobile-adoption.md`
(11 task, di atas 15 task provisioning sebelumnya — tidak menggantikan
struktur module, hanya kontrak wire/kripto/state di dalamnya).
```

- [ ] **Step 4: `clean build` menyeluruh**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew clean build --no-daemon 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. Kalau gagal, diagnosis penuh sebelum melanjutkan — jangan tebak-tebak patch tempel.

- [ ] **Step 5: Seluruh tes**

Run: `export JAVA_HOME="/c/Users/W5NXL/.jdks/ms-17.0.20.1" && cd /d/gandha_cashup/projects/mobile-cashup-payment && ./gradlew test testDebugUnitTest --no-daemon 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, 0 gagal. Laporkan hitungan tes per module di laporan task.

- [ ] **Step 6: Verifikasi tidak ada sisa referensi ke API lama**

Run: `grep -rn "DeviceIdentitySink\|setInFlightSerial\|ProvisioningState(\|KeyPackageRequest\|stateStore.current()\|state.current()" --include=*.kt provisioning-core app`
Expected: kosong. Kalau ada hasil, itu sisa yang terlewat dari task sebelumnya — perbaiki sebelum menutup task ini.

- [ ] **Step 7: Verifikasi tidak ada key material yang bisa masuk log di luar `Evidence`**

Run: `grep -rn "ipek\|IPEK\|aesKey\|packageKey" --include=*.kt provisioning-core/src/main | grep -i "log\|println\|toString()"`
Expected: kosong, atau hasil yang jelas berada di dalam `Evidence.kt` sendiri / bagian yang sudah melalui `Evidence.secret(...)`.

- [ ] **Step 8: Commit**

```bash
git add docs/
git commit -m "docs: align design docs with the edc-mobile provisioning contract

Marks 2026-09-16 spec sections 1-4 and 7 as superseded, points
EDC_PAYMENT_APP_DESIGN.md's provisioning sections at the new spec instead
of restating a contract that no longer matches the code, and records the
change in session_log.md for whoever resumes next.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Setelah plan ini

Provisioning J1 sekarang jalan lawan kontrak `edc-mobile` penuh, bukan diagram tim. Yang belum:

1. **Validasi manual di terminal fisik** untuk seluruh jalur kripto baru — verifikasi signature Ed25519 server, unwrap hybrid AES-GCM, generate sertifikat X.509 dari key TEE sungguhan (bukan software seperti di `SelfSignedCertificateTest`), `deviceSignature` RSA dari key TEE.
2. **Konfirmasi ke backend**: apakah bentuk `PlainKeyPackage`/AAD/urutan byte signature yang diadopsi dari `edc-mobile` benar-benar sama persis dengan yang dipakai backend produksi (bukan cuma `corepayment` dev) — ini asumsi terbesar yang belum diverifikasi lawan sistem nyata.
3. **J2/J3 penuh** (menu Device Settings, re-provisioning UI eksplisit, deactivation) — masih di luar scope, cuma efek samping `dukptProvisioningRequired=false` yang masuk lewat plan ini.
4. Item lain yang sudah tercatat di plan 16 September §"Setelah plan ini" yang belum selesai: cabut package `audit/` sebelum rilis produksi (checklist Task 10 plan lama perlu satu baris tambahan: `ProvisioningStep` sekarang tinggal permanen di `domain`, bukan `audit` — sudah benar sejak sebelumnya, tidak perlu diubah lagi oleh plan ini).

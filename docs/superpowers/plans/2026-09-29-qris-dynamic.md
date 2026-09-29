# QRIS Dynamic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ganti toast "Fitur belum tersedia" pada tombol QRIS dengan alur QRIS Dynamic end-to-end: generate QR ber-amount → tampil di layar → tunggu konfirmasi (MQTT push + polling `inquiries`) → hasil dibayar / kedaluwarsa / gagal.

**Architecture:** Dua modul Android library baru mengikuti pasangan `:cdcp-core` / `:feature-card-payment`: `:qris-core` (Retrofit + signing yang sama dengan CDCP, `QrisRepository`, `QrisPaymentTracker` di atas `PaymentStatusWaiter`) dan `:feature-qris` (Fragment + ViewModel + render QR lewat ZXing). `:app` menyambungkan lewat `AppQrisDependencies` (koneksi MQTT lazy) dan `QrisDependenciesOwner` di `CashupApp`. Navigasi dari tombol QRIS memakai deep-link `cashup://qris?amount=…` supaya `:feature-card-payment` tidak bergantung ke `:feature-qris`.

**Tech Stack:** Kotlin, Retrofit 2.11 + OkHttp 4.12 + Gson, `kotlinx-coroutines` 1.9.0, Eclipse Paho (sudah ada di `:notification-core`), ZXing `com.google.zxing:core:3.5.3`, AndroidX Navigation 2.5.3, JUnit4 + MockWebServer + `kotlinx-coroutines-test` (virtual time, tanpa `Thread.sleep`).

**Spec:** `docs/superpowers/specs/2026-09-29-qris-dynamic-design.md` (kontrak backend: `D:\Downloads\qris-mobile-api.md`, disebut "dokumen API" di spec). Baca spec sebelum mengerjakan task mana pun.

## Global Constraints

- **Subset API 23 / Java 8** untuk semua source `main` di modul baru: tanpa `java.time.*`, `java.util.Base64`, `java.util.function.*`, `Optional`, dan tanpa parsing timestamp ISO-8601 (`minSdk = 23`, tidak ada core library desugaring; repo sudah 3x kena bug kelas ini — lihat header `common-core/build.gradle.kts`). Countdown QR memakai `qrExpirySeconds` + jam monotonic lokal, bukan `expiresAt`.
- Kesalahan backend diputuskan dari `error.code`, **tidak pernah** dari `message`.
- `Idempotency-Key`: UUID baru untuk setiap `POST`. `inquiries` memakai kunci **baru di setiap panggilan** (kunci yang sama memutar ulang jawaban pertama). `qr-codes` memakai `attemptId` sebagai kunci dan request-nya dibekukan per `attemptId` sampai sukses.
- Urutan interceptor mengikat: `RequestHeadersInterceptor()` **sebelum** `SigningInterceptor(signer)`.
- Timing tracker (dari dokumen API): tunggu push 5 dtk sebelum polling; polling tiap 10 dtk bila `transport.isConnected`, 3 dtk bila tidak; setelah push cocok → 1× `inquiry` konfirmasi; batas akhir `qrExpirySeconds * 1000 + 5000` ms lalu 1× `inquiry` terakhir, baru `EXPIRED` lokal.
- Push hanya diterima bila `v == 1`, `type == "qris.payment_paid"`, `module == "qris"`, dan `data.transactionId` sama dengan QR di layar. `data.reference` tidak dipakai untuk mencocokkan. `data.detail` opsional.
- `PaymentStatusWaiter` / tracker **tidak pernah** memanggil `transport.disconnect()`.
- MQTT tetap plain `tcp://` port `1883`, anonymous (keputusan spec 2026-09-28 §1). Tidak menambah TLS/login.
- Bahasa UI: Indonesia. Nama string resource berawalan `qris_`.
- `./gradlew checkModuleBoundaries` harus hijau setelah setiap perubahan graph modul.
- Setiap commit diakhiri trailer: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` (contoh perintah di tiap task memakai `-m` kedua untuk itu).

## File Structure

```
qris-core/
  build.gradle.kts
  src/main/kotlin/com/cashup/qris/
    QrisModels.kt          domain publik: PayApp, QrSupport, GeneratedQr, QrisStatus, QrisPaymentResult, QrisPushDetail
    QrisDtos.kt            DTO Gson internal + mapper ke domain (semua field nullable, defensif)
    QrisGateway.kt         interface: payApps / generate / inquiry (seam untuk test tracker & app)
    QrisRepository.kt      Retrofit QrisApi + implementasi QrisGateway (signing, idempotency)
    PushParser.kt          parse payload push MQTT -> ParsedPush?
    QrisPaymentTracker.kt  race push vs polling di atas PaymentStatusWaiter
  src/test/kotlin/com/cashup/qris/
    QrisModelsTest.kt  QrisRepositoryTest.kt  QrisPaymentTrackerTest.kt
feature-qris/
  build.gradle.kts
  src/main/kotlin/com/cashup/feature/qris/
    QrisDependencies.kt    interface + QrisDependenciesOwner
    QrisErrorMessages.kt   error.code -> @StringRes
    QrCodeEncoder.kt       ZXing -> BitMatrix
    QrisViewModel.kt       state machine + Factory
    QrisFragment.kt        UI
  src/main/res/layout/fragment_qris.xml, values/strings.xml, values/colors.xml
  src/test/kotlin/com/cashup/feature/qris/
    QrCodeEncoderTest.kt  QrisErrorMessagesTest.kt  QrisViewModelTest.kt
app/
  src/main/kotlin/com/cashup/app/di/AppQrisDependencies.kt   (baru)
  src/main/kotlin/com/cashup/app/di/AppContainer.kt          (modif: qrisDependencies)
  src/main/kotlin/com/cashup/app/CashupApp.kt                (modif: QrisDependenciesOwner)
  src/main/res/navigation/nav_home.xml                       (modif: destinasi nav_qris + deep-link)
  src/test/kotlin/com/cashup/app/di/AppQrisDependenciesTest.kt
feature-card-payment/  CardPaymentFragment.kt (modif: tombol QRIS), build.gradle.kts (navigation dep)
settings.gradle.kts, gradle/module-boundaries.gradle.kts, app/build.gradle.kts
docs/session_log.md
```

---

## Task 1: `:qris-core` scaffold + domain models + DTO mapping

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/module-boundaries.gradle.kts`
- Create: `qris-core/build.gradle.kts`
- Create: `qris-core/src/main/kotlin/com/cashup/qris/QrisModels.kt`
- Create: `qris-core/src/main/kotlin/com/cashup/qris/QrisDtos.kt`
- Test: `qris-core/src/test/kotlin/com/cashup/qris/QrisModelsTest.kt`

**Interfaces:**
- Produces (dipakai semua task berikutnya):
  - `enum class QrSupport { DYNAMIC, STATIC, BOTH }` dengan `QrSupport.parse(raw: String?): QrSupport` (tak dikenal → `STATIC`)
  - `data class PayApp(qrisHostId: Int, hostName: String, payAppName: String?, payAppLogoRef: String?, qrSupport: QrSupport, qrExpirySeconds: Int)` dengan `supportsDynamic: Boolean`, `displayName: String`
  - `data class GeneratedQr(transactionId: String, invoiceNumber: String?, baseAmount: BigDecimal, qrisHostId: Int, payAppName: String?, merchantName: String?, qrContent: String, merchantTrxId: String?)`
  - `enum class QrisStatus { INITIATED, AUTHORIZED, SETTLED, EXPIRED, FAILED, UNKNOWN }` dengan `isPaid`, `isFinal`, `QrisStatus.parse(raw: String?)`
  - `data class QrisPushDetail(mid, tid, nmid, rrn, paidAt, qrType, acquirer, issuerNns, issuerName, customerPan, merchantPan, approvalCode, customerName: String? — semua default null)`
  - `data class QrisPaymentResult(transactionId: String, status: QrisStatus, amount: BigDecimal, invoiceNumber, rrn, approvalCode, issuerName, customerName, customerPan, paidAtRaw: String? = null, pushDetail: QrisPushDetail? = null)`
  - internal: `PayAppDto`, `QrCodeDto`, `InquiryDto` (semua field nullable, default null) dengan `toDomain()` yang mengembalikan `null` bila field wajib hilang; `GenerateQrRequest(qrisHostId: Int, baseAmount: BigDecimal, merchantTrxId: String)`; `InquiryRequest(transactionId: String)`.

- [ ] **Step 1: Scaffold modul (belum ada source)**

`settings.gradle.kts` — tambahkan setelah `include(":notification-core")`:

```kotlin
include(":qris-core")
```

`gradle/module-boundaries.gradle.kts` — di `allowedProjectDeps`, tambahkan setelah entri `":cdcp-core"`:

```kotlin
    ":qris-core" to setOf(":common-core", ":signing-core", ":notification-core"),
```

Buat `qris-core/build.gradle.kts` (salinan pola `cdcp-core/build.gradle.kts`, tanpa `device-sdk-api`, plus `notification-core`):

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.qris"
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
    api(project(":signing-core"))
    api(project(":notification-core"))
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

- [ ] **Step 2: Tulis test yang gagal**

`qris-core/src/test/kotlin/com/cashup/qris/QrisModelsTest.kt`:

```kotlin
package com.cashup.qris

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrisModelsTest {
    @Test fun `status parse maps known values and falls back to UNKNOWN`() {
        assertEquals(QrisStatus.INITIATED, QrisStatus.parse("INITIATED"))
        assertEquals(QrisStatus.AUTHORIZED, QrisStatus.parse("authorized"))
        assertEquals(QrisStatus.UNKNOWN, QrisStatus.parse("SOMETHING_NEW"))
        assertEquals(QrisStatus.UNKNOWN, QrisStatus.parse(null))
    }

    @Test fun `paid and final flags follow the api doc status table`() {
        assertTrue(QrisStatus.AUTHORIZED.isPaid)
        assertTrue(QrisStatus.SETTLED.isPaid)
        assertFalse(QrisStatus.EXPIRED.isPaid)
        assertTrue(QrisStatus.AUTHORIZED.isFinal)
        assertTrue(QrisStatus.SETTLED.isFinal)
        assertTrue(QrisStatus.EXPIRED.isFinal)
        assertTrue(QrisStatus.FAILED.isFinal)
        assertFalse(QrisStatus.INITIATED.isFinal)
        assertFalse("unknown status must keep polling", QrisStatus.UNKNOWN.isFinal)
    }

    @Test fun `qrSupport parse treats unknown as STATIC so it is never offered as dynamic`() {
        assertEquals(QrSupport.BOTH, QrSupport.parse("BOTH"))
        assertEquals(QrSupport.STATIC, QrSupport.parse("WHATEVER"))
        assertEquals(QrSupport.STATIC, QrSupport.parse(null))
    }

    @Test fun `pay app dto maps nullable name and logo and derives supportsDynamic`() {
        val app = PayAppDto(qrisHostId = 2, hostName = "CASHLEZ-AVATAR", payAppName = null,
            payAppLogoRef = null, qrSupport = "BOTH", qrExpirySeconds = 900).toDomain()!!
        assertEquals(2, app.qrisHostId)
        assertNull(app.payAppName)
        assertTrue(app.supportsDynamic)
        assertEquals("CASHLEZ-AVATAR", app.displayName)
        assertEquals(900, app.qrExpirySeconds)
        assertFalse(PayAppDto(qrisHostId = 3, qrSupport = "STATIC").toDomain()!!.supportsDynamic)
    }

    @Test fun `pay app dto without host id is dropped`() {
        assertNull(PayAppDto(qrisHostId = null, qrSupport = "DYNAMIC").toDomain())
    }

    @Test fun `qr code dto requires transactionId and qrContent`() {
        val ok = QrCodeDto(transactionId = "tx-1", qrContent = "0002", baseAmount = BigDecimal("15000"),
            qrisHostId = 2, status = "INITIATED").toDomain()!!
        assertEquals("tx-1", ok.transactionId)
        assertEquals(BigDecimal("15000"), ok.baseAmount)
        assertNull(QrCodeDto(transactionId = "tx-1", qrContent = null, baseAmount = BigDecimal.ONE).toDomain())
        assertNull(QrCodeDto(transactionId = null, qrContent = "0002", baseAmount = BigDecimal.ONE).toDomain())
    }

    @Test fun `inquiry dto keeps nulls until paid`() {
        val pending = InquiryDto(transactionId = "tx-1", status = "INITIATED", baseAmount = BigDecimal("15000")).toDomain()!!
        assertEquals(QrisStatus.INITIATED, pending.status)
        assertNull(pending.rrn)
        val paid = InquiryDto(transactionId = "tx-1", status = "AUTHORIZED", baseAmount = BigDecimal("15000"),
            rrn = "123", approvalCode = "885790", paidAt = "2026-09-24T03:16:10Z", issuerName = "DANA").toDomain()!!
        assertTrue(paid.status.isPaid)
        assertEquals("123", paid.rrn)
        assertEquals("2026-09-24T03:16:10Z", paid.paidAtRaw)
        assertNull(InquiryDto(transactionId = null, status = "AUTHORIZED").toDomain())
    }
}
```

- [ ] **Step 3: Jalankan test, pastikan gagal**

Run: `./gradlew :qris-core:testDebugUnitTest --tests "com.cashup.qris.QrisModelsTest"`
Expected: FAIL kompilasi — `Unresolved reference: QrisStatus` / `PayAppDto`.

Jika Gradle mengeluh soal JVM target `:notification-core` (toolchain 17) dari modul Android (target 1.8): cocokkan cara `:common-core` mengatasinya (`common-core/build.gradle.kts`) dan terapkan hal yang sama; jangan ubah `:notification-core`.

- [ ] **Step 4: Implementasi minimal**

`qris-core/src/main/kotlin/com/cashup/qris/QrisModels.kt`:

```kotlin
package com.cashup.qris

import java.math.BigDecimal

enum class QrSupport {
    DYNAMIC, STATIC, BOTH;

    companion object {
        /** Nilai tak dikenal jatuh ke STATIC: tidak pernah ditawarkan sebagai dynamic. */
        fun parse(raw: String?): QrSupport = values().firstOrNull { it.name == raw?.uppercase() } ?: STATIC
    }
}

data class PayApp(
    val qrisHostId: Int,
    val hostName: String,
    val payAppName: String?,
    val payAppLogoRef: String?,
    val qrSupport: QrSupport,
    val qrExpirySeconds: Int,
) {
    val supportsDynamic: Boolean get() = qrSupport == QrSupport.DYNAMIC || qrSupport == QrSupport.BOTH
    val displayName: String get() = payAppName ?: hostName.ifBlank { "QRIS" }
}

data class GeneratedQr(
    val transactionId: String,
    val invoiceNumber: String?,
    val baseAmount: BigDecimal,
    val qrisHostId: Int,
    val payAppName: String?,
    val merchantName: String?,
    val qrContent: String,
    val merchantTrxId: String?,
)

enum class QrisStatus {
    INITIATED, AUTHORIZED, SETTLED, EXPIRED, FAILED, UNKNOWN;

    val isPaid: Boolean get() = this == AUTHORIZED || this == SETTLED

    /** UNKNOWN sengaja tidak final: status baru dari backend tidak boleh menghentikan polling. */
    val isFinal: Boolean get() = this != INITIATED && this != UNKNOWN

    companion object {
        fun parse(raw: String?): QrisStatus = values().firstOrNull { it.name == raw?.uppercase() } ?: UNKNOWN
    }
}

/** `data.detail` dari push MQTT. Semua opsional; `null` = tidak tersedia (sembunyikan barisnya). */
data class QrisPushDetail(
    val mid: String? = null,
    val tid: String? = null,
    val nmid: String? = null,
    val rrn: String? = null,
    val paidAt: String? = null,
    val qrType: String? = null,
    val acquirer: String? = null,
    val issuerNns: String? = null,
    val issuerName: String? = null,
    val customerPan: String? = null,
    val merchantPan: String? = null,
    val approvalCode: String? = null,
    val customerName: String? = null,
)

data class QrisPaymentResult(
    val transactionId: String,
    val status: QrisStatus,
    val amount: BigDecimal,
    val invoiceNumber: String? = null,
    val rrn: String? = null,
    val approvalCode: String? = null,
    val issuerName: String? = null,
    val customerName: String? = null,
    val customerPan: String? = null,
    /** String mentah dari server; tidak di-parse (API 23 tanpa java.time). */
    val paidAtRaw: String? = null,
    val pushDetail: QrisPushDetail? = null,
)
```

`qris-core/src/main/kotlin/com/cashup/qris/QrisDtos.kt`:

```kotlin
package com.cashup.qris

import java.math.BigDecimal

/** Dipakai bila backend tidak mengirim `qrExpirySeconds` (nilai contoh di dokumen API). */
private const val FALLBACK_EXPIRY_SECONDS = 900

// Gson mengisi field yang hilang dengan null walau tipenya non-null; karena itu semua field DTO
// dibuat nullable dan pemetaan ke domain menolak (mengembalikan null) bila field wajib hilang.

internal data class PayAppDto(
    val qrisHostId: Int? = null,
    val hostName: String? = null,
    val payAppName: String? = null,
    val payAppLogoRef: String? = null,
    val qrSupport: String? = null,
    val qrExpirySeconds: Int? = null,
) {
    fun toDomain(): PayApp? {
        val id = qrisHostId ?: return null
        return PayApp(id, hostName.orEmpty(), payAppName, payAppLogoRef,
            QrSupport.parse(qrSupport), qrExpirySeconds ?: FALLBACK_EXPIRY_SECONDS)
    }
}

internal data class GenerateQrRequest(
    val qrisHostId: Int,
    val baseAmount: BigDecimal,
    val merchantTrxId: String,
)

internal data class InquiryRequest(val transactionId: String)

internal data class QrCodeDto(
    val transactionId: String? = null,
    val invoiceNumber: String? = null,
    val status: String? = null,
    val baseAmount: BigDecimal? = null,
    val qrisHostId: Int? = null,
    val payAppName: String? = null,
    val merchantName: String? = null,
    val qrContent: String? = null,
    val merchantTrxId: String? = null,
) {
    fun toDomain(): GeneratedQr? {
        val tx = transactionId?.takeIf { it.isNotBlank() } ?: return null
        val content = qrContent?.takeIf { it.isNotBlank() } ?: return null
        return GeneratedQr(tx, invoiceNumber, baseAmount ?: BigDecimal.ZERO, qrisHostId ?: 0,
            payAppName, merchantName, content, merchantTrxId)
    }
}

internal data class InquiryDto(
    val transactionId: String? = null,
    val status: String? = null,
    val invoiceNumber: String? = null,
    val baseAmount: BigDecimal? = null,
    val rrn: String? = null,
    val approvalCode: String? = null,
    val paidAt: String? = null,
    val issuerName: String? = null,
    val customerPan: String? = null,
    val customerName: String? = null,
) {
    fun toDomain(): QrisPaymentResult? {
        val tx = transactionId?.takeIf { it.isNotBlank() } ?: return null
        return QrisPaymentResult(tx, QrisStatus.parse(status), baseAmount ?: BigDecimal.ZERO,
            invoiceNumber, rrn, approvalCode, issuerName, customerName, customerPan, paidAtRaw = paidAt)
    }
}
```

- [ ] **Step 5: Jalankan test, pastikan lulus**

Run: `./gradlew :qris-core:testDebugUnitTest --tests "com.cashup.qris.QrisModelsTest"`
Expected: PASS (7 tests).

- [ ] **Step 6: Boundaries + commit**

Run: `./gradlew checkModuleBoundaries`
Expected: BUILD SUCCESSFUL.

```bash
git add docs/superpowers/specs/2026-09-29-qris-dynamic-design.md docs/superpowers/plans/2026-09-29-qris-dynamic.md settings.gradle.kts gradle/module-boundaries.gradle.kts qris-core
git commit -m "feat(qris-core): scaffold module with domain models and DTO mapping" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 2: `QrisGateway` + `QrisRepository` (HTTP, signing, idempotency)

**Files:**
- Create: `qris-core/src/main/kotlin/com/cashup/qris/QrisGateway.kt`
- Create: `qris-core/src/main/kotlin/com/cashup/qris/QrisRepository.kt`
- Test: `qris-core/src/test/kotlin/com/cashup/qris/QrisRepositoryTest.kt`

**Interfaces:**
- Consumes (Task 1): `PayApp`, `GeneratedQr`, `QrisPaymentResult`, `PayAppDto`, `QrCodeDto`, `InquiryDto`, `GenerateQrRequest`, `InquiryRequest`. Dari repo: `ApiEnvelope`, `ApiResult`, `ApiError`, `safeEnvelopeCall`, `RequestHeadersInterceptor`, `SigningInterceptor`, `DeviceSigner`.
- Produces:
  - `interface QrisGateway { suspend fun payApps(): ApiResult<List<PayApp>>; suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr>; suspend fun inquiry(transactionId: String): ApiResult<QrisPaymentResult> }`
  - `class QrisRepository : QrisGateway` dengan `companion fun create(baseUrl: String, signer: DeviceSigner, debugLogging: Boolean = false): QrisRepository`.

- [ ] **Step 1: Tulis test yang gagal**

`qris-core/src/test/kotlin/com/cashup/qris/QrisRepositoryTest.kt`:

```kotlin
package com.cashup.qris

import com.cashup.common.network.ApiResult
import com.cashup.signing.DeviceSigner
import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrisRepositoryTest {
    private class CapturingSigner : DeviceSigner {
        var canonical: String? = null
        override fun deviceId() = "dev-1"
        override fun sign(canonicalBytes: ByteArray): ByteArray {
            canonical = String(canonicalBytes, Charsets.UTF_8)
            return ByteArray(64) { 7 }
        }
    }

    private fun sha256Hex(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private inline fun withServer(block: (MockWebServer, QrisRepository, CapturingSigner) -> Unit) {
        val server = MockWebServer().apply { start() }
        try {
            val signer = CapturingSigner()
            block(server, QrisRepository.create(server.url("/").toString(), signer), signer)
        } finally { server.shutdown() }
    }

    private val qrBody = """{"data":{"transactionId":"tx-1","invoiceNumber":"000001","status":"INITIATED",
        "baseAmount":15000,"currency":"360","qrisHostId":2,"payAppName":"DANA","merchantName":"Toko",
        "qrContent":"00020101","merchantTrxId":"APP-attempt-1"},"meta":{"correlationId":"c"}}""".trimIndent()

    @Test fun `payApps is a signed GET without idempotency key and tolerates null names`() = runBlocking {
        withServer { server, repo, signer ->
            server.enqueue(MockResponse().setBody("""{"data":[
                {"qrisHostId":2,"hostName":"CASHLEZ-AVATAR","payAppName":"DANA","payAppLogoRef":null,
                 "qrSupport":"BOTH","qrExpirySeconds":900,"supportsCancel":false,"supportsRefund":false},
                {"qrisHostId":3,"hostName":"OTHER","payAppName":null,"qrSupport":"STATIC","qrExpirySeconds":600}]}"""))
            val result = repo.payApps()
            val apps = (result as ApiResult.Success).data
            assertEquals(2, apps.size)
            assertEquals("DANA", apps[0].payAppName)
            assertNull(apps[1].payAppName)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/qris/pay-apps", request.path)
            assertEquals("dev-1", request.getHeader("X-Device-Id"))
            assertNotNull(request.getHeader("X-Signature"))
            assertNotNull(request.getHeader("X-Timestamp"))
            assertNotNull(request.getHeader("X-Nonce"))
            assertNull(request.getHeader("Idempotency-Key"))
            // GET meng-hash string kosong (dokumen API).
            val lines = signer.canonical!!.split("\n")
            assertEquals(listOf("GET", "/v1/qris/pay-apps", "dev-1"), lines.take(3))
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", lines[5])
        }
    }

    @Test fun `generate posts signed body with attemptId as idempotency key`() = runBlocking {
        withServer { server, repo, signer ->
            server.enqueue(MockResponse().setBody(qrBody))
            val result = repo.generate(2, BigDecimal("15000"), "attempt-1")
            val qr = (result as ApiResult.Success).data
            assertEquals("tx-1", qr.transactionId)
            assertEquals("00020101", qr.qrContent)
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertEquals("POST", request.method)
            assertEquals("/v1/qris/qr-codes", request.path)
            assertEquals("attempt-1", request.getHeader("Idempotency-Key"))
            assertEquals("""{"qrisHostId":2,"baseAmount":15000,"merchantTrxId":"APP-attempt-1"}""", body)
            // Canonical string: METHOD, path tanpa query, deviceId, timestamp header, nonce header, sha256(body).
            assertEquals(
                listOf("POST", "/v1/qris/qr-codes", "dev-1", request.getHeader("X-Timestamp"),
                    request.getHeader("X-Nonce"), sha256Hex(body)),
                signer.canonical!!.split("\n"),
            )
        }
    }

    @Test fun `generate retry with same attemptId resends identical key and body`() = runBlocking {
        withServer { server, repo, _ ->
            server.enqueue(MockResponse().setResponseCode(503)
                .setBody("""{"error":{"code":"ACQUIRER_UNAVAILABLE","message":"down"}}"""))
            server.enqueue(MockResponse().setBody(qrBody))
            val first = repo.generate(2, BigDecimal("15000"), "attempt-1")
            val second = repo.generate(2, BigDecimal("15000"), "attempt-1")
            assertEquals("ACQUIRER_UNAVAILABLE", (first as ApiResult.Failure).error.code)
            assertTrue(second is ApiResult.Success)
            val r1 = server.takeRequest()
            val r2 = server.takeRequest()
            assertEquals(r1.getHeader("Idempotency-Key"), r2.getHeader("Idempotency-Key"))
            assertEquals(r1.body.readUtf8(), r2.body.readUtf8())
        }
    }

    @Test fun `generate with a new attemptId sends a new key`() = runBlocking {
        withServer { server, repo, _ ->
            server.enqueue(MockResponse().setBody(qrBody))
            server.enqueue(MockResponse().setBody(qrBody))
            repo.generate(2, BigDecimal("15000"), "attempt-1")
            repo.generate(2, BigDecimal("15000"), "attempt-2")
            assertEquals("attempt-1", server.takeRequest().getHeader("Idempotency-Key"))
            assertEquals("attempt-2", server.takeRequest().getHeader("Idempotency-Key"))
        }
    }

    @Test fun `inquiry uses a fresh idempotency key on every call`() = runBlocking {
        withServer { server, repo, _ ->
            server.enqueue(MockResponse().setBody("""{"data":{"transactionId":"tx-1","status":"INITIATED","baseAmount":15000}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"transactionId":"tx-1","status":"AUTHORIZED","baseAmount":15000,
                "rrn":"1","approvalCode":"2","paidAt":"2026-09-24T03:16:10Z","issuerName":null}}"""))
            val first = (repo.inquiry("tx-1") as ApiResult.Success).data
            val second = (repo.inquiry("tx-1") as ApiResult.Success).data
            assertEquals(QrisStatus.INITIATED, first.status)
            assertNull(first.rrn)
            assertTrue(second.status.isPaid)
            assertEquals("1", second.rrn)
            val r1 = server.takeRequest()
            val r2 = server.takeRequest()
            assertEquals("/v1/qris/inquiries", r1.path)
            assertEquals("""{"transactionId":"tx-1"}""", r1.body.readUtf8())
            assertNotNull(r1.getHeader("Idempotency-Key"))
            assertNotEquals(r1.getHeader("Idempotency-Key"), r2.getHeader("Idempotency-Key"))
        }
    }

    @Test fun `backend error code is surfaced from a 422 envelope`() = runBlocking {
        withServer { server, repo, _ ->
            server.enqueue(MockResponse().setResponseCode(422)
                .setBody("""{"error":{"code":"AMOUNT_OUT_OF_RANGE","message":"x","details":{}},"meta":{}}"""))
            val result = repo.generate(2, BigDecimal("1"), "attempt-1")
            assertEquals("AMOUNT_OUT_OF_RANGE", (result as ApiResult.Failure).error.code)
            assertEquals(422, result.error.httpStatus)
        }
    }

    @Test fun `incomplete qr-codes response becomes RESPONSE_UNEXPECTED`() = runBlocking {
        withServer { server, repo, _ ->
            server.enqueue(MockResponse().setBody("""{"data":{"transactionId":"tx-1","status":"INITIATED"}}"""))
            val result = repo.generate(2, BigDecimal("15000"), "attempt-1")
            assertEquals("RESPONSE_UNEXPECTED", (result as ApiResult.Failure).error.code)
        }
    }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `./gradlew :qris-core:testDebugUnitTest --tests "com.cashup.qris.QrisRepositoryTest"`
Expected: FAIL kompilasi — `Unresolved reference: QrisRepository`.

- [ ] **Step 3: Implementasi**

`qris-core/src/main/kotlin/com/cashup/qris/QrisGateway.kt`:

```kotlin
package com.cashup.qris

import com.cashup.common.network.ApiResult
import java.math.BigDecimal

/** Seam HTTP QRIS: dipakai [QrisPaymentTracker] dan `:app`, dan mudah di-fake di test. */
interface QrisGateway {
    suspend fun payApps(): ApiResult<List<PayApp>>

    /** [attemptId] = satu aksi pengguna; dipakai sebagai `Idempotency-Key`. */
    suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr>

    /** Selalu memakai `Idempotency-Key` baru. */
    suspend fun inquiry(transactionId: String): ApiResult<QrisPaymentResult>
}
```

`qris-core/src/main/kotlin/com/cashup/qris/QrisRepository.kt`:

```kotlin
package com.cashup.qris

import com.cashup.common.network.ApiEnvelope
import com.cashup.common.network.ApiError
import com.cashup.common.network.ApiResult
import com.cashup.common.network.RequestHeadersInterceptor
import com.cashup.common.network.safeEnvelopeCall
import com.cashup.signing.DeviceSigner
import com.cashup.signing.SigningInterceptor
import com.google.gson.Gson
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

internal interface QrisApi {
    @GET("v1/qris/pay-apps")
    suspend fun payApps(): Response<ApiEnvelope<List<PayAppDto>>>

    @POST("v1/qris/qr-codes")
    suspend fun generate(@Header("Idempotency-Key") idempotencyKey: String,
                         @Body body: GenerateQrRequest): Response<ApiEnvelope<QrCodeDto>>

    @POST("v1/qris/inquiries")
    suspend fun inquiry(@Header("Idempotency-Key") idempotencyKey: String,
                        @Body body: InquiryRequest): Response<ApiEnvelope<InquiryDto>>
}

class QrisRepository private constructor(
    private val api: QrisApi,
    private val gson: Gson,
) : QrisGateway {
    private var pendingGenerate: Pair<String, GenerateQrRequest>? = null

    override suspend fun payApps(): ApiResult<List<PayApp>> =
        safeEnvelopeCall(gson) { api.payApps() }.convert { list -> list.mapNotNull { it.toDomain() } }

    override suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr> {
        require(attemptId.isNotBlank()) { "attemptId wajib per aksi pembayaran" }
        // Request dibekukan per attemptId supaya retry mengirim key + body yang persis sama.
        val request = pendingGenerate?.takeIf { it.first == attemptId }?.second
            ?: GenerateQrRequest(qrisHostId, amount, "APP-$attemptId").also { pendingGenerate = attemptId to it }
        val result = safeEnvelopeCall(gson) { api.generate(attemptId, request) }.convert { it.toDomain() }
        if (result is ApiResult.Success) pendingGenerate = null
        return result
    }

    override suspend fun inquiry(transactionId: String): ApiResult<QrisPaymentResult> =
        safeEnvelopeCall(gson) { api.inquiry(UUID.randomUUID().toString(), InquiryRequest(transactionId)) }
            .convert { it.toDomain() }

    private inline fun <D, R : Any> ApiResult<D>.convert(transform: (D) -> R?): ApiResult<R> = when (this) {
        is ApiResult.Failure -> this
        is ApiResult.Success -> transform(data)?.let { ApiResult.Success(it) }
            ?: ApiResult.Failure(ApiError("RESPONSE_UNEXPECTED", "Respons QRIS tidak lengkap"))
    }

    companion object {
        fun create(baseUrl: String, signer: DeviceSigner, debugLogging: Boolean = false): QrisRepository {
            val gson = Gson()
            val clientBuilder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(RequestHeadersInterceptor())
                .addInterceptor(SigningInterceptor(signer))
            if (debugLogging) {
                clientBuilder.addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                    redactHeader("X-Signature")
                })
            }
            val api = Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
                .client(clientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build().create(QrisApi::class.java)
            return QrisRepository(api, gson)
        }
    }
}
```

- [ ] **Step 4: Jalankan test, pastikan lulus**

Run: `./gradlew :qris-core:testDebugUnitTest --tests "com.cashup.qris.QrisRepositoryTest"`
Expected: PASS (7 tests). Jika `merchantTrxId` / urutan field di body berbeda dari asersi, jangan ubah asersi menjadi longgar — periksa urutan deklarasi field di `GenerateQrRequest` (Gson memakai urutan deklarasi).

- [ ] **Step 5: Commit**

```bash
git add qris-core
git commit -m "feat(qris-core): add QrisRepository with signed pay-apps/qr-codes/inquiries" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 3: `PushParser` + `QrisPaymentTracker`

**Files:**
- Create: `qris-core/src/main/kotlin/com/cashup/qris/PushParser.kt`
- Create: `qris-core/src/main/kotlin/com/cashup/qris/QrisPaymentTracker.kt`
- Test: `qris-core/src/test/kotlin/com/cashup/qris/QrisPaymentTrackerTest.kt`

**Interfaces:**
- Consumes (Task 1–2): `QrisGateway`, `GeneratedQr`, `QrisPaymentResult`, `QrisStatus`, `QrisPushDetail`. Dari `:notification-core`: `PaymentStatusWaiter(transport)` dengan `suspend fun <T> await(timeoutMs, pollIntervalMs, matches: (RawNotification) -> T?, statusCheck: suspend () -> T?): T`, `PaymentNotificationTransport`, `RawNotification(topic, payload)`.
- Produces: `class QrisPaymentTracker(gateway: QrisGateway, waiter: PaymentStatusWaiter, transport: PaymentNotificationTransport)` dengan `suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult` — tidak melempar selain `CancellationException`.

- [ ] **Step 1: Tulis test yang gagal**

`qris-core/src/test/kotlin/com/cashup/qris/QrisPaymentTrackerTest.kt`:

```kotlin
package com.cashup.qris

import com.cashup.common.network.ApiError
import com.cashup.common.network.ApiResult
import com.cashup.notification.PaymentNotificationTransport
import com.cashup.notification.PaymentStatusWaiter
import com.cashup.notification.RawNotification
import java.math.BigDecimal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTransport(connected: Boolean) : PaymentNotificationTransport {
    override var isConnected: Boolean = connected
    var disconnectCalls = 0
    private val flow = MutableSharedFlow<RawNotification>(extraBufferCapacity = 16)
    override fun connect(deviceId: String) { isConnected = true }
    override val messages: Flow<RawNotification> = flow
    override fun disconnect() { disconnectCalls++ }
    fun emit(notification: RawNotification) { flow.tryEmit(notification) }
}

private class FakeGateway(private val now: () -> Long) : QrisGateway {
    val inquiryAt = mutableListOf<Long>()
    var script: (Int) -> ApiResult<QrisPaymentResult> = { initiated() }
    override suspend fun payApps(): ApiResult<List<PayApp>> = error("unused")
    override suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr> = error("unused")
    override suspend fun inquiry(transactionId: String): ApiResult<QrisPaymentResult> {
        inquiryAt += now()
        return script(inquiryAt.size)
    }
}

private fun result(status: QrisStatus) = ApiResult.Success(QrisPaymentResult("tx-1", status, BigDecimal("15000")))
private fun initiated() = result(QrisStatus.INITIATED)
private fun paid() = result(QrisStatus.AUTHORIZED)
private fun qr() = GeneratedQr("tx-1", "000001", BigDecimal("15000"), 2, "DANA", "Toko", "0002", "APP-a")

private fun push(
    tx: String, type: String = "qris.payment_paid", v: Int = 1, module: String = "qris", detail: String? = null,
) = RawNotification(
    "cashup/staging/v1/devices/d",
    """{"v":$v,"id":"m-1","type":"$type","module":"$module","data":{"amount":"15000.00","currency":"IDR",""" +
        """"reference":"1","invoiceNumber":"000001","transactionId":"$tx"${detail?.let { ",\"detail\":$it" } ?: ""}}}""",
)

@OptIn(ExperimentalCoroutinesApi::class)
class QrisPaymentTrackerTest {
    private fun TestScope.tracker(transport: FakeTransport, gateway: FakeGateway) =
        QrisPaymentTracker(gateway, PaymentStatusWaiter(transport), transport)

    @Test fun `matching push triggers exactly one confirming inquiry and carries push detail`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { paid() } }
        val deferred = async { tracker(transport, gateway).track(qr(), 600) }
        runCurrent()
        transport.emit(push("tx-1", detail = """{"mid":"M1","rrn":"R1","issuerName":null}"""))
        val outcome = deferred.await()
        assertEquals(QrisStatus.AUTHORIZED, outcome.status)
        assertEquals("M1", outcome.pushDetail?.mid)
        assertNull(outcome.pushDetail?.issuerName)
        assertEquals(1, gateway.inquiryAt.size)
        assertEquals("tracker must never disconnect the transport", 0, transport.disconnectCalls)
    }

    @Test fun `push without detail key still confirms`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { paid() } }
        val deferred = async { tracker(transport, gateway).track(qr(), 600) }
        runCurrent()
        transport.emit(push("tx-1"))
        val outcome = deferred.await()
        assertTrue(outcome.status.isPaid)
        assertNull(outcome.pushDetail)
    }

    @Test fun `push while inquiry still INITIATED keeps polling every 3s until final`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { n -> if (n < 2) initiated() else paid() } }
        val deferred = async { tracker(transport, gateway).track(qr(), 600) }
        runCurrent()
        transport.emit(push("tx-1", detail = """{"mid":"M1"}"""))
        val outcome = deferred.await()
        assertTrue(outcome.status.isPaid)
        assertEquals("M1", outcome.pushDetail?.mid)
        assertEquals(listOf(0L, 3_000L), gateway.inquiryAt)
    }

    @Test fun `unrelated or malformed pushes are ignored and do not trigger inquiry`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { paid() } }
        val deferred = async { tracker(transport, gateway).track(qr(), 600) }
        runCurrent()
        transport.emit(push("other-tx"))
        transport.emit(push("tx-1", type = "qris.something_else"))
        transport.emit(push("tx-1", v = 2))
        transport.emit(push("tx-1", module = "cdcp"))
        transport.emit(RawNotification("t", "not json at all"))
        transport.emit(RawNotification("t", """{"v":1,"type":"qris.payment_paid","module":"qris"}"""))
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue("no inquiry before the 5s push window ends", gateway.inquiryAt.isEmpty())
        assertFalse(deferred.isCompleted)
        transport.emit(push("tx-1"))
        assertTrue(deferred.await().status.isPaid)
    }

    @Test fun `same push delivered twice results in a single inquiry`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { paid() } }
        val deferred = async { tracker(transport, gateway).track(qr(), 600) }
        runCurrent()
        transport.emit(push("tx-1"))
        transport.emit(push("tx-1"))
        deferred.await()
        runCurrent()
        assertEquals(1, gateway.inquiryAt.size)
    }

    @Test fun `without push polling starts at 5s and repeats every 10s while mqtt is connected`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { n -> if (n < 3) initiated() else paid() } }
        val outcome = tracker(transport, gateway).track(qr(), 600)
        assertTrue(outcome.status.isPaid)
        assertEquals(listOf(5_000L, 15_000L, 25_000L), gateway.inquiryAt)
    }

    @Test fun `without push polling repeats every 3s when mqtt is not connected`() = runTest {
        val transport = FakeTransport(connected = false)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { n -> if (n < 3) initiated() else paid() } }
        val outcome = tracker(transport, gateway).track(qr(), 600)
        assertTrue(outcome.status.isPaid)
        assertEquals(listOf(5_000L, 8_000L, 11_000L), gateway.inquiryAt)
    }

    @Test fun `failed inquiry during polling is ignored and retried`() = runTest {
        val transport = FakeTransport(connected = false)
        val gateway = FakeGateway { testScheduler.currentTime }.apply {
            script = { n -> if (n == 1) ApiResult.Failure(ApiError("NETWORK", "down")) else paid() }
        }
        val outcome = tracker(transport, gateway).track(qr(), 600)
        assertTrue(outcome.status.isPaid)
        assertEquals(listOf(5_000L, 8_000L), gateway.inquiryAt)
    }

    @Test fun `past the deadline one last inquiry decides then falls back to EXPIRED`() = runTest {
        val transport = FakeTransport(connected = false)
        val gateway = FakeGateway { testScheduler.currentTime }
        val outcome = tracker(transport, gateway).track(qr(), 10) // deadline = 10s + 5s grace = 15s
        assertEquals(QrisStatus.EXPIRED, outcome.status)
        assertEquals("tx-1", outcome.transactionId)
        assertEquals(BigDecimal("15000"), outcome.amount)
        assertEquals(listOf(5_000L, 8_000L, 11_000L, 14_000L, 15_000L), gateway.inquiryAt)
    }

    @Test fun `last inquiry at the deadline can still report a payment`() = runTest {
        val transport = FakeTransport(connected = false)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { n -> if (n < 5) initiated() else paid() } }
        val outcome = tracker(transport, gateway).track(qr(), 10)
        assertEquals(QrisStatus.AUTHORIZED, outcome.status)
        assertEquals(15_000L, gateway.inquiryAt.last())
    }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `./gradlew :qris-core:testDebugUnitTest --tests "com.cashup.qris.QrisPaymentTrackerTest"`
Expected: FAIL kompilasi — `Unresolved reference: QrisPaymentTracker`.

- [ ] **Step 3: Implementasi**

`qris-core/src/main/kotlin/com/cashup/qris/PushParser.kt`:

```kotlin
package com.cashup.qris

import com.google.gson.Gson
import com.google.gson.JsonParseException

internal class ParsedPush(
    val version: Int?,
    val type: String?,
    val module: String?,
    val transactionId: String?,
    val detail: QrisPushDetail?,
)

private class PushEnvelope(
    val v: Int? = null,
    val type: String? = null,
    val module: String? = null,
    val data: PushData? = null,
)

private class PushData(
    val transactionId: String? = null,
    val detail: QrisPushDetail? = null,
)

/** Payload rusak / bukan objek JSON -> `null` (dilewati), tidak pernah melempar. */
internal class PushParser(private val gson: Gson = Gson()) {
    fun parse(payload: String): ParsedPush? {
        val envelope = try {
            gson.fromJson(payload, PushEnvelope::class.java)
        } catch (e: JsonParseException) {
            null
        } ?: return null
        return ParsedPush(envelope.v, envelope.type, envelope.module, envelope.data?.transactionId, envelope.data?.detail)
    }
}
```

`qris-core/src/main/kotlin/com/cashup/qris/QrisPaymentTracker.kt`:

```kotlin
package com.cashup.qris

import com.cashup.common.network.ApiResult
import com.cashup.notification.PaymentNotificationTransport
import com.cashup.notification.PaymentStatusWaiter
import com.cashup.notification.RawNotification
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Menunggu status final sebuah QR dynamic: push MQTT sebagai petunjuk cepat, `inquiries` sebagai
 * sumber kebenaran (dokumen API §3/§5). Tidak pernah memanggil `transport.disconnect()`.
 *
 * Dedupe push by `id` (QoS 1 bisa ganda) tidak butuh state di sini: `PaymentStatusWaiter` berhenti
 * mendengarkan pada push cocok pertama, jadi duplikatnya tidak pernah diproses.
 */
class QrisPaymentTracker(
    private val gateway: QrisGateway,
    private val waiter: PaymentStatusWaiter,
    private val transport: PaymentNotificationTransport,
) {
    private val parser = PushParser()

    private sealed class Signal {
        class Push(val detail: QrisPushDetail?) : Signal()
        class Polled(val result: QrisPaymentResult) : Signal()
    }

    /** Suspend sampai hasil final atau QR kedaluwarsa. */
    suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult {
        val settled = withTimeoutOrNull(expirySeconds * 1_000L + EXPIRY_GRACE_MS) { waitForFinal(qr) }
        if (settled != null) return settled
        return finalOrNull(qr) ?: expired(qr)
    }

    private suspend fun waitForFinal(qr: GeneratedQr): QrisPaymentResult {
        val signal = waiter.await<Signal>(
            timeoutMs = PUSH_WAIT_MS,
            pollIntervalMs = if (transport.isConnected) POLL_WITH_PUSH_MS else POLL_WITHOUT_PUSH_MS,
            matches = { raw -> paidPushFor(qr, raw) },
            statusCheck = { finalOrNull(qr)?.let { Signal.Polled(it) } },
        )
        return when (signal) {
            is Signal.Polled -> signal.result
            is Signal.Push -> (finalOrNull(qr) ?: pollUntilFinal(qr)).copy(pushDetail = signal.detail)
        }
    }

    private fun paidPushFor(qr: GeneratedQr, raw: RawNotification): Signal.Push? {
        val push = parser.parse(raw.payload) ?: return null
        if (push.version != KNOWN_VERSION || push.type != PAID_TYPE || push.module != MODULE) return null
        if (push.transactionId != qr.transactionId) return null
        return Signal.Push(push.detail)
    }

    private suspend fun finalOrNull(qr: GeneratedQr): QrisPaymentResult? =
        (gateway.inquiry(qr.transactionId) as? ApiResult.Success)?.data?.takeIf { it.status.isFinal }

    private suspend fun pollUntilFinal(qr: GeneratedQr): QrisPaymentResult {
        while (true) {
            delay(POLL_WITHOUT_PUSH_MS)
            finalOrNull(qr)?.let { return it }
        }
    }

    private fun expired(qr: GeneratedQr) = QrisPaymentResult(
        transactionId = qr.transactionId,
        status = QrisStatus.EXPIRED,
        amount = qr.baseAmount,
        invoiceNumber = qr.invoiceNumber,
    )

    private companion object {
        const val PUSH_WAIT_MS = 5_000L
        const val POLL_WITH_PUSH_MS = 10_000L
        const val POLL_WITHOUT_PUSH_MS = 3_000L
        const val EXPIRY_GRACE_MS = 5_000L
        const val KNOWN_VERSION = 1
        const val PAID_TYPE = "qris.payment_paid"
        const val MODULE = "qris"
    }
}
```

- [ ] **Step 4: Jalankan test, pastikan lulus**

Run: `./gradlew :qris-core:testDebugUnitTest`
Expected: PASS — seluruh test `:qris-core` (Models 7 + Repository 7 + Tracker 10 = 24). Bila satu test timing gagal, periksa dulu apakah `runCurrent()` dipanggil sebelum `emit` (flow tanpa replay: pesan yang dikirim sebelum kolektor subscribe hilang) sebelum menyentuh implementasi.

- [ ] **Step 5: Commit**

```bash
git add qris-core
git commit -m "feat(qris-core): add QrisPaymentTracker (push hint + inquiry confirmation + expiry)" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 4: `:feature-qris` scaffold + `QrisDependencies` + error messages + QR encoder

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/module-boundaries.gradle.kts`
- Create: `feature-qris/build.gradle.kts`
- Create: `feature-qris/src/main/kotlin/com/cashup/feature/qris/QrisDependencies.kt`
- Create: `feature-qris/src/main/kotlin/com/cashup/feature/qris/QrisErrorMessages.kt`
- Create: `feature-qris/src/main/kotlin/com/cashup/feature/qris/QrCodeEncoder.kt`
- Create: `feature-qris/src/main/res/values/strings.xml`
- Create: `feature-qris/src/main/res/values/colors.xml`
- Test: `feature-qris/src/test/kotlin/com/cashup/feature/qris/QrCodeEncoderTest.kt`, `QrisErrorMessagesTest.kt`

**Interfaces:**
- Consumes (Task 1): `PayApp`, `GeneratedQr`, `QrisPaymentResult`; `ApiResult`.
- Produces:
  - `interface QrisDependencies { fun isTransactionAllowed(): Boolean = true; suspend fun ensureNotificationsConnected(); suspend fun payApps(): ApiResult<List<PayApp>>; suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr>; suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult }`
  - `interface QrisDependenciesOwner { val qrisDependencies: QrisDependencies }`
  - `object QrisErrorMessages { @StringRes fun forCode(code: String?): Int }`
  - `object QrCodeEncoder { fun encode(content: String, size: Int): com.google.zxing.common.BitMatrix }`
  - String resources `qris_*` (daftar lengkap di Step 3; dipakai Task 5–6).

- [ ] **Step 1: Scaffold modul**

`settings.gradle.kts` — tambahkan setelah `include(":qris-core")`:

```kotlin
include(":feature-qris")
```

`gradle/module-boundaries.gradle.kts` — tambahkan setelah entri `":qris-core"`:

```kotlin
    ":feature-qris" to setOf(":common-core", ":qris-core"),
```

`feature-qris/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.feature.qris"
    compileSdk = (project.property("cashup.compileSdk") as String).toInt()
    defaultConfig { minSdk = (project.property("cashup.minSdk") as String).toInt() }
    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
}

dependencies {
    implementation(project(":common-core"))
    implementation(project(":qris-core"))

    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.5.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

- [ ] **Step 2: Tulis test yang gagal**

`feature-qris/src/test/kotlin/com/cashup/feature/qris/QrCodeEncoderTest.kt`:

```kotlin
package com.cashup.feature.qris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeEncoderTest {
    @Test fun `encode returns a square matrix of the requested size with dark modules`() {
        val matrix = QrCodeEncoder.encode("00020101021226690014ID.CO.QRIS.WWW0215ID10200000000006304ABCD", 256)
        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)
        var dark = 0
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix.get(x, y)) dark++
        assertTrue("QR must contain dark modules", dark > 0)
    }

    @Test fun `top-left finder pattern corner is dark`() {
        val matrix = QrCodeEncoder.encode("hello", 200)
        // Margin 1 modul -> ada modul gelap di pojok finder pattern, bukan di piksel (0,0) yang putih.
        assertTrue(!matrix.get(0, 0))
        var found = false
        for (i in 0 until 40) if (matrix.get(i, i)) found = true
        assertTrue(found)
    }
}
```

`feature-qris/src/test/kotlin/com/cashup/feature/qris/QrisErrorMessagesTest.kt`:

```kotlin
package com.cashup.feature.qris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QrisErrorMessagesTest {
    @Test fun `known backend codes map to specific messages`() {
        assertEquals(R.string.qris_error_network, QrisErrorMessages.forCode("NETWORK"))
        assertEquals(R.string.qris_error_amount, QrisErrorMessages.forCode("AMOUNT_OUT_OF_RANGE"))
        assertEquals(R.string.qris_error_acquirer_down, QrisErrorMessages.forCode("ACQUIRER_UNAVAILABLE"))
        assertEquals(R.string.qris_error_rejected, QrisErrorMessages.forCode("ACQUIRER_REJECTED"))
        assertEquals(R.string.qris_error_clock, QrisErrorMessages.forCode("TIMESTAMP_OUT_OF_RANGE"))
    }

    @Test fun `grouped codes share a message`() {
        val unavailable = QrisErrorMessages.forCode("DEVICE_NOT_ACTIVE")
        assertEquals(unavailable, QrisErrorMessages.forCode("MERCHANT_SUSPENDED"))
        assertEquals(unavailable, QrisErrorMessages.forCode("MERCHANT_CHANNEL_INACTIVE"))
        val host = QrisErrorMessages.forCode("QRIS_HOST_NOT_ACTIVE")
        assertEquals(host, QrisErrorMessages.forCode("QRIS_ACCOUNT_NOT_FOUND"))
        assertEquals(host, QrisErrorMessages.forCode("QRIS_HOST_AMBIGUOUS"))
        val auth = QrisErrorMessages.forCode("DEVICE_SIGNATURE_INVALID")
        assertEquals(auth, QrisErrorMessages.forCode("DEVICE_SIGNATURE_REPLAYED"))
        assertEquals(auth, QrisErrorMessages.forCode("DEVICE_CREDENTIAL_NOT_FOUND"))
        assertNotEquals(unavailable, host)
    }

    @Test fun `unknown or null codes fall back to the generic message`() {
        assertEquals(R.string.qris_error_generic, QrisErrorMessages.forCode("SOMETHING_NEW"))
        assertEquals(R.string.qris_error_generic, QrisErrorMessages.forCode(null))
    }
}
```

- [ ] **Step 3: Jalankan test, pastikan gagal**

Run: `./gradlew :feature-qris:testDebugUnitTest`
Expected: FAIL kompilasi — `Unresolved reference: QrCodeEncoder` / `QrisErrorMessages` / `R.string.qris_error_network`.

- [ ] **Step 4: Implementasi**

`feature-qris/src/main/kotlin/com/cashup/feature/qris/QrisDependencies.kt`:

```kotlin
package com.cashup.feature.qris

import com.cashup.common.network.ApiResult
import com.cashup.qris.GeneratedQr
import com.cashup.qris.PayApp
import com.cashup.qris.QrisPaymentResult
import java.math.BigDecimal

interface QrisDependencies {
    /** Dibaca ulang di batas transaksi supaya suspend jarak jauh langsung berlaku. */
    fun isTransactionAllowed(): Boolean = true

    /** Idempotent; kegagalan ditelan (polling menjadi jalur utama). */
    suspend fun ensureNotificationsConnected()

    suspend fun payApps(): ApiResult<List<PayApp>>

    suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr>

    /** Suspend sampai final atau kedaluwarsa; tidak melempar selain CancellationException. */
    suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult
}

interface QrisDependenciesOwner {
    val qrisDependencies: QrisDependencies
}
```

`feature-qris/src/main/kotlin/com/cashup/feature/qris/QrisErrorMessages.kt`:

```kotlin
package com.cashup.feature.qris

import androidx.annotation.StringRes

/** Keputusan selalu pada `error.code`, tidak pernah pada `message` (dokumen API). */
object QrisErrorMessages {
    @StringRes
    fun forCode(code: String?): Int = when (code) {
        "NETWORK" -> R.string.qris_error_network
        "AMOUNT_OUT_OF_RANGE" -> R.string.qris_error_amount
        "DEVICE_NOT_ACTIVE", "MERCHANT_SUSPENDED", "MERCHANT_CHANNEL_INACTIVE" -> R.string.qris_error_unavailable
        "QRIS_HOST_NOT_ACTIVE", "QRIS_ACCOUNT_NOT_FOUND", "QRIS_HOST_AMBIGUOUS" -> R.string.qris_error_host
        "ACQUIRER_REJECTED" -> R.string.qris_error_rejected
        "ACQUIRER_UNAVAILABLE" -> R.string.qris_error_acquirer_down
        "TIMESTAMP_REQUIRED", "TIMESTAMP_INVALID", "TIMESTAMP_OUT_OF_RANGE" -> R.string.qris_error_clock
        "DEVICE_SIGNATURE_HEADER_MISSING", "DEVICE_SIGNATURE_INVALID", "DEVICE_SIGNATURE_REPLAYED",
        "DEVICE_CREDENTIAL_NOT_FOUND" -> R.string.qris_error_auth
        else -> R.string.qris_error_generic
    }
}
```

`feature-qris/src/main/kotlin/com/cashup/feature/qris/QrCodeEncoder.kt`:

```kotlin
package com.cashup.feature.qris

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeEncoder {
    /** Murni JVM (tanpa Android); ubah ke Bitmap di layer UI. Panggil di background thread. */
    fun encode(content: String, size: Int): BitMatrix = QRCodeWriter().encode(
        content, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
}
```

`feature-qris/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="qris_title">Bayar dengan QRIS</string>
    <string name="qris_back">Kembali</string>
    <string name="qris_amount_label">Total Pembayaran</string>
    <string name="qris_loading">Menyiapkan QRIS…</string>
    <string name="qris_generating">Membuat kode QR…</string>
    <string name="qris_select_app">Pilih penyedia QRIS</string>
    <string name="qris_scan_hint">Minta pelanggan memindai kode QR ini</string>
    <string name="qris_expires_in">Berlaku %1$s</string>
    <string name="qris_paid">Pembayaran berhasil</string>
    <string name="qris_expired">Kode QR kedaluwarsa</string>
    <string name="qris_failed">Pembayaran gagal</string>
    <string name="qris_new_qr">Buat QR baru</string>
    <string name="qris_done">Selesai</string>
    <string name="qris_no_dynamic">QRIS tidak tersedia untuk merchant ini</string>
    <string name="qris_device_suspended">Terminal dinonaktifkan. Transaksi tidak dapat dilakukan.</string>
    <string name="qris_invalid_amount">Nominal tidak valid</string>
    <string name="qris_detail_issuer">Penerbit: %1$s</string>
    <string name="qris_detail_rrn">RRN: %1$s</string>
    <string name="qris_detail_approval">Kode persetujuan: %1$s</string>
    <string name="qris_error_code_fmt">Kode: %1$s</string>
    <string name="qris_error_generic">Terjadi kesalahan. Coba lagi.</string>
    <string name="qris_error_network">Tidak ada koneksi. Coba lagi.</string>
    <string name="qris_error_amount">Nominal di luar batas QRIS merchant.</string>
    <string name="qris_error_unavailable">Terminal atau merchant tidak dapat memakai QRIS.</string>
    <string name="qris_error_host">Penyedia QRIS tidak valid. Pilih ulang.</string>
    <string name="qris_error_rejected">Ditolak oleh penyedia QRIS.</string>
    <string name="qris_error_acquirer_down">Penyedia QRIS sedang gangguan. Coba lagi nanti.</string>
    <string name="qris_error_clock">Jam terminal tidak sesuai. Periksa tanggal dan jam.</string>
    <string name="qris_error_auth">Autentikasi terminal gagal. Hubungi teknisi.</string>
</resources>
```

`feature-qris/src/main/res/values/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="qris_surface">#FFFFFF</color>
    <color name="qris_navy">#252D63</color>
    <color name="qris_muted">#687A96</color>
    <color name="qris_ok">#176D2F</color>
    <color name="qris_error">#FD5662</color>
</resources>
```

- [ ] **Step 5: Jalankan test, pastikan lulus**

Run: `./gradlew :feature-qris:testDebugUnitTest checkModuleBoundaries`
Expected: PASS (5 test) dan `checkModuleBoundaries` BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/module-boundaries.gradle.kts feature-qris
git commit -m "feat(feature-qris): scaffold module with QrisDependencies, error messages and QR encoder" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 5: `QrisViewModel` (state machine)

**Files:**
- Create: `feature-qris/src/main/kotlin/com/cashup/feature/qris/QrisViewModel.kt`
- Test: `feature-qris/src/test/kotlin/com/cashup/feature/qris/QrisViewModelTest.kt`

**Interfaces:**
- Consumes (Task 1, 4): `PayApp`, `QrSupport`, `GeneratedQr`, `QrisPaymentResult`, `QrisStatus`, `QrisDependencies`, `QrisErrorMessages`, `R.string.qris_no_dynamic / qris_device_suspended / qris_invalid_amount / qris_failed`; `ApiResult`, `ApiError`.
- Produces:
  - `enum class QrisStage { LOADING_APPS, SELECT_APP, GENERATING, SHOWING_QR, PAID, EXPIRED, FAILED }`
  - `data class QrisUiState(stage: QrisStage = LOADING_APPS, amount: BigDecimal, apps: List<PayApp> = emptyList(), qr: GeneratedQr? = null, qrDeadline: Long = 0L, result: QrisPaymentResult? = null, @StringRes messageRes: Int? = null, errorCode: String? = null)`
  - `class QrisViewModel(dependencies: QrisDependencies, amountRupiah: Long, clock: () -> Long = ::monotonicMillis, retryDelayMs: Long = 1_000L) : ViewModel()` dengan `val state: StateFlow<QrisUiState>`, `fun selectApp(qrisHostId: Int)`, `fun retry()`, dan `class Factory(dependencies: QrisDependencies, amountRupiah: Long) : ViewModelProvider.Factory`. Memulai alur sendiri di `init`.
  - `internal fun monotonicMillis(): Long` (dipakai juga oleh fragment untuk countdown supaya jamnya sama).

- [ ] **Step 1: Tulis test yang gagal**

`feature-qris/src/test/kotlin/com/cashup/feature/qris/QrisViewModelTest.kt`:

```kotlin
package com.cashup.feature.qris

import com.cashup.common.network.ApiError
import com.cashup.common.network.ApiResult
import com.cashup.qris.GeneratedQr
import com.cashup.qris.PayApp
import com.cashup.qris.QrSupport
import com.cashup.qris.QrisPaymentResult
import com.cashup.qris.QrisStatus
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun app(id: Int, support: QrSupport = QrSupport.DYNAMIC, expiry: Int = 900) =
    PayApp(id, "HOST-$id", "APP-$id", null, support, expiry)

private fun qr(tx: String = "tx-1") =
    GeneratedQr(tx, "000001", BigDecimal("15000"), 2, "APP-2", "Toko", "0002", "APP-a")

private fun outcome(status: QrisStatus) = QrisPaymentResult("tx-1", status, BigDecimal("15000"), rrn = "123")

private class FakeDeps : QrisDependencies {
    var allowed = true
    var apps: ApiResult<List<PayApp>> = ApiResult.Success(listOf(app(2)))
    val generateCalls = mutableListOf<Triple<Int, BigDecimal, String>>()
    val generateResults = mutableListOf<ApiResult<GeneratedQr>>()
    var trackResult: QrisPaymentResult = outcome(QrisStatus.AUTHORIZED)
    var trackGate: CompletableDeferred<QrisPaymentResult>? = null
    var trackedExpiry: Int? = null
    var connectCalls = 0
    var payAppsCalls = 0

    override fun isTransactionAllowed() = allowed
    override suspend fun ensureNotificationsConnected() { connectCalls++ }
    override suspend fun payApps(): ApiResult<List<PayApp>> { payAppsCalls++; return apps }
    override suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr> {
        generateCalls += Triple(qrisHostId, amount, attemptId)
        return if (generateResults.isEmpty()) ApiResult.Success(qr()) else generateResults.removeAt(0)
    }
    override suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult {
        trackedExpiry = expirySeconds
        return trackGate?.await() ?: trackResult
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class QrisViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(deps: FakeDeps, amount: Long = 15_000, clock: () -> Long = { 1_000L }) =
        QrisViewModel(deps, amount, clock, retryDelayMs = 1_000L)

    @Test fun `single dynamic pay app generates immediately and ends PAID`() = runTest {
        val deps = FakeDeps()
        val viewModel = vm(deps)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
        assertEquals(1, deps.connectCalls)
        assertEquals(1, deps.generateCalls.size)
        val (host, amount, attempt) = deps.generateCalls.single()
        assertEquals(2, host)
        assertEquals(BigDecimal("15000"), amount)
        assertTrue(attempt.isNotBlank())
        assertEquals("123", viewModel.state.value.result?.rrn)
        assertEquals(900, deps.trackedExpiry)
    }

    @Test fun `showing qr exposes the qr and a local deadline from qrExpirySeconds`() = runTest {
        val deps = FakeDeps().apply { trackGate = CompletableDeferred() }
        val viewModel = vm(deps, clock = { 1_000L })
        val state = viewModel.state.value
        assertEquals(QrisStage.SHOWING_QR, state.stage)
        assertEquals("0002", state.qr?.qrContent)
        assertEquals(1_000L + 900_000L, state.qrDeadline)
        deps.trackGate!!.complete(outcome(QrisStatus.AUTHORIZED))
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `several dynamic apps ask the user to choose and static-only apps are filtered out`() = runTest {
        val deps = FakeDeps().apply {
            apps = ApiResult.Success(listOf(app(2, QrSupport.DYNAMIC), app(3, QrSupport.BOTH), app(4, QrSupport.STATIC)))
        }
        val viewModel = vm(deps)
        assertEquals(QrisStage.SELECT_APP, viewModel.state.value.stage)
        assertEquals(listOf(2, 3), viewModel.state.value.apps.map { it.qrisHostId })
        assertTrue(deps.generateCalls.isEmpty())
        viewModel.selectApp(3)
        assertEquals(3, deps.generateCalls.single().first)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `no dynamic pay app fails with a clear message`() = runTest {
        val deps = FakeDeps().apply { apps = ApiResult.Success(listOf(app(4, QrSupport.STATIC))) }
        val viewModel = vm(deps)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_no_dynamic, viewModel.state.value.messageRes)
    }

    @Test fun `pay apps failure surfaces the mapped message and code`() = runTest {
        val deps = FakeDeps().apply { apps = ApiResult.Failure(ApiError("ACQUIRER_UNAVAILABLE", "down", 503)) }
        val viewModel = vm(deps)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_error_acquirer_down, viewModel.state.value.messageRes)
        assertEquals("ACQUIRER_UNAVAILABLE", viewModel.state.value.errorCode)
    }

    @Test fun `business failure then retry uses a new attempt id`() = runTest {
        val deps = FakeDeps().apply {
            generateResults += ApiResult.Failure(ApiError("AMOUNT_OUT_OF_RANGE", "x", 422))
        }
        val viewModel = vm(deps)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_error_amount, viewModel.state.value.messageRes)
        viewModel.retry()
        assertEquals(2, deps.generateCalls.size)
        assertNotEquals(deps.generateCalls[0].third, deps.generateCalls[1].third)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `network failure then retry keeps the same attempt id`() = runTest {
        val deps = FakeDeps().apply {
            generateResults += ApiResult.Failure(ApiError("NETWORK", "timeout"))
        }
        val viewModel = vm(deps)
        assertEquals(R.string.qris_error_network, viewModel.state.value.messageRes)
        viewModel.retry()
        assertEquals(deps.generateCalls[0].third, deps.generateCalls[1].third)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `idempotency in progress is retried once with the same key after a short delay`() = runTest {
        val deps = FakeDeps().apply {
            generateResults += ApiResult.Failure(ApiError("IDEMPOTENCY_KEY_IN_PROGRESS", "wait", 409))
        }
        val viewModel = vm(deps)
        advanceUntilIdle()
        assertEquals(2, deps.generateCalls.size)
        assertEquals(deps.generateCalls[0].third, deps.generateCalls[1].third)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `expired outcome ends EXPIRED and retry generates a fresh qr`() = runTest {
        val deps = FakeDeps().apply { trackResult = outcome(QrisStatus.EXPIRED) }
        val viewModel = vm(deps)
        assertEquals(QrisStage.EXPIRED, viewModel.state.value.stage)
        deps.trackResult = outcome(QrisStatus.AUTHORIZED)
        viewModel.retry()
        assertEquals(2, deps.generateCalls.size)
        assertNotEquals(deps.generateCalls[0].third, deps.generateCalls[1].third)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `failed acquirer outcome ends FAILED with the generic failure message`() = runTest {
        val deps = FakeDeps().apply { trackResult = outcome(QrisStatus.FAILED) }
        val viewModel = vm(deps)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_failed, viewModel.state.value.messageRes)
    }

    @Test fun `suspended device never touches the network`() = runTest {
        val deps = FakeDeps().apply { allowed = false }
        val viewModel = vm(deps)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_device_suspended, viewModel.state.value.messageRes)
        assertEquals(0, deps.payAppsCalls)
        assertTrue(deps.generateCalls.isEmpty())
    }

    @Test fun `non positive amount is rejected before any call`() = runTest {
        val deps = FakeDeps()
        val viewModel = vm(deps, amount = 0)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_invalid_amount, viewModel.state.value.messageRes)
        assertEquals(0, deps.payAppsCalls)
        assertNull(viewModel.state.value.qr)
    }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `./gradlew :feature-qris:testDebugUnitTest --tests "com.cashup.feature.qris.QrisViewModelTest"`
Expected: FAIL kompilasi — `Unresolved reference: QrisViewModel`.

- [ ] **Step 3: Implementasi**

`feature-qris/src/main/kotlin/com/cashup/feature/qris/QrisViewModel.kt`:

```kotlin
package com.cashup.feature.qris

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cashup.common.network.ApiError
import com.cashup.common.network.ApiResult
import com.cashup.qris.GeneratedQr
import com.cashup.qris.PayApp
import com.cashup.qris.QrisPaymentResult
import com.cashup.qris.QrisStatus
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class QrisStage { LOADING_APPS, SELECT_APP, GENERATING, SHOWING_QR, PAID, EXPIRED, FAILED }

data class QrisUiState(
    val stage: QrisStage = QrisStage.LOADING_APPS,
    val amount: BigDecimal,
    val apps: List<PayApp> = emptyList(),
    val qr: GeneratedQr? = null,
    /** Jam monotonic lokal ([monotonicMillis]) saat QR kedaluwarsa; bukan dari `expiresAt` server. */
    val qrDeadline: Long = 0L,
    val result: QrisPaymentResult? = null,
    @StringRes val messageRes: Int? = null,
    val errorCode: String? = null,
)

/** Jam monotonic yang sama dipakai ViewModel dan fragment; tanpa API Android supaya JVM-testable. */
internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

class QrisViewModel(
    private val dependencies: QrisDependencies,
    amountRupiah: Long,
    private val clock: () -> Long = ::monotonicMillis,
    private val retryDelayMs: Long = 1_000L,
) : ViewModel() {
    private val amount = BigDecimal.valueOf(amountRupiah)
    private val mutableState = MutableStateFlow(QrisUiState(amount = amount))
    val state: StateFlow<QrisUiState> = mutableState

    private var job: Job? = null
    private var selectedApp: PayApp? = null
    /** Dipertahankan hanya setelah kegagalan NETWORK: request mungkin sudah sampai ke server. */
    private var attemptId: String? = null

    init {
        // Koneksi MQTT paralel; tidak menunda pemuatan pay-apps. Kegagalan sudah ditelan implementasinya.
        viewModelScope.launch { dependencies.ensureNotificationsConnected() }
        job = viewModelScope.launch { begin() }
    }

    fun selectApp(qrisHostId: Int) {
        val app = state.value.apps.firstOrNull { it.qrisHostId == qrisHostId } ?: return
        if (state.value.stage != QrisStage.SELECT_APP) return
        job = viewModelScope.launch { generateAndTrack(app) }
    }

    /** "Coba lagi" (FAILED) atau "Buat QR baru" (EXPIRED). */
    fun retry() {
        val stage = state.value.stage
        if (stage != QrisStage.FAILED && stage != QrisStage.EXPIRED) return
        job = viewModelScope.launch { selectedApp?.let { generateAndTrack(it) } ?: begin() }
    }

    private suspend fun begin() {
        if (amount.signum() <= 0) return fail(R.string.qris_invalid_amount)
        if (!dependencies.isTransactionAllowed()) return fail(R.string.qris_device_suspended)
        update { copy(stage = QrisStage.LOADING_APPS, messageRes = null, errorCode = null) }
        when (val loaded = dependencies.payApps()) {
            is ApiResult.Failure -> failWith(loaded.error)
            is ApiResult.Success -> {
                val dynamic = loaded.data.filter { it.supportsDynamic }
                when {
                    dynamic.isEmpty() -> fail(R.string.qris_no_dynamic)
                    dynamic.size == 1 -> generateAndTrack(dynamic.first())
                    else -> update { copy(stage = QrisStage.SELECT_APP, apps = dynamic) }
                }
            }
        }
    }

    private suspend fun generateAndTrack(app: PayApp) {
        selectedApp = app
        if (!dependencies.isTransactionAllowed()) return fail(R.string.qris_device_suspended)
        update { copy(stage = QrisStage.GENERATING, qr = null, result = null, messageRes = null, errorCode = null) }
        val attempt = attemptId ?: UUID.randomUUID().toString().also { attemptId = it }
        var generated = dependencies.generate(app.qrisHostId, amount, attempt)
        if (generated is ApiResult.Failure && generated.error.code == IN_PROGRESS) {
            delay(retryDelayMs)
            generated = dependencies.generate(app.qrisHostId, amount, attempt)
        }
        when (generated) {
            is ApiResult.Failure -> {
                if (generated.error.code != NETWORK) attemptId = null
                failWith(generated.error)
            }
            is ApiResult.Success -> {
                attemptId = null
                showAndTrack(generated.data, app)
            }
        }
    }

    private suspend fun showAndTrack(qr: GeneratedQr, app: PayApp) {
        val deadline = clock() + app.qrExpirySeconds * 1_000L
        update { copy(stage = QrisStage.SHOWING_QR, qr = qr, qrDeadline = deadline) }
        val outcome = dependencies.track(qr, app.qrExpirySeconds)
        update {
            when {
                outcome.status.isPaid -> copy(stage = QrisStage.PAID, result = outcome)
                outcome.status == QrisStatus.EXPIRED -> copy(stage = QrisStage.EXPIRED, result = outcome)
                else -> copy(stage = QrisStage.FAILED, result = outcome, messageRes = R.string.qris_failed)
            }
        }
    }

    private fun fail(@StringRes messageRes: Int) =
        update { copy(stage = QrisStage.FAILED, messageRes = messageRes, errorCode = null) }

    private fun failWith(error: ApiError) =
        update { copy(stage = QrisStage.FAILED, messageRes = QrisErrorMessages.forCode(error.code), errorCode = error.code) }

    private inline fun update(block: QrisUiState.() -> QrisUiState) {
        mutableState.value = mutableState.value.block()
    }

    class Factory(
        private val dependencies: QrisDependencies,
        private val amountRupiah: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            QrisViewModel(dependencies, amountRupiah) as T
    }

    private companion object {
        const val NETWORK = "NETWORK"
        const val IN_PROGRESS = "IDEMPOTENCY_KEY_IN_PROGRESS"
    }
}
```

Catatan: `job` disimpan supaya mudah diperluas, tetapi `viewModelScope` sudah membatalkan semuanya di `onCleared`; jika compiler memperingatkan `job` tidak dipakai, hapus properti `job` dan pakai `viewModelScope.launch { ... }` langsung.

- [ ] **Step 4: Jalankan test, pastikan lulus**

Run: `./gradlew :feature-qris:testDebugUnitTest`
Expected: PASS — 12 test `QrisViewModelTest` ditambah 5 test dari Task 4 (17 total di `:feature-qris`).

- [ ] **Step 5: Commit**

```bash
git add feature-qris
git commit -m "feat(feature-qris): add QrisViewModel state machine" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 6: `QrisFragment` + layout + destinasi navigasi

**Files:**
- Create: `feature-qris/src/main/res/layout/fragment_qris.xml`
- Create: `feature-qris/src/main/kotlin/com/cashup/feature/qris/QrisFragment.kt`
- Modify: `app/src/main/res/navigation/nav_home.xml`

**Interfaces:**
- Consumes (Task 4–5): `QrisDependenciesOwner`, `QrisViewModel` + `Factory`, `QrisUiState`, `QrisStage`, `QrCodeEncoder`, `monotonicMillis()`, string `qris_*`, warna `qris_*`.
- Produces: `class QrisFragment : Fragment(R.layout.fragment_qris)` yang membaca argumen `amount: Long` (`QrisFragment.ARG_AMOUNT = "amount"`); destinasi `nav_qris` di `nav_home.xml` dengan deep-link `cashup://qris?amount={amount}` (dipakai Task 8).

Fragment ini UI murni; verifikasinya build + uji manual (Step 4), bukan unit test.

- [ ] **Step 1: Layout**

`feature-qris/src/main/res/layout/fragment_qris.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/qris_surface"
    android:gravity="center_horizontal"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/back"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="start"
        android:padding="8dp"
        android:text="@string/qris_back"
        android:textColor="@color/qris_navy"
        android:textSize="16sp" />

    <TextView
        android:id="@+id/title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/qris_title"
        android:textColor="@color/qris_muted"
        android:textSize="16sp" />

    <TextView
        android:id="@+id/amountValue"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:textColor="@color/qris_navy"
        android:textSize="32sp"
        android:textStyle="bold" />

    <ProgressBar
        android:id="@+id/progress"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:layout_marginTop="32dp"
        android:indeterminate="true" />

    <ImageView
        android:id="@+id/qrImage"
        android:layout_width="280dp"
        android:layout_height="280dp"
        android:layout_marginTop="16dp"
        android:background="@color/qris_surface"
        android:contentDescription="@string/qris_title"
        android:visibility="gone" />

    <TextView
        android:id="@+id/countdown"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textColor="@color/qris_muted"
        android:textSize="14sp"
        android:visibility="gone" />

    <TextView
        android:id="@+id/statusText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:gravity="center"
        android:textColor="@color/qris_navy"
        android:textSize="18sp" />

    <LinearLayout
        android:id="@+id/details"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:orientation="vertical"
        android:visibility="gone">

        <TextView
            android:id="@+id/detailIssuer"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/qris_muted"
            android:textSize="14sp" />

        <TextView
            android:id="@+id/detailRrn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/qris_muted"
            android:textSize="14sp" />

        <TextView
            android:id="@+id/detailApproval"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/qris_muted"
            android:textSize="14sp" />
    </LinearLayout>

    <Button
        android:id="@+id/primaryAction"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:visibility="gone" />
</LinearLayout>
```

- [ ] **Step 2: Fragment**

`feature-qris/src/main/kotlin/com/cashup/feature/qris/QrisFragment.kt`:

```kotlin
package com.cashup.feature.qris

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.cashup.feature.qris.databinding.FragmentQrisBinding
import com.google.zxing.common.BitMatrix
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QrisFragment : Fragment(R.layout.fragment_qris) {
    private val dependencies: QrisDependencies by lazy {
        (requireActivity().application as QrisDependenciesOwner).qrisDependencies
    }
    private val viewModel: QrisViewModel by viewModels {
        QrisViewModel.Factory(dependencies, requireArguments().getLong(ARG_AMOUNT))
    }
    private var countdown: Job? = null
    private var appDialog: AlertDialog? = null
    private var renderedQr: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentQrisBinding.bind(view)
        renderedQr = null
        binding.back.setOnClickListener { findNavController().popBackStack() }
        binding.primaryAction.setOnClickListener {
            if (viewModel.state.value.stage == QrisStage.PAID) findNavController().popBackStack()
            else viewModel.retry()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(binding, it) }
            }
        }
    }

    override fun onDestroyView() {
        appDialog?.dismiss()
        appDialog = null
        countdown?.cancel()
        super.onDestroyView()
    }

    private fun render(binding: FragmentQrisBinding, state: QrisUiState) {
        val stage = state.stage
        binding.amountValue.text = state.amount.rupiah()
        binding.progress.isVisible = stage == QrisStage.LOADING_APPS || stage == QrisStage.GENERATING
        binding.qrImage.isVisible = stage == QrisStage.SHOWING_QR
        binding.countdown.isVisible = stage == QrisStage.SHOWING_QR
        binding.details.isVisible = stage == QrisStage.PAID
        binding.primaryAction.isVisible = stage == QrisStage.PAID || stage == QrisStage.EXPIRED || stage == QrisStage.FAILED
        binding.primaryAction.setText(if (stage == QrisStage.PAID) R.string.qris_done else R.string.qris_new_qr)

        binding.statusText.setTextColor(requireContext().getColor(when (stage) {
            QrisStage.PAID -> R.color.qris_ok
            QrisStage.EXPIRED, QrisStage.FAILED -> R.color.qris_error
            else -> R.color.qris_navy
        }))
        binding.statusText.text = when (stage) {
            QrisStage.LOADING_APPS -> getString(R.string.qris_loading)
            QrisStage.GENERATING -> getString(R.string.qris_generating)
            QrisStage.SELECT_APP -> getString(R.string.qris_select_app)
            QrisStage.SHOWING_QR -> getString(R.string.qris_scan_hint)
            QrisStage.PAID -> getString(R.string.qris_paid)
            QrisStage.EXPIRED -> getString(R.string.qris_expired)
            QrisStage.FAILED -> failureText(state)
        }

        if (stage == QrisStage.SHOWING_QR) {
            state.qr?.let { showQr(binding, it.qrContent) }
            startCountdown(binding, state.qrDeadline)
        } else {
            countdown?.cancel()
        }
        if (stage == QrisStage.PAID) renderDetails(binding, state)
        if (stage == QrisStage.SELECT_APP && appDialog == null) showAppPicker(state)
    }

    private fun failureText(state: QrisUiState): String {
        val message = getString(state.messageRes ?: R.string.qris_error_generic)
        val code = state.errorCode?.let { "\n" + getString(R.string.qris_error_code_fmt, it) }.orEmpty()
        return message + code
    }

    private fun showAppPicker(state: QrisUiState) {
        appDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.qris_select_app)
            .setItems(state.apps.map { it.displayName }.toTypedArray()) { _, which ->
                appDialog = null
                viewModel.selectApp(state.apps[which].qrisHostId)
            }
            .setOnCancelListener { appDialog = null; findNavController().popBackStack() }
            .show()
    }

    private fun showQr(binding: FragmentQrisBinding, content: String) {
        if (renderedQr == content) return
        renderedQr = content
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) { QrCodeEncoder.encode(content, QR_SIZE_PX).toBitmap() }
            binding.qrImage.setImageBitmap(bitmap)
        }
    }

    private fun startCountdown(binding: FragmentQrisBinding, deadline: Long) {
        if (countdown?.isActive == true) return
        countdown = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val remaining = maxOf(0L, (deadline - monotonicMillis()) / 1_000L)
                binding.countdown.text = getString(R.string.qris_expires_in, "%d:%02d".format(remaining / 60, remaining % 60))
                if (remaining == 0L) break
                delay(1_000L)
            }
        }
    }

    private fun renderDetails(binding: FragmentQrisBinding, state: QrisUiState) {
        val result = state.result
        // Nilai null berarti tidak tersedia: sembunyikan barisnya, jangan tampilkan "null".
        binding.detailIssuer.bind(R.string.qris_detail_issuer, result?.issuerName ?: result?.pushDetail?.issuerName)
        binding.detailRrn.bind(R.string.qris_detail_rrn, result?.rrn ?: result?.pushDetail?.rrn)
        binding.detailApproval.bind(R.string.qris_detail_approval, result?.approvalCode ?: result?.pushDetail?.approvalCode)
    }

    private fun TextView.bind(format: Int, value: String?) {
        isVisible = !value.isNullOrBlank()
        if (isVisible) text = getString(format, value)
    }

    private fun BitMatrix.toBitmap(): Bitmap {
        val pixels = IntArray(width * height) { if (get(it % width, it / width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun BigDecimal.rupiah(): String =
        "Rp " + NumberFormat.getIntegerInstance(Locale("id", "ID")).format(this)

    companion object {
        /** Nama argumen di `nav_home.xml` dan deep-link `cashup://qris?amount={amount}`. */
        const val ARG_AMOUNT = "amount"
        private const val QR_SIZE_PX = 512
    }
}
```

- [ ] **Step 3: Destinasi navigasi**

`app/src/main/res/navigation/nav_home.xml` — tambahkan sebelum `</navigation>` (graph ini di-host `NavHostFragment` `homeNavHost` di `HomeFragment`, sehingga `findNavController()` dari `CardPaymentFragment` menemukannya):

```xml
    <fragment
        android:id="@+id/nav_qris"
        android:name="com.cashup.feature.qris.QrisFragment">
        <argument
            android:name="amount"
            app:argType="long" />
        <deepLink app:uri="cashup://qris?amount={amount}" />
    </fragment>
```

- [ ] **Step 4: Verifikasi build modul**

Run: `./gradlew :feature-qris:assembleDebug :feature-qris:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. (`app` belum bergantung ke `:feature-qris` sampai Task 7, jadi `nav_home.xml` baru terkompilasi penuh di Task 7 — jangan jalankan `:app:assembleDebug` di sini.)

- [ ] **Step 5: Commit**

```bash
git add feature-qris app/src/main/res/navigation/nav_home.xml
git commit -m "feat(feature-qris): add QrisFragment UI and nav destination with deep-link" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 7: `:app` wiring (`AppQrisDependencies`, `AppContainer`, `CashupApp`)

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `gradle/module-boundaries.gradle.kts`
- Create: `app/src/main/kotlin/com/cashup/app/di/AppQrisDependencies.kt`
- Modify: `app/src/main/kotlin/com/cashup/app/di/AppContainer.kt`
- Modify: `app/src/main/kotlin/com/cashup/app/CashupApp.kt`
- Test: `app/src/test/kotlin/com/cashup/app/di/AppQrisDependenciesTest.kt`

**Interfaces:**
- Consumes: `QrisDependencies` / `QrisDependenciesOwner` (Task 4), `QrisGateway` / `QrisRepository.create` / `QrisPaymentTracker` (Task 2–3), `PaymentNotificationTransport` / `PaymentStatusWaiter` / `MqttNotificationTransport(host, port)` (`:notification-core`), `AppConfig.baseUrl/mqttHost/mqttPort`, `AppContainer.deviceSigner`, `activeDeviceId()`, `isTransactionAllowed()`.
- Produces: `class AppQrisDependencies(gateway: QrisGateway, tracker: QrisPaymentTracker, transport: PaymentNotificationTransport, deviceId: () -> String?, transactionAllowed: () -> Boolean, io: CoroutineDispatcher = Dispatchers.IO) : QrisDependencies`; `AppContainer.qrisDependencies: QrisDependencies`; `CashupApp : QrisDependenciesOwner`.

- [ ] **Step 1: Tulis test yang gagal**

`app/src/test/kotlin/com/cashup/app/di/AppQrisDependenciesTest.kt`:

```kotlin
package com.cashup.app.di

import com.cashup.common.network.ApiResult
import com.cashup.notification.PaymentNotificationTransport
import com.cashup.notification.PaymentStatusWaiter
import com.cashup.notification.RawNotification
import com.cashup.qris.GeneratedQr
import com.cashup.qris.PayApp
import com.cashup.qris.QrisGateway
import com.cashup.qris.QrisPaymentResult
import com.cashup.qris.QrisPaymentTracker
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppQrisDependenciesTest {
    private class FakeTransport(private val failOnConnect: Boolean = false) : PaymentNotificationTransport {
        val connectedIds = mutableListOf<String>()
        override val isConnected get() = connectedIds.isNotEmpty()
        override fun connect(deviceId: String) {
            if (failOnConnect) throw IllegalStateException("broker unreachable")
            connectedIds += deviceId
        }
        override val messages: Flow<RawNotification> = emptyFlow()
        override fun disconnect() = Unit
    }

    private object NoopGateway : QrisGateway {
        override suspend fun payApps(): ApiResult<List<PayApp>> = ApiResult.Success(emptyList())
        override suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr> = error("unused")
        override suspend fun inquiry(transactionId: String): ApiResult<QrisPaymentResult> = error("unused")
    }

    private fun deps(transport: FakeTransport, deviceId: String? = "dev-1", allowed: Boolean = true) =
        AppQrisDependencies(
            gateway = NoopGateway,
            tracker = QrisPaymentTracker(NoopGateway, PaymentStatusWaiter(transport), transport),
            transport = transport,
            deviceId = { deviceId },
            transactionAllowed = { allowed },
            io = Dispatchers.Unconfined,
        )

    @Test fun `connects the transport with the provisioned device id`() = runBlocking {
        val transport = FakeTransport()
        deps(transport).ensureNotificationsConnected()
        assertEquals(listOf("dev-1"), transport.connectedIds)
    }

    @Test fun `does nothing when the device is not provisioned`() = runBlocking {
        val transport = FakeTransport()
        deps(transport, deviceId = null).ensureNotificationsConnected()
        assertTrue(transport.connectedIds.isEmpty())
    }

    @Test fun `a failing broker connection is swallowed so polling can take over`() = runBlocking {
        deps(FakeTransport(failOnConnect = true)).ensureNotificationsConnected()
    }

    @Test fun `transaction permission is delegated`() {
        assertTrue(deps(FakeTransport(), allowed = true).isTransactionAllowed())
        assertFalse(deps(FakeTransport(), allowed = false).isTransactionAllowed())
    }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `./gradlew :app:testDebugUnitTest --tests "com.cashup.app.di.AppQrisDependenciesTest"`
Expected: FAIL kompilasi — `Unresolved reference: AppQrisDependencies` (dan/atau `com.cashup.qris` belum ada di classpath `:app`).

- [ ] **Step 3: Dependensi & boundaries**

`app/build.gradle.kts` — di blok `dependencies`, setelah `implementation(project(":device-sdk-factory"))`:

```kotlin
    implementation(project(":notification-core"))
    implementation(project(":qris-core"))
    implementation(project(":feature-qris"))
```

`gradle/module-boundaries.gradle.kts` — ganti entri `":app"`:

```kotlin
    ":app" to setOf(
        ":provisioning-core", ":cdcp-core", ":device-sdk-edcsdk", ":device-sdk-factory",
        ":feature-card-payment", ":notification-core", ":qris-core", ":feature-qris",
    ),
```

- [ ] **Step 4: Implementasi**

`app/src/main/kotlin/com/cashup/app/di/AppQrisDependencies.kt`:

```kotlin
package com.cashup.app.di

import android.util.Log
import com.cashup.common.network.ApiResult
import com.cashup.feature.qris.QrisDependencies
import com.cashup.notification.PaymentNotificationTransport
import com.cashup.qris.GeneratedQr
import com.cashup.qris.PayApp
import com.cashup.qris.QrisGateway
import com.cashup.qris.QrisPaymentResult
import com.cashup.qris.QrisPaymentTracker
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Jembatan `:feature-qris` -> `:qris-core`/`:notification-core`. Koneksi MQTT dibuat lazily saat
 * layar QRIS pertama dibuka (bukan saat startup app), lalu tetap hidup selama proses; tidak pernah
 * `disconnect()` dari alur pembayaran (spec 2026-09-29 §6).
 */
class AppQrisDependencies(
    private val gateway: QrisGateway,
    private val tracker: QrisPaymentTracker,
    private val transport: PaymentNotificationTransport,
    private val deviceId: () -> String?,
    private val transactionAllowed: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : QrisDependencies {

    override fun isTransactionAllowed(): Boolean = transactionAllowed()

    override suspend fun ensureNotificationsConnected() {
        val id = deviceId() ?: return
        withContext(io) {
            try {
                transport.connect(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Push hanyalah percepatan; tanpa MQTT tracker tetap jalan lewat polling 3 dtk.
                Log.w(TAG, "MQTT connect gagal, memakai polling: ${e.message}")
            }
        }
    }

    override suspend fun payApps(): ApiResult<List<PayApp>> = gateway.payApps()

    override suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr> =
        gateway.generate(qrisHostId, amount, attemptId)

    override suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult =
        tracker.track(qr, expirySeconds)

    private companion object {
        const val TAG = "QrisDependencies"
    }
}
```

`app/src/main/kotlin/com/cashup/app/di/AppContainer.kt` — tambahkan import:

```kotlin
import com.cashup.feature.qris.QrisDependencies
import com.cashup.notification.MqttNotificationTransport
import com.cashup.notification.PaymentStatusWaiter
import com.cashup.qris.QrisPaymentTracker
import com.cashup.qris.QrisRepository
```

dan properti baru, tepat setelah `val saleRepository ... { ... }`:

```kotlin
    val qrisDependencies: QrisDependencies by lazy {
        val gateway = QrisRepository.create(config.baseUrl, deviceSigner, debugLogging = BuildConfig.DEBUG)
        val transport = MqttNotificationTransport(config.mqttHost, config.mqttPort)
        AppQrisDependencies(
            gateway = gateway,
            tracker = QrisPaymentTracker(gateway, PaymentStatusWaiter(transport), transport),
            transport = transport,
            deviceId = this::activeDeviceId,
            transactionAllowed = this::isTransactionAllowed,
        )
    }
```

`app/src/main/kotlin/com/cashup/app/CashupApp.kt`:

```kotlin
package com.cashup.app

import android.app.Application
import com.cashup.app.di.AppContainer
import com.cashup.feature.cardpayment.CardPaymentDependencies
import com.cashup.feature.cardpayment.CardPaymentDependenciesOwner
import com.cashup.feature.qris.QrisDependencies
import com.cashup.feature.qris.QrisDependenciesOwner

class CashupApp : Application(), CardPaymentDependenciesOwner, QrisDependenciesOwner {
    lateinit var container: AppContainer
        private set

    override val cardPaymentDependencies: CardPaymentDependencies
        get() = container

    override val qrisDependencies: QrisDependencies
        get() = container.qrisDependencies

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
```

- [ ] **Step 5: Jalankan test + build, pastikan lulus**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug checkModuleBoundaries`
Expected: PASS (4 test baru + test `:app` yang sudah ada), APK terbangun, boundaries hijau. Jika `MqttNotificationTransport` ternyata tidak terlihat dari `:app`, pastikan `implementation(project(":notification-core"))` ada (Step 3) — `:qris-core` mengekspos `:notification-core` lewat `api`, tetapi `:app` memakai kelasnya langsung.

- [ ] **Step 6: Commit**

```bash
git add app gradle/module-boundaries.gradle.kts
git commit -m "feat(app): wire QRIS dependencies with lazy MQTT connection" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 8: Sambungkan tombol QRIS di `CardPaymentFragment`

**Files:**
- Modify: `feature-card-payment/build.gradle.kts`
- Modify: `feature-card-payment/src/main/kotlin/com/cashup/feature/cardpayment/CardPaymentFragment.kt:74`

**Interfaces:**
- Consumes: destinasi `nav_qris` dengan deep-link `cashup://qris?amount={amount}` (Task 6); `calculator.current().amount: Long`; `R.string.card_payment_invalid_amount` (sudah ada).
- Produces: tombol `payQris` membuka layar QRIS membawa nominal. `otherPayment` tetap `showUnavailable()`.

- [ ] **Step 1: Dependensi navigation**

`feature-card-payment/build.gradle.kts` — tambahkan di blok `dependencies`, setelah `implementation("androidx.fragment:fragment-ktx:1.5.7")`:

```kotlin
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
```

- [ ] **Step 2: Ubah tombol**

Di `CardPaymentFragment.kt` tambahkan import (urut alfabet di antara import yang ada):

```kotlin
import android.net.Uri
import androidx.navigation.fragment.findNavController
```

Ganti baris 74:

```kotlin
        binding.payQris.setOnClickListener { showUnavailable() }
```

dengan:

```kotlin
        binding.payQris.setOnClickListener {
            val amount = calculator.current().amount
            if (amount <= 0) {
                Toast.makeText(requireContext(), R.string.card_payment_invalid_amount, Toast.LENGTH_SHORT).show()
            } else {
                // Deep-link, bukan id navigasi: modul ini tidak boleh bergantung ke :feature-qris atau :app.
                // Skema harus sama dengan <deepLink> nav_qris di app/src/main/res/navigation/nav_home.xml.
                findNavController().navigate(Uri.parse("cashup://qris?amount=$amount"))
            }
        }
```

Baris `binding.otherPayment.setOnClickListener { showUnavailable() }` dibiarkan.

- [ ] **Step 3: Verifikasi build dan test**

Run: `./gradlew :feature-card-payment:testDebugUnitTest :app:assembleDebug checkModuleBoundaries`
Expected: BUILD SUCCESSFUL; test `CardPaymentViewModelTest` dan `SimpleCalculatorTest` tetap hijau.

- [ ] **Step 4: Commit**

```bash
git add feature-card-payment
git commit -m "feat(card-payment): open QRIS screen from the QRIS button" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 9: Verifikasi penuh, smoke test dev server, catat hasil

**Files:**
- Modify: `docs/session_log.md` (entri baru di paling atas, format sama dengan entri yang ada)

- [ ] **Step 1: Seluruh test + boundaries + build**

Run: `./gradlew test checkModuleBoundaries assembleDebug`
Expected: BUILD SUCCESSFUL, tidak ada test gagal. Jika ada test modul lain yang gagal dan **bukan** akibat perubahan ini, catat namanya di session log dan jangan diperbaiki dalam plan ini.

- [ ] **Step 2: Smoke test manual terhadap dev server**

Prasyarat: device/emulator terprovisioning, satu jaringan dengan broker dev (`100.114.27.64` Tailscale atau `192.168.90.46` LAN), backend dev hidup, akses simulator pembayaran dari tim backend. Jalankan dan catat hasil **apa adanya** (lulus/gagal, angka nyata):

- [ ] Layar nominal → isi `15000` → tekan QRIS → layar QRIS terbuka menampilkan `Rp 15.000`, lalu QR muncul (satu pay-app) atau dialog pilihan (beberapa).
- [ ] Request `pay-apps`/`qr-codes` diterima backend (tidak 401 `DEVICE_SIGNATURE_INVALID`; tidak 400 `TIMESTAMP_*`) → menjawab pertanyaan terbuka spec §10 nomor 2 (format `X-Timestamp`).
- [ ] Endpoint `/v1/qris/*` terjawab dari `AppConfig.baseUrl` (`:8080`) → menjawab pertanyaan terbuka nomor 1. Bila 404/koneksi ditolak: tambahkan `AppConfig.qrisBaseUrl` (default sesuai host QRIS yang benar) dan pakai di `qrisDependencies` di `AppContainer`, lalu ulangi — itu satu perubahan kecil terpisah, commit tersendiri.
- [ ] Bayar QR lewat simulator → layar berpindah ke "Pembayaran berhasil" dan mencatat berapa detik dari pembayaran hingga layar berubah (push via MQTT port `1883` → menjawab pertanyaan terbuka nomor 3; bandingkan dengan 516 ms/212 ms yang terukur di `8883`).
- [ ] Ulangi dengan MQTT sengaja tidak terjangkau (matikan Tailscale/ubah `mqttHost` ke alamat mati): QR tetap tampil, pembayaran tetap terdeteksi lewat polling (≤ ±8 detik dari bayar), tidak ada crash.
- [ ] QR dibiarkan kedaluwarsa (atau pakai pay-app `qrExpirySeconds` pendek bila ada) → layar "Kode QR kedaluwarsa" → "Buat QR baru" menghasilkan QR baru.
- [ ] Nominal `0` di layar nominal → tombol QRIS menampilkan toast nominal tidak valid dan tidak berpindah layar.

- [ ] **Step 3: Catat di `docs/session_log.md`**

Tambahkan entri di atas dengan judul `## Status terkini — 2026-09-29 (QRIS Dynamic)`: ringkas apa yang dibangun (modul `:qris-core`, `:feature-qris`, wiring `:app`, tombol QRIS), hasil smoke test dari Step 2 (angka nyata, bukan perkiraan), dan status tiga pertanyaan terbuka spec §10 (terjawab/masih terbuka beserta buktinya). Jangan menulis "lulus" untuk item yang tidak benar-benar dijalankan.

- [ ] **Step 4: Commit**

```bash
git add docs/session_log.md
git commit -m "docs: record QRIS dynamic smoke test results" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

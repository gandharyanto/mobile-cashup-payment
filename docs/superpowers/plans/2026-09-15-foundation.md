# Foundation (common-core, device-sdk-api, signing-core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Gradle project skeleton and the three foundation modules (`common-core`, `device-sdk-api`, `signing-core`) that every later plan (Provisioning, Notification, CDCP, QRIS, vendor SDK adapters, ECR bridge, App shell) builds on.

**Architecture:** Three pure Kotlin/JVM library modules (no Android framework dependency, no Robolectric needed — fast unit tests only). `common-core` provides a typed API result wrapper and a Retrofit/OkHttp factory. `device-sdk-api` defines the hardware abstraction contract (card reader, printer, scanner) plus the model→vendor resolution registry. `signing-core` implements ECDSA request signing (canonicalize → sign → verify) and an OkHttp interceptor that applies it — the replacement for the old bearer-token `AuthInterceptor`.

**Tech Stack:** Kotlin 2.0.21, JVM toolchain 17, Gradle 8.9, OkHttp 4.12.0, Retrofit 2.11.0 + Gson converter, JUnit 5 (Jupiter), OkHttp MockWebServer for interceptor/network tests.

**Spec:** `docs/EDC_PAYMENT_APP_DESIGN.md`

## Global Constraints

- Kotlin JVM target 17, matching the sibling `tms-agent` repo's JDK 17 requirement (spec §9 item 7 — keep toolchains compatible in case that integration resumes).
- No `@Body: Any` / untyped request-response patterns anywhere — every API surface uses explicit data classes (spec §11).
- Auth to the Front-facing API is digital signature (ECDSA), never a bearer token (spec §2).
- Signing key is hardware-backed (Android Keystore, TEE floor, StrongBox opportunistic) — implemented in the Provisioning plan, not here; this plan only defines the `SigningKeyProvider` contract it fulfills (spec §2).
- Minimize redundant logic; no code duplicated across modules that belongs in one shared place (spec §11).
- These three modules are plain Kotlin/JVM (`kotlin("jvm")`), not Android library modules — only vendor SDK adapters and the `app` module need the Android Gradle plugin.

---

## Task 1: Gradle project scaffold

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: a working root Gradle build that `./gradlew help` succeeds against. Later tasks add `include(":<module>")` lines to `settings.gradle.kts`.

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "mobile-cashup-payment"
```

- [ ] **Step 2: Create root `build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.0.21" apply false
}

allprojects {
    group = "com.cashup"
    version = "0.1.0"
}
```

- [ ] **Step 3: Create `gradle.properties`**

```properties
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2g
org.gradle.parallel=true
```

- [ ] **Step 4: Generate the Gradle wrapper**

Run: `gradle wrapper --gradle-version 8.9`

Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.

- [ ] **Step 5: Verify the root build resolves**

Run: `./gradlew help`
Expected: `BUILD SUCCESSFUL` with no modules listed yet (none included).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle/
git commit -m "chore: scaffold Gradle project"
```

---

## Task 2: `common-core` — API result wrapper and Retrofit factory

**Files:**
- Create: `common-core/build.gradle.kts`
- Create: `common-core/src/main/kotlin/com/cashup/common/network/ApiError.kt`
- Create: `common-core/src/main/kotlin/com/cashup/common/network/ApiResult.kt`
- Create: `common-core/src/main/kotlin/com/cashup/common/network/RetrofitFactory.kt`
- Create: `common-core/src/main/kotlin/com/cashup/common/logging/PaymentLogger.kt`
- Test: `common-core/src/test/kotlin/com/cashup/common/network/ApiResultTest.kt`
- Test: `common-core/src/test/kotlin/com/cashup/common/network/RetrofitFactoryTest.kt`
- Modify: `settings.gradle.kts` — add `include(":common-core")`

**Interfaces:**
- Consumes: nothing.
- Produces: `ApiError(code: String, message: String, httpStatus: Int? = null, cause: Throwable? = null)`; `sealed class ApiResult<T>` with `Success<T>(data: T)` / `Failure(error: ApiError)`, plus `map`, `onSuccess`, `onFailure`; `RetrofitFactory.create(baseUrl: String, interceptors: List<Interceptor> = emptyList(), gson: Gson = Gson(), timeoutSeconds: Long = 15L): Retrofit`; `interface PaymentLogger { debug/warn/error }` and `NoOpPaymentLogger`. Later plans (Provisioning, CDCP, QRIS) build their Retrofit services on top of `RetrofitFactory.create()` and wrap responses in `ApiResult`.

- [ ] **Step 1: Add the module to `settings.gradle.kts`**

```kotlin
include(":common-core")
```

- [ ] **Step 2: Create `common-core/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

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
```

- [ ] **Step 3: Write the failing test for `ApiResult`**

`common-core/src/test/kotlin/com/cashup/common/network/ApiResultTest.kt`:

```kotlin
package com.cashup.common.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApiResultTest {

    @Test
    fun `map transforms success data`() {
        val result: ApiResult<Int> = ApiResult.Success(2)
        val mapped = result.map { it * 10 }
        assertEquals(ApiResult.Success(20), mapped)
    }

    @Test
    fun `map leaves failure untouched`() {
        val error = ApiError(code = "NETWORK", message = "timeout")
        val result: ApiResult<Int> = ApiResult.Failure(error)
        val mapped = result.map { it * 10 }
        assertEquals(ApiResult.Failure(error), mapped)
    }

    @Test
    fun `onSuccess runs action only for success`() {
        var captured = -1
        val result: ApiResult<Int> = ApiResult.Success(7)
        result.onSuccess { captured = it }
        assertEquals(7, captured)
    }

    @Test
    fun `onFailure runs action only for failure`() {
        var captured: ApiError? = null
        val error = ApiError(code = "AUTH", message = "signature invalid")
        val result: ApiResult<Int> = ApiResult.Failure(error)
        result.onFailure { captured = it }
        assertEquals(error, captured)
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :common-core:test --tests "com.cashup.common.network.ApiResultTest"`
Expected: FAIL — `ApiResult`, `ApiError` unresolved references.

- [ ] **Step 5: Implement `ApiError` and `ApiResult`**

`common-core/src/main/kotlin/com/cashup/common/network/ApiError.kt`:

```kotlin
package com.cashup.common.network

data class ApiError(
    val code: String,
    val message: String,
    val httpStatus: Int? = null,
    val cause: Throwable? = null,
)
```

`common-core/src/main/kotlin/com/cashup/common/network/ApiResult.kt`:

```kotlin
package com.cashup.common.network

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): ApiResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onFailure(action: (ApiError) -> Unit): ApiResult<T> {
        if (this is Failure) action(error)
        return this
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :common-core:test --tests "com.cashup.common.network.ApiResultTest"`
Expected: PASS, 4 tests green.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts common-core/build.gradle.kts \
  common-core/src/main/kotlin/com/cashup/common/network/ApiError.kt \
  common-core/src/main/kotlin/com/cashup/common/network/ApiResult.kt \
  common-core/src/test/kotlin/com/cashup/common/network/ApiResultTest.kt
git commit -m "feat(common-core): add typed ApiResult/ApiError"
```

- [ ] **Step 8: Write the failing test for `RetrofitFactory`**

`common-core/src/test/kotlin/com/cashup/common/network/RetrofitFactoryTest.kt`:

```kotlin
package com.cashup.common.network

import okhttp3.Interceptor
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Call
import retrofit2.http.GET

private interface PingService {
    @GET("/ping")
    fun ping(): Call<ResponseBody>
}

class RetrofitFactoryTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `applies every configured interceptor to outgoing requests`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val marker = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("X-Marker", "present")
                .build()
            chain.proceed(request)
        }

        val retrofit = RetrofitFactory.create(
            baseUrl = server.url("/").toString(),
            interceptors = listOf(marker),
        )
        retrofit.create(PingService::class.java).ping().execute()

        val recorded = server.takeRequest()
        assertEquals("present", recorded.getHeader("X-Marker"))
    }
}
```

- [ ] **Step 9: Run the test to verify it fails**

Run: `./gradlew :common-core:test --tests "com.cashup.common.network.RetrofitFactoryTest"`
Expected: FAIL — `RetrofitFactory` unresolved reference.

- [ ] **Step 10: Implement `RetrofitFactory`**

`common-core/src/main/kotlin/com/cashup/common/network/RetrofitFactory.kt`:

```kotlin
package com.cashup.common.network

import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitFactory {

    private const val DEFAULT_TIMEOUT_SECONDS = 15L

    fun create(
        baseUrl: String,
        interceptors: List<Interceptor> = emptyList(),
        gson: Gson = Gson(),
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): Retrofit {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)

        interceptors.forEach { clientBuilder.addInterceptor(it) }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
}
```

- [ ] **Step 11: Run the test to verify it passes**

Run: `./gradlew :common-core:test --tests "com.cashup.common.network.RetrofitFactoryTest"`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add common-core/src/main/kotlin/com/cashup/common/network/RetrofitFactory.kt \
  common-core/src/test/kotlin/com/cashup/common/network/RetrofitFactoryTest.kt
git commit -m "feat(common-core): add RetrofitFactory"
```

- [ ] **Step 13: Add `PaymentLogger` (no dedicated test — a no-branching stub interface + no-op)**

`common-core/src/main/kotlin/com/cashup/common/logging/PaymentLogger.kt`:

```kotlin
package com.cashup.common.logging

interface PaymentLogger {
    fun debug(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

class NoOpPaymentLogger : PaymentLogger {
    override fun debug(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}
```

- [ ] **Step 14: Verify the module still builds**

Run: `./gradlew :common-core:build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 15: Commit**

```bash
git add common-core/src/main/kotlin/com/cashup/common/logging/PaymentLogger.kt
git commit -m "feat(common-core): add PaymentLogger interface"
```

---

## Task 3: `device-sdk-api` — hardware abstraction contract

**Files:**
- Create: `device-sdk-api/build.gradle.kts`
- Create: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/Capability.kt`
- Create: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/CardReader.kt`
- Create: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/Printer.kt`
- Create: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/Scanner.kt`
- Create: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdk.kt`
- Create: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdkRegistry.kt`
- Test: `device-sdk-api/src/test/kotlin/com/cashup/devicesdk/DeviceSdkRegistryTest.kt`
- Modify: `settings.gradle.kts` — add `include(":device-sdk-api")`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class Capability { CARD_READ, PRINT, SCAN_QR }`; `interface CardReader { suspend fun waitForCard(timeoutMillis: Long): CardReadResult; fun cancel() }` with `sealed class CardReadResult { Success(trackData, cardType) / Failure(reason) / Cancelled }`; `interface Printer { fun print(receipt: ReceiptContent): PrintResult }`; `interface Scanner { suspend fun scanQr(timeoutMillis: Long): String? }`; `interface DeviceSdk { vendorId, capabilities, cardReader, printer, scanner, fun supports(Capability): Boolean }`; `object DeviceSdkRegistry { fun register(modelPrefix: String, factory: () -> DeviceSdk); fun resolve(buildModel: String): DeviceSdk?; fun clearForTest() }`. The 9 vendor adapter modules (later plan) each call `DeviceSdkRegistry.register(...)` and implement `DeviceSdk`; `provisioning-core`, `cdcp-core` and `qris-core` consume `DeviceSdk` via `DeviceSdkRegistry.resolve(Build.MODEL)`.

- [ ] **Step 1: Add the module to `settings.gradle.kts`**

```kotlin
include(":device-sdk-api")
```

- [ ] **Step 2: Create `device-sdk-api/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
```

- [ ] **Step 3: Create the capability and hardware-interface files (no branching logic — data/contract only, implemented directly without a failing-test step)**

`device-sdk-api/src/main/kotlin/com/cashup/devicesdk/Capability.kt`:

```kotlin
package com.cashup.devicesdk

enum class Capability {
    CARD_READ,
    PRINT,
    SCAN_QR,
}
```

`device-sdk-api/src/main/kotlin/com/cashup/devicesdk/CardReader.kt`:

```kotlin
package com.cashup.devicesdk

enum class CardType { CHIP, TAP, SWIPE }

sealed class CardReadResult {
    data class Success(val trackData: String, val cardType: CardType) : CardReadResult()
    data class Failure(val reason: String) : CardReadResult()
    data object Cancelled : CardReadResult()
}

interface CardReader {
    suspend fun waitForCard(timeoutMillis: Long): CardReadResult
    fun cancel()
}
```

`device-sdk-api/src/main/kotlin/com/cashup/devicesdk/Printer.kt`:

```kotlin
package com.cashup.devicesdk

data class ReceiptContent(val lines: List<String>)

sealed class PrintResult {
    data object Success : PrintResult()
    data class Failure(val reason: String) : PrintResult()
}

interface Printer {
    fun print(receipt: ReceiptContent): PrintResult
}
```

`device-sdk-api/src/main/kotlin/com/cashup/devicesdk/Scanner.kt`:

```kotlin
package com.cashup.devicesdk

interface Scanner {
    suspend fun scanQr(timeoutMillis: Long): String?
}
```

`device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdk.kt`:

```kotlin
package com.cashup.devicesdk

interface DeviceSdk {
    val vendorId: String
    val capabilities: Set<Capability>
    val cardReader: CardReader?
    val printer: Printer?
    val scanner: Scanner?

    fun supports(capability: Capability): Boolean = capability in capabilities
}
```

- [ ] **Step 4: Verify the module compiles**

Run: `./gradlew :device-sdk-api:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts device-sdk-api/build.gradle.kts \
  device-sdk-api/src/main/kotlin/com/cashup/devicesdk/Capability.kt \
  device-sdk-api/src/main/kotlin/com/cashup/devicesdk/CardReader.kt \
  device-sdk-api/src/main/kotlin/com/cashup/devicesdk/Printer.kt \
  device-sdk-api/src/main/kotlin/com/cashup/devicesdk/Scanner.kt \
  device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdk.kt
git commit -m "feat(device-sdk-api): add hardware abstraction contract"
```

- [ ] **Step 6: Write the failing test for `DeviceSdkRegistry`**

`device-sdk-api/src/test/kotlin/com/cashup/devicesdk/DeviceSdkRegistryTest.kt`:

```kotlin
package com.cashup.devicesdk

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private class FakeDeviceSdk(override val vendorId: String) : DeviceSdk {
    override val capabilities: Set<Capability> = setOf(Capability.CARD_READ)
    override val cardReader: CardReader? = null
    override val printer: Printer? = null
    override val scanner: Scanner? = null
}

class DeviceSdkRegistryTest {

    @AfterEach
    fun tearDown() {
        DeviceSdkRegistry.clearForTest()
    }

    @Test
    fun `resolves the sdk whose model prefix matches`() {
        DeviceSdkRegistry.register("PAX_A920") { FakeDeviceSdk("pax") }

        val resolved = DeviceSdkRegistry.resolve("PAX_A920_PRO")

        assertEquals("pax", resolved?.vendorId)
    }

    @Test
    fun `returns null when no prefix matches`() {
        DeviceSdkRegistry.register("PAX_A920") { FakeDeviceSdk("pax") }

        val resolved = DeviceSdkRegistry.resolve("SUNMI_P2")

        assertNull(resolved)
    }
}
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `./gradlew :device-sdk-api:test --tests "com.cashup.devicesdk.DeviceSdkRegistryTest"`
Expected: FAIL — `DeviceSdkRegistry` unresolved reference.

- [ ] **Step 8: Implement `DeviceSdkRegistry`**

`device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdkRegistry.kt`:

```kotlin
package com.cashup.devicesdk

/**
 * The one place that maps a terminal's `Build.MODEL` string to the vendor
 * [DeviceSdk] factory that knows how to drive it. Each vendor adapter module
 * registers itself here; nothing else in the app resolves hardware by model.
 */
object DeviceSdkRegistry {

    private val factories = mutableMapOf<String, () -> DeviceSdk>()

    fun register(modelPrefix: String, factory: () -> DeviceSdk) {
        factories[modelPrefix] = factory
    }

    fun resolve(buildModel: String): DeviceSdk? {
        val match = factories.entries.firstOrNull { (prefix, _) -> buildModel.startsWith(prefix, ignoreCase = true) }
        return match?.value?.invoke()
    }

    /** Test-only: clears registrations so tests don't leak state into each other. */
    fun clearForTest() {
        factories.clear()
    }
}
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `./gradlew :device-sdk-api:test --tests "com.cashup.devicesdk.DeviceSdkRegistryTest"`
Expected: PASS, 2 tests green.

- [ ] **Step 10: Commit**

```bash
git add device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdkRegistry.kt \
  device-sdk-api/src/test/kotlin/com/cashup/devicesdk/DeviceSdkRegistryTest.kt
git commit -m "feat(device-sdk-api): add DeviceSdkRegistry"
```

---

## Task 4: `signing-core` — ECDSA request signing

**Files:**
- Create: `signing-core/build.gradle.kts`
- Create: `signing-core/src/main/kotlin/com/cashup/signing/SigningKeyProvider.kt`
- Create: `signing-core/src/main/kotlin/com/cashup/signing/RequestSigner.kt`
- Create: `signing-core/src/main/kotlin/com/cashup/signing/SigningInterceptor.kt`
- Test: `signing-core/src/test/kotlin/com/cashup/signing/RequestSignerTest.kt`
- Test: `signing-core/src/test/kotlin/com/cashup/signing/SigningInterceptorTest.kt`
- Modify: `settings.gradle.kts` — add `include(":signing-core")`

**Interfaces:**
- Consumes: nothing from Tasks 2–3 (deliberately independent — `signing-core` doesn't need `ApiResult` or `DeviceSdk`).
- Produces: `fun interface SigningKeyProvider { fun currentKeyPair(): KeyPair? }`; `class RequestSigner { fun canonicalize(method, path, timestampMillis, nonce, body: ByteArray): ByteArray; fun sign(canonicalBytes, privateKey): String; fun verify(canonicalBytes, signatureBase64, publicKey): Boolean }`; `class SigningInterceptor(keyProvider: SigningKeyProvider, signer: RequestSigner = RequestSigner(), ...) : Interceptor`. The Provisioning plan implements `SigningKeyProvider` backed by Android Keystore; `cdcp-core`/`qris-core` attach `SigningInterceptor` to their Retrofit clients built via `common-core`'s `RetrofitFactory`.

> **Algorithm decision (spec §9 item 1 is still open with the backend team):** this plan implements **ECDSA over the P-256 curve (`SHA256withECDSA`)** as the concrete v1 signing scheme — a real "digital signature" (asymmetric, backend verifies with the device's registered public key) rather than an HMAC shared secret, matching the spec's explicit wording. If backend alignment lands on a different algorithm, only `RequestSigner`'s internals change — `SigningKeyProvider` and `SigningInterceptor`'s public shape stay the same.

- [ ] **Step 1: Add the module to `settings.gradle.kts`**

```kotlin
include(":signing-core")
```

- [ ] **Step 2: Create `signing-core/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

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
```

- [ ] **Step 3: Create `SigningKeyProvider` (no test — a single-method functional interface with no logic)**

`signing-core/src/main/kotlin/com/cashup/signing/SigningKeyProvider.kt`:

```kotlin
package com.cashup.signing

import java.security.KeyPair

/**
 * Supplies the EC key pair used to sign outgoing requests. The private key
 * is expected to live in hardware-backed storage (Android Keystore, TEE
 * floor, StrongBox opportunistic — see the Provisioning plan) and never
 * leaves it; this interface only exposes the [KeyPair] handle needed to
 * sign and verify, not raw key material.
 *
 * Returns null before the device has been provisioned.
 */
fun interface SigningKeyProvider {
    fun currentKeyPair(): KeyPair?
}
```

- [ ] **Step 4: Write the failing test for `RequestSigner`**

`signing-core/src/test/kotlin/com/cashup/signing/RequestSignerTest.kt`:

```kotlin
package com.cashup.signing

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class RequestSignerTest {

    private val signer = RequestSigner()

    private fun generateKeyPair() =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    @Test
    fun `a signature verifies against the same canonical bytes and key pair`() {
        val keyPair = generateKeyPair()
        val canonical = signer.canonicalize(
            method = "POST",
            path = "/cdcp/sale",
            timestampMillis = 1_726_300_000_000,
            nonce = "abc123",
            body = """{"amount":"10000"}""".toByteArray(),
        )

        val signature = signer.sign(canonical, keyPair.private)

        assertTrue(signer.verify(canonical, signature, keyPair.public))
    }

    @Test
    fun `verification fails when the body changes after signing`() {
        val keyPair = generateKeyPair()
        val signedCanonical = signer.canonicalize("POST", "/cdcp/sale", 1_726_300_000_000, "abc123", """{"amount":"10000"}""".toByteArray())
        val signature = signer.sign(signedCanonical, keyPair.private)

        val tamperedCanonical = signer.canonicalize("POST", "/cdcp/sale", 1_726_300_000_000, "abc123", """{"amount":"99999999"}""".toByteArray())

        assertFalse(signer.verify(tamperedCanonical, signature, keyPair.public))
    }

    @Test
    fun `verification fails against a different key pair`() {
        val keyPair = generateKeyPair()
        val otherKeyPair = generateKeyPair()
        val canonical = signer.canonicalize("GET", "/cdcp/status/1", 1_726_300_000_000, "xyz", ByteArray(0))
        val signature = signer.sign(canonical, keyPair.private)

        assertFalse(signer.verify(canonical, signature, otherKeyPair.public))
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./gradlew :signing-core:test --tests "com.cashup.signing.RequestSignerTest"`
Expected: FAIL — `RequestSigner` unresolved reference.

- [ ] **Step 6: Implement `RequestSigner`**

`signing-core/src/main/kotlin/com/cashup/signing/RequestSigner.kt`:

```kotlin
package com.cashup.signing

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Canonicalizes an outgoing request and produces an ECDSA signature over it.
 * The canonical form is deliberately simple and stable: method, path,
 * timestamp, nonce and a hash of the body, newline-separated. Byte-for-byte
 * stability matters here — reordering fields later invalidates every
 * signature already accepted by the backend.
 */
class RequestSigner {

    fun canonicalize(method: String, path: String, timestampMillis: Long, nonce: String, body: ByteArray): ByteArray {
        val bodyHash = sha256(body)
        return listOf(
            method.uppercase(),
            path,
            timestampMillis.toString(),
            nonce,
            Base64.getEncoder().encodeToString(bodyHash),
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    fun sign(canonicalBytes: ByteArray, privateKey: PrivateKey): String {
        val signature = Signature.getInstance(ALGORITHM)
        signature.initSign(privateKey)
        signature.update(canonicalBytes)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    fun verify(canonicalBytes: ByteArray, signatureBase64: String, publicKey: PublicKey): Boolean {
        val signature = Signature.getInstance(ALGORITHM)
        signature.initVerify(publicKey)
        signature.update(canonicalBytes)
        return signature.verify(Base64.getDecoder().decode(signatureBase64))
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        const val ALGORITHM = "SHA256withECDSA"
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :signing-core:test --tests "com.cashup.signing.RequestSignerTest"`
Expected: PASS, 3 tests green.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts signing-core/build.gradle.kts \
  signing-core/src/main/kotlin/com/cashup/signing/SigningKeyProvider.kt \
  signing-core/src/main/kotlin/com/cashup/signing/RequestSigner.kt \
  signing-core/src/test/kotlin/com/cashup/signing/RequestSignerTest.kt
git commit -m "feat(signing-core): add ECDSA RequestSigner"
```

- [ ] **Step 9: Write the failing test for `SigningInterceptor`**

`signing-core/src/test/kotlin/com/cashup/signing/SigningInterceptorTest.kt`:

```kotlin
package com.cashup.signing

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class SigningInterceptorTest {

    private lateinit var server: MockWebServer
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `adds a verifiable signature header to every request`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val keyProvider = SigningKeyProvider { keyPair }
        val client = OkHttpClient.Builder()
            .addInterceptor(SigningInterceptor(keyProvider))
            .build()

        client.newCall(Request.Builder().url(server.url("/cdcp/sale")).build()).execute()

        val recorded = server.takeRequest()
        val signature = recorded.getHeader("X-Signature")!!
        val timestamp = recorded.getHeader("X-Timestamp")!!.toLong()
        val nonce = recorded.getHeader("X-Nonce")!!

        val signer = RequestSigner()
        val canonical = signer.canonicalize("GET", "/cdcp/sale", timestamp, nonce, ByteArray(0))
        assertTrue(signer.verify(canonical, signature, keyPair.public))
    }

    @Test
    fun `refuses to send a request when the device is not provisioned`() {
        val unprovisioned = SigningKeyProvider { null }
        val client = OkHttpClient.Builder()
            .addInterceptor(SigningInterceptor(unprovisioned))
            .build()

        assertThrows(IllegalStateException::class.java) {
            client.newCall(Request.Builder().url(server.url("/cdcp/sale")).build()).execute()
        }
    }
}
```

- [ ] **Step 10: Run the test to verify it fails**

Run: `./gradlew :signing-core:test --tests "com.cashup.signing.SigningInterceptorTest"`
Expected: FAIL — `SigningInterceptor` unresolved reference.

- [ ] **Step 11: Implement `SigningInterceptor`**

`signing-core/src/main/kotlin/com/cashup/signing/SigningInterceptor.kt`:

```kotlin
package com.cashup.signing

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.util.UUID

/**
 * Signs every request that passes through it. Attach this only to clients
 * that talk to the Front-facing API after provisioning — provisioning's own
 * bootstrap call uses the temporary admin JWT instead (Provisioning plan),
 * never this interceptor.
 *
 * Fails loudly (throws) rather than sending an unsigned request when no key
 * is available yet: an unsigned request reaching this interceptor is a
 * wiring bug, not a recoverable state.
 */
class SigningInterceptor(
    private val keyProvider: SigningKeyProvider,
    private val signer: RequestSigner = RequestSigner(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonceFactory: () -> String = { UUID.randomUUID().toString() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val keyPair = keyProvider.currentKeyPair()
            ?: error("SigningInterceptor invoked before the device is provisioned")

        val request = chain.request()
        val bodyBytes = request.body?.let { body ->
            Buffer().also { body.writeTo(it) }.readByteArray()
        } ?: ByteArray(0)

        val timestamp = clock()
        val nonce = nonceFactory()
        val canonical = signer.canonicalize(
            method = request.method,
            path = request.url.encodedPath,
            timestampMillis = timestamp,
            nonce = nonce,
            body = bodyBytes,
        )
        val signature = signer.sign(canonical, keyPair.private)

        val signedRequest = request.newBuilder()
            .addHeader("X-Signature", signature)
            .addHeader("X-Timestamp", timestamp.toString())
            .addHeader("X-Nonce", nonce)
            .build()

        return chain.proceed(signedRequest)
    }
}
```

- [ ] **Step 12: Run the test to verify it passes**

Run: `./gradlew :signing-core:test --tests "com.cashup.signing.SigningInterceptorTest"`
Expected: PASS, 2 tests green.

- [ ] **Step 13: Run the full test suite for all three modules**

Run: `./gradlew :common-core:test :device-sdk-api:test :signing-core:test`
Expected: `BUILD SUCCESSFUL`, all tests green (4 `ApiResultTest` + 1 `RetrofitFactoryTest` + 2 `DeviceSdkRegistryTest` + 3 `RequestSignerTest` + 2 `SigningInterceptorTest` = 12 tests across the three modules).

- [ ] **Step 14: Commit**

```bash
git add signing-core/src/main/kotlin/com/cashup/signing/SigningInterceptor.kt \
  signing-core/src/test/kotlin/com/cashup/signing/SigningInterceptorTest.kt
git commit -m "feat(signing-core): add SigningInterceptor"
```

# Standard Log Broker (RabbitMQ `logs.persist`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Task 10 is NOT a normal subagent task.** It requires the real RabbitMQ credentials, which must never appear in this plan file, in a subagent's dispatch prompt, or in any git-tracked file. The orchestrating session performs Task 10 directly with the human, exactly as every other task before it stayed credential-free by design (see spec §7).

**Goal:** Publish structured, HMAC-signed transaction logs from `mobile-cashup-payment` to the RabbitMQ `logs.persist` queue (exchange `logs`, routing key `logs.service`) during real card-payment transactions, matching the contract already consumed by the backend from `mobile-apps-cashlez-softpos`.

**Architecture:** Two new pure-contract modules — `:secure-storage-core` (EncryptedSharedPreferences/Android Keystore wrapper) and `:broker-core` (generic `BrokerClient` transport, first implementation `AmqpBrokerClient` for RabbitMQ). `:common-core` gains a `logging.standard` package (`StandardLogPayload`/`StandardLogHmacSigner`/`StandardLogSanitizer`/`StandardLogPublisher`) that depends on `:broker-core`. `:app` resolves broker credentials from XOR-obfuscated constants (never a bare string literal), caches them in `:secure-storage-core`, and wires `CardPaymentDependencies.logTransaction(...)` so `:feature-card-payment`'s `CardPaymentViewModel` can log at each transaction stage without knowing about brokers at all.

**Tech Stack:** Kotlin, `com.rabbitmq:amqp-client:5.35.0`, `androidx.security:security-crypto:1.1.0-alpha06`, Gson (already transitive via `converter-gson` in `:common-core`), JUnit 5 (Jupiter) for pure-JVM modules, JUnit 4 + Robolectric for Android-library modules (matches each tier's existing convention in this repo), MockK for `AmqpBrokerClient`.

**Spec:** `docs/superpowers/specs/2026-09-23-standard-log-broker-design.md`

## Global Constraints

- Every `kotlin("jvm")` module that ships into `:app` (minSdk 23, no core library desugoring) must avoid `java.time.*`, `java.util.Base64`, `java.util.function.*`, `Optional`, and default/static interface methods — this applies to `:broker-core` and the new `:common-core` code. Use `SimpleDateFormat`/`TimeZone`, not `java.time`.
- `./gradlew checkModuleBoundaries` must pass after every task that changes a module's project dependencies — update `gradle/module-boundaries.gradle.kts` in the SAME task that introduces the dependency, never as an afterthought.
- No RabbitMQ credential, HMAC secret, or any other secret literal may be written to any file this plan or its tasks create or modify, except the final, deliberately-separated Task 10 step — and even there, never as a bare string literal (always XOR-obfuscated, see Task 7/8).
- Port algorithm-critical code (`StandardLogHmacSigner`'s hex encoding, `StandardLogSanitizer`'s key list) byte-for-byte from the proven implementation in `mobile-apps-cashlez-softpos` (`common-core/src/main/java/com/common/core/logger/StandardLogPayload.kt` in that project) — do not "improve" the hex-encoding or redaction logic, the backend depends on this exact format.
- `com.rabbitmq:amqp-client` TLS setup must use explicit `SSLContext.getInstance("TLSv1.2")` + `enableHostnameVerification()`, never the no-argument `useSslProtocol()` overload (that was a trust-everything MITM hole already fixed once in the origin project — do not reintroduce it here).

---

## Task 1: `:secure-storage-core` module — `EncryptedPrefs`

**Files:**
- Create: `settings.gradle.kts` (add `include(":secure-storage-core")`)
- Create: `secure-storage-core/build.gradle.kts`
- Create: `secure-storage-core/src/main/kotlin/com/cashup/securestorage/EncryptedPrefs.kt`
- Create: `secure-storage-core/src/test/kotlin/com/cashup/securestorage/EncryptedPrefsTest.kt`
- Modify: `gradle/module-boundaries.gradle.kts:10-14` (add `":secure-storage-core" to emptySet(),`)

**Interfaces:**
- Produces: `object EncryptedPrefs { fun open(context: Context, fileName: String): SharedPreferences }`, package `com.cashup.securestorage`. Later tasks (Task 2, Task 8) call `EncryptedPrefs.open(context, fileName)`.

- [ ] **Step 1: Add the module to `settings.gradle.kts`**

Open `settings.gradle.kts` and add a new include line next to the others:

```kotlin
include(":secure-storage-core")
include(":device-sdk-api")
```

(insert `include(":secure-storage-core")` immediately before the existing `include(":device-sdk-api")` line — exact position doesn't matter, just keep every `include(...)` in the block.)

- [ ] **Step 2: Create `secure-storage-core/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.securestorage"
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
        // Sama seperti provisioning-core/build.gradle.kts -- EncryptedSharedPreferences
        // butuh Robolectric asli, bukan stub android.jar yang isReturnDefaultValues.
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
```

- [ ] **Step 3: Write `EncryptedPrefs.kt`**

```kotlin
package com.cashup.securestorage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * `SharedPreferences` ter-enkripsi AES-256-GCM dengan master key yang dipegang
 * Android Keystore. Dipakai oleh module mana pun yang butuh menyimpan sesuatu
 * yang tidak boleh terbaca dari file SharedPreferences biasa di disk.
 */
object EncryptedPrefs {
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

- [ ] **Step 4: Write the failing test**

```kotlin
package com.cashup.securestorage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EncryptedPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `round-trips a stored value`() {
        val prefs = EncryptedPrefs.open(context, "round_trip_test")
        prefs.edit().putString("key", "super-secret-value").commit()

        assertEquals("super-secret-value", prefs.getString("key", null))
    }

    @Test
    fun `value is not stored as plaintext on disk`() {
        val prefs = EncryptedPrefs.open(context, "plaintext_check_test")
        prefs.edit().putString("key", "super-secret-value").commit()

        val prefsFile = File(context.filesDir.parentFile, "shared_prefs/plaintext_check_test.xml")
        val raw = prefsFile.readText()

        assertFalse(raw.contains("super-secret-value"))
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :secure-storage-core:testDebugUnitTest`
Expected: both tests PASS. (If it fails with a Keystore/Robolectric shadow error, confirm `org.robolectric:robolectric:4.12.2` and `androidx.test.ext:junit:1.1.5` are on the test classpath and re-run — this exact combination already works for `EncryptedSharedPreferences` in `:provisioning-core`.)

- [ ] **Step 6: Register the module boundary**

In `gradle/module-boundaries.gradle.kts`, inside `allowedProjectDeps`, add a new entry next to the other pure-contract modules:

```kotlin
val allowedProjectDeps: Map<String, Set<String>> = mapOf(
    // Kontrak murni -- tanpa dependency internal apa pun.
    ":common-core" to emptySet(),
    ":device-sdk-api" to emptySet(),
    ":signing-core" to emptySet(),
    ":secure-storage-core" to emptySet(),
    ...
```

- [ ] **Step 7: Verify boundaries and commit**

Run: `./gradlew checkModuleBoundaries`
Expected: `Module boundaries OK: N modules checked.` (N one higher than before).

```bash
git add settings.gradle.kts secure-storage-core gradle/module-boundaries.gradle.kts
git commit -m "feat(secure-storage-core): add EncryptedPrefs (EncryptedSharedPreferences wrapper)"
```

---

## Task 2: Migrate `:provisioning-core` to `:secure-storage-core`

**Files:**
- Modify: `provisioning-core/build.gradle.kts`
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/crypto/Ed25519KeyStore.kt:8,38`
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/crypto/RsaKeyStore.kt:10,87`
- Modify: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/local/ProvisioningStateStore.kt:1-6,39`
- Delete: `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/local/SecurePrefs.kt`
- Modify: `gradle/module-boundaries.gradle.kts` (`:provisioning-core` entry)

**Interfaces:**
- Consumes: `com.cashup.securestorage.EncryptedPrefs.open(context, fileName)` from Task 1.

- [ ] **Step 1: Add the `:secure-storage-core` dependency**

In `provisioning-core/build.gradle.kts`, replace:

```kotlin
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

with:

```kotlin
    implementation(project(":secure-storage-core"))
```

(`provisioning-core` no longer touches `EncryptedSharedPreferences`/`MasterKey` directly once this migration is done, so the direct `security-crypto` dependency is now unused there — remove it, don't leave it alongside.)

- [ ] **Step 2: Update `Ed25519KeyStore.kt`**

Line 8, replace:

```kotlin
import com.cashup.provisioning.data.local.SecurePrefs
```

with:

```kotlin
import com.cashup.securestorage.EncryptedPrefs
```

Line 38, replace:

```kotlin
    private val prefs by lazy { SecurePrefs.open(context.applicationContext, PREFS) }
```

with:

```kotlin
    private val prefs by lazy { EncryptedPrefs.open(context.applicationContext, PREFS) }
```

- [ ] **Step 3: Update `RsaKeyStore.kt`**

Line 10, replace:

```kotlin
import com.cashup.provisioning.data.local.SecurePrefs
```

with:

```kotlin
import com.cashup.securestorage.EncryptedPrefs
```

Line 87, replace:

```kotlin
    private val prefs by lazy { SecurePrefs.open(appContext, PREFS) }
```

with:

```kotlin
    private val prefs by lazy { EncryptedPrefs.open(appContext, PREFS) }
```

- [ ] **Step 4: Update `ProvisioningStateStore.kt`**

Add an import — it previously relied on `SecurePrefs` being in the same package (`com.cashup.provisioning.data.local`), which is no longer true. After the existing imports (line 6, `import com.google.gson.JsonSyntaxException`), add:

```kotlin
import com.cashup.securestorage.EncryptedPrefs
```

Line 39, replace:

```kotlin
    private val prefs by lazy { SecurePrefs.open(context.applicationContext, PREFS) }
```

with:

```kotlin
    private val prefs by lazy { EncryptedPrefs.open(context.applicationContext, PREFS) }
```

- [ ] **Step 5: Delete the old `SecurePrefs.kt`**

```bash
git rm provisioning-core/src/main/kotlin/com/cashup/provisioning/data/local/SecurePrefs.kt
```

- [ ] **Step 6: Update the module boundary**

In `gradle/module-boundaries.gradle.kts`, change:

```kotlin
    ":provisioning-core" to setOf(":common-core", ":device-sdk-api", ":signing-core"),
```

to:

```kotlin
    ":provisioning-core" to setOf(":common-core", ":device-sdk-api", ":signing-core", ":secure-storage-core"),
```

- [ ] **Step 7: Run the existing provisioning-core test suite**

Run: `./gradlew :provisioning-core:testDebugUnitTest`
Expected: all existing tests PASS unchanged (this is a pure rename/relocation, no behavior change — `RsaKeyLocationTest`, `PackageUnwrapperTest`, `ProvisionDeviceUseCaseTest`, `SelfSignedCertificateTest`, `Tr34...` etc. must all stay green).

- [ ] **Step 8: Verify boundaries, full module compiles, and commit**

Run: `./gradlew checkModuleBoundaries :provisioning-core:compileDebugKotlin`
Expected: both succeed.

```bash
git add provisioning-core gradle/module-boundaries.gradle.kts
git commit -m "refactor(provisioning-core): use shared EncryptedPrefs from :secure-storage-core"
```

---

## Task 3: `:broker-core` module scaffold — `BrokerAuth` + `BrokerClient`

**Files:**
- Modify: `settings.gradle.kts` (add `include(":broker-core")`)
- Create: `broker-core/build.gradle.kts`
- Create: `broker-core/src/main/kotlin/com/cashup/broker/BrokerAuth.kt`
- Create: `broker-core/src/main/kotlin/com/cashup/broker/BrokerClient.kt`
- Modify: `gradle/module-boundaries.gradle.kts` (add `":broker-core" to emptySet(),`)

**Interfaces:**
- Produces: `data class BrokerAuth(host, port, username, password, virtualHost, useTls)` and `interface BrokerClient { val isConnected: Boolean; suspend fun connect(): Boolean; suspend fun publish(exchange: String, routingKey: String, payload: ByteArray): Boolean; fun disconnect() }`, both package `com.cashup.broker`. Task 4 implements `BrokerClient`. Task 6 (`:common-core`) consumes `BrokerClient` as a parameter type.

- [ ] **Step 1: Add the module to `settings.gradle.kts`**

```kotlin
include(":broker-core")
```

(add next to `include(":secure-storage-core")` from Task 1.)

- [ ] **Step 2: Create `broker-core/build.gradle.kts`**

```kotlin
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

plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    implementation("com.rabbitmq:amqp-client:5.35.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
```

- [ ] **Step 3: Write `BrokerAuth.kt`**

```kotlin
package com.cashup.broker

data class BrokerAuth(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val virtualHost: String = "/",
    val useTls: Boolean = false,
)
```

- [ ] **Step 4: Write `BrokerClient.kt`**

```kotlin
package com.cashup.broker

/**
 * Transport pub/sub generik. `exchange`/`routingKey` cocok untuk model AMQP
 * (exchange+routing key) MAUPUN MQTT (exchange diabaikan, routingKey = topic)
 * -- implementasi MQTT payment-status nanti bisa pakai kontrak yang sama
 * tanpa mengubah consumer manapun.
 */
interface BrokerClient {
    val isConnected: Boolean
    suspend fun connect(): Boolean
    suspend fun publish(exchange: String, routingKey: String, payload: ByteArray): Boolean
    fun disconnect()
}
```

- [ ] **Step 5: Compile to confirm the scaffold is valid**

Run: `./gradlew :broker-core:compileKotlin`
Expected: SUCCESS (no tests yet, this task has no behavior to test — `BrokerAuth`/`BrokerClient` are a pure data class and interface).

- [ ] **Step 6: Register the module boundary**

In `gradle/module-boundaries.gradle.kts`:

```kotlin
    ":secure-storage-core" to emptySet(),
    ":broker-core" to emptySet(),
```

- [ ] **Step 7: Verify boundaries and commit**

Run: `./gradlew checkModuleBoundaries`
Expected: `Module boundaries OK: N modules checked.`

```bash
git add settings.gradle.kts broker-core gradle/module-boundaries.gradle.kts
git commit -m "feat(broker-core): scaffold module with BrokerAuth/BrokerClient contract"
```

---

## Task 4: `AmqpBrokerClient` (RabbitMQ implementation)

**Files:**
- Create: `broker-core/src/main/kotlin/com/cashup/broker/amqp/AmqpBrokerClient.kt`
- Test: `broker-core/src/test/kotlin/com/cashup/broker/amqp/AmqpBrokerClientTest.kt`

**Interfaces:**
- Consumes: `BrokerAuth`, `BrokerClient` from Task 3.
- Produces: `class AmqpBrokerClient(auth: BrokerAuth, connectionFactory: ConnectionFactory = ConnectionFactory()) : BrokerClient`. Task 8 (`:app`) constructs this directly.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.cashup.broker.amqp

import com.cashup.broker.BrokerAuth
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.net.ssl.SSLContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmqpBrokerClientTest {

    @Test
    fun `connect enables TLS with hostname verification when auth requests it`() = runTest {
        val factory = mockk<ConnectionFactory>(relaxed = true)
        val connection = mockk<Connection>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        every { factory.newConnection() } returns connection
        every { connection.createChannel() } returns channel
        every { connection.isOpen } returns true

        val auth = BrokerAuth(
            host = "broker.example.com", port = 5671, username = "user",
            password = "pass", virtualHost = "vhost", useTls = true,
        )
        val client = AmqpBrokerClient(auth, factory)

        val connected = client.connect()

        assertTrue(connected)
        assertTrue(client.isConnected)
        verify { factory.useSslProtocol(any<SSLContext>()) }
        verify { factory.enableHostnameVerification() }
        verify { factory.isAutomaticRecoveryEnabled = true }
    }

    @Test
    fun `connect skips TLS setup when auth does not request it`() = runTest {
        val factory = mockk<ConnectionFactory>(relaxed = true)
        every { factory.newConnection() } returns mockk(relaxed = true)

        val auth = BrokerAuth(host = "broker.example.com", port = 5672, useTls = false)
        AmqpBrokerClient(auth, factory).connect()

        verify(exactly = 0) { factory.useSslProtocol(any()) }
        verify(exactly = 0) { factory.enableHostnameVerification() }
    }

    @Test
    fun `connect returns false and stays disconnected when the factory throws`() = runTest {
        val factory = mockk<ConnectionFactory>(relaxed = true)
        every { factory.newConnection() } throws java.io.IOException("refused")

        val client = AmqpBrokerClient(BrokerAuth("h", 5672), factory)

        assertFalse(client.connect())
        assertFalse(client.isConnected)
    }

    @Test
    fun `publish sends the payload to the given exchange and routing key`() = runTest {
        val factory = mockk<ConnectionFactory>(relaxed = true)
        val connection = mockk<Connection>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        every { factory.newConnection() } returns connection
        every { connection.createChannel() } returns channel

        val client = AmqpBrokerClient(BrokerAuth("h", 5672), factory)
        client.connect()
        val payload = "hello".toByteArray()

        val result = client.publish("logs", "logs.service", payload)

        assertTrue(result)
        verify { channel.basicPublish("logs", "logs.service", null, payload) }
    }

    @Test
    fun `publish returns false when not connected`() = runTest {
        val client = AmqpBrokerClient(BrokerAuth("h", 5672), mockk(relaxed = true))

        val result = client.publish("logs", "logs.service", "x".toByteArray())

        assertFalse(result)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :broker-core:test --tests "com.cashup.broker.amqp.AmqpBrokerClientTest"`
Expected: FAIL with "unresolved reference: AmqpBrokerClient" (class doesn't exist yet).

- [ ] **Step 3: Write `AmqpBrokerClient.kt`**

```kotlin
package com.cashup.broker.amqp

import com.cashup.broker.BrokerAuth
import com.cashup.broker.BrokerClient
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementasi [BrokerClient] untuk RabbitMQ/AMQP. TLS eksplisit
 * `TLSv1.2` + hostname verification -- JANGAN ganti ke `useSslProtocol()`
 * tanpa argumen, itu trust-everything dan sudah pernah jadi temuan security
 * di project asal (lihat spec §, Global Constraints di plan ini).
 */
class AmqpBrokerClient(
    private val auth: BrokerAuth,
    private val connectionFactory: ConnectionFactory = ConnectionFactory(),
) : BrokerClient {

    private var connection: Connection? = null
    private var channel: Channel? = null

    override val isConnected: Boolean
        get() = connection?.isOpen == true

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            connectionFactory.host = auth.host
            connectionFactory.port = auth.port
            connectionFactory.virtualHost = auth.virtualHost
            auth.username?.let { connectionFactory.username = it }
            auth.password?.let { connectionFactory.password = it }
            connectionFactory.isAutomaticRecoveryEnabled = true
            if (auth.useTls) {
                val sslContext = SSLContext.getInstance("TLSv1.2")
                sslContext.init(null, null, null)
                connectionFactory.useSslProtocol(sslContext)
                connectionFactory.enableHostnameVerification()
            }
            connection = connectionFactory.newConnection()
            channel = connection?.createChannel()
        }.isSuccess
    }

    override suspend fun publish(exchange: String, routingKey: String, payload: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val activeChannel = channel ?: return@withContext false
            runCatching { activeChannel.basicPublish(exchange, routingKey, null, payload) }.isSuccess
        }

    override fun disconnect() {
        runCatching { channel?.close() }
        runCatching { connection?.close() }
        channel = null
        connection = null
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :broker-core:test --tests "com.cashup.broker.amqp.AmqpBrokerClientTest"`
Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add broker-core/src/main/kotlin/com/cashup/broker/amqp broker-core/src/test/kotlin/com/cashup/broker/amqp
git commit -m "feat(broker-core): implement AmqpBrokerClient for RabbitMQ"
```

---

## Task 5: `:common-core` — StandardLog pure pieces

**Files:**
- Create: `common-core/src/main/kotlin/com/cashup/common/logging/standard/StandardLogSanitizer.kt`
- Create: `common-core/src/main/kotlin/com/cashup/common/logging/standard/StandardLogHmacSigner.kt`
- Create: `common-core/src/main/kotlin/com/cashup/common/logging/standard/StandardLogTimestamp.kt`
- Create: `common-core/src/main/kotlin/com/cashup/common/logging/standard/CorrelationIdGenerator.kt`
- Create: `common-core/src/main/kotlin/com/cashup/common/logging/standard/LocalNetwork.kt`
- Create: `common-core/src/main/kotlin/com/cashup/common/logging/standard/StandardLogPayload.kt`
- Test: `common-core/src/test/kotlin/com/cashup/common/logging/standard/StandardLogSanitizerTest.kt`
- Test: `common-core/src/test/kotlin/com/cashup/common/logging/standard/StandardLogHmacSignerTest.kt`
- Test: `common-core/src/test/kotlin/com/cashup/common/logging/standard/StandardLogPayloadTest.kt`

**Interfaces:**
- Produces: `StandardLogPayload(correlationId, timestamp, source, deviceId, merchantId, ipAddress, target, service, processDescription, httpHeader, jsonBody, hmac).signed(secretKey): StandardLogPayload` and `.toJson(): String`; `CorrelationIdGenerator.generate(timestamp: Long = System.currentTimeMillis()): String`. Task 6's `StandardLogPublisher` consumes both.

- [ ] **Step 1: Write `StandardLogSanitizer.kt`**

```kotlin
package com.cashup.common.logging.standard

/** Daftar key & logika redaksi identik dengan mobile-apps-cashlez-softpos -- jangan ubah. */
object StandardLogSanitizer {
    private val sensitiveKeys = setOf(
        "authorization", "token", "accessToken", "refreshToken", "password",
        "pin", "cardNumber", "pan", "track2", "track2Data", "iccData",
    )

    fun sanitize(data: Map<String, Any?>): Map<String, Any?> {
        return data.mapValues { (key, value) ->
            when {
                sensitiveKeys.any { it.equals(key, ignoreCase = true) } -> "****"
                value is Map<*, *> -> sanitize(value.entries.associate { it.key.toString() to it.value })
                value is List<*> -> value.map { item ->
                    if (item is Map<*, *>) sanitize(item.entries.associate { it.key.toString() to it.value }) else item
                }
                else -> value
            }
        }
    }
}
```

- [ ] **Step 2: Write the failing sanitizer test**

```kotlin
package com.cashup.common.logging.standard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StandardLogSanitizerTest {

    @Test
    fun `redacts sensitive top-level fields`() {
        val result = StandardLogSanitizer.sanitize(mapOf("pin" to "1234", "amount" to 10000))
        assertEquals("****", result["pin"])
        assertEquals(10000, result["amount"])
    }

    @Test
    fun `redaction is case-insensitive`() {
        val result = StandardLogSanitizer.sanitize(mapOf("CardNumber" to "4111111111111111"))
        assertEquals("****", result["CardNumber"])
    }

    @Test
    fun `redacts sensitive fields nested inside maps`() {
        val input = mapOf("card" to mapOf("pan" to "4111111111111111", "last4" to "1111"))
        val result = StandardLogSanitizer.sanitize(input)
        val nested = result["card"] as Map<*, *>
        assertEquals("****", nested["pan"])
        assertEquals("1111", nested["last4"])
    }

    @Test
    fun `redacts sensitive fields nested inside lists of maps`() {
        val input = mapOf("cards" to listOf(mapOf("track2" to "secret-track-data")))
        val result = StandardLogSanitizer.sanitize(input)
        val firstEntry = (result["cards"] as List<*>)[0] as Map<*, *>
        assertEquals("****", firstEntry["track2"])
    }
}
```

Run: `./gradlew :common-core:test --tests "com.cashup.common.logging.standard.StandardLogSanitizerTest"`
Expected: since `StandardLogSanitizer` was already written in Step 1, this should PASS immediately — confirm it does before moving on.

- [ ] **Step 3: Write `StandardLogHmacSigner.kt`**

```kotlin
package com.cashup.common.logging.standard

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Backend spec: HMAC-SHA256("{correlationId}.{service}.", key=secretKey), hex-encoded. Port byte-for-byte, jangan diubah. */
object StandardLogHmacSigner {
    fun sign(correlationId: String, service: String, secretKey: String): String {
        val signingString = "$correlationId.$service."
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val hashBytes = mac.doFinal(signingString.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Write the failing signer test**

```kotlin
package com.cashup.common.logging.standard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class StandardLogHmacSignerTest {

    @Test
    fun `matches known HMAC-SHA256 vector`() {
        // Vektor dihitung independen lewat Python hmac/hashlib:
        // hmac.new(b"test-secret", b"1234-1700000000000.sale/sale_trx_start.", hashlib.sha256).hexdigest()
        val signature = StandardLogHmacSigner.sign(
            correlationId = "1234-1700000000000",
            service = "sale/sale_trx_start",
            secretKey = "test-secret",
        )
        assertEquals("3c5cd8ea657286235940a520d88cc5a8b9fe0fbe62824ea0c200a629efda8ef2", signature)
    }

    @Test
    fun `different secret keys produce different signatures`() {
        val a = StandardLogHmacSigner.sign("c-1", "svc", "secret-a")
        val b = StandardLogHmacSigner.sign("c-1", "svc", "secret-b")
        assertNotEquals(a, b)
    }

    @Test
    fun `is deterministic for the same input`() {
        val a = StandardLogHmacSigner.sign("c-1", "svc", "secret")
        val b = StandardLogHmacSigner.sign("c-1", "svc", "secret")
        assertEquals(a, b)
    }
}
```

Run: `./gradlew :common-core:test --tests "com.cashup.common.logging.standard.StandardLogHmacSignerTest"`
Expected: all 3 PASS (implementation was already written in Step 3; this step confirms the known vector actually matches).

- [ ] **Step 5: Write `StandardLogTimestamp.kt`, `CorrelationIdGenerator.kt`, `LocalNetwork.kt`**

```kotlin
package com.cashup.common.logging.standard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** java.text, bukan java.time -- lihat Global Constraints di plan ini (minSdk 23, tanpa desugaring). */
object StandardLogTimestamp {
    fun now(date: Date = Date()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(date)
    }
}
```

```kotlin
package com.cashup.common.logging.standard

import kotlin.random.Random

object CorrelationIdGenerator {
    fun generate(timestamp: Long = System.currentTimeMillis()): String {
        val randomPrefix = Random.nextInt(1000, 10000)
        return "$randomPrefix-$timestamp"
    }
}
```

```kotlin
package com.cashup.common.logging.standard

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * `java.net.NetworkInterface` tidak butuh Context Android -- makanya ini bisa
 * hidup di :common-core (kotlin("jvm") murni) alih-alih di :app, tidak
 * seperti resolusi IP di project asal yang lewat ConnectivityManager (yang
 * ujung-ujungnya jatuh ke fallback yang sama persis di semua cabang -- di
 * sini disederhanakan langsung ke fallback itu).
 */
internal object LocalNetwork {
    fun currentIpv4(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    }.getOrNull().orEmpty().ifBlank { "-" }
}
```

- [ ] **Step 6: Write `StandardLogPayload.kt`**

```kotlin
package com.cashup.common.logging.standard

import com.google.gson.Gson

data class StandardLogPayload(
    val correlationId: String,
    val timestamp: String = StandardLogTimestamp.now(),
    val source: String,
    val deviceId: String,
    val merchantId: String,
    val ipAddress: String,
    val target: String,
    val service: String,
    val processDescription: String,
    val httpHeader: Map<String, Any?> = emptyMap(),
    val jsonBody: Map<String, Any?> = emptyMap(),
    val hmac: String = "",
) {
    fun signed(secretKey: String): StandardLogPayload {
        return copy(
            httpHeader = StandardLogSanitizer.sanitize(httpHeader),
            jsonBody = StandardLogSanitizer.sanitize(jsonBody),
            hmac = StandardLogHmacSigner.sign(correlationId, service, secretKey),
        )
    }

    // Gson tersedia transitif lewat api("com.squareup.retrofit2:converter-gson:...")
    // di common-core/build.gradle.kts -- tidak perlu dependency baru.
    fun toJson(): String = Gson().toJson(this)
}
```

- [ ] **Step 7: Write the failing payload test**

```kotlin
package com.cashup.common.logging.standard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandardLogPayloadTest {

    @Test
    fun `signed payload carries the correct hmac and sanitized fields`() {
        val payload = StandardLogPayload(
            correlationId = "1234-1700000000000",
            timestamp = "2026-09-23T00:00:00.000Z",
            source = "card-payment",
            deviceId = "device-1",
            merchantId = "device-1",
            ipAddress = "10.0.0.1",
            target = "cdcp-service",
            service = "sale/sale_trx_start",
            processDescription = "start",
            jsonBody = mapOf("pin" to "1234", "amount" to 10000),
        )

        val signed = payload.signed("test-secret")

        assertEquals("3c5cd8ea657286235940a520d88cc5a8b9fe0fbe62824ea0c200a629efda8ef2", signed.hmac)
        assertEquals("****", signed.jsonBody["pin"])
        assertEquals(10000, signed.jsonBody["amount"])
    }

    @Test
    fun `toJson serializes the payload fields`() {
        val payload = StandardLogPayload(
            correlationId = "c-1", timestamp = "2026-09-23T00:00:00.000Z", source = "card-payment",
            deviceId = "device-1", merchantId = "device-1", ipAddress = "10.0.0.1",
            target = "cdcp-service", service = "sale/sale_trx_start", processDescription = "start",
        )

        val json = payload.toJson()

        assertTrue(json.contains("\"correlationId\":\"c-1\""))
        assertTrue(json.contains("\"service\":\"sale/sale_trx_start\""))
    }
}
```

Run: `./gradlew :common-core:test --tests "com.cashup.common.logging.standard.StandardLogPayloadTest"`
Expected: both PASS.

- [ ] **Step 8: Run the whole common-core suite and commit**

Run: `./gradlew :common-core:test`
Expected: all tests PASS (existing `ApiResultTest`/`RetrofitFactoryTest`/etc. plus the four new files).

```bash
git add common-core/src/main/kotlin/com/cashup/common/logging/standard common-core/src/test/kotlin/com/cashup/common/logging/standard
git commit -m "feat(common-core): add StandardLog payload/signer/sanitizer pipeline"
```

---

## Task 6: `:common-core` — `StandardLogPublisher`

**Files:**
- Modify: `common-core/build.gradle.kts`
- Modify: `gradle/module-boundaries.gradle.kts` (`:common-core` entry)
- Create: `common-core/src/main/kotlin/com/cashup/common/logging/standard/StandardLogPublisher.kt`
- Test: `common-core/src/test/kotlin/com/cashup/common/logging/standard/StandardLogPublisherTest.kt`

**Interfaces:**
- Consumes: `com.cashup.broker.BrokerClient` (Task 3), `StandardLogPayload`/`StandardLogHmacSigner` (Task 5).
- Produces: `suspend fun StandardLogPublisher.publish(broker: BrokerClient, hmacSecret: String, correlationId: String, source: String, deviceId: String, merchantId: String, target: String, service: String, processDescription: String, jsonBody: Map<String, Any?> = emptyMap(), httpHeader: Map<String, Any?> = emptyMap(), ipAddress: String = LocalNetwork.currentIpv4()): Boolean`. Task 8 (`:app`) calls this.

- [ ] **Step 1: Add `:broker-core` as an `api` dependency**

In `common-core/build.gradle.kts`, add to `dependencies { ... }`:

```kotlin
    api(project(":broker-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
```

`api`, not `implementation` — `StandardLogPublisher.publish(...)` takes `BrokerClient` as a parameter type, so consumers of `:common-core` (`:app`) need that type on their own compile classpath. Same reasoning already documented in `provisioning-core/build.gradle.kts` for its `:signing-core` dependency.

- [ ] **Step 2: Update the module boundary**

In `gradle/module-boundaries.gradle.kts`, change:

```kotlin
    ":common-core" to emptySet(),
```

to:

```kotlin
    ":common-core" to setOf(":broker-core"),
```

- [ ] **Step 3: Write the failing test**

```kotlin
package com.cashup.common.logging.standard

import com.cashup.broker.BrokerClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandardLogPublisherTest {

    private class FakeBrokerClient(var connectResult: Boolean = true) : BrokerClient {
        var connectCalls = 0
        var publishedExchange: String? = null
        var publishedRoutingKey: String? = null
        var publishedPayload: String? = null
        override var isConnected = false

        override suspend fun connect(): Boolean {
            connectCalls++
            isConnected = connectResult
            return connectResult
        }

        override suspend fun publish(exchange: String, routingKey: String, payload: ByteArray): Boolean {
            publishedExchange = exchange
            publishedRoutingKey = routingKey
            publishedPayload = String(payload, Charsets.UTF_8)
            return true
        }

        override fun disconnect() {
            isConnected = false
        }
    }

    @Test
    fun `publish is skipped when hmac secret is blank`() = runTest {
        val broker = FakeBrokerClient()

        val result = StandardLogPublisher.publish(
            broker = broker, hmacSecret = "", correlationId = "c-1", source = "card-payment",
            deviceId = "device-1", merchantId = "device-1", target = "cdcp-service",
            service = "sale/sale_trx_start", processDescription = "start",
        )

        assertFalse(result)
        assertEquals(0, broker.connectCalls)
    }

    @Test
    fun `publish connects lazily then sends a signed payload to the logs exchange`() = runTest {
        val broker = FakeBrokerClient()

        val result = StandardLogPublisher.publish(
            broker = broker, hmacSecret = "test-secret", correlationId = "1234-1700000000000",
            source = "card-payment", deviceId = "device-1", merchantId = "device-1",
            target = "cdcp-service", service = "sale/sale_trx_start", processDescription = "start",
        )

        assertTrue(result)
        assertEquals(1, broker.connectCalls)
        assertEquals("logs", broker.publishedExchange)
        assertEquals("logs.service", broker.publishedRoutingKey)
        assertTrue(broker.publishedPayload!!.contains("3c5cd8ea657286235940a520d88cc5a8b9fe0fbe62824ea0c200a629efda8ef2"))
    }

    @Test
    fun `publish does not reconnect when already connected`() = runTest {
        val broker = FakeBrokerClient().apply { isConnected = true }

        StandardLogPublisher.publish(
            broker = broker, hmacSecret = "test-secret", correlationId = "c-1", source = "card-payment",
            deviceId = "device-1", merchantId = "device-1", target = "cdcp-service",
            service = "sale/authorizing", processDescription = "authorizing",
        )

        assertEquals(0, broker.connectCalls)
    }

    @Test
    fun `publish returns false when connect fails`() = runTest {
        val broker = FakeBrokerClient(connectResult = false)

        val result = StandardLogPublisher.publish(
            broker = broker, hmacSecret = "test-secret", correlationId = "c-1", source = "card-payment",
            deviceId = "device-1", merchantId = "device-1", target = "cdcp-service",
            service = "sale/sale_trx_start", processDescription = "start",
        )

        assertFalse(result)
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :common-core:test --tests "com.cashup.common.logging.standard.StandardLogPublisherTest"`
Expected: FAIL — `StandardLogPublisher` unresolved.

- [ ] **Step 5: Write `StandardLogPublisher.kt`**

```kotlin
package com.cashup.common.logging.standard

import com.cashup.broker.BrokerClient

object StandardLogPublisher {
    private const val EXCHANGE = "logs"
    private const val ROUTING_KEY = "logs.service"

    /** Referensi dokumentasi -- publish() sendiri hanya perlu exchange+routingKey, backend route ke queue ini lewat binding. */
    const val QUEUE = "logs.persist"

    suspend fun publish(
        broker: BrokerClient,
        hmacSecret: String,
        correlationId: String,
        source: String,
        deviceId: String,
        merchantId: String,
        target: String,
        service: String,
        processDescription: String,
        jsonBody: Map<String, Any?> = emptyMap(),
        httpHeader: Map<String, Any?> = emptyMap(),
        ipAddress: String = LocalNetwork.currentIpv4(),
    ): Boolean {
        if (hmacSecret.isBlank()) return false

        val payload = StandardLogPayload(
            correlationId = correlationId,
            source = source,
            deviceId = deviceId,
            merchantId = merchantId,
            ipAddress = ipAddress,
            target = target,
            service = service,
            processDescription = processDescription,
            httpHeader = httpHeader,
            jsonBody = jsonBody,
        ).signed(hmacSecret)

        if (!broker.isConnected && !broker.connect()) return false

        return broker.publish(EXCHANGE, ROUTING_KEY, payload.toJson().toByteArray(Charsets.UTF_8))
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :common-core:test --tests "com.cashup.common.logging.standard.StandardLogPublisherTest"`
Expected: all 4 PASS.

- [ ] **Step 7: Verify boundaries and commit**

Run: `./gradlew checkModuleBoundaries :common-core:test`
Expected: both succeed.

```bash
git add common-core gradle/module-boundaries.gradle.kts
git commit -m "feat(common-core): add StandardLogPublisher (publishes to logs.persist via BrokerClient)"
```

---

## Task 7: `:app` — `XorObfuscator` + `ObfuscatedBrokerCredentials` (placeholder values)

**Files:**
- Create: `app/src/main/kotlin/com/cashup/app/broker/XorObfuscator.kt`
- Create: `app/src/main/kotlin/com/cashup/app/broker/ObfuscatedBrokerCredentials.kt`
- Test: `app/src/test/kotlin/com/cashup/app/broker/XorObfuscatorTest.kt`

**Interfaces:**
- Produces: `internal object XorObfuscator { fun decode(encoded: IntArray, key: IntArray): String }` and `internal object ObfuscatedBrokerCredentials { fun brokerAuth(): BrokerAuth; fun hmacSecret(): String }`. Task 8's `BrokerCredentialStore` consumes both. **The real credential arrays are injected in Task 10, not here — this task ships with empty placeholder arrays and must compile and test green as-is.**

- [ ] **Step 1: Write `XorObfuscator.kt`**

```kotlin
package com.cashup.app.broker

/**
 * Menyembunyikan string literal dari extraksi langsung (`strings`/quick-scan
 * Jadx pada APK release) -- BUKAN proteksi mutlak, lihat
 * docs/superpowers/specs/2026-09-23-standard-log-broker-design.md §7.
 */
internal object XorObfuscator {
    fun decode(encoded: IntArray, key: IntArray): String {
        val bytes = ByteArray(encoded.size) { i -> (encoded[i] xor key[i % key.size]).toByte() }
        return String(bytes, Charsets.UTF_8)
    }
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.cashup.app.broker

import org.junit.Assert.assertEquals
import org.junit.Test

class XorObfuscatorTest {

    private fun encode(plain: String, key: IntArray): IntArray {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        return IntArray(bytes.size) { i -> bytes[i].toInt() xor key[i % key.size] }
    }

    @Test
    fun `decode reverses encode for arbitrary strings and keys`() {
        val key = intArrayOf(0x5A, 0x3C, 0x91, 0x17, 0x6B)
        val samples = listOf("svc-amqps.example.com", "", "p@ssw0rd!#%", "a", "cashup")

        samples.forEach { plain ->
            val encoded = encode(plain, key)
            assertEquals(plain, XorObfuscator.decode(encoded, key))
        }
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.cashup.app.broker.XorObfuscatorTest"`
Expected: FAIL — `XorObfuscator` unresolved, until Step 1's file is saved; after Step 1, re-run and expect PASS.

- [ ] **Step 3: Write `ObfuscatedBrokerCredentials.kt` with placeholder (empty) arrays**

```kotlin
package com.cashup.app.broker

import com.cashup.broker.BrokerAuth

/**
 * Kredensial broker AMQP `logs.persist`, di-obfuscate XOR supaya tidak
 * muncul sebagai string literal utuh di APK release. INI BUKAN PROTEKSI
 * MUTLAK -- lihat docs/superpowers/specs/2026-09-23-standard-log-broker-design.md §7
 * untuk kenapa dan apa perbaikan jangka panjangnya.
 *
 * Nilai *Encoded di bawah adalah PLACEHOLDER KOSONG. Diisi lewat Task 10 di
 * docs/superpowers/plans/2026-09-23-standard-log-broker.md -- itu langkah
 * manual (butuh kredensial asli), bukan sesuatu yang subagent tugas ini
 * boleh isi sendiri. Selama masih kosong, `brokerAuth()`/`hmacSecret()`
 * mengembalikan string kosong -- AppContainer harus menahan publish kalau
 * hmacSecret() kosong (StandardLogPublisher sudah menahan ini sendiri).
 */
internal object ObfuscatedBrokerCredentials {
    private val key = intArrayOf(0x5A, 0x3C, 0x91, 0x17, 0x6B, 0xD4, 0x28, 0x77)

    private val hostEncoded = intArrayOf()
    private val usernameEncoded = intArrayOf()
    private val passwordEncoded = intArrayOf()
    private val virtualHostEncoded = intArrayOf()
    private val hmacSecretEncoded = intArrayOf()
    private const val PORT = 5671

    fun brokerAuth(): BrokerAuth = BrokerAuth(
        host = XorObfuscator.decode(hostEncoded, key),
        port = PORT,
        username = XorObfuscator.decode(usernameEncoded, key),
        password = XorObfuscator.decode(passwordEncoded, key),
        virtualHost = XorObfuscator.decode(virtualHostEncoded, key),
        useTls = true,
    )

    fun hmacSecret(): String = XorObfuscator.decode(hmacSecretEncoded, key)
}
```

- [ ] **Step 4: Compile to confirm the placeholder compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS (empty `intArrayOf()` is valid Kotlin; `brokerAuth()`/`hmacSecret()` just decode to empty strings at runtime, which is correct/intentional until Task 10).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cashup/app/broker app/src/test/kotlin/com/cashup/app/broker
git commit -m "feat(app): add XOR credential obfuscation utility (placeholder values, filled in Task 10)"
```

---

## Task 8: `:app` — wire `BrokerCredentialStore`, `AppContainer`, `CardPaymentDependencies.logTransaction`

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `gradle/module-boundaries.gradle.kts` (`:app` entry)
- Create: `app/src/main/kotlin/com/cashup/app/broker/BrokerCredentialStore.kt`
- Modify: `app/src/main/kotlin/com/cashup/app/di/AppContainer.kt`
- Modify: `feature-card-payment/src/main/kotlin/com/cashup/feature/cardpayment/CardPaymentDependencies.kt`

**Interfaces:**
- Consumes: `ObfuscatedBrokerCredentials`/`XorObfuscator` (Task 7), `EncryptedPrefs` (Task 1), `AmqpBrokerClient` (Task 4), `StandardLogPublisher` (Task 6).
- Produces: `CardPaymentDependencies.logTransaction(correlationId: String, service: String, processDescription: String, jsonBody: Map<String, Any?> = emptyMap()): Unit = Unit` (default no-op). Task 9's `CardPaymentViewModel` calls this.

- [ ] **Step 1: Add `:broker-core` and `:secure-storage-core` dependencies**

In `app/build.gradle.kts`, add to the `implementation(project(...))` block:

```kotlin
    implementation(project(":broker-core"))
    implementation(project(":secure-storage-core"))
```

- [ ] **Step 2: Update the module boundary**

In `gradle/module-boundaries.gradle.kts`, change the `:app` entry from:

```kotlin
    ":app" to setOf(
        ":provisioning-core", ":cdcp-core", ":device-sdk-edcsdk", ":device-sdk-factory",
        ":feature-card-payment",
    ),
```

to:

```kotlin
    ":app" to setOf(
        ":provisioning-core", ":cdcp-core", ":device-sdk-edcsdk", ":device-sdk-factory",
        ":feature-card-payment", ":broker-core", ":secure-storage-core",
    ),
```

- [ ] **Step 3: Write `BrokerCredentialStore.kt`**

```kotlin
package com.cashup.app.broker

import android.content.Context
import com.cashup.broker.BrokerAuth
import com.cashup.securestorage.EncryptedPrefs

/**
 * Decode kredensial ter-obfuscate SEKALI, cache di EncryptedSharedPreferences
 * lewat :secure-storage-core, lalu baca dari cache itu untuk pemanggilan
 * berikutnya -- lihat spec §7 poin 2.
 */
internal class BrokerCredentialStore(context: Context) {
    private val prefs = EncryptedPrefs.open(context.applicationContext, "broker_credentials")

    fun brokerAuth(): BrokerAuth {
        ensureCached()
        return BrokerAuth(
            host = prefs.getString(KEY_HOST, null).orEmpty(),
            port = PORT,
            username = prefs.getString(KEY_USERNAME, null),
            password = prefs.getString(KEY_PASSWORD, null),
            virtualHost = prefs.getString(KEY_VHOST, null).orEmpty(),
            useTls = true,
        )
    }

    fun hmacSecret(): String {
        ensureCached()
        return prefs.getString(KEY_HMAC, null).orEmpty()
    }

    private fun ensureCached() {
        if (prefs.contains(KEY_HOST)) return
        val auth = ObfuscatedBrokerCredentials.brokerAuth()
        prefs.edit()
            .putString(KEY_HOST, auth.host)
            .putString(KEY_USERNAME, auth.username.orEmpty())
            .putString(KEY_PASSWORD, auth.password.orEmpty())
            .putString(KEY_VHOST, auth.virtualHost)
            .putString(KEY_HMAC, ObfuscatedBrokerCredentials.hmacSecret())
            .apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_VHOST = "virtual_host"
        const val KEY_HMAC = "hmac_secret"
        const val PORT = 5671
    }
}
```

(No unit test for this class — it needs a real Android `Context`/Keystore to exercise `EncryptedSharedPreferences`, and `:app` doesn't carry Robolectric today. This matches the existing convention in this exact file's neighbor, `AppContainer.kt`, which is also untested wiring glue — it's exercised by the manual smoke test in Task 10.)

- [ ] **Step 4: Add the default `logTransaction` method to `CardPaymentDependencies`**

In `feature-card-payment/src/main/kotlin/com/cashup/feature/cardpayment/CardPaymentDependencies.kt`, inside the `CardPaymentDependencies` interface, add a new default method right after `isTransactionAllowed()`:

```kotlin
interface CardPaymentDependencies {
    /** Re-read at transaction boundaries so a remote device suspension takes effect immediately. */
    fun isTransactionAllowed(): Boolean = true

    /** No-op by default so existing fakes/tests don't need to override it. */
    fun logTransaction(
        correlationId: String,
        service: String,
        processDescription: String,
        jsonBody: Map<String, Any?> = emptyMap(),
    ): Unit = Unit

    suspend fun cardReader(): CardReader
    suspend fun authorize(
        card: CardTransactionData,
        amount: BigDecimal,
        tip: BigDecimal,
        idempotencyKey: String,
    ): ApiResult<SaleResponse>
}
```

- [ ] **Step 5: Wire `AppContainer`**

In `app/src/main/kotlin/com/cashup/app/di/AppContainer.kt`, add these imports next to the existing ones:

```kotlin
import com.cashup.app.broker.BrokerCredentialStore
import com.cashup.broker.BrokerClient
import com.cashup.broker.amqp.AmqpBrokerClient
import com.cashup.common.logging.standard.StandardLogPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
```

Add these properties (near `val deviceSdkFactory = ...`, before the `override suspend fun cardReader()` block):

```kotlin
    private val brokerCredentialStore = BrokerCredentialStore(appContext)
    private val brokerClient: BrokerClient by lazy { AmqpBrokerClient(brokerCredentialStore.brokerAuth()) }
    private val loggingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

Add the interface override (near the other `override` members):

```kotlin
    override fun logTransaction(
        correlationId: String,
        service: String,
        processDescription: String,
        jsonBody: Map<String, Any?>,
    ) {
        val deviceId = activeDeviceId() ?: return
        loggingScope.launch {
            runCatching {
                StandardLogPublisher.publish(
                    broker = brokerClient,
                    hmacSecret = brokerCredentialStore.hmacSecret(),
                    correlationId = correlationId,
                    source = "card-payment",
                    deviceId = deviceId,
                    // Tidak ada konsep merchantId terpisah di project ini (device-per-terminal,
                    // tanpa login merchant) -- fallback ke deviceId, sama seperti fallback
                    // merchantId project asal saat detail merchant tidak tersedia.
                    merchantId = deviceId,
                    target = "cdcp-service",
                    service = service,
                    processDescription = processDescription,
                    jsonBody = jsonBody,
                )
            }
        }
    }
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin :feature-card-payment:compileKotlin`
Expected: SUCCESS.

- [ ] **Step 7: Verify boundaries and commit**

Run: `./gradlew checkModuleBoundaries`
Expected: `Module boundaries OK: N modules checked.`

```bash
git add app/build.gradle.kts app/src/main/kotlin/com/cashup/app/broker app/src/main/kotlin/com/cashup/app/di/AppContainer.kt feature-card-payment/src/main/kotlin/com/cashup/feature/cardpayment/CardPaymentDependencies.kt gradle/module-boundaries.gradle.kts
git commit -m "feat(app): wire BrokerCredentialStore + CardPaymentDependencies.logTransaction"
```

---

## Task 9: `:feature-card-payment` — log at each `CardPaymentViewModel` transaction stage

**Files:**
- Modify: `feature-card-payment/src/main/kotlin/com/cashup/feature/cardpayment/CardPaymentViewModel.kt`
- Modify: `feature-card-payment/src/test/kotlin/com/cashup/feature/cardpayment/CardPaymentViewModelTest.kt`

**Interfaces:**
- Consumes: `CardPaymentDependencies.logTransaction(...)` (Task 8), `com.cashup.common.logging.standard.CorrelationIdGenerator` (Task 5).

- [ ] **Step 1: Add the `correlationId` field and import**

In `CardPaymentViewModel.kt`, add this import next to the others:

```kotlin
import com.cashup.common.logging.standard.CorrelationIdGenerator
```

Add a new private field next to `private var idempotencyKey: String? = null`:

```kotlin
    private var correlationId: String? = null
```

- [ ] **Step 2: Generate the correlation ID and log the start event in `start()`**

In `start()`, change:

```kotlin
        if (pendingFingerprint != fingerprint) {
            pendingFingerprint = fingerprint
            idempotencyKey = SaleRepository.newIdempotencyKey()
        }
        transactionJob = viewModelScope.launch { connectAndStart(amount, tip) }
```

to:

```kotlin
        if (pendingFingerprint != fingerprint) {
            pendingFingerprint = fingerprint
            idempotencyKey = SaleRepository.newIdempotencyKey()
            correlationId = CorrelationIdGenerator.generate()
        }
        dependencies.logTransaction(
            requireNotNull(correlationId), "sale/sale_trx_start", "Transaksi kartu dimulai",
            mapOf("amount" to amount.toPlainString(), "tip" to tip.toPlainString()),
        )
        transactionJob = viewModelScope.launch { connectAndStart(amount, tip) }
```

- [ ] **Step 3: Log the authorizing event in `transact()`'s listener**

In `transact()`, change:

```kotlin
                override fun onEvent(event: CardTransactionEvent) {
                    val next = when (event) {
```

to:

```kotlin
                override fun onEvent(event: CardTransactionEvent) {
                    if (event == CardTransactionEvent.Authorizing) {
                        dependencies.logTransaction(
                            requireNotNull(correlationId), "sale/authorizing", "Mengotorisasi transaksi",
                            mapOf("amount" to amount.toPlainString(), "tip" to tip.toPlainString()),
                        )
                    }
                    val next = when (event) {
```

(leave the rest of `onEvent` — the `when` block and the `mutableState.value = ...` assignment after it — exactly as-is.)

- [ ] **Step 4: Log the success event in `renderHostResult()`**

Change:

```kotlin
    private fun renderHostResult(authorization: CardAuthorization) {
        when (val response = hostResult) {
            is ApiResult.Success -> {
                if (authorization.approved) {
                    pendingFingerprint = null
                    idempotencyKey = null
                }
```

to:

```kotlin
    private fun renderHostResult(authorization: CardAuthorization) {
        when (val response = hostResult) {
            is ApiResult.Success -> {
                if (authorization.approved) {
                    dependencies.logTransaction(
                        requireNotNull(correlationId), "sale/sale_trx_success", "Transaksi kartu berhasil",
                        mapOf(
                            "transactionId" to response.data.transactionId,
                            "status" to response.data.status,
                            "responseCode" to response.data.responseCode,
                        ),
                    )
                    pendingFingerprint = null
                    idempotencyKey = null
                    correlationId = null
                }
```

- [ ] **Step 5: Log the failure event in `fail()`**

Change:

```kotlin
    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(stage = PaymentStage.FAILURE, message = message)
    }
```

to:

```kotlin
    private fun fail(message: String) {
        correlationId?.let { id ->
            dependencies.logTransaction(id, "sale/sale_trx_failed", "Transaksi kartu gagal", mapOf("message" to message))
        }
        mutableState.value = mutableState.value.copy(stage = PaymentStage.FAILURE, message = message)
    }
```

(`?.let`, not `requireNotNull` — `fail()` is a shared helper and must never crash the payment flow over a logging concern, matching the fire-and-forget/`runCatching` philosophy already used for the actual publish in `AppContainer.logTransaction`.)

- [ ] **Step 6: Confirm the existing test file still compiles and passes unchanged**

Run: `./gradlew :feature-card-payment:test --tests "com.cashup.feature.cardpayment.CardPaymentViewModelTest"`
Expected: all 3 existing tests PASS unchanged — the anonymous `object : CardPaymentDependencies { ... }` instances in that file don't override `logTransaction`, so they get the default no-op from Task 8 Step 4 and nothing breaks.

- [ ] **Step 7: Write the failing logging-assertion tests**

Add these two tests to `CardPaymentViewModelTest.kt` (needs `com.cashup.common.network.ApiError` as an additional import):

```kotlin
    @Test fun `successful transaction logs start, authorizing, and success events`() = runTest {
        val pinBlock = ByteArray(8) { 0x11 }
        val card = CardTransactionData("4111111111111111D2812101", CardType.CHIP, "9F2601AA", pinBlock)
        val loggedServices = mutableListOf<String>()
        val reader = object : CardReader {
            override suspend fun transact(request: CardTransactionRequest, listener: CardTransactionListener): CardReadResult {
                listener.onEvent(CardTransactionEvent.WaitingForCard)
                listener.onEvent(CardTransactionEvent.CardDetected(CardType.CHIP))
                listener.onEvent(CardTransactionEvent.Authorizing)
                val authorization = listener.authorize(card)
                listener.onEvent(CardTransactionEvent.Completing)
                return CardReadResult.Success(authorization)
            }
            override fun cancel() = Unit
        }
        val dependencies = object : CardPaymentDependencies {
            override suspend fun cardReader() = reader
            override suspend fun authorize(
                card: CardTransactionData, amount: BigDecimal, tip: BigDecimal, idempotencyKey: String,
            ): ApiResult<SaleResponse> =
                ApiResult.Success(SaleResponse("trx-1", "APPROVED", approvalCode = "123456", responseCode = "00"))

            override fun logTransaction(
                correlationId: String, service: String, processDescription: String, jsonBody: Map<String, Any?>,
            ) {
                assertTrue(correlationId.isNotBlank())
                loggedServices += service
            }
        }

        val viewModel = CardPaymentViewModel(dependencies)
        viewModel.start("10000", "0")

        assertEquals(
            listOf("sale/sale_trx_start", "sale/authorizing", "sale/sale_trx_success"),
            loggedServices,
        )
    }

    @Test fun `failed authorization logs a failure event`() = runTest {
        val pinBlock = ByteArray(8) { 0x11 }
        val card = CardTransactionData("4111111111111111D2812101", CardType.CHIP, "9F2601AA", pinBlock)
        val loggedServices = mutableListOf<String>()
        val reader = object : CardReader {
            override suspend fun transact(request: CardTransactionRequest, listener: CardTransactionListener): CardReadResult {
                listener.onEvent(CardTransactionEvent.WaitingForCard)
                listener.onEvent(CardTransactionEvent.CardDetected(CardType.CHIP))
                listener.onEvent(CardTransactionEvent.Authorizing)
                val authorization = listener.authorize(card)
                listener.onEvent(CardTransactionEvent.Completing)
                return CardReadResult.Success(authorization)
            }
            override fun cancel() = Unit
        }
        val dependencies = object : CardPaymentDependencies {
            override suspend fun cardReader() = reader
            override suspend fun authorize(
                card: CardTransactionData, amount: BigDecimal, tip: BigDecimal, idempotencyKey: String,
            ): ApiResult<SaleResponse> = ApiResult.Failure(ApiError(code = "05", message = "Declined"))

            override fun logTransaction(
                correlationId: String, service: String, processDescription: String, jsonBody: Map<String, Any?>,
            ) {
                loggedServices += service
            }
        }

        val viewModel = CardPaymentViewModel(dependencies)
        viewModel.start("10000", "0")

        assertEquals(
            listOf("sale/sale_trx_start", "sale/authorizing", "sale/sale_trx_failed"),
            loggedServices,
        )
    }
```

Add the missing import at the top of the file:

```kotlin
import com.cashup.common.network.ApiError
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :feature-card-payment:test --tests "com.cashup.feature.cardpayment.CardPaymentViewModelTest"`
Expected: all 5 tests PASS (3 pre-existing + 2 new).

- [ ] **Step 9: Commit**

```bash
git add feature-card-payment/src/main/kotlin/com/cashup/feature/cardpayment/CardPaymentViewModel.kt feature-card-payment/src/test/kotlin/com/cashup/feature/cardpayment/CardPaymentViewModelTest.kt
git commit -m "feat(feature-card-payment): log transaction stages to logs.persist via CardPaymentDependencies"
```

---

## Task 10: Inject real credentials, full verification, manual smoke test (ORCHESTRATOR STEP — not a generic subagent dispatch)

**This task must be performed by the orchestrating session directly with the human, not delegated to a fresh subagent that has no access to the real credentials.** Every task before this one is credential-free by design (spec §7) — this is the one deliberate, isolated exception, and it stays isolated: nothing here gets copied into any other task, and the plan file itself is never edited to contain the real values.

**Files:**
- Modify: `app/src/main/kotlin/com/cashup/app/broker/ObfuscatedBrokerCredentials.kt` (only the five `*Encoded` array literals — nothing else in this file changes)

- [ ] **Step 1: Generate the obfuscated arrays locally (never saved with real values embedded)**

Save this script to the scratchpad directory (never inside the project checkout, so it can never accidentally get `git add`-ed):

```python
import sys

key = [0x5A, 0x3C, 0x91, 0x17, 0x6B, 0xD4, 0x28, 0x77]

def xor_encode(plain: str) -> list[int]:
    data = plain.encode("utf-8")
    return [b ^ key[i % len(key)] for i, b in enumerate(data)]

label, value = sys.argv[1], sys.argv[2]
print(f"{label} = intArrayOf({', '.join(str(b) for b in xor_encode(value))})")
```

Run it once per credential field, passing the real value as a command-line argument (the value only ever exists in this one interactive command and the printed output — the script file on disk never contains it):

```bash
python generate_obfuscated_credential.py host "<real RabbitMQ host>"
python generate_obfuscated_credential.py username "<real RabbitMQ username>"
python generate_obfuscated_credential.py password "<real RabbitMQ password>"
python generate_obfuscated_credential.py virtualHost "<real RabbitMQ vhost>"
python generate_obfuscated_credential.py hmacSecret "<real HMAC secret>"
```

- [ ] **Step 2: Paste the five printed arrays into `ObfuscatedBrokerCredentials.kt`**

Replace the five empty placeholder lines:

```kotlin
    private val hostEncoded = intArrayOf()
    private val usernameEncoded = intArrayOf()
    private val passwordEncoded = intArrayOf()
    private val virtualHostEncoded = intArrayOf()
    private val hmacSecretEncoded = intArrayOf()
```

with the five printed `intArrayOf(...)` values from Step 1 (matching each field name to its array).

- [ ] **Step 3: Confirm the values decode back correctly**

Add a temporary local sanity check (do not commit this — run it as a one-off, e.g. via a scratch `main()` or the debugger, then delete it): call `ObfuscatedBrokerCredentials.brokerAuth()` and `ObfuscatedBrokerCredentials.hmacSecret()` and confirm the decoded `host`/`username`/`virtualHost`/`hmacSecret` match what was typed into the generator script (do NOT print/log the password anywhere, including logcat).

- [ ] **Step 4: Run the full verification suite**

Run: `./gradlew check`
Expected: SUCCESS — this runs every module's unit tests plus `checkModuleBoundaries` (wired via `gradle.projectsEvaluated` in `gradle/module-boundaries.gradle.kts`) across the whole project, confirming Tasks 1–9 are all still consistent together.

- [ ] **Step 5: Manual smoke test on a real transaction**

Build and install the debug `:app` variant on a device/emulator with the card-payment flow reachable, run a real (or test-environment) card transaction end to end, and confirm in logcat / on the RabbitMQ side that messages land in `logs.persist` for the `sale/sale_trx_start` → `sale/authorizing` → `sale/sale_trx_success` (or `sale/sale_trx_failed`) sequence. This is the same manual verification style already used for `AppContainer`'s other untested wiring in this codebase — there is no automated end-to-end test for a real broker connection.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/cashup/app/broker/ObfuscatedBrokerCredentials.kt
git commit -m "feat(app): inject production logs.persist broker credentials (obfuscated)"
```

# `:notification-core` MQTT Payment-Status Listener Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone `:notification-core` module that connects to the dev MQTT broker and exposes a "wait 5s for a push, then poll REST every 1s indefinitely until a final status or the push wins, MQTT connection stays alive throughout" policy for payment status — no UI/ViewModel wiring yet.

**Architecture:** One new pure-JVM Gradle module (`kotlin("jvm")`, same tier as `:common-core`). Two independent pieces: `PaymentNotificationTransport` (subscribe-only MQTT contract + `MqttNotificationTransport` Paho-based implementation) and `PaymentStatusWaiter` (transport-agnostic race between MQTT push and REST polling, unit-tested with a fake transport — no real broker needed for its tests).

**Tech Stack:** Kotlin, `kotlinx-coroutines-core` (Flow, `select`, structured concurrency), Eclipse Paho Java MQTT client (`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`), JUnit 5 + `kotlinx-coroutines-test` (virtual time — tests must not actually sleep multiple seconds).

**Spec:** `docs/superpowers/specs/2026-09-28-notification-core-mqtt-design.md`

## Global Constraints

- `:notification-core` main source must stay inside the **Java 8 / Android API 23 subset**: no `java.time.*`, no `java.util.Base64`, no `java.util.function.*`, no default/static interface methods relying on desugaring. This module's compiled classes ship inside `:app` (minSdk 23) and Android Lint does **not** check pure-`kotlin("jvm")` modules — this exact class of bug has hit this repo 3 times already (see `common-core/build.gradle.kts` header comment). Copy that same warning comment into this module's `build.gradle.kts`.
- MQTT connection: plain TCP, port `1883`, **no TLS**, **anonymous** (no username/password) — explicit user decision, see spec §1. Do not add TLS/credential code in this plan.
- Topic: `cashup/staging/v1/devices/<deviceId>`, QoS 1. `clientId`: `"edc-$deviceId"`.
- `PaymentStatusWaiter.await()` must never call `transport.disconnect()` on any path (spec §3/§5 — disconnect is the app-level lifecycle owner's job, out of scope here).
- No unbounded-retry safeguard (max attempts/duration) inside `PaymentStatusWaiter` — that is an explicit, documented user decision (spec §3/§6). Do not add one.
- `checkModuleBoundaries` (`./gradlew checkModuleBoundaries`) must stay green after every module-graph change.

---

## Task 1: `:notification-core` module scaffold

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/module-boundaries.gradle.kts`
- Create: `notification-core/build.gradle.kts`
- Create: `notification-core/src/main/kotlin/com/cashup/notification/.gitkeep` (placeholder, deleted in Task 2 once real source lands — see step 5)
- Test: `notification-core/src/test/kotlin/com/cashup/notification/ModuleScaffoldTest.kt`

**Interfaces:**
- Produces: a buildable, empty `:notification-core` module other tasks add real files into.

- [ ] **Step 1: Register the module in `settings.gradle.kts`**

Add after the existing `include(...)` lines (before `include(":app")`, matching the existing top-to-bottom dependency order):

```kotlin
include(":notification-core")
```

- [ ] **Step 2: Create `notification-core/build.gradle.kts`**

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
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
```

- [ ] **Step 3: Add `:notification-core` to `gradle/module-boundaries.gradle.kts`**

In the `allowedProjectDeps` map, add it to the "Kontrak murni" group (alongside `:common-core`/`:device-sdk-api`/`:signing-core`):

```kotlin
":common-core" to emptySet(),
":device-sdk-api" to emptySet(),
":signing-core" to emptySet(),
":notification-core" to emptySet(),
```

Leave `:app`'s allowed-deps entry untouched — nothing in this plan adds `implementation(project(":notification-core"))` to `:app` yet (wiring is out of scope per spec §8), so granting that edge now would be an unused permission. Add it in the future plan that does the wiring.

- [ ] **Step 4: Create a placeholder so Gradle sees a non-empty module, and a scaffold test**

Create `notification-core/src/main/kotlin/com/cashup/notification/.gitkeep` (empty file — deleted in Task 2 step 1 once `RawNotification.kt` exists in that package).

Create `notification-core/src/test/kotlin/com/cashup/notification/ModuleScaffoldTest.kt`:

```kotlin
package com.cashup.notification

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ModuleScaffoldTest {
    @Test
    fun `module builds and runs a test`() {
        assertTrue(true)
    }
}
```

- [ ] **Step 5: Run the build to verify wiring**

Run: `gradlew.bat :notification-core:test checkModuleBoundaries`
Expected: `BUILD SUCCESSFUL`, `ModuleScaffoldTest` passes, `checkModuleBoundaries` logs `Module boundaries OK: N modules checked.`

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/module-boundaries.gradle.kts notification-core/
git commit -m "feat(notification-core): scaffold new pure-JVM module"
```

---

## Task 2: Core contracts — `RawNotification` + `PaymentNotificationTransport`

**Files:**
- Create: `notification-core/src/main/kotlin/com/cashup/notification/PaymentNotificationTransport.kt`
- Delete: `notification-core/src/main/kotlin/com/cashup/notification/.gitkeep`

**Interfaces:**
- Consumes: nothing (leaf types).
- Produces: `RawNotification(topic: String, payload: String)`, `interface PaymentNotificationTransport { val isConnected: Boolean; fun connect(deviceId: String); val messages: Flow<RawNotification>; fun disconnect() }` — Task 3 (`PaymentStatusWaiter`) and Task 4 (`MqttNotificationTransport`) both depend on these exact names/types.

- [ ] **Step 1: Delete the scaffold placeholder and create the contract file**

Delete `notification-core/src/main/kotlin/com/cashup/notification/.gitkeep`.

Create `notification-core/src/main/kotlin/com/cashup/notification/PaymentNotificationTransport.kt`:

```kotlin
package com.cashup.notification

import kotlinx.coroutines.flow.Flow

/**
 * Pesan mentah dari broker. Payload TIDAK di-parse di sini -- schema JSON dari
 * backend belum final (lihat spec §8), jadi caller (mis. [PaymentStatusWaiter]
 * lewat parameter `matches`) yang tahu cara mem-parsing & mencocokkannya.
 */
data class RawNotification(val topic: String, val payload: String)

/** Client MQTT subscribe-only, generik terhadap payload. */
interface PaymentNotificationTransport {

    val isConnected: Boolean

    /**
     * Subscribe ke `cashup/staging/v1/devices/<deviceId>`, QoS 1. Idempotent
     * kalau dipanggil ulang dengan deviceId yang sama saat sudah connected.
     */
    fun connect(deviceId: String)

    /** Hot stream -- semua subscriber melihat pesan yang sama, tidak ada replay. */
    val messages: Flow<RawNotification>

    fun disconnect()
}
```

- [ ] **Step 2: Compile to verify**

Run: `gradlew.bat :notification-core:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add notification-core/
git commit -m "feat(notification-core): add PaymentNotificationTransport contract"
```

---

## Task 3: `PaymentStatusWaiter` — wait/poll/race policy (TDD)

**Files:**
- Create: `notification-core/src/main/kotlin/com/cashup/notification/PaymentStatusWaiter.kt`
- Test: `notification-core/src/test/kotlin/com/cashup/notification/PaymentStatusWaiterTest.kt`

**Interfaces:**
- Consumes: `RawNotification`, `PaymentNotificationTransport` (Task 2).
- Produces: `class PaymentStatusWaiter(transport: PaymentNotificationTransport) { suspend fun <T> await(timeoutMs: Long = 5_000L, pollIntervalMs: Long = 1_000L, matches: (RawNotification) -> T?, statusCheck: suspend () -> T?): T }` — this is the type future wiring (out of scope of this plan) will call from `:feature-card-payment`/QRIS.

- [ ] **Step 1: Write the test file with a fake transport and the first test**

Create `notification-core/src/test/kotlin/com/cashup/notification/PaymentStatusWaiterTest.kt`:

```kotlin
package com.cashup.notification

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private class FakeTransport : PaymentNotificationTransport {
    override var isConnected: Boolean = false
        private set
    val disconnectCallCount get() = _disconnectCallCount
    private var _disconnectCallCount = 0
    private val flow = MutableSharedFlow<RawNotification>(extraBufferCapacity = 16)

    override fun connect(deviceId: String) { isConnected = true }
    override val messages: Flow<RawNotification> = flow
    override fun disconnect() { _disconnectCallCount++; isConnected = false }

    suspend fun emit(notification: RawNotification) = flow.emit(notification)
}

class PaymentStatusWaiterTest {

    @Test
    fun `mqtt message matching before timeout wins, statusCheck never called`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)
        var statusCheckCalls = 0

        val job = launch {
            val result = waiter.await(
                timeoutMs = 5_000L,
                pollIntervalMs = 1_000L,
                matches = { it.payload.takeIf { p -> p == "paid" } },
                statusCheck = { statusCheckCalls++; null },
            )
            assertEquals("paid", result)
        }

        advanceTimeBy(1_000L)
        transport.emit(RawNotification(topic = "t", payload = "paid"))
        job.join()

        assertEquals(0, statusCheckCalls)
        assertEquals(0, transport.disconnectCallCount)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradlew.bat :notification-core:test --tests "com.cashup.notification.PaymentStatusWaiterTest"`
Expected: FAIL — `PaymentStatusWaiter` is unresolved (class does not exist yet).

- [ ] **Step 3: Implement `PaymentStatusWaiter`**

Create `notification-core/src/main/kotlin/com/cashup/notification/PaymentStatusWaiter.kt`:

```kotlin
package com.cashup.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.selects.select

/**
 * Kebijakan "tunggu push [timeoutMs], lalu fallback REST tiap [pollIntervalMs]
 * SELAMANYA sampai dapat hasil final atau push menang duluan" -- independen
 * dari detail MQTT, testable dengan fake [PaymentNotificationTransport].
 *
 * TIDAK pernah memanggil [PaymentNotificationTransport.disconnect] -- itu
 * tanggung jawab pemilik lifecycle transport, bukan modul ini (spec §3/§5).
 */
class PaymentStatusWaiter(private val transport: PaymentNotificationTransport) {

    /**
     * @param matches dipanggil untuk tiap [RawNotification] yang masuk selama
     *   SELURUH pemanggilan ini (bukan cuma [timeoutMs] pertama); kembalikan
     *   hasil ter-parse kalau pesan ini yang dicari, `null` kalau bukan.
     * @param statusCheck dipanggil pertama kali persis saat [timeoutMs]
     *   terlampaui tanpa [matches] yang berhasil, lalu diulang setiap
     *   [pollIntervalMs] sampai mengembalikan non-null atau [matches] menang
     *   duluan. Exception dari [statusCheck] diabaikan dan di-retry pada
     *   percobaan berikutnya (kecuali [CancellationException], yang harus
     *   tetap propagate supaya cancel dari race ini bekerja).
     * @return hasil pertama yang non-null, dari [matches] atau dari salah
     *   satu pemanggilan [statusCheck] -- mana pun yang menang duluan.
     */
    suspend fun <T> await(
        timeoutMs: Long = 5_000L,
        pollIntervalMs: Long = 1_000L,
        matches: (RawNotification) -> T?,
        statusCheck: suspend () -> T?,
    ): T = coroutineScope {
        val fromMqtt = async { transport.messages.mapNotNull(matches).first() }
        val fromPoll = async {
            delay(timeoutMs)
            while (true) {
                val result = try {
                    statusCheck()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (result != null) return@async result
                delay(pollIntervalMs)
            }
        }
        val winner = select<T> {
            fromMqtt.onAwait { it }
            fromPoll.onAwait { it }
        }
        fromMqtt.cancel()
        fromPoll.cancel()
        winner
    }
}
```

- [ ] **Step 4: Run the first test to verify it passes**

Run: `gradlew.bat :notification-core:test --tests "com.cashup.notification.PaymentStatusWaiterTest"`
Expected: PASS

- [ ] **Step 5: Add the remaining scenarios from spec §7 to the same test file**

Add these test functions inside `PaymentStatusWaiterTest` (after the existing one):

```kotlin
    @Test
    fun `no mqtt message, statusCheck succeeds on first poll at exactly timeoutMs`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)
        var statusCheckCalls = 0

        val result = waiter.await(
            timeoutMs = 5_000L,
            pollIntervalMs = 1_000L,
            matches = { null },
            statusCheck = { statusCheckCalls++; "final-status" },
        )

        assertEquals("final-status", result)
        assertEquals(1, statusCheckCalls)
        assertEquals(0, transport.disconnectCallCount)
    }

    @Test
    fun `statusCheck returns null a few times before succeeding, polls at pollIntervalMs apart`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)
        var statusCheckCalls = 0

        val result = waiter.await(
            timeoutMs = 5_000L,
            pollIntervalMs = 1_000L,
            matches = { null },
            statusCheck = {
                statusCheckCalls++
                if (statusCheckCalls < 3) null else "final-status"
            },
        )

        assertEquals("final-status", result)
        assertEquals(3, statusCheckCalls)
    }

    @Test
    fun `mqtt message wins mid-polling, no further statusCheck calls after`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)
        var statusCheckCalls = 0

        val job = launch {
            val result = waiter.await(
                timeoutMs = 5_000L,
                pollIntervalMs = 1_000L,
                matches = { it.payload.takeIf { p -> p == "paid" } },
                statusCheck = { statusCheckCalls++; null },
            )
            assertEquals("paid", result)
        }

        // Let two poll attempts happen (t=5000, t=6000), then push arrives before the third (t=7000).
        advanceTimeBy(6_500L)
        val callsBeforePush = statusCheckCalls
        transport.emit(RawNotification(topic = "t", payload = "paid"))
        job.join()

        assertEquals(2, callsBeforePush)
        assertEquals(2, statusCheckCalls)
    }

    @Test
    fun `statusCheck exception is ignored, polling continues on next interval`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)
        var statusCheckCalls = 0

        val result = waiter.await(
            timeoutMs = 5_000L,
            pollIntervalMs = 1_000L,
            matches = { null },
            statusCheck = {
                statusCheckCalls++
                if (statusCheckCalls == 1) error("transient network error") else "final-status"
            },
        )

        assertEquals("final-status", result)
        assertEquals(2, statusCheckCalls)
    }

    @Test
    fun `non-matching mqtt messages do not stop waiting`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)

        val job = launch {
            val result = waiter.await(
                timeoutMs = 5_000L,
                pollIntervalMs = 1_000L,
                matches = { it.payload.takeIf { p -> p == "paid" } },
                statusCheck = { null },
            )
            assertEquals("paid", result)
        }

        transport.emit(RawNotification(topic = "t", payload = "other-invoice"))
        advanceTimeBy(2_000L)
        transport.emit(RawNotification(topic = "t", payload = "paid"))
        job.join()
    }
```

Add the missing import alongside the existing ones at the top of the file:

```kotlin
import kotlinx.coroutines.test.advanceTimeBy
```

(already imported in Step 1 — no change needed if Step 1 was followed exactly; verify it's present.)

- [ ] **Step 6: Run the full test class**

Run: `gradlew.bat :notification-core:test --tests "com.cashup.notification.PaymentStatusWaiterTest"`
Expected: PASS, 6 tests, 0 failures. Test run must complete in well under a second of wall-clock time per test — if any test seems to actually wait multiple real seconds, `runTest`'s virtual time isn't being used correctly; re-check that `advanceTimeBy` (not `delay`) drives time in the test body.

- [ ] **Step 7: Commit**

```bash
git add notification-core/
git commit -m "feat(notification-core): add PaymentStatusWaiter wait/poll/race policy"
```

---

## Task 4: `MqttNotificationTransport` — Eclipse Paho implementation

**Files:**
- Create: `notification-core/src/main/kotlin/com/cashup/notification/MqttNotificationTransport.kt`
- Test: `notification-core/src/test/kotlin/com/cashup/notification/MqttNotificationTransportTest.kt`

**Interfaces:**
- Consumes: `PaymentNotificationTransport`, `RawNotification` (Task 2).
- Produces: `class MqttNotificationTransport(host: String, port: Int) : PaymentNotificationTransport` and `companion object { fun topicFor(deviceId: String): String }`.

- [ ] **Step 1: Write the failing test for the pure topic-building logic**

This is the only piece of this class worth a real broker-free unit test — the rest (`connect`/`disconnect`/`messages`) is exercised manually against the dev broker per spec §7 (unrealistic to unit-test a live TCP MQTT connection in CI).

Create `notification-core/src/test/kotlin/com/cashup/notification/MqttNotificationTransportTest.kt`:

```kotlin
package com.cashup.notification

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MqttNotificationTransportTest {
    @Test
    fun `topicFor builds the unified per-device topic`() {
        assertEquals(
            "cashup/staging/v1/devices/EDC12345",
            MqttNotificationTransport.topicFor("EDC12345"),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :notification-core:test --tests "com.cashup.notification.MqttNotificationTransportTest"`
Expected: FAIL — `MqttNotificationTransport` is unresolved.

- [ ] **Step 3: Implement `MqttNotificationTransport`**

Create `notification-core/src/main/kotlin/com/cashup/notification/MqttNotificationTransport.kt`:

```kotlin
package com.cashup.notification

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Wrapper Eclipse Paho untuk [PaymentNotificationTransport]. Broker dev saat
 * ini: plain TCP tanpa TLS, anonymous tanpa username/password (spec §1/§4).
 *
 * `messages` pakai [MutableSharedFlow] replay=0, bukan `callbackFlow` --
 * lebih sederhana untuk mencocokkan kontrak §3 "hot stream, semua subscriber
 * melihat pesan yang sama, tidak ada replay" tanpa perlu membridging ulang
 * callback Paho per collector.
 */
class MqttNotificationTransport(
    private val host: String,
    private val port: Int,
) : PaymentNotificationTransport {

    private val internalMessages = MutableSharedFlow<RawNotification>(extraBufferCapacity = 64)
    override val messages: Flow<RawNotification> = internalMessages.asSharedFlow()

    private var client: MqttClient? = null

    override val isConnected: Boolean
        get() = client?.isConnected == true

    override fun connect(deviceId: String) {
        if (isConnected) return
        val mqttClient = MqttClient("tcp://$host:$port", "edc-$deviceId", MemoryPersistence())
        mqttClient.setCallback(object : MqttCallback {
            override fun messageArrived(topic: String, message: MqttMessage) {
                internalMessages.tryEmit(RawNotification(topic, String(message.payload, Charsets.UTF_8)))
            }

            override fun connectionLost(cause: Throwable?) = Unit

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
        }
        mqttClient.connect(options)
        mqttClient.subscribe(topicFor(deviceId), 1)
        client = mqttClient
    }

    override fun disconnect() {
        client?.let { current ->
            if (current.isConnected) current.disconnect()
            current.close()
        }
        client = null
    }

    companion object {
        fun topicFor(deviceId: String): String = "cashup/staging/v1/devices/$deviceId"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :notification-core:test --tests "com.cashup.notification.MqttNotificationTransportTest"`
Expected: PASS

- [ ] **Step 5: Run the full module test suite**

Run: `gradlew.bat :notification-core:test checkModuleBoundaries`
Expected: `BUILD SUCCESSFUL`, all tests across the module pass.

- [ ] **Step 6: Commit**

```bash
git add notification-core/
git commit -m "feat(notification-core): add MqttNotificationTransport (Eclipse Paho)"
```

- [ ] **Step 7: Manual smoke test against the dev broker (not part of the automated suite)**

This step has no automated pass/fail — it is a one-time manual check per spec §7, and also the first real data point on `1883`/plain latency (spec §1 notes only `8883`/TLS was measured). Do this from a short Kotlin scratch script or a temporary `main()` inside a throwaway file (not committed):

```kotlin
fun main() {
    val transport = MqttNotificationTransport(host = "100.114.27.64", port = 1883)
    transport.connect(deviceId = "smoke-test-device")
    // Trigger a real qris.payment_paid event on this device's topic from another
    // tool/terminal, then confirm it prints below within a few seconds.
    kotlinx.coroutines.runBlocking {
        transport.messages.collect { println("Received: $it") }
    }
}
```

Record the result (received or not, approximate latency) as a one-line note in `docs/session_log.md` under today's date — this is the kind of "verified manually, out of CI" fact that belongs there per this repo's existing convention (see how the `:broker-core` spec's §7 credential verification was logged), not as a permanent code artifact.

---

## Task 5: `AppConfig` — MQTT host/port configuration

**Files:**
- Modify: `app/src/main/kotlin/com/cashup/app/AppConfig.kt`

**Interfaces:**
- Consumes: nothing new (`AppConfig` already exists; this task only adds two properties following its existing `baseUrl` pattern).
- Produces: `AppConfig.mqttHost: String`, `AppConfig.mqttPort: Int` — for the future (out-of-scope-here) `:app` wiring task to read when constructing a `MqttNotificationTransport`.

No automated test for this step: `baseUrl`, the property this mirrors exactly, has no unit test in this codebase either (`SharedPreferences`/`Context`-backed properties in `:app` aren't currently covered — testing them would need Robolectric or a `Context`/`SharedPreferences` mock that no existing test in this module sets up). Introducing a new testing pattern for two trivial getter/setter properties, inconsistent with the property they're copied from, is out of scope here. The deliverable is verified by compiling.

- [ ] **Step 1: Add the two properties to `AppConfig`**

Read the current file first (`app/src/main/kotlin/com/cashup/app/AppConfig.kt`) to confirm line numbers haven't shifted, then edit it to:

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

    /**
     * Host/port broker MQTT payment-status. Plain TCP, tanpa TLS, port 1883 --
     * keputusan eksplisit user (bukan port 8883/TLS yang direkomendasikan
     * dokumen konektivitas broker dev), lihat
     * docs/superpowers/specs/2026-09-28-notification-core-mqtt-design.md §1.
     */
    var mqttHost: String
        get() = prefs.getString(KEY_MQTT_HOST, DEFAULT_MQTT_HOST) ?: DEFAULT_MQTT_HOST
        set(value) = prefs.edit().putString(KEY_MQTT_HOST, value).apply()

    var mqttPort: Int
        get() = prefs.getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT)
        set(value) = prefs.edit().putInt(KEY_MQTT_PORT, value).apply()

    private companion object {
        const val KEY_BASE_URL = "base_url"
//        const val DEFAULT_BASE_URL = "http://192.168.90.46:8080" //local
        const val DEFAULT_BASE_URL = "http://100.114.27.64:8080" //tailscale

        const val KEY_MQTT_HOST = "mqtt_host"
        const val KEY_MQTT_PORT = "mqtt_port"
//        const val DEFAULT_MQTT_HOST = "192.168.90.46" // office LAN
        const val DEFAULT_MQTT_HOST = "100.114.27.64" // tailscale
        const val DEFAULT_MQTT_PORT = 1883
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/cashup/app/AppConfig.kt
git commit -m "feat(app): add MQTT host/port config for notification-core"
```

---

## Final verification (after all tasks)

- [ ] Run: `gradlew.bat :notification-core:test :app:assembleDebug checkModuleBoundaries`
- Expected: `BUILD SUCCESSFUL`, all `:notification-core` tests pass (11 total: 1 scaffold + 6 waiter + 1 topic + whatever `:app` already had), module boundaries clean, `:app` still assembles (no wiring added, so this is really just confirming `AppConfig`'s new properties don't break the app module build).

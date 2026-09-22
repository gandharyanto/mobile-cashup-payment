# Device SDK Vendor Abstraction (EDC built-in + mPOS Bluetooth) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add multi-vendor device SDK selection to `mobile-cashup-payment`: a new `device-sdk-mpos` module (Newland/Topwise Bluetooth mPOS readers, not covered by the existing `edc-sdk` AAR), a new `device-sdk-factory` module that probes EDC built-in first then falls back to mPOS, and a `checkModuleBoundaries` Gradle check enforcing the module graph.

**Architecture:** `device-sdk-api` stays untouched except for one new optional interface (`PairableCardReader`). Two new Android library modules (`device-sdk-mpos`, `device-sdk-factory`) sit beside the existing `device-sdk-edcsdk`, each implementing `DeviceSdk`/`CardReader` from `device-sdk-api` against a different vendor SDK family. `device-sdk-factory` is the only module allowed to depend on both vendor modules — it probes EDC built-in via `SDKManager.autoDetectDevice`, falling back to a Bluetooth-adapter-presence check for mPOS.

**Tech Stack:** Kotlin 2.0.21, AGP 8.4.0, Android library modules (`com.android.library` + `kotlin("android")`), `kotlinx-coroutines-android` 1.9.0, JUnit 4.13.2 + mockk 1.13.11 + `kotlinx-coroutines-test` 1.9.0 (matching `device-sdk-edcsdk`'s existing test convention — these are Android-library modules, not pure JVM like `device-sdk-api`). Vendor AARs consumed via the repo's existing `flatDir { dirs("$rootDir/aarlib") }` (declared in `settings.gradle.kts`), `implementation(group = "", name = "...", ext = "aar")` form.

**Spec:** `docs/superpowers/specs/2026-09-22-device-sdk-vendor-abstraction-design.md` — this plan implements it, with two corrections found during planning (both applied to the spec too, see errata below).

## Errata against the spec (found while planning, already patched into the spec file)

1. **`PairableCardReader.pairedDevices()` must be `suspend`.** The spec originally wrote it as a plain `fun`. The real API it wraps (`DeviceConnectionManager.scan(channel, durationMs)`, confirmed from `edc-sdk` source) is a suspend function that takes ~8 seconds (Bluetooth discovery) — it cannot be synchronous.
2. **The mPOS AARs must be built from `edc-sdk` at version `1.0.63` (matching the `core-release_1.0.63.aar` already vendored here), not copied from `mobile-apps-cashlez/aarlib`'s cached `_1.0.50` artifacts.** Verified by decompiling `mobile-cashup-payment/aarlib/core-release_1.0.63.aar`: it contains `com.lib.core.SDKManager`/`DeviceManager` but **none** of the `com.lib.device.*` channel/pairing framework (`DeviceConnectionManager`, `Channel`, `BrandRegistry`, etc.) that `mobile-apps-cashlez` relies on for mPOS. That framework lives in a **separate** `edc-sdk` module called `:other` (namespace `com.lib.device`), which this project's `aarlib/` has never vendored because `device-sdk-edcsdk` never needed it. `mobile-apps-cashlez`'s `core-release_${coreVersion}.jar` is a different (older) build lineage — confirmed by a real signature mismatch: `cashlez`'s `EmvViewModel.kt` calls `emv.startReadCard(amount: String, mode: Int, listener)` (3-arg), while both this project's `RealEmvGateway.kt` **and** the real `BaseEmvConfiguration.startReadCard(amount: Long, transactionResponse: TransactionResponse)` source in `edc-sdk`'s `:core` module (current HEAD, same one `core-release_1.0.63.aar` is built from) use the 2-arg form. Building `:other`/`:newland-mpos`/`:topwise-mpos` fresh from `edc-sdk` at the matching version avoids mixing two SDK generations.

## Global Constraints

- Shared Android levels: `compileSdk=33`, `minSdk=23`, `targetSdk=33` (from root `gradle.properties`, read via `project.property("cashup.compileSdk")` etc. — copy this pattern exactly in every new module's `android {}` block).
- No Gradle version catalog exists in this repo — hardcode dependency version strings inline in each `build.gradle.kts`, matching what sibling modules already use verbatim (versions given in each task below).
- Vendor AARs: `implementation(group = "", name = "<file-stem>", ext = "aar")` — never `api`. This is enforced by `checkModuleBoundaries` (Task 9).
- Android-library test convention (used by `device-sdk-edcsdk`, and by both new modules): `testImplementation("junit:junit:4.13.2")`, `testImplementation("io.mockk:mockk:1.13.11")`, `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")`, `testOptions { unitTests { isIncludeAndroidResources = false } }`.
- `device-sdk-api` stays pure `kotlin("jvm")`, zero Android imports — this plan adds one new file there and it must not import `android.*`.
- Module boundary rules (Task 9 encodes these as a Gradle check): `:device-sdk-edcsdk` and `:device-sdk-mpos` must never depend on each other; only `:device-sdk-factory` may depend on both; vendor AARs are always `implementation`, never `api`.
- Real dependency graph today (verified by reading every `build.gradle.kts`), which the new modules must not violate: `:device-sdk-edcsdk` → `:device-sdk-api` (api). `:provisioning-core` → `:common-core`, `:device-sdk-api`, `:signing-core` (all api) — **not** `:device-sdk-edcsdk`. `:cdcp-core` → `:common-core`, `:device-sdk-api`, `:signing-core` (all api). `:app` → `:provisioning-core`, `:cdcp-core`, `:device-sdk-edcsdk` (all implementation).

---

### Task 1: Vendor the mPOS AARs from `edc-sdk`

**Files:**
- Create (in `edc-sdk`, a sibling repo, then copy out): `D:/gandha_cashup/projects/edc-sdk/aarlib/other-release-core_1.0.63.aar`, `newland-mpos-release-core_1.0.63.aar`, `topwise-mpos-release-core_1.0.63.aar`
- Create: `mobile-cashup-payment/aarlib/other-release-core_1.0.63.aar`, `mobile-cashup-payment/aarlib/newland-mpos-release-core_1.0.63.aar`, `mobile-cashup-payment/aarlib/topwise-mpos-release-core_1.0.63.aar`
- Modify: `mobile-cashup-payment/aarlib/README.md`

**Interfaces:**
- Produces: three `.aar` files on the `flatDir` classpath, consumable in Task 3 as `implementation(group = "", name = "other-release-core_1.0.63", ext = "aar")` etc.

- [ ] **Step 1: Build the three AARs in `edc-sdk`**

```bash
cd "D:/gandha_cashup/projects/edc-sdk"
./gradlew :other:assembleRelease :newland-mpos:assembleRelease :topwise-mpos:assembleRelease
```

Expected: BUILD SUCCESSFUL. If it fails on a missing `aarlib/emvlib-release_1.0.2.aar` inside `edc-sdk` itself (that repo's `:other/build.gradle` declares `implementation files("$rootDir/aarlib/emvlib-release_1.0.2.aar")`), check `edc-sdk/aarlib/` for that file first — it's that repo's own pre-existing dependency, not something this task introduces.

- [ ] **Step 2: Run the repo's own copy tasks to get the correctly-named artifacts**

```bash
cd "D:/gandha_cashup/projects/edc-sdk"
./gradlew :other:copyAARRelease :newland-mpos:copyAARRelease :topwise-mpos:copyAARRelease
```

Expected: three new files appear:
```
edc-sdk/aarlib/other-release-core_1.0.63.aar
edc-sdk/aarlib/newland-mpos-release-core_1.0.63.aar
edc-sdk/aarlib/topwise-mpos-release-core_1.0.63.aar
```
(The `1.0.63` comes from `VERSION_NAME_CORE` in `edc-sdk/config.gradle`, confirmed already set to `'1.0.63'` — matching this project's existing `core-release_1.0.63.aar`.)

- [ ] **Step 3: Copy the three AARs into this project**

```bash
cp "D:/gandha_cashup/projects/edc-sdk/aarlib/other-release-core_1.0.63.aar" "D:/gandha_cashup/projects/mobile-cashup-payment/aarlib/"
cp "D:/gandha_cashup/projects/edc-sdk/aarlib/newland-mpos-release-core_1.0.63.aar" "D:/gandha_cashup/projects/mobile-cashup-payment/aarlib/"
cp "D:/gandha_cashup/projects/edc-sdk/aarlib/topwise-mpos-release-core_1.0.63.aar" "D:/gandha_cashup/projects/mobile-cashup-payment/aarlib/"
```

- [ ] **Step 4: Update `aarlib/README.md`**

Read the current file first, then add three rows to the table (same style as the existing ones) and update the trailing note:

```markdown
| `other-release-core_1.0.63.aar` | `:device-sdk-mpos` — `DeviceConnectionManager`, `Channel`, `BrandRegistry`, `MposReaderDevice` |
| `newland-mpos-release-core_1.0.63.aar` | `:device-sdk-mpos` — `NewlandBlueHelper` |
| `topwise-mpos-release-core_1.0.63.aar` | `:device-sdk-mpos` — `TopwiseBlueHelper` |
```

And change the closing paragraph from "Feitian, Urovo, Newland, dan Tianyu tidak punya AAR..." to:

```markdown
Feitian, Urovo, dan Tianyu tidak punya AAR di sini dan tidak punya implementasi
`SystemKey` di `edc-sdk` — tidak didukung sebagai EDC terminal built-in. Newland
**hanya** didukung lewat mPOS Bluetooth eksternal (`:device-sdk-mpos`), bukan
lewat `:device-sdk-edcsdk` — `edc-sdk` tidak punya modul `newland` (terminal
built-in) untuk vendor ini, hanya `newland-mpos`.
```

- [ ] **Step 5: Commit**

```bash
cd "D:/gandha_cashup/projects/mobile-cashup-payment"
git add aarlib/other-release-core_1.0.63.aar aarlib/newland-mpos-release-core_1.0.63.aar aarlib/topwise-mpos-release-core_1.0.63.aar aarlib/README.md
git commit -m "build: vendor other/newland-mpos/topwise-mpos AARs from edc-sdk 1.0.63"
```

---

### Task 2: Add `PairableCardReader` to `device-sdk-api`

**Files:**
- Create: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/PairableCardReader.kt`
- Create: `device-sdk-api/src/test/kotlin/com/cashup/devicesdk/PairableCardReaderTest.kt`

**Interfaces:**
- Produces: `PairedDeviceInfo(id: String, name: String)`, `interface PairableCardReader { suspend fun pairedDevices(): List<PairedDeviceInfo>; suspend fun selectDevice(id: String): Boolean }` — consumed by `device-sdk-mpos` (Task 5) and, later, by UI code outside this plan's scope.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cashup.devicesdk

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairableCardReaderTest {
    private class FakePairableReader : PairableCardReader {
        var selected: String? = null
        override suspend fun pairedDevices(): List<PairedDeviceInfo> =
            listOf(PairedDeviceInfo("AA:BB", "Newland N910"))
        override suspend fun selectDevice(id: String): Boolean {
            selected = id
            return id == "AA:BB"
        }
    }

    @Test
    fun `pairedDevices lists discovered readers`() = runTest {
        val reader = FakePairableReader()
        val devices = reader.pairedDevices()
        assertEquals(1, devices.size)
        assertEquals(PairedDeviceInfo("AA:BB", "Newland N910"), devices.first())
    }

    @Test
    fun `selectDevice reports success only for a known id`() = runTest {
        val reader = FakePairableReader()
        assertTrue(reader.selectDevice("AA:BB"))
        assertFalse(reader.selectDevice("unknown"))
        assertEquals("unknown", reader.selected)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd device-sdk-api && ../gradlew test --tests "com.cashup.devicesdk.PairableCardReaderTest"`
Expected: FAIL — compile error, `PairableCardReader`/`PairedDeviceInfo` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cashup.devicesdk

data class PairedDeviceInfo(val id: String, val name: String)

/**
 * Kontrak opsional untuk [CardReader] yang butuh device fisik dipilih secara
 * eksplisit sebelum bisa [CardReader.transact] — reader Bluetooth eksternal,
 * bukan terminal built-in. Consumer mengecek lewat `as?`, bukan seluruh
 * [CardReader] mengimplementasikannya.
 */
interface PairableCardReader {
    suspend fun pairedDevices(): List<PairedDeviceInfo>
    suspend fun selectDevice(id: String): Boolean
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd device-sdk-api && ../gradlew test --tests "com.cashup.devicesdk.PairableCardReaderTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add device-sdk-api/src/main/kotlin/com/cashup/devicesdk/PairableCardReader.kt device-sdk-api/src/test/kotlin/com/cashup/devicesdk/PairableCardReaderTest.kt
git commit -m "feat(device-sdk-api): add PairableCardReader for reader selection"
```

---

### Task 3: Scaffold the `device-sdk-mpos` Gradle module

**Files:**
- Modify: `settings.gradle.kts:` add `include(":device-sdk-mpos")`
- Create: `device-sdk-mpos/build.gradle.kts`
- Create: `device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos/.gitkeep` (placeholder dir — deleted once Task 4 adds real files)

**Interfaces:**
- Produces: an empty, compiling Android library module ready for Tasks 4–6 to fill in.

- [ ] **Step 1: Add the module to `settings.gradle.kts`**

Read the file first, then add the new include line, keeping the existing order and comment intact:

```kotlin
include(":device-sdk-api")
include(":signing-core")
include(":device-sdk-edcsdk")
include(":device-sdk-mpos")
include(":provisioning-core")
```

- [ ] **Step 2: Create `device-sdk-mpos/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.devicesdk.mpos"
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

    testOptions { unitTests { isIncludeAndroidResources = false } }
}

dependencies {
    api(project(":device-sdk-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.10.1")

    // AAR vendor dari edc-sdk (Task 1). Selalu implementation, tidak pernah
    // api: tipe com.lib.core.*/com.lib.device.* tidak boleh bocor melewati
    // module ini.
    implementation(group = "", name = "core-release_1.0.63", ext = "aar")
    implementation(group = "", name = "logger-release_1.0.2", ext = "aar")
    implementation(group = "", name = "other-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "newland-mpos-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "topwise-mpos-release-core_1.0.63", ext = "aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

- [ ] **Step 3: Create the placeholder source directory and verify the module compiles empty**

```bash
mkdir -p "device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos"
touch "device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos/.gitkeep"
./gradlew :device-sdk-mpos:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. If the Kotlin compiler reports an unresolved reference while later tasks add real code against `com.lib.device.*`/`com.lib.core.*` types, that means an additional AAR (beyond the five above) is needed — check `edc-sdk`'s `:other`/`:newland-mpos`/`:topwise-mpos` `build.gradle` dependency blocks (already read during planning — `:other` also needs `emvlib-release_1.0.2.aar` and `com.github.felHR85:UsbSerial:6.1.0` as **its own** implementation deps, which do not propagate to consumers; only add them here if a specific class from those turns out to be referenced by code this plan writes, which Tasks 4–6 do not expect).

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts device-sdk-mpos/build.gradle.kts device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos/.gitkeep
git commit -m "build: scaffold device-sdk-mpos module"
```

---

### Task 4: `MposEmvGateway` — EMV transact plumbing over an already-connected `DeviceSession`

**Files:**
- Create: `device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos/MposEmvGateway.kt`

**Interfaces:**
- Consumes: `com.cashup.devicesdk.{CardTransactionData, CardType}` (from `device-sdk-api`), `com.lib.device.core.session.DeviceSession` (has `val emv: BaseEmvConfiguration?`), `com.lib.core.emv.{BaseEmvConfiguration, TransactionResponse, CardModeType, TrackData, CvmEnum, PinConfig, PinInputListener}`, `com.lib.core.SDKManager.getCurrentActivity()`.
- Produces: `internal interface EmvCallback` (7 methods — `onCard`, `onPinRequested`, `onPinProgress`, `onAppletSelection`, `onOnline`, `onError`, `onFinish`), `internal interface MposEmvGateway { fun start(amount: Long, callback: EmvCallback); fun stop(); fun selectApplet(index: Int) }`, `internal class RealMposEmvGateway(session: DeviceSession) : MposEmvGateway` — consumed by `MposCardReader` in Task 5.

This is a direct structural port of `device-sdk-edcsdk/src/main/kotlin/com/cashup/devicesdk/edcsdk/EmvGateway.kt` (already proven working in this repo against the exact same `com.lib.core.emv.*` interfaces), swapping only where the `emv: BaseEmvConfiguration` instance comes from — a `DeviceSession` obtained via mPOS pairing instead of `SDKManager.requireHelper()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cashup.devicesdk.mpos

import com.cashup.devicesdk.CardTransactionData
import com.lib.core.emv.BaseEmvConfiguration
import com.lib.core.emv.TransactionResponse
import com.lib.device.core.session.DeviceSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test

class MposEmvGatewayTest {

    @Test
    fun `start forwards to session emv startReadCard with the same amount`() {
        val emv = mockk<BaseEmvConfiguration>(relaxed = true)
        val session = mockk<DeviceSession> { every { this@mockk.emv } returns emv }
        val responseSlot = slot<TransactionResponse>()
        every { emv.startReadCard(10_000L, capture(responseSlot)) } returns Unit

        val gateway: MposEmvGateway = RealMposEmvGateway(session)
        gateway.start(10_000L, NoopEmvCallback)

        verify { emv.startReadCard(10_000L, any()) }
        assertTrue(responseSlot.isCaptured)
    }

    @Test
    fun `stop calls session emv stopEmv and swallows exceptions`() {
        val emv = mockk<BaseEmvConfiguration>(relaxed = true)
        every { emv.stopEmv() } throws IllegalStateException("kernel sudah berhenti")
        val session = mockk<DeviceSession> { every { this@mockk.emv } returns emv }

        val gateway: MposEmvGateway = RealMposEmvGateway(session)
        gateway.stop() // must not throw
    }

    private object NoopEmvCallback : EmvCallback {
        override fun onCard(data: CardTransactionData) = Unit
        override fun onPinRequested() = Unit
        override fun onPinProgress(length: Int) = Unit
        override fun onAppletSelection(applets: List<String>) = Unit
        override fun onOnline(data: CardTransactionData): String? = null
        override fun onError(code: Int, message: String?) = Unit
        override fun onFinish() = Unit
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :device-sdk-mpos:testDebugUnitTest --tests "com.cashup.devicesdk.mpos.MposEmvGatewayTest"`
Expected: FAIL — `MposEmvGateway`/`RealMposEmvGateway`/`EmvCallback` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cashup.devicesdk.mpos

import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardType
import com.lib.core.SDKManager
import com.lib.core.emv.BaseEmvConfiguration
import com.lib.core.emv.CardModeType
import com.lib.core.emv.CvmEnum
import com.lib.core.emv.PinConfig
import com.lib.core.emv.PinInputListener
import com.lib.core.emv.TrackData
import com.lib.core.emv.TransactionResponse
import com.lib.device.core.session.DeviceSession

internal interface EmvCallback {
    fun onCard(data: CardTransactionData)
    fun onPinRequested()
    fun onPinProgress(length: Int)
    fun onAppletSelection(applets: List<String>)
    fun onOnline(data: CardTransactionData): String?
    fun onError(code: Int, message: String?)
    fun onFinish()
}

internal interface MposEmvGateway {
    fun start(amount: Long, callback: EmvCallback)
    fun stop()
    fun selectApplet(index: Int)
}

/** Adapter EMV di atas [DeviceSession] mPOS yang sudah terhubung (lihat [MposCardReader]). */
internal class RealMposEmvGateway(private val session: DeviceSession) : MposEmvGateway {
    private val emv: BaseEmvConfiguration
        get() = requireNotNull(session.emv) { "Device session mPOS ini tidak menyediakan EMV" }

    override fun start(amount: Long, callback: EmvCallback) {
        val activity = SDKManager.getCurrentActivity()
        val response = object : TransactionResponse {
            override fun onSearchCard(cardType: CardModeType, trackData: TrackData, iccData: String?) {
                callback.onCard(snapshot(cardType, trackData, iccData))
            }

            override fun onPinEntry(code: Int) {
                val cvm = CvmEnum.values().getOrNull(code) ?: CvmEnum.EMV_CVMFLAG_NO_CVM
                if (cvm == CvmEnum.EMV_CVMFLAG_NO_CVM || cvm == CvmEnum.EMV_CVMFLAG_SIGNATURE) {
                    emv.skipPin()
                    return
                }
                callback.onPinRequested()
                emv.startPinInput(
                    PinConfig(
                        isKeyboardDefault = true,
                        pinLen = 6,
                        pan = emv.cardData.pan,
                        isRandom = true,
                        pinMin = 4,
                        timeOut = 60,
                    ),
                    object : PinInputListener {
                        override fun onDisplayPin(pin: String) = callback.onPinProgress(pin.length)
                        override fun onSuccess(pinBlock: ByteArray) = Unit
                        override fun onError(code: Int) = callback.onError(code, "PIN gagal")
                        override fun onTimeout() = callback.onError(PIN_TIMEOUT, "Waktu input PIN habis")
                        override fun onCancel() = callback.onError(PIN_CANCELLED, "Input PIN dibatalkan")
                    },
                )
            }

            override fun onError(code: Int, message: String?) = callback.onError(code, message)
            override fun onFinish() = callback.onFinish()

            override fun onOnlineProcess(tlv: String?): String? {
                val data = snapshot(
                    emv.cardData.cardModeType ?: CardModeType.IC,
                    emv.cardData.trackData ?: TrackData(),
                    emv.cardData.iccData,
                )
                return callback.onOnline(data)
            }

            override fun appletSelect(list: List<String>) = callback.onAppletSelection(list)
        }

        if (emv.isConfiguration || activity == null) {
            emv.startReadCard(amount, response)
        } else {
            emv.configuration(activity, { emv.startReadCard(amount, response) }) {
                callback.onError(EMV_CONFIG_FAILED, "Konfigurasi EMV gagal")
            }
        }
    }

    override fun stop() {
        runCatching { emv.stopEmv() }
    }

    override fun selectApplet(index: Int) {
        emv.setApplet(index)
    }

    private fun snapshot(mode: CardModeType, tracks: TrackData, iccData: String?): CardTransactionData {
        val track2 = tracks.track2Data
            ?: emv.cardData.trackData?.track2Data
            ?: emv.getTagList(intArrayOf(0x57))?.let(::track2FromTag57)
            ?: ""
        return CardTransactionData(
            track2 = track2.trimEnd('F'),
            cardType = when (mode) {
                CardModeType.IC -> CardType.CHIP
                CardModeType.RF -> CardType.TAP
                CardModeType.MAG -> CardType.SWIPE
            },
            iccData = iccData ?: emv.cardData.iccData,
            pinBlock = emv.cardData.pinBlock
                ?.takeUnless { it.equals("FFFFFFFFFFFFFFFF", ignoreCase = true) }
                ?.hexToBytesOrNull(),
        )
    }

    private fun track2FromTag57(value: String): String =
        value.removePrefix("57").let { encoded ->
            if (encoded.length >= 2 && encoded.take(2).toIntOrNull(16) != null) encoded.drop(2) else encoded
        }.trimEnd('F')

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0 || !all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private companion object {
        const val EMV_CONFIG_FAILED = -10_001
        const val PIN_TIMEOUT = -10_002
        const val PIN_CANCELLED = -10_003
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :device-sdk-mpos:testDebugUnitTest --tests "com.cashup.devicesdk.mpos.MposEmvGatewayTest"`
Expected: PASS (2 tests). If `emv.startReadCard`/`emv.configuration`/`CardModeType`/`CvmEnum`/`PinConfig` fail to resolve against the mockk-based `BaseEmvConfiguration`, re-check the exact member signatures in the vendored `core-release_1.0.63.aar` — they are asserted here from `edc-sdk`'s real `com/lib/core/emv/BaseEmvConfiguration.kt` source read during planning, but mockk's relaxed mode still requires the class shape to exist on the classpath (Task 3, Step 3's compile check).

- [ ] **Step 5: Commit**

```bash
git add device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos/MposEmvGateway.kt device-sdk-mpos/src/test/kotlin/com/cashup/devicesdk/mpos/MposEmvGatewayTest.kt
git commit -m "feat(device-sdk-mpos): add EMV transact plumbing over a paired DeviceSession"
```

---

### Task 5: `MposCardReader` — pairing + `CardReader`

**Files:**
- Create: `device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos/MposCardReader.kt`

**Interfaces:**
- Consumes: `com.cashup.devicesdk.{CardReader, PairableCardReader, PairedDeviceInfo, CardReadResult, CardAuthorization, CardTransactionRequest, CardTransactionListener, CardTransactionEvent}` (from `device-sdk-api`, Task 2), `MposEmvGateway`/`RealMposEmvGateway`/`EmvCallback` (Task 4), `com.lib.device.core.channel.Channel`, `com.lib.device.core.manager.DeviceConnectionManager`, `com.lib.device.core.model.DeviceCandidate`, `com.lib.device.core.session.DeviceSession`, `com.lib.device.channel.mpos.MPOSConnector`.
- Produces: `class MposCardReader(context: Context) : CardReader, PairableCardReader` — consumed by `MposConnector` (Task 6).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cashup.devicesdk.mpos

import com.cashup.devicesdk.CardAuthorization
import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardTransactionListener
import com.cashup.devicesdk.CardTransactionRequest
import com.cashup.devicesdk.PairedDeviceInfo
import com.lib.device.core.channel.Channel
import com.lib.device.core.manager.DeviceConnectionManager
import com.lib.device.core.model.DeviceCandidate
import com.lib.device.core.session.DeviceSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MposCardReaderTest {

    @Test
    fun `pairedDevices maps scan results to PairedDeviceInfo`() = runTest {
        val candidate = DeviceCandidate(id = "AA:BB", name = "Newland N910", channel = Channel.MPOS)
        val connectionManager = mockk<DeviceConnectionManager> {
            coEvery { scan(Channel.MPOS, any()) } returns listOf(candidate)
        }
        val reader = MposCardReader(context = mockk(relaxed = true), connectionManager = connectionManager)

        val devices = reader.pairedDevices()

        assertEquals(listOf(PairedDeviceInfo("AA:BB", "Newland N910")), devices)
    }

    @Test
    fun `selectDevice returns false for an id that was never scanned`() = runTest {
        val connectionManager = mockk<DeviceConnectionManager>()
        val reader = MposCardReader(context = mockk(relaxed = true), connectionManager = connectionManager)

        assertFalse(reader.selectDevice("never-scanned"))
    }

    @Test
    fun `selectDevice connects and returns the session's isAlive value`() = runTest {
        val candidate = DeviceCandidate(id = "AA:BB", name = "Newland N910", channel = Channel.MPOS)
        val session = mockk<DeviceSession> { every { isAlive } returns true; every { emv } returns null }
        val connectionManager = mockk<DeviceConnectionManager> {
            coEvery { scan(Channel.MPOS, any()) } returns listOf(candidate)
            coEvery { connect(Channel.MPOS, candidate) } returns session
        }
        val reader = MposCardReader(context = mockk(relaxed = true), connectionManager = connectionManager)
        reader.pairedDevices()

        assertTrue(reader.selectDevice("AA:BB"))
    }

    @Test
    fun `transact fails fast when no device has been selected yet`() = runTest {
        val reader = MposCardReader(context = mockk(relaxed = true), connectionManager = mockk())

        val result = reader.transact(
            CardTransactionRequest(10_000, 1_000),
            object : CardTransactionListener {
                override suspend fun authorize(card: CardTransactionData) = CardAuthorization(true, "00")
            },
        )

        assertEquals(CardReadResult.Failure("Belum ada reader mPOS terpilih"), result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :device-sdk-mpos:testDebugUnitTest --tests "com.cashup.devicesdk.mpos.MposCardReaderTest"`
Expected: FAIL — `MposCardReader` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cashup.devicesdk.mpos

import android.content.Context
import com.cashup.devicesdk.CardAuthorization
import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardTransactionEvent
import com.cashup.devicesdk.CardTransactionListener
import com.cashup.devicesdk.CardTransactionRequest
import com.cashup.devicesdk.PairableCardReader
import com.cashup.devicesdk.PairedDeviceInfo
import com.lib.device.channel.mpos.MPOSConnector
import com.lib.device.core.channel.Channel
import com.lib.device.core.manager.DeviceConnectionManager
import com.lib.device.core.model.DeviceCandidate
import com.lib.device.core.session.DeviceSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Reader mPOS Bluetooth eksternal (Newland/Topwise). Perlu [selectDevice] sebelum [transact]. */
class MposCardReader internal constructor(
    private val context: Context,
    private val connectionManager: DeviceConnectionManager,
) : CardReader, PairableCardReader {

    constructor(context: Context) : this(
        context,
        DeviceConnectionManager(listOf(MPOSConnector(context.applicationContext))),
    )

    @Volatile private var gateway: MposEmvGateway? = null
    @Volatile private var active: Continuation<CardReadResult>? = null
    @Volatile private var authorization: CardAuthorization? = null
    private val completed = AtomicBoolean(false)
    private var lastScan: List<DeviceCandidate> = emptyList()

    override suspend fun pairedDevices(): List<PairedDeviceInfo> {
        lastScan = connectionManager.scan(Channel.MPOS)
        return lastScan.map { PairedDeviceInfo(it.id, it.name) }
    }

    override suspend fun selectDevice(id: String): Boolean {
        val candidate = lastScan.find { it.id == id } ?: return false
        return try {
            val session = connectionManager.connect(Channel.MPOS, candidate)
            gateway = RealMposEmvGateway(session)
            session.isAlive
        } catch (unavailable: Exception) {
            gateway = null
            false
        }
    }

    override suspend fun transact(
        request: CardTransactionRequest,
        listener: CardTransactionListener,
    ): CardReadResult {
        val gw = gateway ?: return CardReadResult.Failure("Belum ada reader mPOS terpilih")
        require(request.amount > 0) { "Nominal harus lebih besar dari nol" }
        require(request.timeoutMillis > 0) { "Timeout harus lebih besar dari nol" }
        check(active == null) { "Transaksi kartu lain masih berjalan" }
        completed.set(false)
        authorization = null

        return try {
            withTimeout(request.timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    active = continuation
                    continuation.invokeOnCancellation { gw.stop() }
                    listener.onEvent(CardTransactionEvent.Connecting)
                    listener.onEvent(CardTransactionEvent.WaitingForCard)
                    runCatching { gw.start(Math.multiplyExact(request.amount, 100L), callbacks(listener)) }
                        .onFailure { finish(CardReadResult.Failure(it.message ?: "Gagal memulai EMV")) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            gw.stop()
            CardReadResult.Failure("Waktu membaca kartu habis")
        } finally {
            active = null
        }
    }

    override fun cancel() {
        gateway?.stop()
        finish(CardReadResult.Cancelled)
    }

    private fun callbacks(listener: CardTransactionListener) = object : EmvCallback {
        override fun onCard(data: CardTransactionData) {
            if (data.track2.isBlank()) {
                finish(CardReadResult.Failure("Track 2 kartu tidak terbaca"))
                return
            }
            listener.onEvent(CardTransactionEvent.CardDetected(data.cardType))
        }

        override fun onPinRequested() = listener.onEvent(CardTransactionEvent.PinRequested)
        override fun onPinProgress(length: Int) = listener.onEvent(CardTransactionEvent.PinProgress(length))

        override fun onAppletSelection(applets: List<String>) {
            val selected = listener.selectApplet(applets).coerceIn(applets.indices)
            gateway?.selectApplet(selected)
        }

        override fun onOnline(data: CardTransactionData): String? {
            listener.onEvent(CardTransactionEvent.Authorizing)
            val result = runCatching {
                runBlocking(Dispatchers.IO) { listener.authorize(data) }
            }.getOrElse {
                finish(CardReadResult.Failure(it.message ?: "Otorisasi transaksi gagal"))
                return declineTlv("96")
            }
            authorization = result
            listener.onEvent(CardTransactionEvent.Completing)
            return declineTlv(result.responseCode)
        }

        override fun onError(code: Int, message: String?) {
            gateway?.stop()
            finish(CardReadResult.Failure(message?.takeIf(String::isNotBlank) ?: "EMV gagal ($code)"))
        }

        override fun onFinish() {
            gateway?.stop()
            val result = authorization
            finish(if (result == null) CardReadResult.Failure("EMV selesai tanpa otorisasi host")
            else CardReadResult.Success(result))
        }
    }

    private fun declineTlv(responseCode: String): String {
        val rc = responseCode.padStart(2, '0').takeLast(2)
        return "8A02" + rc.toByteArray(Charsets.US_ASCII).joinToString("") { "%02X".format(it) }
    }

    private fun finish(result: CardReadResult) {
        if (!completed.compareAndSet(false, true)) return
        val continuation = active
        if (continuation != null) {
            active = null
            continuation.resume(result)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :device-sdk-mpos:testDebugUnitTest --tests "com.cashup.devicesdk.mpos.MposCardReaderTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos/MposCardReader.kt device-sdk-mpos/src/test/kotlin/com/cashup/devicesdk/mpos/MposCardReaderTest.kt
git commit -m "feat(device-sdk-mpos): add MposCardReader with pairing"
```

---

### Task 6: `MposConnector` — brand registration + Bluetooth capability probe

**Files:**
- Create: `device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos/MposConnector.kt`
- Delete: `device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos/.gitkeep` (no longer the only file in the package)

**Interfaces:**
- Consumes: `com.cashup.devicesdk.{DeviceSdk, Capability, Printer, Scanner}` (device-sdk-api), `MposCardReader` (Task 5), `com.lib.device.channel.mpos.{BrandRegistry, MposReaderDevice}`, `com.lib.device.newland.mpos.NewlandBlueHelper`, `com.lib.device.topwise.mpos.TopwiseBlueHelper`.
- Produces: `object MposConnector { fun tryConnect(context: Context): DeviceSdk? }`, `internal class MposDeviceSdk(context: Context) : DeviceSdk` — consumed by `DeviceSdkFactory` (Task 8).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cashup.devicesdk.mpos

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.cashup.devicesdk.Capability
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MposConnectorTest {

    @Test
    fun `tryConnect returns null when there is no Bluetooth adapter`() {
        val manager = mockk<BluetoothManager> { every { adapter } returns null }
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        }

        assertNull(MposConnector.tryConnect(context))
    }

    @Test
    fun `tryConnect returns an mpos DeviceSdk when a Bluetooth adapter is present`() {
        val manager = mockk<BluetoothManager> { every { adapter } returns mockk<BluetoothAdapter>() }
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        }

        val sdk = MposConnector.tryConnect(context)

        assertNotNull(sdk)
        assertEquals("mpos", sdk!!.vendorId)
        assertEquals(setOf(Capability.CARD_READ), sdk.capabilities)
        assertNull(sdk.printer)
        assertNull(sdk.scanner)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :device-sdk-mpos:testDebugUnitTest --tests "com.cashup.devicesdk.mpos.MposConnectorTest"`
Expected: FAIL — `MposConnector` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cashup.devicesdk.mpos

import android.bluetooth.BluetoothManager
import android.content.Context
import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner
import com.lib.device.channel.mpos.BrandRegistry
import com.lib.device.channel.mpos.MposReaderDevice
import com.lib.device.newland.mpos.NewlandBlueHelper
import com.lib.device.topwise.mpos.TopwiseBlueHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Terhubung" di sini berarti device ini SANGGUP mPOS (adapter Bluetooth ada),
 * bukan bahwa reader fisik sudah terpasang -- itu terjadi belakangan lewat
 * [com.cashup.devicesdk.PairableCardReader] setelah UI memilih device (spec
 * §5). Tidak pernah meminta izin runtime di sini.
 */
object MposConnector {
    private val registered = AtomicBoolean(false)

    fun tryConnect(context: Context): DeviceSdk? {
        val appContext = context.applicationContext
        if (!hasBluetoothAdapter(appContext)) return null
        registerBrandsOnce()
        return MposDeviceSdk(appContext)
    }

    private fun registerBrandsOnce() {
        if (!registered.compareAndSet(false, true)) return
        BrandRegistry.register(MposReaderDevice.NEWLAND, provider = { NewlandBlueHelper() }, replace = true)
        BrandRegistry.register(MposReaderDevice.TOPWISE, provider = { TopwiseBlueHelper() }, replace = true)
    }

    private fun hasBluetoothAdapter(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter != null
    }
}

internal class MposDeviceSdk(context: Context) : DeviceSdk {
    override val vendorId: String = "mpos"
    override val capabilities: Set<Capability> = setOf(Capability.CARD_READ)
    override val cardReader: MposCardReader = MposCardReader(context)
    override val printer: Printer? = null
    override val scanner: Scanner? = null
}
```

- [ ] **Step 4: Run test to verify it passes, then delete the placeholder**

```bash
./gradlew :device-sdk-mpos:testDebugUnitTest --tests "com.cashup.devicesdk.mpos.MposConnectorTest"
rm "device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos/.gitkeep"
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add -A device-sdk-mpos/src/main/kotlin/com/cashup/devicesdk/mpos/
git commit -m "feat(device-sdk-mpos): add MposConnector with brand registration"
```

---

### Task 7: `EdcSdkConnector` — probe wrapper for the existing built-in adapter

**Files:**
- Create: `device-sdk-edcsdk/src/main/kotlin/com/cashup/devicesdk/edcsdk/EdcSdkConnector.kt`
- Create: `device-sdk-edcsdk/src/test/kotlin/com/cashup/devicesdk/edcsdk/EdcSdkConnectorTest.kt`

**Interfaces:**
- Consumes: `com.cashup.devicesdk.{DeviceSdk, Capability, Printer, Scanner}` (device-sdk-api), existing `EdcSdkCardReader`, `EdcSdkScanner` (both already in this module, untouched), `com.lib.core.SDKManager.autoDetectDevice(context, callback)`.
- Produces: `object EdcSdkConnector { suspend fun tryConnect(context: Context): DeviceSdk? }`, `internal class EdcSdkDeviceSdk(context: Context) : DeviceSdk` — consumed by `DeviceSdkFactory` (Task 8).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.Capability
import com.lib.core.SDKManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EdcSdkConnectorTest {

    @After
    fun tearDown() {
        unmockkObject(SDKManager)
    }

    @Test
    fun `tryConnect returns null when autoDetectDevice reports not connected`() = runTest {
        val context = mockk<Context> { every { applicationContext } returns this }
        mockkObject(SDKManager)
        val callbackSlot = slot<(Boolean) -> Unit>()
        every { SDKManager.autoDetectDevice(any(), capture(callbackSlot)) } answers { callbackSlot.captured(false) }

        assertNull(EdcSdkConnector.tryConnect(context))
    }

    @Test
    fun `tryConnect returns an edcsdk DeviceSdk when autoDetectDevice reports connected`() = runTest {
        val context = mockk<Context> { every { applicationContext } returns this }
        mockkObject(SDKManager)
        val callbackSlot = slot<(Boolean) -> Unit>()
        every { SDKManager.autoDetectDevice(any(), capture(callbackSlot)) } answers { callbackSlot.captured(true) }

        val sdk = EdcSdkConnector.tryConnect(context)

        assertNotNull(sdk)
        assertEquals("edcsdk", sdk!!.vendorId)
        assertEquals(setOf(Capability.CARD_READ, Capability.SCAN_QR), sdk.capabilities)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :device-sdk-edcsdk:testDebugUnitTest --tests "com.cashup.devicesdk.edcsdk.EdcSdkConnectorTest"`
Expected: FAIL — `EdcSdkConnector` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner
import com.lib.core.SDKManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object EdcSdkConnector {
    suspend fun tryConnect(context: Context): DeviceSdk? = suspendCancellableCoroutine { continuation ->
        val appContext = context.applicationContext
        SDKManager.autoDetectDevice(appContext) { connected ->
            if (continuation.isActive) {
                continuation.resume(if (connected) EdcSdkDeviceSdk(appContext) else null)
            }
        }
    }
}

internal class EdcSdkDeviceSdk(context: Context) : DeviceSdk {
    override val vendorId: String = "edcsdk"
    override val capabilities: Set<Capability> = setOf(Capability.CARD_READ, Capability.SCAN_QR)
    override val cardReader: EdcSdkCardReader = EdcSdkCardReader(context)
    override val printer: Printer? = null
    override val scanner: Scanner = EdcSdkScanner(context)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :device-sdk-edcsdk:testDebugUnitTest --tests "com.cashup.devicesdk.edcsdk.EdcSdkConnectorTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add device-sdk-edcsdk/src/main/kotlin/com/cashup/devicesdk/edcsdk/EdcSdkConnector.kt device-sdk-edcsdk/src/test/kotlin/com/cashup/devicesdk/edcsdk/EdcSdkConnectorTest.kt
git commit -m "feat(device-sdk-edcsdk): add EdcSdkConnector probe wrapper"
```

---

### Task 8: `device-sdk-factory` module — the single fan-out point

**Files:**
- Modify: `settings.gradle.kts` — add `include(":device-sdk-factory")`
- Create: `device-sdk-factory/build.gradle.kts`
- Create: `device-sdk-factory/src/main/kotlin/com/cashup/devicesdk/factory/DeviceSdkFactory.kt`
- Create: `device-sdk-factory/src/test/kotlin/com/cashup/devicesdk/factory/DeviceSdkFactoryTest.kt`

**Interfaces:**
- Consumes: `EdcSdkConnector` (Task 7), `MposConnector` (Task 6), `com.cashup.devicesdk.DeviceSdk` (device-sdk-api).
- Produces: `class NoDeviceSdkAvailableException : Exception`, `class DeviceSdkFactory(context: Context) { suspend fun connect(): DeviceSdk }` — consumed by `app` (Task 10).

- [ ] **Step 1: Add the module and its `build.gradle.kts`**

Add to `settings.gradle.kts` (after `:device-sdk-mpos`):
```kotlin
include(":device-sdk-mpos")
include(":device-sdk-factory")
```

```kotlin
// device-sdk-factory/build.gradle.kts
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.devicesdk.factory"
    compileSdk = (project.property("cashup.compileSdk") as String).toInt()
    defaultConfig { minSdk = (project.property("cashup.minSdk") as String).toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
    testOptions { unitTests { isIncludeAndroidResources = false } }
}

dependencies {
    // Satu-satunya module yang boleh depend ke device-sdk-edcsdk DAN
    // device-sdk-mpos sekaligus (spec §3, ditegakkan Task 9).
    api(project(":device-sdk-api"))
    implementation(project(":device-sdk-edcsdk"))
    implementation(project(":device-sdk-mpos"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.cashup.devicesdk.factory

import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceSdkFactoryTest {

    private class FakeDeviceSdk(override val vendorId: String) : DeviceSdk {
        override val capabilities: Set<Capability> = emptySet()
        override val cardReader: CardReader? = null
        override val printer: Printer? = null
        override val scanner: Scanner? = null
    }

    @Test
    fun `connect returns the first candidate that connects`() = runTest {
        val fake = FakeDeviceSdk("fake")
        val factory = DeviceSdkFactory(listOf({ null }, { fake }, { error("should not be reached") }))

        assertEquals(fake, factory.connect())
    }

    @Test
    fun `connect treats a candidate Exception as unavailable and tries the next one`() = runTest {
        val fake = FakeDeviceSdk("fake")
        val factory = DeviceSdkFactory(listOf({ throw IllegalStateException("vendor bind failed") }, { fake }))

        assertEquals(fake, factory.connect())
    }

    @Test
    fun `connect throws NoDeviceSdkAvailableException when nothing connects`() = runTest {
        val factory = DeviceSdkFactory(listOf({ null }, { null }))

        assertThrows(NoDeviceSdkAvailableException::class.java) {
            kotlinx.coroutines.runBlocking { factory.connect() }
        }
    }

    @Test
    fun `connect lets an Error propagate instead of treating it as unavailable`() = runTest {
        val factory = DeviceSdkFactory(listOf({ throw OutOfMemoryError("simulated build defect") }))

        assertThrows(OutOfMemoryError::class.java) {
            kotlinx.coroutines.runBlocking { factory.connect() }
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :device-sdk-factory:testDebugUnitTest --tests "com.cashup.devicesdk.factory.DeviceSdkFactoryTest"`
Expected: FAIL — `DeviceSdkFactory` unresolved.

- [ ] **Step 4: Write the implementation**

```kotlin
package com.cashup.devicesdk.factory

import android.content.Context
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.edcsdk.EdcSdkConnector
import com.cashup.devicesdk.mpos.MposConnector

class NoDeviceSdkAvailableException : Exception("Tidak ada device SDK yang tersedia di device ini")

/**
 * Satu-satunya titik fan-out (spec §5). Urutan berjenjang, bukan race murni:
 * EDC built-in dicoba dulu (deteksi cepat & deterministik), mPOS baru dicoba
 * kalau tidak ada -- lihat spec §5 untuk kenapa "connect" mPOS bukan berarti
 * reader fisik sudah terpasang.
 */
class DeviceSdkFactory internal constructor(
    private val candidates: List<suspend () -> DeviceSdk?>,
) {
    constructor(context: Context) : this(
        listOf(
            { EdcSdkConnector.tryConnect(context) },
            { MposConnector.tryConnect(context) },
        ),
    )

    suspend fun connect(): DeviceSdk {
        for (candidate in candidates) {
            // Exception = "vendor ini bilang tidak", lanjut ke kandidat
            // berikutnya. Error (mis. AAR salah dikonfigurasi) sengaja TIDAK
            // ditangkap di sini -- harus meledakkan build/CI, bukan diam-diam
            // dianggap "vendor tidak ada" (spec §6).
            val result = try {
                candidate()
            } catch (unavailable: Exception) {
                null
            }
            if (result != null) return result
        }
        throw NoDeviceSdkAvailableException()
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :device-sdk-factory:testDebugUnitTest --tests "com.cashup.devicesdk.factory.DeviceSdkFactoryTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts device-sdk-factory/
git commit -m "feat(device-sdk-factory): add DeviceSdkFactory, the single fan-out point"
```

---

### Task 9: `checkModuleBoundaries` Gradle task

**Files:**
- Create: `gradle/module-boundaries.gradle.kts`
- Modify: `build.gradle.kts` (root) — add `apply(from = "gradle/module-boundaries.gradle.kts")`

**Interfaces:**
- Produces: `./gradlew checkModuleBoundaries` — a standalone `verification`-group task, not wired into `check`/`build` (matches `edc-tms-agent`'s convention: invoked explicitly, e.g. in CI alongside `:app:assembleDebug`).

This is adapted from `edc-tms-agent/gradle/module-boundaries.gradle.kts` (read in full during planning), with `allowedProjectDeps` rebuilt from this repo's **actual** dependency graph (verified by reading every module's `build.gradle.kts` during planning — see Global Constraints), not copied from the TMS project's module list.

- [ ] **Step 1: Create `gradle/module-boundaries.gradle.kts`**

```kotlin
// Menegakkan aturan dependency module dari spec 2026-09-22 (§3).
//
// Diperiksa lewat build, bukan review, karena setiap pelanggaran tidak
// terlihat di diff: menambah `implementation(project(":device-sdk-mpos"))`
// ke device-sdk-edcsdk terlihat wajar sendirian, cuma keseluruhan graph yang
// menunjukkan itu salah.
//
// Run: ./gradlew checkModuleBoundaries

val allowedProjectDeps: Map<String, Set<String>> = mapOf(
    // Kontrak murni -- tanpa dependency internal apa pun.
    ":common-core" to emptySet(),
    ":device-sdk-api" to emptySet(),
    ":signing-core" to emptySet(),

    // Adapter vendor -- masing-masing lihat SATU port (device-sdk-api).
    ":device-sdk-edcsdk" to setOf(":device-sdk-api"),
    ":device-sdk-mpos" to setOf(":device-sdk-api"),

    // Satu-satunya titik fan-out. SATU-SATUNYA module yang boleh lihat kedua
    // adapter vendor sekaligus.
    ":device-sdk-factory" to setOf(":device-sdk-api", ":device-sdk-edcsdk", ":device-sdk-mpos"),

    ":provisioning-core" to setOf(":common-core", ":device-sdk-api", ":signing-core"),
    ":cdcp-core" to setOf(":common-core", ":device-sdk-api", ":signing-core"),

    // Shell -- wiring saja. device-sdk-edcsdk tetap ada di sini untuk
    // TerminalKeyInstaller/DukptKeyProvider/Scanner/SerialNumberProvider, yang
    // TIDAK lewat device-sdk-factory (spec §5/§8 -- di luar scope factory ini).
    ":app" to setOf(
        ":provisioning-core", ":cdcp-core", ":device-sdk-edcsdk", ":device-sdk-factory",
    ),
)

// Vendor SDK tidak boleh bocor lewat batas modulnya: dependency AAR vendor
// selalu implementation, tidak pernah api.
val noApiScope: List<String> = listOf(":device-sdk-edcsdk", ":device-sdk-mpos")

// Hanya periksa configuration yang benar-benar ditulis manusia di
// `dependencies {}`. Classpath hasil resolusi (…CompileClasspath,
// …RuntimeClasspath) turunan, bukan deklarasi.
val declarableSuffixes = listOf("implementation", "api", "compileOnly", "runtimeOnly")

fun isDeclarable(name: String): Boolean = declarableSuffixes.any { suffix ->
    name == suffix || name.endsWith(suffix.replaceFirstChar { it.uppercaseChar() })
}

tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Fails if any module depends on something the design forbids (spec 2026-09-22 §3)."
    doLast {
        val violations = mutableListOf<String>()

        rootProject.subprojects.forEach { p ->
            val allowed = allowedProjectDeps[p.path]
            if (allowed == null) {
                violations += "${p.path}: not listed in module-boundaries. Add it deliberately, with its allowed dependencies."
                return@forEach
            }

            p.configurations.filter { isDeclarable(it.name) }.forEach { cfg ->
                cfg.dependencies.forEach { d ->
                    if (d is org.gradle.api.artifacts.ProjectDependency) {
                        val target = d.dependencyProject.path
                        if (target != p.path && target !in allowed) {
                            violations += "${p.path} --> $target  (via '${cfg.name}') is not an allowed dependency"
                        }
                    }
                }
            }

            if (p.path in noApiScope) {
                val api = p.configurations.findByName("api")
                val leaked = api?.dependencies
                    ?.filterNot { it.group == "org.jetbrains.kotlin" }
                    ?.map { "${it.group ?: ""}:${it.name}" }
                    .orEmpty()
                if (leaked.isNotEmpty()) {
                    violations += "${p.path}: uses 'api' scope for ${leaked.joinToString()}. Vendor modules must keep their SDK implementation-scoped so it cannot leak to consumers."
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "Module boundary violations (spec 2026-09-22 §3):\n  - " + violations.joinToString("\n  - ")
            )
        }
        logger.lifecycle("Module boundaries OK: ${rootProject.subprojects.size} modules checked.")
    }
}
```

- [ ] **Step 2: Wire it from root `build.gradle.kts`**

Read the file first, then add the last line:

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

apply(from = "gradle/module-boundaries.gradle.kts")
```

- [ ] **Step 3: Run it and verify it passes against the real graph**

```bash
./gradlew checkModuleBoundaries
```

Expected: `Module boundaries OK: 9 modules checked.` (7 pre-existing + `device-sdk-mpos` + `device-sdk-factory`). If it reports a violation against a module this plan did **not** touch, that means `allowedProjectDeps` above is missing something real in that module's `build.gradle.kts` — re-read that file and add the missing entry; do not weaken the rule to make the failure go away.

- [ ] **Step 4: Prove it actually catches a violation, then revert the probe**

```bash
# Temporarily break a rule to prove the check works:
```
Edit `device-sdk-mpos/build.gradle.kts`, temporarily add `implementation(project(":device-sdk-edcsdk"))` to its `dependencies {}` block, then:
```bash
./gradlew checkModuleBoundaries
```
Expected: FAILS with `:device-sdk-mpos --> :device-sdk-edcsdk ... is not an allowed dependency`. Remove the temporary line, then re-run `./gradlew checkModuleBoundaries` and confirm it passes again.

- [ ] **Step 5: Commit**

```bash
git add gradle/module-boundaries.gradle.kts build.gradle.kts
git commit -m "build: add checkModuleBoundaries task enforcing the device-sdk module graph"
```

---

### Task 10: Wire `DeviceSdkFactory` into `AppContainer`

**Files:**
- Modify: `app/build.gradle.kts` — add `implementation(project(":device-sdk-factory"))`
- Modify: `app/src/main/kotlin/com/cashup/app/di/AppContainer.kt`

**Interfaces:**
- Consumes: `DeviceSdkFactory` (Task 8).
- Produces: `AppContainer.deviceSdkFactory: DeviceSdkFactory` — available for whichever future sale-flow code (out of this plan's scope, per spec §9/§8: `TerminalKeyInstaller`/`DukptKeyProvider`/`Scanner`/`SerialNumberProvider` stay wired directly to `device-sdk-edcsdk` as they are today) calls `.connect()` inside its own coroutine scope.

This is intentionally the smallest possible change to `AppContainer.kt`: `DeviceSdkFactory`'s constructor does no I/O (only `.connect()` does), so it fits the existing eager-`val` wiring style without needing to touch the class's synchronous `init` blocks or invent a suspend-wiring pattern that belongs to the UI sub-project, not this one.

- [ ] **Step 1: Add the dependency**

In `app/build.gradle.kts`, in the `dependencies {}` block, add alongside the existing three:
```kotlin
    implementation(project(":provisioning-core"))
    implementation(project(":cdcp-core"))
    implementation(project(":device-sdk-edcsdk"))
    implementation(project(":device-sdk-factory"))
```

- [ ] **Step 2: Add the import and the property to `AppContainer.kt`**

Add the import (alphabetically among the existing `com.cashup.devicesdk.edcsdk.*` imports):
```kotlin
import com.cashup.devicesdk.factory.DeviceSdkFactory
```

Add the property right after `val vendorScanner = EdcSdkScanner(appContext)` (line 91):
```kotlin
    val vendorScanner = EdcSdkScanner(appContext)

    /**
     * Pemilihan CardReader multi-vendor (EDC built-in atau mPOS Bluetooth).
     * `connect()`-nya suspend dan melakukan I/O nyata -- panggil dari
     * coroutine scope pemanggil (mis. viewModelScope alur sale), bukan di
     * sini. Scanner/SerialNumberProvider/TerminalKeyInstaller/DukptKeyProvider
     * TIDAK lewat sini (spec §5/§8) -- tetap wiring langsung ke
     * device-sdk-edcsdk seperti di atas.
     */
    val deviceSdkFactory = DeviceSdkFactory(appContext)
```

- [ ] **Step 3: Verify the whole app module still compiles**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full existing test suite to confirm nothing broke**

```bash
./gradlew test testDebugUnitTest
```
Expected: all pre-existing tests plus every test added in Tasks 2–9 pass.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin/com/cashup/app/di/AppContainer.kt
git commit -m "feat(app): wire DeviceSdkFactory into AppContainer"
```

---

## Self-Review

**Spec coverage:**
- §3 module layout (`device-sdk-mpos`, `device-sdk-factory`, boundary rules) → Tasks 3, 8, 9.
- §4 `PairableCardReader`/`PairedDeviceInfo`, `device-sdk-api` untouched otherwise → Task 2.
- §5 connector pattern, staged probing, mPOS "connect ≠ paired" semantics, `TerminalKeyInstaller` untouched → Tasks 4–8, explicit note in Task 10.
- §6 `Exception` vs `Error`, `NoDeviceSdkAvailableException`, boundary check → Tasks 8, 9.
- §7 testing (fakes/mocks, no real AAR needed for `device-sdk-factory` tests) → Task 8 uses plain lambdas, no mockk needed there.
- §8 out of scope (no further split of the 6 built-in vendors, UI, transaction-logic refactor, unsupported vendors) → not touched by any task, consistent.
- §9 file-level consequences table → covered by Tasks 1 (aarlib), 2 (device-sdk-api), new modules (3–8), 9 (root build.gradle.kts), 10 (app/AppContainer.kt).

**Placeholder scan:** none found — every step has real code, real commands, or a concrete verification command with an expected result.

**Type consistency:** `PairableCardReader.pairedDevices()`/`selectDevice()` (Task 2) are used identically in `MposCardReader` (Task 5) and its test. `MposEmvGateway`/`EmvCallback` (Task 4) signatures match their usage in `MposCardReader` (Task 5) exactly. `DeviceSdk`/`Capability`/`Printer`/`Scanner` usage in `MposDeviceSdk` (Task 6) and `EdcSdkDeviceSdk` (Task 7) matches the existing `device-sdk-api` contract read during planning. `DeviceSdkFactory`'s two constructors (Task 8) are both exercised: the internal one directly in tests, the public `Context` one in `AppContainer` (Task 10).

---

**Plan complete and saved to `docs/superpowers/plans/2026-09-22-device-sdk-vendor-abstraction.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**

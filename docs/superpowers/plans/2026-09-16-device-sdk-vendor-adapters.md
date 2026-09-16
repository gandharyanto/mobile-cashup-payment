# Device SDK Vendor Adapters Implementation Plan

> # ⛔ DIBATALKAN — JANGAN DIJALANKAN
>
> **Digantikan oleh `docs/superpowers/specs/2026-09-16-provisioning-design.md` §5.**
>
> Plan ini hendak membangun ulang enam adapter vendor dari AAR mentah, dan hanya
> sampai `connect()` + `serialNumber()`. Ternyata AAR yang mau disalin itu memang
> **output build `edc-sdk`** (`D:\gandha_cashup\projects\edc-sdk`) — SDK vendor
> internal yang sudah menyediakan deteksi device, serial number, injeksi key DUKPT,
> printer, card reader, dan EMV untuk tujuh vendor lewat `SDKManager`,
> `KeyManager`, dan `BaseSystemKey`. Sudah diverifikasi langsung dari isi
> `core-release_1.0.63.aar`.
>
> Penggantinya: satu module tipis `device-sdk-edcsdk` di atas AAR itu.
> `DeviceSdkRegistry` juga dihapus — `SDKManager.autoDetectDevice()` sudah
> melakukan deteksi lewat `Build.BRAND`.
>
> Nol dari 67 step pernah dijalankan. Disimpan sebagai catatan keputusan saja.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `device-sdk-api` a real, vendor-SDK-backed way to read a terminal's serial number, with working adapters for the 6 EDC vendors whose SDK binaries actually exist on this machine (PAX, Sunmi, Centerm, Nexgo, Topwise, Szanfu) — the prerequisite `provisioning-core` needs before it can send a real `serialNumber` in its QR-redeem request.

**Architecture:** `device-sdk-api` stays pure Kotlin/JVM (per the foundation plan) and gains two new `DeviceSdk` members (`connect`, `serialNumber`) plus a small `DeviceContext` marker interface so it never has to import `android.content.Context` itself. Six new `com.android.library` modules (`device-sdk-pax`, `-sunmi`, `-centerm`, `-nexgo`, `-topwise`, `-szanfu`) — the first Android modules in this repo — each wrap one vendor's real AAR SDK behind a small "binder" that silently fails on the wrong hardware (adapting the proven pattern from the sibling `tms-agent` repo's `tms-device-*` modules) and expose only `connect()`/`serialNumber()`; card/printer/scanner capabilities stay unimplemented (`null`/empty) because they are out of scope here. `DeviceSdkRegistry` gains a `resolveByProbing` path that tries every registered vendor's `connect()` in turn, because (per `tms-agent`'s own documented lesson) `Build.MODEL` string-prefix matching cannot reliably identify which of six vendors a terminal is. Each vendor module defines its own trivial `AndroidDeviceContext(context: Context) : DeviceContext` wrapper rather than sharing one from a new common Android module — six one-line duplicates is a deliberate, cheaper trade than standing up a shared `device-sdk-android` module for a single class; revisit if a second shared Android-only type shows up.

**Tech Stack:** Kotlin 2.0.21, AGP 8.4.0, Gradle 8.9, JDK 17 (toolchain for build tooling; Android modules compile Kotlin/Java 8 bytecode), compileSdk 33 / minSdk 23 / targetSdk 33, JUnit 5 (Jupiter) + MockK 1.13.11 + kotlinx-coroutines-test 1.9.0 for adapter unit tests.

**Spec:** `docs/EDC_PAYMENT_APP_DESIGN.md` §3 (module table lists the vendor adapters); reference implementation pattern: sibling repo `tms-agent` (`tms-device-api`, `tms-device-pax`, `tms-device-sunmi`, `tms-device-centerm`, `tms-device-nexgo`, `tms-device-topwise`, `tms-device-szanfu`).

## Global Constraints

- This is the **first** use of the Android Gradle Plugin in this repo. `common-core`, `device-sdk-api`, and `signing-core` stay plain `kotlin("jvm")` exactly as the foundation plan left them — nothing in this plan converts them to Android modules. Only the 6 new vendor-adapter modules use `com.android.library`.
- AGP version pinned to **8.4.0**, matching the exact version the sibling `tms-agent` repo already proved compatible with these same vendor AAR binaries and this JDK/Gradle combination.
- `compileSdk = 33`, `minSdk = 23`, `targetSdk = 33`. `minSdk` 23 specifically (not lower) because non-exportable Android Keystore key generation and `EncryptedSharedPreferences` — which `signing-core`'s future Keystore-backed `SigningKeyProvider` will need — require API 23; this mirrors `tms-agent`'s own documented rationale (its ADR 004).
- Vendor SDK classes are `implementation`-scoped only inside each adapter module, **never `api`** — a vendor type must never leak into `device-sdk-api` or any module that depends on the adapter. This is the opposite situation from the recent `common-core`/`signing-core` fix that promoted *this project's own* public API deps to `api(...)`; vendor SDK types are internal implementation detail, not part of this project's public surface, so `implementation(...)` is correct here.
- Vendor AAR binaries (`pax-release-core_1.0.63.aar`, `sunmi-release-core_1.0.63.aar`, `centerm-release-core_1.0.63.aar`, `nexgo-release-core_1.0.63.aar`, `topwize-release-core_1.0.63.aar`, `szanfu-release-core_1.0.63.aar`, plus their shared `core-release_1.0.63.aar` and `logger-release_1.0.2.aar` dependencies) are copied from `C:\Users\ACER\Documents\projects\tms-agent-bootstrap-2026-09-08\edc-tms-agent\aarlib\` into a new `aarlib/` folder at this repo's root. They are third-party binaries: git-ignored, resolved via a Gradle `flatDir` repository (not `files(...)` — AGP rejects a local `.aar` file dependency inside a project that itself builds an AAR), never rebuilt by this repo's own build.
- **Never** copy, reference, or commit `C:\Users\ACER\Documents\projects\tms-agent-bootstrap-2026-09-08\edc-tms-agent\key\busways.jks` or its `config.properties` — that bundle's own README marks them as a production signing keystore and its password, unrelated to this task, and explicitly "jangan pernah di-commit ke repo mana pun."
- Feitian, Urovo, Newland, and Tianyu adapters are **out of scope** for this plan — no vendor SDK binary is available for them on this machine. `docs/EDC_PAYMENT_APP_DESIGN.md` §3 names all 9 vendors; this plan closes the gap for the 6 that currently have a real AAR, and leaves the other 3 as a recorded open item (see "Out of Scope" below).
- Each vendor adapter's production "binder" (the class that actually calls `bindService`/`NeptuneLiteUser.getInstance()`/etc.) touches real Android framework/binder machinery and is not unit-testable without either physical hardware or a much heavier Robolectric setup than this plan introduces. Every adapter's unit tests instead inject a **fake** binder (a test-only lambda returning a pre-built services holder with a MockK-mocked vendor service interface), so what gets verified is the serial-number-reading/cleanup logic, not the real bind sequence. This is a deliberate, recorded gap — production binders are validated manually against physical terminals, not by this plan's test suite.
- Card reader, printer, and QR scanner capabilities are **not** implemented on any of the 6 new adapters (`capabilities = emptySet()`, `cardReader`/`printer`/`scanner` = `null`) — only `connect()`/`serialNumber()` are real. Wiring the rest of `DeviceSdk` per vendor is future work, not this plan.

---

## Task 1: Root Gradle setup for Android library modules

**Files:**
- Modify: `build.gradle.kts` — add the AGP + Kotlin-Android plugins (`apply false`)
- Modify: `settings.gradle.kts` — add the `flatDir` repository and `include(...)` for the 6 new modules
- Modify: `gradle.properties` — add `android.useAndroidX=true` and `android.nonTransitiveRClass=true`
- Modify: `.gitignore` — ignore the AAR binaries, keep the README tracked
- Create: `aarlib/README.md`
- Create: `aarlib/pax-release-core_1.0.63.aar`, `aarlib/sunmi-release-core_1.0.63.aar`, `aarlib/centerm-release-core_1.0.63.aar`, `aarlib/nexgo-release-core_1.0.63.aar`, `aarlib/topwize-release-core_1.0.63.aar`, `aarlib/szanfu-release-core_1.0.63.aar`, `aarlib/core-release_1.0.63.aar`, `aarlib/logger-release_1.0.2.aar` (binary copies)
- Create: `gradle/android-vendor-adapter.gradle.kts` — shared Android-library convention script applied by each of the 6 vendor modules, so `compileSdk`/`minSdk`/`compileOptions`/test dependencies aren't repeated 6 times

**Interfaces:**
- Consumes: nothing.
- Produces: a root build where `com.android.library` + `kotlin("android")` are available (`apply false`) for module `build.gradle.kts` files to apply; a `flatDir` repo pointing at `aarlib/`; a reusable `gradle/android-vendor-adapter.gradle.kts` that configures `compileSdk = 33`, `minSdk = 23`, Java 8 source/target compatibility, and the JUnit 5 + MockK + coroutines-test dependencies every vendor module's tests need. Tasks 3–8 each apply this script and add only their own vendor-specific dependencies.

- [ ] **Step 1: Copy the vendor AARs into `aarlib/`**

Run (PowerShell):
```powershell
New-Item -ItemType Directory -Force -Path "aarlib" | Out-Null
$src = "C:\Users\ACER\Documents\projects\tms-agent-bootstrap-2026-09-08\edc-tms-agent\aarlib"
Copy-Item "$src\pax-release-core_1.0.63.aar" "aarlib\"
Copy-Item "$src\sunmi-release-core_1.0.63.aar" "aarlib\"
Copy-Item "$src\centerm-release-core_1.0.63.aar" "aarlib\"
Copy-Item "$src\nexgo-release-core_1.0.63.aar" "aarlib\"
Copy-Item "$src\topwize-release-core_1.0.63.aar" "aarlib\"
Copy-Item "$src\szanfu-release-core_1.0.63.aar" "aarlib\"
Copy-Item "$src\core-release_1.0.63.aar" "aarlib\"
Copy-Item "$src\logger-release_1.0.2.aar" "aarlib\"
```

Expected: `aarlib/` contains exactly those 8 `.aar` files. **Do not** copy `key\busways.jks` or `config.properties` from that bundle — they are unrelated production signing secrets.

- [ ] **Step 2: Create `aarlib/README.md`**

`aarlib/README.md`:

```markdown
# `aarlib/` — vendor SDK artifacts

These `.aar` files are third-party EDC vendor SDK binaries (PAX, Sunmi,
Centerm, Nexgo, Topwise, Szanfu), plus the shared `core`/`logger` AARs they
depend on. They are **not built by this repo** and are git-ignored.

| File | Used by |
|---|---|
| `core-release_1.0.63.aar` | all 6 vendor adapter modules |
| `logger-release_1.0.2.aar` | all 6 vendor adapter modules |
| `pax-release-core_1.0.63.aar` | `:device-sdk-pax` |
| `sunmi-release-core_1.0.63.aar` | `:device-sdk-sunmi` |
| `centerm-release-core_1.0.63.aar` | `:device-sdk-centerm` |
| `nexgo-release-core_1.0.63.aar` | `:device-sdk-nexgo` |
| `topwize-release-core_1.0.63.aar` | `:device-sdk-topwise` |
| `szanfu-release-core_1.0.63.aar` | `:device-sdk-szanfu` |

Sourced from the sibling `tms-agent` repo's own `aarlib/` (built from a
separate `edc-sdk` repository). If these go missing on a fresh clone, copy
them again from wherever the team keeps the current `edc-sdk` build output —
see `tms-agent/aarlib/README.md` for the original build/regenerate steps.

Only 6 of the 9 vendors named in `docs/EDC_PAYMENT_APP_DESIGN.md` §3 have an
AAR here — Feitian, Urovo, Newland, and Tianyu are not yet available.
```

- [ ] **Step 3: Add `aarlib/*.aar` to `.gitignore`**

Modify `.gitignore`, appending:

```
aarlib/*.aar
```

- [ ] **Step 4: Add the AGP + Kotlin-Android plugins to the root `build.gradle.kts`**

Modify `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.0.21" apply false
    id("com.android.library") version "8.4.0" apply false
    kotlin("android") version "2.0.21" apply false
}

allprojects {
    group = "com.cashup"
    version = "0.1.0"
}
```

- [ ] **Step 5: Add the `flatDir` repository and module includes to `settings.gradle.kts`**

Modify `settings.gradle.kts`:

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
        // Vendor SDK AARs — see aarlib/README.md. flatDir, not files(), because
        // AGP rejects a local .aar file dependency inside a project that itself
        // builds an AAR.
        flatDir { dirs("$rootDir/aarlib") }
    }
}
rootProject.name = "mobile-cashup-payment"

include(":common-core")
include(":device-sdk-api")
include(":signing-core")
include(":device-sdk-pax")
include(":device-sdk-sunmi")
include(":device-sdk-centerm")
include(":device-sdk-nexgo")
include(":device-sdk-topwise")
include(":device-sdk-szanfu")
```

- [ ] **Step 6: Add Android flags to `gradle.properties`**

Modify `gradle.properties`, appending:

```properties
android.useAndroidX=true
android.nonTransitiveRClass=true
```

- [ ] **Step 7: Create the shared Android-library convention script**

`gradle/android-vendor-adapter.gradle.kts`:

```kotlin
import com.android.build.gradle.LibraryExtension

configure<LibraryExtension> {
    compileSdk = 33

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
    "implementation"(project(":device-sdk-api"))

    "testImplementation"(platform("org.junit:junit-bom:5.11.0"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testImplementation"("io.mockk:mockk:1.13.11")
    "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

- [ ] **Step 8: Verify the root build still resolves**

Run: `./gradlew help`
Expected: `BUILD SUCCESSFUL`. The 6 new module names appear if you run `./gradlew projects`, but each fails to build until its own `build.gradle.kts` exists (Tasks 3–8) — that's expected at this point.

- [ ] **Step 9: Commit**

```bash
git add build.gradle.kts settings.gradle.kts gradle.properties .gitignore \
  gradle/android-vendor-adapter.gradle.kts aarlib/README.md
git commit -m "chore: add Android Gradle Plugin and vendor AAR infrastructure"
```

Note: `aarlib/*.aar` binaries are intentionally not committed (git-ignored per Step 3).

---

## Task 2: `device-sdk-api` — `DeviceContext`, `DeviceSdk.connect`/`serialNumber`, `DeviceSdkRegistry.resolveByProbing`

**Files:**
- Create: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceContext.kt`
- Modify: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdk.kt`
- Modify: `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdkRegistry.kt`
- Modify: `device-sdk-api/src/test/kotlin/com/cashup/devicesdk/DeviceSdkRegistryTest.kt`
- Modify: `device-sdk-api/src/testFixtures/kotlin/com/cashup/devicesdk/fake/FakeDeviceSdk.kt`
- Modify: `device-sdk-api/build.gradle.kts` — add `kotlinx-coroutines-test` for the new suspend-function test
- Test: `device-sdk-api/src/test/kotlin/com/cashup/devicesdk/DeviceSdkRegistryProbingTest.kt`

**Interfaces:**
- Consumes: nothing new from other modules.
- Produces: `interface DeviceContext` (empty marker — device-sdk-api stays Android-agnostic; each vendor adapter module defines its own concrete `DeviceContext` wrapping a real `android.content.Context` and unwraps it internally); `DeviceSdk` gains `suspend fun connect(context: DeviceContext): Boolean` and `suspend fun serialNumber(): String?`; `DeviceSdkRegistry.resolveByProbing(context: DeviceContext): DeviceSdk?` tries every registered factory's `connect()` in registration order and returns the first one that binds. Tasks 3–8's adapters implement `connect`/`serialNumber` against real vendor SDKs; a future `provisioning-core` plan calls `DeviceSdkRegistry.resolveByProbing(...)`.

- [ ] **Step 1: Create `DeviceContext`**

`device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceContext.kt`:

```kotlin
package com.cashup.devicesdk

/**
 * Opaque platform handle passed to [DeviceSdk.connect]. device-sdk-api stays
 * pure Kotlin/JVM with no Android dependency — each vendor adapter module
 * defines its own concrete [DeviceContext] wrapping a real
 * `android.content.Context` and unwraps it internally.
 */
interface DeviceContext
```

- [ ] **Step 2: Add `connect`/`serialNumber` to `DeviceSdk`**

Modify `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdk.kt`:

```kotlin
package com.cashup.devicesdk

interface DeviceSdk {
    val vendorId: String
    val capabilities: Set<Capability>
    val cardReader: CardReader?
    val printer: Printer?
    val scanner: Scanner?

    fun supports(capability: Capability): Boolean = capability in capabilities

    /**
     * Binds to this vendor's SDK. Returns false if this adapter is not the
     * right one for the terminal it's running on — the expected case on five
     * terminals out of six — never throws on wrong hardware.
     */
    suspend fun connect(context: DeviceContext): Boolean

    /** Null before [connect] has bound successfully, or if the vendor SDK cannot answer. */
    suspend fun serialNumber(): String?
}
```

- [ ] **Step 3: Fix the two existing `DeviceSdk` fakes so the module still compiles**

Modify `device-sdk-api/src/testFixtures/kotlin/com/cashup/devicesdk/fake/FakeDeviceSdk.kt`:

```kotlin
package com.cashup.devicesdk.fake

import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.DeviceContext
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner

class FakeDeviceSdk(
    override val vendorId: String = "fake",
    override val capabilities: Set<Capability> = setOf(Capability.CARD_READ, Capability.PRINT, Capability.SCAN_QR),
    override val cardReader: CardReader? = FakeCardReader(),
    override val printer: Printer? = FakePrinter(),
    override val scanner: Scanner? = FakeScanner(),
    private val connectResult: Boolean = true,
    private val serialNumberValue: String? = "FAKE-SN-0001",
) : DeviceSdk {
    override suspend fun connect(context: DeviceContext): Boolean = connectResult
    override suspend fun serialNumber(): String? = serialNumberValue
}
```

Modify `device-sdk-api/src/test/kotlin/com/cashup/devicesdk/DeviceSdkRegistryTest.kt` — replace the private `FakeDeviceSdk` class with:

```kotlin
private class FakeDeviceSdk(override val vendorId: String) : DeviceSdk {
    override val capabilities: Set<Capability> = setOf(Capability.CARD_READ)
    override val cardReader: CardReader? = null
    override val printer: Printer? = null
    override val scanner: Scanner? = null
    override suspend fun connect(context: DeviceContext): Boolean = true
    override suspend fun serialNumber(): String? = null
}
```

(The rest of `DeviceSdkRegistryTest.kt` — imports, the two `@Test` methods, `clearForTest()` — is unchanged.)

- [ ] **Step 4: Run the existing device-sdk-api tests to confirm the fixes compile and pass**

Run: `./gradlew :device-sdk-api:test`
Expected: `BUILD SUCCESSFUL` — the pre-existing `DeviceSdkRegistryTest` and `FakeDeviceSdkTest` still pass with the two new interface members implemented.

- [ ] **Step 5: Commit the interface changes**

```bash
git add device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceContext.kt \
  device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdk.kt \
  device-sdk-api/src/test/kotlin/com/cashup/devicesdk/DeviceSdkRegistryTest.kt \
  device-sdk-api/src/testFixtures/kotlin/com/cashup/devicesdk/fake/FakeDeviceSdk.kt
git commit -m "feat(device-sdk-api): add DeviceContext and DeviceSdk.connect/serialNumber"
```

- [ ] **Step 6: Add `kotlinx-coroutines-test` to `device-sdk-api/build.gradle.kts`**

Modify `device-sdk-api/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(testFixtures(project(":device-sdk-api")))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
```

- [ ] **Step 7: Write the failing test for `resolveByProbing`**

`device-sdk-api/src/test/kotlin/com/cashup/devicesdk/DeviceSdkRegistryProbingTest.kt`:

```kotlin
package com.cashup.devicesdk

import com.cashup.devicesdk.fake.FakeDeviceSdk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private object NoopContext : DeviceContext

class DeviceSdkRegistryProbingTest {

    @AfterEach
    fun tearDown() {
        DeviceSdkRegistry.clearForTest()
    }

    @Test
    fun `returns the first registered sdk whose connect succeeds`() = runTest {
        DeviceSdkRegistry.register("VENDOR_A") { FakeDeviceSdk(vendorId = "a", connectResult = false) }
        DeviceSdkRegistry.register("VENDOR_B") { FakeDeviceSdk(vendorId = "b", connectResult = true) }

        val resolved = DeviceSdkRegistry.resolveByProbing(NoopContext)

        assertEquals("b", resolved?.vendorId)
    }

    @Test
    fun `returns null when no registered sdk connects`() = runTest {
        DeviceSdkRegistry.register("VENDOR_A") { FakeDeviceSdk(vendorId = "a", connectResult = false) }

        val resolved = DeviceSdkRegistry.resolveByProbing(NoopContext)

        assertNull(resolved)
    }
}
```

- [ ] **Step 8: Run the test to verify it fails**

Run: `./gradlew :device-sdk-api:test --tests "com.cashup.devicesdk.DeviceSdkRegistryProbingTest"`
Expected: FAIL — `resolveByProbing` unresolved reference.

- [ ] **Step 9: Implement `resolveByProbing`**

Modify `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdkRegistry.kt`:

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

    /**
     * Tries every registered vendor's [DeviceSdk.connect] in registration
     * order and returns the first one that binds. `Build.MODEL` string
     * prefixes ([resolve]) can't reliably identify which of several vendors a
     * terminal is — a real bind attempt can. Registration order therefore
     * matters when more than one adapter could plausibly bind on the same
     * terminal; callers registering adapters should list the most specific
     * vendor first.
     */
    suspend fun resolveByProbing(context: DeviceContext): DeviceSdk? {
        for (factory in factories.values) {
            val candidate = factory()
            if (candidate.connect(context)) return candidate
        }
        return null
    }

    /** Test-only: clears registrations so tests don't leak state into each other. */
    fun clearForTest() {
        factories.clear()
    }
}
```

- [ ] **Step 10: Run the test to verify it passes**

Run: `./gradlew :device-sdk-api:test --tests "com.cashup.devicesdk.DeviceSdkRegistryProbingTest"`
Expected: PASS, 2 tests green.

- [ ] **Step 11: Run the full device-sdk-api suite**

Run: `./gradlew :device-sdk-api:test`
Expected: `BUILD SUCCESSFUL`, all tests green (existing `DeviceSdkRegistryTest`, `FakeDeviceSdkTest`, plus the new `DeviceSdkRegistryProbingTest`).

- [ ] **Step 12: Commit**

```bash
git add device-sdk-api/build.gradle.kts \
  device-sdk-api/src/main/kotlin/com/cashup/devicesdk/DeviceSdkRegistry.kt \
  device-sdk-api/src/test/kotlin/com/cashup/devicesdk/DeviceSdkRegistryProbingTest.kt
git commit -m "feat(device-sdk-api): add DeviceSdkRegistry.resolveByProbing"
```

---

## Task 3: `device-sdk-pax`

**Files:**
- Create: `device-sdk-pax/build.gradle.kts`
- Create: `device-sdk-pax/src/main/kotlin/com/cashup/devicesdk/pax/PaxBinding.kt`
- Create: `device-sdk-pax/src/main/kotlin/com/cashup/devicesdk/pax/PaxDeviceSdk.kt`
- Test: `device-sdk-pax/src/test/kotlin/com/cashup/devicesdk/pax/PaxDeviceSdkTest.kt`

**Interfaces:**
- Consumes: `com.cashup.devicesdk.{DeviceSdk, DeviceContext, Capability, CardReader, Printer, Scanner}` from `device-sdk-api` (Task 2).
- Produces: `PaxDeviceSdk : DeviceSdk` (`vendorId = "pax"`), registered in `DeviceSdkRegistry` under model prefix `"PAX"`. Nothing else in this plan consumes it directly — `provisioning-core` (a separate, future plan) will call `DeviceSdkRegistry.resolveByProbing(...)` and get whichever adapter's `connect()` succeeds, `PaxDeviceSdk` included.

- [ ] **Step 1: Create `device-sdk-pax/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.devicesdk.pax"
}

apply(from = "$rootDir/gradle/android-vendor-adapter.gradle.kts")

dependencies {
    implementation(group = "", name = "pax-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "core-release_1.0.63", ext = "aar")
    implementation(group = "", name = "logger-release_1.0.2", ext = "aar")
}
```

- [ ] **Step 2: Create the PAX binder and Android context wrapper**

`device-sdk-pax/src/main/kotlin/com/cashup/devicesdk/pax/PaxBinding.kt`:

```kotlin
package com.cashup.devicesdk.pax

import android.content.Context
import com.cashup.devicesdk.DeviceContext
import com.pax.dal.IDAL
import com.pax.dal.ISys
import com.pax.neptunelite.api.NeptuneLiteUser

/** Wraps the real Android [Context] so device-sdk-api never has to import it. */
class AndroidDeviceContext(val context: Context) : DeviceContext

/** What bound on this terminal. Without `ISys` this is not a PAX. */
data class PaxServices(val sys: ISys? = null) {
    val bound: Boolean get() = sys != null
}

fun interface PaxBinder {
    suspend fun bind(context: Context): PaxServices
}

/**
 * The production binder. `getDal(context)` throws on non-PAX hardware — five
 * terminals out of six — so that failure is the expected path, not an error.
 */
class NeptuneLitePaxBinder : PaxBinder {

    override suspend fun bind(context: Context): PaxServices {
        val dal: IDAL = try {
            NeptuneLiteUser.getInstance().getDal(context.applicationContext)
        } catch (unavailable: Exception) {
            return PaxServices()
        }

        val sys = try {
            dal.sys
        } catch (unavailable: Exception) {
            null
        }

        return PaxServices(sys = sys)
    }
}
```

- [ ] **Step 3: Write the failing test for `PaxDeviceSdk`**

`device-sdk-pax/src/test/kotlin/com/cashup/devicesdk/pax/PaxDeviceSdkTest.kt`:

```kotlin
package com.cashup.devicesdk.pax

import android.content.Context
import com.pax.dal.ISys
import com.pax.dal.entity.ETermInfoKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaxDeviceSdkTest {

    @Test
    fun `connect returns true and serialNumber reads SN when the pax sdk binds`() = runTest {
        val sys = mockk<ISys> {
            every { getTermInfo() } returns mapOf(ETermInfoKey.SN to "1234567890")
        }
        val sdk = PaxDeviceSdk(binder = PaxBinder { PaxServices(sys = sys) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(connected)
        assertEquals("1234567890", sdk.serialNumber())
    }

    @Test
    fun `connect returns false and serialNumber is null when the pax sdk does not bind`() = runTest {
        val sdk = PaxDeviceSdk(binder = PaxBinder { PaxServices(sys = null) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(!connected)
        assertNull(sdk.serialNumber())
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :device-sdk-pax:test`
Expected: FAIL — `PaxDeviceSdk` unresolved reference.

- [ ] **Step 5: Implement `PaxDeviceSdk`**

`device-sdk-pax/src/main/kotlin/com/cashup/devicesdk/pax/PaxDeviceSdk.kt`:

```kotlin
package com.cashup.devicesdk.pax

import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.DeviceContext
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner
import com.pax.dal.entity.ETermInfoKey

class PaxDeviceSdk(
    private val binder: PaxBinder = NeptuneLitePaxBinder(),
) : DeviceSdk {

    override val vendorId: String = VENDOR
    override val capabilities: Set<Capability> = emptySet()
    override val cardReader: CardReader? = null
    override val printer: Printer? = null
    override val scanner: Scanner? = null

    private var services: PaxServices = PaxServices()

    override suspend fun connect(context: DeviceContext): Boolean {
        val androidContext = (context as? AndroidDeviceContext)?.context
            ?: return false
        if (services.bound) return true
        services = binder.bind(androidContext)
        return services.bound
    }

    override suspend fun serialNumber(): String? {
        val sys = services.sys ?: return null
        return try {
            sys.getTermInfo()[ETermInfoKey.SN]?.trim()?.takeUnless { it.isEmpty() }
        } catch (unavailable: Exception) {
            null
        }
    }

    companion object {
        const val VENDOR = "pax"
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :device-sdk-pax:test`
Expected: PASS, 2 tests green.

- [ ] **Step 7: Register the adapter with `DeviceSdkRegistry`**

Add to `device-sdk-pax/src/main/kotlin/com/cashup/devicesdk/pax/PaxDeviceSdk.kt`, appended below the class:

```kotlin
/**
 * Call once at app startup (before any `DeviceSdkRegistry.resolve*` call).
 * Kept as a free function rather than an `init {}` block so registration
 * order across vendors — relevant to `resolveByProbing` — is the caller's
 * explicit choice, not import-order accident.
 */
fun registerPaxDeviceSdk() {
    com.cashup.devicesdk.DeviceSdkRegistry.register("PAX") { PaxDeviceSdk() }
}
```

- [ ] **Step 8: Commit**

```bash
git add device-sdk-pax/build.gradle.kts \
  device-sdk-pax/src/main/kotlin/com/cashup/devicesdk/pax/PaxBinding.kt \
  device-sdk-pax/src/main/kotlin/com/cashup/devicesdk/pax/PaxDeviceSdk.kt \
  device-sdk-pax/src/test/kotlin/com/cashup/devicesdk/pax/PaxDeviceSdkTest.kt
git commit -m "feat(device-sdk-pax): add PAX serial-number adapter"
```

---

## Task 4: `device-sdk-sunmi`

**Files:**
- Create: `device-sdk-sunmi/build.gradle.kts`
- Create: `device-sdk-sunmi/src/main/kotlin/com/cashup/devicesdk/sunmi/SunmiBinding.kt`
- Create: `device-sdk-sunmi/src/main/kotlin/com/cashup/devicesdk/sunmi/SunmiDeviceSdk.kt`
- Test: `device-sdk-sunmi/src/test/kotlin/com/cashup/devicesdk/sunmi/SunmiDeviceSdkTest.kt`

**Interfaces:**
- Consumes: `com.cashup.devicesdk.{DeviceSdk, DeviceContext, Capability, CardReader, Printer, Scanner}` from `device-sdk-api` (Task 2).
- Produces: `SunmiDeviceSdk : DeviceSdk` (`vendorId = "sunmi"`), `registerSunmiDeviceSdk()` registering it under model prefix `"SUNMI"`.

- [ ] **Step 1: Create `device-sdk-sunmi/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.devicesdk.sunmi"
}

apply(from = "$rootDir/gradle/android-vendor-adapter.gradle.kts")

dependencies {
    implementation(group = "", name = "sunmi-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "core-release_1.0.63", ext = "aar")
    implementation(group = "", name = "logger-release_1.0.2", ext = "aar")
}
```

- [ ] **Step 2: Create the Sunmi binder**

`device-sdk-sunmi/src/main/kotlin/com/cashup/devicesdk/sunmi/SunmiBinding.kt`:

```kotlin
package com.cashup.devicesdk.sunmi

import android.content.Context
import com.cashup.devicesdk.DeviceContext
import com.sunmi.pay.hardware.aidlv2.system.BasicOptV2
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import sunmi.paylib.SunmiPayKernel

class AndroidDeviceContext(val context: Context) : DeviceContext

/** What bound. Sunmi is one asynchronous service connection, one interface behind it. */
data class SunmiServices(val basic: BasicOptV2? = null) {
    val bound: Boolean get() = basic != null
}

fun interface SunmiBinder {
    suspend fun bind(context: Context): SunmiServices
}

/**
 * The production binder. `initPaySDK` starts an asynchronous bind and reports
 * through a callback, so this waits with a timeout — on a non-Sunmi terminal
 * the callback never comes and the caller needs a "no" rather than a hang.
 */
class PayKernelSunmiBinder(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : SunmiBinder {

    override suspend fun bind(context: Context): SunmiServices {
        val kernel = try {
            SunmiPayKernel.getInstance()
        } catch (unavailable: Exception) {
            return SunmiServices()
        }

        val connected = try {
            withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val started = kernel.initPaySDK(
                        context.applicationContext,
                        object : SunmiPayKernel.ConnectCallback {
                            override fun onConnectPaySDK() {
                                if (continuation.isActive) continuation.resume(true)
                            }

                            override fun onDisconnectPaySDK() {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        },
                    )
                    if (!started && continuation.isActive) continuation.resume(false)
                }
            } ?: false
        } catch (unavailable: Exception) {
            false
        }

        if (!connected) return SunmiServices()

        return SunmiServices(
            basic = try {
                kernel.mBasicOptV2
            } catch (unavailable: Exception) {
                null
            },
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
```

- [ ] **Step 3: Write the failing test for `SunmiDeviceSdk`**

`device-sdk-sunmi/src/test/kotlin/com/cashup/devicesdk/sunmi/SunmiDeviceSdkTest.kt`:

```kotlin
package com.cashup.devicesdk.sunmi

import android.content.Context
import com.sunmi.pay.hardware.aidl.AidlConstants
import com.sunmi.pay.hardware.aidlv2.system.BasicOptV2
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SunmiDeviceSdkTest {

    @Test
    fun `connect returns true and serialNumber reads SN when the sunmi sdk binds`() = runTest {
        val basic = mockk<BasicOptV2> {
            every { getSysParam(AidlConstants.SysParam.SN) } returns "9876543210"
        }
        val sdk = SunmiDeviceSdk(binder = SunmiBinder { SunmiServices(basic = basic) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(connected)
        assertEquals("9876543210", sdk.serialNumber())
    }

    @Test
    fun `connect returns false and serialNumber is null when the sunmi sdk does not bind`() = runTest {
        val sdk = SunmiDeviceSdk(binder = SunmiBinder { SunmiServices(basic = null) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(!connected)
        assertNull(sdk.serialNumber())
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :device-sdk-sunmi:test`
Expected: FAIL — `SunmiDeviceSdk` unresolved reference.

- [ ] **Step 5: Implement `SunmiDeviceSdk`**

`device-sdk-sunmi/src/main/kotlin/com/cashup/devicesdk/sunmi/SunmiDeviceSdk.kt`:

```kotlin
package com.cashup.devicesdk.sunmi

import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.DeviceContext
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner
import com.sunmi.pay.hardware.aidl.AidlConstants

class SunmiDeviceSdk(
    private val binder: SunmiBinder = PayKernelSunmiBinder(),
) : DeviceSdk {

    override val vendorId: String = VENDOR
    override val capabilities: Set<Capability> = emptySet()
    override val cardReader: CardReader? = null
    override val printer: Printer? = null
    override val scanner: Scanner? = null

    private var services: SunmiServices = SunmiServices()

    override suspend fun connect(context: DeviceContext): Boolean {
        val androidContext = (context as? AndroidDeviceContext)?.context
            ?: return false
        if (services.bound) return true
        services = binder.bind(androidContext)
        return services.bound
    }

    override suspend fun serialNumber(): String? {
        val basic = services.basic ?: return null
        return try {
            basic.getSysParam(AidlConstants.SysParam.SN)?.trim()?.takeUnless { it.isEmpty() }
        } catch (unavailable: Exception) {
            null
        }
    }

    companion object {
        const val VENDOR = "sunmi"
    }
}

fun registerSunmiDeviceSdk() {
    com.cashup.devicesdk.DeviceSdkRegistry.register("SUNMI") { SunmiDeviceSdk() }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :device-sdk-sunmi:test`
Expected: PASS, 2 tests green.

- [ ] **Step 7: Commit**

```bash
git add device-sdk-sunmi/build.gradle.kts \
  device-sdk-sunmi/src/main/kotlin/com/cashup/devicesdk/sunmi/SunmiBinding.kt \
  device-sdk-sunmi/src/main/kotlin/com/cashup/devicesdk/sunmi/SunmiDeviceSdk.kt \
  device-sdk-sunmi/src/test/kotlin/com/cashup/devicesdk/sunmi/SunmiDeviceSdkTest.kt
git commit -m "feat(device-sdk-sunmi): add Sunmi serial-number adapter"
```

---

## Task 5: `device-sdk-centerm`

**Files:**
- Create: `device-sdk-centerm/build.gradle.kts`
- Create: `device-sdk-centerm/src/main/kotlin/com/cashup/devicesdk/centerm/CentermBinding.kt`
- Create: `device-sdk-centerm/src/main/kotlin/com/cashup/devicesdk/centerm/CentermDeviceSdk.kt`
- Test: `device-sdk-centerm/src/test/kotlin/com/cashup/devicesdk/centerm/CentermDeviceSdkTest.kt`

**Interfaces:**
- Consumes: `com.cashup.devicesdk.{DeviceSdk, DeviceContext, Capability, CardReader, Printer, Scanner}` from `device-sdk-api` (Task 2).
- Produces: `CentermDeviceSdk : DeviceSdk` (`vendorId = "centerm"`), `registerCentermDeviceSdk()` registering it under model prefix `"CENTERM"`.

- [ ] **Step 1: Create `device-sdk-centerm/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.devicesdk.centerm"
}

apply(from = "$rootDir/gradle/android-vendor-adapter.gradle.kts")

dependencies {
    implementation(group = "", name = "centerm-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "core-release_1.0.63", ext = "aar")
    implementation(group = "", name = "logger-release_1.0.2", ext = "aar")
}
```

- [ ] **Step 2: Create the Centerm binder**

`device-sdk-centerm/src/main/kotlin/com/cashup/devicesdk/centerm/CentermBinding.kt`:

```kotlin
package com.cashup.devicesdk.centerm

import android.content.Context
import com.cashup.devicesdk.DeviceContext
import com.pos.sdk.DeviceManager
import com.pos.sdk.DevicesFactory
import com.pos.sdk.callback.ResultCallback
import com.pos.sdk.sys.SystemDevice
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AndroidDeviceContext(val context: Context) : DeviceContext

/** What bound. Centerm has one root object and one interface off it that this adapter needs. */
data class CentermServices(val system: SystemDevice? = null) {
    val bound: Boolean get() = system != null
}

fun interface CentermBinder {
    suspend fun bind(context: Context): CentermServices
}

/**
 * The production binder. `DevicesFactory.create` is asynchronous and reports
 * through a [ResultCallback], so this waits with a timeout — on a non-Centerm
 * terminal neither callback ever fires.
 */
class DevicesFactoryCentermBinder(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : CentermBinder {

    override suspend fun bind(context: Context): CentermServices {
        val manager = try {
            withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine<DeviceManager?> { continuation ->
                    DevicesFactory.create(
                        context.applicationContext,
                        object : ResultCallback<DeviceManager> {
                            override fun onFinish(result: DeviceManager?) {
                                if (continuation.isActive) continuation.resume(result)
                            }

                            override fun onError(code: Int, message: String?) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        },
                    )
                }
            }
        } catch (unavailable: Exception) {
            null
        } ?: return CentermServices()

        return CentermServices(
            system = try {
                manager.getSystemDevice()
            } catch (unavailable: Exception) {
                null
            },
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
```

- [ ] **Step 3: Write the failing test for `CentermDeviceSdk`**

`device-sdk-centerm/src/test/kotlin/com/cashup/devicesdk/centerm/CentermDeviceSdkTest.kt`:

```kotlin
package com.cashup.devicesdk.centerm

import android.content.Context
import com.pos.sdk.sys.SystemDevice
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CentermDeviceSdkTest {

    @Test
    fun `connect returns true and serialNumber reads SN when the centerm sdk binds`() = runTest {
        val system = mockk<SystemDevice> {
            every { getSystemInfo(SystemDevice.SystemInfoType.SN) } returns "CT-0001"
        }
        val sdk = CentermDeviceSdk(binder = CentermBinder { CentermServices(system = system) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(connected)
        assertEquals("CT-0001", sdk.serialNumber())
    }

    @Test
    fun `connect returns false and serialNumber is null when the centerm sdk does not bind`() = runTest {
        val sdk = CentermDeviceSdk(binder = CentermBinder { CentermServices(system = null) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(!connected)
        assertNull(sdk.serialNumber())
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :device-sdk-centerm:test`
Expected: FAIL — `CentermDeviceSdk` unresolved reference.

- [ ] **Step 5: Implement `CentermDeviceSdk`**

`device-sdk-centerm/src/main/kotlin/com/cashup/devicesdk/centerm/CentermDeviceSdk.kt`:

```kotlin
package com.cashup.devicesdk.centerm

import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.DeviceContext
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner
import com.pos.sdk.sys.SystemDevice

class CentermDeviceSdk(
    private val binder: CentermBinder = DevicesFactoryCentermBinder(),
) : DeviceSdk {

    override val vendorId: String = VENDOR
    override val capabilities: Set<Capability> = emptySet()
    override val cardReader: CardReader? = null
    override val printer: Printer? = null
    override val scanner: Scanner? = null

    private var services: CentermServices = CentermServices()

    override suspend fun connect(context: DeviceContext): Boolean {
        val androidContext = (context as? AndroidDeviceContext)?.context
            ?: return false
        if (services.bound) return true
        services = binder.bind(androidContext)
        return services.bound
    }

    override suspend fun serialNumber(): String? {
        val system = services.system ?: return null
        return try {
            system.getSystemInfo(SystemDevice.SystemInfoType.SN)?.trim()?.takeUnless { it.isEmpty() }
        } catch (unavailable: Exception) {
            null
        }
    }

    companion object {
        const val VENDOR = "centerm"
    }
}

fun registerCentermDeviceSdk() {
    com.cashup.devicesdk.DeviceSdkRegistry.register("CENTERM") { CentermDeviceSdk() }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :device-sdk-centerm:test`
Expected: PASS, 2 tests green.

- [ ] **Step 7: Commit**

```bash
git add device-sdk-centerm/build.gradle.kts \
  device-sdk-centerm/src/main/kotlin/com/cashup/devicesdk/centerm/CentermBinding.kt \
  device-sdk-centerm/src/main/kotlin/com/cashup/devicesdk/centerm/CentermDeviceSdk.kt \
  device-sdk-centerm/src/test/kotlin/com/cashup/devicesdk/centerm/CentermDeviceSdkTest.kt
git commit -m "feat(device-sdk-centerm): add Centerm serial-number adapter"
```

---

## Task 6: `device-sdk-nexgo`

**Files:**
- Create: `device-sdk-nexgo/build.gradle.kts`
- Create: `device-sdk-nexgo/src/main/kotlin/com/cashup/devicesdk/nexgo/NexgoBinding.kt`
- Create: `device-sdk-nexgo/src/main/kotlin/com/cashup/devicesdk/nexgo/NexgoDeviceSdk.kt`
- Test: `device-sdk-nexgo/src/test/kotlin/com/cashup/devicesdk/nexgo/NexgoDeviceSdkTest.kt`

**Interfaces:**
- Consumes: `com.cashup.devicesdk.{DeviceSdk, DeviceContext, Capability, CardReader, Printer, Scanner}` from `device-sdk-api` (Task 2).
- Produces: `NexgoDeviceSdk : DeviceSdk` (`vendorId = "nexgo"`), `registerNexgoDeviceSdk()` registering it under model prefix `"NEXGO"`.

Nexgo exposes its terminal through two independent layers (an ordinary SDK layer and a privileged `com.xgd.possystemservice` layer). Serial number comes entirely from the ordinary layer (`DeviceEngine.deviceInfo.sn`), so this adapter only binds that layer — the privileged layer is irrelevant to serial-number reading and is not wired here.

- [ ] **Step 1: Create `device-sdk-nexgo/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.devicesdk.nexgo"
}

apply(from = "$rootDir/gradle/android-vendor-adapter.gradle.kts")

dependencies {
    implementation(group = "", name = "nexgo-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "core-release_1.0.63", ext = "aar")
    implementation(group = "", name = "logger-release_1.0.2", ext = "aar")
}
```

- [ ] **Step 2: Create the Nexgo binder**

`device-sdk-nexgo/src/main/kotlin/com/cashup/devicesdk/nexgo/NexgoBinding.kt`:

```kotlin
package com.cashup.devicesdk.nexgo

import android.content.Context
import com.cashup.devicesdk.DeviceContext
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.platform.Platform

class AndroidDeviceContext(val context: Context) : DeviceContext

/** The ordinary SDK layer. Without `platform` this is not a Nexgo terminal. */
data class NexgoServices(val engine: DeviceEngine? = null, val platform: Platform? = null) {
    val bound: Boolean get() = platform != null
}

fun interface NexgoBinder {
    suspend fun bind(context: Context): NexgoServices
}

class ApiProxyNexgoBinder : NexgoBinder {

    override suspend fun bind(context: Context): NexgoServices {
        val application = context.applicationContext

        val engine = try {
            APIProxy.getDeviceEngine(application)
        } catch (unavailable: Exception) {
            null
        }

        val platform = try {
            engine?.platform
        } catch (unavailable: Exception) {
            null
        }

        if (platform == null) return NexgoServices()
        return NexgoServices(engine = engine, platform = platform)
    }
}
```

- [ ] **Step 3: Write the failing test for `NexgoDeviceSdk`**

`device-sdk-nexgo/src/test/kotlin/com/cashup/devicesdk/nexgo/NexgoDeviceSdkTest.kt`:

```kotlin
package com.cashup.devicesdk.nexgo

import android.content.Context
import com.nexgo.oaf.apiv3.DeviceEngine
import com.nexgo.oaf.apiv3.platform.Platform
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NexgoDeviceSdkTest {

    @Test
    fun `connect returns true and serialNumber reads sn when the nexgo sdk binds`() = runTest {
        val deviceInfo = mockk<com.nexgo.oaf.apiv3.DeviceInfo> {
            every { sn } returns "NX-0001"
        }
        val engine = mockk<DeviceEngine> {
            every { platform } returns mockk<Platform>()
            every { this@mockk.deviceInfo } returns deviceInfo
        }
        val sdk = NexgoDeviceSdk(binder = NexgoBinder { NexgoServices(engine = engine, platform = engine.platform) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(connected)
        assertEquals("NX-0001", sdk.serialNumber())
    }

    @Test
    fun `connect returns false and serialNumber is null when the nexgo sdk does not bind`() = runTest {
        val sdk = NexgoDeviceSdk(binder = NexgoBinder { NexgoServices(engine = null, platform = null) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(!connected)
        assertNull(sdk.serialNumber())
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :device-sdk-nexgo:test`
Expected: FAIL — `NexgoDeviceSdk` unresolved reference.

- [ ] **Step 5: Implement `NexgoDeviceSdk`**

`device-sdk-nexgo/src/main/kotlin/com/cashup/devicesdk/nexgo/NexgoDeviceSdk.kt`:

```kotlin
package com.cashup.devicesdk.nexgo

import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.DeviceContext
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner

class NexgoDeviceSdk(
    private val binder: NexgoBinder = ApiProxyNexgoBinder(),
) : DeviceSdk {

    override val vendorId: String = VENDOR
    override val capabilities: Set<Capability> = emptySet()
    override val cardReader: CardReader? = null
    override val printer: Printer? = null
    override val scanner: Scanner? = null

    private var services: NexgoServices = NexgoServices()

    override suspend fun connect(context: DeviceContext): Boolean {
        val androidContext = (context as? AndroidDeviceContext)?.context
            ?: return false
        if (services.bound) return true
        services = binder.bind(androidContext)
        return services.bound
    }

    override suspend fun serialNumber(): String? {
        val engine = services.engine ?: return null
        return try {
            engine.deviceInfo?.sn?.trim()?.takeUnless { it.isEmpty() }
        } catch (unavailable: Exception) {
            null
        }
    }

    companion object {
        const val VENDOR = "nexgo"
    }
}

fun registerNexgoDeviceSdk() {
    com.cashup.devicesdk.DeviceSdkRegistry.register("NEXGO") { NexgoDeviceSdk() }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :device-sdk-nexgo:test`
Expected: PASS, 2 tests green.

- [ ] **Step 7: Commit**

```bash
git add device-sdk-nexgo/build.gradle.kts \
  device-sdk-nexgo/src/main/kotlin/com/cashup/devicesdk/nexgo/NexgoBinding.kt \
  device-sdk-nexgo/src/main/kotlin/com/cashup/devicesdk/nexgo/NexgoDeviceSdk.kt \
  device-sdk-nexgo/src/test/kotlin/com/cashup/devicesdk/nexgo/NexgoDeviceSdkTest.kt
git commit -m "feat(device-sdk-nexgo): add Nexgo serial-number adapter"
```

---

## Task 7: `device-sdk-topwise`

**Files:**
- Create: `device-sdk-topwise/build.gradle.kts`
- Create: `device-sdk-topwise/src/main/kotlin/com/cashup/devicesdk/topwise/TopwiseBinding.kt`
- Create: `device-sdk-topwise/src/main/kotlin/com/cashup/devicesdk/topwise/TopwiseDeviceSdk.kt`
- Test: `device-sdk-topwise/src/test/kotlin/com/cashup/devicesdk/topwise/TopwiseDeviceSdkTest.kt`

**Interfaces:**
- Consumes: `com.cashup.devicesdk.{DeviceSdk, DeviceContext, Capability, CardReader, Printer, Scanner}` from `device-sdk-api` (Task 2).
- Produces: `TopwiseDeviceSdk : DeviceSdk` (`vendorId = "topwise"`), `registerTopwiseDeviceSdk()` registering it under model prefix `"TOPWISE"`. Note the AAR filename is `topwize-release-core_1.0.63.aar` (vendor's own naming), while the module/vendor id stay `topwise` to match `docs/EDC_PAYMENT_APP_DESIGN.md` §3's `device-sdk-topwise`.

- [ ] **Step 1: Create `device-sdk-topwise/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.devicesdk.topwise"
}

apply(from = "$rootDir/gradle/android-vendor-adapter.gradle.kts")

dependencies {
    implementation(group = "", name = "topwize-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "core-release_1.0.63", ext = "aar")
    implementation(group = "", name = "logger-release_1.0.2", ext = "aar")
}
```

- [ ] **Step 2: Create the Topwise binder**

`device-sdk-topwise/src/main/kotlin/com/cashup/devicesdk/topwise/TopwiseBinding.kt`:

```kotlin
package com.cashup.devicesdk.topwise

import android.content.Context
import com.cashup.devicesdk.DeviceContext
import com.topwise.cloudpos.aidl.system.AidlSystem
import com.topwise.cloudpos.service.DeviceServiceManager
import kotlinx.coroutines.delay

class AndroidDeviceContext(val context: Context) : DeviceContext

data class TopwiseServices(val system: AidlSystem? = null) {
    val bound: Boolean get() = system != null
}

fun interface TopwiseBinder {
    suspend fun bind(context: Context): TopwiseServices
}

/**
 * The production binder. `init()` starts an asynchronous bind and returns
 * immediately, so the manager is null for a short while afterwards — polling
 * is the vendor's intended usage. The timeout is what keeps a non-Topwise
 * terminal from hanging: on another vendor's terminal, nothing will ever bind.
 */
class DeviceServiceManagerBinder(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val pollMillis: Long = DEFAULT_POLL_MILLIS,
    private val elapsedMillis: () -> Long = System::currentTimeMillis,
) : TopwiseBinder {

    override suspend fun bind(context: Context): TopwiseServices {
        val manager = try {
            DeviceServiceManager.getInstance()
        } catch (unavailable: Exception) {
            return TopwiseServices()
        }

        try {
            manager.init(context.applicationContext)
        } catch (unavailable: Exception) {
            return TopwiseServices()
        }

        val deadline = elapsedMillis() + timeoutMillis
        while (elapsedMillis() < deadline) {
            val system = try {
                manager.systemManager
            } catch (unavailable: Exception) {
                null
            }
            if (system != null) return TopwiseServices(system)
            delay(pollMillis)
        }
        return TopwiseServices()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_POLL_MILLIS = 100L
    }
}
```

- [ ] **Step 3: Write the failing test for `TopwiseDeviceSdk`**

`device-sdk-topwise/src/test/kotlin/com/cashup/devicesdk/topwise/TopwiseDeviceSdkTest.kt`:

```kotlin
package com.cashup.devicesdk.topwise

import android.content.Context
import com.topwise.cloudpos.aidl.system.AidlSystem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TopwiseDeviceSdkTest {

    @Test
    fun `connect returns true and serialNumber reads serialNo when the topwise sdk binds`() = runTest {
        val system = mockk<AidlSystem> {
            every { serialNo } returns "TW-0001"
        }
        val sdk = TopwiseDeviceSdk(binder = TopwiseBinder { TopwiseServices(system = system) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(connected)
        assertEquals("TW-0001", sdk.serialNumber())
    }

    @Test
    fun `connect returns false and serialNumber is null when the topwise sdk does not bind`() = runTest {
        val sdk = TopwiseDeviceSdk(binder = TopwiseBinder { TopwiseServices(system = null) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(!connected)
        assertNull(sdk.serialNumber())
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :device-sdk-topwise:test`
Expected: FAIL — `TopwiseDeviceSdk` unresolved reference.

- [ ] **Step 5: Implement `TopwiseDeviceSdk`**

`device-sdk-topwise/src/main/kotlin/com/cashup/devicesdk/topwise/TopwiseDeviceSdk.kt`:

```kotlin
package com.cashup.devicesdk.topwise

import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.DeviceContext
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner

class TopwiseDeviceSdk(
    private val binder: TopwiseBinder = DeviceServiceManagerBinder(),
) : DeviceSdk {

    override val vendorId: String = VENDOR
    override val capabilities: Set<Capability> = emptySet()
    override val cardReader: CardReader? = null
    override val printer: Printer? = null
    override val scanner: Scanner? = null

    private var services: TopwiseServices = TopwiseServices()

    override suspend fun connect(context: DeviceContext): Boolean {
        val androidContext = (context as? AndroidDeviceContext)?.context
            ?: return false
        if (services.bound) return true
        services = binder.bind(androidContext)
        return services.bound
    }

    override suspend fun serialNumber(): String? {
        val system = services.system ?: return null
        return try {
            system.serialNo?.trim()?.takeUnless { it.isEmpty() }
        } catch (unavailable: Exception) {
            null
        }
    }

    companion object {
        const val VENDOR = "topwise"
    }
}

fun registerTopwiseDeviceSdk() {
    com.cashup.devicesdk.DeviceSdkRegistry.register("TOPWISE") { TopwiseDeviceSdk() }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :device-sdk-topwise:test`
Expected: PASS, 2 tests green.

- [ ] **Step 7: Commit**

```bash
git add device-sdk-topwise/build.gradle.kts \
  device-sdk-topwise/src/main/kotlin/com/cashup/devicesdk/topwise/TopwiseBinding.kt \
  device-sdk-topwise/src/main/kotlin/com/cashup/devicesdk/topwise/TopwiseDeviceSdk.kt \
  device-sdk-topwise/src/test/kotlin/com/cashup/devicesdk/topwise/TopwiseDeviceSdkTest.kt
git commit -m "feat(device-sdk-topwise): add Topwise serial-number adapter"
```

---

## Task 8: `device-sdk-szanfu`

**Files:**
- Create: `device-sdk-szanfu/build.gradle.kts`
- Create: `device-sdk-szanfu/src/main/kotlin/com/cashup/devicesdk/szanfu/SzanfuBinding.kt`
- Create: `device-sdk-szanfu/src/main/kotlin/com/cashup/devicesdk/szanfu/SzanfuDeviceSdk.kt`
- Test: `device-sdk-szanfu/src/test/kotlin/com/cashup/devicesdk/szanfu/SzanfuDeviceSdkTest.kt`

**Interfaces:**
- Consumes: `com.cashup.devicesdk.{DeviceSdk, DeviceContext, Capability, CardReader, Printer, Scanner}` from `device-sdk-api` (Task 2).
- Produces: `SzanfuDeviceSdk : DeviceSdk` (`vendorId = "szanfu"`), `registerSzanfuDeviceSdk()` registering it under model prefix `"ANFU"`. Szanfu (SZ Anfu) is not one of the 9 vendors named in `docs/EDC_PAYMENT_APP_DESIGN.md` §3 — it is included because its AAR is available and it costs nothing extra to wire up; note this in the "Out of Scope" section below rather than silently expanding the design spec's vendor list.

- [ ] **Step 1: Create `device-sdk-szanfu/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.cashup.devicesdk.szanfu"
}

apply(from = "$rootDir/gradle/android-vendor-adapter.gradle.kts")

dependencies {
    implementation(group = "", name = "szanfu-release-core_1.0.63", ext = "aar")
    implementation(group = "", name = "core-release_1.0.63", ext = "aar")
    implementation(group = "", name = "logger-release_1.0.2", ext = "aar")
}
```

- [ ] **Step 2: Create the Szanfu binder**

`device-sdk-szanfu/src/main/kotlin/com/cashup/devicesdk/szanfu/SzanfuBinding.kt`:

```kotlin
package com.cashup.devicesdk.szanfu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.cashup.devicesdk.DeviceContext
import com.szanfu.sdk.api.ISdkServiceManager
import com.szanfu.sdk.api.system.ISystemBinderService
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AndroidDeviceContext(val context: Context) : DeviceContext

/** SZ Anfu is the only vendor in this fleet bound by hand — an ordinary `bindService`. */
data class SzanfuServices(val system: ISystemBinderService? = null) {
    val bound: Boolean get() = system != null
}

fun interface SzanfuBinder {
    suspend fun bind(context: Context): SzanfuServices
}

/**
 * The production binder. The intent action, package, and service index below
 * are the values the sibling `tms-agent` repo's `SzanfuBinding.kt` documents
 * as taken from disassembling the vendor's own SDK wrapper — SZ Anfu ships no
 * AIDL for `ISdkServiceManager` and no written statement of what
 * `getService(int)` indexes.
 */
class BindServiceSzanfuBinder(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : SzanfuBinder {

    override suspend fun bind(context: Context): SzanfuServices {
        val application = context.applicationContext

        val binder = try {
            withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine<IBinder?> { continuation ->
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (continuation.isActive) continuation.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = Unit
                    }

                    val intent = Intent().setPackage(SERVICE_PACKAGE).setAction(SERVICE_ACTION)

                    val started = try {
                        application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                    } catch (unavailable: Exception) {
                        false
                    }

                    if (!started && continuation.isActive) continuation.resume(null)
                }
            }
        } catch (unavailable: Exception) {
            null
        } ?: return SzanfuServices()

        val manager = try {
            ISdkServiceManager.Stub.asInterface(binder)
        } catch (unavailable: Exception) {
            null
        } ?: return SzanfuServices()

        return SzanfuServices(
            system = try {
                ISystemBinderService.Stub.asInterface(manager.getService(SYSTEM_SERVICE_INDEX))
            } catch (unavailable: Exception) {
                null
            },
        )
    }

    private companion object {
        const val SERVICE_PACKAGE = "com.szanfu.sdk.service"
        const val SERVICE_ACTION = "com.szanfu.sdk.service.SDK_SERVICE"
        const val SYSTEM_SERVICE_INDEX = 1
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
```

- [ ] **Step 3: Write the failing test for `SzanfuDeviceSdk`**

`device-sdk-szanfu/src/test/kotlin/com/cashup/devicesdk/szanfu/SzanfuDeviceSdkTest.kt`:

```kotlin
package com.cashup.devicesdk.szanfu

import android.content.Context
import com.szanfu.sdk.api.system.ISystemBinderService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SzanfuDeviceSdkTest {

    @Test
    fun `connect returns true and serialNumber reads sn when the szanfu sdk binds`() = runTest {
        val system = mockk<ISystemBinderService> {
            every { sn } returns "SZ-0001"
        }
        val sdk = SzanfuDeviceSdk(binder = SzanfuBinder { SzanfuServices(system = system) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(connected)
        assertEquals("SZ-0001", sdk.serialNumber())
    }

    @Test
    fun `connect returns false and serialNumber is null when the szanfu sdk does not bind`() = runTest {
        val sdk = SzanfuDeviceSdk(binder = SzanfuBinder { SzanfuServices(system = null) })

        val connected = sdk.connect(AndroidDeviceContext(mockk<Context>(relaxed = true)))

        assertTrue(!connected)
        assertNull(sdk.serialNumber())
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :device-sdk-szanfu:test`
Expected: FAIL — `SzanfuDeviceSdk` unresolved reference.

- [ ] **Step 5: Implement `SzanfuDeviceSdk`**

`device-sdk-szanfu/src/main/kotlin/com/cashup/devicesdk/szanfu/SzanfuDeviceSdk.kt`:

```kotlin
package com.cashup.devicesdk.szanfu

import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.DeviceContext
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner

class SzanfuDeviceSdk(
    private val binder: SzanfuBinder = BindServiceSzanfuBinder(),
) : DeviceSdk {

    override val vendorId: String = VENDOR
    override val capabilities: Set<Capability> = emptySet()
    override val cardReader: CardReader? = null
    override val printer: Printer? = null
    override val scanner: Scanner? = null

    private var services: SzanfuServices = SzanfuServices()

    override suspend fun connect(context: DeviceContext): Boolean {
        val androidContext = (context as? AndroidDeviceContext)?.context
            ?: return false
        if (services.bound) return true
        services = binder.bind(androidContext)
        return services.bound
    }

    override suspend fun serialNumber(): String? {
        val system = services.system ?: return null
        return try {
            system.sn?.trim()?.takeUnless { it.isEmpty() }
        } catch (unavailable: Exception) {
            null
        }
    }

    companion object {
        const val VENDOR = "szanfu"
    }
}

fun registerSzanfuDeviceSdk() {
    com.cashup.devicesdk.DeviceSdkRegistry.register("ANFU") { SzanfuDeviceSdk() }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :device-sdk-szanfu:test`
Expected: PASS, 2 tests green.

- [ ] **Step 7: Commit**

```bash
git add device-sdk-szanfu/build.gradle.kts \
  device-sdk-szanfu/src/main/kotlin/com/cashup/devicesdk/szanfu/SzanfuBinding.kt \
  device-sdk-szanfu/src/main/kotlin/com/cashup/devicesdk/szanfu/SzanfuDeviceSdk.kt \
  device-sdk-szanfu/src/test/kotlin/com/cashup/devicesdk/szanfu/SzanfuDeviceSdkTest.kt
git commit -m "feat(device-sdk-szanfu): add Szanfu serial-number adapter"
```

---

## Task 9: Full build verification

**Files:** none (verification only).

**Interfaces:**
- Consumes: every module from Tasks 1–8.
- Produces: nothing new — confirms the whole repo builds and tests green together before this plan is considered done.

- [ ] **Step 1: Run every module's tests together**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`. `common-core`, `device-sdk-api`, and `signing-core` are unaffected by this plan and still pass; all 6 vendor adapters' `connect`/`serialNumber` tests pass (12 tests: 2 per vendor × 6 vendors), plus the 2 new `DeviceSdkRegistryProbingTest` tests in `device-sdk-api`.

- [ ] **Step 2: Run a full build of every module**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` across all 9 modules (`common-core`, `device-sdk-api`, `signing-core`, `device-sdk-pax`, `device-sdk-sunmi`, `device-sdk-centerm`, `device-sdk-nexgo`, `device-sdk-topwise`, `device-sdk-szanfu`).

- [ ] **Step 3: Commit any final cleanup**

If Steps 1–2 required no changes, there is nothing to commit here — this step only applies if verification surfaced a fix. If it did:

```bash
git add -A
git commit -m "fix: address issues found in full-build verification"
```

---

## Out of Scope

- **Feitian, Urovo, Newland, Tianyu adapters** — no vendor SDK AAR is available on this machine for these 4 of the 9 vendors `docs/EDC_PAYMENT_APP_DESIGN.md` §3 names. Source their AARs (or a licensed alternative) before planning these.
- **Szanfu is extra, not in the original 9** — included because its AAR was available at no extra cost; flagged here so `docs/EDC_PAYMENT_APP_DESIGN.md` §3's vendor list and this repo's actual module list are not silently allowed to drift apart. Worth a one-line addition to that spec once this plan lands.
- **`Build.MODEL`-prefix registration is an unverified guess.** The prefixes used in Tasks 3–8 (`"PAX"`, `"SUNMI"`, `"CENTERM"`, `"NEXGO"`, `"TOPWISE"`, `"ANFU"`) are reasonable guesses, not confirmed against real terminal `Build.MODEL` strings. `DeviceSdkRegistry.resolveByProbing` (Task 2) is the resolution path that doesn't depend on this guess being right and should be preferred by callers; `resolve(buildModel)` stays available for compatibility with its existing test but its vendor-prefix table needs real-device verification before being trusted.
- **Card reader, printer, and QR scanner capabilities** on all 6 adapters — only `connect`/`serialNumber` are implemented.
- **Physical-hardware / Robolectric verification of the production binders** (`NeptuneLitePaxBinder`, `PayKernelSunmiBinder`, `DevicesFactoryCentermBinder`, `ApiProxyNexgoBinder`, `DeviceServiceManagerBinder`, `BindServiceSzanfuBinder`) — this plan's tests inject fakes; the real binders need manual verification on physical terminals.
- **`provisioning-core`** — a separate, not-yet-written plan that will consume `DeviceSdkRegistry.resolveByProbing(...)`. Not designed or planned here.
- **Login/JWT flows** — unrelated to this plan.

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
        private val vendorState: VendorModuleState = VendorModuleState.READY,
        private val failVendorWrite: Boolean = false,
        private val failVaultSlot: Int? = null,
        private val detectFailure: Exception? = null,
    ) : KeyManagerGateway {
        val vendorWrites = mutableListOf<String>()
        val vaultWrites = mutableListOf<Int>()
        val events = mutableListOf<String>()
        var clearCount = 0

        override suspend fun detectVendorModule(): VendorModuleState {
            events += "detect"
            detectFailure?.let { throw it }
            return vendorState
        }

        override fun writeToVendorModule(ipek: ByteArray, ksn: ByteArray): Boolean {
            events += "vendorWrite"
            if (failVendorWrite) return false
            vendorWrites += ipek.joinToString("") { "%02x".format(it) }
            return true
        }

        override fun writeToVaultSlot(slot: Int, ipek: ByteArray, ksn: ByteArray): Boolean {
            events += "vaultWrite"
            if (slot == failVaultSlot) return false
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
        assertEquals(listOf(10, 11, 13), gateway.vaultWrites)
    }

    @Test
    fun `every purpose falls back to the vault when there is no vendor module`() = runTest {
        val gateway = FakeGateway(vendorState = VendorModuleState.ABSENT)
        val installer = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN")

        val result = installer.install(materials)

        val outcomes = (result as TerminalKeyInstallResult.Installed).outcomes
        assertTrue(outcomes.all { it.backing == KeyBacking.TEE_VAULT_ONLY })
        assertEquals(0, gateway.vendorWrites.size)
        assertEquals(listOf(10, 12, 11, 13), gateway.vaultWrites)
    }

    @Test
    fun `an unreachable vendor module fails the install before anything is written`() = runTest {
        val gateway = FakeGateway(vendorState = VendorModuleState.UNREACHABLE)
        val installer = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN")

        val result = installer.install(materials)

        assertTrue(result is TerminalKeyInstallResult.Failed)
        assertEquals("PIN", (result as TerminalKeyInstallResult.Failed).purpose)
        assertEquals(listOf("detect"), gateway.events)
    }

    @Test
    fun `a detection that throws fails the install without leaking the vendor message`() = runTest {
        val gateway = FakeGateway(detectFailure = IllegalStateException("vendor binder dead: internal detail"))

        val result = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN").install(materials)

        assertTrue(result is TerminalKeyInstallResult.Failed)
        val failed = result as TerminalKeyInstallResult.Failed
        assertEquals("PIN", failed.purpose)
        assertTrue(!failed.reason.contains("binder"))
        assertEquals(listOf("detect"), gateway.events)
    }

    @Test
    fun `the vendor module is detected before the first key is written`() = runTest {
        val gateway = FakeGateway()

        EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN").install(materials)

        assertEquals("detect", gateway.events.first())
        assertEquals(1, gateway.events.count { it == "detect" })
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
    fun `an unreachable vendor module names a non-PIN vendor slot purpose and writes nothing`() = runTest {
        val gateway = FakeGateway(vendorState = VendorModuleState.UNREACHABLE)

        val result = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "EMV").install(materials)

        assertEquals("EMV", (result as TerminalKeyInstallResult.Failed).purpose)
        assertEquals(listOf("detect"), gateway.events)
        assertTrue(gateway.vaultWrites.isEmpty())
        assertTrue(gateway.vendorWrites.isEmpty())
    }

    @Test
    fun `a vendor write failing mid-list stops later purposes and reports the failed one`() = runTest {
        val gateway = FakeGateway(failVendorWrite = true)

        val result = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "AMOUNT").install(materials)

        assertEquals("AMOUNT", (result as TerminalKeyInstallResult.Failed).purpose)
        // TRACK and PIN precede AMOUNT; EMV follows and must never be attempted.
        assertEquals(listOf(10, 12), gateway.vaultWrites)
        assertEquals(
            listOf("detect", "vaultWrite", "vaultWrite", "vendorWrite"),
            gateway.events,
        )
    }

    @Test
    fun `a vault write failing mid-list stops later purposes and reports the failed one`() = runTest {
        val gateway = FakeGateway(vendorState = VendorModuleState.ABSENT, failVaultSlot = 12)

        val result = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN").install(materials)

        assertEquals("PIN", (result as TerminalKeyInstallResult.Failed).purpose)
        assertEquals(listOf(10), gateway.vaultWrites)
        assertEquals(2, gateway.events.count { it == "vaultWrite" })
    }

    @Test
    fun `detect runs once per install call and again on a second install`() = runTest {
        val gateway = FakeGateway()
        val installer = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN")

        installer.install(materials)
        assertEquals(1, gateway.events.count { it == "detect" })

        installer.install(materials)
        assertEquals(2, gateway.events.count { it == "detect" })
        // First write of the second install is preceded by its own detect.
        val secondDetect = gateway.events.lastIndexOf("detect")
        assertTrue(secondDetect > 0)
        assertTrue(gateway.events.drop(secondDetect + 1).none { it == "detect" })
    }

    @Test
    fun `failure reasons are non-empty and carry no key bytes`() = runTest {
        val secretLike = TerminalKeyMaterial(
            purpose = "PIN",
            ipek = byteArrayOf(0x7A, 0x5B, 0x3C, 0x2D),
            ksn = byteArrayOf(0x6E, 0x4F),
        )
        val forbidden = listOf("7a5b3c2d", "7A5B3C2D", "6e4f", "6E4F", "122", "[B@")
        val failures = listOf(
            FakeGateway(vendorState = VendorModuleState.UNREACHABLE),
            FakeGateway(failVendorWrite = true),
            FakeGateway(vendorState = VendorModuleState.ABSENT, failVaultSlot = 12),
        ).map {
            EdcSdkTerminalKeyInstaller(it, vendorSlotPurpose = "PIN").install(listOf(secretLike))
        }

        failures.forEach { result ->
            val reason = (result as TerminalKeyInstallResult.Failed).reason
            assertTrue(reason.isNotBlank())
            forbidden.forEach { assertTrue("reason leaked $it", !reason.contains(it)) }
        }
    }

    @Test
    fun `wipe clears everything`() = runTest {
        val gateway = FakeGateway()

        EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN").wipe()

        assertEquals(1, gateway.clearCount)
    }
}

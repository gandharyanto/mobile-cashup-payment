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
        assertEquals(listOf(10, 11, 13), gateway.vaultWrites)
    }

    @Test
    fun `every purpose falls back to the vault when there is no vendor module`() = runTest {
        val gateway = FakeGateway(vendorPresent = false)
        val installer = EdcSdkTerminalKeyInstaller(gateway, vendorSlotPurpose = "PIN")

        val result = installer.install(materials)

        val outcomes = (result as TerminalKeyInstallResult.Installed).outcomes
        assertTrue(outcomes.all { it.backing == KeyBacking.TEE_VAULT_ONLY })
        assertEquals(0, gateway.vendorWrites.size)
        assertEquals(listOf(10, 12, 11, 13), gateway.vaultWrites)
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

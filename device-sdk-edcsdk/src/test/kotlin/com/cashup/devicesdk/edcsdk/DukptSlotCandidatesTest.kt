package com.cashup.devicesdk.edcsdk

import org.junit.Assert.assertEquals
import org.junit.Test

class DukptSlotCandidatesTest {

    @Test
    fun `purposes other than PIN always come from the vault slot`() {
        assertEquals(listOf(10), dukptSlotCandidates("TRACK", vendorKeyIndex = 2))
        assertEquals(listOf(11), dukptSlotCandidates("AMOUNT", vendorKeyIndex = 2))
        assertEquals(listOf(13), dukptSlotCandidates("EMV", vendorKeyIndex = null))
    }

    @Test
    fun `PIN without a vendor module comes from the vault slot`() {
        assertEquals(listOf(12), dukptSlotCandidates("PIN", vendorKeyIndex = null))
    }

    @Test
    fun `PIN with a vendor module tries the vendor slot first and falls back to the vault slot`() {
        // Reader mPOS yang tersambung belakangan (mis. ANFU) mengisi DeviceManager.systemKey, tapi IPEK PIN hasil
        // provisioning di HP ada di vault. Tanpa cadangan ini, kuncinya "tidak terpasang".
        assertEquals(listOf(2, 12), dukptSlotCandidates("PIN", vendorKeyIndex = 2))
    }

    @Test
    fun `PIN does not repeat a slot when the vendor slot is the vault slot`() {
        assertEquals(listOf(12), dukptSlotCandidates("PIN", vendorKeyIndex = 12))
    }
}

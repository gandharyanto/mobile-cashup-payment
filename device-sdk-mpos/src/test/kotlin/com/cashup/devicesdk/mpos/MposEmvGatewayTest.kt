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

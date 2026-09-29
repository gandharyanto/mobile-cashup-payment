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

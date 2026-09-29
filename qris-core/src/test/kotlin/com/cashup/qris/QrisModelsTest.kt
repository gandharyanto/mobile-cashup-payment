package com.cashup.qris

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrisModelsTest {
    @Test fun `status parse maps known values and falls back to UNKNOWN`() {
        assertEquals(QrisStatus.INITIATED, QrisStatus.parse("INITIATED"))
        assertEquals(QrisStatus.AUTHORIZED, QrisStatus.parse("authorized"))
        assertEquals(QrisStatus.UNKNOWN, QrisStatus.parse("SOMETHING_NEW"))
        assertEquals(QrisStatus.UNKNOWN, QrisStatus.parse(null))
    }

    @Test fun `paid and final flags follow the api doc status table`() {
        assertTrue(QrisStatus.AUTHORIZED.isPaid)
        assertTrue(QrisStatus.SETTLED.isPaid)
        assertFalse(QrisStatus.EXPIRED.isPaid)
        assertTrue(QrisStatus.AUTHORIZED.isFinal)
        assertTrue(QrisStatus.SETTLED.isFinal)
        assertTrue(QrisStatus.EXPIRED.isFinal)
        assertTrue(QrisStatus.FAILED.isFinal)
        assertFalse(QrisStatus.INITIATED.isFinal)
        assertFalse("unknown status must keep polling", QrisStatus.UNKNOWN.isFinal)
    }

    @Test fun `qrSupport parse treats unknown as STATIC so it is never offered as dynamic`() {
        assertEquals(QrSupport.BOTH, QrSupport.parse("BOTH"))
        assertEquals(QrSupport.STATIC, QrSupport.parse("WHATEVER"))
        assertEquals(QrSupport.STATIC, QrSupport.parse(null))
    }

    @Test fun `pay app dto maps nullable name and logo and derives supportsDynamic`() {
        val app = PayAppDto(qrisHostId = 2, hostName = "CASHLEZ-AVATAR", payAppName = null,
            payAppLogoRef = null, qrSupport = "BOTH", qrExpirySeconds = 900).toDomain()!!
        assertEquals(2, app.qrisHostId)
        assertNull(app.payAppName)
        assertTrue(app.supportsDynamic)
        assertEquals("CASHLEZ-AVATAR", app.displayName)
        assertEquals(900, app.qrExpirySeconds)
        assertFalse(PayAppDto(qrisHostId = 3, qrSupport = "STATIC").toDomain()!!.supportsDynamic)
    }

    @Test fun `pay app dto without host id is dropped`() {
        assertNull(PayAppDto(qrisHostId = null, qrSupport = "DYNAMIC").toDomain())
    }

    @Test fun `qr code dto requires transactionId and qrContent`() {
        val ok = QrCodeDto(transactionId = "tx-1", qrContent = "0002", baseAmount = BigDecimal("15000"),
            qrisHostId = 2, status = "INITIATED").toDomain()!!
        assertEquals("tx-1", ok.transactionId)
        assertEquals(BigDecimal("15000"), ok.baseAmount)
        assertNull(QrCodeDto(transactionId = "tx-1", qrContent = null, baseAmount = BigDecimal.ONE).toDomain())
        assertNull(QrCodeDto(transactionId = null, qrContent = "0002", baseAmount = BigDecimal.ONE).toDomain())
    }

    @Test fun `inquiry dto keeps nulls until paid`() {
        val pending = InquiryDto(transactionId = "tx-1", status = "INITIATED", baseAmount = BigDecimal("15000")).toDomain()!!
        assertEquals(QrisStatus.INITIATED, pending.status)
        assertNull(pending.rrn)
        val paid = InquiryDto(transactionId = "tx-1", status = "AUTHORIZED", baseAmount = BigDecimal("15000"),
            rrn = "123", approvalCode = "885790", paidAt = "2026-09-24T03:16:10Z", issuerName = "DANA").toDomain()!!
        assertTrue(paid.status.isPaid)
        assertEquals("123", paid.rrn)
        assertEquals("2026-09-24T03:16:10Z", paid.paidAtRaw)
        assertNull(InquiryDto(transactionId = null, status = "AUTHORIZED").toDomain())
    }
}

package com.cashup.feature.qris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeEncoderTest {
    @Test fun `encode returns a square matrix of the requested size with dark modules`() {
        val matrix = QrCodeEncoder.encode("00020101021226690014ID.CO.QRIS.WWW0215ID10200000000006304ABCD", 256)
        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)
        var dark = 0
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix.get(x, y)) dark++
        assertTrue("QR must contain dark modules", dark > 0)
    }

    @Test fun `top-left finder pattern corner is dark`() {
        val matrix = QrCodeEncoder.encode("hello", 200)
        // Margin 1 modul -> ada modul gelap di pojok finder pattern, bukan di piksel (0,0) yang putih.
        assertTrue(!matrix.get(0, 0))
        var found = false
        for (i in 0 until 40) if (matrix.get(i, i)) found = true
        assertTrue(found)
    }
}

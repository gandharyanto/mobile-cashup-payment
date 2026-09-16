package com.cashup.app.scan

import com.cashup.devicesdk.Scanner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanSourceTest {

    private class FakeScanner(private val result: String?) : Scanner {
        var called = false
        override suspend fun scanQr(timeoutMillis: Long): String? {
            called = true
            return result
        }
    }

    @Test
    fun `uses the vendor scanner when it returns a code`() = runTest {
        val vendor = FakeScanner("ABCD-1234")
        val camera = FakeScanner("WRONG")

        val result = QrScanSource(vendor) { camera }.scan(10_000)

        assertEquals("ABCD-1234", result)
        assertFalse("camera must stay off when the vendor scanner worked", camera.called)
    }

    @Test
    fun `falls back to the camera when there is no vendor scanner`() = runTest {
        val camera = FakeScanner("ABCD-1234")

        val result = QrScanSource(vendor = null) { camera }.scan(10_000)

        assertEquals("ABCD-1234", result)
        assertTrue(camera.called)
    }

    @Test
    fun `falls back to the camera when the vendor scanner yields nothing`() = runTest {
        val vendor = FakeScanner(null)
        val camera = FakeScanner("ABCD-1234")

        val result = QrScanSource(vendor) { camera }.scan(10_000)

        assertEquals("ABCD-1234", result)
        assertTrue(camera.called)
    }

    @Test
    fun `returns null when neither source produces a code`() = runTest {
        val result = QrScanSource(FakeScanner(null)) { FakeScanner(null) }.scan(10_000)

        assertNull(result)
    }
}

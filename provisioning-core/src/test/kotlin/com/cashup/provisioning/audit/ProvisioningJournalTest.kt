package com.cashup.provisioning.audit

import com.cashup.common.logging.PaymentLogger
import com.cashup.provisioning.domain.ProvisioningStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningJournalTest {

    private class RecordingLogger : PaymentLogger {
        val lines = mutableListOf<String>()
        override fun debug(tag: String, message: String) { lines += message }
        override fun warn(tag: String, message: String, throwable: Throwable?) { lines += message }
        override fun error(tag: String, message: String, throwable: Throwable?) { lines += message }
    }

    private fun journalWithClock(vararg times: Long): Pair<ProvisioningJournal, RecordingLogger> {
        val logger = RecordingLogger()
        var index = 0
        val journal = ProvisioningJournal(logger) { times[index++.coerceAtMost(times.size - 1)] }
        return journal to logger
    }

    @Test
    fun `records steps in order with their evidence`() {
        val (journal, _) = journalWithClock(1000, 1200)

        journal.start(ProvisioningStep.REDEEM, mapOf("challengeCode" to Evidence.token("ABCD-1234")))
        journal.ok(ProvisioningStep.REDEEM, mapOf("orderId" to "o-1"))

        assertEquals(2, journal.entries.size)
        assertEquals(StepStatus.STARTED, journal.entries[0].status)
        assertEquals("ABCD-1234", journal.entries[0].evidence["challengeCode"])
        assertEquals(StepStatus.OK, journal.entries[1].status)
        assertEquals("o-1", journal.entries[1].evidence["orderId"])
    }

    @Test
    fun `a completed step reports how long it took`() {
        val (journal, _) = journalWithClock(1000, 1200)

        journal.start(ProvisioningStep.DOWNLOAD_PACKAGE, emptyMap())
        journal.ok(ProvisioningStep.DOWNLOAD_PACKAGE, emptyMap())

        assertNull(journal.entries[0].durationMillis)
        assertEquals(200L, journal.entries[1].durationMillis)
    }

    @Test
    fun `a failure keeps the backend error code`() {
        val (journal, _) = journalWithClock(1000, 1100)

        journal.start(ProvisioningStep.REDEEM, emptyMap())
        journal.failed(ProvisioningStep.REDEEM, "PROVISIONING_TOKEN_INVALID", emptyMap())

        assertEquals("PROVISIONING_TOKEN_INVALID", journal.entries[1].errorCode)
        assertEquals(StepStatus.FAILED, journal.entries[1].status)
    }

    @Test
    fun `every entry is also handed to the logger`() {
        val (journal, logger) = journalWithClock(1000)

        journal.ok(ProvisioningStep.INSTALL_KEYS, mapOf("PIN" to "VENDOR_SECURE_MODULE"))

        assertEquals(1, logger.lines.size)
        assertTrue(logger.lines[0], logger.lines[0].contains("INSTALL_KEYS"))
        assertTrue(logger.lines[0], logger.lines[0].contains("VENDOR_SECURE_MODULE"))
    }

    @Test
    fun `render produces one readable line per entry for the report`() {
        val (journal, _) = journalWithClock(1000, 1050, 1300)

        journal.start(ProvisioningStep.UNWRAP_PACKAGE, emptyMap())
        journal.ok(
            ProvisioningStep.UNWRAP_PACKAGE,
            mapOf("PIN.ipek" to "len=16 fp=a3f9c1d2", "PIN.kcv" to "A1B2C3"),
        )

        val lines = journal.render().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1], lines[1].contains("UNWRAP_PACKAGE"))
        assertTrue(lines[1], lines[1].contains("PIN.kcv=A1B2C3"))
        assertTrue(lines[1], lines[1].contains("fp=a3f9c1d2"))
    }

    @Test
    fun `clear empties the journal for a fresh attempt`() {
        val (journal, _) = journalWithClock(1000)

        journal.ok(ProvisioningStep.SCAN_QR, emptyMap())
        journal.clear()

        assertTrue(journal.entries.isEmpty())
    }
}
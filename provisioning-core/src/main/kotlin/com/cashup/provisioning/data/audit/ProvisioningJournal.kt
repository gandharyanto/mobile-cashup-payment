package com.cashup.provisioning.audit

import com.cashup.common.logging.NoOpPaymentLogger
import com.cashup.common.logging.PaymentLogger
import java.util.Collections

enum class ProvisioningStep {
    DETECT_DEVICE,
    GENERATE_KEYS,
    SCAN_QR,
    REDEEM,
    DOWNLOAD_PACKAGE,
    UNWRAP_PACKAGE,
    VERIFY_KCV,
    INSTALL_KEYS,
    ACTIVATE,
    PERSIST_STATE,
    ROLLBACK,
}

enum class StepStatus { STARTED, OK, FAILED }

data class JournalEntry(
    val step: ProvisioningStep,
    val status: StepStatus,
    val atMillis: Long,
    val durationMillis: Long?,
    val evidence: Map<String, String>,
    val errorCode: String?,
)

/**
 * **SEMENTARA — dihapus sebelum produksi.** Alat bantu tahap awal untuk
 * membuktikan alur berjalan dan menyusun laporan selama uji coba di terminal.
 * Seluruh package `audit/` dicabut bersamaan; lihat checklist di
 * `docs/superpowers/plans/2026-09-16-provisioning.md` Task 10.
 *
 * Catatan berurutan tentang apa yang terjadi selama provisioning, beserta bukti
 * nilai yang cukup untuk dilaporkan dan dicocokkan dengan sisi backend.
 *
 * Isinya aman dibaca dan disalin: nilai rahasia masuk lewat [Evidence], yang
 * hanya bisa mengeluarkan panjang dan sidik jari terpotong. KCV dicatat utuh
 * karena memang itu fungsinya — bukti publik atas sebuah key.
 *
 * Jurnal ini **bukan** pengganti log aplikasi; tiap entri juga diteruskan ke
 * [PaymentLogger]. Bedanya, jurnal tetap hidup di memori sebagai satu kesatuan
 * sehingga layar Result bisa menampilkannya dan laporan bisa mengambilnya utuh,
 * tanpa mengais logcat.
 *
 * Durasi dihitung dari [start] ke [ok]/[failed] untuk langkah yang sama, jadi
 * laporan bisa menunjukkan langkah mana yang lambat — biasanya
 * [ProvisioningStep.DOWNLOAD_PACKAGE], yang menunggu dua HSM.
 */
class ProvisioningJournal(
    private val logger: PaymentLogger = NoOpPaymentLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val mutableEntries = Collections.synchronizedList(mutableListOf<JournalEntry>())
    private val startedAt = mutableMapOf<ProvisioningStep, Long>()

    val entries: List<JournalEntry> get() = mutableEntries.toList()

    fun start(step: ProvisioningStep, evidence: Map<String, String> = emptyMap()) {
        val now = clock()
        synchronized(startedAt) { startedAt[step] = now }
        record(step, StepStatus.STARTED, now, null, evidence, null)
    }

    fun ok(step: ProvisioningStep, evidence: Map<String, String> = emptyMap()) {
        val now = clock()
        record(step, StepStatus.OK, now, durationFor(step, now), evidence, null)
    }

    fun failed(step: ProvisioningStep, errorCode: String?, evidence: Map<String, String> = emptyMap()) {
        val now = clock()
        record(step, StepStatus.FAILED, now, durationFor(step, now), evidence, errorCode)
    }

    /** Satu baris per entri, siap disalin ke laporan. */
    fun render(): String = entries.joinToString("\n") { entry ->
        buildString {
            append(entry.atMillis)
            append(' ')
            append(entry.status.name.padEnd(7))
            append(' ')
            append(entry.step.name)
            entry.durationMillis?.let { append(" (${it}ms)") }
            entry.errorCode?.let { append(" error=").append(it) }
            entry.evidence.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        }
    }

    fun clear() {
        mutableEntries.clear()
        synchronized(startedAt) { startedAt.clear() }
    }

    private fun durationFor(step: ProvisioningStep, now: Long): Long? =
        synchronized(startedAt) { startedAt.remove(step) }?.let { now - it }

    private fun record(
        step: ProvisioningStep,
        status: StepStatus,
        atMillis: Long,
        durationMillis: Long?,
        evidence: Map<String, String>,
        errorCode: String?,
    ) {
        val entry = JournalEntry(step, status, atMillis, durationMillis, evidence.toMap(), errorCode)
        mutableEntries += entry
        val line = renderEntry(entry)
        when (status) {
            StepStatus.FAILED -> logger.error(TAG, line)
            else -> logger.debug(TAG, line)
        }
    }

    private fun renderEntry(entry: JournalEntry): String = buildString {
        append(entry.status.name)
        append(' ')
        append(entry.step.name)
        entry.durationMillis?.let { append(" (${it}ms)") }
        entry.errorCode?.let { append(" error=").append(it) }
        entry.evidence.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
    }

    private companion object {
        const val TAG = "Provisioning"
    }
}
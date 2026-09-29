package com.cashup.qris

import java.math.BigDecimal

enum class QrSupport {
    DYNAMIC, STATIC, BOTH;

    companion object {
        /** Nilai tak dikenal jatuh ke STATIC: tidak pernah ditawarkan sebagai dynamic. */
        fun parse(raw: String?): QrSupport = values().firstOrNull { it.name == raw?.uppercase() } ?: STATIC
    }
}

data class PayApp(
    val qrisHostId: Int,
    val hostName: String,
    val payAppName: String?,
    val payAppLogoRef: String?,
    val qrSupport: QrSupport,
    val qrExpirySeconds: Int,
) {
    val supportsDynamic: Boolean get() = qrSupport == QrSupport.DYNAMIC || qrSupport == QrSupport.BOTH
    val displayName: String get() = payAppName ?: hostName.ifBlank { "QRIS" }
}

data class GeneratedQr(
    val transactionId: String,
    val invoiceNumber: String?,
    val baseAmount: BigDecimal,
    val qrisHostId: Int,
    val payAppName: String?,
    val merchantName: String?,
    val qrContent: String,
    val merchantTrxId: String?,
)

enum class QrisStatus {
    INITIATED, AUTHORIZED, SETTLED, EXPIRED, FAILED, UNKNOWN;

    val isPaid: Boolean get() = this == AUTHORIZED || this == SETTLED

    /** UNKNOWN sengaja tidak final: status baru dari backend tidak boleh menghentikan polling. */
    val isFinal: Boolean get() = this != INITIATED && this != UNKNOWN

    companion object {
        fun parse(raw: String?): QrisStatus = values().firstOrNull { it.name == raw?.uppercase() } ?: UNKNOWN
    }
}

/** `data.detail` dari push MQTT. Semua opsional; `null` = tidak tersedia (sembunyikan barisnya). */
data class QrisPushDetail(
    val mid: String? = null,
    val tid: String? = null,
    val nmid: String? = null,
    val rrn: String? = null,
    val paidAt: String? = null,
    val qrType: String? = null,
    val acquirer: String? = null,
    val issuerNns: String? = null,
    val issuerName: String? = null,
    val customerPan: String? = null,
    val merchantPan: String? = null,
    val approvalCode: String? = null,
    val customerName: String? = null,
)

data class QrisPaymentResult(
    val transactionId: String,
    val status: QrisStatus,
    val amount: BigDecimal,
    val invoiceNumber: String? = null,
    val rrn: String? = null,
    val approvalCode: String? = null,
    val issuerName: String? = null,
    val customerName: String? = null,
    val customerPan: String? = null,
    /** String mentah dari server; tidak di-parse (API 23 tanpa java.time). */
    val paidAtRaw: String? = null,
    val pushDetail: QrisPushDetail? = null,
)

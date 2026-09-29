package com.cashup.qris

import java.math.BigDecimal

/** Dipakai bila backend tidak mengirim `qrExpirySeconds` (nilai contoh di dokumen API). */
private const val FALLBACK_EXPIRY_SECONDS = 900

// Gson mengisi field yang hilang dengan null walau tipenya non-null; karena itu semua field DTO
// dibuat nullable dan pemetaan ke domain menolak (mengembalikan null) bila field wajib hilang.

internal data class PayAppDto(
    val qrisHostId: Int? = null,
    val hostName: String? = null,
    val payAppName: String? = null,
    val payAppLogoRef: String? = null,
    val qrSupport: String? = null,
    val qrExpirySeconds: Int? = null,
) {
    fun toDomain(): PayApp? {
        val id = qrisHostId ?: return null
        return PayApp(id, hostName.orEmpty(), payAppName, payAppLogoRef,
            QrSupport.parse(qrSupport), qrExpirySeconds ?: FALLBACK_EXPIRY_SECONDS)
    }
}

internal data class GenerateQrRequest(
    val qrisHostId: Int,
    val baseAmount: BigDecimal,
    val merchantTrxId: String,
)

internal data class InquiryRequest(val transactionId: String)

internal data class QrCodeDto(
    val transactionId: String? = null,
    val invoiceNumber: String? = null,
    val status: String? = null,
    val baseAmount: BigDecimal? = null,
    val qrisHostId: Int? = null,
    val payAppName: String? = null,
    val merchantName: String? = null,
    val qrContent: String? = null,
    val merchantTrxId: String? = null,
) {
    fun toDomain(): GeneratedQr? {
        val tx = transactionId?.takeIf { it.isNotBlank() } ?: return null
        val content = qrContent?.takeIf { it.isNotBlank() } ?: return null
        return GeneratedQr(tx, invoiceNumber, baseAmount ?: BigDecimal.ZERO, qrisHostId ?: 0,
            payAppName, merchantName, content, merchantTrxId)
    }
}

internal data class InquiryDto(
    val transactionId: String? = null,
    val status: String? = null,
    val invoiceNumber: String? = null,
    val baseAmount: BigDecimal? = null,
    val rrn: String? = null,
    val approvalCode: String? = null,
    val paidAt: String? = null,
    val issuerName: String? = null,
    val customerPan: String? = null,
    val customerName: String? = null,
) {
    fun toDomain(): QrisPaymentResult? {
        val tx = transactionId?.takeIf { it.isNotBlank() } ?: return null
        return QrisPaymentResult(tx, QrisStatus.parse(status), baseAmount ?: BigDecimal.ZERO,
            invoiceNumber, rrn, approvalCode, issuerName, customerName, customerPan, paidAtRaw = paidAt)
    }
}

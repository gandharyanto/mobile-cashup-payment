package com.cashup.cdcp

import java.math.BigDecimal

/** Wire contract of the reference app's POST /v1/cdcp/sales. */
data class CardPayloadRequest(
    val keySetVersion: Int,
    val track2Enc: String,
    val track2Hash: String? = null,
    val track2Len: Int? = null,
    val trackKsnIndex: String? = null,
    val pinblockEnc: String? = null,
    val pinKsnIndex: String? = null,
    /** Nominal dan tip dikirim apa adanya (plain), bukan DUKPT: backend mengambilnya langsung dari body. */
    val baseAmount: BigDecimal,
    val tipAmount: BigDecimal? = null,
    val emvReqEnc: String? = null,
    val emvReqLen: Int? = null,
    val emvKsnIndex: String? = null,
    val panSequenceNumber: String? = null,
)

data class SaleRequest(
    val entryMode: String,
    val card: CardPayloadRequest,
    val posReference: String? = null,
    val tenor: Int? = null,
)

data class SaleResponse(
    val transactionId: String,
    val status: String,
    val invoiceNumber: String? = null,
    val approvalCode: String? = null,
    val responseCode: String? = null,
    val maskedPan: String? = null,
    val referenceNumber: String? = null,
)

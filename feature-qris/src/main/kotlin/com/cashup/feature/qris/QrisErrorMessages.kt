package com.cashup.feature.qris

import androidx.annotation.StringRes

/** Keputusan selalu pada `error.code`, tidak pernah pada `message` (dokumen API). */
object QrisErrorMessages {
    @StringRes
    fun forCode(code: String?): Int = when (code) {
        "NETWORK" -> R.string.qris_error_network
        "AMOUNT_OUT_OF_RANGE" -> R.string.qris_error_amount
        "DEVICE_NOT_ACTIVE", "MERCHANT_SUSPENDED", "MERCHANT_CHANNEL_INACTIVE" -> R.string.qris_error_unavailable
        "QRIS_HOST_NOT_ACTIVE", "QRIS_ACCOUNT_NOT_FOUND", "QRIS_HOST_AMBIGUOUS" -> R.string.qris_error_host
        "ACQUIRER_REJECTED" -> R.string.qris_error_rejected
        "ACQUIRER_UNAVAILABLE" -> R.string.qris_error_acquirer_down
        "TIMESTAMP_REQUIRED", "TIMESTAMP_INVALID", "TIMESTAMP_OUT_OF_RANGE" -> R.string.qris_error_clock
        "DEVICE_SIGNATURE_HEADER_MISSING", "DEVICE_SIGNATURE_INVALID", "DEVICE_SIGNATURE_REPLAYED",
        "DEVICE_CREDENTIAL_NOT_FOUND" -> R.string.qris_error_auth
        else -> R.string.qris_error_generic
    }
}

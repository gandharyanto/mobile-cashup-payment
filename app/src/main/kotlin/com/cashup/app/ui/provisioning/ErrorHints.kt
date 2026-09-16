package com.cashup.app.ui.provisioning

import androidx.annotation.StringRes
import com.cashup.app.R

/**
 * Memetakan kode kegagalan ke pesan operator.
 *
 * Mengembalikan `0` untuk kode yang tidak dikenal, dan pemanggil menampilkan
 * `message` dari backend apa adanya. Daftar kode definitif belum ada (spec §8
 * item 8), jadi memetakan yang tak dikenal ke pesan generik justru akan
 * menyembunyikan keterangan yang paling berguna saat itu.
 *
 * Selalu dipetakan dari `code`, tidak pernah dari `message` — `message`
 * ditujukan untuk manusia dan boleh berubah kapan saja.
 */
@StringRes
fun hintFor(code: String): Int = when (code) {
    "PROVISIONING_TOKEN_INVALID" -> R.string.err_token_invalid
    "TERMINAL_KEY_ALREADY_PROVISIONED" -> R.string.err_already_provisioned
    "TERMINAL_NOT_REGISTERED", "TERMINAL_INACTIVE" -> R.string.err_not_registered
    "DEVICE_UNKNOWN" -> R.string.err_device_unknown
    "PACKAGE_INVALID" -> R.string.err_package_invalid
    "KEY_INSTALL_FAILED" -> R.string.err_key_install_failed
    else -> 0
}

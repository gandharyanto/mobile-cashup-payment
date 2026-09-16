package com.cashup.app.ui.provisioning

import com.cashup.devicesdk.KeyInstallOutcome

/**
 * Sealed interface, bukan satu data class dengan `isLoading`/`error`/`data`
 * nullable sekaligus — dengan begitu kombinasi mustahil seperti "sedang memuat
 * sekaligus gagal" tidak bisa dibentuk sama sekali.
 *
 * `journalText` **sementara**, dicabut bersama package `audit/` sebelum
 * produksi (Task 10).
 */
sealed interface ProvisioningUiState {

    data object Idle : ProvisioningUiState

    data object Scanning : ProvisioningUiState

    data object Processing : ProvisioningUiState

    data class Success(
        val serialNumber: String,
        val orderId: String,
        val installed: List<KeyInstallOutcome>,
        val journalText: String,
    ) : ProvisioningUiState

    data class Failure(
        val code: String,
        val message: String,
        val journalText: String,
    ) : ProvisioningUiState
}

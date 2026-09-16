package com.cashup.app.ui.provisioning

import com.cashup.devicesdk.KeyInstallOutcome
import com.cashup.provisioning.audit.ProvisioningStep

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

    /**
     * [step] membawa langkah ceremony yang sedang berjalan.
     * `ProvisionDeviceUseCase` sudah melaporkannya sejak awal; sebelum ini
     * laporan itu tidak tersambung ke mana pun dan layar Processing hanya
     * menampilkan spinner selama seluruh ceremony — termasuk sepanjang
     * `DOWNLOAD_PACKAGE`, yang menunggu dua HSM dan paling mungkin terlihat
     * seperti aplikasi yang menggantung.
     */
    data class Processing(val step: ProvisioningStep) : ProvisioningUiState

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

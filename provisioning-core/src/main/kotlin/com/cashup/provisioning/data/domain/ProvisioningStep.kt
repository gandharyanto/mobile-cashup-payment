package com.cashup.provisioning.domain

/**
 * Langkah-langkah ceremony provisioning, dipakai [ProvisionDeviceUseCase] untuk
 * melaporkan progres (lewat `onStep`, ke UI) dan oleh jurnal sementara
 * (`com.cashup.provisioning.audit.ProvisioningJournal`) untuk mencatat evidence.
 *
 * Sengaja tinggal di `domain`, BUKAN di package `audit` — `audit/` itu
 * scaffolding sementara yang dicabut sebelum produksi (lihat plan Task 10),
 * sementara enum ini durable: dikonsumsi `app` (`ProvisioningUiState.Processing`)
 * untuk menampilkan progres ke teknisi, sesuatu yang tetap dibutuhkan setelah
 * `audit/` dihapus.
 */
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

package com.cashup.provisioning.domain

import com.cashup.devicesdk.KeyInstallOutcome

/**
 * `journalText` di kedua cabang adalah **sementara** — bagian dari alat bantu
 * pelaporan tahap awal, dijadwalkan dihapus bersama package `audit/` sebelum
 * produksi. Lihat checklist di Task 10.
 */
sealed interface ProvisioningOutcome {

    data class Success(
        val serialNumber: String,
        val orderId: String,
        val installed: List<KeyInstallOutcome>,
        val journalText: String,
    ) : ProvisioningOutcome

    /**
     * [code] adalah `error.code` dari backend kalau kegagalannya datang dari
     * sana, atau kode lokal (`DEVICE_UNKNOWN`, `PACKAGE_INVALID`,
     * `KEY_INSTALL_FAILED`) kalau bukan. Selalu bercabang pada [code], tidak
     * pernah pada [message].
     */
    data class Failure(
        val code: String,
        val message: String,
        val journalText: String,
    ) : ProvisioningOutcome
}
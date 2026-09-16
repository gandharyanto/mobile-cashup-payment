package com.cashup.provisioning.domain

import com.cashup.common.network.ApiResult
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.ActivateResponse
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.cashup.provisioning.data.remote.QrRedeemRequest
import com.cashup.provisioning.data.remote.QrRedeemResponse

/**
 * Tiga panggilan ceremony, tanpa menyebut Retrofit.
 *
 * Ada supaya [ProvisionDeviceUseCase] — termasuk seluruh jalur rollback-nya —
 * bisa diuji di JVM dengan implementasi palsu. Rollback adalah jalur yang paling
 * jarang dijalankan sekaligus paling mahal kalau salah, jadi ia harus bisa diuji
 * tanpa hardware.
 */
interface ProvisioningGateway {
    suspend fun redeem(request: QrRedeemRequest): ApiResult<QrRedeemResponse>
    suspend fun downloadKeyPackage(request: KeyPackageRequest): ApiResult<KeyPackageResponse>
    suspend fun activate(orderId: String, request: ActivateRequest): ApiResult<ActivateResponse>
}
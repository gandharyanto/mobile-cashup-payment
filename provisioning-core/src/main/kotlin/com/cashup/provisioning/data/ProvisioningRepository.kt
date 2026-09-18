package com.cashup.provisioning.data

import com.cashup.common.network.ApiResult
import com.cashup.common.network.safeEnvelopeCall
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.ActivateResponse
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.cashup.provisioning.data.remote.ProvisioningApi
import com.cashup.provisioning.data.remote.QrRedeemRequest
import com.cashup.provisioning.data.remote.QrRedeemResponse
import com.cashup.provisioning.domain.ProvisioningGateway
import com.google.gson.Gson

/**
 * Tiga panggilan ceremony provisioning, dipetakan ke [ApiResult].
 *
 * Dua client dipisah dengan sengaja: [unsigned] hanya untuk `qr-redeem`, yang
 * tidak boleh ditandatangani karena backend belum mengenal public key device
 * pada titik itu. Salah pakai di sini akan tampak sebagai penolakan tanda
 * tangan dari backend, bukan sebagai bug lokal — karena itu pemilihannya
 * dikunci di kelas ini, bukan diserahkan ke pemanggil.
 */
class ProvisioningRepository internal constructor(
    private val unsigned: ProvisioningApi,
    private val signed: ProvisioningApi,
    private val gson: Gson = Gson(),
) : ProvisioningGateway {

    override suspend fun redeem(request: QrRedeemRequest): ApiResult<QrRedeemResponse> =
        safeEnvelopeCall(gson) { unsigned.redeem(request) }

    override suspend fun downloadKeyPackage(request: KeyPackageRequest): ApiResult<KeyPackageResponse> =
        safeEnvelopeCall(gson) { signed.keyPackage(request.orderId, request.activationToken) }

    override suspend fun activate(orderId: String, request: ActivateRequest): ApiResult<ActivateResponse> =
        safeEnvelopeCall(gson) { signed.activate(orderId, request) }
}

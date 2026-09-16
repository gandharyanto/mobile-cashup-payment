package com.cashup.provisioning.data.remote

import com.cashup.common.network.ApiEnvelope
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Setiap method mengembalikan `Response<ApiEnvelope<T>>`, bukan `T` polos.
 * Retrofit tidak mem-parsing body pada respons gagal kalau tipe return-nya
 * bukan `Response<T>`, dan justru di sanalah `error.code` backend berada —
 * lihat `safeEnvelopeCall`.
 */
internal interface ProvisioningApi {

    @POST("v1/terminal-key-provisioning/qr-redeem")
    suspend fun redeem(
        @Body body: QrRedeemRequest,
    ): Response<ApiEnvelope<QrRedeemResponse>>

    @POST("v1/terminal-key-provisioning/orders/{orderId}/package")
    suspend fun keyPackage(
        @Path("orderId") orderId: String,
        @Body body: KeyPackageRequest,
    ): Response<ApiEnvelope<KeyPackageResponse>>

    @POST("v1/terminal-key-provisioning/orders/{orderId}/activate")
    suspend fun activate(
        @Path("orderId") orderId: String,
        @Body body: ActivateRequest,
    ): Response<ApiEnvelope<ActivateResponse>>
}
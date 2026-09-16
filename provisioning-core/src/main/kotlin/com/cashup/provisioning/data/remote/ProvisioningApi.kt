package com.cashup.provisioning.data.remote

import com.cashup.common.network.ApiEnvelope
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Setiap method mengembalikan `Response<ApiEnvelope<T>>`, bukan `T` polos.
 * Retrofit tidak mem-parsing body pada respons gagal kalau tipe return-nya
 * bukan `Response<T>`, dan justru di sanalah `error.code` backend berada —
 * lihat `safeEnvelopeCall`.
 *
 * `/package` sekarang `GET` + header `X-Activation-Token`, BUKAN `POST` +
 * body — kontrak `edc-mobile`, spec 17 September §1.
 */
internal interface ProvisioningApi {

    @POST("v1/terminal-key-provisioning/qr-redeem")
    suspend fun redeem(
        @Body body: QrRedeemRequest,
    ): Response<ApiEnvelope<QrRedeemResponse>>

    @GET("v1/terminal-key-provisioning/orders/{orderId}/package")
    suspend fun keyPackage(
        @Path("orderId") orderId: String,
        @Header("X-Activation-Token") activationToken: String,
    ): Response<ApiEnvelope<KeyPackageResponse>>

    @POST("v1/terminal-key-provisioning/orders/{orderId}/activate")
    suspend fun activate(
        @Path("orderId") orderId: String,
        @Body body: ActivateRequest,
    ): Response<ApiEnvelope<ActivateResponse>>
}

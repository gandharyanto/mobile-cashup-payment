package com.cashup.cdcp

import com.cashup.common.network.ApiEnvelope
import com.cashup.common.network.ApiResult
import com.cashup.common.network.RequestHeadersInterceptor
import com.cashup.common.network.safeEnvelopeCall
import com.cashup.devicesdk.DukptKeyProvider
import com.cashup.signing.DeviceSigner
import com.cashup.signing.SigningInterceptor
import com.google.gson.Gson
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

internal interface SaleApi {
    @POST("v1/cdcp/sales")
    suspend fun sale(@Header("Idempotency-Key") idempotencyKey: String,
                     @Body body: SaleRequest): Response<ApiEnvelope<SaleResponse>>
}

class SaleRepository private constructor(
    private val api: SaleApi,
    private val payloads: CardPayloadFactory,
    private val gson: Gson,
) {
    private var pending: Pair<String, SaleRequest>? = null

    suspend fun sale(deviceId: String, keySetVersion: Int, card: CardDefinition,
                     amount: BigDecimal, tipAmount: BigDecimal = BigDecimal.ZERO,
                     pin: String? = null, idempotencyKey: String): ApiResult<SaleResponse> {
        require(idempotencyKey.isNotBlank()) { "Idempotency key wajib per aksi pembayaran" }
        val request = if (pending?.first == idempotencyKey) pending!!.second else
            SaleRequest("051", payloads.build(deviceId, keySetVersion, card, amount, tipAmount, pin))
                .also { pending = idempotencyKey to it }
        val result = safeEnvelopeCall(gson) { api.sale(idempotencyKey, request) }
        if (result is ApiResult.Success) pending = null
        return result
    }

    companion object {
        fun create(baseUrl: String, signer: DeviceSigner, keys: DukptKeyProvider): SaleRepository {
            val gson = Gson()
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(65, TimeUnit.SECONDS)
                .writeTimeout(65, TimeUnit.SECONDS)
                .addInterceptor(RequestHeadersInterceptor())
                .addInterceptor(SigningInterceptor(signer))
                .build()
            val api = Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build().create(SaleApi::class.java)
            return SaleRepository(api, CardPayloadFactory(keys), gson)
        }

        fun newIdempotencyKey(): String = UUID.randomUUID().toString()
    }
}

package com.cashup.qris

import com.cashup.common.network.ApiEnvelope
import com.cashup.common.network.ApiError
import com.cashup.common.network.ApiResult
import com.cashup.common.network.RequestHeadersInterceptor
import com.cashup.common.network.safeEnvelopeCall
import com.cashup.signing.DeviceSigner
import com.cashup.signing.SigningInterceptor
import com.google.gson.Gson
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

internal interface QrisApi {
    @GET("v1/qris/pay-apps")
    suspend fun payApps(): Response<ApiEnvelope<List<PayAppDto>>>

    @POST("v1/qris/qr-codes")
    suspend fun generate(@Header("Idempotency-Key") idempotencyKey: String,
                         @Body body: GenerateQrRequest): Response<ApiEnvelope<QrCodeDto>>

    @POST("v1/qris/inquiries")
    suspend fun inquiry(@Header("Idempotency-Key") idempotencyKey: String,
                        @Body body: InquiryRequest): Response<ApiEnvelope<InquiryDto>>
}

class QrisRepository private constructor(
    private val api: QrisApi,
    private val gson: Gson,
) : QrisGateway {
    private var pendingGenerate: Pair<String, GenerateQrRequest>? = null

    override suspend fun payApps(): ApiResult<List<PayApp>> =
        safeEnvelopeCall(gson) { api.payApps() }.convert { list -> list.mapNotNull { it.toDomain() } }

    override suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr> {
        require(attemptId.isNotBlank()) { "attemptId wajib per aksi pembayaran" }
        // Request dibekukan per attemptId supaya retry mengirim key + body yang persis sama.
        val request = pendingGenerate?.takeIf { it.first == attemptId }?.second
            ?: GenerateQrRequest(qrisHostId, amount, "APP-$attemptId").also { pendingGenerate = attemptId to it }
        val result = safeEnvelopeCall(gson) { api.generate(attemptId, request) }.convert { it.toDomain() }
        if (result is ApiResult.Success) pendingGenerate = null
        return result
    }

    override suspend fun inquiry(transactionId: String): ApiResult<QrisPaymentResult> =
        safeEnvelopeCall(gson) { api.inquiry(UUID.randomUUID().toString(), InquiryRequest(transactionId)) }
            .convert { it.toDomain() }

    private inline fun <D, R : Any> ApiResult<D>.convert(transform: (D) -> R?): ApiResult<R> = when (this) {
        is ApiResult.Failure -> this
        is ApiResult.Success -> transform(data)?.let { ApiResult.Success(it) }
            ?: ApiResult.Failure(ApiError("RESPONSE_UNEXPECTED", "Respons QRIS tidak lengkap"))
    }

    companion object {
        fun create(baseUrl: String, signer: DeviceSigner, debugLogging: Boolean = false): QrisRepository {
            val gson = Gson()
            val clientBuilder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(RequestHeadersInterceptor())
                .addInterceptor(SigningInterceptor(signer))
            if (debugLogging) {
                clientBuilder.addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                    redactHeader("X-Signature")
                })
            }
            val api = Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
                .client(clientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build().create(QrisApi::class.java)
            return QrisRepository(api, gson)
        }
    }
}

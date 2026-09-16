package com.cashup.common.network

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import retrofit2.Response
import java.io.IOException

/**
 * Versi [safeApiCall] yang sadar amplop backend.
 *
 * Perbedaan yang menentukan: amplop di-parse untuk respons 2xx **maupun**
 * non-2xx. Backend mengirim `error.code` yang berguna justru pada 401/403/409/
 * 410/422/503, dan Retrofit tidak mem-parsing body pada respons gagal — jadi
 * `errorBody` harus dibaca manual di sini. Tanpa itu, kode error backend hilang
 * dan tergantikan kode sintetis seperti `HTTP_410`, yang tidak bisa dipetakan
 * ke pesan operator.
 *
 * Amplop error yang datang dengan status 2xx juga diperlakukan sebagai
 * kegagalan: `data` dan `error` saling meniadakan, dan kepercayaan pada status
 * HTTP saja pernah jadi sumber bug di app lama.
 */
suspend fun <T> safeEnvelopeCall(
    gson: Gson = Gson(),
    block: suspend () -> Response<ApiEnvelope<T>>,
): ApiResult<T> = try {
    val response = block()
    val status = response.code()

    if (response.isSuccessful) {
        val envelope = response.body()
        val error = envelope?.error
        val data = envelope?.data
        when {
            error != null -> ApiResult.Failure(ApiError(error.code, error.message, status))
            data != null -> ApiResult.Success(data)
            else -> ApiResult.Failure(
                ApiError("RESPONSE_UNEXPECTED", "Respons tanpa data maupun error", status)
            )
        }
    } else {
        val raw = response.errorBody()?.string()
        val parsed = raw?.let {
            try {
                gson.fromJson(it, ApiEnvelope::class.java)?.error
            } catch (_: JsonSyntaxException) {
                null
            }
        }
        if (parsed != null) {
            ApiResult.Failure(ApiError(parsed.code, parsed.message, status))
        } else {
            ApiResult.Failure(
                ApiError(
                    code = "RESPONSE_UNREADABLE",
                    message = "Respons bukan JSON (HTTP $status): ${raw?.take(300) ?: "<body kosong>"}",
                    httpStatus = status,
                )
            )
        }
    }
} catch (e: IOException) {
    ApiResult.Failure(ApiError("NETWORK", e.message ?: "Network error", cause = e))
} catch (e: Exception) {
    ApiResult.Failure(ApiError("UNKNOWN", e.message ?: "Unknown error", cause = e))
}
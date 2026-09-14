package com.cashup.common.network

import retrofit2.Response
import java.io.IOException

/**
 * Shared HTTP-call-to-[ApiResult] mapper. Spec §11 names `common-core` as the
 * home for logic shared across future domain modules (CDCP, QRIS, etc.) —
 * this is that mapping, so it is written once here instead of being
 * duplicated per module.
 */
suspend fun <T> safeApiCall(block: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = block()
        val body = response.body()
        if (response.isSuccessful && body != null) {
            ApiResult.Success(body)
        } else {
            ApiResult.Failure(
                ApiError(
                    code = "HTTP_${response.code()}",
                    message = response.errorBody()?.string() ?: response.message(),
                    httpStatus = response.code(),
                )
            )
        }
    } catch (e: IOException) {
        ApiResult.Failure(ApiError(code = "NETWORK", message = e.message ?: "Network error", cause = e))
    } catch (e: Exception) {
        ApiResult.Failure(ApiError(code = "UNKNOWN", message = e.message ?: "Unknown error", cause = e))
    }
}

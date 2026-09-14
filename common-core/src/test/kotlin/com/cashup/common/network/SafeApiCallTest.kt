package com.cashup.common.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs a suspend block synchronously. `safeApiCall`'s block never truly
 * suspends in these tests (it returns/throws immediately), so the
 * continuation always resumes before this function returns — no coroutine
 * dispatcher/library dependency required for these tests.
 */
private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return outcome!!.getOrThrow()
}

class SafeApiCallTest {

    @Test
    fun `successful response with body maps to Success`() {
        val result = runSuspend { safeApiCall { Response.success("payload") } }

        assertEquals(ApiResult.Success("payload"), result)
    }

    @Test
    fun `successful HTTP code with null body maps to Failure with HTTP code`() {
        val result = runSuspend { safeApiCall { Response.success<String>(null) } }

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertEquals("HTTP_200", error.code)
        assertEquals(200, error.httpStatus)
    }

    @Test
    fun `non-2xx HTTP response maps to Failure with the right code`() {
        val errorBody = """{"message":"not found"}""".toResponseBody("application/json".toMediaType())
        val result = runSuspend { safeApiCall { Response.error<String>(404, errorBody) } }

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertEquals("HTTP_404", error.code)
        assertEquals(404, error.httpStatus)
    }

    @Test
    fun `IOException thrown by the block maps to Failure with NETWORK code`() {
        val result = runSuspend {
            safeApiCall<String> { throw IOException("connection reset") }
        }

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertEquals("NETWORK", error.code)
        assertEquals("connection reset", error.message)
    }
}

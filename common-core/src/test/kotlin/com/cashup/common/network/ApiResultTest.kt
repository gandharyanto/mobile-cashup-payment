package com.cashup.common.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApiResultTest {

    @Test
    fun `map transforms success data`() {
        val result: ApiResult<Int> = ApiResult.Success(2)
        val mapped = result.map { it * 10 }
        assertEquals(ApiResult.Success(20), mapped)
    }

    @Test
    fun `map leaves failure untouched`() {
        val error = ApiError(code = "NETWORK", message = "timeout")
        val result: ApiResult<Int> = ApiResult.Failure(error)
        val mapped = result.map { it * 10 }
        assertEquals(ApiResult.Failure(error), mapped)
    }

    @Test
    fun `onSuccess runs action only for success`() {
        var captured = -1
        val result: ApiResult<Int> = ApiResult.Success(7)
        result.onSuccess { captured = it }
        assertEquals(7, captured)
    }

    @Test
    fun `onFailure runs action only for failure`() {
        var captured: ApiError? = null
        val error = ApiError(code = "AUTH", message = "signature invalid")
        val result: ApiResult<Int> = ApiResult.Failure(error)
        result.onFailure { captured = it }
        assertEquals(error, captured)
    }
}

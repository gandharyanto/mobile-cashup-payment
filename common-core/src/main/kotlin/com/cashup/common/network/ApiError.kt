package com.cashup.common.network

data class ApiError(
    val code: String,
    val message: String,
    val httpStatus: Int? = null,
    val cause: Throwable? = null,
)

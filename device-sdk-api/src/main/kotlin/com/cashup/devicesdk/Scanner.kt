package com.cashup.devicesdk

interface Scanner {
    suspend fun scanQr(timeoutMillis: Long): String?
}

package com.cashup.devicesdk.fake

import com.cashup.devicesdk.SerialNumberProvider

class FakeSerialNumberProvider(private val value: String?) : SerialNumberProvider {
    override suspend fun serialNumber(): String? = value
}
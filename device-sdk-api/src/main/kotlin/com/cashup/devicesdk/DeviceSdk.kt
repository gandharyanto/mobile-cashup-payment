package com.cashup.devicesdk

interface DeviceSdk {
    val vendorId: String
    val capabilities: Set<Capability>
    val cardReader: CardReader?
    val printer: Printer?
    val scanner: Scanner?

    fun supports(capability: Capability): Boolean = capability in capabilities
}

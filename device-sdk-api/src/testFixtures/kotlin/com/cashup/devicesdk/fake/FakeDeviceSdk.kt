package com.cashup.devicesdk.fake

import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner

class FakeDeviceSdk(
    override val vendorId: String = "fake",
    override val capabilities: Set<Capability> = setOf(Capability.CARD_READ, Capability.PRINT, Capability.SCAN_QR),
    override val cardReader: CardReader? = FakeCardReader(),
    override val printer: Printer? = FakePrinter(),
    override val scanner: Scanner? = FakeScanner(),
) : DeviceSdk

package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner
import com.lib.core.SDKManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object EdcSdkConnector {
    private val probing = AtomicBoolean(false)

    suspend fun tryConnect(context: Context): DeviceSdk? {
        if (!probing.compareAndSet(false, true)) return null
        return suspendCancellableCoroutine { continuation ->
        val appContext = context.applicationContext
        continuation.invokeOnCancellation { probing.set(false) }
        SDKManager.autoDetectDevice(appContext) { connected ->
            probing.set(false)
            if (continuation.isActive) {
                continuation.resume(if (connected) EdcSdkDeviceSdk(appContext) else null)
            }
        }
        }
    }
}

internal class EdcSdkDeviceSdk(context: Context) : DeviceSdk {
    override val vendorId: String = "edcsdk"
    override val capabilities: Set<Capability> = setOf(Capability.CARD_READ, Capability.SCAN_QR)
    override val cardReader: EdcSdkCardReader = EdcSdkCardReader(context)
    override val printer: Printer? = null
    override val scanner: Scanner = EdcSdkScanner(context)
}

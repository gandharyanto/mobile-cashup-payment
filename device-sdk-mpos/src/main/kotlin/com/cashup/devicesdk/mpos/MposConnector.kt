package com.cashup.devicesdk.mpos

import android.bluetooth.BluetoothManager
import android.content.Context
import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner
import com.cz.anfubridge.AnfuBridge
import com.lib.device.channel.mpos.BrandRegistry
import com.lib.device.channel.mpos.MposReaderDevice
import com.lib.device.newland.mpos.NewlandBlueHelper
import com.lib.device.topwise.mpos.TopwiseBlueHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Terhubung" di sini berarti device ini SANGGUP mPOS (adapter Bluetooth ada),
 * bukan bahwa reader fisik sudah terpasang -- itu terjadi belakangan lewat
 * [com.cashup.devicesdk.PairableCardReader] setelah UI memilih device (spec
 * §5). Tidak pernah meminta izin runtime di sini.
 */
object MposConnector {
    private val registered = AtomicBoolean(false)

    fun tryConnect(context: Context): DeviceSdk? {
        val appContext = context.applicationContext
        if (!hasBluetoothAdapter(appContext)) return null
        registerBrandsOnce(appContext)
        return MposDeviceSdk(appContext)
    }

    private fun registerBrandsOnce(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        BrandRegistry.register(MposReaderDevice.NEWLAND, provider = { NewlandBlueHelper() }, replace = true)
        BrandRegistry.register(MposReaderDevice.TOPWISE, provider = { TopwiseBlueHelper() }, replace = true)
        // ANFU didaftarkan oleh anfu-bridge (juga menyiapkan Prefs vendor); tidak menyentuh tipe vendor ANFU.
        AnfuBridge.register(context)
    }

    private fun hasBluetoothAdapter(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter != null
    }
}

internal class MposDeviceSdk(context: Context) : DeviceSdk {
    override val vendorId: String = "mpos"
    override val capabilities: Set<Capability> = setOf(Capability.CARD_READ)
    override val cardReader: MposCardReader = MposCardReader(context)
    override val printer: Printer? = null
    override val scanner: Scanner? = null
}

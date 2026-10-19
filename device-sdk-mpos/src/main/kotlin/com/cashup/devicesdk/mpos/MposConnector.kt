package com.cashup.devicesdk.mpos

import android.bluetooth.BluetoothManager
import android.content.Context
import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner
import com.lib.core.pref.PREFERENCE_NAME
import com.lib.core.pref.Prefs
import com.lib.device.anfu.mpos.AnfuBlueHelper
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
        initVendorPrefs(appContext)
        registerBrandsOnce()
        return MposDeviceSdk(appContext)
    }

    private fun registerBrandsOnce() {
        if (!registered.compareAndSet(false, true)) return
        BrandRegistry.register(MposReaderDevice.NEWLAND, provider = { NewlandBlueHelper() }, replace = true)
        BrandRegistry.register(MposReaderDevice.TOPWISE, provider = { TopwiseBlueHelper() }, replace = true)
        BrandRegistry.register(MposReaderDevice.ANFU, provider = { AnfuBlueHelper() }, replace = true)
    }

    /** Core vendor menyimpan "device terakhir" lewat [Prefs] (lateinit); tanpa ini connect ANFU gagal setelah terhubung. */
    private fun initVendorPrefs(context: Context) {
        if (runCatching { Prefs }.isFailure) {
            Prefs = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
        }
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

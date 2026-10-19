package com.cashup.reader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Izin runtime yang dibutuhkan untuk menyambungkan reader mPOS lewat Bluetooth. Dipakai splash dan layar pembayaran
 * supaya daftar izinnya tidak ditulis dua kali.
 */
object BluetoothPermissions {

    /**
     * "Nearby devices" di Android 12+ = BLUETOOTH_SCAN + BLUETOOTH_CONNECT; Android 11 ke bawah butuh izin lokasi agar
     * scan Bluetooth menemukan reader.
     */
    fun required(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Izin yang belum diberikan; kosong berarti reader boleh disambungkan. */
    fun missing(context: Context): Array<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()
}

package com.cashup.devicesdk.edcsdk

import android.content.Context
import android.os.Build
import com.lib.core.DeviceManager
import com.lib.core.KeyManager
import java.io.File

/**
 * Batas tipis di atas `KeyManager` milik `edc-sdk`, ada semata supaya logika
 * perutean slot di [EdcSdkTerminalKeyInstaller] bisa diuji tanpa hardware.
 *
 * Dua metode tulis di sini memang berbeda tujuan, bukan duplikat:
 *
 * - [writeToVendorModule] memakai `KeyManager.writeIPEK(ipek, ksn)` — menulis
 *   ke modul aman vendor lalu mencerminkannya ke vault, dan hanya mengembalikan
 *   true kalau modul vendor mengonfirmasi. Ini jalur yang benar, tapi vendor
 *   hanya punya SATU slot DUKPT (`keyIndex` tetap per vendor).
 * - [writeToVaultSlot] memakai `KeyManager.writeIPEK(slot, ipek, ksn)` — sudah
 *   diperiksa di source `edc-sdk`: varian ini menulis HANYA ke vault dan
 *   melewati modul vendor sepenuhnya.
 *
 * Lihat spec §4.3.
 */
internal interface KeyManagerGateway {
    fun hasVendorModule(): Boolean
    fun writeToVendorModule(ipek: ByteArray, ksn: ByteArray): Boolean
    fun writeToVaultSlot(slot: Int, ipek: ByteArray, ksn: ByteArray): Boolean
    fun clearAll()
}

internal class RealKeyManagerGateway(context: Context) : KeyManagerGateway {

    private val appContext = context.applicationContext
    private val keyManager by lazy { KeyManager.getInstance(appContext) }

    override fun hasVendorModule(): Boolean = DeviceManager.systemKey != null

    override fun writeToVendorModule(ipek: ByteArray, ksn: ByteArray): Boolean =
        keyManager.writeIPEK(ipek, ksn)

    override fun writeToVaultSlot(slot: Int, ipek: ByteArray, ksn: ByteArray): Boolean =
        keyManager.writeIPEK(slot, ipek, ksn)

    /**
     * `DuktpVaultCompat` tidak mengekspos API hapus apa pun — hanya store/load
     * (dikonfirmasi dari isi `core-release_1.0.63.aar`). Rollback atomic
     * menuntutnya, jadi sementara ini menghapus berkas SharedPreferences-nya
     * langsung berdasarkan nama.
     *
     * Ini kopling rapuh ke detail internal `edc-sdk` dan dicatat sebagai utang
     * di spec §4.3: begitu `edc-sdk` menyediakan `clear()` resmi, ganti ke sana.
     */
    override fun clearAll() {
        VAULT_PREFS.forEach { deletePrefs(it) }
    }

    private fun deletePrefs(name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.deleteSharedPreferences(name)
        } else {
            // deleteSharedPreferences baru ada di API 24; minSdk kita 23.
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            File(appContext.applicationInfo.dataDir, "shared_prefs/$name.xml").delete()
        }
    }

    private companion object {
        val VAULT_PREFS = listOf("duktp.vault", "KeyManager")
    }
}
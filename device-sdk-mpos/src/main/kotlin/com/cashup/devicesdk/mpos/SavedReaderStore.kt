package com.cashup.devicesdk.mpos

import android.content.Context

/** Reader ANFU yang pertama kali berhasil terhubung; app menghubungkan ke reader ini terus. */
internal data class SavedReader(val id: String, val name: String)

internal interface SavedReaderStore {
    fun get(): SavedReader?
    fun save(reader: SavedReader)
}

/**
 * Hanya MAC address dan nama reader -- bukan rahasia, jadi SharedPreferences biasa.
 * Satu-satunya cara mengganti reader adalah menghapus data app.
 */
internal class SharedPrefsSavedReaderStore(context: Context) : SavedReaderStore {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    override fun get(): SavedReader? {
        val id = prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return SavedReader(id, prefs.getString(KEY_NAME, null).orEmpty().ifBlank { id })
    }

    override fun save(reader: SavedReader) {
        prefs.edit().putString(KEY_ID, reader.id).putString(KEY_NAME, reader.name).apply()
    }

    private companion object {
        const val FILE = "cashup_mpos_reader"
        const val KEY_ID = "anfu_mac"
        const val KEY_NAME = "anfu_name"
    }
}

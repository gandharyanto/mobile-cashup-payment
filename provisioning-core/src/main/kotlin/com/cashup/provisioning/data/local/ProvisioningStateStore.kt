package com.cashup.provisioning.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * [backings] memetakan purpose ke nama [com.cashup.devicesdk.KeyBacking],
 * disimpan sebagai string supaya penambahan nilai enum kelak tidak membuat
 * state lama tidak terbaca.
 */
data class ProvisioningState(
    val serialNumber: String,
    val orderId: String,
    val backings: Map<String, String>,
)

class ProvisioningStateStore(context: Context) {

    private val prefs by lazy { SecurePrefs.open(context.applicationContext, PREFS) }
    private val gson = Gson()

    /**
     * Mengembalikan `null` juga kalau isi tersimpan tidak lagi cocok skema saat
     * ini — entri dibuang, bukan didiamkan, supaya pembacaan berikutnya tidak
     * mengulang error yang sama selamanya.
     */
    fun current(): ProvisioningState? {
        val raw = prefs.getString(KEY, null) ?: return null
        return try {
            gson.fromJson(raw, ProvisioningState::class.java)
        } catch (e: JsonSyntaxException) {
            prefs.edit().remove(KEY).commit()
            null
        }
    }

    fun serialNumber(): String? = current()?.serialNumber

    @Synchronized
    fun save(state: ProvisioningState) {
        prefs.edit().putString(KEY, gson.toJson(state)).commit()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private companion object {
        const val PREFS = "provisioning_state"
        const val KEY = "current"
    }
}
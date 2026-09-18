package com.cashup.provisioning.data.local

import android.content.Context
import com.cashup.provisioning.domain.ProvisioningStateRepository
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/** Persist segera setelah redeem sukses (spec §2 langkah 5) — sebelum DUKPT apa pun disentuh. */
data class IdentityState(
    val serialNumber: String,
    val deviceId: String,
    val credentialKeyVersion: Int,
    val certificateChain: List<String> = emptyList(),
    val devicePublicKey: String? = null,
)

/**
 * Persist terpisah dari [IdentityState], hanya setelah `/activate` sukses.
 * [backings] memetakan purpose ke nama [com.cashup.devicesdk.KeyBacking],
 * disimpan sebagai string supaya penambahan nilai enum kelak tidak membuat
 * state lama tidak terbaca.
 */
data class DukptState(
    val deviceId: String,
    val keySetId: Long,
    val keySetVersion: Int,
    val backings: Map<String, String>,
)

/**
 * Dua record independen, dua titik persist berbeda — spec §2 langkah 5 (identity)
 * vs langkah 6b-l (DUKPT). Device bisa punya identity TANPA DUKPT (baru redeem
 * pertama kali, gagal sebelum activate, atau refresh-identitas-saja lewat
 * `dukptProvisioningRequired=false`) — itu keadaan yang SAH, bukan setengah
 * jalan. Lihat spec §2.1.
 */
class ProvisioningStateStore(context: Context) : ProvisioningStateRepository {

    private val prefs by lazy { SecurePrefs.open(context.applicationContext, PREFS) }
    private val gson = Gson()

    override fun identity(): IdentityState? = read(KEY_IDENTITY, IdentityState::class.java, KEY_IDENTITY)

    @Synchronized
    override fun saveIdentity(state: IdentityState) {
        prefs.edit().putString(KEY_IDENTITY, gson.toJson(state)).commit()
    }

    @Synchronized
    override fun clearIdentity() {
        prefs.edit().remove(KEY_IDENTITY).commit()
    }

    override fun dukpt(): DukptState? = read(KEY_DUKPT, DukptState::class.java, KEY_DUKPT)

    @Synchronized
    override fun saveDukpt(state: DukptState) {
        prefs.edit().putString(KEY_DUKPT, gson.toJson(state)).commit()
    }

    @Synchronized
    override fun clearDukpt() {
        prefs.edit().remove(KEY_DUKPT).commit()
    }

    /**
     * Mengembalikan `null` juga kalau isi tersimpan tidak lagi cocok skema saat
     * ini — entri dibuang, bukan didiamkan, supaya pembacaan berikutnya tidak
     * mengulang error yang sama selamanya.
     */
    private fun <T> read(key: String, type: Class<T>, removeKeyOnCorrupt: String): T? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            gson.fromJson(raw, type)
        } catch (e: JsonSyntaxException) {
            prefs.edit().remove(removeKeyOnCorrupt).commit()
            null
        }
    }

    private companion object {
        const val PREFS = "provisioning_state"
        const val KEY_IDENTITY = "identity"
        const val KEY_DUKPT = "dukpt"
    }
}

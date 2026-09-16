package com.cashup.provisioning.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * `SharedPreferences` ter-enkripsi AES-256-GCM dengan master key yang dipegang
 * Android Keystore. Dipakai untuk apa pun yang tidak bisa masuk hardware:
 * blob private key Ed25519 dan, di jalur fallback, blob RSA.
 */
internal object SecurePrefs {
    fun open(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
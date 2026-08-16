package com.sendro.android.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences with a survivable fallback.
 *
 * Two things live behind this: the bearer tokens this device holds for hosts
 * it paired WITH ([TokenStore]), and the token verifiers for devices that
 * paired TO this one ([PeerStore]). Both are credentials, so both get
 * AES256-GCM values under an Android Keystore master key.
 *
 * FALLBACK: a small number of devices ship a broken Keystore, and a keyset
 * restored from a backup cannot be decrypted. Rather than making the app
 * unusable we wipe the file and fall back to plain preferences — never
 * silently, and never leaving unreadable ciphertext behind to be misread.
 */
object SecurePrefs {

    private const val TAG = "SendroSecurePrefs"

    data class Result(val prefs: SharedPreferences, val encrypted: Boolean)

    fun open(context: Context, name: String): Result {
        val appContext = context.applicationContext
        val encrypted = try {
            val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable for $name; wiping and using plain", e)
            appContext.deleteSharedPreferences(name)
            null
        }
        return if (encrypted != null) {
            Result(encrypted, true)
        } else {
            Result(appContext.getSharedPreferences(name + "_fallback", Context.MODE_PRIVATE), false)
        }
    }
}

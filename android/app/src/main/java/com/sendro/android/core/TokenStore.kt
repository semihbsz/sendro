package com.sendro.android.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-host bearer tokens (PROTOCOL.md §3) — the iOS Keychain analogue.
 *
 * Backed by EncryptedSharedPreferences: AES256-GCM values under a master key
 * held in the Android Keystore (hardware-backed on most devices). One entry
 * per paired host, keyed by the host's deviceId.
 *
 * A token is a bearer credential for exactly one PC on one LAN; it never
 * leaves the device except as an `Authorization: Bearer` header to that PC,
 * and it is excluded from backup/transfer (see xml/data_extraction_rules).
 *
 * FALLBACK: if the Keystore is unusable (a small number of devices ship a
 * broken one, and a restored-from-backup keyset cannot be decrypted) we fall
 * back to plain SharedPreferences rather than making the app unusable —
 * with the file wiped first, so a corrupt keyset can never be read as
 * ciphertext-as-plaintext. This is logged, never silent.
 */
class TokenStore(context: Context) {

    private val opened = SecurePrefs.open(context, FILE_ENCRYPTED)
    private val prefs: SharedPreferences = opened.prefs

    /** True when the tokens really are encrypted at rest. Shown in Settings. */
    val isEncrypted: Boolean = opened.encrypted

    fun token(hostId: String): String? = prefs.getString(hostId, null)

    fun save(hostId: String, token: String) {
        prefs.edit().putString(hostId, token).apply()
    }

    fun delete(hostId: String) {
        prefs.edit().remove(hostId).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_ENCRYPTED = "sendro_tokens_v1"
    }
}

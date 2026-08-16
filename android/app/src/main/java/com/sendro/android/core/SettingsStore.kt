package com.sendro.android.core

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.util.UUID

/** iOS parity: Settings.SaveMediaMode. */
enum class SaveMediaMode(val raw: String, val label: String) {
    ALWAYS("always", "Always"),
    ASK("ask", "Ask Every Time"),
    NEVER("never", "Never");

    companion object {
        fun from(raw: String?): SaveMediaMode =
            entries.firstOrNull { it.raw == raw } ?: ALWAYS
    }
}

/** Immutable snapshot the engine and the UI both read. */
data class SendroSettings(
    val deviceName: String,
    val clientDeviceId: String,
    val autoAcceptFromTrusted: Boolean,
    val saveMediaToGallery: SaveMediaMode,
    val addToSendroAlbum: Boolean,
    val deleteTempAfterSave: Boolean,
    val notifyTransfers: Boolean,
    val notifyMessages: Boolean,
    val autoCheckUpdates: Boolean,
    val dismissedUpdateVersion: String?,
    /**
     * §15.4 receiver host. ON by default on a TV (a TV only ever receives),
     * OFF by default on a phone (a phone is normally a client). Off means the
     * server is stopped and the mDNS advertisement withdrawn.
     */
    val receiveFromOtherDevices: Boolean,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sendro_settings")

/**
 * DataStore-backed settings, mirroring the iOS `Settings` object key for key.
 *
 * The device token is deliberately NOT here — it lives in [TokenStore]
 * (EncryptedSharedPreferences), the Keychain analogue.
 */
class SettingsStore(
    private val context: Context,
    scope: CoroutineScope,
    /**
     * The §15.4 default for this device. Passed in rather than detected here
     * so `core` stays free of UI concerns and so a test can pin it.
     */
    private val receiverDefault: Boolean = false,
) {

    private object Keys {
        val deviceName = stringPreferencesKey("deviceName")
        val clientDeviceId = stringPreferencesKey("clientDeviceId")
        val autoAcceptFromTrusted = booleanPreferencesKey("autoAcceptFromTrusted")
        val saveMediaToGallery = stringPreferencesKey("saveMediaToGallery")
        val addToSendroAlbum = booleanPreferencesKey("addToSendroAlbum")
        val deleteTempAfterSave = booleanPreferencesKey("deleteTempAfterSave")
        val notifyTransfers = booleanPreferencesKey("notifyTransfers")
        val notifyMessages = booleanPreferencesKey("notifyMessages")
        val autoCheckUpdates = booleanPreferencesKey("autoCheckUpdates")
        val dismissedUpdateVersion = stringPreferencesKey("dismissedUpdateVersion")
        val receiveFromOtherDevices = booleanPreferencesKey("receiveFromOtherDevices")
    }

    /** `Xiaomi 13T Pro` etc. — the closest Android has to UIDevice.name. */
    private val defaultDeviceName: String = buildString {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        when {
            model.startsWith(manufacturer, ignoreCase = true) -> append(model)
            manufacturer.isEmpty() -> append(model)
            else -> append(manufacturer.replaceFirstChar { it.uppercase() }).append(' ').append(model)
        }
    }.ifBlank { "Android phone" }

    private val defaults = SendroSettings(
        deviceName = defaultDeviceName,
        clientDeviceId = "",
        autoAcceptFromTrusted = false,
        saveMediaToGallery = SaveMediaMode.ALWAYS,
        addToSendroAlbum = true,
        deleteTempAfterSave = true,
        notifyTransfers = true,
        notifyMessages = true,
        autoCheckUpdates = true,
        dismissedUpdateVersion = null,
        receiveFromOtherDevices = receiverDefault,
    )

    val flow: Flow<SendroSettings> = context.settingsDataStore.data
        .catch { error ->
            // A corrupted preferences file must not brick the app.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            SendroSettings(
                deviceName = prefs[Keys.deviceName]?.takeIf { it.isNotBlank() } ?: defaultDeviceName,
                clientDeviceId = prefs[Keys.clientDeviceId].orEmpty(),
                autoAcceptFromTrusted = prefs[Keys.autoAcceptFromTrusted] ?: false,
                saveMediaToGallery = SaveMediaMode.from(prefs[Keys.saveMediaToGallery]),
                addToSendroAlbum = prefs[Keys.addToSendroAlbum] ?: true,
                deleteTempAfterSave = prefs[Keys.deleteTempAfterSave] ?: true,
                notifyTransfers = prefs[Keys.notifyTransfers] ?: true,
                notifyMessages = prefs[Keys.notifyMessages] ?: true,
                autoCheckUpdates = prefs[Keys.autoCheckUpdates] ?: true,
                dismissedUpdateVersion = prefs[Keys.dismissedUpdateVersion],
                // Absent means "never chosen", which resolves to the device
                // default — so a TV receives out of the box and a phone does
                // not, without either being a surprise after an upgrade.
                receiveFromOtherDevices = prefs[Keys.receiveFromOtherDevices] ?: receiverDefault,
            )
        }

    val state: StateFlow<SendroSettings> =
        flow.stateIn(scope, SharingStarted.Eagerly, defaults)

    /** Current values without suspending — used by engine code on any thread. */
    val current: SendroSettings get() = state.value

    /**
     * Stable client deviceId (UUID v4, lowercase), generated once and never
     * regenerated: the host keys the pairing on it.
     *
     * Backed by plain SharedPreferences rather than DataStore because the
     * engine needs it synchronously during a pairing request, before any flow
     * has had a chance to emit.
     */
    val clientDeviceId: String
        get() {
            val prefs = context.getSharedPreferences("sendro_identity", Context.MODE_PRIVATE)
            prefs.getString("clientDeviceId", null)?.let { return it }
            val fresh = UUID.randomUUID().toString().lowercase()
            prefs.edit().putString("clientDeviceId", fresh).apply()
            return fresh
        }

    suspend fun setDeviceName(value: String) = put { it[Keys.deviceName] = value.trim() }
    suspend fun setAutoAccept(value: Boolean) = put { it[Keys.autoAcceptFromTrusted] = value }
    suspend fun setSaveMedia(value: SaveMediaMode) = put { it[Keys.saveMediaToGallery] = value.raw }
    suspend fun setAddToAlbum(value: Boolean) = put { it[Keys.addToSendroAlbum] = value }
    suspend fun setDeleteTemp(value: Boolean) = put { it[Keys.deleteTempAfterSave] = value }
    suspend fun setNotifyTransfers(value: Boolean) = put { it[Keys.notifyTransfers] = value }
    suspend fun setNotifyMessages(value: Boolean) = put { it[Keys.notifyMessages] = value }
    suspend fun setAutoCheckUpdates(value: Boolean) = put { it[Keys.autoCheckUpdates] = value }
    suspend fun setReceiveFromOtherDevices(value: Boolean) =
        put { it[Keys.receiveFromOtherDevices] = value }
    suspend fun setDismissedUpdateVersion(value: String?) = put {
        if (value == null) it.remove(Keys.dismissedUpdateVersion)
        else it[Keys.dismissedUpdateVersion] = value
    }

    /**
     * `DataStore.edit` takes a `suspend (MutablePreferences) -> Unit`, and
     * Kotlin will not convert a plain function value into a suspend one — the
     * parameter has to be declared suspend here or this does not compile.
     */
    private suspend fun put(
        block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        context.settingsDataStore.edit(block)
    }
}

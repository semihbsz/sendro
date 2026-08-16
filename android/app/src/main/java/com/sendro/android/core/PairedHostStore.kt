package com.sendro.android.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * A Windows host this phone has paired with. The bearer token itself lives in
 * [TokenStore]; this keeps the metadata plus the last endpoint that worked, so
 * a manually-typed or QR-scanned PC is reachable again after a restart with no
 * discovery at all.
 */
@Serializable
data class PairedHost(
    val deviceId: String,
    val name: String,
    val lastHost: String,
    val lastPort: Int,
    val pairedAtMs: Long,
    /**
     * `windows` | `androidtv` | `android` | `ios`. Informational only (§15.1) —
     * it decides an icon and a label, never a capability.
     *
     * Defaulted so entries written before this field existed still decode.
     */
    val platform: String = "windows",
    /**
     * True once this peer has answered `404` to an outbox poll (§15.1): it is a
     * receiver, not a sender. Cached so the poll loop stops long-polling it and
     * the UI can say "receive-only" instead of showing it as broken.
     */
    val receiveOnly: Boolean = false,
)

/**
 * Persistent registry of paired hosts. Small and read on the UI thread, so
 * plain SharedPreferences + JSON rather than DataStore — the whole list is a
 * few hundred bytes and the engine needs it synchronously.
 */
class PairedHostStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sendro_paired_hosts", Context.MODE_PRIVATE)

    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<PairedHost>> = _hosts.asStateFlow()

    fun host(id: String?): PairedHost? =
        if (id == null) null else _hosts.value.firstOrNull { it.deviceId == id }

    fun add(host: PairedHost) {
        val next = (_hosts.value.filter { it.deviceId != host.deviceId } + host)
            .sortedBy { it.pairedAtMs }
        _hosts.value = next
        save(next)
    }

    fun remove(id: String) {
        val next = _hosts.value.filter { it.deviceId != id }
        _hosts.value = next
        save(next)
    }

    /**
     * Called when discovery (or a successful manual connect) finds a fresh
     * address for a host we already know.
     */
    /** Records the §15.1 outbox-404 discovery. */
    @Synchronized
    fun markReceiveOnly(id: String, receiveOnly: Boolean) {
        val current = _hosts.value
        val index = current.indexOfFirst { it.deviceId == id }
        if (index < 0) return
        if (current[index].receiveOnly == receiveOnly) return
        val next = current.toMutableList()
        next[index] = next[index].copy(receiveOnly = receiveOnly)
        _hosts.value = next
        save(next)
    }

    fun updateEndpoint(id: String, address: String, port: Int, name: String?) {
        val current = _hosts.value
        val index = current.indexOfFirst { it.deviceId == id }
        if (index < 0) return
        val existing = current[index]
        val updated = existing.copy(
            lastHost = address,
            lastPort = port,
            name = if (!name.isNullOrBlank()) name else existing.name,
        )
        if (updated == existing) return
        val next = current.toMutableList().also { it[index] = updated }
        _hosts.value = next
        save(next)
    }

    private fun load(): List<PairedHost> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { SendroJson.decodeFromString<List<PairedHost>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun save(list: List<PairedHost>) {
        prefs.edit().putString(KEY, SendroJson.encodeToString(list)).apply()
    }

    private companion object {
        const val KEY = "hosts"
    }
}

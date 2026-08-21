package com.sendro.android.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the diagnostics screen shows, and what the engine reacts to. */
data class NetworkState(
    val isConnected: Boolean = false,
    val isWifi: Boolean = false,
    val isEthernet: Boolean = false,
    val isCellular: Boolean = false,
    val isMetered: Boolean = false,
    /** Bumped on every meaningful transport change (including the first). */
    val changeToken: Int = 0,
) {
    /** Wi-Fi or Ethernet: the only two transports a LAN transfer can use. */
    val hasLocalTransport: Boolean get() = isWifi || isEthernet

    val statusText: String
        get() = when {
            !isConnected -> "No network connection"
            isWifi && isEthernet -> "Connected via Wi-Fi and Ethernet"
            isWifi -> "Connected via Wi-Fi"
            isEthernet -> "Connected via Ethernet"
            isCellular -> "Cellular only — Sendro needs Wi-Fi or Ethernet"
            else -> "Connected (interface unknown)"
        }
}

/**
 * One ConnectivityManager callback for the whole app.
 *
 * Why it matters (the same reason as iOS's NWPathMonitor): joining the PC's
 * Mobile Hotspot, or turning on the phone's own hotspot for the PC to join,
 * changes the interface under a running app. mDNS results, the resolved
 * ip:port and any in-flight long poll all belong to the old network. On a
 * change we restart discovery and re-ping every paired host — a manually
 * entered or QR-scanned address is re-probed rather than being written off.
 *
 * Note it registers a *callback*, not a broadcast receiver:
 * CONNECTIVITY_ACTION is deprecated and unreliable from API 28.
 *
 * Two things here are deliberate and were both bugs before:
 *
 *  - The request does NOT ask for `NET_CAPABILITY_INTERNET`. Sendro's whole
 *    job is a LAN, and a router with no upstream — or a PC hotspot, or a TV on
 *    an isolated switch — never gains that capability, so a callback that
 *    required it simply never fired and network changes went unnoticed.
 *  - Transports are computed across EVERY network the callback knows about,
 *    not just `activeNetwork`. Android makes a validated cellular link the
 *    active network whenever Wi-Fi has no internet, which is exactly the
 *    situation Sendro is designed for; reading only the active network made
 *    the app report "cellular" while sitting on the PC's Wi-Fi.
 */
class NetworkWatcher(context: Context) {

    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager

    private val _state = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private var lastSignature: String? = null
    private var registered = false

    /** Every network the system currently has, not just the default route. */
    private val seen = LinkedHashMap<Network, NetworkCapabilities>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onLost(network: Network) {
            synchronized(seen) { seen.remove(network) }
            refresh()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            synchronized(seen) { seen[network] = caps }
            refresh()
        }

        override fun onUnavailable() {
            refresh()
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val manager = connectivity ?: return
        val request = NetworkRequest.Builder()
            // No NET_CAPABILITY_INTERNET: see the class doc. A LAN with no
            // upstream is the normal case, not an edge case.
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { registered = true }
        refresh()
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        registered = false
    }

    /**
     * Recompute across every known network.
     *
     * The active network still decides "metered", because that is genuinely a
     * property of the default route — but which transports EXIST is what
     * matters for a LAN app, and that is the union.
     */
    @Synchronized
    fun refresh() {
        val manager = connectivity
        val active = manager?.activeNetwork
        val activeCaps = active?.let { manager.getNetworkCapabilities(it) }

        // Refresh the cached capabilities of the active network too: on a cold
        // start no callback has fired yet and `seen` is empty.
        if (active != null && activeCaps != null) {
            synchronized(seen) { seen[active] = activeCaps }
        }
        val all = synchronized(seen) {
            // Drop anything the system no longer knows about: onLost is not
            // guaranteed to arrive after a process freeze, and a stale entry
            // would keep claiming the device is on Wi-Fi forever.
            if (manager != null) {
                val dead = seen.keys.filter { manager.getNetworkCapabilities(it) == null }
                dead.forEach { seen.remove(it) }
            }
            seen.values.toList()
        }

        val wifi = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        val ethernet = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
        val cellular = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
        val connected = all.isNotEmpty()
        val metered = manager?.isActiveNetworkMetered == true

        val signature = "$connected|$wifi|$ethernet|$cellular"
        val previous = _state.value
        // The first update just records the baseline: the app has only just
        // started discovery and restarting it there would be pure churn.
        val token = when {
            lastSignature == null -> previous.changeToken
            lastSignature != signature -> previous.changeToken + 1
            else -> previous.changeToken
        }
        lastSignature = signature

        _state.value = NetworkState(
            isConnected = connected,
            isWifi = wifi,
            isEthernet = ethernet,
            isCellular = cellular,
            isMetered = metered,
            changeToken = token,
        )
    }
}

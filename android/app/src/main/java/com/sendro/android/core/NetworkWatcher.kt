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
    val statusText: String
        get() = when {
            !isConnected -> "No network connection"
            isWifi -> "Connected via Wi-Fi"
            isEthernet -> "Connected via Ethernet"
            isCellular -> "Connected via cellular"
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
 */
class NetworkWatcher(context: Context) {

    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager

    private val _state = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private var lastSignature: String? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        override fun onUnavailable() = refresh()
    }

    @Synchronized
    fun start() {
        if (registered) return
        val manager = connectivity ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
     * Recompute from the *active* network rather than the callback's argument:
     * during a handover several networks are momentarily available and only
     * the active one describes what a socket will actually use.
     */
    @Synchronized
    fun refresh() {
        val manager = connectivity
        val active = manager?.activeNetwork
        val caps = active?.let { manager.getNetworkCapabilities(it) }

        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val connected = caps != null
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

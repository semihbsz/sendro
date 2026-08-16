package com.sendro.android.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress

/** One `_sendro._tcp` instance seen on the LAN (PROTOCOL.md §2). */
data class DiscoveredHost(
    /** TXT `id` — the host deviceId. */
    val deviceId: String,
    /** TXT `nm`, falling back to the mDNS instance name. */
    val name: String,
    /** TXT `pf` — `windows` | `ios`. */
    val platform: String,
    /** TXT `v` — protocol version. */
    val protocolVersion: Int,
    val address: String? = null,
    val port: Int? = null,
    val serviceName: String,
) {
    val isResolved: Boolean get() = address != null && port != null
}

/**
 * mDNS discovery of `_sendro._tcp` hosts via [NsdManager].
 *
 * Three Android-specific hazards are handled explicitly, because each one
 * produces "it works on my Pixel, not on their Xiaomi":
 *
 *  1. **Resolve concurrency.** `NsdManager.resolveService` is single-slot on
 *     most OEM builds: firing two in parallel fails the second with
 *     `FAILURE_ALREADY_ACTIVE` (error 3), and on some builds wedges the
 *     resolver until the process restarts. Every resolve goes through one
 *     coroutine consuming a [Channel], so exactly one is ever in flight.
 *  2. **Multicast.** Several devices drop inbound multicast when the screen is
 *     off or Wi-Fi is power-saving, and mDNS simply never arrives. A
 *     `WifiManager.MulticastLock` is held for as long as we are browsing, and
 *     released the moment we stop (it is a real battery cost).
 *  3. **Stale services.** `onServiceLost` is unreliable; a PC that changed
 *     subnet keeps its old resolution forever. Every [restart] (network change,
 *     manual refresh) drops the whole cache and re-browses from zero.
 */
class Discovery(
    context: Context,
    private val scope: CoroutineScope,
) {

    enum class Status { IDLE, BROWSING, FAILED }

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Serialises resolves (hazard 1). Capacity is generous; duplicates are
     *  filtered by [pendingResolves] before they ever reach the queue. */
    private val resolveQueue = Channel<NsdServiceInfo>(capacity = 64)
    private val pendingResolves = HashSet<String>()
    private var resolveWorkerStarted = false

    /** serviceName -> parsed host (whatever we know about it right now). */
    private val known = LinkedHashMap<String, DiscoveredHost>()

    @Synchronized
    fun start() {
        val manager = nsdManager ?: run {
            _status.value = Status.FAILED
            return
        }
        if (listener != null) return

        acquireMulticastLock()
        startResolveWorker()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _status.value = Status.BROWSING
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _status.value = Status.IDLE
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
                _status.value = Status.FAILED
                runCatching { manager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery stop failed: $errorCode")
                _status.value = Status.IDLE
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                forget(serviceInfo.serviceName)
            }
        }

        listener = discoveryListener
        _status.value = Status.BROWSING
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            Log.w(TAG, "discoverServices threw", it)
            listener = null
            releaseMulticastLock()
            _status.value = Status.FAILED
        }
    }

    @Synchronized
    fun stop() {
        val manager = nsdManager
        listener?.let { current ->
            runCatching { manager?.stopServiceDiscovery(current) }
        }
        listener = null
        releaseMulticastLock()
        _status.value = Status.IDLE
    }

    /**
     * Full reset: used on every network change (joining the PC's hotspot,
     * switching Wi-Fi) where every cached resolution belongs to the old
     * interface and is therefore a lie.
     */
    @Synchronized
    fun restart() {
        stop()
        synchronized(known) { known.clear() }
        synchronized(pendingResolves) { pendingResolves.clear() }
        _hosts.value = emptyList()
        scope.launch {
            // Android needs a beat between stopServiceDiscovery and the next
            // discoverServices or the new one silently never starts.
            delay(350)
            start()
        }
    }

    // -----------------------------------------------------------------------
    // Resolve pipeline (one at a time)
    // -----------------------------------------------------------------------

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        // NsdManager only reports the type we asked for, but it spells it
        // inconsistently across OEM builds ("_sendro._tcp", "_sendro._tcp.",
        // "_sendro._tcp.local."), so match loosely rather than exactly.
        val type = serviceInfo.serviceType.orEmpty()
        if (type.isNotEmpty() && !type.contains("sendro", ignoreCase = true)) return
        val name = serviceInfo.serviceName ?: return
        synchronized(pendingResolves) {
            if (!pendingResolves.add(name)) return
        }
        resolveQueue.trySend(serviceInfo)
    }

    private fun startResolveWorker() {
        if (resolveWorkerStarted) return
        resolveWorkerStarted = true
        scope.launch {
            for (info in resolveQueue) {
                if (!isActive) break
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveOnce(info) }
                synchronized(pendingResolves) { pendingResolves.remove(info.serviceName) }
                if (resolved != null) publish(resolved)
                // A short breather between resolves: back-to-back calls on some
                // OEM builds still trip FAILURE_ALREADY_ACTIVE even when the
                // previous callback has fired.
                delay(120)
            }
        }
    }

    /**
     * One `resolveService` call, bridged to a suspend function.
     *
     * `resolveService` is deprecated on API 34 in favour of
     * `registerServiceInfoCallback`, which does not exist below 34. The
     * deprecated call still works on every level Sendro supports (26..35) and
     * keeps this to one code path.
     */
    @Suppress("DEPRECATION")
    private suspend fun resolveOnce(info: NsdServiceInfo): DiscoveredHost? {
        val manager = nsdManager ?: return null
        val result = Channel<NsdServiceInfo?>(capacity = 1)

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                result.trySend(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                result.trySend(serviceInfo)
            }
        }

        runCatching { manager.resolveService(info, listener) }
            .onFailure {
                Log.w(TAG, "resolveService threw", it)
                return null
            }

        val resolved = result.receive() ?: return null
        return parse(resolved)
    }

    private fun parse(info: NsdServiceInfo): DiscoveredHost? {
        val txt = info.attributes ?: emptyMap()
        fun txtValue(key: String): String? =
            txt[key]?.let { bytes -> runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }

        val deviceId = txtValue("id") ?: return null
        val name = txtValue("nm") ?: info.serviceName ?: "Unknown PC"
        val platform = txtValue("pf") ?: "windows"
        val version = txtValue("v")?.toIntOrNull() ?: SENDRO_PROTOCOL_VERSION

        // Force IPv4: Windows advertises link-local IPv6 (fe80::%scope) over
        // mDNS too, and a scoped literal does not survive the trip into a URL.
        // Every LAN Sendro targets has IPv4.
        val address = pickIpv4(info)
        val port = info.port.takeIf { it in 1..65535 }

        return DiscoveredHost(
            deviceId = deviceId,
            name = name,
            platform = platform,
            protocolVersion = version,
            address = address,
            port = port,
            serviceName = info.serviceName ?: deviceId,
        )
    }

    @Suppress("DEPRECATION")
    private fun pickIpv4(info: NsdServiceInfo): String? {
        val candidates: List<InetAddress> =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.hostAddresses
            } else {
                listOfNotNull(info.host)
            }
        val v4 = candidates.firstOrNull { it is Inet4Address } ?: return null
        // hostAddress can carry a scope suffix; it is meaningless in a URL.
        return v4.hostAddress?.substringBefore('%')
    }

    private fun publish(host: DiscoveredHost) {
        synchronized(known) {
            val previous = known[host.serviceName]
            // Never downgrade a good resolution to an unresolved duplicate.
            known[host.serviceName] = if (host.isResolved || previous == null) {
                host
            } else {
                previous.copy(name = host.name, platform = host.platform)
            }
            _hosts.value = known.values.sortedBy { it.name.lowercase() }
        }
    }

    private fun forget(serviceName: String?) {
        if (serviceName == null) return
        synchronized(known) {
            if (known.remove(serviceName) != null) {
                _hosts.value = known.values.sortedBy { it.name.lowercase() }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Multicast lock (hazard 2)
    // -----------------------------------------------------------------------

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val lock = runCatching { wifiManager?.createMulticastLock(MULTICAST_TAG) }.getOrNull()
            ?: return
        runCatching {
            lock.setReferenceCounted(false)
            lock.acquire()
        }
        multicastLock = lock
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        multicastLock = null
    }

    private companion object {
        const val TAG = "SendroDiscovery"
        const val SERVICE_TYPE = "_sendro._tcp."
        const val MULTICAST_TAG = "sendro-mdns"
        const val RESOLVE_TIMEOUT_MS = 6_000L
    }
}

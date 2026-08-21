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
 *  4. **A resolve that fails is a host that disappears.** NsdManager reports a
 *     service ONCE; it does not re-announce one it has already handed over. So
 *     a single `FAILURE_ALREADY_ACTIVE` or a timeout used to make that PC
 *     invisible until the user pressed "Restart discovery". Failed resolves
 *     are now retried, keeping their slot in [pendingResolves] meanwhile.
 *  5. **The browser can die quietly.** `onDiscoveryStopped` and
 *     `onStartDiscoveryFailed` fire when the system tears the browser down
 *     (an interface going away on a TV does exactly this) — and the old code
 *     left [listener] non-null, so every later [start] returned immediately
 *     and discovery was over for the life of the process. Both callbacks now
 *     clear it, and a watchdog re-starts a browser that should be running.
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

    /**
     * A one-line explanation for the diagnostics panel when something is
     * wrong, so an unfixable fault reads as a sentence instead of a silent
     * empty list.
     */
    private val _detail = MutableStateFlow<String?>(null)
    val detail: StateFlow<String?> = _detail.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** True between [start] and [stop]; what the watchdog compares against. */
    private var shouldBrowse = false
    private var watchdogStarted = false

    /** Serialises resolves (hazard 1). Capacity is generous; duplicates are
     *  filtered by [pendingResolves] before they ever reach the queue. */
    private val resolveQueue = Channel<NsdServiceInfo>(capacity = 64)
    private val pendingResolves = HashSet<String>()

    /** serviceName -> failed resolve attempts so far (hazard 4). */
    private val resolveAttempts = HashMap<String, Int>()
    private var resolveWorkerStarted = false

    /** serviceName -> parsed host (whatever we know about it right now). */
    private val known = LinkedHashMap<String, DiscoveredHost>()

    @Synchronized
    fun start() {
        shouldBrowse = true
        startWatchdog()
        val manager = nsdManager ?: run {
            _status.value = Status.FAILED
            _detail.value = "This device has no mDNS service. Add the PC by IP address instead."
            return
        }
        if (listener != null) return

        // Deliberately NOT gated on Wi-Fi being the active transport: a TV on
        // Ethernet, or a phone whose default route is cellular while the PC
        // sits on the same Wi-Fi, must still discover. The lock is free when
        // there is no Wi-Fi radio to lock.
        acquireMulticastLock()
        startResolveWorker()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _status.value = Status.BROWSING
                _detail.value = null
            }

            override fun onDiscoveryStopped(serviceType: String) {
                // The system tore the browser down — an interface disappearing
                // on a TV does exactly this. Forget the listener so the next
                // start() (or the watchdog) can actually build a new one.
                clearListener(this)
                _status.value = Status.IDLE
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
                runCatching { manager.stopServiceDiscovery(this) }
                clearListener(this)
                _status.value = Status.FAILED
                _detail.value = startFailureText(errorCode)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery stop failed: $errorCode")
                clearListener(this)
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
            _detail.value = "The system refused to start mDNS browsing. Retrying…"
        }
    }

    @Synchronized
    fun stop() {
        shouldBrowse = false
        val manager = nsdManager
        listener?.let { current ->
            runCatching { manager?.stopServiceDiscovery(current) }
        }
        listener = null
        releaseMulticastLock()
        _status.value = Status.IDLE
    }

    /** Drop [candidate] if it is still the live listener; ignore a stale one. */
    @Synchronized
    private fun clearListener(candidate: NsdManager.DiscoveryListener) {
        if (listener !== candidate) return
        listener = null
        releaseMulticastLock()
    }

    /**
     * Revives a browser that should be running but is not.
     *
     * There is no callback for "the resolver wedged"; the only honest way to
     * notice is to check that we still hold a live listener and rebuild one if
     * we do not. Cheap — a comparison every [WATCHDOG_INTERVAL_MS].
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val dead = synchronized(this@Discovery) { shouldBrowse && listener == null }
                if (dead) {
                    Log.i(TAG, "watchdog: browser is not running, restarting")
                    start()
                }
            }
        }
    }

    private fun startFailureText(errorCode: Int): String = when (errorCode) {
        // NsdManager.FAILURE_ALREADY_ACTIVE
        3 -> "Another app is already browsing this network. Retrying…"
        // NsdManager.FAILURE_MAX_LIMIT
        4 -> "The system's mDNS service is out of slots. Restart the device, " +
            "or add the PC by IP address."
        else -> "The system's mDNS service refused to browse. Retrying…"
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
        synchronized(pendingResolves) {
            pendingResolves.clear()
            resolveAttempts.clear()
        }
        _hosts.value = emptyList()
        _detail.value = null
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
                // Explicitly non-null: `serviceName` is a Java platform type,
                // and every entry that reaches this queue came through
                // enqueueResolve, which already rejected a null name.
                val name: String = info.serviceName ?: ""
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveOnce(info) }
                if (resolved != null) {
                    synchronized(pendingResolves) {
                        pendingResolves.remove(name)
                        resolveAttempts.remove(name)
                    }
                    publish(resolved)
                } else {
                    // Hazard 4: NsdManager will not announce this service
                    // again, so dropping it here loses the host entirely.
                    // Keep its slot in pendingResolves (so onServiceFound
                    // cannot double-queue it) and try again shortly.
                    val attempts = synchronized(pendingResolves) {
                        val next = (resolveAttempts[name] ?: 0) + 1
                        resolveAttempts[name] = next
                        next
                    }
                    if (attempts <= RESOLVE_RETRY_LIMIT) {
                        Log.d(TAG, "resolve retry $attempts for $name")
                        scope.launch {
                            delay(attempts * RESOLVE_RETRY_STEP_MS)
                            resolveQueue.trySend(info)
                        }
                    } else {
                        synchronized(pendingResolves) {
                            pendingResolves.remove(name)
                            resolveAttempts.remove(name)
                        }
                        Log.w(TAG, "giving up resolving $name")
                        _detail.value =
                            "Found \"$name\" but the system could not resolve its address. " +
                            "Add it by IP address, or restart discovery."
                    }
                }
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

        /** How many times a failed resolve is retried before the host is dropped. */
        const val RESOLVE_RETRY_LIMIT = 4
        const val RESOLVE_RETRY_STEP_MS = 900L

        /** How often the watchdog checks that the browser is still alive. */
        const val WATCHDOG_INTERVAL_MS = 20_000L
    }
}

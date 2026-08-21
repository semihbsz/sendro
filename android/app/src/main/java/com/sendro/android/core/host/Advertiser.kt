package com.sendro.android.core.host

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The §2 mDNS advertisement, from the host side.
 *
 * Same service type and the same four TXT records the Windows host publishes,
 * so an existing client discovers a TV exactly as it discovers a PC and needs
 * no protocol change (§15.1). `pf` carries `androidtv`, which clients must
 * treat as informational — capability comes from `/api/v1/info` and a 404 on
 * the outbox, never from this string.
 */
class Advertiser(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var listener: NsdManager.RegistrationListener? = null

    /**
     * Advertising needs the same MulticastLock browsing does.
     *
     * The lock is not about sending: it is about *receiving* the mDNS queries
     * that ask "who is _sendro._tcp?". Several Wi-Fi drivers drop inbound
     * multicast when no app holds a lock, and the symptom is an advertisement
     * that exists but is never discovered — the single most confusing failure
     * mode in this whole feature.
     */
    private var multicastLock: WifiManager.MulticastLock? = null

    /** The name the system actually registered (it renames on conflict). */
    @Volatile
    var registeredName: String? = null
        private set

    /**
     * Why the advertisement is not up, in a sentence, for the diagnostics
     * panel. mDNS registration failing at boot (the interface is often not
     * ready yet on a TV) used to be completely invisible: the app said "Ready
     * to receive" and no phone could ever find it.
     */
    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun register(instanceName: String, port: Int, deviceId: String, deviceName: String, platform: String) {
        val manager = nsdManager ?: return
        unregister()
        acquireMulticastLock()

        val info = NsdServiceInfo().apply {
            // The instance name is the human device name (§2). NsdManager
            // appends " (2)" itself if another Sendro is already using it.
            serviceName = instanceName.ifBlank { "Sendro" }
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("v", "1")
            setAttribute("id", deviceId)
            setAttribute("nm", deviceName)
            setAttribute("pf", platform)
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredName = serviceInfo.serviceName
                lastError = null
                Log.i(TAG, "advertising ${serviceInfo.serviceName} on $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
                registeredName = null
                lastError = failureText(errorCode)
                // Never registered, so there is nothing to unregister later:
                // holding on to this listener would make the next attempt
                // throw before it even tried.
                dropListener(this)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registeredName = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregistration failed: $errorCode")
            }
        }

        listener = registration
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure {
                Log.w(TAG, "registerService threw", it)
                listener = null
            }
    }

    @Synchronized
    fun unregister() {
        val manager = nsdManager
        listener?.let { runCatching { manager?.unregisterService(it) } }
        listener = null
        registeredName = null
        releaseMulticastLock()
    }

    /** Forget [candidate] if it is still the live listener. */
    @Synchronized
    private fun dropListener(candidate: NsdManager.RegistrationListener) {
        if (listener !== candidate) return
        listener = null
    }

    private fun failureText(errorCode: Int): String = when (errorCode) {
        // NsdManager.FAILURE_ALREADY_ACTIVE
        3 -> "Another Sendro advertisement is still being torn down. Retrying…"
        // NsdManager.FAILURE_MAX_LIMIT
        4 -> "The system's mDNS service is out of slots. Restart this device, " +
            "or pair from the phone using this device's IP address."
        else -> "This device could not announce itself on the network. " +
            "Pair using its IP address, shown above."
    }

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
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    companion object {
        private const val TAG = "SendroAdvertiser"
        const val SERVICE_TYPE = "_sendro._tcp."
        private const val MULTICAST_TAG = "sendro-mdns-host"

        /**
         * The LAN addresses this device can be reached on, best first.
         *
         * Used for the §13 QR (`h=`) and for the "connect to me at…" line on
         * the pairing screen. IPv4 only, for the same reason the client
         * resolver forces IPv4: a scoped link-local IPv6 literal does not
         * survive the trip into a URL, and every LAN Sendro targets has IPv4.
         */
        fun localIpv4Addresses(): List<String> {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }
                .getOrNull() ?: return emptyList()
            val scored = ArrayList<Pair<Int, String>>()
            for (nif in interfaces) {
                val up = runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)
                if (!up) continue
                val name = nif.name.orEmpty().lowercase()
                // Wi-Fi first, then Ethernet, then anything else; a TV on
                // Ethernet and a phone on Wi-Fi are both normal.
                val rank = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") -> 2      // the device's own hotspot
                    name.startsWith("rmnet") -> 9   // cellular: never useful here
                    else -> 5
                }
                for (address in nif.inetAddresses) {
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isAnyLocalAddress) continue
                    val text = address.hostAddress?.substringBefore('%') ?: continue
                    scored.add(rank to text)
                }
            }
            return scored.sortedBy { it.first }.map { it.second }.distinct()
        }
    }
}

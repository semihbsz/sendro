package com.sendro.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sendro.android.core.ApkInstaller
import com.sendro.android.core.AppPaths
import com.sendro.android.core.DeviceKind
import com.sendro.android.core.Discovery
import com.sendro.android.core.HistoryStore
import com.sendro.android.core.MediaSaver
import com.sendro.android.core.MessageCenter
import com.sendro.android.core.NetworkWatcher
import com.sendro.android.core.Notifier
import com.sendro.android.core.PairedHostStore
import com.sendro.android.core.SendTray
import com.sendro.android.core.SettingsStore
import com.sendro.android.core.TokenStore
import com.sendro.android.core.TransferEngine
import com.sendro.android.core.TransferService
import com.sendro.android.core.UpdateChecker
import com.sendro.android.core.UploadEngine
import com.sendro.android.core.host.Advertiser
import com.sendro.android.core.host.HostPairing
import com.sendro.android.core.host.PeerStore
import com.sendro.android.core.host.ReceiverHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The object graph, built once, by hand.
 *
 * No Hilt, no Koin: there are fourteen singletons and one lifetime. A DI
 * framework here would cost a KSP round on every build and buy nothing.
 *
 * Everything lives in [appScope], NOT in an Activity's scope, because a
 * transfer has to survive rotation, the Activity being destroyed, and the user
 * leaving the app (the foreground service is what keeps the process alive, but
 * the coroutines that do the work belong here).
 */
class SendroApplication : Application(), coil.ImageLoaderFactory {

    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    lateinit var paths: AppPaths private set
    lateinit var settings: SettingsStore private set
    lateinit var tokens: TokenStore private set
    lateinit var pairedHosts: PairedHostStore private set
    lateinit var history: HistoryStore private set
    lateinit var messages: MessageCenter private set
    lateinit var notifier: Notifier private set
    lateinit var networkWatcher: NetworkWatcher private set
    lateinit var discovery: Discovery private set
    lateinit var mediaSaver: MediaSaver private set
    lateinit var transferEngine: TransferEngine private set
    lateinit var uploadEngine: UploadEngine private set
    lateinit var sendTray: SendTray private set
    lateinit var updateChecker: UpdateChecker private set
    lateinit var apkInstaller: ApkInstaller private set

    /** §15 receiver host: the TV (or an opted-in phone) acting as a host. */
    lateinit var peers: PeerStore private set
    lateinit var hostPairing: HostPairing private set
    lateinit var receiverHost: ReceiverHost private set

    /** True on an Android TV. Decides §15.4's default and the UI's ten-foot mode. */
    val isTelevision: Boolean by lazy { DeviceKind.isTelevision(this) }

    /**
     * The §5 `platform` string this device reports to peers — `androidtv` on a
     * TV, `android` otherwise. Used both when this device HOSTS (in
     * `/api/v1/info`) and when it pairs as a CLIENT, so a PC's device list
     * shows a TV as a TV.
     */
    val platform: String by lazy { DeviceKind.platformString(this) }

    override fun onCreate() {
        super.onCreate()

        paths = AppPaths(this)
        // §15.4: a TV receives by default, a phone does not.
        settings = SettingsStore(this, appScope, receiverDefault = isTelevision)
        tokens = TokenStore(this)
        pairedHosts = PairedHostStore(this)
        history = HistoryStore(paths.historyFile)
        messages = MessageCenter()
        notifier = Notifier(this, settings)
        networkWatcher = NetworkWatcher(this)
        discovery = Discovery(this, appScope)
        mediaSaver = MediaSaver(this, paths)
        sendTray = SendTray()

        transferEngine = TransferEngine(
            context = this,
            scope = appScope,
            settings = settings,
            paired = pairedHosts,
            tokens = tokens,
            history = history,
            paths = paths,
            mediaSaver = mediaSaver,
            messages = messages,
            notifier = notifier,
            onTransferActivity = ::syncForegroundService,
        )
        uploadEngine = UploadEngine(
            context = this,
            scope = appScope,
            paired = pairedHosts,
            tokens = tokens,
            history = history,
            paths = paths,
            onUploadActivity = ::syncForegroundService,
        )
        updateChecker = UpdateChecker(this, appScope, settings)
        apkInstaller = ApkInstaller(this)

        peers = PeerStore(this)
        hostPairing = HostPairing()
        receiverHost = ReceiverHost(
            context = this,
            scope = appScope,
            settings = settings,
            paths = paths,
            mediaSaver = mediaSaver,
            history = history,
            messages = messages,
            notifier = notifier,
            peers = peers,
            pairing = hostPairing,
            advertiser = Advertiser(this),
            onStateChanged = ::syncForegroundService,
        )

        networkWatcher.start()
        transferEngine.start()
        discovery.start()
        updateChecker.startAutomaticChecks()

        observeReceiverSetting()
        observeNetworkChanges()
        observeDiscovery()
        observeProcessLifecycle()
        pruneStaleTemporaries()
    }

    /**
     * Coil's loader, built here so the video-frame decoder is registered
     * (Coil 2 does not pick up `coil-video` automatically) and so the disk
     * cache stays small — Library thumbnails are the only images Sendro ever
     * decodes, and they are always downsampled to the tile size.
     */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .components { add(coil.decode.VideoFrameDecoder.Factory()) }
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()

    /**
     * The foreground service exists exactly while something is moving. The
     * engines call this on every state change; the service itself decides when
     * to stop, so a race between "last transfer finished" and "next one
     * started" cannot leave the process unprotected mid-download.
     */
    /**
     * The foreground service has two jobs now: keeping a transfer alive, and
     * keeping the §15 receiver host's socket alive. Without the second one the
     * system would freeze the process minutes after the user leaves the app
     * and a phone's upload would hit a dead port.
     */
    private fun syncForegroundService() {
        val busy = transferEngine.active.value.any { it.phase.isLive } ||
            uploadEngine.items.value.any { it.phase.isLive } ||
            receiverHost.isRunning
        if (!busy) return
        // On API 31+ the system refuses a foreground service started from the
        // background. It does not crash (TransferService.start catches it) —
        // it just quietly does nothing, which on a TV means the receiver host
        // is unprotected and will be frozen. Record it, show it in
        // diagnostics, and try again the moment the app is visible.
        foregroundServiceBlocked = !TransferService.start(this)
    }

    /**
     * True when the system last refused to start the foreground service.
     *
     * Read by the diagnostics panel: this is the one fault here that the app
     * genuinely cannot fix on its own, so it gets a sentence instead of a
     * silent stall.
     */
    @Volatile
    var foregroundServiceBlocked: Boolean = false
        private set

    /**
     * The receiver host follows its setting, and nothing else starts or stops
     * it. Toggling in Settings is therefore the whole story: off means the
     * socket is closed and the mDNS advertisement withdrawn.
     */
    private fun observeReceiverSetting() {
        appScope.launch {
            // distinctUntilChanged matters: SendroSettings emits on every
            // unrelated change too, and tearing the socket down and back up
            // because the user renamed the device would drop a live upload.
            settings.state
                .map { it.receiveFromOtherDevices }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    if (enabled) receiverHost.start() else receiverHost.stop()
                }
        }
    }

    private fun observeNetworkChanges() {
        appScope.launch {
            var lastToken = -1
            networkWatcher.state.collectLatest { state ->
                if (state.changeToken == lastToken) return@collectLatest
                val first = lastToken == -1
                lastToken = state.changeToken
                // The first emission is the baseline; discovery has only just
                // started, restarting it there would be pure churn.
                if (first) return@collectLatest
                discovery.restart()
                transferEngine.onNetworkChanged()
                uploadEngine.clearCooldowns()
                // The advertisement belongs to the old interface and the
                // pairing screen's addresses are stale; the listening socket
                // itself is bound to 0.0.0.0 and survives.
                receiverHost.onNetworkChanged()
            }
        }
    }

    private fun observeDiscovery() {
        appScope.launch {
            discovery.hosts.collectLatest { transferEngine.syncDiscoveredEndpoints(it) }
        }
    }

    private fun observeProcessLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Back in the foreground: re-ping every paired host and restart
                // any parked poll loop.
                transferEngine.onAppForegrounded()
                uploadEngine.clearCooldowns()
                networkWatcher.refresh()
                discovery.start()
                // The app is visible, so a foreground service the system
                // refused while we were in the background is allowed now.
                syncForegroundService()
            }

            override fun onStop(owner: LifecycleOwner) {
                // Browsing mDNS in the background costs battery for nothing —
                // the long poll is what actually delivers offers. The
                // multicast lock goes with it.
                discovery.stop()
            }
        })
    }

    /**
     * Staged outgoing copies are the app's only unbounded temp: a share that
     * was never sent leaves bytes in the cache. Anything older than a day and
     * not in the tray is dropped at launch.
     */
    private fun pruneStaleTemporaries() {
        appScope.launch {
            runCatching {
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
                paths.outgoing.listFiles()?.forEach { batch ->
                    if (batch.lastModified() < cutoff) batch.deleteRecursively()
                }
            }
        }
    }
}

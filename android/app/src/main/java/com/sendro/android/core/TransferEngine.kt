package com.sendro.android.core

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

// ---------------------------------------------------------------------------
// UI-facing state
// ---------------------------------------------------------------------------

sealed interface TransferPhase {
    /**
     * Accepted, waiting for a slot.
     *
     * The host runs a concurrency gate (`core/src/server.rs`, default 2) and
     * answers 503 when it is full, so the client keeps its own queue at the
     * same width. [position] is 1-based and recomputed whenever the queue
     * moves, so a row can say "3rd in line" without knowing about the list.
     */
    data class Queued(val position: Int) : TransferPhase

    /**
     * The host told us to come back later — slots busy, or the user pressed
     * Pause on the PC. Amber, never red: this is the host working correctly.
     */
    data class HostBusy(
        val message: String,
        val retryInSeconds: Int,
        val paused: Boolean,
    ) : TransferPhase

    /** Preflight + prefix re-hash. */
    data object Preparing : TransferPhase
    data object Downloading : TransferPhase
    data object Verifying : TransferPhase

    /** Media + the "Ask Every Time" setting. */
    data object AwaitingSaveChoice : TransferPhase
    data object Saving : TransferPhase

    /** Gallery write refused for want of a permission; the bytes are kept. */
    data object StorageDenied : TransferPhase

    data class Failed(val message: String, val resumable: Boolean) : TransferPhase

    /** Restored from a previous launch, or paused by a lost connection. */
    data object Interrupted : TransferPhase

    val label: String
        get() = when (this) {
            is Queued -> if (position <= 1) "Waiting…" else "Waiting — $position in line"
            is HostBusy -> if (retryInSeconds > 0) "$message Retrying in ${retryInSeconds}s." else message
            Preparing -> "Preparing…"
            Downloading -> "Downloading"
            Verifying -> "Verifying…"
            AwaitingSaveChoice -> "Where should this go?"
            Saving -> "Saving…"
            StorageDenied -> "Storage Access Needed"
            is Failed -> "Failed"
            Interrupted -> "Paused"
        }

    /** The short chip label on the Receive list. */
    val shortLabel: String
        get() = when (this) {
            is Queued -> if (position <= 1) "Next up" else "#$position"
            is HostBusy -> if (paused) "Paused on PC" else "Host busy"
            Preparing -> "Prep"
            Downloading -> "Receiving"
            Verifying -> "Verifying"
            AwaitingSaveChoice -> "Choose"
            Saving -> "Saving"
            StorageDenied -> "Storage?"
            is Failed -> "Failed"
            Interrupted -> "Paused"
        }

    /** True while the foreground service should be alive for this transfer. */
    val isBusy: Boolean
        get() = this is Preparing || this is Downloading || this is Verifying || this is Saving

    /**
     * True while the transfer is still going to happen by itself. Queued and
     * backpressured transfers are NOT failures and must never be drawn as
     * such — that distinction is the whole point of this pair of states.
     */
    val isPending: Boolean get() = this is Queued || this is HostBusy

    /** True when the engine is holding it and the user need do nothing. */
    val isLive: Boolean get() = isBusy || isPending
}

data class ActiveTransfer(
    val offer: TransferOffer,
    val hostId: String,
    val phase: TransferPhase,
    val bytesReceived: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val etaSeconds: Int? = null,
    /** FIFO ordering for the queue; set once when the transfer is enqueued. */
    val queuedAtMs: Long = 0,
) {
    val id: String get() = offer.transferId
    val fraction: Double
        get() = if (offer.sizeBytes <= 0) 0.0
        else (bytesReceived.toDouble() / offer.sizeBytes).coerceIn(0.0, 1.0)
}

data class IncomingOffer(
    val offer: TransferOffer,
    val hostId: String,
    val receivedAtMs: Long,
    /** True while a bulk "Accept all" call for this offer is in flight. */
    val isAccepting: Boolean = false,
    /** Set when a bulk accept failed for this one item (§12). */
    val errorMessage: String? = null,
) {
    val id: String get() = offer.transferId
}

/** Persisted so a relaunch can resume accepted-but-unfinished transfers. */
@Serializable
private data class InFlightRecord(val offer: TransferOffer, val hostId: String)

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

/**
 * Per-paired-host outbox long-poll loops, offer intake, auto-accept, streaming
 * downloads with resume, verification, save routing and §6.5 status reports.
 *
 * Threading: everything public here is safe to call from the main thread. The
 * mutable state is a handful of [MutableStateFlow]s guarded by [stateLock];
 * the actual IO happens in [scope] (the application scope) on
 * [Dispatchers.IO], so a transfer outlives the Activity that started it.
 */
class TransferEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val paired: PairedHostStore,
    private val tokens: TokenStore,
    private val history: HistoryStore,
    private val paths: AppPaths,
    private val mediaSaver: MediaSaver,
    private val messages: MessageCenter,
    /** The 24-hour local shelf (§11.3); MessageCenter stays RAM-only. */
    private val notes: NoteStore,
    private val notifier: Notifier,
    private val onTransferActivity: () -> Unit,
) {

    companion object {
        private const val TAG = "SendroEngine"

        /** 200 MB safety margin on top of the file size for storage preflight. */
        const val STORAGE_MARGIN_BYTES = 200L * 1024 * 1024

        /** §12 — never more than this many accept calls in flight. */
        const val BULK_ACCEPT_CONCURRENCY = 4

        /**
         * How many downloads may be streaming at once.
         *
         * The host gates at `settings.concurrency` (`core/src/server.rs`,
         * default 2, user-settable 1–4) and answers 503 above it. Matching the
         * default here means the common case never touches the gate at all;
         * when the user has lowered it to 1, the 503 path below absorbs the
         * difference instead of turning it into failures.
         */
        const val MAX_CONCURRENT_DOWNLOADS = 2

        /**
         * A transfer that has been getting nothing but "busy" for this long
         * stops asking and becomes a *resumable* failure the user can retry.
         * Ten minutes is long enough to outlast a big transfer on the PC and
         * short enough that a forgotten Pause does not spin all night.
         */
        private const val GIVE_UP_AFTER_MS = 10L * 60L * 1000L

        /** A 416 restart is cheap once, suspicious twice, a loop three times. */
        private const val RANGE_RESTART_LIMIT = 2

        /** How many times a refused §6.3 accept is retried before giving up. */
        private const val ACCEPT_RETRY_LIMIT = 5

        private const val POLL_WAIT_SECONDS = 25
        private const val BACKOFF_CAP_SECONDS = 15.0

        /** How often a receive-only peer (§15.1) is re-pinged for presence. */
        private const val RECEIVE_ONLY_PING_MS = 10_000L

        /** How often every paired host's poll loop is checked for liveness. */
        private const val POLL_WATCHDOG_MS = 30_000L

        /** Shortest gap between two re-accepts of the same host-retried transfer. */
        private const val REACCEPT_COOLDOWN_MS = 10_000L
    }

    private val _incoming = MutableStateFlow<List<IncomingOffer>>(emptyList())
    val incoming: StateFlow<List<IncomingOffer>> = _incoming.asStateFlow()

    private val _active = MutableStateFlow<List<ActiveTransfer>>(emptyList())
    val active: StateFlow<List<ActiveTransfer>> = _active.asStateFlow()

    private val _hostOnline = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val hostOnline: StateFlow<Map<String, Boolean>> = _hostOnline.asStateFlow()

    private val stateLock = Any()
    private val pollJobs = HashMap<String, Job>()
    private val downloadJobs = HashMap<String, Job>()
    private val processedOfferIds = HashSet<String>()
    private val autoResumed = HashSet<String>()
    private var started = false

    // --- queue + backpressure state, all guarded by [stateLock] ------------

    /** hostId -> epoch ms before which this host must not be asked again. */
    private val hostCooldowns = HashMap<String, Long>()

    /** hostId -> consecutive busy answers, for the exponential-ish backoff. */
    private val hostBusyStreak = HashMap<String, Int>()

    /** transferId -> epoch ms; the 409 "not ready for this one" cooldown. */
    private val transferCooldowns = HashMap<String, Long>()
    private val transferBusyStreak = HashMap<String, Int>()

    /** transferId -> when this transfer FIRST got a busy answer (give-up clock). */
    private val busySince = HashMap<String, Long>()

    /** transferId -> how many times a 416 has made us start over. */
    private val rangeRestarts = HashMap<String, Int>()

    /** transferId -> when we last re-accepted it after a host-side Retry. */
    private val lastReaccept = HashMap<String, Long>()

    /** The pump is a single logical worker; these two make it re-entrant-safe. */
    private var pumping = false
    private var pumpAgain = false

    /** Runs only while something is queued or cooling down. */
    private var tickerJob: Job? = null

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    fun start() {
        synchronized(stateLock) {
            if (started) return
            started = true
        }
        restoreInFlight()
        scope.launch {
            paired.hosts.collect { reconcileLoops() }
        }
        reconcileLoops()
        startPollWatchdog()
    }

    /**
     * Revives any poll loop that is no longer running.
     *
     * [reconcileLoops] already skips hosts with a live job, so this costs a
     * map lookup per paired host every [POLL_WATCHDOG_MS]. It exists because a
     * loop that dies for a reason nobody predicted must not take that host
     * offline for the rest of the process's life — on a TV, "the PC just
     * stopped appearing and only a restart fixes it" is the exact complaint
     * this prevents.
     */
    private fun startPollWatchdog() {
        scope.launch {
            while (stillActive()) {
                delay(POLL_WATCHDOG_MS)
                reconcileLoops()
            }
        }
    }

    /**
     * Call when the app comes to the foreground.
     *
     * Android may have frozen the process (App Standby / Doze) while it was
     * away, leaving a poll socket that is alive on paper and dead in practice,
     * and the loops may be parked in a backoff sleep of up to 15 s. Cancel and
     * restart every loop so each paired host is re-pinged immediately — a
     * reachable host shows connected within ~1–2 s with no user action.
     */
    fun onAppForegrounded() {
        if (!started) return
        cancelAllPolls()
        _hostOnline.value = emptyMap()
        reconcileLoops()
        // A cooldown measured before the process was frozen means nothing now,
        // and the user is looking at the screen: try everything again at once.
        clearCooldowns()
    }

    /**
     * Call on every network change (joining the PC's hotspot, turning on the
     * phone's own hotspot, switching Wi-Fi).
     *
     * The old poll sockets belong to the old interface, so every loop is torn
     * down and respawned: each paired host — discovered, manually typed or
     * QR-scanned — is re-pinged at its stored address. A host is never
     * permanently written off; the loop retries forever and discovery updates
     * the endpoint if the same PC reappears on the new subnet.
     */
    fun onNetworkChanged() {
        if (!started) return
        cancelAllPolls()
        _hostOnline.value = emptyMap()
        reconcileLoops()
        // New interface, new sockets: whatever the old one was told about
        // busy slots no longer applies.
        clearCooldowns()
    }

    /**
     * Forget every backpressure cooldown and try the queue immediately.
     *
     * Called on foreground and on network change — both are moments where the
     * stored "come back in N seconds" is stale by construction.
     */
    private fun clearCooldowns() {
        synchronized(stateLock) {
            hostCooldowns.clear()
            hostBusyStreak.clear()
            transferCooldowns.clear()
            transferBusyStreak.clear()
        }
        // Anything parked on a countdown goes back to plain "Waiting…".
        _active.update { list ->
            list.map { if (it.phase is TransferPhase.HostBusy) it.copy(phase = TransferPhase.Queued(0)) else it }
        }
        ensureTicker()
        pump()
    }

    private fun cancelAllPolls() {
        synchronized(stateLock) {
            pollJobs.values.forEach { it.cancel() }
            pollJobs.clear()
        }
    }

    /** Discovery found a fresh endpoint for a host we are paired with. */
    fun syncDiscoveredEndpoints(hosts: List<DiscoveredHost>) {
        for (host in hosts) {
            val address = host.address ?: continue
            val port = host.port ?: continue
            if (paired.host(host.deviceId) != null) {
                paired.updateEndpoint(host.deviceId, address, port, host.name)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Pairing management
    // -----------------------------------------------------------------------

    fun unpair(hostId: String) {
        synchronized(stateLock) {
            pollJobs.remove(hostId)?.cancel()
            hostCooldowns.remove(hostId)
            hostBusyStreak.remove(hostId)
        }
        _hostOnline.value = _hostOnline.value - hostId
        _active.value.filter { it.hostId == hostId }.forEach { cancel(it.id) }
        _incoming.update { list -> list.filterNot { it.hostId == hostId } }
        tokens.delete(hostId)
        paired.remove(hostId)
    }

    suspend fun pingHost(hostId: String): String {
        val client = clientFor(hostId) ?: return "No stored endpoint / token"
        val startedAt = System.currentTimeMillis()
        return try {
            val pong = client.ping()
            val ms = System.currentTimeMillis() - startedAt
            "OK — ${pong.deviceName} ($ms ms)"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Unreachable — ${e.sendroMessage()}"
        }
    }

    // -----------------------------------------------------------------------
    // Offers
    // -----------------------------------------------------------------------

    fun accept(incomingOffer: IncomingOffer) {
        _incoming.update { list -> list.filterNot { it.id == incomingOffer.id } }
        synchronized(stateLock) { processedOfferIds.add(incomingOffer.id) }
        scope.launch { acceptAndStart(incomingOffer.offer, incomingOffer.hostId) }
    }

    fun reject(incomingOffer: IncomingOffer) {
        _incoming.update { list -> list.filterNot { it.id == incomingOffer.id } }
        synchronized(stateLock) { processedOfferIds.add(incomingOffer.id) }
        history.add(
            transferId = incomingOffer.offer.transferId,
            fileName = incomingOffer.offer.fileName,
            sizeBytes = incomingOffer.offer.sizeBytes,
            senderName = incomingOffer.offer.senderName,
            outcome = "rejected",
        )
        scope.launch {
            runCatching { clientFor(incomingOffer.hostId)?.reject(incomingOffer.offer.transferId) }
        }
    }

    /**
     * §12 — accept every pending offer. There is no batch endpoint, so this is
     * a client-side loop over §6.3 with at most [BULK_ACCEPT_CONCURRENCY]
     * accepts in flight. A failure affects only its own item: that offer stays
     * pending with `errorMessage` set, the rest carry on.
     */
    fun acceptAll() {
        val batch = _incoming.value.filterNot { it.isAccepting }
        if (batch.isEmpty()) return
        val batchIds = batch.mapTo(HashSet()) { it.id }
        _incoming.update { list ->
            list.map { if (it.id in batchIds) it.copy(isAccepting = true, errorMessage = null) else it }
        }
        scope.launch {
            val gate = Semaphore(BULK_ACCEPT_CONCURRENCY)
            val jobs = batch.map { item ->
                launch { gate.withPermit { bulkAcceptOne(item) } }
            }
            jobs.forEach { it.join() }
        }
    }

    /** Decline every pending offer (each through the normal reject path). */
    fun declineAll() {
        _incoming.value.filterNot { it.isAccepting }.forEach { reject(it) }
    }

    private suspend fun bulkAcceptOne(item: IncomingOffer) {
        val offer = item.offer
        val transferId = offer.transferId

        // Same storage preflight as the single-offer path (§9).
        val free = freeBytes()
        if (free != null && free < offer.sizeBytes + STORAGE_MARGIN_BYTES) {
            reportStatus(item.hostId, transferId, StatusReport(TransferState.FAILED, error = "insufficient_storage"))
            failBulkItem(
                transferId,
                "Not enough free space — needs ${Format.bytes(offer.sizeBytes + STORAGE_MARGIN_BYTES)} free.",
            )
            return
        }
        val client = clientFor(item.hostId)
        if (client == null) {
            failBulkItem(transferId, "That computer is not reachable right now.")
            return
        }
        val problem = acceptWithBackoff(client, offer, item.hostId)
        if (problem != null) {
            failBulkItem(transferId, problem)
            return
        }
        // Declined or accepted individually while this call was in flight.
        if (_incoming.value.none { it.id == transferId }) return
        _incoming.update { list -> list.filterNot { it.id == transferId } }
        synchronized(stateLock) { processedOfferIds.add(transferId) }
        addRecord(offer, item.hostId)
        // §12 accept-all enqueues; it does NOT start 20 sockets at once.
        enqueue(offer, item.hostId)
    }

    private fun failBulkItem(transferId: String, message: String) {
        _incoming.update { list ->
            list.map { if (it.id == transferId) it.copy(isAccepting = false, errorMessage = message) else it }
        }
    }

    // -----------------------------------------------------------------------
    // §11.2 — client -> host text
    // -----------------------------------------------------------------------

    /** @return null on success, or a human-readable error. Never stores the text. */
    suspend fun sendMessage(text: String, hostId: String): String? {
        val client = clientFor(hostId) ?: return "That computer is not reachable right now."
        return try {
            client.sendMessage(text)
            // Kept locally for 24 h (§11.3). Only on success: an unsent
            // message must not look like it went anywhere.
            notes.addOutgoing(text, paired.host(hostId)?.name ?: "your PC")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.sendroMessage()
        }
    }

    // -----------------------------------------------------------------------
    // Transfer control (UI)
    // -----------------------------------------------------------------------

    fun cancel(transferId: String) {
        val job = synchronized(stateLock) { downloadJobs.remove(transferId) }
        val transfer = _active.value.firstOrNull { it.id == transferId }
        job?.cancel()
        if (transfer == null) return

        paths.partFile(transferId).delete()
        paths.stagedFile(transferId, transfer.offer.fileName).delete()
        removeRecord(transferId)
        forgetQueueState(transferId)
        synchronized(stateLock) { lastReaccept.remove(transferId) }
        _active.update { list -> list.filterNot { it.id == transferId } }
        // Dismissing a row that ALREADY failed is not a cancellation. The
        // host has known it failed since the status report went out, and
        // history has to say what actually happened — otherwise clearing a
        // batch of red rows rewrites them all as "cancelled".
        val failure = transfer.phase as? TransferPhase.Failed
        if (failure == null) {
            reportStatus(transfer.hostId, transferId, StatusReport(TransferState.CANCELLED))
        }
        history.add(
            transferId = transferId,
            fileName = transfer.offer.fileName,
            sizeBytes = transfer.offer.sizeBytes,
            senderName = transfer.offer.senderName,
            outcome = if (failure != null) "failed" else "cancelled",
            errorMessage = failure?.message,
        )
        onTransferActivity()
    }

    /**
     * Resume or retry a failed / interrupted transfer.
     *
     * A manual retry goes through the queue like everything else — it just
     * jumps the backpressure timers, because the user pressing Retry is an
     * explicit "try now" and the host will say 503 again harmlessly if it is
     * still busy.
     */
    fun resume(transferId: String) {
        val transfer = _active.value.firstOrNull { it.id == transferId } ?: return
        when (transfer.phase) {
            is TransferPhase.Failed, is TransferPhase.HostBusy, TransferPhase.Interrupted -> {
                synchronized(stateLock) {
                    transferCooldowns.remove(transferId)
                    transferBusyStreak.remove(transferId)
                    busySince.remove(transferId)
                    rangeRestarts.remove(transferId)
                    hostCooldowns.remove(transfer.hostId)
                    hostBusyStreak.remove(transfer.hostId)
                }
                if (hasRecord(transferId)) {
                    // Already accepted on the host — just download again (ranged).
                    enqueue(transfer.offer, transfer.hostId)
                } else {
                    // Never accepted (e.g. the storage preflight blocked it).
                    scope.launch { acceptAndStart(transfer.offer, transfer.hostId) }
                }
            }
            else -> Unit
        }
    }

    /**
     * The user answered "gallery or files?", or is retrying after a storage
     * permission denial.
     */
    fun resolveSaveChoice(transferId: String, toGallery: Boolean) {
        val transfer = _active.value.firstOrNull { it.id == transferId } ?: return
        if (transfer.phase != TransferPhase.AwaitingSaveChoice &&
            transfer.phase != TransferPhase.StorageDenied
        ) {
            return
        }
        val file = pendingFile(transfer.offer)
        if (file == null) {
            setPhase(transferId, TransferPhase.Failed("Temp file went missing.", resumable = true))
            return
        }
        scope.launch {
            val kind = MediaSaver.mediaKind(transfer.offer.fileName, transfer.offer.mimeType)
            if (toGallery && kind != null) {
                saveToGallery(transfer.offer, transfer.hostId, file, kind)
            } else {
                saveAsFile(transfer.offer, transfer.hostId, file)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------------

    /** Free bytes on the volume the app's private storage lives on. */
    fun freeBytes(): Long? = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrNull()

    // -----------------------------------------------------------------------
    // Poll loops
    // -----------------------------------------------------------------------

    private fun reconcileLoops() {
        val pairedIds = paired.hosts.value.map { it.deviceId }.toSet()
        synchronized(stateLock) {
            val gone = pollJobs.keys.filterNot { it in pairedIds }
            gone.forEach { pollJobs.remove(it)?.cancel() }
            if (gone.isNotEmpty()) {
                _hostOnline.value = _hostOnline.value.filterKeys { it in pairedIds }
            }
            for (id in pairedIds) {
                if (pollJobs[id]?.isActive == true) continue
                pollJobs[id] = scope.launch { pollLoop(id) }
            }
        }
    }

    /**
     * Immortal per-host loop. Invariants, all hard-won on iOS and equally true
     * here:
     *
     *  - Online state comes ONLY from ping / long-poll success. Manual hosts
     *    have no mDNS presence, so discovery must never drive the indicator.
     *  - A 200 with an empty offers array is the normal long-poll timeout:
     *    mark online and re-poll immediately, no delay.
     *  - A long-poll error alone never marks the host offline. The poll socket
     *    dies routinely (process freeze, keep-alive reuse, a Wi-Fi blip) while
     *    the host is perfectly reachable, so we confirm with a fast ping first.
     *  - Real unreachability (refused / no route) backs off 1→2→4→8→15 s,
     *    capped, and retries forever — never a terminal stop.
     */
    /**
     * Supervises [pollLoopBody]. An unexpected throw restarts the loop instead
     * of ending it: a permanently dead poll loop is indistinguishable, from
     * the user's seat, from the PC being switched off.
     */
    private suspend fun pollLoop(hostId: String) {
        while (stillActive()) {
            try {
                pollLoopBody(hostId)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "poll loop for $hostId crashed; restarting", e)
                markOnline(hostId, false)
                delay(2_000)
            }
        }
    }

    private suspend fun pollLoopBody(hostId: String) {
        var backoff = 0.0
        while (stillActive()) {
            val client = clientFor(hostId)
            if (client == null) {
                // No stored endpoint/token yet — look again shortly.
                delay(5_000)
                continue
            }

            // Fast reachability probe whenever we are not already known-online
            // (loop start, after a failure, after a foreground restart) so a
            // reachable host turns green in ~1–2 s instead of after a full
            // 25 s poll cycle.
            if (_hostOnline.value[hostId] != true) {
                val alive = runCatching { client.ping() }.isSuccess
                if (!stillActive()) return
                if (alive) {
                    markOnline(hostId, true)
                    backoff = 0.0
                }
            }

            // §15.1: a receiver host has no outbox and answers 404. That is a
            // capability discovery, not a failure — poll it once, remember the
            // answer, and from then on just keep it marked online with a cheap
            // ping. Long-polling a peer that will never offer anything would
            // burn its CPU and ours forever.
            if (paired.host(hostId)?.receiveOnly == true) {
                val alive = runCatching { client.ping() }.isSuccess
                if (!stillActive()) return
                markOnline(hostId, alive)
                backoff = if (alive) {
                    0.0
                } else {
                    if (backoff <= 0.0) 1.0 else minOf(backoff * 2, BACKOFF_CAP_SECONDS)
                }
                delay(if (alive) RECEIVE_ONLY_PING_MS else (backoff * 1000).toLong())
                continue
            }

            try {
                val response = client.outboxLongPoll(POLL_WAIT_SECONDS)
                if (!stillActive()) return
                // A 200 — even an empty one — is the strongest possible
                // liveness signal, so it both clears the backoff and marks the
                // host online.
                markOnline(hostId, true)
                backoff = 0.0

                var senderName = paired.host(hostId)?.name.orEmpty()
                var freshOffers = 0
                for (offer in response.offers) {
                    if (offer.senderName.isNotBlank()) senderName = offer.senderName
                    if (handleOffer(offer, hostId)) freshOffers++
                }
                if (freshOffers > 0) {
                    notifier.notifyIncomingOffers(freshOffers, senderName.ifBlank { "Your PC" })
                }

                // §11: straight into the RAM inbox, and a notification that
                // names the sender and nothing else.
                if (response.messages.isNotEmpty()) {
                    messages.receive(response.messages)
                    // §11.3 — the card is still the ephemeral thing; this is
                    // the local 24-hour copy so the text can be read again
                    // after it is dismissed.
                    notes.addIncoming(response.messages)
                    val from = response.messages.lastOrNull()?.senderName
                        ?.takeIf { it.isNotBlank() }
                        ?: senderName
                    notifier.notifyMessage(from.ifBlank { "Your PC" })
                }

                // The host is answering again, so anything parked by a lost
                // connection can pick up where it stopped.
                resumeInterrupted(hostId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SendroHttpException) {
                if (!stillActive()) return
                if (e.status == 404) {
                    // §15.1 receive-only peer. Latch it and take the cheap
                    // branch above from now on.
                    Log.i(TAG, "host $hostId has no outbox; treating as receive-only")
                    paired.markReceiveOnly(hostId, true)
                    markOnline(hostId, true)
                    backoff = 0.0
                    continue
                }
                markOnline(hostId, false)
                backoff = if (backoff <= 0.0) 1.0 else minOf(backoff * 2, BACKOFF_CAP_SECONDS)
                delay((backoff * 1000).toLong())
            } catch (e: Exception) {
                if (!stillActive()) return
                // Distinguish "poll socket died" from "host actually gone".
                val alive = runCatching { client.ping() }.isSuccess
                if (!stillActive()) return
                if (alive) {
                    markOnline(hostId, true)
                    backoff = 0.0
                    continue // host is fine — re-poll immediately
                }
                markOnline(hostId, false)
                backoff = if (backoff <= 0.0) 1.0 else minOf(backoff * 2, BACKOFF_CAP_SECONDS)
                delay((backoff * 1000).toLong())
            }
        }
    }

    /** `while (stillActive())` reads better than the raw context lookup. */
    private suspend fun stillActive(): Boolean = currentCoroutineContext().isActive

    private fun markOnline(hostId: String, online: Boolean) {
        val current = _hostOnline.value
        if (current[hostId] == online) return
        _hostOnline.value = current + (hostId to online)
    }

    /**
     * @return true when this offer is newly waiting for the user. An
     *   auto-accepted offer returns false — it notifies on completion, not on
     *   arrival.
     */
    private fun handleOffer(offer: TransferOffer, hostId: String): Boolean {
        val id = offer.transferId

        // Already on screen as a transfer? Then this is not a new offer, it
        // is the host re-publishing one we know about — which happens for
        // exactly one reason: Retry was pressed on the PC, putting the
        // transfer back into Offered. Falling through the guards below did
        // nothing, which is why Retry looked broken. Consent was given once.
        val existing = _active.value.firstOrNull { it.id == id }
        if (existing != null) {
            when (existing.phase) {
                is TransferPhase.Failed, TransferPhase.Interrupted ->
                    reacceptAfterHostRetry(offer, hostId)
                else -> Unit
            }
            return false
        }

        synchronized(stateLock) {
            // §6.2: re-delivery is idempotent, the client dedupes by transferId.
            if (id in processedOfferIds) return false
        }
        if (_incoming.value.any { it.id == id }) return false

        if (autoAcceptAllowed(hostId)) {
            synchronized(stateLock) { processedOfferIds.add(id) }
            scope.launch { acceptAndStart(offer, hostId) }
            return false
        }
        _incoming.update { list ->
            list + IncomingOffer(
                offer = offer,
                hostId = hostId,
                receivedAtMs = System.currentTimeMillis(),
            )
        }
        return true
    }

    /**
     * "Accept automatically from trusted devices" is the RECEIVER'S choice,
     * and here "trusted" means one thing: a computer this phone deliberately
     * paired with and has not forgotten. Every host we long-poll is one of
     * those, so the setting alone decides.
     *
     * It used to also require the sender to have flagged the offer
     * `autoAccept`, which only happens for watch-folder files — so the
     * toggle appeared to do nothing for hand-picked files, which is exactly
     * the case people turn it on for.
     */
    private fun autoAcceptAllowed(hostId: String): Boolean {
        if (!settings.current.autoAcceptFromTrusted) return false
        return paired.host(hostId) != null
    }

    /**
     * The host re-offered something we already have a failed or paused row
     * for. Its state there is Offered again, so a bare ranged GET would be
     * refused with 409 — it has to be accepted once more first. The partial
     * file is left untouched, so this resumes from exactly where it stopped.
     *
     * Rate-limited: the outbox republishes an Offered transfer on every
     * poll, and a poll returns immediately while anything is pending, so
     * without the cooldown a transfer we keep failing to accept would spin.
     */
    private fun reacceptAfterHostRetry(offer: TransferOffer, hostId: String) {
        val transferId = offer.transferId
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            val last = lastReaccept[transferId] ?: 0L
            if (now - last < REACCEPT_COOLDOWN_MS) return
        }
        // Only this transfer's backoff. A host-wide cooldown means the PC
        // told us it is busy, and a re-offer is not evidence that it stopped
        // being busy — clearing it here would reset the backoff for every
        // other file queued behind it.
        //
        // `lastReaccept` is deliberately NOT part of forgetQueueState: that
        // runs on every terminal outcome, and dropping the stamp there would
        // let a transfer the host keeps re-offering, and we keep failing to
        // accept, re-accept on every single poll.
        forgetQueueState(transferId)
        synchronized(stateLock) { lastReaccept[transferId] = now }
        setPhase(transferId, TransferPhase.Preparing)
        // Drop the stale acceptance record; acceptAndStart re-adds it once
        // the host has accepted this round.
        removeRecord(transferId)
        scope.launch { acceptAndStart(offer, hostId) }
    }

    /**
     * Ids of every row that ended in failure — what a "Clear failed" control
     * sweeps away.
     */
    fun failedTransferIds(): List<String> =
        _active.value.filter { it.phase is TransferPhase.Failed }.map { it.id }

    /**
     * Dismiss every failed transfer at once instead of making the user tap
     * Remove on each row. Each goes through the same [cancel] path a single
     * Remove uses, so nothing is left behind.
     */
    fun clearFailed() {
        for (id in failedTransferIds()) cancel(id)
    }

    private fun resumeInterrupted(hostId: String) {
        for (transfer in _active.value) {
            if (transfer.hostId != hostId) continue
            if (transfer.phase != TransferPhase.Interrupted) continue
            val fresh = synchronized(stateLock) { autoResumed.add(transfer.id) }
            if (fresh) enqueue(transfer.offer, hostId)
        }
    }

    // -----------------------------------------------------------------------
    // Accept & download
    // -----------------------------------------------------------------------

    private suspend fun acceptAndStart(offer: TransferOffer, hostId: String) {
        // Storage preflight (§9 insufficient_storage).
        val free = freeBytes()
        if (free != null && free < offer.sizeBytes + STORAGE_MARGIN_BYTES) {
            reportStatus(hostId, offer.transferId, StatusReport(TransferState.FAILED, error = "insufficient_storage"))
            upsertActive(
                offer, hostId,
                TransferPhase.Failed(
                    "Not enough free space — needs " +
                        "${Format.bytes(offer.sizeBytes + STORAGE_MARGIN_BYTES)} free.",
                    resumable = true,
                ),
            )
            return
        }

        upsertActive(offer, hostId, TransferPhase.Preparing)

        val client = clientFor(hostId)
        if (client == null) {
            setPhase(offer.transferId, TransferPhase.Failed("Host is not reachable.", resumable = true))
            return
        }
        val problem = acceptWithBackoff(client, offer, hostId)
        if (problem != null) {
            setPhase(offer.transferId, TransferPhase.Failed(problem, resumable = true))
            return
        }
        addRecord(offer, hostId)
        enqueue(offer, hostId)
    }

    /**
     * §6.3 accept, with the same "503 is backpressure" ethic as the download.
     *
     * The accept itself is not gated by the host's concurrency setting, but
     * Pause and the §14 guest limit can still refuse it, and an offer that is
     * never accepted eventually expires — so this retries in place, honouring
     * the host's `Retry-After`, instead of dropping the offer on the floor.
     *
     * @return null on success, or a human-readable problem.
     */
    private suspend fun acceptWithBackoff(
        client: SendroClient,
        offer: TransferOffer,
        hostId: String,
    ): String? {
        val peerName = peerNameFor(hostId, offer)
        var attempt = 0
        while (true) {
            try {
                client.accept(offer.transferId)
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: SendroHttpException) {
                val text = e.explain(peerName, receiving = true)
                val retryable = e.disposition == HttpDisposition.BACKPRESSURE ||
                    e.disposition == HttpDisposition.RETRY_SOON ||
                    e.disposition == HttpDisposition.HOST_ERROR
                attempt++
                if (!retryable || attempt > ACCEPT_RETRY_LIMIT) return text
                val seconds = maxOf(
                    e.retryAfterSeconds ?: HttpSemantics.MIN_RETRY_SECONDS,
                    backoffSeconds(attempt),
                )
                // Show the wait rather than freezing on "Preparing…".
                setPhase(
                    offer.transferId,
                    TransferPhase.HostBusy(text, seconds, e.busyReason == BusyReason.PAUSED),
                )
                delay(seconds * 1000L)
            } catch (e: Exception) {
                Log.w(TAG, "accept failed for ${offer.transferId}", e)
                return "Couldn't tell $peerName to send it — the offer is still there, try again."
            }
        }
    }

    // -----------------------------------------------------------------------
    // The download queue (JOB 1A) and backpressure (JOB 1B)
    // -----------------------------------------------------------------------
    //
    // There is no separate list: the queue IS the subset of [_active] whose
    // phase is Queued or HostBusy, ordered by `queuedAtMs`. Keeping it there
    // means a queued transfer is a first-class visible row with a cancel
    // button, not an invisible entry in a side structure that can drift out of
    // sync with what the user sees.
    //
    //  - [enqueue] is the ONLY way a download starts. Accept, accept-all,
    //    manual retry, relaunch-resume and post-503 retries all funnel here.
    //  - [pump] is the only thing that calls [startDownload]. It is idempotent
    //    and re-entrancy safe: a second caller while it is running just sets a
    //    flag, and the running pass loops once more.
    //  - Cooldowns are keyed per HOST for "slots busy" / "paused" (they are
    //    properties of the host, so one 503 must not make ten transfers each
    //    hammer it) and per TRANSFER for 409 (a property of that one item).

    /** Put a transfer in line. Safe to call repeatedly for the same id. */
    private fun enqueue(offer: TransferOffer, hostId: String) {
        val transferId = offer.transferId
        synchronized(stateLock) {
            if (downloadJobs[transferId]?.isActive == true) return
        }
        val now = System.currentTimeMillis()
        _active.update { list ->
            if (list.any { it.id == transferId }) {
                list.map { row ->
                    if (row.id != transferId) {
                        row
                    } else {
                        row.copy(
                            phase = TransferPhase.Queued(0),
                            bytesPerSecond = 0.0,
                            etaSeconds = null,
                            // First enqueue wins, so a transfer that gets
                            // bounced by a 503 keeps its place in line instead
                            // of going to the back every time.
                            queuedAtMs = if (row.queuedAtMs > 0L) row.queuedAtMs else now,
                        )
                    }
                }
            } else {
                list + ActiveTransfer(
                    offer = offer,
                    hostId = hostId,
                    phase = TransferPhase.Queued(0),
                    bytesReceived = paths.partFile(transferId).length(),
                    queuedAtMs = now,
                )
            }
        }
        ensureTicker()
        pump()
    }

    /**
     * Start whatever may be started. Idempotent, re-entrancy safe, cheap when
     * there is nothing to do.
     */
    private fun pump() {
        synchronized(stateLock) {
            if (pumping) {
                pumpAgain = true
                return
            }
            pumping = true
            pumpAgain = false
        }
        try {
            while (true) {
                pumpOnce()
                // Clearing `pumping` and observing `pumpAgain` happen in the
                // same critical section, so a request that arrives while we
                // are finishing can never be lost.
                val again = synchronized(stateLock) {
                    if (pumpAgain) {
                        pumpAgain = false
                        true
                    } else {
                        pumping = false
                        false
                    }
                }
                if (!again) return
            }
        } catch (t: Throwable) {
            synchronized(stateLock) {
                pumping = false
                pumpAgain = false
            }
            throw t
        }
    }

    private fun pumpOnce() {
        val now = System.currentTimeMillis()
        val starts = ArrayList<Pair<TransferOffer, String>>()
        synchronized(stateLock) {
            // The authoritative "how many are streaming" is the job map, not
            // the phase, because a job exists from the instant it is launched.
            var running = downloadJobs.count { it.value.isActive }
            val waiting = _active.value
                .filter { it.phase.isPending }
                .sortedBy { it.queuedAtMs }
            for (row in waiting) {
                if (running >= MAX_CONCURRENT_DOWNLOADS) break
                if (downloadJobs[row.id]?.isActive == true) continue
                // A host on cooldown is skipped, not blocking: another host's
                // transfer further down the line may still go.
                if (cooldownUntilLocked(row.hostId, row.id) > now) continue
                starts += row.offer to row.hostId
                running++
            }
        }
        starts.forEach { (offer, hostId) -> startDownload(offer, hostId) }
        renumberQueue()
    }

    /** Give every waiting row its 1-based place in line. */
    private fun renumberQueue() {
        val order = _active.value.filter { it.phase.isPending }.sortedBy { it.queuedAtMs }
        if (order.isEmpty()) return
        val positions = HashMap<String, Int>(order.size)
        order.forEachIndexed { index, row -> positions[row.id] = index + 1 }
        _active.update { list ->
            list.map { row ->
                val phase = row.phase
                val position = positions[row.id]
                if (phase is TransferPhase.Queued && position != null && phase.position != position) {
                    row.copy(phase = TransferPhase.Queued(position))
                } else {
                    row
                }
            }
        }
    }

    private fun cooldownUntilLocked(hostId: String, transferId: String): Long =
        maxOf(hostCooldowns[hostId] ?: 0L, transferCooldowns[transferId] ?: 0L)

    /**
     * One-second heartbeat, alive only while something is waiting.
     *
     * It does two jobs: count the "retrying in Ns" label down so the user can
     * see the app is still trying, and re-pump so an expired cooldown is
     * noticed without anything else having to happen.
     */
    private fun ensureTicker() {
        synchronized(stateLock) {
            if (tickerJob?.isActive == true) return
            tickerJob = scope.launch { tickLoop() }
        }
    }

    private suspend fun tickLoop() {
        while (stillActive()) {
            val now = System.currentTimeMillis()
            for (row in _active.value) {
                val phase = row.phase
                if (phase !is TransferPhase.HostBusy) continue
                val until = synchronized(stateLock) { cooldownUntilLocked(row.hostId, row.id) }
                val seconds = if (until <= now) 0 else (((until - now) + 999L) / 1000L).toInt()
                if (seconds != phase.retryInSeconds) {
                    setPhase(row.id, phase.copy(retryInSeconds = seconds))
                }
            }
            pump()
            val done = synchronized(stateLock) {
                if (_active.value.none { it.phase.isPending }) {
                    tickerJob = null
                    true
                } else {
                    false
                }
            }
            if (done) return
            delay(1_000)
        }
        synchronized(stateLock) { tickerJob = null }
    }

    /**
     * Record a host-wide "come back later" (503 from the concurrency gate, or
     * Pause on the PC) and park the transfer on an amber countdown.
     *
     * The delay is the larger of what the host asked for and our own
     * exponential-ish backoff, so a host that keeps saying "2 seconds" while
     * it is genuinely saturated does not get polled twice a second forever.
     */
    private fun noteHostBusy(hostId: String, transferId: String, outcome: DownloadTask.Outcome.Busy) {
        val now = System.currentTimeMillis()
        val delaySeconds = synchronized(stateLock) {
            val streak = (hostBusyStreak[hostId] ?: 0) + 1
            hostBusyStreak[hostId] = streak
            val mine = backoffSeconds(streak)
            val seconds = maxOf(outcome.retryAfterSeconds ?: HttpSemantics.MIN_RETRY_SECONDS, mine)
            hostCooldowns[hostId] = now + seconds * 1000L
            seconds
        }
        parkAsBusy(
            transferId = transferId,
            message = outcome.message,
            delaySeconds = delaySeconds,
            paused = outcome.reason == BusyReason.PAUSED,
            now = now,
        )
    }

    /** The 409 equivalent: this ONE transfer is not ready, the host is fine. */
    private fun noteTransferNotReady(transferId: String, message: String, retryAfterSeconds: Int?) {
        val now = System.currentTimeMillis()
        val delaySeconds = synchronized(stateLock) {
            val streak = (transferBusyStreak[transferId] ?: 0) + 1
            transferBusyStreak[transferId] = streak
            val seconds = maxOf(retryAfterSeconds ?: HttpSemantics.MIN_RETRY_SECONDS, backoffSeconds(streak))
            transferCooldowns[transferId] = now + seconds * 1000L
            seconds
        }
        parkAsBusy(transferId, message, delaySeconds, paused = false, now = now)
    }

    /** 1, 2, 4, 8, 16, 30, 30… seconds — clamped by [HttpSemantics]. */
    private fun backoffSeconds(streak: Int): Int {
        val exponent = (streak - 1).coerceIn(0, 5)
        val raw = HttpSemantics.MIN_RETRY_SECONDS.toLong() shl exponent
        return HttpSemantics.clampRetry(raw)
    }

    /**
     * Amber, not red. Unless the transfer has been getting nothing but this
     * for [GIVE_UP_AFTER_MS] — then it becomes a failure the user can still
     * retry, because a permanent silent countdown is its own kind of lie.
     */
    private fun parkAsBusy(
        transferId: String,
        message: String,
        delaySeconds: Int,
        paused: Boolean,
        now: Long,
    ) {
        val since = synchronized(stateLock) {
            val existing = busySince[transferId]
            if (existing == null) {
                busySince[transferId] = now
                now
            } else {
                existing
            }
        }
        if (now - since >= GIVE_UP_AFTER_MS) {
            synchronized(stateLock) {
                busySince.remove(transferId)
                transferCooldowns.remove(transferId)
                transferBusyStreak.remove(transferId)
            }
            setPhase(
                transferId,
                TransferPhase.Failed(
                    if (paused) {
                        "$message Nothing was lost — press Retry once transfers are resumed."
                    } else {
                        "$message It has stayed busy for a while — press Retry to try again."
                    },
                    resumable = true,
                ),
            )
            pump()
            return
        }
        setPhase(transferId, TransferPhase.HostBusy(message, delaySeconds, paused))
        ensureTicker()
        pump()
    }

    /** A slot was granted / the file landed: this host is not busy after all. */
    private fun clearBusyState(hostId: String, transferId: String) {
        synchronized(stateLock) {
            hostCooldowns.remove(hostId)
            hostBusyStreak.remove(hostId)
            transferCooldowns.remove(transferId)
            transferBusyStreak.remove(transferId)
            busySince.remove(transferId)
        }
    }

    private fun forgetQueueState(transferId: String) {
        synchronized(stateLock) {
            transferCooldowns.remove(transferId)
            transferBusyStreak.remove(transferId)
            busySince.remove(transferId)
            rangeRestarts.remove(transferId)
        }
    }

    private fun startDownload(offer: TransferOffer, hostId: String) {
        val transferId = offer.transferId
        synchronized(stateLock) {
            if (downloadJobs[transferId]?.isActive == true) return
        }
        val client = clientFor(hostId)
        if (client == null) {
            setPhase(transferId, TransferPhase.Failed("Host is not reachable.", resumable = true))
            return
        }

        upsertActive(offer, hostId, TransferPhase.Preparing)
        onTransferActivity()

        val peerName = peerNameFor(hostId, offer)
        val job = scope.launch {
            val task = DownloadTask(offer, paths.partFile(transferId), client, peerName)
            var lastStatusMs = 0L
            val outcome = task.run(
                onBegan = { resumedFrom ->
                    // Bytes are moving, so whatever the host said last time
                    // about being full is history.
                    clearBusyState(hostId, transferId)
                    setPhase(transferId, TransferPhase.Downloading)
                    setBytes(transferId, resumedFrom)
                    reportStatus(
                        hostId, transferId,
                        StatusReport(TransferState.DOWNLOADING, bytesReceived = resumedFrom),
                    )
                },
                onProgress = { progress ->
                    applyProgress(transferId, progress)
                    val now = System.currentTimeMillis()
                    if (now - lastStatusMs >= 1_000) {
                        lastStatusMs = now
                        reportStatus(
                            hostId, transferId,
                            StatusReport(
                                TransferState.DOWNLOADING,
                                bytesReceived = progress.bytesReceived,
                            ),
                        )
                    }
                },
                onVerifying = {
                    setPhase(transferId, TransferPhase.Verifying)
                    reportStatus(hostId, transferId, StatusReport(TransferState.VERIFYING))
                },
            )
            synchronized(stateLock) { downloadJobs.remove(transferId) }
            handleOutcome(outcome, offer, hostId)
            onTransferActivity()
            // A slot just freed up, whatever the outcome was.
            pump()
        }
        synchronized(stateLock) { downloadJobs[transferId] = job }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                synchronized(stateLock) { downloadJobs.remove(transferId) }
                // A cancelled download that still has an active row was
                // interrupted, not cancelled by the user (cancel() removes the
                // row itself). Park it so a later poll can resume it.
                if (_active.value.any { it.id == transferId }) {
                    setPhase(transferId, TransferPhase.Interrupted)
                }
                onTransferActivity()
                pump()
            }
        }
    }

    /** The best name we have for the device on the other end of a transfer. */
    private fun peerNameFor(hostId: String, offer: TransferOffer): String =
        paired.host(hostId)?.name?.takeIf { it.isNotBlank() }
            ?: offer.senderName.takeIf { it.isNotBlank() }
            ?: "the sender"

    private suspend fun handleOutcome(
        outcome: DownloadTask.Outcome,
        offer: TransferOffer,
        hostId: String,
    ) {
        val transferId = offer.transferId
        when (outcome) {
            is DownloadTask.Outcome.Verified -> {
                clearBusyState(hostId, transferId)
                forgetQueueState(transferId)
                setBytes(transferId, offer.sizeBytes)
                reportStatus(
                    hostId, transferId,
                    StatusReport(TransferState.VERIFIED, bytesReceived = offer.sizeBytes),
                )
                routeSave(offer, hostId, outcome.file)
            }

            DownloadTask.Outcome.IntegrityMismatch -> {
                paths.partFile(transferId).delete()
                removeRecord(transferId)
                forgetQueueState(transferId)
                reportStatus(hostId, transferId, StatusReport(TransferState.FAILED, error = "integrity"))
                setPhase(
                    transferId,
                    TransferPhase.Failed(
                        "Integrity check failed — the received bytes don't match the " +
                            "sender's SHA-256. Retry to download again.",
                        resumable = true,
                    ),
                )
                history.add(
                    transferId = transferId,
                    fileName = offer.fileName,
                    sizeBytes = offer.sizeBytes,
                    senderName = offer.senderName,
                    outcome = "failed",
                    errorMessage = "integrity",
                )
                notifier.notifyTransferFailed(transferId, offer.fileName, "integrity check")
            }

            // ---- backpressure: the host is fine, it is just full ----------
            is DownloadTask.Outcome.Busy -> {
                // Deliberately NO status report and NO notification: nothing
                // has failed, and telling the host "failed" would make it drop
                // a transfer we still very much want.
                Log.i(TAG, "host $hostId busy (${outcome.reason}) for $transferId")
                noteHostBusy(hostId, transferId, outcome)
            }

            is DownloadTask.Outcome.NotReady -> {
                Log.i(TAG, "host $hostId not ready for $transferId")
                noteTransferNotReady(transferId, outcome.message, outcome.retryAfterSeconds)
            }

            // ---- our resume offset did not line up: start over -------------
            is DownloadTask.Outcome.RangeMismatch -> {
                val restarts = synchronized(stateLock) {
                    val next = (rangeRestarts[transferId] ?: 0) + 1
                    rangeRestarts[transferId] = next
                    next
                }
                paths.partFile(transferId).delete()
                setBytes(transferId, 0)
                if (restarts > RANGE_RESTART_LIMIT) {
                    reportStatus(
                        hostId, transferId,
                        StatusReport(TransferState.FAILED, error = "range_mismatch"),
                    )
                    setPhase(
                        transferId,
                        TransferPhase.Failed(
                            "${outcome.message} That kept happening, so it stopped — " +
                                "press Retry to take the file from the beginning.",
                            resumable = true,
                        ),
                    )
                    notifier.notifyTransferFailed(transferId, offer.fileName, "resume mismatch")
                } else {
                    enqueue(offer, hostId)
                }
            }

            // ---- retrying genuinely cannot help ----------------------------
            is DownloadTask.Outcome.Unrecoverable -> {
                removeRecord(transferId)
                forgetQueueState(transferId)
                reportStatus(
                    hostId, transferId,
                    StatusReport(TransferState.FAILED, error = outcome.message),
                )
                setPhase(transferId, TransferPhase.Failed(outcome.message, resumable = false))
                history.add(
                    transferId = transferId,
                    fileName = offer.fileName,
                    sizeBytes = offer.sizeBytes,
                    senderName = offer.senderName,
                    outcome = "failed",
                    errorMessage = outcome.message,
                )
                notifier.notifyTransferFailed(transferId, offer.fileName, outcome.message)
            }

            is DownloadTask.Outcome.Failed -> {
                reportStatus(
                    hostId, transferId,
                    StatusReport(TransferState.FAILED, error = outcome.message),
                )
                setPhase(transferId, TransferPhase.Failed(outcome.message, outcome.resumable))
                notifier.notifyTransferFailed(transferId, offer.fileName, outcome.message)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Save routing
    // -----------------------------------------------------------------------

    private suspend fun routeSave(offer: TransferOffer, hostId: String, file: File) {
        val kind = MediaSaver.mediaKind(offer.fileName, offer.mimeType)
        if (kind == null) {
            saveAsFile(offer, hostId, file)
            return
        }
        when (settings.current.saveMediaToGallery) {
            SaveMediaMode.ALWAYS -> saveToGallery(offer, hostId, file, kind)
            SaveMediaMode.ASK -> setPhase(offer.transferId, TransferPhase.AwaitingSaveChoice)
            SaveMediaMode.NEVER -> saveAsFile(offer, hostId, file)
        }
    }

    private suspend fun saveToGallery(
        offer: TransferOffer,
        hostId: String,
        file: File,
        kind: MediaKind,
    ) {
        setPhase(offer.transferId, TransferPhase.Saving)
        reportStatus(hostId, offer.transferId, StatusReport(TransferState.SAVING))

        // Stage the verified bytes under the real name first. MediaStore takes
        // DISPLAY_NAME from what we give it, but the extension is what every
        // gallery app uses to decide the type — handing it "<uuid>.part" is
        // how you end up with unopenable files.
        val staged = stageForSave(offer, file)
        if (staged == null) {
            failSave(offer, hostId, "Temp file went missing.")
            return
        }

        val settingsNow = settings.current
        val result = mediaSaver.saveToGallery(
            source = staged,
            displayName = offer.fileName,
            kind = kind,
            declaredMimeType = offer.mimeType,
            useAlbum = settingsNow.addToSendroAlbum,
            moveFile = settingsNow.deleteTempAfterSave,
        )

        when (result) {
            is SaveResult.Gallery -> {
                var keptName: String? = null
                if (!settingsNow.deleteTempAfterSave) {
                    val kept = mediaSaver.saveToAppStore(staged, offer.fileName, moveFile = true)
                    if (kept is SaveResult.Files) keptName = kept.file.name
                }
                finishCompleted(
                    offer, hostId,
                    savedTo = "photos",
                    localName = keptName,
                    mediaUri = result.uri.toString(),
                )
            }

            SaveResult.NeedsStoragePermission -> {
                // Surface it instead of silently rerouting — the user expects
                // media in their gallery. The bytes stay staged for a retry
                // once the permission is granted, or can be sent to Files.
                reportStatus(
                    hostId, offer.transferId,
                    StatusReport(TransferState.FAILED, error = "storage permission denied"),
                )
                setPhase(offer.transferId, TransferPhase.StorageDenied)
            }

            is SaveResult.Failed -> {
                // Never lose verified bytes because the gallery said no.
                Log.w(TAG, "gallery save failed: ${result.message}; falling back to Files")
                saveAsFile(offer, hostId, staged)
            }

            is SaveResult.Files -> finishCompleted(
                offer, hostId, savedTo = "files", localName = result.file.name,
                mediaUri = null,
            )
        }
    }

    private suspend fun saveAsFile(offer: TransferOffer, hostId: String, file: File) {
        setPhase(offer.transferId, TransferPhase.Saving)
        reportStatus(hostId, offer.transferId, StatusReport(TransferState.SAVING))

        val staged = stageForSave(offer, file)
        if (staged == null) {
            failSave(offer, hostId, "Temp file went missing.")
            return
        }

        // Non-media goes to Download/Sendro so the user's own file manager can
        // see it, with the app store as the fallback that can never fail.
        val result = mediaSaver.saveToDownloads(
            source = staged,
            displayName = offer.fileName,
            declaredMimeType = offer.mimeType,
            useAlbum = settings.current.addToSendroAlbum,
            moveFile = true,
        )
        when (result) {
            is SaveResult.Gallery ->
                finishCompleted(offer, hostId, "files", null, result.uri.toString())
            is SaveResult.Files ->
                finishCompleted(offer, hostId, "files", result.file.name, null)
            SaveResult.NeedsStoragePermission -> {
                val kept = mediaSaver.saveToAppStore(staged, offer.fileName, moveFile = true)
                if (kept is SaveResult.Files) {
                    finishCompleted(offer, hostId, "files", kept.file.name, null)
                } else {
                    failSave(offer, hostId, "Could not save the file.")
                }
            }
            is SaveResult.Failed -> failSave(offer, hostId, result.message)
        }
    }

    private fun failSave(offer: TransferOffer, hostId: String, message: String) {
        reportStatus(
            hostId, offer.transferId,
            StatusReport(TransferState.FAILED, error = "save failed: $message"),
        )
        setPhase(offer.transferId, TransferPhase.Failed("Could not save: $message", resumable = false))
    }

    /**
     * Move the verified `.part` to its staged name. Idempotent: safe to call
     * again after a failed save attempt (the file is already staged).
     */
    private suspend fun stageForSave(offer: TransferOffer, file: File): File? =
        withContext(Dispatchers.IO) {
            val staged = paths.stagedFile(offer.transferId, offer.fileName)
            if (file.absolutePath == staged.absolutePath) return@withContext staged
            if (file.isFile) {
                if (staged.exists()) staged.delete()
                if (file.renameTo(staged)) return@withContext staged
                // Rename can fail across devices; fall back to a stream copy.
                return@withContext runCatching {
                    file.copyStreamTo(staged)
                    file.delete()
                    staged
                }.getOrNull()
            }
            if (staged.isFile) staged else null
        }

    /** Wherever this transfer's verified bytes currently live, if anywhere. */
    private fun pendingFile(offer: TransferOffer): File? {
        val staged = paths.stagedFile(offer.transferId, offer.fileName)
        if (staged.isFile) return staged
        val part = paths.partFile(offer.transferId)
        return if (part.isFile) part else null
    }

    private fun finishCompleted(
        offer: TransferOffer,
        hostId: String,
        savedTo: String,
        localName: String?,
        mediaUri: String?,
    ) {
        reportStatus(
            hostId, offer.transferId,
            StatusReport(TransferState.COMPLETED, bytesReceived = offer.sizeBytes, savedTo = savedTo),
        )
        history.add(
            transferId = offer.transferId,
            fileName = offer.fileName,
            sizeBytes = offer.sizeBytes,
            senderName = offer.senderName,
            outcome = "completed",
            savedTo = savedTo,
            localName = localName,
            mediaUri = mediaUri,
        )
        removeRecord(offer.transferId)
        synchronized(stateLock) { lastReaccept.remove(offer.transferId) }
        _active.update { list -> list.filterNot { it.id == offer.transferId } }
        notifier.notifyTransferFinished(offer.transferId, offer.fileName, savedTo)
        onTransferActivity()
    }

    // -----------------------------------------------------------------------
    // Active list plumbing
    // -----------------------------------------------------------------------

    private fun upsertActive(offer: TransferOffer, hostId: String, phase: TransferPhase) {
        _active.update { list ->
            if (list.any { it.id == offer.transferId }) {
                list.map { if (it.id == offer.transferId) it.copy(phase = phase) else it }
            } else {
                list + ActiveTransfer(offer = offer, hostId = hostId, phase = phase)
            }
        }
    }

    /** Replace one row in place; a no-op when the row is already gone. */
    private fun mutateActive(transferId: String, block: (ActiveTransfer) -> ActiveTransfer) {
        _active.update { list ->
            if (list.none { it.id == transferId }) list
            else list.map { if (it.id == transferId) block(it) else it }
        }
    }

    private fun setPhase(transferId: String, phase: TransferPhase) = mutateActive(transferId) {
        // Nothing is moving in any of these, so a stale speed / ETA would be
        // the row telling a small lie while it waits.
        if (phase is TransferPhase.Failed || phase.isPending) {
            it.copy(phase = phase, bytesPerSecond = 0.0, etaSeconds = null)
        } else {
            it.copy(phase = phase)
        }
    }

    private fun setBytes(transferId: String, bytes: Long) = mutateActive(transferId) {
        it.copy(bytesReceived = bytes)
    }

    private fun applyProgress(transferId: String, progress: DownloadTask.Progress) =
        mutateActive(transferId) {
            it.copy(
                bytesReceived = progress.bytesReceived,
                bytesPerSecond = progress.bytesPerSecond,
                etaSeconds = progress.etaSeconds,
            )
        }

    // -----------------------------------------------------------------------
    // Status reports (client -> host, fire and forget)
    // -----------------------------------------------------------------------

    private fun reportStatus(hostId: String, transferId: String, report: StatusReport) {
        scope.launch {
            runCatching { clientFor(hostId)?.reportStatus(transferId, report) }
        }
    }

    fun clientFor(hostId: String): SendroClient? {
        val host = paired.host(hostId) ?: return null
        val token = tokens.token(hostId) ?: return null
        return SendroClient.create(host.lastHost, host.lastPort, token)
    }

    // -----------------------------------------------------------------------
    // In-flight persistence (survives process death)
    // -----------------------------------------------------------------------

    private fun restoreInFlight() {
        val records = loadRecords()
        if (records.isEmpty()) return
        val restored = records.map { record ->
            val size = paths.partFile(record.offer.transferId).length()
            ActiveTransfer(
                offer = record.offer,
                hostId = record.hostId,
                phase = TransferPhase.Interrupted,
                bytesReceived = size,
            )
        }
        _active.update { list -> list + restored }
        synchronized(stateLock) {
            records.forEach { processedOfferIds.add(it.offer.transferId) }
        }
    }

    private fun loadRecords(): List<InFlightRecord> {
        val file = paths.inFlightStateFile
        if (!file.isFile) return emptyList()
        return runCatching { SendroJson.decodeFromString<List<InFlightRecord>>(file.readText()) }
            .getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveRecords(records: List<InFlightRecord>) {
        runCatching { paths.inFlightStateFile.writeText(SendroJson.encodeToString(records)) }
    }

    @Synchronized
    private fun addRecord(offer: TransferOffer, hostId: String) {
        val records = loadRecords().filterNot { it.offer.transferId == offer.transferId }
        saveRecords(records + InFlightRecord(offer, hostId))
    }

    @Synchronized
    private fun removeRecord(transferId: String) {
        saveRecords(loadRecords().filterNot { it.offer.transferId == transferId })
    }

    private fun hasRecord(transferId: String): Boolean =
        loadRecords().any { it.offer.transferId == transferId }
}

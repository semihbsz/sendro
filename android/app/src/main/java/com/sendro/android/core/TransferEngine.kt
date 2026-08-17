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
}

data class ActiveTransfer(
    val offer: TransferOffer,
    val hostId: String,
    val phase: TransferPhase,
    val bytesReceived: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val etaSeconds: Int? = null,
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
    private val notifier: Notifier,
    private val onTransferActivity: () -> Unit,
) {

    companion object {
        private const val TAG = "SendroEngine"

        /** 200 MB safety margin on top of the file size for storage preflight. */
        const val STORAGE_MARGIN_BYTES = 200L * 1024 * 1024

        /** §12 — never more than this many accept calls in flight. */
        const val BULK_ACCEPT_CONCURRENCY = 4

        private const val POLL_WAIT_SECONDS = 25
        private const val BACKOFF_CAP_SECONDS = 15.0

        /** How often a receive-only peer (§15.1) is re-pinged for presence. */
        private const val RECEIVE_ONLY_PING_MS = 10_000L
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
        try {
            client.accept(transferId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failBulkItem(transferId, "Couldn't accept: ${e.sendroMessage()}")
            return
        }
        // Declined or accepted individually while this call was in flight.
        if (_incoming.value.none { it.id == transferId }) return
        _incoming.update { list -> list.filterNot { it.id == transferId } }
        synchronized(stateLock) { processedOfferIds.add(transferId) }
        addRecord(offer, item.hostId)
        startDownload(offer, item.hostId)
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
        _active.update { list -> list.filterNot { it.id == transferId } }
        reportStatus(transfer.hostId, transferId, StatusReport(TransferState.CANCELLED))
        history.add(
            transferId = transferId,
            fileName = transfer.offer.fileName,
            sizeBytes = transfer.offer.sizeBytes,
            senderName = transfer.offer.senderName,
            outcome = "cancelled",
        )
        onTransferActivity()
    }

    /** Resume or retry a failed / interrupted transfer. */
    fun resume(transferId: String) {
        val transfer = _active.value.firstOrNull { it.id == transferId } ?: return
        when (transfer.phase) {
            is TransferPhase.Failed, TransferPhase.Interrupted -> {
                if (hasRecord(transferId)) {
                    // Already accepted on the host — just download again (ranged).
                    startDownload(transfer.offer, transfer.hostId)
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
    private suspend fun pollLoop(hostId: String) {
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
        synchronized(stateLock) {
            // §6.2: re-delivery is idempotent, the client dedupes by transferId.
            if (id in processedOfferIds) return false
        }
        if (_incoming.value.any { it.id == id }) return false
        if (_active.value.any { it.id == id }) return false

        if (offer.autoAccept && settings.current.autoAcceptFromTrusted) {
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

    private fun resumeInterrupted(hostId: String) {
        for (transfer in _active.value) {
            if (transfer.hostId != hostId) continue
            if (transfer.phase != TransferPhase.Interrupted) continue
            val fresh = synchronized(stateLock) { autoResumed.add(transfer.id) }
            if (fresh) startDownload(transfer.offer, hostId)
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
        try {
            client.accept(offer.transferId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setPhase(
                offer.transferId,
                TransferPhase.Failed("Could not accept: ${e.sendroMessage()}", resumable = true),
            )
            return
        }
        addRecord(offer, hostId)
        startDownload(offer, hostId)
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

        val job = scope.launch {
            val task = DownloadTask(offer, paths.partFile(transferId), client)
            var lastStatusMs = 0L
            val outcome = task.run(
                onBegan = { resumedFrom ->
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
            }
        }
    }

    private suspend fun handleOutcome(
        outcome: DownloadTask.Outcome,
        offer: TransferOffer,
        hostId: String,
    ) {
        val transferId = offer.transferId
        when (outcome) {
            is DownloadTask.Outcome.Verified -> {
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
        if (phase is TransferPhase.Failed) {
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

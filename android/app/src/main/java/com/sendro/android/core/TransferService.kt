package com.sendro.android.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.sendro.android.SendroApplication
import com.sendro.android.core.host.ReceiverHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The Android answer to the iOS Live Activity: a foreground service with an
 * ongoing notification showing file name, progress and speed.
 *
 * It is what keeps a download running when the user leaves Sendro. Without it
 * the process is a background app and the OS will suspend the sockets within
 * seconds; there is no "background URLSession" on Android to fall back to.
 *
 * The service owns NO transfer state. [TransferEngine] runs in the application
 * scope and simply asks this service to exist while `active` is non-empty;
 * the service mirrors the engine's flows into the notification and stops
 * itself when the last transfer ends. That way a swipe-away of the app, or the
 * service being killed and restarted, can never lose or duplicate a transfer.
 */
class TransferService : Service() {

    private lateinit var scope: CoroutineScope
    private var started = false

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? SendroApplication
        if (app == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!started) {
            started = true
            // Post the placeholder immediately: on API 26+ a foreground
            // service that does not call startForeground within ~5 s is killed
            // with ForegroundServiceDidNotStartInTimeException.
            promote(app.notifier.buildProgressNotification(
                title = "Sendro",
                text = "Preparing…",
                progressPercent = null,
                indeterminate = true,
            ))
            observe(app)
        }
        return START_STICKY
    }

    private fun observe(app: SendroApplication) {
        combine(
            app.transferEngine.active,
            app.uploadEngine.items,
            app.receiverHost.state,
        ) { downloads, uploads, host -> Triple(downloads, uploads, host) }
            .onEach { (downloads, uploads, host) ->
                val runningDownload = downloads.firstOrNull { it.phase.isBusy }
                val runningUpload = uploads.firstOrNull { it.phase.isBusy }
                when {
                    runningDownload != null ->
                        promote(notificationFor(app, runningDownload, downloads.size))
                    runningUpload != null ->
                        promote(notificationFor(app, runningUpload, uploads.size))
                    // §15: the receiver host outlives any individual transfer.
                    // As long as it is listening the process must stay
                    // foreground, or Android freezes it and a phone's upload
                    // hits a dead socket.
                    host is ReceiverHost.State.Running -> promote(
                        app.notifier.buildProgressNotification(
                            title = "Ready to receive",
                            text = host.addresses.firstOrNull()
                                ?.let { "Sendro is listening on $it:${host.port}" }
                                ?: "Sendro is listening on port ${host.port}",
                            progressPercent = null,
                            indeterminate = false,
                        ),
                    )
                    else -> stopEverything()
                }
            }
            .launchIn(scope)
    }

    private fun notificationFor(
        app: SendroApplication,
        transfer: ActiveTransfer,
        total: Int,
    ) = app.notifier.buildProgressNotification(
        title = transfer.offer.fileName,
        text = buildString {
            append(transfer.phase.shortLabel)
            if (transfer.phase is TransferPhase.Downloading) {
                append(" · ")
                append(Format.bytes(transfer.bytesReceived))
                append(" / ")
                append(Format.bytes(transfer.offer.sizeBytes))
                if (transfer.bytesPerSecond > 1) {
                    append(" · ")
                    append(Format.speed(transfer.bytesPerSecond))
                }
            }
            if (total > 1) append("  (+${total - 1} more)")
        },
        progressPercent = (transfer.fraction * 100).toInt(),
        indeterminate = transfer.phase !is TransferPhase.Downloading,
    )

    private fun notificationFor(
        app: SendroApplication,
        item: UploadItem,
        total: Int,
    ) = app.notifier.buildProgressNotification(
        title = item.fileName,
        text = buildString {
            append(item.phase.label)
            if (item.phase is UploadPhase.Uploading) {
                append(" · ")
                append(Format.bytes(item.bytesSent))
                append(" / ")
                append(Format.bytes(item.sizeBytes))
                if (item.bytesPerSecond > 1) {
                    append(" · ")
                    append(Format.speed(item.bytesPerSecond))
                }
            }
            if (total > 1) append("  (+${total - 1} more)")
        },
        progressPercent = (item.fraction * 100).toInt(),
        indeterminate = item.phase !is UploadPhase.Uploading,
    )

    private fun promote(notification: android.app.Notification) {
        try {
            ServiceCompat.startForeground(
                this,
                Notifier.Ids.PROGRESS,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            // API 31+ throws ForegroundServiceStartNotAllowedException when
            // the app is in the background without an exemption, and API 34
            // throws if the type's permission is missing. Never crash the
            // transfer over the notification: the download keeps running in
            // the application scope, it just loses its lifeline if the user
            // leaves the app.
            Log.w(TAG, "startForeground refused", e)
        }
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        started = false
        if (::scope.isInitialized) scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SendroService"

        /**
         * Ask the service to exist. Safe to call repeatedly; the service is a
         * singleton and ignores redundant starts.
         *
         * On API 31+ a background app cannot start a foreground service at all
         * (`ForegroundServiceStartNotAllowedException`), which is exactly the
         * case where we do not need one — a transfer only ever begins from a
         * user action or while the app is visible.
         */
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "could not start transfer service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}

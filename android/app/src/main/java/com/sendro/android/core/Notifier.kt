package com.sendro.android.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sendro.android.MainActivity
import com.sendro.android.R

/** Where a notification tap should land. */
enum class NotificationRoute { RECEIVE, SEND, LIBRARY }

/** Which surface the user is looking at right now — used to stay quiet. */
enum class AppSurface { RECEIVE, SEND, LIBRARY, FLIGHT, BACKGROUND }

/**
 * Local notifications. No push server, no FCM, no Play Services — everything
 * here is posted by the app itself, for things that arrive while the app (or
 * its foreground service) is running.
 *
 * PRIVACY (§11.3): a text message NEVER puts its text in a notification body.
 * The body says "Sent you text" and nothing else — the same rule as iOS.
 */
class Notifier(
    private val context: Context,
    private val settings: SettingsStore,
) {

    object Channels {
        const val OFFERS = "sendro.offers"
        const val TRANSFERS = "sendro.transfers"
        const val MESSAGES = "sendro.messages"
        const val PROGRESS = "sendro.progress"
    }

    object Ids {
        const val OFFERS = 1001
        const val MESSAGE = 1002
        const val PROGRESS = 1003
        const val UPDATE = 1004
        const val PAIRING = 1005
        /** Finished/failed transfers get an id derived from the transfer. */
        fun forTransfer(transferId: String): Int = 2000 + (transferId.hashCode() and 0x3FFF)
    }

    @Volatile
    private var surface: AppSurface = AppSurface.BACKGROUND

    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    fun updateSurface(surface: AppSurface) {
        this.surface = surface
    }

    /**
     * API 33+ requires the runtime POST_NOTIFICATIONS grant; below that a
     * notification always posts. Never throw — a missing grant must degrade
     * to "no banner", never to a crash mid-transfer.
     */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val service = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(
                Channels.OFFERS,
                context.getString(R.string.notification_channel_offers),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "A paired computer wants to send you files." },
            NotificationChannel(
                Channels.TRANSFERS,
                context.getString(R.string.notification_channel_transfers),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Finished and failed transfers." },
            NotificationChannel(
                Channels.MESSAGES,
                context.getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Someone sent you text. The text itself is never shown here."
            },
            NotificationChannel(
                Channels.PROGRESS,
                context.getString(R.string.notification_channel_progress),
                // Silent: this one is an ongoing progress bar, not an event.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "The ongoing notification that keeps a transfer alive."
                setShowBadge(false)
            },
        )
        channels.forEach { runCatching { service.createNotificationChannel(it) } }
    }

    // -----------------------------------------------------------------------
    // Posting
    // -----------------------------------------------------------------------

    fun notifyIncomingOffers(count: Int, senderName: String) {
        if (!settings.current.notifyTransfers || count <= 0) return
        if (!shouldPost(NotificationRoute.RECEIVE)) return
        post(
            id = Ids.OFFERS,
            channel = Channels.OFFERS,
            title = if (count == 1) "$senderName wants to send a file"
            else "$senderName wants to send $count files",
            body = if (count == 1) "1 file is waiting for you."
            else "$count files are waiting for you.",
            route = NotificationRoute.RECEIVE,
        )
    }

    fun notifyTransferFinished(transferId: String, fileName: String, savedTo: String?) {
        if (!settings.current.notifyTransfers) return
        if (!shouldPost(NotificationRoute.LIBRARY)) return
        val destination = when (savedTo) {
            "photos" -> "Gallery"
            "files" -> "Files"
            else -> "this phone"
        }
        post(
            id = Ids.forTransfer(transferId),
            channel = Channels.TRANSFERS,
            title = "Saved to $destination",
            body = fileName,
            route = NotificationRoute.LIBRARY,
        )
    }

    fun notifyTransferFailed(transferId: String, fileName: String, reason: String) {
        if (!settings.current.notifyTransfers) return
        if (!shouldPost(NotificationRoute.RECEIVE)) return
        post(
            id = Ids.forTransfer(transferId),
            channel = Channels.TRANSFERS,
            title = "Transfer failed",
            body = "$fileName — $reason",
            route = NotificationRoute.RECEIVE,
        )
    }

    /** §11 text. The text itself is deliberately absent from the payload. */
    fun notifyMessage(senderName: String) {
        if (!settings.current.notifyMessages) return
        if (!shouldPost(NotificationRoute.RECEIVE)) return
        post(
            id = Ids.MESSAGE,
            channel = Channels.MESSAGES,
            title = senderName,
            body = "Sent you text",
            route = NotificationRoute.RECEIVE,
        )
    }

    /**
     * §15: a device is asking to pair with this one and needs the six digits
     * typed on its side. The digits are shown, deliberately — they are only
     * useful to someone who can already see this screen, which is exactly the
     * physical-presence property §4 relies on.
     */
    fun notifyPairingRequest(peerName: String, code: String) {
        post(
            id = Ids.PAIRING,
            channel = Channels.OFFERS,
            title = "$peerName wants to pair",
            body = "Type $code on that device.",
            route = NotificationRoute.RECEIVE,
        )
    }

    /** §15: a device finished pairing and may now send to this one. */
    fun notifyPaired(peerName: String) {
        cancel(Ids.PAIRING)
        post(
            id = Ids.PAIRING,
            channel = Channels.TRANSFERS,
            title = "$peerName is paired",
            body = "It can now send files to this device.",
            route = NotificationRoute.RECEIVE,
        )
    }

    fun cancel(id: Int) {
        runCatching { manager.cancel(id) }
    }

    /** Don't post when the user is already looking at where it would send them. */
    private fun shouldPost(route: NotificationRoute): Boolean = when (surface) {
        AppSurface.BACKGROUND -> true
        AppSurface.FLIGHT -> route == NotificationRoute.SEND
        AppSurface.RECEIVE -> route != NotificationRoute.RECEIVE
        AppSurface.SEND -> route != NotificationRoute.SEND
        AppSurface.LIBRARY -> route != NotificationRoute.LIBRARY
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(id: Int, channel: String, title: String, body: String, route: NotificationRoute) {
        // canPost() is the runtime guard lint cannot see through.
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_sendro)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent(route))
            .build()
        runCatching { manager.notify(id, notification) }
    }

    fun contentIntent(route: NotificationRoute): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_ROUTE
            putExtra(MainActivity.EXTRA_ROUTE, route.name)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            route.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The ongoing notification behind [TransferService]. Built here so the
     * channel, icon and tap target stay in one place.
     */
    fun buildProgressNotification(
        title: String,
        text: String,
        progressPercent: Int?,
        indeterminate: Boolean,
    ): Notification {
        val builder = NotificationCompat.Builder(context, Channels.PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_sendro)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent(NotificationRoute.RECEIVE))
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else if (progressPercent != null) {
            builder.setProgress(100, progressPercent.coerceIn(0, 100), false)
        }
        return builder.build()
    }
}

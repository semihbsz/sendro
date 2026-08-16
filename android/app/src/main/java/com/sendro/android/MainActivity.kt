package com.sendro.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.sendro.android.core.NotificationRoute
import com.sendro.android.core.PairLink
import com.sendro.android.ui.RootScreen
import com.sendro.android.ui.theme.SendroTheme
import kotlinx.coroutines.launch

/**
 * The one Activity.
 *
 * `singleTask` in the manifest so a share, a `sendro://pair` deep link or a
 * notification tap always lands in the running instance (and therefore in the
 * running transfer engine) rather than starting a second copy of the UI.
 */
class MainActivity : ComponentActivity() {

    private val app: SendroApplication get() = application as SendroApplication

    /** Files shared into Sendro, the route from a notification, a scanned QR. */
    private val pendingRoute = mutableStateOf<NotificationRoute?>(null)
    private val pendingPairLink = mutableStateOf<PairLink?>(null)
    private val pendingLinkWarning = mutableStateOf<String?>(null)
    private val sharedFilesSignal = mutableStateOf(0)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        handleIntent(intent)

        setContent {
            SendroTheme {
                RootScreen(
                    app = app,
                    pendingRoute = pendingRoute,
                    pendingPairLink = pendingPairLink,
                    pendingLinkWarning = pendingLinkWarning,
                    sharedFilesSignal = sharedFilesSignal,
                    onRequestNotificationPermission = ::askForNotifications,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Asked at the first moment it makes sense — right after a pairing
     * succeeds — never at launch. A permission sheet before the user has
     * anything to be notified about is the classic way to get denied.
     */
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (app.notifier.canPost()) return
        runCatching {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // -----------------------------------------------------------------------
    // Incoming intents
    // -----------------------------------------------------------------------

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> handleViewIntent(intent)
            Intent.ACTION_SEND -> {
                val uri = intentUri(intent, Intent.EXTRA_STREAM)
                if (uri != null) {
                    stageShared(listOf(uri))
                } else {
                    // A text/plain share: pre-fill the §11 composer rather than
                    // inventing a file for it.
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                        SharedTextBuffer.value = text
                        pendingRoute.value = NotificationRoute.SEND
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intentUriList(intent)
                if (uris.isNotEmpty()) stageShared(uris)
            }
            ACTION_ROUTE -> {
                intent.getStringExtra(EXTRA_ROUTE)?.let { raw ->
                    pendingRoute.value = runCatching { NotificationRoute.valueOf(raw) }.getOrNull()
                }
            }
        }
        // Consume so a configuration change does not replay the share.
        intent.action = null
    }

    private fun handleViewIntent(intent: Intent) {
        val data = intent.data ?: return
        if (!data.scheme.equals("sendro", ignoreCase = true)) return
        // PROTOCOL.md §13: a sendro:// URL is only ever accepted from an OS URL
        // open (a camera / QR reader) or our own scanner. It always lands on
        // the confirmation pane, which names the PC before anything is sent.
        val link = PairLink.parse(data)
        if (link == null) {
            pendingLinkWarning.value =
                "That pairing link isn't valid — show a fresh QR code on your PC."
            return
        }
        pendingPairLink.value = link
    }

    private fun stageShared(uris: List<Uri>) {
        // Take a persistable read grant where the sender offered one, so a
        // large file that the user sends minutes later still resolves.
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        lifecycleScope.launch {
            val staged = app.uploadEngine.stage(uris)
            app.sendTray.add(staged.files)
            if (staged.files.isNotEmpty()) {
                sharedFilesSignal.value = sharedFilesSignal.value + 1
                pendingRoute.value = NotificationRoute.SEND
            }
            if (staged.failures.isNotEmpty()) {
                pendingLinkWarning.value =
                    "Some items couldn't be added: ${staged.failures.first()}"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun intentUri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun intentUriList(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    companion object {
        const val ACTION_ROUTE = "com.sendro.android.action.ROUTE"
        const val EXTRA_ROUTE = "route"
    }
}

/**
 * Text shared into Sendro from another app, waiting for the §11 composer.
 *
 * A plain mutable holder rather than a store: like every §11 payload it is RAM
 * only and must never reach disk.
 */
object SharedTextBuffer {
    @Volatile
    var value: String? = null

    fun take(): String? {
        val current = value
        value = null
        return current
    }
}

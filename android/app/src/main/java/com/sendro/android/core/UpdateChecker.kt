package com.sendro.android.core

import android.content.Context
import android.util.Log
import com.sendro.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** `android.json` from the GitHub Release (docs/UPDATES.md §4). */
@Serializable
data class AndroidManifestJson(
    val version: String,
    val versionCode: Int,
    val pubDate: String = "",
    val notes: String = "",
    val notesTr: String = "",
    val minSupported: String = "0.0.0",
    val mandatory: Boolean = false,
    val apkUrl: String,
    val apkSha256: String,
    val apkSizeBytes: Long = 0,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState

    data class Available(
        val manifest: AndroidManifestJson,
        /** True when this build is below `minSupported` — "update required". */
        val required: Boolean,
    ) : UpdateState

    data class Downloading(
        val manifest: AndroidManifestJson,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : UpdateState {
        val fraction: Double
            get() = if (totalBytes <= 0) 0.0
            else (bytesDownloaded.toDouble() / totalBytes).coerceIn(0.0, 1.0)
    }

    data class ReadyToInstall(val manifest: AndroidManifestJson, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Sendro's self-update (docs/UPDATES.md §4).
 *
 * THIS IS THE ONLY CODE IN THE APP THAT TOUCHES THE INTERNET. Everything else
 * speaks to a private LAN address. The request is a plain HTTPS GET for a
 * static file: no identifiers, no query string, no telemetry, and it does not
 * happen at all when the auto-check setting is off (except when the user taps
 * "Check now" themselves).
 *
 * Flow: fetch `android.json` -> compare `versionCode` -> download the APK to
 * app-scoped storage with progress -> **verify SHA-256 before installing** ->
 * hand it to the system installer via FileProvider + ACTION_VIEW. Android
 * shows its own install prompt; the user grants "install unknown apps" once.
 */
class UpdateChecker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) {

    companion object {
        private const val TAG = "SendroUpdate"

        /** Stable URL — never changes between releases (UPDATES.md §2). */
        const val MANIFEST_URL =
            "https://github.com/semihbsz/sendro/releases/latest/download/android.json"

        /** After launch, wait this long before the automatic check. */
        const val LAUNCH_GRACE_MS = 10_000L

        /** And re-check this often while the app keeps running. */
        const val RECHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /**
         * `versionCode` derivation, mirroring scripts/bump_version.py:
         * major*10000 + minor*100 + patch. Used to compare against
         * `minSupported`, which the manifest gives as a semver string.
         */
        fun versionCodeOf(semver: String): Int? {
            val parts = semver.trim().split('.')
            if (parts.size != 3) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts[2].toIntOrNull() ?: return null
            if (minor > 99 || patch > 99 || major < 0 || minor < 0 || patch < 0) return null
            return major * 10_000 + minor * 100 + patch
        }
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // No call timeout: the APK download is the long one.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    val currentVersionName: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    /**
     * The automatic schedule: one check [LAUNCH_GRACE_MS] after launch so
     * startup stays fast, then every [RECHECK_INTERVAL_MS].
     */
    fun startAutomaticChecks() {
        scope.launch {
            kotlinx.coroutines.delay(LAUNCH_GRACE_MS)
            while (true) {
                if (settings.current.autoCheckUpdates) check(manual = false)
                kotlinx.coroutines.delay(RECHECK_INTERVAL_MS)
            }
        }
    }

    /** @param manual true when the user tapped "Check now" in Settings. */
    fun check(manual: Boolean) {
        if (!manual && !settings.current.autoCheckUpdates) return
        val running = job
        if (running?.isActive == true) return
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            try {
                val manifest = withContext(Dispatchers.IO) { fetchManifest() }
                val minimum = versionCodeOf(manifest.minSupported)
                _state.value = when {
                    manifest.versionCode <= currentVersionCode -> UpdateState.UpToDate
                    else -> UpdateState.Available(
                        manifest = manifest,
                        required = manifest.mandatory ||
                            (minimum != null && currentVersionCode < minimum),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                // Failures never brick the app: stay on the current version
                // and, for an automatic check, stay silent.
                _state.value = if (manual) {
                    UpdateState.Failed("Could not check for updates: ${e.sendroMessage()}")
                } else {
                    UpdateState.Idle
                }
            }
        }
    }

    private suspend fun fetchManifest(): AndroidManifestJson {
        val request = Request.Builder()
            .url(MANIFEST_URL)
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).awaitResponse().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // No bare numbers here either — this string is shown verbatim
                // on the Settings card.
                throw java.io.IOException(
                    when (response.code) {
                        403, 429 -> "GitHub is rate-limiting this device. Try again in a few minutes."
                        404 -> "No release manifest has been published yet."
                        in 500..599 -> "GitHub is having trouble right now."
                        else -> "GitHub didn't answer with a release manifest."
                    },
                )
            }
            return SendroJson.decodeFromString(body)
        }
    }

    /** Dismiss the card for this version (not possible when mandatory). */
    fun dismiss(version: String) {
        scope.launch { settings.setDismissedUpdateVersion(version) }
        _state.value = UpdateState.Idle
    }

    fun clearFailure() {
        if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
    }

    /**
     * Download the APK to app-scoped storage, then verify its SHA-256 before
     * anything is handed to the installer — the same byte-for-byte ethic as a
     * transfer. A mismatch deletes the file and fails loudly.
     */
    fun download(manifest: AndroidManifestJson) {
        val running = job
        if (running?.isActive == true) running.cancel()
        job = scope.launch {
            _state.value = UpdateState.Downloading(manifest, 0, manifest.apkSizeBytes)
            val target = File(context.filesDir, "updates/Sendro-${manifest.version}.apk")
            try {
                withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()

                    val request = Request.Builder().url(manifest.apkUrl).get().build()
                    http.newCall(request).awaitResponse().use { response ->
                        if (!response.isSuccessful) {
                            throw java.io.IOException(
                                when (response.code) {
                                    403, 429 -> "GitHub is rate-limiting this device. Try again shortly."
                                    404 -> "That release download is no longer available."
                                    in 500..599 -> "GitHub is having trouble right now."
                                    else -> "GitHub refused the download."
                                },
                            )
                        }
                        val body = response.body ?: throw java.io.IOException("Empty response")
                        val total = body.contentLength().takeIf { it > 0 }
                            ?: manifest.apkSizeBytes
                        body.byteStream().use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(SendroCrypto.CHUNK_BYTES)
                                var written = 0L
                                var lastEmit = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    written += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit >= 200) {
                                        lastEmit = now
                                        _state.value =
                                            UpdateState.Downloading(manifest, written, total)
                                    }
                                }
                                output.flush()
                            }
                        }
                    }

                    val digest = SendroCrypto.sha256Hex(target)
                    if (!digest.equals(manifest.apkSha256, ignoreCase = true)) {
                        target.delete()
                        throw java.io.IOException(
                            "The downloaded APK failed its SHA-256 check and was deleted.",
                        )
                    }
                }
                _state.value = UpdateState.ReadyToInstall(manifest, target)
            } catch (e: CancellationException) {
                runCatching { target.delete() }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update download failed", e)
                runCatching { target.delete() }
                _state.value = UpdateState.Failed("Update failed: ${e.sendroMessage()}")
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
        _state.value = UpdateState.Idle
    }

    /**
     * Installation is delegated to [ApkInstaller], which the "someone sent me
     * an APK" action in the Library uses too. One implementation means the
     * update path and the received-file path cannot drift on the parts that
     * matter (the FileProvider grant, the permission walk, the MIME type).
     */
    private val installer = ApkInstaller(context)

    fun canRequestInstall(): Boolean = installer.canRequestInstall()

    /** Starts the first system screen that can grant "install unknown apps". */
    fun openInstallPermission(): Boolean = installer.openPermissionSettings()

    /** Starts the system installer for [apk]; false when nothing can handle it. */
    fun startInstall(apk: File): Boolean = installer.install(apk)

    /** The notes to show — Turkish preferred, per UPDATES.md §1. */
    fun notesFor(manifest: AndroidManifestJson): String =
        manifest.notesTr.takeIf { it.isNotBlank() } ?: manifest.notes
}

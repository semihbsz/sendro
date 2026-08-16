package com.sendro.android.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing an APK to the system installer.
 *
 * Shared by two callers that want identical behaviour: the self-updater
 * (docs/UPDATES.md §4) and the "someone sent me an APK" action in the Library.
 * Keeping them on one implementation means the update path and the received
 * path cannot drift on the security-relevant details.
 *
 * `ACTION_VIEW` + FileProvider rather than `PackageInstaller`'s session API:
 * the session API's silent path needs a device-owner privilege Sendro does not
 * have, so it would show exactly the same system prompt after twice the code.
 *
 * Nothing here installs anything on its own. Every path ends at Android's own
 * confirmation screen, and the callers require an explicit user confirmation
 * before even getting that far.
 */
class ApkInstaller(private val context: Context) {

    /** What a received `.apk` actually contains, when it can be read cheaply. */
    data class ApkInfo(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        /** True when this package is already installed on the device. */
        val installed: Boolean,
        /** Installed version, when [installed]. */
        val installedVersionName: String? = null,
        /** True when the archive is older than what is installed. */
        val isDowngrade: Boolean = false,
    )

    enum class Kind {
        /** A plain APK: installable. */
        INSTALLABLE,

        /**
         * A split bundle (`.apks`, `.xapk`, `.apkm`). These are archives of
         * several APKs and Android cannot install them directly — a helper app
         * has to feed the splits to PackageInstaller. Sendro says so plainly
         * rather than launching an intent that will fail.
         */
        SPLIT_BUNDLE,
        NOT_AN_APK,
    }

    fun kindOf(fileName: String): Kind = when (FileNames.extensionOf(fileName)) {
        "apk" -> Kind.INSTALLABLE
        "apks", "xapk", "apkm" -> Kind.SPLIT_BUNDLE
        else -> Kind.NOT_AN_APK
    }

    /**
     * Parses the archive's manifest so the user can see what they are about to
     * install. Cheap: `getPackageArchiveInfo` reads the manifest only, it does
     * not unpack the APK.
     *
     * Needs a real filesystem path, which is exactly why received APKs are
     * routed to Sendro's own store rather than to MediaStore
     * ([MediaSaver.mustStayInAppStore]).
     */
    fun inspect(file: File): ApkInfo? {
        if (!file.isFile) return null
        val pm = context.packageManager
        val archive: PackageInfo = runCatching {
            pm.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return null

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }

        val installedInfo = runCatching {
            pm.getPackageInfo(archive.packageName, 0)
        }.getOrNull()
        val installedCode = installedInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                it.versionCode.toLong()
            }
        }

        return ApkInfo(
            packageName = archive.packageName,
            versionName = archive.versionName,
            versionCode = versionCode,
            installed = installedInfo != null,
            installedVersionName = installedInfo?.versionName,
            isDowngrade = installedCode != null && versionCode < installedCode,
        )
    }

    /**
     * True when this app may install packages. On API 26+ the user grants
     * "install unknown apps" per app, once.
     */
    fun canRequestInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * Where to send the user to allow installs, best target first.
     *
     * `ACTION_MANAGE_UNKNOWN_APP_SOURCES` is the right screen and every phone
     * has it, but a fair number of Android TV firmwares do not implement it —
     * on those the toggle lives under Security & restrictions, or nowhere at
     * all and the Settings root is the best available. Callers walk the list
     * until one resolves.
     */
    fun permissionIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** Starts the first of [permissionIntents] the device can handle. */
    fun openPermissionSettings(): Boolean {
        for (intent in permissionIntents()) {
            if (intent.resolveActivity(context.packageManager) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** @return false when nothing on the device can handle an APK. */
    fun install(apk: File): Boolean {
        val intent = runCatching { installIntent(apk) }.getOrNull() ?: return false
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    companion object {
        const val MIME = "application/vnd.android.package-archive"
    }
}

package com.sendro.android.core

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * "Is this a television?" — the one piece of device sniffing that `core` needs.
 *
 * It lives here rather than in the UI's `DeviceProfile` because two non-UI
 * decisions depend on it: the §5/§15.1 `platform` string a peer sees, and
 * whether the §15.4 receiver host defaults to on. `DeviceProfile` calls
 * straight into this so the two can never disagree.
 */
object DeviceKind {

    /**
     * Belt-and-braces, in the order of how much the signal can be trusted:
     * `UI_MODE_TYPE_TELEVISION` is correct and every real Android TV sets it;
     * some cheap boxes and emulators only report `FEATURE_LEANBACK`; a few
     * only give themselves away by having no touchscreen at all.
     */
    fun isTelevision(context: Context): Boolean {
        val pm = context.packageManager
        val uiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.software.leanback_only") ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /**
     * The §5 / §15.1 `platform` value this device reports, and the §2 mDNS
     * `pf` TXT record.
     *
     * Clients must treat this as informational (§15.1) — capability comes from
     * `/api/v1/info` plus a 404 on the outbox, never from the platform string.
     */
    fun platformString(context: Context): String =
        if (isTelevision(context)) "androidtv" else "android"
}

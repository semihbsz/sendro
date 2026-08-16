package com.sendro.android.core

import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Byte / speed / time formatting shared by every surface.
 *
 * Decimal units (kB, MB, GB) to match the Windows app and iOS's
 * `ByteCountFormatter(countStyle: .file)` — a 1.0 GB file must read the same
 * on the PC that sent it and the phone that received it.
 */
object Format {

    private val locale: Locale get() = Locale.US

    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1_000) return "$value bytes"
        val units = arrayOf("kB", "MB", "GB", "TB", "PB")
        var size = value.toDouble() / 1000.0
        var index = 0
        while (size >= 1000.0 && index < units.lastIndex) {
            size /= 1000.0
            index++
        }
        val pattern = if (size >= 100 || index == 0) "%.0f %s" else "%.1f %s"
        return String.format(locale, pattern, size, units[index])
    }

    fun speed(bytesPerSecond: Double): String =
        if (bytesPerSecond <= 0) "—" else bytes(bytesPerSecond.toLong()) + "/s"

    fun eta(seconds: Int?): String {
        if (seconds == null || seconds <= 0) return "—"
        if (seconds < 60) return "${seconds}s"
        if (seconds < 3600) return "${seconds / 60}m ${seconds % 60}s"
        return "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    /** Today -> "14:32"; otherwise "16 Aug". */
    fun timestamp(millis: Long): String {
        val date = Date(millis)
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { time = date }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "d MMM"
        return java.text.SimpleDateFormat(pattern, locale).format(date)
    }

    fun percent(fraction: Double): String =
        String.format(locale, "%d%%", (fraction.coerceIn(0.0, 1.0) * 100).toInt())
}

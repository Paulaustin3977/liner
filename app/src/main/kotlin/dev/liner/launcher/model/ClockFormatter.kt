package dev.liner.launcher.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ClockFormatter {
    fun formatTime(
        epochMillis: Long,
        zone: ZoneId,
        use24Hour: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val pattern = if (use24Hour) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, locale).format(time)
    }

    /** Returns null for the "none" style. */
    fun formatDate(
        epochMillis: Long,
        zone: ZoneId,
        style: String,
        locale: Locale = Locale.getDefault(),
    ): String? {
        val pattern = when (style) {
            "short" -> "EEE d MMM"
            "medium" -> "EEEE, d MMMM"
            "long" -> "EEEE, d MMMM yyyy"
            else -> return null
        }
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return DateTimeFormatter.ofPattern(pattern, locale).format(date)
    }
}

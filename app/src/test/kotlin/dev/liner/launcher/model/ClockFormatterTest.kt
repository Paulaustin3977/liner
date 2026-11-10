package dev.liner.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ClockFormatterTest {

    // 2024-03-14T15:09:26Z — 14 March 2024, 15:09:26 UTC
    private val instant = Instant.parse("2024-03-14T15:09:26Z")
    private val zone = ZoneId.of("UTC")

    @Test
    fun `twenty four hour time omits the am pm marker`() {
        assertEquals("15:09", ClockFormatter.formatTime(instant.toEpochMilli(), zone, use24Hour = true, locale = Locale.UK))
    }

    @Test
    fun `twelve hour time includes the am pm marker`() {
        assertEquals("3:09 PM", ClockFormatter.formatTime(instant.toEpochMilli(), zone, use24Hour = false, locale = Locale.US))
    }

    @Test
    fun `midnight renders as twelve am`() {
        val midnight = Instant.parse("2024-03-14T00:00:00Z").toEpochMilli()

        assertEquals("12:00 AM", ClockFormatter.formatTime(midnight, zone, use24Hour = false, locale = Locale.US))
    }

    @Test
    fun `date style none returns null`() {
        assertNull(ClockFormatter.formatDate(instant.toEpochMilli(), zone, "none", Locale.UK))
    }

    @Test
    fun `date styles render their respective formats`() {
        assertEquals("Thu 14 Mar", ClockFormatter.formatDate(instant.toEpochMilli(), zone, "short", Locale.UK))
        assertEquals("Thursday, 14 March", ClockFormatter.formatDate(instant.toEpochMilli(), zone, "medium", Locale.UK))
        assertEquals("Thursday, 14 March 2024", ClockFormatter.formatDate(instant.toEpochMilli(), zone, "long", Locale.UK))
    }
}

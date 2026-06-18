package com.rousecontext.integrations.common

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeriodParserTest {

    // A fixed "now" that is mid-afternoon UTC so day boundaries are unambiguous.
    private val now: Instant = Instant.parse("2026-06-18T14:30:00Z")

    @Test
    fun `today range runs from start of today to now in the given zone`() {
        val zone = ZoneId.of("UTC")
        val range = PeriodParser.parse("today", zone, now)!!

        assertEquals(Instant.parse("2026-06-18T00:00:00Z"), range.start)
        assertEquals(now, range.end)
    }

    @Test
    fun `week range runs from start of today minus 7 days to now`() {
        val zone = ZoneId.of("UTC")
        val range = PeriodParser.parse("week", zone, now)!!

        assertEquals(Instant.parse("2026-06-11T00:00:00Z"), range.start)
        assertEquals(now, range.end)
    }

    @Test
    fun `month range runs from start of today minus 30 days to now`() {
        val zone = ZoneId.of("UTC")
        val range = PeriodParser.parse("month", zone, now)!!

        assertEquals(Instant.parse("2026-05-19T00:00:00Z"), range.start)
        assertEquals(now, range.end)
    }

    @Test
    fun `start of today is anchored in the local zone not UTC`() {
        // In New York (UTC-4 on this date) 14:30 UTC is 10:30 local, still the 18th,
        // so start-of-today-local is 2026-06-18T04:00:00Z.
        val zone = ZoneId.of("America/New_York")
        val range = PeriodParser.parse("today", zone, now)!!

        assertEquals(Instant.parse("2026-06-18T04:00:00Z"), range.start)
        assertEquals(now, range.end)
    }

    @Test
    fun `start of today respects a zone where local date is already the next day`() {
        // In Kiritimati (UTC+14) 14:30 UTC is 04:30 on the 19th local,
        // so start-of-today-local is 2026-06-19T00:00+14:00 == 2026-06-18T10:00:00Z.
        val zone = ZoneId.of("Pacific/Kiritimati")
        val range = PeriodParser.parse("today", zone, now)!!

        assertEquals(Instant.parse("2026-06-18T10:00:00Z"), range.start)
        assertEquals(now, range.end)
    }

    @Test
    fun `unrecognised period returns null`() {
        assertNull(PeriodParser.parse("year", ZoneId.of("UTC"), now))
        assertNull(PeriodParser.parse("yesterday", ZoneId.of("UTC"), now))
        assertNull(PeriodParser.parse("", ZoneId.of("UTC"), now))
    }

    @Test
    fun `PeriodRange exposes epoch millis accessors`() {
        val range = PeriodParser.parse("today", ZoneId.of("UTC"), now)!!

        assertEquals(Instant.parse("2026-06-18T00:00:00Z").toEpochMilli(), range.startMillis)
        assertEquals(now.toEpochMilli(), range.endMillis)
    }

    @Test
    fun `week is exactly 7 days before start of today`() {
        val zone = ZoneId.of("UTC")
        val range = PeriodParser.parse("week", zone, now)!!
        val today = PeriodParser.parse("today", zone, now)!!

        assertEquals(today.start.minus(7, ChronoUnit.DAYS), range.start)
    }
}

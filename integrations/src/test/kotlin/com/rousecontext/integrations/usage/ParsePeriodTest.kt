package com.rousecontext.integrations.usage

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the usage-only `yesterday` extra in [parsePeriod].
 *
 * `yesterday` is NOT handled by the shared `PeriodParser` (it is a usage-only
 * extra resolved at this call site), so its DST contract is exercised here.
 *
 * Contract: `yesterday` resolves to the prior calendar day in the given zone,
 * `[local-midnight-of-yesterday, local-midnight-of-today]`. Because it uses
 * LocalDate arithmetic, the window correctly spans 23h on spring-forward days
 * and 25h on fall-back days — a flat 86_400s subtraction off an Instant would
 * get this wrong (see #500 review).
 */
class ParsePeriodTest {

    private val ny = ZoneId.of("America/New_York")

    @Test
    fun `yesterday is a 23h window on a spring-forward day`() {
        // DST spring-forward in America/New_York is 2026-03-08 02:00 EST -> 03:00 EDT.
        // "yesterday" relative to mid-morning on the 9th is the 8th, a 23h day.
        val now = Instant.parse("2026-03-09T14:30:00Z") // 10:30 EDT on the 9th
        val (start, end) = parsePeriod("yesterday", ny, now)!!

        // Start of the 8th is local midnight EST (UTC-5); start of the 9th is
        // local midnight EDT (UTC-4) because the clocks already sprang forward.
        assertEquals(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(), end)
        assertEquals(Duration.ofHours(23).toMillis(), end - start)
    }

    @Test
    fun `yesterday is a 25h window on a fall-back day`() {
        // DST fall-back in America/New_York is 2026-11-01 02:00 EDT -> 01:00 EST.
        // "yesterday" relative to mid-morning on the 2nd is the 1st, a 25h day.
        val now = Instant.parse("2026-11-02T14:30:00Z") // 09:30 EST on the 2nd
        val (start, end) = parsePeriod("yesterday", ny, now)!!

        // Start of the 1st is local midnight EDT (UTC-4); start of the 2nd is
        // local midnight EST (UTC-5) because the clocks fell back.
        assertEquals(Instant.parse("2026-11-01T04:00:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-11-02T05:00:00Z").toEpochMilli(), end)
        assertEquals(Duration.ofHours(25).toMillis(), end - start)
    }

    @Test
    fun `yesterday is a plain 24h window on a non-transition day`() {
        val now = Instant.parse("2026-06-18T14:30:00Z") // 10:30 EDT
        val (start, end) = parsePeriod("yesterday", ny, now)!!

        assertEquals(Instant.parse("2026-06-17T04:00:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-06-18T04:00:00Z").toEpochMilli(), end)
        assertEquals(Duration.ofHours(24).toMillis(), end - start)
    }

    @Test
    fun `parsePeriod delegates today week month to the shared parser`() {
        val now = Instant.parse("2026-06-18T14:30:00Z")
        val zone = ZoneId.of("UTC")

        assertEquals(
            Instant.parse("2026-06-18T00:00:00Z").toEpochMilli() to now.toEpochMilli(),
            parsePeriod("today", zone, now)
        )
        assertEquals(
            Instant.parse("2026-06-11T00:00:00Z").toEpochMilli() to now.toEpochMilli(),
            parsePeriod("week", zone, now)
        )
    }

    @Test
    fun `parsePeriod returns null for unrecognised period`() {
        assertNull(parsePeriod("year", ny, Instant.parse("2026-06-18T14:30:00Z")))
    }
}

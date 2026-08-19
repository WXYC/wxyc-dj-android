package org.wxyc.dj.api

import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Pins invariant 14 (iOS issue #93): any non-null bin counts as in rotation,
 * the kill-date compare is strict and lexicographic over `YYYY-MM-DD`, and an
 * unreadable kill date reads as expired rather than un-expiring. Mirrors
 * `AlbumInfoRotationTests.swift`.
 */
class RotationPredicateTest {
    @Test
    fun `no bin is never in rotation`() {
        assertFalse(RotationPredicate.isInRotation(bin = null, killDay = "2026-07-01", today = "2026-06-22"))
        assertFalse(RotationPredicate.isInRotation(bin = null, killDay = null, today = "2026-06-22"))
    }

    @Test
    fun `an unrecognized bin outside HMLS still counts as in rotation`() {
        // Forward-compat hedge: a cohort added server-side ahead of the app.
        assertTrue(RotationPredicate.isInRotation(bin = "N", killDay = null, today = "2026-06-22"))
    }

    @Test
    fun `a bin with no kill date is in rotation forever`() {
        assertTrue(RotationPredicate.isInRotation(bin = "H", killDay = null, today = "2026-06-22"))
    }

    @Test
    fun `the kill-date compare is strict — expiring today is already out`() {
        assertFalse(RotationPredicate.isInRotation(bin = "H", killDay = "2026-06-22", today = "2026-06-22"))
        assertTrue(RotationPredicate.isInRotation(bin = "H", killDay = "2026-06-23", today = "2026-06-22"))
        assertFalse(RotationPredicate.isInRotation(bin = "H", killDay = "2026-06-21", today = "2026-06-22"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-date", "", "2026-6-2", "20260622", "twenty-twenty-six"])
    fun `an unreadable kill date fails closed rather than open`(killDay: String) {
        // "not-a-date" sorts above every real YYYY-MM-DD lexicographically, so
        // a bare compare would read it as in rotation forever. Unreadable
        // therefore means expired, never un-expiring.
        assertFalse(RotationPredicate.isInRotation(bin = "H", killDay = killDay, today = "2026-06-22"))
    }

    @Test
    fun `a kill date as a full timestamp still compares via its leading day`() {
        assertTrue(RotationPredicate.isInRotation(bin = "H", killDay = "2026-07-01T20:00:00-04:00", today = "2026-06-22"))
        assertFalse(RotationPredicate.isInRotation(bin = "H", killDay = "2026-07-01T20:00:00-04:00", today = "2026-08-01"))
    }

    @Test
    fun `a zero-padded low kill year stays chronological`() {
        assertFalse(RotationPredicate.isInRotation(bin = "H", killDay = "0999-01-01", today = "2026-06-22"))
        assertEquals("0999-01-01", RotationPredicate.calendarDay("0999-01-01"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-date", "abc", "2026-6-2", "20260622", "short"])
    fun `calendarDay rejects malformed shapes`(raw: String) {
        assertNull(RotationPredicate.calendarDay(raw))
    }

    @Test
    fun `calendarDay rejects an empty string`() {
        assertNull(RotationPredicate.calendarDay(""))
    }

    @Test
    fun `localDay respects the device time zone`() {
        // 00:30 UTC on the 22nd is still the 21st in America/New_York (EDT, UTC-4).
        val justAfterMidnightUtc = Instant.parse("2026-06-22T00:30:00Z")
        assertEquals("2026-06-22", RotationPredicate.localDay(justAfterMidnightUtc, ZoneId.of("UTC")))
        assertEquals("2026-06-21", RotationPredicate.localDay(justAfterMidnightUtc, ZoneId.of("America/New_York")))
    }
}

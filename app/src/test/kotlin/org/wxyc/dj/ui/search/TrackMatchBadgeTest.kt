package org.wxyc.dj.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.wxyc.dj.api.TrackMatchHint
import org.wxyc.dj.api.TrackMatchSource

/**
 * Pins [trackMatchSummary], the pure core behind [TrackMatchBadge] (issue
 * #9). Ported from `WXYCDJTests/Search/TrackMatchBadgeTests.swift`, which
 * exercises the same pure formatter rather than the SwiftUI view tree --
 * mirrored here since the composable is a thin wrapper around one `Text`.
 * Templates are passed as literal strings rather than resolved via
 * `stringResource`, matching `LoginViewModelTest`'s `displayTarget` tests --
 * the pure function needs no Android framework to exercise.
 */
class TrackMatchBadgeTest {

    private val singleTemplate = "via track: %1\$s"
    private val multipleTemplate = "via track: %1\$s (+%2\$d more)"

    @Test
    fun `empty hints render nothing`() {
        assertNull(trackMatchSummary(emptyList(), singleTemplate, multipleTemplate))
    }

    @Test
    fun `a single hint renders the via-track prefix`() {
        val hint = TrackMatchHint(title = "In a Sentimental Mood", source = TrackMatchSource.Cta)

        assertEquals(
            "via track: In a Sentimental Mood",
            trackMatchSummary(listOf(hint), singleTemplate, multipleTemplate),
        )
    }

    @Test
    fun `multiple hints append the overflow count`() {
        val hints = listOf(
            TrackMatchHint(title = "VI Scose Poise", source = TrackMatchSource.DiscogsMaster),
            TrackMatchHint(title = "Eutow", source = TrackMatchSource.DiscogsMaster),
            TrackMatchHint(title = "Pen Expers", source = TrackMatchSource.DiscogsMaster),
        )

        assertEquals(
            "via track: VI Scose Poise (+2 more)",
            trackMatchSummary(hints, singleTemplate, multipleTemplate),
        )
    }
}

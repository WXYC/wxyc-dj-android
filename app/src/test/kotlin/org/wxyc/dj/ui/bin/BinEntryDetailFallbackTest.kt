package org.wxyc.dj.ui.bin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wxyc.dj.api.BinEntry

/**
 * Pins [BinEntry.toDetailFallback] -- the row-tap bridge issue #11's notes
 * asked for a deliberate design decision on rather than a silent guess. See
 * `BinEntryDetailFallback.kt`'s KDoc for why this bridge is `ui/bin`-local
 * rather than the shared `:api` factory iOS uses for the identical problem.
 */
class BinEntryDetailFallbackTest {

    private val fullRow = BinEntry(
        albumId = 100,
        albumTitle = "DOGA",
        artistName = "Juana Molina",
        alphabeticalName = "Molina, Juana",
        label = "Sonamos",
        codeLetters = "MOL",
        codeArtistNumber = 1,
        codeNumber = 12,
        formatName = "CD",
        genreName = "Rock",
        legacyReleaseId = 55123,
    )

    @Test
    fun `carries id, title, artist, label, format, genre and every call-number leg`() {
        val fallback = fullRow.toDetailFallback()

        assertEquals(100, fallback.id)
        assertEquals("DOGA", fallback.albumTitle)
        assertEquals("Juana Molina", fallback.artistName)
        assertEquals("Sonamos", fallback.label)
        assertEquals("MOL", fallback.codeLetters)
        assertEquals(1, fallback.codeArtistNumber)
        assertEquals(12, fallback.codeNumber)
        assertEquals("CD", fallback.formatName)
        assertEquals("Rock", fallback.genreName)
        assertEquals("MOL 1/12", fallback.callNumber)
    }

    /**
     * The `/djs/bin` projection has no `artwork_url`, `plays`, `on_streaming`,
     * `add_date`, `label_id`, or `rotation_bin` -- an artwork-less fallback is
     * the expected shape for a bin-sourced detail header, not a gap. See the
     * bridge's KDoc.
     */
    @Test
    fun `leaves artwork, plays, streaming, and rotation at their defaults`() {
        val fallback = fullRow.toDetailFallback()

        assertNull(fallback.artworkUrl)
        assertNull(fallback.plays)
        assertNull(fallback.onStreaming)
        assertNull(fallback.addDate)
        assertNull(fallback.labelId)
        assertNull(fallback.rotationBin)
        assertNull(fallback.rotationId)
        assertNull(fallback.albumArtist)
        assertTrue(fallback.matchedVia.isEmpty())
    }

    @Test
    fun `a row with no call-number legs bridges to an empty call number rather than crashing`() {
        val unfiled = BinEntry(albumId = 1, albumTitle = "Edits", artistName = "Chuquimamani-Condori")

        val fallback = unfiled.toDetailFallback()

        assertEquals("", fallback.callNumber)
        assertNull(fallback.label)
    }
}

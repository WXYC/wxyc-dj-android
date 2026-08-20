package org.wxyc.dj.api

import java.text.Collator
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Mirrors the `BinEntry` cases of `DTODecodingTests.swift`, plus invariants
 * 15 and 16 (issues #77/#80 and #60).
 */
class BinEntryTest {
    @Test
    fun `decodes GET djs bin as a bare array`() {
        val entries = Fixtures.binEntries()
        assertEquals(2, entries.size)
        val juana = entries.single { it.albumId == 100 }
        assertEquals("Juana Molina", juana.artistName)
        assertEquals("Molina, Juana", juana.alphabeticalName)
        assertEquals("Sonamos", juana.label)
        assertEquals("CD", juana.formatName)
        assertEquals("Rock", juana.genreName)
        // The album is the bin's key: the wire carries no bins.id.
        assertEquals(100, juana.id)
        assertEquals("MOL 1/12", juana.callNumber)
    }

    @Test
    fun `tolerates null call-number legs, e g VA compilations`() {
        val raw = """
            {
              "album_id": 300, "album_title": "Edits", "artist_name": "Chuquimamani-Condori",
              "alphabetical_name": "Chuquimamani-Condori", "label": null,
              "code_letters": null, "code_artist_number": null, "code_number": null,
              "format_name": "CD", "genre_name": "Electronic"
            }
        """.trimIndent()
        val entry = WxycJson.json.decodeFromString(BinEntry.serializer(), raw)
        assertNull(entry.codeLetters)
        assertNull(entry.codeNumber)
        assertEquals("", entry.callNumber)
    }

    @Test
    fun `an empty bin array is authoritative`() {
        assertTrue(BinResponse.decode("[]").isEmpty())
    }

    @Test
    fun `a null body is not a bin and throws`() {
        // Issue #60: dj-site coerces null to an empty bin; this client must not.
        assertThrows(SerializationException::class.java) {
            BinResponse.decode("null")
        }
    }

    @Test
    fun `dedup collapses repeated albums keeping first-seen order`() {
        val entries = Fixtures.binEntries()
        val duplicated = entries + entries.first()
        val deduped = BinEntry.deduplicatedByAlbum(duplicated)
        assertEquals(entries.map { it.albumId }, deduped.map { it.albumId })
    }

    @Test
    fun `sorted orders by filing name then album title`() {
        // Wire order is Pratt then Molina; the filing sort must put Molina first.
        val entries = Fixtures.binEntries()
        val sorted = BinSorting.sorted(entries)
        assertEquals(listOf("Molina, Juana", "Pratt, Jessica"), sorted.map { it.sortName })
    }

    @Test
    fun `the album-title tiebreak actually runs when filing names collide`() {
        // The common real case: one artist with several releases in a bin.
        // A two-row fixture with distinct sortNames (as above) never reaches
        // BinEntry.kt's `.thenComparator` leg, so this pins it directly —
        // deleting that leg must fail this test.
        val entries = listOf(
            BinEntry(
                albumId = 1,
                albumTitle = "Zzyzx",
                artistName = "Juana Molina",
                alphabeticalName = "Molina, Juana",
            ),
            BinEntry(
                albumId = 2,
                albumTitle = "DOGA",
                artistName = "Juana Molina",
                alphabeticalName = "Molina, Juana",
            ),
        )
        val sorted = BinSorting.sorted(entries)
        assertEquals(listOf("DOGA", "Zzyzx"), sorted.map { it.albumTitle })
    }

    @Test
    fun `sortName falls back to artistName when alphabetical_name is absent`() {
        val raw = """
            {
              "album_id": 1, "album_title": "x", "artist_name": "Boards of Canada"
            }
        """.trimIndent()
        val entry = WxycJson.json.decodeFromString(BinEntry.serializer(), raw)
        assertEquals("Boards of Canada", entry.sortName)
    }

    @Test
    fun `sorting is stable across the diacritic-bearing pool from wxyc-example-data`() {
        // Two real WXYC artists, each as an (unaccented, accented, sentinel)
        // triple — see Fixtures.diacriticBearingArtists for why the triple
        // shape is what gives this pool discriminating power. Primary
        // strength + canonical decomposition means the sort key is accent-
        // and case-insensitive, so each artist's unaccented/accented pair
        // must land adjacent to each other, ahead of that artist's sentinel,
        // rather than the sentinel splitting the pair the way plain
        // code-point order would.
        val entries = Fixtures.diacriticBearingArtists().mapIndexed { index, name ->
            BinEntry(
                albumId = index,
                albumTitle = "Album $index",
                artistName = name,
                alphabeticalName = name,
            )
        }.shuffled(kotlin.random.Random(42))

        val sorted = BinSorting.sorted(entries).map { it.sortName }

        assertEquals(
            listOf(
                "Hermanos Gutierrez",
                "Hermanos Gutiérrez",
                "Hermanos Gutizz",
                "Nilufer Yanya",
                "Nilüfer Yanya",
                "Nilzz Yanya",
            ),
            sorted,
        )
    }

    @Test
    fun `newCollator uses a decomposition mode Android's Collator actually accepts`() {
        // Regression pin for a crash that a desktop-only :api:test run cannot
        // otherwise catch: Android's java.text.Collator is android.icu-backed,
        // and its private decompositionMode_Java_ICU(int) converter accepts
        // only CANONICAL_DECOMPOSITION and NO_DECOMPOSITION — every other
        // value, including FULL_DECOMPOSITION, throws
        // IllegalArgumentException("Bad mode: " + mode) unconditionally. The
        // desktop JVM's RuleBasedCollator tolerates FULL_DECOMPOSITION, so
        // this assertion — not the sort behavior above — is what makes
        // reintroducing that mode fail on the host instead of only on device.
        assertEquals(Collator.CANONICAL_DECOMPOSITION, BinSorting.newCollator().decomposition)
    }
}

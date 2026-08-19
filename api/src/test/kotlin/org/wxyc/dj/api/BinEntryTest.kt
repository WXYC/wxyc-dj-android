package org.wxyc.dj.api

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
        // Aşıq Altay, Csillagrablók, GIDEÖN, Hermanos Gutiérrez, Nilüfer Yanya —
        // the entries the three headline fixtures don't supply. Primary
        // strength + full decomposition means the sort key is accent- and
        // case-insensitive, so this order must match plain alphabetical order
        // on the ASCII skeleton of each name.
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
            listOf("Aşıq Altay", "Csillagrablók", "GIDEÖN", "Hermanos Gutiérrez", "Nilüfer Yanya"),
            sorted,
        )
    }
}

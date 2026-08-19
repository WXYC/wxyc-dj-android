package org.wxyc.dj.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Mirrors the `AlbumSearchResult` cases of `DTODecodingTests.swift`. */
class AlbumSearchResultTest {
    @Test
    fun `decodes a search result`() {
        val row = decode(Fixtures.juanaMolinaSearchResult)
        assertEquals("Juana Molina", row.artistName)
        assertEquals("DOGA", row.albumTitle)
        assertEquals(RotationBin.Heavy, row.rotationBin)
    }

    @Test
    fun `round-trips through encode-decode`() {
        val original = decode(Fixtures.juanaMolinaSearchResult)
        val reEncoded = WxycJson.json.encodeToString(AlbumSearchResult.serializer(), original)
        val roundTripped = WxycJson.json.decodeFromString(AlbumSearchResult.serializer(), reEncoded)
        assertEquals(original, roundTripped)
    }

    @Test
    fun `decodes a null label and other nullable fields`() {
        val raw = """
            {
              "id": 200,
              "add_date": "2024-03-04T00:00:00.000Z",
              "album_title": "Confield",
              "artist_name": "Autechre",
              "code_letters": "AUT",
              "code_number": 7,
              "code_artist_number": 1,
              "format_name": "CD",
              "genre_name": "Electronic",
              "label": null,
              "label_id": null,
              "on_streaming": null,
              "album_artist": null,
              "plays": 12,
              "artwork_url": null
            }
        """.trimIndent()
        val row = decode(raw)
        assertEquals("Autechre", row.artistName)
        assertNull(row.label)
        assertNull(row.onStreaming)
    }

    @Test
    fun `call number formats letters, artist and release`() {
        val raw = """
            { "id": 1, "album_title": "Tri Repetae", "artist_name": "Autechre",
              "code_letters": "AU", "code_artist_number": 3, "code_number": 2 }
        """.trimIndent()
        assertEquals("AU 3/2", decode(raw).callNumber)
    }

    @Test
    fun `call number skips missing legs`() {
        val raw = """{ "id": 1, "album_title": "x", "artist_name": "y", "code_letters": "AU" }"""
        assertEquals("AU", decode(raw).callNumber)
    }

    @Test
    fun `an unrecognized rotation_bin decodes to null rather than throwing`() {
        val raw = """{ "id": 1, "album_title": "x", "artist_name": "y", "rotation_bin": "N" }"""
        assertNull(decode(raw).rotationBin)
    }

    @Test
    fun `matched_via defaults to an empty list when absent`() {
        assertTrue(decode(Fixtures.juanaMolinaSearchResult).matchedVia.isEmpty())
    }

    @Test
    fun `decodes an explicit empty matched_via array`() {
        val raw = """{ "id": 1, "album_title": "DOGA", "artist_name": "Juana Molina", "matched_via": [] }"""
        assertTrue(decode(raw).matchedVia.isEmpty())
    }

    @Test
    fun `decodes a cta match hint`() {
        val raw = """
            {
              "id": 410,
              "album_title": "Duke Ellington & John Coltrane",
              "artist_name": "Duke Ellington & John Coltrane",
              "matched_via": [
                { "title": "In a Sentimental Mood", "artist_credit": "Duke Ellington & John Coltrane",
                  "confidence": 1.0, "source": "cta" }
              ]
            }
        """.trimIndent()
        val hint = decode(raw).matchedVia.single()
        assertEquals("In a Sentimental Mood", hint.title)
        assertEquals("Duke Ellington & John Coltrane", hint.artistCredit)
        assertNull(hint.position)
        assertEquals(1.0, hint.confidence)
        assertEquals(TrackMatchSource.Cta, hint.source)
    }

    @Test
    fun `decodes multiple hints preserving order`() {
        val raw = """
            {
              "id": 60359, "album_title": "Confield", "artist_name": "Autechre",
              "matched_via": [
                { "title": "VI Scose Poise", "source": "discogs_master" },
                { "title": "Eutow", "source": "discogs_master" },
                { "title": "Pen Expers", "source": "discogs_master" }
              ]
            }
        """.trimIndent()
        val hints = decode(raw).matchedVia
        assertEquals(listOf("VI Scose Poise", "Eutow", "Pen Expers"), hints.map { it.title })
        assertTrue(hints.all { it.source == TrackMatchSource.DiscogsMaster })
    }

    @Test
    fun `decodes an unrecognized match source as Unknown rather than failing`() {
        val raw = """
            {
              "id": 1, "album_title": "x", "artist_name": "y",
              "matched_via": [ { "title": "song", "source": "musicbrainz_recording" } ]
            }
        """.trimIndent()
        assertEquals(TrackMatchSource.Unknown, decode(raw).matchedVia.single().source)
    }

    @Test
    fun `drops a hint with a missing source, keeping well-formed siblings`() {
        val raw = """
            {
              "id": 60359, "album_title": "Confield", "artist_name": "Autechre",
              "matched_via": [
                { "title": "VI Scose Poise", "source": "discogs_master" },
                { "title": "no source key at all" }
              ]
            }
        """.trimIndent()
        val hints = decode(raw).matchedVia
        assertEquals(1, hints.size)
        assertEquals("VI Scose Poise", hints.single().title)
    }

    @Test
    fun `drops a hint with an explicit null source`() {
        val raw = """
            {
              "id": 60360, "album_title": "Amber", "artist_name": "Autechre",
              "matched_via": [
                { "title": "null source hint", "source": null },
                { "title": "Slip", "source": "cta" }
              ]
            }
        """.trimIndent()
        val hints = decode(raw).matchedVia
        assertEquals(1, hints.size)
        assertEquals("Slip", hints.single().title)
    }

    private fun decode(json: String): AlbumSearchResult =
        WxycJson.json.decodeFromString(AlbumSearchResult.serializer(), json)
}

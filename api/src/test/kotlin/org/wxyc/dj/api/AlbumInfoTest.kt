package org.wxyc.dj.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Mirrors the `AlbumInfo` cases of `DTODecodingTests.swift`. */
class AlbumInfoTest {
    @Test
    fun `decodes AlbumInfo with a nested rotation`() {
        val info = decode(Fixtures.albumInfoJSON)
        assertEquals("DOGA", info.albumTitle)
        assertEquals("H", info.rotation?.rotationBin)
        assertEquals(RotationBin.Heavy, info.rotation?.rotationCohort)
        assertNull(info.rotation?.killDate)
        assertEquals("2025-10-15", info.rotation?.addDate)
    }

    @Test
    fun `falls back from label to record_label`() {
        val raw = """
            { "id": 1, "album_title": "DOGA", "artist_name": "Juana Molina", "record_label": "Sonamos" }
        """.trimIndent()
        assertEquals("Sonamos", decode(raw).label)
    }

    @Test
    fun `prefers label over record_label when both are present`() {
        val raw = """
            { "id": 1, "album_title": "DOGA", "artist_name": "Juana Molina",
              "label": "Sonamos", "record_label": "Other" }
        """.trimIndent()
        assertEquals("Sonamos", decode(raw).label)
    }

    @Test
    fun `an unrecognized rotation_bin does not fail the whole decode`() {
        val raw = """
            {
              "id": 401, "album_title": "Edits", "artist_name": "Chuquimamani-Condori",
              "rotation": { "id": 12, "rotation_bin": "N", "add_date": "2025-10-15", "kill_date": null }
            }
        """.trimIndent()
        val info = decode(raw)
        assertEquals("Edits", info.albumTitle)
        assertEquals("N", info.rotation?.rotationBin)
        assertNull(info.rotation?.rotationCohort)
    }

    @Test
    fun `an empty rotation_bin normalizes to null`() {
        val raw = """
            {
              "id": 401, "album_title": "DOGA", "artist_name": "Juana Molina",
              "rotation": { "id": 12, "rotation_bin": "", "add_date": "2025-10-15" }
            }
        """.trimIndent()
        val info = decode(raw)
        assertNotNull(info.rotation)
        assertNull(info.rotation?.rotationBin)
        assertNull(info.rotation?.rotationCohort)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{ "id": 13, "add_date": "2025-10-15" }""",
            """{ "id": 13, "rotation_bin": null, "add_date": "2025-10-15" }""",
            """{ "rotation_bin": "H" }""",
            "{}",
        ],
    )
    fun `a partial rotation object does not fail the whole decode`(rotationObject: String) {
        val raw = """
            { "id": 401, "album_title": "DOGA", "artist_name": "Juana Molina", "rotation": $rotationObject }
        """.trimIndent()
        val info = decode(raw)
        assertEquals("DOGA", info.albumTitle)
        assertNotNull(info.rotation)
    }

    @Test
    fun `an absent rotation decodes to null`() {
        val raw = """{ "id": 1, "album_title": "On Your Own Love Again", "artist_name": "Jessica Pratt" }"""
        assertNull(decode(raw).rotation)
    }

    @Test
    fun `an explicit null rotation decodes to null`() {
        val raw = """
            { "id": 1, "album_title": "On Your Own Love Again", "artist_name": "Jessica Pratt", "rotation": null }
        """.trimIndent()
        assertNull(decode(raw).rotation)
    }

    @Test
    fun `an empty add_date costs the date, not the screen`() {
        val raw = """
            {
              "id": 401, "album_title": "Edits", "artist_name": "Chuquimamani-Condori",
              "rotation": { "id": 12, "rotation_bin": "H", "add_date": "" }
            }
        """.trimIndent()
        val info = decode(raw)
        assertNull(info.rotation?.addDate)
        assertEquals("H", info.rotation?.rotationBin)
        assertTrue(info.rotation?.isInRotation("2026-06-22") == true)
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-date", "", "2026-6-2", "20260622", "twenty-twenty-six"])
    fun `an unreadable kill date fails closed rather than open`(killDate: String) {
        val raw = """
            {
              "id": 401, "album_title": "Edits", "artist_name": "Chuquimamani-Condori",
              "rotation": { "id": 12, "rotation_bin": "H", "kill_date": "$killDate" }
            }
        """.trimIndent()
        val info = decode(raw)
        assertEquals("Edits", info.albumTitle)
        assertFalse(info.rotation?.isInRotation("2026-06-22") == true)
    }

    @Test
    fun `rotation dates are held verbatim rather than reinterpreted`() {
        val raw = """
            {
              "id": 402, "album_title": "On Your Own Love Again", "artist_name": "Jessica Pratt",
              "rotation": {
                "id": 14, "rotation_bin": "L",
                "add_date": "2026-06-23T20:00:00-04:00",
                "kill_date": "2026-06-23T20:00:00-04:00"
              }
            }
        """.trimIndent()
        val rotation = decode(raw).rotation!!
        assertEquals("2026-06-23T20:00:00-04:00", rotation.addDate)
        assertEquals("2026-06-23T20:00:00-04:00", rotation.killDate)
    }

    private fun decode(json: String): AlbumInfo =
        WxycJson.json.decodeFromString(AlbumInfo.serializer(), json)
}

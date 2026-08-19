package org.wxyc.dj.api

import java.time.Instant
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Pins [InstantSerializer]'s tolerance for the three shapes Backend-Service
 * puts on the wire — ISO-8601 with fractional seconds, without, and a bare
 * calendar day — mirroring `WXYCDateFormattingTests.swift` / the
 * `JSONCoders.decoder` cases.
 */
class InstantSerializerTest {
    @Test
    fun `decodes ISO-8601 with fractional seconds`() {
        val instant = decodeInstant(""""2024-03-04T00:00:00.000Z"""")
        assertEquals(Instant.parse("2024-03-04T00:00:00Z"), instant)
    }

    @Test
    fun `decodes ISO-8601 without fractional seconds`() {
        val instant = decodeInstant(""""2024-03-04T00:00:00Z"""")
        assertEquals(Instant.parse("2024-03-04T00:00:00Z"), instant)
    }

    @Test
    fun `decodes a bare calendar date as midnight UTC`() {
        val instant = decodeInstant(""""2024-03-04"""")
        assertEquals(Instant.parse("2024-03-04T00:00:00Z"), instant)
    }

    @Test
    fun `decodes an offset-bearing timestamp`() {
        val instant = decodeInstant(""""2026-06-23T20:00:00-04:00"""")
        assertEquals(Instant.parse("2026-06-24T00:00:00Z"), instant)
    }

    @Test
    fun `throws on an unrecognized date format`() {
        assertThrows(SerializationException::class.java) {
            decodeInstant(""""twenty-twenty-six"""")
        }
    }

    private fun decodeInstant(json: String): Instant =
        WxycJson.json.decodeFromString(InstantSerializer, json)
}

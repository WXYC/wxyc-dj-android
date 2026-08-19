package org.wxyc.dj.api

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Backend-Service emits ISO-8601 timestamps both with and without fractional
 * seconds, and — on some rotation columns — a bare `YYYY-MM-DD` calendar day.
 * Mirrors `JSONCoders.swift`'s custom date-decoding strategy: try the most
 * specific parser first and fall through on failure, throwing only once none
 * apply.
 *
 * Only used for **top-level** timestamp fields ([AlbumSearchResult.addDate],
 * [AlbumInfo.addDate]). [AlbumInfo.Rotation]'s `add_date`/`kill_date` are held
 * as raw wire strings instead — see that type's KDoc for why turning them into
 * an `Instant` would be actively wrong for [RotationPredicate]'s compare.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        return parseOrNull(raw)
            ?: throw SerializationException("Unrecognized date format: $raw")
    }

    /** Exposed for callers that want to fail soft rather than throw. */
    fun parseOrNull(raw: String): Instant? {
        runCatching { return Instant.parse(raw) }
        runCatching { return OffsetDateTime.parse(raw).toInstant() }
        runCatching { return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant() }
        return null
    }
}

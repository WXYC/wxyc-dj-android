package org.wxyc.dj.api

import java.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A row returned by Backend-Service `GET /library/`. Mirrors
 * `AlbumSearchResult.swift`, hand-authored per the same reasons documented
 * there: [matchedVia] is a non-optional list callers index into without
 * unwrapping, and [RotationBin] is spelled out rather than the generated
 * short codes.
 */
@Serializable
data class AlbumSearchResult(
    @SerialName("id") val id: Int,
    @SerialName("add_date")
    @Serializable(with = InstantSerializer::class)
    val addDate: Instant? = null,
    @SerialName("album_title") val albumTitle: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("code_letters") val codeLetters: String? = null,
    @SerialName("code_number") val codeNumber: Int? = null,
    @SerialName("code_artist_number") val codeArtistNumber: Int? = null,
    @SerialName("format_name") val formatName: String? = null,
    @SerialName("genre_name") val genreName: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("label_id") val labelId: Int? = null,
    // An unknown rotation_bin decodes to null rather than blowing up the row —
    // the same forward-compat hedge RotationPredicate documents.
    @SerialName("rotation_bin")
    @Serializable(with = TolerantRotationBinSerializer::class)
    val rotationBin: RotationBin? = null,
    @SerialName("rotation_id") val rotationId: Int? = null,
    @SerialName("plays") val plays: Int? = null,
    @SerialName("on_streaming") val onStreaming: Boolean? = null,
    @SerialName("album_artist") val albumArtist: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    // Per-element, not a plain List<TrackMatchHint>: LML is a separately
    // deployed service, and Backend-Service passes matched_via through
    // unvalidated, so one malformed hint on the wire must not fail this whole
    // row (and, by extension, the whole [AlbumSearchResult] array a search
    // response decodes all-or-nothing). See FailableTrackMatchHintListSerializer.
    @SerialName("matched_via")
    @Serializable(with = FailableTrackMatchHintListSerializer::class)
    val matchedVia: List<TrackMatchHint> = emptyList(),
) {
    /** Shelf call number, e.g. "AU 3/2". Any missing leg is dropped. */
    val callNumber: String
        get() = formatCallNumber(codeLetters, codeArtistNumber, codeNumber)

    companion object {
        fun formatCallNumber(letters: String?, artistNumber: Int?, releaseNumber: Int?): String {
            val parts = mutableListOf<String>()
            if (!letters.isNullOrEmpty()) parts.add(letters)
            when {
                artistNumber != null && releaseNumber != null -> parts.add("$artistNumber/$releaseNumber")
                artistNumber != null -> parts.add("$artistNumber")
                releaseNumber != null -> parts.add("$releaseNumber")
            }
            return parts.joinToString(" ")
        }
    }
}

/** The DJ-facing rotation cohort. Mirrors `RotationBin` on iOS. */
enum class RotationBin(val wireValue: String, val label: String) {
    Heavy("H", "Heavy"),
    Medium("M", "Medium"),
    Light("L", "Light"),
    Single("S", "Single"),
    ;

    companion object {
        fun fromWireValue(raw: String?): RotationBin? = raw?.let { value -> entries.firstOrNull { it.wireValue == value } }
    }
}

/**
 * Decodes `rotation_bin` tolerantly: a value outside the current H/M/L/S
 * cohorts decodes to `null` instead of throwing, and a bin added server-side
 * ahead of this app therefore doesn't fail the enclosing row. Implemented as a
 * serializer for the whole nullable type (not `KSerializer<RotationBin>`
 * wrapped automatically) because the automatic null-wrapper only absorbs a
 * literal JSON `null` — an unrecognized *non-null* string still needs to fall
 * through to `null` here.
 */
object TolerantRotationBinSerializer : KSerializer<RotationBin?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("RotationBin", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: RotationBin?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.wireValue)
        }
    }

    override fun deserialize(decoder: Decoder): RotationBin? {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        return RotationBin.fromWireValue(element.jsonPrimitive.content)
    }
}

/**
 * Isolates a single malformed [TrackMatchHint] inside `matched_via` instead of
 * failing the whole array decode — the Kotlin analogue of iOS's
 * `FailableDecodable`. `source` is non-optional on [TrackMatchHint], so a hint
 * missing that key, or carrying an explicit `null` there, throws on its own
 * decode; this serializer catches that per element and drops it, leaving its
 * well-formed siblings intact.
 */
object FailableTrackMatchHintListSerializer : KSerializer<List<TrackMatchHint>> {
    override val descriptor: SerialDescriptor = ListSerializer(TrackMatchHint.serializer()).descriptor

    override fun serialize(encoder: Encoder, value: List<TrackMatchHint>) {
        val jsonEncoder = encoder as JsonEncoder
        val array = JsonArray(value.map { jsonEncoder.json.encodeToJsonElement(TrackMatchHint.serializer(), it) })
        jsonEncoder.encodeJsonElement(array)
    }

    override fun deserialize(decoder: Decoder): List<TrackMatchHint> {
        val jsonDecoder = decoder as JsonDecoder
        val array = jsonDecoder.decodeJsonElement()
        if (array !is JsonArray) return emptyList()
        return array.mapNotNull { element ->
            try {
                jsonDecoder.json.decodeFromJsonElement(TrackMatchHint.serializer(), element)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

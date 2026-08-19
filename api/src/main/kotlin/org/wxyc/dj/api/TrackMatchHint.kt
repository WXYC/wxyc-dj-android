package org.wxyc.dj.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A single track-title match that drove a search row into results (catalog
 * track-search Track 1 CTA fallback, or Track 2 LML proxy). Empty on a normal
 * artist or album hit. Mirrors `TrackMatchHint.swift`.
 */
@Serializable
data class TrackMatchHint(
    @SerialName("title") val title: String,
    @SerialName("artist_credit") val artistCredit: String? = null,
    @SerialName("position") val position: String? = null,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("source") val source: TrackMatchSource,
)

/**
 * Forward-compatible with a source the server may add ahead of this app: an
 * unrecognized wire value decodes to [Unknown] rather than failing the
 * enclosing [TrackMatchHint] (which, via [FailableTrackMatchHintListSerializer],
 * would otherwise drop the whole hint for a reason this enum can absorb
 * instead).
 */
@Serializable(with = TrackMatchSourceSerializer::class)
enum class TrackMatchSource(val wireValue: String) {
    Cta("cta"),
    DiscogsMaster("discogs_master"),
    LibraryIdentity("library_identity"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWireValue(raw: String): TrackMatchSource = entries.firstOrNull { it.wireValue == raw } ?: Unknown
    }
}

object TrackMatchSourceSerializer : KSerializer<TrackMatchSource> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TrackMatchSource", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TrackMatchSource) = encoder.encodeString(value.wireValue)

    override fun deserialize(decoder: Decoder): TrackMatchSource = TrackMatchSource.fromWireValue(decoder.decodeString())
}

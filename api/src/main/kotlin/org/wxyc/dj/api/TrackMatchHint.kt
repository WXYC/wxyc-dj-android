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
 *
 * The four real values are api.yaml's `TrackMatchSource` schema, verbatim:
 * `cta` (Backend's local `compilation_track_artist` lookup, Track 1),
 * `discogs_release` (LML matched via a Discogs release id, Track 2),
 * `discogs_master` (LML matched via a Discogs master id, Track 2), and
 * `library_identity` (an explicit substrate match, post-cross-cache-identity).
 * [Unknown]'s wire value is `unknown_default_open_api`, matching the
 * generated iOS enum's `unknownDefaultOpenApi` sentinel — not the
 * free-standing string `"unknown"`, which the spec never emits and which
 * would therefore never collide with [Unknown] on decode anyway.
 *
 * The mapping is lossy in one direction: [TrackMatchSourceSerializer] decodes
 * *any* unrecognized string to [Unknown], but serializing [Unknown] always
 * writes `unknown_default_open_api` — the original unrecognized string is not
 * retained, so encode(decode(x)) != x for an unrecognized x. This app never
 * re-encodes a decoded [TrackMatchHint] back onto the wire, so the loss is
 * inert today; it matches the generated iOS type's behavior, which has the
 * same property for the same reason.
 */
@Serializable(with = TrackMatchSourceSerializer::class)
enum class TrackMatchSource(val wireValue: String) {
    Cta("cta"),
    DiscogsRelease("discogs_release"),
    DiscogsMaster("discogs_master"),
    LibraryIdentity("library_identity"),
    Unknown("unknown_default_open_api"),
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

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
 * retained, so encode(decode(x)) != x for an unrecognized x. **As of issue
 * #23 this is a latent property again, and the history is worth keeping.**
 * It was briefly live: issue #7's `AlbumSearchResultNavType` re-encoded a
 * decoded [org.wxyc.dj.api.AlbumSearchResult] -- which embeds a
 * `List<TrackMatchHint>` via `matchedVia` -- back through
 * [org.wxyc.dj.api.WxycJson] on every navigation to an album detail, so any
 * row carrying an unrecognized [TrackMatchSource] genuinely round-tripped
 * through the lossy direction. Issue #23 made `AlbumRoute` id-only and
 * deleted that `NavType`, so no production path re-encodes a decoded
 * [org.wxyc.dj.api.AlbumSearchResult] any more. It matches the generated
 * iOS type's behavior, which has the same property for the same reason.
 *
 * What would make it live again: anything that decodes an
 * [org.wxyc.dj.api.AlbumSearchResult] from the server and re-encodes it for
 * storage or transport -- an offline row cache, a saved-state hand-off that
 * serializes the row rather than holding it in memory the way
 * `AlbumRouteFallbackStore` does. Such a path is only safe while nothing
 * reads [TrackMatchSource] back off the re-encoded copy; check that before
 * adding one.
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

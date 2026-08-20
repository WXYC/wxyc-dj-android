package org.wxyc.dj.api

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Decoded shape of `GET /library/info?album_id=X`: an album plus denormalized
 * artist/code/format/genre and an optional nested rotation block. Mirrors
 * `AlbumInfo.swift`.
 */
@Serializable
data class AlbumInfo(
    @SerialName("id") val id: Int,
    @SerialName("artist_id") val artistId: Int? = null,
    @SerialName("album_title") val albumTitle: String,
    @SerialName("code_number") val codeNumber: Int? = null,
    @SerialName("code_letters") val codeLetters: String? = null,
    @SerialName("code_artist_number") val codeArtistNumber: Int? = null,
    @SerialName("artist_name") val artistName: String,
    @SerialName("format_name") val formatName: String? = null,
    @SerialName("genre_name") val genreName: String? = null,
    // /library/info uses `record_label`; the catalog list endpoint uses
    // `label`. Both are decoded and `label` (below) prefers labelRaw,
    // falling back to recordLabelRaw — a deliberately explicit precedence
    // rather than relying on kotlinx's @JsonNames tie-break, which is
    // unspecified when both keys are present in the same object.
    @SerialName("label") private val labelRaw: String? = null,
    @SerialName("record_label") private val recordLabelRaw: String? = null,
    @SerialName("label_id") val labelId: Int? = null,
    @SerialName("add_date")
    @Serializable(with = InstantSerializer::class)
    val addDate: Instant? = null,
    @SerialName("disc_quantity") val discQuantity: Int? = null,
    @SerialName("alternate_artist_name") val alternateArtistName: String? = null,
    @SerialName("album_artist") val albumArtist: String? = null,
    @SerialName("plays") val plays: Int? = null,
    @SerialName("on_streaming") val onStreaming: Boolean? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("rotation") val rotation: Rotation? = null,
) {
    /** Shelf call number, mirroring [AlbumSearchResult.callNumber]. */
    val callNumber: String
        get() = AlbumSearchResult.formatCallNumber(codeLetters, codeArtistNumber, codeNumber)

    /** `label` if the wire sent it, else `record_label`. See the constructor KDoc. */
    val label: String? get() = labelRaw ?: recordLabelRaw

    /**
     * The nested `rotation` object on `GET /library/info`. **Every field is
     * optional, deliberately** — api.yaml's nested `rotation` schema declares
     * no `required` list at all, so a present-but-partial object must decode
     * rather than throw `keyNotFound`/`MissingFieldException` out of the
     * enclosing [AlbumInfo] — the same failure class issue #93 exists to
     * close, reached through a different door.
     *
     * `GET /library/info` does not emit `rotation` at all today — this
     * decodes to `null` in practice — but is typed defensively so the day
     * that projection grows a rotation join isn't the day release detail
     * starts failing to load.
     */
    @Serializable
    data class Rotation(
        @SerialName("id") val id: Int? = null,
        /**
         * Raw current-rotation bin verbatim from the wire — **not** the
         * closed [RotationBin] enum. Kept raw as the same
         * forward-compatibility hedge [RotationPredicate] documents: a bin
         * added server-side ahead of this app must decode rather than throw
         * out of the whole [AlbumInfo].
         *
         * Exposed as `null` for a dirty empty string too — the same
         * treatment the raw `rotation_bin` column gets everywhere else, so
         * the online and (future) cloned paths can't disagree about what
         * `""` means. This feeds [RotationPredicate.isInRotation], so an
         * empty string left verbatim would read as in-rotation.
         *
         * Don't read this directly to decide rotation state — a bin can be
         * set on a record whose [killDate] has already passed. Call
         * [isInRotation].
         */
        @SerialName("rotation_bin") private val rotationBinRaw: String? = null,
        /**
         * Date this rotation record began, as the raw `YYYY-MM-DD` the wire
         * carries. A dirty empty string normalizes to `null` — it's
         * decorative, and `null` simply omits the line.
         */
        @SerialName("add_date") private val addDateRaw: String? = null,
        /**
         * Date this rotation record expires, as the raw `YYYY-MM-DD` the wire
         * carries — **not** a decoded `Instant`. A `Date`/`Instant` is an
         * *instant*, not a calendar day, so recovering the wire day means
         * picking a zone to render it back through, and any choice is wrong
         * for some input. Holding the string sidesteps the question:
         * whatever arrives is compared as the server wrote it, via
         * [RotationPredicate].
         *
         * Deliberately **not** normalized like [rotationBinRaw]/[addDateRaw]:
         * for the bin, "empty" and absent both mean "no assignment", but for
         * a kill date `null` means *no expiry* — folding `""` into that would
         * resurrect the forever-in-rotation bug this type guards against. An
         * empty kill date instead reaches [RotationPredicate.isInRotation]
         * intact and fails closed there, with the other unreadable values.
         */
        @SerialName("kill_date") val killDate: String? = null,
    ) {
        val rotationBin: String? get() = rotationBinRaw?.takeIf { it.isNotEmpty() }
        val addDate: String? get() = addDateRaw?.takeIf { it.isNotEmpty() }

        /**
         * The DJ-facing display cohort for [rotationBin], or `null` when the
         * bin is absent or outside those cohorts — both cases [rotationBin]
         * is deliberately typed to survive. Use this only for labelling.
         */
        val rotationCohort: RotationBin? get() = RotationBin.fromWireValue(rotationBin)

        /**
         * Whether this record is in rotation as of [now] in [zone] (defaults:
         * the device's current clock). Delegates to [RotationPredicate] —
         * see that type for why this rule is shared rather than duplicated.
         */
        fun isInRotation(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
            isInRotation(RotationPredicate.localDay(now, zone))

        /**
         * Pure core of [isInRotation]. [today] MUST be the zero-padded
         * `YYYY-MM-DD` local day [RotationPredicate.localDay] produces —
         * the same reason this stays `internal` rather than public; callers
         * outside `:api` go through [isInRotation]'s `Instant`/`ZoneId`
         * overload instead.
         */
        internal fun isInRotation(today: String): Boolean =
            RotationPredicate.isInRotation(bin = rotationBin, killDay = killDate, today = today)
    }
}

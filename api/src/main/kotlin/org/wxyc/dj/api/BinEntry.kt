package org.wxyc.dj.api

import java.text.Collator
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull

/**
 * One release in the DJ's bin, as projected by `djs.service.getBinFromDB` —
 * the `bins` row joined out to `library`/`artists`/`format`/`genres`. Modelled
 * on that **handler projection**, not on api.yaml: the spec still declares a
 * `{dj_id, entries: [...]}` envelope for `GET /djs/bin` that no handler has
 * ever emitted (WXYC/wxyc-shared#344, #359). `GET /djs/bin` returns a **bare
 * array** of this shape instead. Mirrors `BinEntry.swift`.
 *
 * The wire carries **no** `bins.id`, `dj_id`, or added-at timestamp — the
 * projection is library data only, and `DELETE /djs/bin` removes every row
 * for a `(dj, album)` pair. [albumId] is therefore the bin's effective key,
 * reported by [id].
 */
@Serializable
data class BinEntry(
    @SerialName("album_id") val albumId: Int,
    @SerialName("album_title") val albumTitle: String,
    @SerialName("artist_name") val artistName: String,
    /**
     * Filing form of [artistName] ("Molina, Juana"). `NOT NULL` upstream, but
     * optional here so a projection change can't fail the whole row; it only
     * drives sort order, which falls back to [artistName].
     */
    @SerialName("alphabetical_name") val alphabeticalName: String? = null,
    @SerialName("label") val label: String? = null,
    // The call-number legs are nullable in the catalog (V/A compilations,
    // unfiled adds), so keep them optional to match AlbumSearchResult.
    @SerialName("code_letters") val codeLetters: String? = null,
    @SerialName("code_artist_number") val codeArtistNumber: Int? = null,
    @SerialName("code_number") val codeNumber: Int? = null,
    @SerialName("format_name") val formatName: String? = null,
    @SerialName("genre_name") val genreName: String? = null,
    @SerialName("legacy_release_id") val legacyReleaseId: Int? = null,
) {
    /** The library row's id — see the type KDoc for why this is the bin's key. */
    val id: Int get() = albumId

    /** Shelf call number, mirroring [AlbumSearchResult.callNumber]. */
    val callNumber: String
        get() = AlbumSearchResult.formatCallNumber(codeLetters, codeArtistNumber, codeNumber)

    /** Key the bin sorts on: the librarian's filing name, falling back to the display name. */
    val sortName: String get() = alphabeticalName ?: artistName

    companion object {
        /**
         * Collapse rows that address the same album, keeping first-seen
         * order. The wire can genuinely repeat an album — the `/djs/bin`
         * projection omits `track_title`, so an album binned under two
         * tracks arrives twice, and `DELETE /djs/bin` clears the album
         * wholesale — so to every reader they are one row.
         */
        fun deduplicatedByAlbum(entries: List<BinEntry>): List<BinEntry> {
            val seen = mutableSetOf<Int>()
            return entries.filter { seen.add(it.albumId) }
        }
    }
}

/**
 * Decodes `GET /djs/bin`'s bare-array body. `[]` is an authoritative empty
 * bin and decodes to an empty list; a JSON `null` body is **not** a bin and
 * must throw rather than coerce to empty — dj-site coerces, this must not
 * (issue #60). The client establishes this contract for the phase-2 offline
 * snapshot, where "written empty" and "never written" have to stay
 * distinguishable.
 */
object BinResponse {
    private val listSerializer = ListSerializer(BinEntry.serializer())

    fun decode(body: String): List<BinEntry> {
        val element = WxycJson.json.parseToJsonElement(body)
        if (element is JsonNull) {
            throw SerializationException("GET /djs/bin returned null; an authoritative empty bin is []")
        }
        return WxycJson.json.decodeFromJsonElement(listSerializer, element)
    }
}

/**
 * Sorts a deduplicated bin the way the DJ sees it: filing name
 * ([BinEntry.sortName]), then album title. The server issues no `ORDER BY`
 * for this projection, so its order is arbitrary and would reshuffle between
 * refreshes.
 *
 * `localizedStandardCompare` has no exact Kotlin analogue —
 * [java.text.Collator] is the equivalent, but **only** when [strength] and
 * [decomposition] are set explicitly rather than left at [Collator]'s own
 * defaults. Android's `Collator` delegates to `android.icu`; the desktop
 * JVM's does not, and default collation rules are not guaranteed to agree —
 * so a green `:api:test` here can still be wrong on device unless both knobs
 * are pinned. See `BinCollationParityTest` in `:app` for the Robolectric
 * check that this actually holds on the platform `Collator`.
 */
object BinSorting {
    fun sorted(entries: List<BinEntry>): List<BinEntry> {
        val collator = newCollator()
        return entries.sortedWith(
            compareBy(collator) { it.sortName }.thenComparator { a, b -> collator.compare(a.albumTitle, b.albumTitle) },
        )
    }

    /**
     * `Locale.US`, [Collator.PRIMARY] strength (case- and accent-insensitive —
     * "Molina" and "molina" sort together, as do "Nilüfer" and "Nilufer"),
     * [Collator.CANONICAL_DECOMPOSITION] (normalizes precomposed vs.
     * combining-mark accent forms before comparing, so the same visual name
     * sorts the same regardless of which Unicode form the wire used). Both
     * knobs are pinned explicitly rather than left at the platform default —
     * that default is exactly the thing that is not guaranteed to agree
     * between the desktop JVM and `android.icu`.
     *
     * **`CANONICAL_DECOMPOSITION`, never `FULL_DECOMPOSITION`.** Android's
     * `java.text.Collator` is `android.icu`-backed, and its private
     * `decompositionMode_Java_ICU(int)` converter accepts *only*
     * `CANONICAL_DECOMPOSITION` and `NO_DECOMPOSITION` — every other value,
     * including `FULL_DECOMPOSITION`, falls through its switch to
     * `throw new IllegalArgumentException("Bad mode: " + mode)`. That is
     * unconditionally fatal on every supported API level: the desktop JVM's
     * `RuleBasedCollator` accepts `FULL_DECOMPOSITION` (which is why a plain
     * `:api:test` run can't catch this), but the first Bin-tab load on a real
     * phone would throw before comparing a single name. Canonical
     * decomposition is sufficient for the precomposed-vs-combining-mark
     * normalization this method exists for; full (compatibility)
     * decomposition additionally folds things like ligatures and width
     * variants, which this sort has no need of. Do not "upgrade" this back to
     * `FULL_DECOMPOSITION` — see `BinEntryTest`'s
     * `newCollator uses a decomposition mode Android's Collator actually
     * accepts` case for the host-side regression pin, and the SDK source at
     * `java/text/Collator.java` (`setDecomposition` /
     * `decompositionMode_Java_ICU`) for the throwing switch itself.
     */
    fun newCollator(): Collator = Collator.getInstance(Locale.US).apply {
        strength = Collator.PRIMARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }

    private fun compareBy(collator: Collator, selector: (BinEntry) -> String): Comparator<BinEntry> =
        Comparator { a, b -> collator.compare(selector(a), selector(b)) }
}

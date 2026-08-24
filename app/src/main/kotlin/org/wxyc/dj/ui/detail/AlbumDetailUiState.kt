package org.wxyc.dj.ui.detail

import org.wxyc.dj.api.AlbumInfo
import org.wxyc.dj.api.AlbumMetadata
import org.wxyc.dj.api.AlbumSearchResult

/**
 * One frame for [AlbumDetailScreen] (issue #10, port of `AlbumDetailView`'s
 * `@State`). A single immutable snapshot, matching [org.wxyc.dj.ui.login.LoginUiState]'s
 * shape, so [AlbumDetailViewModel] can update several related fields (e.g.
 * `info` and `infoLoaded` together) in one atomic `copy()`.
 *
 * [catalogResolution] and [preferredArtworkUrl] are computed, not stored --
 * they are pure functions of the fields above (see `AlbumDetailPrecedence.kt`),
 * so there is nowhere for them to drift out of sync with the state they're
 * derived from.
 */
data class AlbumDetailUiState(
    /** The row the caller already had in hand (search result today; a bin row once issue #11 lands), read once from `AlbumRouteFallbackStore` at construction. */
    val fallback: AlbumSearchResult? = null,
    /** `GET /library/info` -- the shelf source of truth. */
    val info: AlbumInfo? = null,
    /** Set once the `/library/info` load has settled, success or failure -- gates [AlbumDetailPrecedence.shouldShowMetadataLabel] so the Release-section label doesn't render-then-collapse. */
    val infoLoaded: Boolean = false,
    /** `/library/info` failed. Distinct from [infoLoaded]: both flip together, but [resolveCatalog] needs to tell "still loading" from "failed" apart. */
    val infoFailed: Boolean = false,
    /** `GET /proxy/metadata/album` (LML) -- best-effort; absence is never an error state on its own. */
    val metadata: AlbumMetadata? = null,
    /** The LML failure reason, rendered as a faint footer note -- never a red banner. `null` once metadata loads, or before any attempt. */
    val metadataError: String? = null,
    /** URLs the header's cover image has genuinely finished failing to load (issue #86) -- see [AlbumDetailViewModel.recordArtworkFailure]. Permanent for the screen's life, keyed by URL string. */
    val failedArtworkUrls: Set<String> = emptySet(),
    val addInFlight: Boolean = false,
    val addedToBin: Boolean = false,
    val addError: String? = null,
) {
    /** Invariant 18's render half -- see `resolveCatalog`'s KDoc. */
    val catalogResolution: CatalogResolution get() = resolveCatalog(info = info, fallback = fallback, infoFailed = infoFailed)

    /** Invariant 17 -- see `preferredArtworkUrl`'s KDoc. */
    val preferredArtworkUrl: String? get() = preferredArtworkUrl(info = info, fallback = fallback, metadata = metadata, failedUrls = failedArtworkUrls)

    /** The catalog label as actually established for the header -- `info`'s when online, else the resolved fallback's. The LML "Label" row dedups against *this*, not `info?.label` alone (see [AlbumDetailPrecedence.shouldShowMetadataLabel]). */
    val catalogLabel: String? get() = info?.label ?: catalogResolution.catalogRow?.label

    /** The title/artist the header renders while waiting on or after losing `/library/info`. */
    val displayTitle: String? get() = info?.albumTitle ?: catalogResolution.catalogRow?.albumTitle
    val displayArtist: String? get() = info?.artistName ?: catalogResolution.catalogRow?.artistName
    val displayLabel: String? get() = info?.label ?: metadata?.label ?: catalogResolution.catalogRow?.label

    /**
     * The rotation record to show, or `null` when there is none or it is not
     * currently in rotation -- reads the shared `AlbumInfo.Rotation.isInRotation()`
     * predicate (invariant 14), never re-derives the rule. No offline
     * counterpart in this v1 port: rotation only ever comes from a live
     * `/library/info` response, since there is no on-device clone to fall
     * back to.
     */
    val activeRotation: AlbumInfo.Rotation? get() = info?.rotation?.takeIf { it.isInRotation() }
}

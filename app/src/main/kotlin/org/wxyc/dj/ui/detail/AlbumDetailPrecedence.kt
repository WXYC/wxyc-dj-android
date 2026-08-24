package org.wxyc.dj.ui.detail

import org.wxyc.dj.api.AlbumInfo
import org.wxyc.dj.api.AlbumMetadata
import org.wxyc.dj.api.AlbumSearchResult

/**
 * The pure decisions [AlbumDetailScreen] renders from (issue #10, invariants
 * 17-18). Kept as plain top-level functions over [AlbumDetailUiState]'s raw
 * fields -- not methods on a resolved view model -- so each is unit-testable
 * without constructing a [AlbumDetailViewModel] or driving Compose, mirroring
 * `AlbumDetailView.swift`'s `static func`s on iOS (`AlbumDetailPrecedenceTest`
 * is the direct port of `AlbumDetailArtworkTests`/`AlbumDetailFallbackTests`).
 *
 * There is deliberately **no on-device catalog clone leg here** -- that is a
 * phase-2 feature (`docs/port-plan.md`), so the precedence chain this module
 * ports is `/library/info` -> the live [AlbumSearchResult] fallback -> LML,
 * one step shorter than iOS's four-source chain.
 */

/**
 * Header artwork precedence (invariant 17). `info` -- the catalog row, the
 * source of truth for shelf data -- wins when it carries a cover; the live
 * `fallback` (the row the DJ already tapped through from search, or #11's
 * bin) is next; LML's best-effort `metadata` art is the last resort, because
 * it can resolve to a **label-level image instead of the cover** (e.g.
 * Autechre's *Confield* coming back as the Warp Records logo) and must never
 * displace catalog art already on screen.
 *
 * **Read directly off the three raw sources, not through [resolveCatalog].**
 * That resolver drops `fallback` the instant `info` lands -- correct for
 * shelf *fields*, which `/library/info` re-states authoritatively, but wrong
 * for artwork: Backend-Service's `getAlbumFromDB` select (the handler behind
 * `/library/info`) does not project `artwork_url`, so `info.artworkUrl` is in
 * practice always `null`. Routing artwork through the resolver would knock
 * the fallback's cover out of the running the moment `/library/info` landed
 * and hand the slot to LML's art -- the "cover swaps a beat after tapping a
 * search result" regression this function exists to prevent. This is the
 * exact split the issue calls out as "the regression that shipped once."
 *
 * **Issue #86 dead-URL fallthrough.** [failedUrls] is the set of URLs the
 * header has already tried and genuinely failed to load -- populated only
 * from Coil's error result, never from a still-loading state (see
 * `AlbumDetailViewModel.recordArtworkFailure`). Candidates are walked in the
 * precedence order above and the first one **not** in [failedUrls] wins, so a
 * dead catalog URL (an expired pre-signed CDN signature, a purged asset)
 * falls through to the next source instead of leaving the header blank.
 * Keying by URL rather than a bare "the catalog failed" flag matters twice:
 * a failure recorded against one source's URL can never suppress a
 * *different*, healthy URL from another source, and a URL shared by two
 * sources (the fallback and, if it were ever added, a clone) is retired in
 * one record. An empty [failedUrls] (nothing recorded as failed yet)
 * reproduces the pre-#86 behavior exactly -- nothing is skipped merely
 * because it hasn't loaded yet, which is what keeps catalog art from ever
 * being displaced by LML while genuinely in flight.
 */
internal fun preferredArtworkUrl(
    info: AlbumInfo?,
    fallback: AlbumSearchResult?,
    metadata: AlbumMetadata?,
    failedUrls: Set<String> = emptySet(),
): String? {
    val candidates = listOfNotNull(info?.artworkUrl, fallback?.artworkUrl, metadata?.artworkUrl)
    return candidates.firstOrNull { it !in failedUrls }
}

/** What the header and catalog section render from once `/library/info` settles, and how to frame a failure. Never a red error -- see [Note]. */
data class CatalogResolution(
    /** The row the header/catalog section render from while `info` is `null`. `null` -> a spinner (still loading) or a minimal header (failed, nothing to show). */
    val catalogRow: AlbumSearchResult?,
    /** The quiet footer note to surface, or `null`. */
    val note: Note?,
) {
    enum class Note {
        /** `info` failed, but the live fallback row is still being rendered. */
        FALLBACK_ROW,

        /** `info` failed and there is nothing left to render but a minimal header. */
        UNAVAILABLE,
    }
}

/**
 * Pure precedence resolver for the catalog/shelf fields (invariant 18's
 * counterpart for rendering, not fetching -- see [AlbumDetailViewModel] for
 * the fan-out itself). `info` wins outright; while it is still loading, the
 * live `fallback` renders un-framed (no note -- this is the ordinary online
 * path, where the fallback shows for a beat before `/library/info` lands);
 * once the load has genuinely failed, the `fallback` renders behind a quiet
 * [CatalogResolution.Note.FALLBACK_ROW] note, or -- with no fallback at all,
 * e.g. a cold system-search deep link in phase 2 -- a minimal header behind
 * [CatalogResolution.Note.UNAVAILABLE].
 */
internal fun resolveCatalog(
    info: AlbumInfo?,
    fallback: AlbumSearchResult?,
    infoFailed: Boolean,
): CatalogResolution = when {
    info != null -> CatalogResolution(catalogRow = null, note = null)
    !infoFailed -> CatalogResolution(catalogRow = fallback, note = null)
    fallback != null -> CatalogResolution(catalogRow = fallback, note = CatalogResolution.Note.FALLBACK_ROW)
    else -> CatalogResolution(catalogRow = null, note = CatalogResolution.Note.UNAVAILABLE)
}

/**
 * Whether LML's best-effort `metadataLabel` earns its own Release-section
 * "Label" row: only once the catalog row has settled ([infoLoaded], else it
 * would render-then-collapse the instant `/library/info` lands), when the
 * label is non-empty, and when it actually diverges from [catalogLabel] --
 * a matching label is a redundant duplicate of the one the header already
 * shows.
 */
internal fun shouldShowMetadataLabel(
    metadataLabel: String?,
    catalogLabel: String?,
    infoLoaded: Boolean,
): Boolean {
    if (!infoLoaded || metadataLabel.isNullOrEmpty()) return false
    return metadataLabel != catalogLabel
}

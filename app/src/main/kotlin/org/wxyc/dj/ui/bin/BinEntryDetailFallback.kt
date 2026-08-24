package org.wxyc.dj.ui.bin

import org.wxyc.dj.api.AlbumSearchResult
import org.wxyc.dj.api.BinEntry

/**
 * Bridges a [BinEntry] to the [AlbumSearchResult] shape
 * `AlbumRouteFallbackStore.stash` takes, so a bin row tap renders the detail
 * header instantly -- title, artist, label, and all three call-number legs --
 * rather than waiting on `/library/info` (issue #11's row-tap requirement).
 *
 * Why this bridge lives here instead of as a shared factory in `:api`, the way
 * iOS solves the identical problem: iOS's `AlbumSearchResult.headerStandIn` is
 * one factory that both `CatalogRow` and `BinEntry` bridge through, so the
 * rationale for which fields a stand-in can carry -- and which it deliberately
 * drops -- is written once. That is the right shape here too: this repo has no
 * `CatalogRow` yet (the on-device catalog clone is a later phase per
 * `docs/port-plan.md`), but a `headerStandIn` equivalent on
 * [AlbumSearchResult] itself is still the correct long-term home once a second
 * caller exists to justify it. It isn't built that way yet because
 * [AlbumSearchResult] lives in `:api`, outside this issue's file ownership --
 * so this is a single-caller, `ui/bin`-local bridge for now, not the shared
 * factory. Promote it to `:api` alongside the catalog-clone work rather than
 * duplicating this reasoning a second time.
 *
 * What's carried and what's dropped, and why: the `/djs/bin` projection
 * (`djs.service.getBinFromDB`) has no `artwork_url`, `plays`, `on_streaming`,
 * `add_date`, `label_id`, `rotation_bin`, `rotation_id`, or `matched_via`, so
 * every one of those stays at [AlbumSearchResult]'s own default (null or an
 * empty list) rather than being guessed at. An artwork-less fallback is the
 * expected shape downstream, not a gap this bridge should paper over: issue
 * #10's artwork-precedence chain falls through past a null artwork URL to the
 * next source in line.
 */
internal fun BinEntry.toDetailFallback(): AlbumSearchResult = AlbumSearchResult(
    id = albumId,
    albumTitle = albumTitle,
    artistName = artistName,
    codeLetters = codeLetters,
    codeNumber = codeNumber,
    codeArtistNumber = codeArtistNumber,
    formatName = formatName,
    genreName = genreName,
    label = label,
)

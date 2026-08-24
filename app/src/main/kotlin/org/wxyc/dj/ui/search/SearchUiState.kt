package org.wxyc.dj.ui.search

import org.wxyc.dj.api.AlbumSearchResult

/**
 * One frame for [SearchScreen] (issue #9, port of `WXYCDJ/Search/SearchViewModel.swift`'s
 * `State` enum plus its `results`/`source` properties). Trimmed relative to
 * iOS in one deliberate way: there is no on-device catalog clone in this
 * phase (`docs/port-plan.md`'s "Notes for the implementer" -- "There is no
 * offline clone in v1 -- call the client directly"), so this carries no
 * `source` field distinguishing server vs. local results the way iOS's
 * `LibrarySearchOutcome` does, and a failed request degrades to
 * [SearchState.Empty] rather than falling back to a clone.
 */
data class SearchUiState(
    /** The raw, untrimmed text in the search field. */
    val query: String = "",
    val state: SearchState = SearchState.Idle,
    val results: List<AlbumSearchResult> = emptyList(),
    /**
     * Per-row inline add-to-bin progress, keyed by [AlbumSearchResult.id]. A
     * row absent from this map has never had "Add to Bin" tapped. Kept as
     * part of this one immutable snapshot -- rather than a second,
     * independent `StateFlow` -- so a failure on one row updates atomically
     * alongside everything else Compose reads to render a frame; the row
     * list itself ([results]) is untouched by an add-to-bin outcome, which
     * is what keeps a failure from disturbing scroll position (issue #9's
     * acceptance criteria) -- `LazyColumn`'s `key = row.id` in
     * `SearchScreen.kt` means only the affected row recomposes.
     */
    val addToBinStatus: Map<Int, AddToBinStatus> = emptyMap(),
)

/**
 * The four states issue #9's acceptance criteria names. An empty result is a
 * real answer, not a failure -- [SearchState.Empty] is a first-class state
 * alongside [SearchState.Results], not something folded into an error path.
 */
enum class SearchState { Idle, Searching, Results, Empty }

/** Where one row's inline "Add to Bin" tap currently stands. Absent (no map entry) means never tapped. */
enum class AddToBinStatus { InFlight, Added, Failed }

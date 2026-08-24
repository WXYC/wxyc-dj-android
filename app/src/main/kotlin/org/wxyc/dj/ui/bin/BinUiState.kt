package org.wxyc.dj.ui.bin

import org.wxyc.dj.api.BinEntry

/**
 * Everything [BinScreen] needs to render one frame of the bin tab (issue
 * #11, port of `WXYCDJ/Bin/BinViewModel.swift`'s `State` enum). A sealed
 * hierarchy rather than one flat data class with a `List<BinEntry>` plus
 * booleans -- the issue is explicit that loading, populated, empty, and
 * error are "genuinely different answers", and [Populated] structurally
 * cannot carry an empty list (see its `init` block), so the one collapse
 * the issue calls out by name -- an authoritative empty bin rendering as,
 * or being confused with, a failed fetch -- is impossible to construct
 * rather than merely tested against.
 *
 * [isRefreshing] and [message] live on [Populated] and [Empty], not as
 * top-level fields alongside this sealed type, because they are only ever
 * meaningful once a bin has loaded once: [BinViewModel.refresh] can only
 * *start* a pull-to-refresh from a state that already has something on
 * screen (an in-flight first load is already [Loading], which has no
 * `entries` to keep showing), and a remove failure has no row to restore
 * a message onto before the first successful load either.
 */
sealed interface BinUiState {

    /** The first fetch, before anything has ever loaded successfully this session. */
    data object Loading : BinUiState

    /**
     * A non-empty, deduplicated, shelf-sorted bin. [BinViewModel] is the
     * only place that constructs this -- always through
     * [BinViewModel.dedupedAndSorted] -- so "sorted" and "deduplicated" are
     * invariants of every instance on screen, not something each renderer
     * has to re-derive.
     *
     * @property isRefreshing True while a pull-to-refresh triggered from
     *   this state is in flight. The list stays on screen underneath --
     *   [BinViewModel.refresh] never regresses a loaded list to [Loading].
     * @property message A transient, one-shot explanation for the DJ:
     *   either a refresh that failed *after* a bin was already loaded (the
     *   list you're looking at is unaffected -- see
     *   [BinViewModel.refresh]'s KDoc for why that must not become
     *   [Error]), or a swipe-to-remove that failed and was rolled back.
     *   [BinScreen] shows it once (a `Snackbar`) and calls
     *   [BinViewModel.clearMessage] so it doesn't replay on the next
     *   recomposition.
     */
    data class Populated(
        val entries: List<BinEntry>,
        val isRefreshing: Boolean = false,
        val message: String? = null,
    ) : BinUiState {
        init {
            require(entries.isNotEmpty()) {
                "BinUiState.Populated must carry at least one entry -- an empty list is BinUiState.Empty, " +
                    "not Populated(emptyList()). Collapsing the two back into one shape is exactly the " +
                    "empty/error confusion issue #11 exists to rule out."
            }
        }
    }

    /**
     * An authoritative empty bin: the server answered `[]`, which means "you
     * have no records saved," not "something went wrong." See [Error]'s
     * doc for why the two must never render the same way.
     */
    data class Empty(
        val isRefreshing: Boolean = false,
        val message: String? = null,
    ) : BinUiState

    /**
     * The fetch failed and nothing has ever loaded successfully this
     * session -- [message] is the reason. Deliberately unreachable once a
     * bin has loaded once: a refresh failure after that point annotates
     * [Populated]/[Empty] instead (see [BinViewModel.refresh]), because
     * regressing a DJ's already-visible bin to a bare error screen on a
     * transient network hiccup would throw away good data for no reason.
     */
    data class Error(val message: String) : BinUiState
}

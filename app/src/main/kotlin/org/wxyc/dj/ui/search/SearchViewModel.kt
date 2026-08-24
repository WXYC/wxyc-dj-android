@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package org.wxyc.dj.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.wxyc.dj.api.AlbumSearchResult
import org.wxyc.dj.api.ApiClient

/**
 * Backs [SearchScreen] (issue #9, port of `WXYCDJ/Search/SearchViewModel.swift`).
 * Owns the debounced live-search pipeline and the per-row inline add-to-bin
 * action.
 *
 * **`debounce(300) + flatMapLatest`, not iOS's manual task cancellation.**
 * iOS hand-cancels the previous `Task` on every keystroke because Swift's
 * concurrency has no debounce-and-switch operator; Kotlin's `Flow` does, and
 * the issue is explicit that porting the manual-cancellation shape here
 * would be the wrong move. [queryChanges] is the raw keystroke stream;
 * [resultsFlow] is `flatMapLatest`'d onto it downstream of [debounce], so a
 * query that supersedes another -- whether inside the same debounce window
 * (coalesced by `debounce` before a request is ever sent) or after a
 * previous query's request is already in flight (cancelled by
 * `flatMapLatest` switching to the new inner flow, which structurally
 * cancels the suspended [ApiClient.searchLibrary] call beneath it) -- always
 * wins. "Newest query wins" therefore isn't a property this class
 * separately maintains; it falls out of composing two stock operators
 * correctly. See `SearchViewModelTest`'s
 * `the newest query wins over a slower, stale response` test for the proof
 * that this actually depends on `flatMapLatest` (a `flatMapMerge` swap
 * turns it red).
 *
 * **Idle/Searching are set synchronously in [onQueryChanged], ahead of the
 * debounce window.** [_uiState] is updated directly the instant the DJ
 * types, so a query long enough to search shows a spinner immediately
 * rather than 300ms later, and a query that drops below the minimum length
 * clears back to idle immediately rather than waiting on the debounced
 * pipeline to catch up -- mirroring iOS's `state = .searching` being set
 * before its `Task` even sleeps. [queryChanges] carries the same raw text
 * into the debounced pipeline, which is what actually fires (or cancels) the
 * network call once the window settles. The corollary is the
 * unchanged-text guard at the top of [onQueryChanged]: setting a state
 * whose only exit is a downstream emission means text that produces no
 * emission must produce no state change either. See the comment there.
 *
 * **A thrown [org.wxyc.dj.api.ApiError] degrades to [SearchState.Empty]
 * rather than propagating.** There is no on-device catalog clone in v1 to
 * fall back to (`docs/port-plan.md`'s implementer notes), so a failed
 * request reads the same as a real empty answer -- the direct analogue of
 * iOS's `serverErrorWithNoLocalCloneTransitionsToEmpty` test, which is what
 * the fallback collapses to once there is no local clone to distinguish.
 * Catching broadly (any non-cancellation [Exception], not just [ApiError])
 * is load-bearing, not defensive: [init]'s `collect` is a single long-lived
 * coroutine for this view model's entire lifetime, and an uncaught throw
 * out of [resultsFlow] would kill it -- silently disabling every later
 * search, not just the one that failed.
 *
 * **Add-to-bin is a plain, non-suspend trigger, not a `suspend fun` a caller
 * `launch`es from its own scope.** [SearchScreen]'s "Add to Bin" button
 * `onClick` isn't suspend, so [addToBin] runs its own work in
 * [viewModelScope] internally -- which sidesteps the composition-scope trap
 * entirely rather than needing the `launch { ... }.join()` pattern
 * `LoginViewModel` uses for its keyboard-triggered actions (there is no
 * caller-owned `rememberCoroutineScope()` in the picture at all here). The
 * in-flight gate is still checked, and flipped, **synchronously before**
 * `launch` -- exactly the guard `LoginViewModel`'s KDoc argues for -- so two
 * taps landing in the same frame can't both start a request; see
 * `a second tap while adding is a no-op`.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val api: ApiClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /**
     * Raw, untrimmed keystrokes, debounced+`flatMapLatest`'d below into the
     * actual network call. Kept as a stream separate from [_uiState] itself
     * so [onQueryChanged] can flip [SearchUiState.state] synchronously (see
     * class doc) while this stream's consumer still waits out the debounce
     * window before touching the network.
     */
    private val queryChanges = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryChanges
                .debounce(DEBOUNCE_MILLIS)
                .flatMapLatest { raw -> resultsFlow(raw.trim()) }
                .collect { outcome -> applyOutcome(outcome) }
        }
    }

    fun onQueryChanged(raw: String) {
        // Unchanged text is a true no-op, and this guard is load-bearing
        // rather than an optimization. The `Searching` state set below has
        // exactly one exit: an emission out of [queryChanges]. That is a
        // `MutableStateFlow`, so assigning it a value equal to the one it
        // already holds emits *nothing at all* -- which would leave the
        // spinner up with no request behind it and no way to ever clear it.
        // Compose's `String`-valued `BasicTextField` happens to filter
        // unchanged text before calling `onValueChange`, so today's one
        // caller cannot hit this; that is a property of the caller, not of
        // this method, and a second caller (a "search this artist" deep
        // link, a retry affordance) would strand the UI with no hint why.
        // Pinned by `repeating the same query is a no-op, not a stuck
        // spinner`. [_uiState]'s `query` and [queryChanges] are written
        // together here and nowhere else, so reading either as "the current
        // text" is equivalent.
        if (raw == _uiState.value.query) return

        val trimmed = raw.trim()
        _uiState.update { current ->
            if (trimmed.length < MIN_QUERY_LENGTH) {
                current.copy(query = raw, state = SearchState.Idle, results = emptyList())
            } else {
                current.copy(query = raw, state = SearchState.Searching)
            }
        }
        queryChanges.value = raw
    }

    /**
     * One value per debounced query. A query that settled below the
     * minimum length emits [SearchOutcome.Idle] without touching the
     * network -- cheap, and it's what lets `flatMapLatest` cancel a
     * still-in-flight search for a longer query the moment the DJ deletes
     * back below the threshold (see the "shortening" test). Passes the
     * trimmed query to **both** `artist` and `title`, matching iOS's
     * `LibrarySearch.search(query:)` -- Backend-Service's `/library/` search
     * is a single free-text field on the wire, not two.
     */
    private fun resultsFlow(trimmed: String): Flow<SearchOutcome> {
        if (trimmed.length < MIN_QUERY_LENGTH) return flowOf(SearchOutcome.Idle)
        return flow {
            val results = try {
                api.searchLibrary(artist = trimmed, title = trimmed, limit = SEARCH_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            emit(if (results.isEmpty()) SearchOutcome.Empty else SearchOutcome.Found(results))
        }
    }

    private fun applyOutcome(outcome: SearchOutcome) {
        _uiState.update { current ->
            when (outcome) {
                SearchOutcome.Idle -> current.copy(state = SearchState.Idle, results = emptyList())
                SearchOutcome.Empty -> current.copy(state = SearchState.Empty, results = emptyList())
                is SearchOutcome.Found -> current.copy(state = SearchState.Results, results = outcome.rows)
            }
        }
    }

    /**
     * Inline add-to-bin (issue #9). Forwards the row's first track-title
     * hint when it was surfaced by a track match rather than an
     * artist/album hit -- mirrors iOS's `SearchViewModel.addToBin`, which
     * forwards `matchedVia.first?.title` so the bin entry remembers what
     * the DJ was actually looking for. `POST /djs/bin`'s `201` is the
     * acknowledgement ([ApiClient.addToBin] deliberately doesn't decode a
     * body), so success is just "the call didn't throw."
     */
    fun addToBin(row: AlbumSearchResult) {
        if (_uiState.value.addToBinStatus[row.id] == AddToBinStatus.InFlight) return
        _uiState.update { it.copy(addToBinStatus = it.addToBinStatus + (row.id to AddToBinStatus.InFlight)) }

        viewModelScope.launch {
            val outcome = try {
                api.addToBin(albumId = row.id, trackTitle = row.matchedVia.firstOrNull()?.title)
                AddToBinStatus.Added
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AddToBinStatus.Failed
            }
            _uiState.update { it.copy(addToBinStatus = it.addToBinStatus + (row.id to outcome)) }
        }
    }

    private sealed interface SearchOutcome {
        data object Idle : SearchOutcome
        data object Empty : SearchOutcome
        data class Found(val rows: List<AlbumSearchResult>) : SearchOutcome
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 2
        const val DEBOUNCE_MILLIS = 300L
        const val SEARCH_LIMIT = 25
    }
}

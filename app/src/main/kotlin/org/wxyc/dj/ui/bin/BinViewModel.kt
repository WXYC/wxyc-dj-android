package org.wxyc.dj.ui.bin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.wxyc.dj.api.ApiClient
import org.wxyc.dj.api.BinEntry
import org.wxyc.dj.api.BinSorting

/**
 * Backs [BinScreen] (issue #11, port of `WXYCDJ/Bin/BinViewModel.swift`).
 * Owns [BinUiState] -- the sorted, deduplicated bin, or the loading/error
 * answer in its place -- and the two actions the screen offers: [refresh]
 * (pull-to-refresh, and the tab's own first load) and [remove]
 * (swipe-to-remove).
 *
 * **No offline store in v1.** iOS's `BinViewModel` is backed by an optional
 * `BinStore` snapshot (issue #60 there) so a cold launch can render the bin
 * before the network answers. This issue's notes are explicit that the
 * offline snapshot is phase 2 for this port -- "there is no local store in
 * v1. Call the client directly and leave the seam out" -- so [refresh]
 * always starts from whatever [ApiClient.getBin] returns; there is nothing
 * to fall back to while offline.
 *
 * **The album is the key, not a row.** `GET /djs/bin` has no `bins.id` and
 * `DELETE /djs/bin` clears every row for a `(dj, album)` pair (issue #5
 * invariant 15), so [remove] targets [BinEntry.albumId] and
 * [dedupedAndSorted] collapses a repeated album to one row before it ever
 * reaches [BinUiState] -- there is no production path that can display two
 * rows for the same album.
 *
 * **Sorting is [BinSorting]'s job, not a second comparator here.** issue #5
 * pins the collator (Android's `java.text.Collator` delegates to
 * `android.icu`, and a naive re-implementation here would drift from it
 * silently on device even while `:api:test` stays green) -- see that
 * object's KDoc.
 *
 * **A refresh failure after a successful load does not regress to
 * [BinUiState.Error].** [hasLoadedBin] is this view model's memory of
 * whether *any* fetch has landed this session. The first failure (nothing
 * loaded yet) has no list to protect, so it becomes [BinUiState.Error] --
 * the DJ has nothing to look at either way, and the reason belongs on
 * screen. Every later failure -- a pull-to-refresh pull
 * against a flaky connection, most commonly -- keeps the DJ's already-
 * loaded bin exactly where it was and only attaches [BinUiState.Populated.message]
 * / [BinUiState.Empty.message] to explain that the *refresh* didn't work,
 * mirroring iOS's `handleRefreshFailure`: "keep an authoritative bin … on
 * screen … and only surface `.error` when there's genuinely nothing
 * loaded. Never blows a good snapshot away." Losing a DJ's bin to a hiccup
 * mid-pull would be a strictly worse experience than the stale-by-a-few-
 * seconds list it replaced.
 *
 * **Removal is optimistic, unlike iOS.** iOS's `remove(_:)` awaits
 * `DELETE /djs/bin` before touching `entries` at all -- correct there, but
 * this issue's own interaction spec reads differently: "Removal should
 * feel immediate; a failed delete restores the row and says so." [remove]
 * therefore drops the row from [BinUiState] *before* the network call, and
 * only reinserts it -- through the same [dedupedAndSorted] every fetch
 * goes through, so a restored row lands back in shelf order rather than
 * wherever it happened to be swiped from -- if the call fails. This is a
 * deliberate behavioral divergence from the source of truth, not an
 * oversight; flagged here so a reviewer comparing against
 * `BinViewModel.swift` line-for-line doesn't mistake it for a missed port.
 *
 * Every suspend action here runs its work in [viewModelScope] and then
 * `join()`s it, matching `LoginViewModel`'s established shape: the scope
 * that owns the work's lifetime is this view model's, not whatever
 * `rememberCoroutineScope()` a composable started it from, so a
 * configuration change mid-refresh or mid-remove cannot strand
 * [BinUiState] on a stuck spinner or an optimistically-removed row that
 * never gets to restore itself. Gates and in-flight state are read and
 * written synchronously, before `launch`, for the same double-tap-guard
 * reason `LoginViewModel` documents.
 */
@HiltViewModel
class BinViewModel @Inject constructor(
    private val api: ApiClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BinUiState>(BinUiState.Loading)
    val uiState: StateFlow<BinUiState> = _uiState.asStateFlow()

    /**
     * True once a fetch has landed successfully at least once this session.
     * Purely in-memory -- there is no offline store in v1 (see class KDoc) --
     * so it resets on process death exactly like every other piece of this
     * view model's state.
     */
    private var hasLoadedBin = false

    /**
     * The [refresh] double-tap guard. Deliberately **not** derived from
     * [BinUiState] (an earlier version read `_uiState.value is BinUiState.Loading`
     * as "already in flight") -- [BinUiState.Loading] is *also* the
     * constructor-default starting state, before [refresh] has ever been
     * called even once. Gating on the published state therefore made the
     * very first call -- [BinScreen]'s own `LaunchedEffect(Unit) { refresh() }`
     * -- see itself as a duplicate of a fetch that was never started, and
     * silently no-op: the tab would show a spinner forever. A plain flag,
     * independent of what's on screen, is what makes "in flight" and "the
     * initial idle state" distinguishable.
     *
     * Cleared inside the [viewModelScope] launch's own `finally`, not in
     * [refresh]'s -- that is the part that matters for the composition-scope
     * survival property the class KDoc documents. If it were cleared around
     * [refresh]'s own `.join()` instead, a caller whose scope dies mid-refresh
     * would unblock a fresh [refresh] call the instant its `.join()` is
     * cancelled, even though the real fetch this flag guards is still
     * running in the background on [viewModelScope] -- reopening the gate
     * while the work it protects hasn't finished. Tying the clear to the
     * launched job's own completion keeps the flag honest about what's
     * actually still in flight, independent of who's still waiting on it.
     */
    private var isFetchInFlight = false

    /**
     * Fetches `GET /djs/bin`, sorts and dedupes it, and publishes the
     * result. Also the tab's first load -- [BinScreen] calls this from a
     * `LaunchedEffect(Unit)`, so "first load" and "pull-to-refresh" are the
     * same call with different starting [BinUiState]s, exactly as on iOS.
     *
     * A no-op while a refresh is already in flight, checked and latched
     * synchronously before the `launch` -- see the class KDoc's
     * double-tap-guard note, and [isFetchInFlight]'s KDoc for why that flag
     * exists (and why it's cleared where it is) rather than reading
     * [BinUiState.Loading] off the published state.
     */
    suspend fun refresh() {
        if (isFetchInFlight) return
        isFetchInFlight = true
        _uiState.value = _uiState.value.markRefreshing()

        viewModelScope.launch {
            try {
                val fetched = api.getBin()
                hasLoadedBin = true
                _uiState.value = dedupedAndSorted(fetched).toUiState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: DEFAULT_ERROR_MESSAGE
                _uiState.update { existing -> existing.afterRefreshFailure(hasLoadedBin, message) }
            } finally {
                isFetchInFlight = false
            }
        }.join()
    }

    /**
     * Removes [entry]'s album from the bin -- optimistically, see the class
     * KDoc for why this diverges from iOS's await-first shape. A no-op if
     * [entry]'s album isn't currently shown (already removed by a prior
     * call, or the state isn't [BinUiState.Populated] at all).
     */
    suspend fun remove(entry: BinEntry) {
        val current = _uiState.value
        if (current !is BinUiState.Populated) return
        val remaining = current.entries.filterNot { it.albumId == entry.albumId }
        if (remaining.size == current.entries.size) return
        _uiState.value = remaining.toUiState()

        viewModelScope.launch {
            try {
                api.removeFromBin(entry.albumId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = removeFailureMessage(entry, e)
                _uiState.update { existing -> existing.restoring(entry, message) }
            }
        }.join()
    }

    /**
     * Dismisses [BinUiState.Populated.message] / [BinUiState.Empty.message]
     * -- called by [BinScreen] once the `Snackbar` showing it has been
     * presented, so the same message doesn't replay on the next
     * recomposition (a rotation, most commonly).
     */
    fun clearMessage() {
        _uiState.update { state ->
            when (state) {
                is BinUiState.Populated -> if (state.message == null) state else state.copy(message = null)
                is BinUiState.Empty -> if (state.message == null) state else state.copy(message = null)
                BinUiState.Loading, is BinUiState.Error -> state
            }
        }
    }

    private fun removeFailureMessage(entry: BinEntry, cause: Exception): String {
        val reason = cause.message ?: DEFAULT_ERROR_MESSAGE
        return "Couldn't remove \"${entry.albumTitle}\". $reason"
    }

    private companion object {
        const val DEFAULT_ERROR_MESSAGE = "Something went wrong. Please try again."
    }
}

/** [BinSorting]'s collator-backed order, over a bin already collapsed to one row per album (issue #5 invariant 15). */
internal fun dedupedAndSorted(entries: List<BinEntry>): List<BinEntry> =
    BinSorting.sorted(BinEntry.deduplicatedByAlbum(entries))

/** A deduplicated, sorted entry list has exactly one non-error, non-loading shape: empty, or [BinUiState.Populated]. */
private fun List<BinEntry>.toUiState(): BinUiState = if (isEmpty()) BinUiState.Empty() else BinUiState.Populated(this)

/**
 * The state to publish the instant a refresh starts. A prior [BinUiState.Populated]/
 * [BinUiState.Empty] stays on screen with [BinUiState.Populated.isRefreshing]/
 * [BinUiState.Empty.isRefreshing] flipped on (and any stale message cleared --
 * a fresh refresh deserves a fresh verdict); [BinUiState.Loading] and
 * [BinUiState.Error] both become [BinUiState.Loading], since neither has a
 * list worth keeping on screen while the fetch is in flight.
 */
private fun BinUiState.markRefreshing(): BinUiState = when (this) {
    is BinUiState.Populated -> copy(isRefreshing = true, message = null)
    is BinUiState.Empty -> copy(isRefreshing = true, message = null)
    BinUiState.Loading, is BinUiState.Error -> BinUiState.Loading
}

/**
 * Where a failed [BinViewModel.refresh] lands: [BinUiState.Error] only when
 * [hasLoadedBin] is still false (nothing on screen to protect), otherwise
 * the existing list stays up with [message] attached and refreshing turned
 * back off. See [BinViewModel]'s class KDoc for the full rationale.
 */
private fun BinUiState.afterRefreshFailure(hasLoadedBin: Boolean, message: String): BinUiState = when {
    !hasLoadedBin -> BinUiState.Error(message)
    this is BinUiState.Populated -> copy(isRefreshing = false, message = message)
    this is BinUiState.Empty -> copy(isRefreshing = false, message = message)
    // Unreachable in practice once hasLoadedBin is true (a successful fetch
    // always publishes Populated/Empty), but total rather than partial so
    // this function can't silently drop a future BinUiState case.
    else -> BinUiState.Error(message)
}

/**
 * Reinserts [entry] into whatever the state holds now -- not necessarily
 * what [BinViewModel.remove] originally removed it from, if a refresh
 * landed in between -- through [dedupedAndSorted], so the restored row is
 * back in shelf order rather than wherever it was swiped from, and cannot
 * appear twice if something else already restored it first.
 */
private fun BinUiState.restoring(entry: BinEntry, message: String): BinUiState {
    val entries = when (this) {
        is BinUiState.Populated -> this.entries
        else -> emptyList()
    }
    val restored = dedupedAndSorted(entries + entry)
    return BinUiState.Populated(restored, message = message)
}

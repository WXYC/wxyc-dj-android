package org.wxyc.dj.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.wxyc.dj.api.AlbumInfo
import org.wxyc.dj.api.AlbumSearchResult
import org.wxyc.dj.api.ApiClient
import org.wxyc.dj.api.ApiError
import org.wxyc.dj.api.ArtworkFailureClassification
import org.wxyc.dj.ui.nav.AlbumRouteFallbackStore

private const val TAG = "AlbumDetailViewModel"

/**
 * Backs [AlbumDetailScreen] (issue #10, port of `AlbumDetailView`). Owns the
 * two-call fan-out (invariant 18), the best-effort LML framing, and the
 * artwork-failure ledger (invariant 17). The pure decisions those feed --
 * catalog-row precedence, artwork precedence, the label dedup -- live in
 * `AlbumDetailPrecedence.kt` and are exposed as computed properties on
 * [AlbumDetailUiState] so [AlbumDetailScreen] never re-derives them.
 *
 * **Reads the caller's row from [AlbumRouteFallbackStore] in `init`, once,
 * scoped to this destination's own [androidx.lifecycle.ViewModelStore].**
 * The placeholder this replaces read the same store via `remember(route.id)`
 * inside the composable, which does not survive a configuration change:
 * `remember` is torn down and rebuilt on rotation, and the store's `take` is
 * single-use, so a second read after rotation would silently see `null` even
 * though the first read already consumed the real row. A [ViewModel]
 * survives that rotation, so reading here makes the read genuinely
 * exactly-once for the lifetime of this navigation, per `AlbumRoute`'s own
 * KDoc note that this was #10's job to close.
 *
 * **Constructed with `@AssistedInject`, not a `SavedStateHandle` read.**
 * [albumId] comes straight from the [org.wxyc.dj.ui.nav.AlbumRoute] the
 * caller already decoded -- `AlbumDetailScreen`'s own `route` parameter --
 * fed through [Factory.create] at the `hiltViewModel` call site (see that
 * file). That keeps this class trivially constructible in tests
 * (`AlbumDetailViewModel(albumId = 100, apiClient = someClient)`, no
 * `SavedStateHandle` or `NavBackStackEntry` involved) rather than taking on
 * trust in exactly how Navigation Compose's typed-route args happen to
 * populate a `SavedStateHandle`.
 */
@HiltViewModel(assistedFactory = AlbumDetailViewModel.Factory::class)
class AlbumDetailViewModel @AssistedInject constructor(
    @Assisted private val albumId: Int,
    private val apiClient: ApiClient,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(albumId: Int): AlbumDetailViewModel
    }

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
        val fallback = AlbumRouteFallbackStore.take(albumId)
        _uiState.update { it.copy(fallback = fallback) }
        viewModelScope.launch { loadAll(fallback) }
    }

    /**
     * Invariant 18: the fan-out branches on whether [fallback] exists. With
     * one -- every tab push in v1 -- LML already has an artist/title to key
     * on, so it runs **concurrently** with `/library/info`. Without one --
     * only reachable in v1 if a route is ever constructed with nothing
     * stashed, e.g. a future cold system-search deep link -- `/library/info`
     * is the only source of an artist name, so it is awaited first and LML
     * goes second, keyed off whatever it returned.
     */
    private suspend fun loadAll(fallback: AlbumSearchResult?) {
        if (fallback != null) {
            coroutineScope {
                launch { loadInfo() }
                launch { loadMetadata(fallback.artistName, fallback.albumTitle) }
            }
        } else {
            val info = loadInfo()
            loadMetadata(info?.artistName, info?.albumTitle)
        }
    }

    /**
     * `/library/info` is the shelf source of truth. A failure marks
     * [AlbumDetailUiState.infoFailed] so [AlbumDetailPrecedence.resolveCatalog]
     * frames the fallback row with a quiet note instead of a red error --
     * the catalog row (from the fallback, when there is one) still renders.
     */
    private suspend fun loadInfo(): AlbumInfo? {
        return try {
            val info = apiClient.albumInfo(albumId)
            _uiState.update { it.copy(info = info, infoLoaded = true) }
            info
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            Log.w(TAG, "library/info failed for album $albumId: ${e.message}")
            _uiState.update { it.copy(infoFailed = true, infoLoaded = true) }
            null
        }
    }

    /**
     * LML is best-effort: a 404 (no LML match), a decode failure, or a rate
     * limit degrades to a quiet footer note ([AlbumDetailUiState.metadataError]),
     * never a red banner, and the catalog row still renders regardless of
     * this leg's outcome.
     */
    private suspend fun loadMetadata(artistName: String?, releaseTitle: String?) {
        if (artistName.isNullOrEmpty()) {
            _uiState.update { it.copy(metadataError = "no artist name available") }
            return
        }
        try {
            val metadata = apiClient.albumMetadata(artistName = artistName, releaseTitle = releaseTitle)
            _uiState.update { it.copy(metadata = metadata) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            Log.w(TAG, "metadata fetch failed for $artistName: ${e.message}")
            _uiState.update { it.copy(metadataError = e.message) }
        }
    }

    /**
     * Issue #86: called from the header's Coil image result with the URL
     * that failed and the [Throwable] it failed with. Classifies **before**
     * recording -- recording is a one-way door for the life of this screen,
     * so a connectivity-class failure (the link, not the URL) must never
     * retire a healthy cover; doing so would hand the header to LML's art
     * the moment connectivity returned, with no self-recovery. Must be
     * called only from a genuinely failed load, never a still-loading one --
     * see `AlbumDetailScreen`'s Coil wiring, which only reaches this from
     * `AsyncImagePainter.State.Error`.
     */
    fun recordArtworkFailure(url: String, error: Throwable) {
        if (!ArtworkFailureClassification.indictsUrl(error)) return
        _uiState.update { it.copy(failedArtworkUrls = it.failedArtworkUrls + url) }
    }

    /**
     * Adds this release to the signed-in DJ's bin. Runs in [viewModelScope]
     * and joins, rather than running directly in whatever scope the caller
     * launches from -- a `rememberCoroutineScope()` composition scope is
     * cancelled by a configuration change, which would otherwise strand
     * [AlbumDetailUiState.addInFlight] `true` forever and permanently
     * disable the button (see `org.wxyc.dj.ui.login.LoginViewModel`'s KDoc
     * for the reproduced defect this pattern exists to fix). The gate is
     * checked and the flag set synchronously, before the `launch`, so two
     * taps dispatched before either body runs can't both fire.
     */
    suspend fun addToBin() {
        val current = _uiState.value
        if (current.addInFlight || current.addedToBin) return
        _uiState.update { it.copy(addInFlight = true, addError = null) }

        viewModelScope.launch {
            try {
                apiClient.addToBin(albumId)
                _uiState.update { it.copy(addInFlight = false, addedToBin = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                _uiState.update { it.copy(addInFlight = false, addError = e.message) }
            }
        }.join()
    }
}

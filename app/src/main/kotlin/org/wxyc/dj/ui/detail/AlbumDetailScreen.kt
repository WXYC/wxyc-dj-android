package org.wxyc.dj.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.wxyc.dj.R
import org.wxyc.dj.ui.nav.AlbumRoute
import org.wxyc.dj.ui.nav.AlbumRouteFallbackStore

/**
 * Placeholder for the album detail destination (issue #7's nav skeleton) --
 * issue #10 fills this file's body in with the header, catalog section, LML
 * best-effort enrichment, and the artwork-precedence fallthrough
 * (invariants 17-18 in `docs/port-plan.md`). [route] and [onBack] already
 * carry everything #10 needs: [AlbumRoute.id] to fetch `/library/info` and
 * [onBack] for any in-screen action that should return to the caller
 * (`MainScaffold.kt`'s top-bar back arrow already calls the same [onBack]
 * independently) -- neither `MainScaffold.kt` nor this function's signature
 * should need to change when #10 lands.
 *
 * **The instant-header row (issue #23).** [AlbumRoute] no longer carries a
 * `fallback` -- see that type's KDoc for why -- so the caller-supplied row,
 * when one exists, is read out of [AlbumRouteFallbackStore] here, exactly
 * once per navigation to this destination. `remember(route.id)` is what
 * makes it exactly-once rather than on every recomposition: the store's
 * [AlbumRouteFallbackStore.take] clears its slot on a match, so a second
 * call for the same [AlbumRoute.id] would otherwise see `null` even though
 * the first call already had the row. Keying `remember` on [AlbumRoute.id]
 * means the read happens once per distinct navigation and survives ordinary
 * recomposition; it does **not** survive a configuration change (`remember`
 * is not `rememberSaveable`, and the row isn't a saveable type -- see the
 * store's KDoc), which is an accepted gap for this placeholder and the
 * reason #10 should promote this read into a `@HiltViewModel`'s `init`
 * block instead, scoped to the destination's own `ViewModelStore` the way
 * `AuthViewModel` already is -- that survives rotation for free because the
 * store, not `remember`, is what Compose tears down.
 */
@Composable
fun AlbumDetailScreen(route: AlbumRoute, onBack: () -> Unit) {
    val fallback = remember(route.id) { AlbumRouteFallbackStore.take(route.id) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column {
            Text(
                text = fallback?.let { "${it.artistName} — ${it.albumTitle}" }
                    ?: stringResource(R.string.album_detail_screen_placeholder),
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.album_detail_back_button_placeholder))
            }
        }
    }
}

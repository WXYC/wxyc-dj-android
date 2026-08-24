package org.wxyc.dj.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.wxyc.dj.R
import org.wxyc.dj.ui.nav.AlbumRoute

/**
 * Placeholder for the album detail destination (issue #7's nav skeleton) --
 * issue #10 fills this file's body in with the header, catalog section, LML
 * best-effort enrichment, and the artwork-precedence fallthrough
 * (invariants 17-18 in `docs/port-plan.md`). [route] and [onBack] already
 * carry everything #10 needs: [AlbumRoute.id] to fetch `/library/info`,
 * [AlbumRoute.fallback] for the instant header render when the caller
 * already had a row in hand, and [onBack] for any in-screen action that
 * should return to the caller (`MainScaffold.kt`'s top-bar back arrow
 * already calls the same [onBack] independently) -- neither
 * `MainScaffold.kt` nor this function's signature should need to change
 * when #10 lands.
 */
@Composable
fun AlbumDetailScreen(route: AlbumRoute, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column {
            Text(
                text = route.fallback?.let { "${it.artistName} — ${it.albumTitle}" }
                    ?: stringResource(R.string.album_detail_screen_placeholder),
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.album_detail_back_button_placeholder))
            }
        }
    }
}

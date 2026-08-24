package org.wxyc.dj.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.wxyc.dj.R
import org.wxyc.dj.ui.nav.AlbumRoute

/**
 * Placeholder for the search tab (issue #7's nav skeleton) -- issue #9
 * fills this file's body in with the debounced live-search list. The
 * [onAlbumSelected] callback is already wired from `MainScaffold.kt` so #9
 * only has to call it with a real [AlbumRoute] -- it does not need to touch
 * the nav graph itself.
 *
 * The row tap is two statements, not one (issue #23 -- [AlbumRoute] carries
 * only an id now, so the row cannot ride along inside it):
 *
 * ```
 * AlbumRouteFallbackStore.stash(id = row.id, fallback = row)
 * onAlbumSelected(AlbumRoute(id = row.id))
 * ```
 *
 * The stash is what lets the detail header render instantly instead of
 * waiting on `/library/info`, mirroring `WXYCDJ/SearchView.swift`'s row
 * tap; it must happen *before* the navigate, and it is optional -- omitting
 * it just means the detail screen waits for the network, which is exactly
 * the cold-launch deep-link path. See [AlbumRoute]'s KDoc for why the row
 * travels out of band.
 */
@Composable
fun SearchScreen(onAlbumSelected: (AlbumRoute) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.search_screen_placeholder))
    }
}

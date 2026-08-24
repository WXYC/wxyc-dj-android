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
 * only has to call it with a real [AlbumRoute] (`AlbumRoute(id = row.id,
 * fallback = row)`, mirroring `WXYCDJ/SearchView.swift`'s row tap) -- it
 * does not need to touch the nav graph itself.
 */
@Composable
fun SearchScreen(onAlbumSelected: (AlbumRoute) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.search_screen_placeholder))
    }
}

package org.wxyc.dj.ui.bin

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
 * Placeholder for the bin tab (issue #7's nav skeleton) -- issue #11 fills
 * this file's body in with the sorted bin list, swipe-to-remove, and
 * pull-to-refresh. The [onAlbumSelected] callback is already wired from
 * `MainScaffold.kt`, so #11 only has to call it with a real [AlbumRoute]
 * built from a `BinEntry` row -- it does not need to touch the nav graph
 * itself.
 */
@Composable
fun BinScreen(onAlbumSelected: (AlbumRoute) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.bin_screen_placeholder))
    }
}

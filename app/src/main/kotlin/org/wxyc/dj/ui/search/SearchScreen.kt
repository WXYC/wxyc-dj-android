package org.wxyc.dj.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.wxyc.dj.R
import org.wxyc.dj.api.AlbumSearchResult
import org.wxyc.dj.ui.nav.AlbumRoute
import org.wxyc.dj.ui.nav.AlbumRouteFallbackStore

/**
 * The search tab (issue #9): a search field driving [SearchViewModel]'s
 * debounced live-search pipeline, over one of four states -- idle,
 * searching, results, empty -- port of `WXYCDJ/Search/SearchView.swift`.
 * The [onAlbumSelected] callback is wired from `MainScaffold.kt` (issue #7);
 * this file never touches the nav graph itself.
 *
 * The row tap is two statements, not one (issue #23 -- [AlbumRoute] carries
 * only an id now, so the row cannot ride along inside it): the tapped
 * [AlbumSearchResult] is stashed into [AlbumRouteFallbackStore] immediately
 * before navigating, so `AlbumDetailScreen` (issue #10) can render its
 * header instantly instead of waiting on `/library/info`. See
 * [AlbumRoute]'s KDoc for why the row travels out of band.
 */
@Composable
fun SearchScreen(onAlbumSelected: (AlbumRoute) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_field_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )

        when (state.state) {
            SearchState.Idle -> CenteredMessage(
                title = stringResource(R.string.search_idle_title),
                description = stringResource(R.string.search_idle_description),
            )
            SearchState.Searching -> SearchingIndicator()
            SearchState.Empty -> CenteredMessage(
                title = stringResource(R.string.search_empty_title),
                description = stringResource(R.string.search_empty_description, state.query),
            )
            SearchState.Results -> ResultsList(
                results = state.results,
                addToBinStatus = state.addToBinStatus,
                onTap = { row ->
                    AlbumRouteFallbackStore.stash(id = row.id, fallback = row)
                    onAlbumSelected(AlbumRoute(id = row.id))
                },
                onAdd = viewModel::addToBin,
            )
        }
    }
}

@Composable
private fun ResultsList(
    results: List<AlbumSearchResult>,
    addToBinStatus: Map<Int, AddToBinStatus>,
    onTap: (AlbumSearchResult) -> Unit,
    onAdd: (AlbumSearchResult) -> Unit,
) {
    LazyColumn {
        // Keyed by album id, not list position: a status-only change (an
        // add-to-bin outcome landing) recomposes just that row rather than
        // the whole list, which is what keeps a failure from disturbing
        // scroll position (issue #9's acceptance criteria).
        items(results, key = { it.id }) { row ->
            SearchResultRow(
                row = row,
                addToBinStatus = addToBinStatus[row.id],
                onTap = { onTap(row) },
                onAdd = { onAdd(row) },
            )
        }
    }
}

@Composable
private fun SearchingIndicator() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.search_searching_label))
        }
    }
}

@Composable
private fun CenteredMessage(title: String, description: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

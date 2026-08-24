package org.wxyc.dj.ui.bin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.wxyc.dj.R
import org.wxyc.dj.api.BinEntry
import org.wxyc.dj.ui.nav.AlbumRoute
import org.wxyc.dj.ui.nav.AlbumRouteFallbackStore

/**
 * The bin tab (issue #11, port of `WXYCDJ/Bin/BinView.swift`): a
 * pull-to-refresh, swipe-to-remove list of the DJ's shelf, with loading,
 * populated, empty, and error rendered as four visibly distinct screens --
 * see [BinUiState]'s KDoc for why that split is structural, not just a
 * `when` branch convention.
 *
 * The initial fetch is a `LaunchedEffect(Unit)` here rather than
 * [BinViewModel]'s `init` block, matching `LoginViewModel`'s established
 * shape in this repo (the view model's own construction stays side-effect
 * free, so a test can build one and call [BinViewModel.refresh] on its own
 * terms -- exactly what [BinViewModelTest] does).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinScreen(onAlbumSelected: (AlbumRoute) -> Unit, viewModel: BinViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val message = when (val current = state) {
        is BinUiState.Populated -> current.message
        is BinUiState.Empty -> current.message
        BinUiState.Loading, is BinUiState.Error -> null
    }
    // One-shot: BinViewModel.clearMessage() drops the message the instant
    // it's been handed to the Snackbar, so a rotation (which re-runs this
    // LaunchedEffect against the same still-collected StateFlow value)
    // cannot replay a message the DJ already saw.
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            BinUiState.Loading -> LoadingBin()

            is BinUiState.Error -> ErrorBin(
                message = current.message,
                onRetry = { scope.launch { viewModel.refresh() } },
            )

            is BinUiState.Empty -> PullToRefreshBox(
                isRefreshing = current.isRefreshing,
                onRefresh = { scope.launch { viewModel.refresh() } },
                modifier = Modifier.fillMaxSize(),
            ) {
                EmptyBin()
            }

            is BinUiState.Populated -> PullToRefreshBox(
                isRefreshing = current.isRefreshing,
                onRefresh = { scope.launch { viewModel.refresh() } },
                modifier = Modifier.fillMaxSize(),
            ) {
                BinList(
                    entries = current.entries,
                    onRemove = { entry -> scope.launch { viewModel.remove(entry) } },
                    onAlbumSelected = onAlbumSelected,
                )
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun LoadingBin() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorBin(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.bin_error_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.bin_retry_button))
            }
        }
    }
}

@Composable
private fun EmptyBin() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.bin_empty_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.bin_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BinList(entries: List<BinEntry>, onRemove: (BinEntry) -> Unit, onAlbumSelected: (AlbumRoute) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.albumId }) { entry ->
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    val dismissed = value == SwipeToDismissBoxValue.StartToEnd || value == SwipeToDismissBoxValue.EndToStart
                    if (dismissed) onRemove(entry)
                    dismissed
                },
            )
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = { RemoveSwipeBackground() },
            ) {
                BinRow(
                    entry = entry,
                    onClick = {
                        // The row already has everything the detail header
                        // needs -- see BinEntryDetailFallback.kt -- so it
                        // travels out of band via AlbumRouteFallbackStore
                        // (issue #23; AlbumRoute itself carries only an id)
                        // rather than waiting on /library/info.
                        AlbumRouteFallbackStore.stash(id = entry.albumId, fallback = entry.toDetailFallback())
                        onAlbumSelected(AlbumRoute(id = entry.albumId))
                    },
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun RemoveSwipeBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.bin_remove_action_content_description),
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun BinRow(entry: BinEntry, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(text = entry.albumTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    text = entry.artistName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val detailParts = buildList {
                    if (entry.callNumber.isNotEmpty()) add(entry.callNumber)
                    entry.formatName?.takeIf { it.isNotEmpty() }?.let(::add)
                }
                if (detailParts.isNotEmpty()) {
                    Text(
                        text = detailParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick),
    )
}

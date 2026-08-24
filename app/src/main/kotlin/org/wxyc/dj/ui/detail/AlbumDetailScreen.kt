package org.wxyc.dj.ui.detail

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.wxyc.dj.R
import org.wxyc.dj.api.AlbumInfo
import org.wxyc.dj.api.AlbumMetadata
import org.wxyc.dj.api.StreamingService
import org.wxyc.dj.ui.nav.AlbumRoute

/**
 * The album detail destination (issue #10, port of `AlbumDetailView.swift`):
 * header (artwork + title/artist/label), the catalog section (shelf source
 * of truth), then LML's best-effort sections -- release, genres/styles,
 * listen, links, tracklist -- and a rotation section, in that order. All
 * loading and precedence logic lives in [AlbumDetailViewModel] and
 * `AlbumDetailPrecedence.kt`; this file only renders [AlbumDetailUiState].
 *
 * The [hiltViewModel] call below is what closes the gap the placeholder this
 * replaces left open (see [AlbumRoute]'s and `AlbumRouteFallbackStore`'s
 * KDoc): [AlbumDetailViewModel] is `@AssistedInject`-constructed with
 * [AlbumRoute.id] fed straight from this screen's own `route` parameter, so
 * the view model -- not a `remember(route.id)` torn down and rebuilt on every
 * configuration change -- is what reads `AlbumRouteFallbackStore` exactly
 * once and survives rotation.
 */
@Composable
fun AlbumDetailScreen(
    route: AlbumRoute,
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel<AlbumDetailViewModel, AlbumDetailViewModel.Factory>(
        creationCallback = { factory -> factory.create(route.id) },
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item { HeaderSection(state, onArtworkFailed = viewModel::recordArtworkFailure) }
        item { CatalogSection(state) }

        val metadata = state.metadata
        if (metadata != null) {
            if (hasReleaseInfo(metadata, catalogLabel = state.catalogLabel, infoLoaded = state.infoLoaded)) {
                item { ReleaseSection(metadata, catalogLabel = state.catalogLabel, infoLoaded = state.infoLoaded) }
            }
            val tags = combinedTags(metadata)
            if (!tags.isNullOrEmpty()) item { TagsSection(tags) }
            if (hasStreamingLinks(metadata)) item { StreamingSection(metadata) }
            if (hasExternalLinks(metadata)) item { LinksSection(metadata) }
            val tracks = metadata.tracklist
            if (!tracks.isNullOrEmpty()) item { TracklistSection(tracks) }
        }

        val rotation = state.activeRotation
        if (rotation != null) item { RotationSection(rotation) }

        item {
            ActionSection(
                addInFlight = state.addInFlight,
                addedToBin = state.addedToBin,
                onAddToBin = { scope.launch { viewModel.addToBin() } },
            )
        }

        // Quiet footer notes -- never a red error banner (issue #10's own
        // framing rule). The metadata-unavailable note is withheld once the
        // fallback-row/unavailable note above is already showing (the
        // offline-equivalent case here: /library/info hasn't succeeded, so
        // LML enrichment failing too would double up the same "things are
        // degraded" message).
        state.catalogResolution.note?.let { note -> item { FooterNote(footerNoteText(note)) } }
        state.addError?.let { error ->
            item { FooterNote(stringResource(R.string.album_detail_add_error, error), isError = true) }
        }
        if (state.info != null && metadata == null && state.metadataError != null) {
            item { FooterNote(stringResource(R.string.album_detail_metadata_unavailable, state.metadataError!!)) }
        }
    }
}

@Composable
private fun HeaderSection(state: AlbumDetailUiState, onArtworkFailed: (String, Throwable) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val artworkUrl = state.preferredArtworkUrl
        if (artworkUrl != null) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = stringResource(R.string.album_detail_artwork_content_description),
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentScale = ContentScale.Fit,
                // Issue #86: only a genuinely failed load is ever classified
                // and possibly recorded -- a still-loading state never reaches
                // this callback, which is what keeps a source merely in
                // flight from ever being treated as failed.
                onError = { errorState -> onArtworkFailed(artworkUrl, errorState.result.throwable) },
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(state.displayTitle.orEmpty(), style = MaterialTheme.typography.headlineSmall)
        Text(state.displayArtist.orEmpty(), style = MaterialTheme.typography.titleMedium)
        val label = state.displayLabel
        if (!label.isNullOrEmpty()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}

@Composable
private fun CatalogSection(state: AlbumDetailUiState) {
    val info = state.info
    val catalogRow = state.catalogResolution.catalogRow
    DetailSection(stringResource(R.string.album_detail_catalog_section_title)) {
        when {
            info != null -> {
                MetadataRow(stringResource(R.string.album_detail_code_label), info.callNumber)
                info.formatName?.let { MetadataRow(stringResource(R.string.album_detail_format_label), it) }
                info.genreName?.let { MetadataRow(stringResource(R.string.album_detail_genre_label), it) }
                info.addDate?.let { MetadataRow(stringResource(R.string.album_detail_added_label), formatAddDate(it)) }
                info.plays?.let { MetadataRow(stringResource(R.string.album_detail_plays_label), it.toString()) }
                MetadataRow(stringResource(R.string.album_detail_streaming_label), stringResource(streamingTextRes(info.onStreaming)))
                info.discQuantity?.let { MetadataRow(stringResource(R.string.album_detail_discs_label), it.toString()) }
            }
            catalogRow != null -> {
                // Offline/pre-info render: the live fallback row.
                MetadataRow(stringResource(R.string.album_detail_code_label), catalogRow.callNumber)
                catalogRow.formatName?.let { MetadataRow(stringResource(R.string.album_detail_format_label), it) }
                catalogRow.genreName?.let { MetadataRow(stringResource(R.string.album_detail_genre_label), it) }
            }
            else -> CircularProgressIndicator(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ReleaseSection(metadata: AlbumMetadata, catalogLabel: String?, infoLoaded: Boolean) {
    DetailSection(stringResource(R.string.album_detail_release_section_title)) {
        metadata.releaseYear?.let { MetadataRow(stringResource(R.string.album_detail_year_label), it.toString()) }
        if (shouldShowMetadataLabel(metadata.label, catalogLabel, infoLoaded)) {
            metadata.label?.let { MetadataRow(stringResource(R.string.album_detail_label_label), it) }
        }
        val released = metadata.fullReleaseDate
        if (!released.isNullOrEmpty()) MetadataRow(stringResource(R.string.album_detail_released_label), released)
    }
}

@Composable
private fun TagsSection(tags: List<String>) {
    DetailSection(stringResource(R.string.album_detail_tags_section_title)) {
        Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StreamingSection(metadata: AlbumMetadata) {
    DetailSection(stringResource(R.string.album_detail_listen_section_title)) {
        for (service in StreamingService.entries) {
            val url = service.urlIn(metadata) ?: continue
            LinkRow(service.label, url)
        }
    }
}

@Composable
private fun LinksSection(metadata: AlbumMetadata) {
    DetailSection(stringResource(R.string.album_detail_links_section_title)) {
        metadata.discogsUrl?.let { LinkRow(stringResource(R.string.album_detail_discogs_link), it) }
        metadata.artistWikipediaUrl?.let { LinkRow(stringResource(R.string.album_detail_wikipedia_link), it) }
    }
}

@Composable
private fun TracklistSection(tracks: List<AlbumMetadata.Track>) {
    DetailSection(stringResource(R.string.album_detail_tracklist_section_title)) {
        for (track in tracks) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(track.position, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                Text(track.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                track.duration?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Reads [AlbumInfo.Rotation.isInRotation] (invariant 14) via
 * [AlbumDetailUiState.activeRotation] -- this composable only ever sees a
 * record already known to be in rotation, never re-derives the rule.
 */
@Composable
private fun RotationSection(rotation: AlbumInfo.Rotation) {
    DetailSection(stringResource(R.string.album_detail_rotation_section_title)) {
        val cohort = rotation.rotationCohort
        Text(cohort?.label ?: stringResource(R.string.album_detail_in_rotation), style = MaterialTheme.typography.bodyMedium)
        rotation.killDate?.let { MetadataRow(stringResource(R.string.album_detail_kill_date_label), it) }
    }
}

@Composable
private fun ActionSection(addInFlight: Boolean, addedToBin: Boolean, onAddToBin: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Button(onClick = onAddToBin, enabled = !addInFlight && !addedToBin, modifier = Modifier.fillMaxWidth()) {
            when {
                addInFlight -> CircularProgressIndicator(modifier = Modifier.height(20.dp))
                addedToBin -> {
                    Icon(Icons.Filled.Favorite, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.album_detail_added_to_bin_button))
                }
                else -> {
                    Icon(Icons.Filled.FavoriteBorder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.album_detail_add_to_bin_button))
                }
            }
        }
    }
}

@Composable
private fun FooterNote(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LinkRow(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { uriHandler.openUri(url) }) {
        Text(label)
    }
}

/** `info.addDate` is a real `Instant`, unlike rotation's raw `YYYY-MM-DD` wire strings -- rendered in the device's own zone, medium style ("Oct 12, 2025"). */
private fun formatAddDate(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(instant)

private fun streamingTextRes(value: Boolean?): Int = when (value) {
    true -> R.string.album_detail_streaming_available
    false -> R.string.album_detail_streaming_library_only
    null -> R.string.album_detail_streaming_unknown
}

@Composable
private fun footerNoteText(note: CatalogResolution.Note): String = when (note) {
    CatalogResolution.Note.FALLBACK_ROW -> stringResource(R.string.album_detail_note_fallback_row)
    CatalogResolution.Note.UNAVAILABLE -> stringResource(R.string.album_detail_note_unavailable)
}

/** The Release section renders Year, a deduped Label, and Released; suppress the section header entirely when none of those would emit a row. */
private fun hasReleaseInfo(metadata: AlbumMetadata, catalogLabel: String?, infoLoaded: Boolean): Boolean {
    if (metadata.releaseYear != null) return true
    if (!metadata.fullReleaseDate.isNullOrEmpty()) return true
    return shouldShowMetadataLabel(metadata.label, catalogLabel, infoLoaded)
}

private fun hasStreamingLinks(metadata: AlbumMetadata): Boolean =
    StreamingService.entries.any { it.urlIn(metadata) != null }

private fun hasExternalLinks(metadata: AlbumMetadata): Boolean =
    metadata.discogsUrl != null || metadata.artistWikipediaUrl != null

/** Genres and styles merged, original order preserved, case-insensitive duplicates dropped. */
private fun combinedTags(metadata: AlbumMetadata): List<String>? {
    val merged = metadata.genres.orEmpty() + metadata.styles.orEmpty()
    if (merged.isEmpty()) return null
    val seen = mutableSetOf<String>()
    return merged.filter { seen.add(it.lowercase()) }
}

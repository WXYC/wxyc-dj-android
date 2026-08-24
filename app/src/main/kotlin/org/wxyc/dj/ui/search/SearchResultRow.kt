package org.wxyc.dj.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.wxyc.dj.R
import org.wxyc.dj.api.AlbumSearchResult
import org.wxyc.dj.api.RotationBin

/**
 * One search result row (issue #9, port of `WXYCDJ/Search/SearchResultRow.swift`):
 * artwork, title/artist, call number + format capsule + rotation badge, a
 * track-match annotation ([TrackMatchBadge]), and an inline add-to-bin
 * control.
 *
 * [onTap] and [onAdd] are separate callbacks rather than one shared "the row
 * was touched" handler: [onAdd] is wired to the trailing [IconButton], which
 * consumes its own tap before it ever reaches the row's own
 * [Modifier.clickable] -- tapping "Add to Bin" must never also navigate to
 * the detail screen. Navigation itself (stashing the row into
 * `AlbumRouteFallbackStore` and building the `AlbumRoute`) is `SearchScreen`'s
 * job, not this row's -- [onTap] is invoked with no arguments so this file
 * doesn't need to know about either type.
 */
@Composable
fun SearchResultRow(
    row: AlbumSearchResult,
    addToBinStatus: AddToBinStatus?,
    onTap: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(url = row.artworkUrl)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.albumTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.artistName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.callNumber,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val format = row.formatName
                if (!format.isNullOrEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    FormatCapsule(format)
                }
                // The rotation badge answers exactly what a search row can
                // answer: whether a bin is currently assigned. Unlike
                // `GET /library/info`'s `AlbumInfo.Rotation` (issue #10's
                // territory), this wire row carries no `kill_date` -- only
                // `rotation_bin` -- so there is nothing here for
                // RotationPredicate's kill-date half to compare against. A
                // non-null rotationBin already *is* this row's whole answer
                // to "is this in rotation", which is why this reads the
                // field directly rather than calling the (internal, and
                // deliberately so) shared predicate -- mirroring iOS's
                // SearchResultRow, which reads `row.rotationBin` the same
                // way with no separate predicate call.
                row.rotationBin?.let { bin ->
                    Spacer(Modifier.width(6.dp))
                    RotationBadge(bin)
                }
            }
            TrackMatchBadge(hints = row.matchedVia)
        }
        Spacer(Modifier.width(8.dp))
        AddToBinButton(status = addToBinStatus, onClick = onAdd)
    }
}

@Composable
private fun Artwork(url: String?) {
    val shape = RoundedCornerShape(4.dp)
    if (url.isNullOrEmpty()) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun FormatCapsule(format: String) {
    Text(
        format,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun RotationBadge(bin: RotationBin) {
    Text(
        bin.wireValue,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .clip(CircleShape)
            .background(rotationColor(bin))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Mirrors iOS's `RotationBadge.color` mapping (heavy/medium/light/single -> red/orange/yellow/gray). */
private fun rotationColor(bin: RotationBin): Color = when (bin) {
    RotationBin.Heavy -> Color(0xFFD32F2F)
    RotationBin.Medium -> Color(0xFFF57C00)
    RotationBin.Light -> Color(0xFFFBC02D)
    RotationBin.Single -> Color(0xFF757575)
}

@Composable
private fun AddToBinButton(status: AddToBinStatus?, onClick: () -> Unit) {
    val stillActionable = status != AddToBinStatus.InFlight && status != AddToBinStatus.Added
    IconButton(onClick = onClick, enabled = stillActionable) {
        when (status) {
            AddToBinStatus.InFlight -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            AddToBinStatus.Added -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.search_add_to_bin_added_description),
                tint = MaterialTheme.colorScheme.primary,
            )
            // A visible failure that invites a retry, rather than a red
            // banner that would disturb the rest of the list (issue #9's
            // acceptance criteria) -- tapping this re-runs the same
            // SearchViewModel.addToBin call.
            AddToBinStatus.Failed -> Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.search_add_to_bin_retry_description),
                tint = MaterialTheme.colorScheme.error,
            )
            null -> Icon(
                Icons.Filled.AddCircle,
                contentDescription = stringResource(R.string.search_add_to_bin_description),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

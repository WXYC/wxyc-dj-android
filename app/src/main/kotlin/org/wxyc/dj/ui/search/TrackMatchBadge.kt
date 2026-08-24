package org.wxyc.dj.ui.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import org.wxyc.dj.R
import org.wxyc.dj.api.TrackMatchHint

/**
 * Inline annotation on a search row that was surfaced by a track-title match
 * rather than an artist/album hit (issue #9, port of
 * `WXYCDJ/Search/TrackMatchBadge.swift`). Renders nothing for an empty
 * [hints] list -- the common case, an ordinary artist/album hit.
 */
@Composable
fun TrackMatchBadge(hints: List<TrackMatchHint>) {
    val summary = trackMatchSummary(
        hints = hints,
        singleTemplate = stringResource(R.string.search_track_match_single),
        multipleTemplate = stringResource(R.string.search_track_match_multiple),
    ) ?: return
    Text(
        summary,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * "via track: «Title»" for one hint, "via track: «Title» (+N more)" for
 * several, `null` for an empty list -- mirrors iOS's
 * `TrackMatchBadge.summary(from:)`. Pulled out as a plain function (not a
 * `@Composable`) so it's unit-testable without a Compose test rule, the same
 * split `TrackMatchBadgeTests.swift` pins the Swift original against.
 *
 * [singleTemplate]/[multipleTemplate] are `%1$s`/`%2$d`-style resource
 * strings the caller resolves via `stringResource(...)` (see
 * [TrackMatchBadge] above) rather than hardcoded here -- mirrors
 * `LoginScreen.kt`'s `displayTarget`, which takes its fallback wording the
 * same way, so this file stays the one place owning this screen's
 * user-facing English while the formatting logic itself needs no Android
 * framework to test.
 */
fun trackMatchSummary(hints: List<TrackMatchHint>, singleTemplate: String, multipleTemplate: String): String? {
    val first = hints.firstOrNull() ?: return null
    return if (hints.size == 1) {
        String.format(singleTemplate, first.title)
    } else {
        String.format(multipleTemplate, first.title, hints.size - 1)
    }
}

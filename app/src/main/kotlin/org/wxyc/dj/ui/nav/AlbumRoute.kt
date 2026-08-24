package org.wxyc.dj.ui.nav

import kotlinx.serialization.Serializable
import org.wxyc.dj.api.AlbumSearchResult

/**
 * The single value navigated to for every album detail push, reachable from
 * both the search and bin tabs (issue #7's nav skeleton) -- the Kotlin port
 * of `WXYCDJ/AlbumRoute.swift` and its test, `WXYCDJTests/AlbumRouteTests.swift`.
 *
 * [equals]/[hashCode] key on [id] **alone**, deliberately ignoring
 * [fallback]: a route built from a search row and one built from a bin row
 * for the same album must coalesce on the back stack, even though
 * [AlbumSearchResult]'s field-for-field equality would diverge between two
 * differently-sourced rows for the same record (add date, artwork, play
 * count). [fallback] is carried so a screen that already has a row in hand
 * can render the detail header before `/library/info` resolves -- it is
 * payload, not identity. This is why [AlbumRoute] is a plain `class` with
 * hand-written [equals]/[hashCode] rather than a `data class`: the
 * auto-generated whole-property equality a `data class` would give it is
 * exactly the thing that must NOT hold here -- see `AlbumRouteTest` for the
 * ported coalescing cases.
 *
 * `@Serializable` so Navigation Compose's type-safe routing can carry it;
 * [fallback]'s type isn't a Bundle-native shape, so [AlbumSearchResultNavType]
 * supplies the `typeMap` entry `composable<AlbumRoute>` needs (see
 * `MainScaffold.kt`).
 */
@Serializable
class AlbumRoute(val id: Int, val fallback: AlbumSearchResult? = null) {
    override fun equals(other: Any?): Boolean = other is AlbumRoute && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "AlbumRoute(id=$id, fallback=${if (fallback != null) "<present>" else "null"})"
}

/** The search tab's root destination. Issue #9 fills in [org.wxyc.dj.ui.search.SearchScreen]. */
@Serializable
object SearchRoute

/** The bin tab's root destination. Issue #11 fills in [org.wxyc.dj.ui.bin.BinScreen]. */
@Serializable
object BinRoute

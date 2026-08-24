package org.wxyc.dj.ui.nav

import kotlinx.serialization.Serializable
import org.wxyc.dj.api.AlbumSearchResult

/**
 * The single value navigated to for every album detail push, reachable from
 * both the search and bin tabs (issue #7's nav skeleton) -- the Kotlin port
 * of `WXYCDJ/AlbumRoute.swift` and its test, `WXYCDJTests/AlbumRouteTests.swift`.
 *
 * [equals]/[hashCode] key on [id] **alone**, deliberately ignoring
 * [fallback] -- ported for parity with iOS's `AlbumRoute`, whose id-only
 * identity is load-bearing there because `NavigationPath` (SwiftUI's
 * back-stack type) *does* key on a pushed value's own `Hashable`
 * conformance, so a fallback-bearing and fallback-less route for the same
 * album genuinely coalesce on iOS's back stack.
 *
 * **That does not carry over to Navigation Compose, and this type has no
 * production consumer of [equals]/[hashCode] today.** Navigation Compose
 * derives back-stack identity from the destination's *encoded route
 * string*, generated from every property Kotlin serialization sees --
 * including [fallback], via [AlbumSearchResultNavType.serializeAsValue] --
 * never from a route instance's `equals`. `AlbumRoute(42, searchRow)` and
 * `AlbumRoute(42, binRow)` therefore produce two distinct route strings, and
 * so two distinct `NavBackStackEntry`s with separate `ViewModelStore`s, even
 * though [equals] reports them equal. `popBackStack<AlbumRoute>()` and
 * `getBackStackEntry<AlbumRoute>()` (no instance argument) match the nearest
 * back-stack entry whose *destination* is `AlbumRoute` by route pattern, not
 * by this class's [equals]; the overloads that take an actual `AlbumRoute`
 * instance match its specific encoded route string, again not [equals].
 *
 * [fallback] is carried so a screen that already has a row in hand can
 * render the detail header before `/library/info` resolves -- it is
 * payload, not identity, and the id-only [equals]/[hashCode] exist so this
 * type keeps the same value semantics as its iOS counterpart (and the
 * ported `AlbumRouteTest` cases) even though nothing here reads them. This
 * is why [AlbumRoute] is a plain `class` with hand-written [equals]/[hashCode]
 * rather than a `data class`: the auto-generated whole-property equality a
 * `data class` would give it would break that ported parity.
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

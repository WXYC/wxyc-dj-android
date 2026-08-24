package org.wxyc.dj.ui.nav

import kotlinx.serialization.Serializable

/**
 * The single value navigated to for every album detail push, reachable from
 * both the search and bin tabs (issue #7's nav skeleton). Originally ported
 * from `WXYCDJ/AlbumRoute.swift` as a hand-`equals`/`hashCode` class carrying
 * a nested `fallback: AlbumSearchResult?` -- issue #23 replaced that shape
 * with this one after finding the port was cargo-culted past the point
 * where it actually worked.
 *
 * **Why the old shape didn't port.** SwiftUI's `NavigationPath` keys its
 * back stack on a pushed value's own `Hashable` conformance, so iOS's
 * `AlbumRoute` could carry a `fallback` payload and still coalesce two
 * differently-sourced routes for the same album by writing `==`/`hash(into:)`
 * over `id` alone. **Navigation Compose does not key the back stack on
 * `equals` at all** -- it derives identity from the destination's *encoded
 * route string*, generated from every property Kotlin serialization sees.
 * A class carrying `id` plus a serialized `fallback` therefore produced two
 * distinct route strings (and so two distinct `NavBackStackEntry`s, each
 * with its own `ViewModelStore`) for the exact case the ported invariant
 * exists to prevent -- the hand-written `equals`/`hashCode` had **no
 * production consumer**, and the test that pinned them (`Set` semantics
 * over in-memory instances) could not see the gap because nothing in the
 * app ever put two [AlbumRoute] instances in a `Set`.
 *
 * **The fix is to make identity and payload the same shape everywhere.**
 * [AlbumRoute] carries only [id], so its `@Serializable`-generated encoded
 * route string ("AlbumRoute/{id}") is id-only by construction, and so are
 * the `data class`-generated [equals]/[hashCode] -- there is nothing left
 * for them to disagree about. That is what makes
 * `getBackStackEntry<AlbumRoute>()`/`getBackStackEntry(AlbumRoute(id))` and
 * `popBackStack<AlbumRoute>()`/`popBackStack(AlbumRoute(id), ...)` -- all of
 * which match by encoded route string, not by [equals] -- resolve an
 * already-open detail for the same album regardless of which screen
 * constructed the lookup. [AlbumRouteNavigationTest] drives a real
 * `NavHostController.navigate()` to pin exactly that; a `Set`-based test
 * cannot exercise this property, which is the mistake being corrected here.
 *
 * **The row a caller already has in hand travels out of band.** A screen
 * that already holds a matching row (a search result, a bin entry) can
 * still render the detail header instantly, without waiting on
 * `/library/info` -- but that row is no longer part of the route. It is
 * stashed into [AlbumRouteFallbackStore] immediately before navigating, and
 * read back exactly once by `AlbumDetailScreen`. See that store's KDoc for
 * why a plain in-memory holder was chosen over `SavedStateHandle`, and
 * `AlbumDetailScreen.kt` for the read side. This also retires
 * `AlbumSearchResultNavType` entirely: that `NavType` existed only to
 * squeeze [org.wxyc.dj.api.AlbumSearchResult] through the route string as
 * JSON, and it is where issue #7's blocking defect lived (a double-decode
 * that silently corrupted "C+C Music Factory" and dropped the whole row for
 * "100% Silk", per `AlbumSearchResultNavTypeTest` on `main`). An id-only
 * route needs no custom `NavType` at all -- `Int` is one of the primitives
 * Navigation's type-safe routing derives automatically -- so the whole class
 * of route-string-encoding bugs it was written to guard against is now
 * structurally impossible rather than merely tested for.
 */
@Serializable
data class AlbumRoute(val id: Int)

/** The search tab's root destination. Issue #9 fills in [org.wxyc.dj.ui.search.SearchScreen]. */
@Serializable
object SearchRoute

/** The bin tab's root destination. Issue #11 fills in [org.wxyc.dj.ui.bin.BinScreen]. */
@Serializable
object BinRoute

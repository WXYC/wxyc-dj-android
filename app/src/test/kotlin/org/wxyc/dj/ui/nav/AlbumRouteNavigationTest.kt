package org.wxyc.dj.ui.nav

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.wxyc.dj.api.AlbumSearchResult

/**
 * Pins the property issue #23 exists to restore: two differently-sourced
 * [AlbumRoute]s for the same album resolve to **one**
 * [androidx.navigation.NavBackStackEntry], driven through a real
 * [androidx.navigation.NavHostController.navigate] / `getBackStackEntry` /
 * `popBackStack` rather than a `Set`/`equals` check over in-memory
 * instances.
 *
 * **Why not `Set` semantics.** The class this replaces
 * (`AlbumRouteTest`'s `a set collapses routes with the same id`) pinned
 * `AlbumRoute`'s hand-written `equals`/`hashCode`, which had no production
 * consumer: Navigation Compose keys the back stack on the destination's
 * *encoded route string*, not on `equals`, so a `Set`-based test cannot see
 * whether two differently-sourced routes actually coalesce on a real back
 * stack. This suite drives the actual mechanism instead.
 *
 * **Why the old shape would have failed a suite like this.** Before issue
 * #23, [AlbumRoute] carried `id` plus a serialized `fallback:
 * AlbumSearchResult?`, so two calls constructing `AlbumRoute(42, rowA)` and
 * `AlbumRoute(42, rowB)` encoded to two different route strings and were
 * two different destinations as far as `getBackStackEntry`/`popBackStack`
 * were concerned, even though both were "album 42." Making the route
 * id-only and moving the row to [AlbumRouteFallbackStore] closes that gap by
 * construction: there is nothing left in the route for two sources to
 * disagree about.
 *
 * Robolectric, not plain JUnit: [NavHostController] and
 * [androidx.navigation.compose.ComposeNavigator] resolve `Context` against
 * the real framework classes Robolectric provides.
 */
@RunWith(RobolectricTestRunner::class)
class AlbumRouteNavigationTest {

    @Before
    @After
    fun resetFallbackStore() {
        AlbumRouteFallbackStore.clearForTesting()
    }

    private fun buildNavController(): NavHostController {
        val navController = NavHostController(RuntimeEnvironment.getApplication())
        navController.navigatorProvider.addNavigator(ComposeNavigator())

        val graph = navController.createGraph(startDestination = SearchRoute) {
            composable<SearchRoute> { }
            composable<BinRoute> { }
            composable<AlbumRoute> { }
        }
        navController.graph = graph
        return navController
    }

    private fun rowFor(id: Int, albumTitle: String = "DOGA", artistName: String = "Juana Molina") =
        AlbumSearchResult(id = id, albumTitle = albumTitle, artistName = artistName)

    /**
     * The acceptance test: a search-sourced navigation opens album 42, then
     * a bin-sourced [AlbumRoute] -- a fresh instance, built with no
     * knowledge of the search screen's call -- looks up the same album via
     * the real [androidx.navigation.NavController.getBackStackEntry]
     * instance overload. They must resolve to the exact same
     * [androidx.navigation.NavBackStackEntry], not merely to `equals`
     * values: [androidx.navigation.NavBackStackEntry.getId] is a stable
     * per-push identifier, so an equal id here proves no second entry was
     * created.
     */
    @Test
    fun `a bin-sourced lookup resolves to the same entry a search-sourced navigate already opened`() {
        val navController = buildNavController()
        // currentBackStack includes the graph's own root entry beneath
        // SearchRoute, so the baseline is captured rather than assumed --
        // only the *delta* a navigate()/lookup produces is asserted below.
        val baseline = navController.currentBackStack.value.size

        AlbumRouteFallbackStore.stash(id = 42, fallback = rowFor(42))
        navController.navigate(AlbumRoute(id = 42))
        val opened = requireNotNull(navController.currentBackStackEntry)
        assertEquals(baseline + 1, navController.currentBackStack.value.size) // one new entry: AlbumRoute

        // A different source (bin), constructing its own AlbumRoute value
        // with no reference to the search screen's instance or its row.
        val fromBin = navController.getBackStackEntry(AlbumRoute(id = 42))

        assertEquals(opened.id, fromBin.id)
        assertEquals(baseline + 1, navController.currentBackStack.value.size) // no second entry was created
    }

    /**
     * [androidx.navigation.NavController.popBackStack]'s instance overload
     * is the other half of acceptance criterion 2: it must match an
     * already-open detail by id alone, not by whichever row happened to be
     * in hand when the entry was first pushed.
     */
    @Test
    fun `popBackStack for the same album pops back to the already-open entry`() {
        val navController = buildNavController()
        val baseline = navController.currentBackStack.value.size

        AlbumRouteFallbackStore.stash(id = 42, fallback = rowFor(42))
        navController.navigate(AlbumRoute(id = 42))
        val opened = requireNotNull(navController.currentBackStackEntry)

        // Something is pushed on top of the open detail (e.g. a further
        // navigation reachable from it) before the app decides to return to
        // album 42 specifically.
        navController.navigate(BinRoute)
        assertEquals(baseline + 2, navController.currentBackStack.value.size)

        val popped = navController.popBackStack(AlbumRoute(id = 42), inclusive = false)

        assertEquals(true, popped)
        val current = requireNotNull(navController.currentBackStackEntry)
        assertEquals(opened.id, current.id)
        assertEquals(42, current.toRoute<AlbumRoute>().id)
        assertEquals(baseline + 1, navController.currentBackStack.value.size)
    }

    /**
     * Acceptance criterion 3: the row a caller already has in hand must be
     * available with no round trip. This asserts the fallback is
     * resolvable via a plain synchronous call immediately after
     * `navigate()` returns -- no suspend function, no coroutine, no
     * network -- which is what lets `AlbumDetailScreen` render its header
     * on the very first composition.
     */
    @Test
    fun `the stashed row is available synchronously right after navigate, with no round trip`() {
        val navController = buildNavController()
        val searchRow = rowFor(42)

        AlbumRouteFallbackStore.stash(id = 42, fallback = searchRow)
        navController.navigate(AlbumRoute(id = 42))

        val route = navController.currentBackStackEntry!!.toRoute<AlbumRoute>()
        val fallback = AlbumRouteFallbackStore.take(route.id)

        assertEquals(searchRow, fallback)
    }

    /**
     * The clone-miss case: a route reached with nothing stashed for it (a
     * cold-launch deep link, in the iOS analogue) must not pick up an
     * unrelated, unconsumed stash left behind for a different album.
     */
    @Test
    fun `a route with nothing stashed for its id resolves no fallback, even if a stale entry is pending`() {
        val navController = buildNavController()
        AlbumRouteFallbackStore.stash(id = 7, fallback = rowFor(7)) // unrelated, never consumed

        navController.navigate(AlbumRoute(id = 99))

        val route = navController.currentBackStackEntry!!.toRoute<AlbumRoute>()
        assertNull(AlbumRouteFallbackStore.take(route.id))
    }
}

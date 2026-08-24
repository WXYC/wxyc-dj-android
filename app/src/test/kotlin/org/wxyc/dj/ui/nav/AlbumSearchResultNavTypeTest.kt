package org.wxyc.dj.ui.nav

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import kotlin.reflect.typeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.wxyc.dj.api.AlbumSearchResult

/**
 * Pins [AlbumSearchResultNavType] against a **real**
 * [androidx.navigation.NavHostController.navigate], not a
 * [AlbumSearchResultNavType.serializeAsValue]/[AlbumSearchResultNavType.parseValue]
 * method pair called back to back. That pairing was the original test's
 * defect: `NavDeepLink`/argument matching interposes exactly one
 * [android.net.Uri.decode] between the two, so a `serializeAsValue`/`parseValue`
 * pair that each apply their own encode/decode step cancels the mismatch out
 * and can never see it. Driving a real `navigate()` + `toRoute()` restores
 * that single interposed decode, which is what actually exposed the bug:
 * `URLEncoder`/`URLDecoder` (form encoding) paired against Navigation's own
 * `Uri.decode` (percent encoding) either corrupted the payload silently or
 * threw inside `NavDeepLink.parseInputParams`, which discards the exception
 * and falls back to [AlbumRoute]'s declared `fallback = null` default with no
 * signal anywhere.
 *
 * Robolectric, not plain JUnit: [NavHostController] and
 * [androidx.navigation.compose.ComposeNavigator] both resolve `Context`/`Uri`
 * against the real framework classes Robolectric provides, and this suite
 * needs the framework's actual [android.net.Uri.decode]/[android.net.Uri.encode]
 * behavior, not a fake.
 */
@RunWith(RobolectricTestRunner::class)
class AlbumSearchResultNavTypeTest {

    /**
     * Builds a fresh nav graph, navigates to an [AlbumRoute] carrying
     * [fallback], and reads the fallback back off
     * `currentBackStackEntry.toRoute<AlbumRoute>()` -- the same path
     * `MainScaffold.kt`'s `composable<AlbumRoute>` destination reads through.
     */
    private fun navigateAndCaptureFallback(fallback: AlbumSearchResult?): AlbumSearchResult? {
        val navController = NavHostController(RuntimeEnvironment.getApplication())
        navController.navigatorProvider.addNavigator(ComposeNavigator())

        val graph = navController.createGraph(startDestination = SearchRoute) {
            composable<SearchRoute> { }
            composable<AlbumRoute>(
                typeMap = mapOf(typeOf<AlbumSearchResult?>() to AlbumSearchResultNavType),
            ) { }
        }
        navController.graph = graph

        navController.navigate(AlbumRoute(id = 99, fallback = fallback))

        val entry = requireNotNull(navController.currentBackStackEntry)
        return entry.toRoute<AlbumRoute>().fallback
    }

    private fun rowWith(albumTitle: String) =
        AlbumSearchResult(id = 1, albumTitle = albumTitle, artistName = "Chuquimamani-Condori")

    @Test
    fun `a plain ASCII title survives navigate`() {
        val fallback = navigateAndCaptureFallback(rowWith("Edits"))
        assertEquals("Edits", fallback?.albumTitle)
    }

    @Test
    fun `a title with internal spaces survives navigate`() {
        val fallback = navigateAndCaptureFallback(rowWith("On Your Own Love Again"))
        assertEquals("On Your Own Love Again", fallback?.albumTitle)
    }

    @Test
    fun `a plus sign is not corrupted into a space -- Sun Ra plus His Arkestra`() {
        val fallback = navigateAndCaptureFallback(rowWith("Sun Ra + His Arkestra"))
        assertEquals("Sun Ra + His Arkestra", fallback?.albumTitle)
    }

    @Test
    fun `a leading plus sign survives navigate -- C plus C Music Factory`() {
        val fallback = navigateAndCaptureFallback(rowWith("C+C Music Factory"))
        assertEquals("C+C Music Factory", fallback?.albumTitle)
    }

    @Test
    fun `a percent followed by hex-looking digits survives navigate`() {
        val fallback = navigateAndCaptureFallback(rowWith("100%41 Silk"))
        assertEquals("100%41 Silk", fallback?.albumTitle)
    }

    @Test
    fun `a percent followed by a hex escape for space survives navigate`() {
        val fallback = navigateAndCaptureFallback(rowWith("100%20Silk"))
        assertEquals("100%20Silk", fallback?.albumTitle)
    }

    @Test
    fun `a bare trailing percent does not blow up the whole fallback -- 100 percent Silk`() {
        val fallback = navigateAndCaptureFallback(rowWith("100% Silk"))
        assertEquals("100% Silk", fallback?.albumTitle)
    }

    @Test
    fun `an ampersand survives navigate`() {
        val fallback = navigateAndCaptureFallback(rowWith("Duke Ellington & John Coltrane"))
        assertEquals("Duke Ellington & John Coltrane", fallback?.albumTitle)
    }

    @Test
    fun `non-ASCII characters survive navigate`() {
        val fallback = navigateAndCaptureFallback(rowWith("Csillagrablók"))
        assertEquals("Csillagrablók", fallback?.albumTitle)
    }

    @Test
    fun `a full URL embedded in a field survives navigate`() {
        val row = AlbumSearchResult(
            id = 1,
            albumTitle = "DOGA",
            artistName = "Juana Molina",
            artworkUrl = "https://example.com/cover.jpg?size=large&format=jpg",
        )

        val fallback = navigateAndCaptureFallback(row)

        assertEquals("https://example.com/cover.jpg?size=large&format=jpg", fallback?.artworkUrl)
    }

    @Test
    fun `null round-trips through navigate`() {
        val fallback = navigateAndCaptureFallback(null)
        assertNull(fallback)
    }

    @Test
    fun `every field of a hostile row survives navigate together`() {
        val row = AlbumSearchResult(
            id = 42,
            albumTitle = "100% Silk",
            artistName = "Sun Ra + His Arkestra",
            label = "C+C Music Factory Records & Tapes",
        )

        val fallback = requireNotNull(navigateAndCaptureFallback(row))

        assertEquals(row.id, fallback.id)
        assertEquals(row.albumTitle, fallback.albumTitle)
        assertEquals(row.artistName, fallback.artistName)
        assertEquals(row.label, fallback.label)
    }
}

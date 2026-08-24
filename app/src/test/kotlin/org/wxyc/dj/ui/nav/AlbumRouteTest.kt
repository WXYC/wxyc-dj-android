package org.wxyc.dj.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.wxyc.dj.api.AlbumSearchResult

/**
 * Pins [AlbumRoute]'s id-only value semantics (issue #7's nav skeleton) --
 * the Kotlin port of `WXYCDJTests/AlbumRouteTests.swift`. The route is the
 * single value navigated to for every album detail push, so a route built
 * from a search row and one built from a bin row for the same album must
 * coalesce, even though [AlbumSearchResult]'s field-for-field equality
 * differs between a cloned/live row on add date, artwork, and play count.
 *
 * Plain JUnit4, no Robolectric: [AlbumRoute.equals]/[AlbumRoute.hashCode]
 * touch no Android API.
 */
class AlbumRouteTest {

    /** A minimal row and a fuller one for the same album -- the exact whole-property drift the issue calls out. */
    private fun minimalRow(id: Int) = AlbumSearchResult(id = id, albumTitle = "DOGA", artistName = "Juana Molina")

    private fun fullerRow(id: Int) = AlbumSearchResult(
        id = id,
        albumTitle = "DOGA",
        artistName = "Juana Molina",
        plays = 12,
        artworkUrl = "https://example.com/doga.jpg",
    )

    @Test
    fun `a fallback-bearing route and a fallback-less route for the same id coalesce`() {
        val withFallback = AlbumRoute(id = 42, fallback = minimalRow(42))
        val withoutFallback = AlbumRoute(id = 42, fallback = null)

        assertEquals(withFallback, withoutFallback)
        assertEquals(withFallback.hashCode(), withoutFallback.hashCode())
    }

    @Test
    fun `differing fallbacks with the same id are equal`() {
        val minimal = minimalRow(7)
        val fuller = fullerRow(7)
        // Guard the premise: the two rows really are distinct as whole
        // objects, so the equality below proves id-only keying rather than
        // a coincidence.
        assertNotEquals(minimal, fuller)

        val minimalRoute = AlbumRoute(id = 7, fallback = minimal)
        val fullerRoute = AlbumRoute(id = 7, fallback = fuller)

        assertEquals(minimalRoute, fullerRoute)
        assertEquals(minimalRoute.hashCode(), fullerRoute.hashCode())
    }

    @Test
    fun `different ids are not equal`() {
        val a = AlbumRoute(id = 1, fallback = minimalRow(1))
        val b = AlbumRoute(id = 2, fallback = minimalRow(2))

        assertNotEquals(a, b)
    }

    @Test
    fun `a set collapses routes with the same id`() {
        val routes = setOf(
            AlbumRoute(id = 5, fallback = minimalRow(5)),
            AlbumRoute(id = 5, fallback = null),
            AlbumRoute(id = 5, fallback = fullerRow(5)),
        )

        assertEquals(1, routes.size)
    }

    @Test
    fun `the fallback is preserved for the instant header render`() {
        // id-only equality must not discard the payload: the caller reads
        // .fallback to render the detail header before /library/info
        // returns.
        val row = minimalRow(3)
        val route = AlbumRoute(id = 3, fallback = row)

        val fallback = requireNotNull(route.fallback)
        assertEquals("DOGA", fallback.albumTitle)
        assertEquals("Juana Molina", fallback.artistName)
    }
}

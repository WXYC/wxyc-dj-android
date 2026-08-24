package org.wxyc.dj.ui.nav

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.wxyc.dj.api.AlbumSearchResult

/**
 * Pins [AlbumRouteFallbackStore]'s single-slot, last-write-wins, single-use
 * contract (issue #23) in isolation from Navigation Compose -- the real
 * `navigate()`-driven proof that this hand-off actually keeps [AlbumRoute]
 * coalescing lives in [AlbumRouteNavigationTest].
 *
 * Plain JUnit4, no Robolectric: the store touches no Android API. [Before]/
 * [After] reset the object's single mutable slot -- see
 * [AlbumRouteFallbackStore.clearForTesting]'s KDoc for why an `object`'s
 * state needs an explicit reset between test methods.
 */
class AlbumRouteFallbackStoreTest {

    private fun rowFor(id: Int) = AlbumSearchResult(id = id, albumTitle = "DOGA", artistName = "Juana Molina")

    @Before
    @After
    fun resetStore() {
        AlbumRouteFallbackStore.clearForTesting()
    }

    @Test
    fun `take returns the row stashed for the matching id`() {
        val row = rowFor(42)
        AlbumRouteFallbackStore.stash(id = 42, fallback = row)

        assertSame(row, AlbumRouteFallbackStore.take(id = 42))
    }

    @Test
    fun `take clears the slot -- a second take for the same id sees nothing`() {
        AlbumRouteFallbackStore.stash(id = 42, fallback = rowFor(42))

        assertEquals(rowFor(42), AlbumRouteFallbackStore.take(id = 42))
        assertNull(AlbumRouteFallbackStore.take(id = 42))
    }

    @Test
    fun `take for a mismatched id returns null and leaves the pending entry alone`() {
        val row = rowFor(7)
        AlbumRouteFallbackStore.stash(id = 7, fallback = row)

        assertNull(AlbumRouteFallbackStore.take(id = 99))
        // The mismatched read didn't consume the real entry -- it's still there for its own id.
        assertSame(row, AlbumRouteFallbackStore.take(id = 7))
    }

    @Test
    fun `take with nothing stashed returns null`() {
        assertNull(AlbumRouteFallbackStore.take(id = 1))
    }

    @Test
    fun `a second stash overwrites the first -- one slot, last write wins`() {
        val first = rowFor(1)
        val second = AlbumSearchResult(id = 1, albumTitle = "On Your Own Love Again", artistName = "Jessica Pratt")

        AlbumRouteFallbackStore.stash(id = 1, fallback = first)
        AlbumRouteFallbackStore.stash(id = 1, fallback = second)

        assertSame(second, AlbumRouteFallbackStore.take(id = 1))
    }

    @Test
    fun `stashing for a different id discards an unconsumed earlier stash`() {
        AlbumRouteFallbackStore.stash(id = 1, fallback = rowFor(1))
        AlbumRouteFallbackStore.stash(id = 2, fallback = rowFor(2))

        assertNull(AlbumRouteFallbackStore.take(id = 1))
        assertEquals(rowFor(2), AlbumRouteFallbackStore.take(id = 2))
    }
}

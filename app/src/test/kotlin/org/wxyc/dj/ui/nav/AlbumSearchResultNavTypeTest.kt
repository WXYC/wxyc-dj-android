package org.wxyc.dj.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.wxyc.dj.api.AlbumSearchResult

/**
 * Pins [AlbumSearchResultNavType.parseValue]/[AlbumSearchResultNavType.serializeAsValue]
 * -- the half of the [androidx.navigation.NavType] that needs no
 * [android.os.Bundle] (unlike [AlbumSearchResultNavType.put]/[AlbumSearchResultNavType.get],
 * which are direct passthroughs to `Bundle.putString`/`getString` and need
 * an Android runtime to exercise). Plain JUnit4, no Robolectric: this half
 * only touches `java.net.URLEncoder`/`URLDecoder` and `:api`'s
 * [org.wxyc.dj.api.WxycJson].
 */
class AlbumSearchResultNavTypeTest {

    @Test
    fun `round-trips a non-null value through the encoded route string`() {
        val row = AlbumSearchResult(id = 11, albumTitle = "Edits", artistName = "Chuquimamani-Condori")

        val encoded = AlbumSearchResultNavType.serializeAsValue(row)
        val decoded = AlbumSearchResultNavType.parseValue(encoded)

        assertEquals(row.id, decoded?.id)
        assertEquals(row.albumTitle, decoded?.albumTitle)
        assertEquals(row.artistName, decoded?.artistName)
    }

    @Test
    fun `round-trips null`() {
        val encoded = AlbumSearchResultNavType.serializeAsValue(null)

        assertEquals("null", encoded)
        assertNull(AlbumSearchResultNavType.parseValue(encoded))
    }

    @Test
    fun `the encoded string is URL-safe`() {
        // A raw JSON blob contains characters (quotes, braces, colons) that
        // are not legal unescaped inside a route's path segment -- this is
        // the exact property serializeAsValue exists to guarantee.
        val row = AlbumSearchResult(id = 1, albumTitle = "On Your Own Love Again", artistName = "Jessica Pratt")

        val encoded = AlbumSearchResultNavType.serializeAsValue(row)

        assertEquals(false, encoded.contains("\""))
        assertEquals(false, encoded.contains("{"))
        assertEquals(false, encoded.contains(" "))
    }
}

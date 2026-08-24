package org.wxyc.dj.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wxyc.dj.api.AlbumInfo
import org.wxyc.dj.api.AlbumMetadata
import org.wxyc.dj.api.AlbumSearchResult

/**
 * Pins invariant 17 (header artwork precedence + the issue-#86 dead-URL
 * fallthrough) and invariant 18's rendering half (the offline/loading
 * catalog-row resolver, plus the Release-section label dedup). Direct port
 * of `AlbumDetailArtworkTests.swift` / `AlbumDetailFallbackTests.swift`,
 * trimmed to the no-clone precedence chain this v1 port actually has (see
 * `AlbumDetailPrecedence.kt`'s file KDoc).
 *
 * Regression guard for the "correct cover replaced by the label logo" bug
 * (e.g. Autechre -- *Confield* swapped for the Warp Records logo), in both
 * its forms: LML landing first, and `/library/info` landing with no
 * `artwork_url` and knocking the fallback row's cover out of the running.
 */
class AlbumDetailPrecedenceTest {

    private val infoArt = "https://info.example/confield-cover.jpg"
    private val fallbackArt = "https://search.example/confield-cover.jpg"
    private val lmlArt = "https://lml.example/warp-logo.jpg"

    /** `/library/info` never projects `artwork_url` in production -- `artworkUrl` defaults to absent, matching that shape. */
    private fun dogaInfo(artworkUrl: String? = null) = AlbumInfo(
        id = 100,
        albumTitle = "DOGA",
        artistName = "Juana Molina",
        codeLetters = "MOL",
        codeNumber = 12,
        codeArtistNumber = 1,
        formatName = "CD",
        genreName = "Rock",
        artworkUrl = artworkUrl,
    )

    /** The live search row the DJ tapped -- the search endpoint projects `artwork_url`, which is why the cover shows in results. */
    private fun dogaFallback(artworkUrl: String? = fallbackArt) = AlbumSearchResult(
        id = 100,
        albumTitle = "DOGA",
        artistName = "Juana Molina",
        codeLetters = "MOL",
        codeNumber = 12,
        codeArtistNumber = 1,
        formatName = "CD",
        genreName = "Rock",
        label = "Sonamos",
        artworkUrl = artworkUrl,
    )

    /** LML enrichment whose `artworkUrl` resolved to a label logo, not the cover. */
    private fun labelLogoMetadata() = AlbumMetadata(label = "Sonamos", artworkUrl = lmlArt)

    // MARK: - Artwork precedence (invariant 17)

    @Test
    fun `catalog library-info art wins over every other source`() {
        val url = preferredArtworkUrl(
            info = dogaInfo(artworkUrl = infoArt),
            fallback = dogaFallback(),
            metadata = labelLogoMetadata(),
        )
        assertEquals(infoArt, url)
    }

    @Test
    fun `fallback art survives library-info landing without artwork_url`() {
        // The reported bug: /library/info carries no artwork_url, so once it
        // lands the fallback row must stay in the running -- otherwise LML's
        // label logo visibly replaces the correct cover a beat after push.
        val url = preferredArtworkUrl(
            info = dogaInfo(),
            fallback = dogaFallback(),
            metadata = labelLogoMetadata(),
        )
        assertEquals(fallbackArt, url)
    }

    @Test
    fun `fallback art wins over LML metadata art before library-info lands`() {
        val url = preferredArtworkUrl(info = null, fallback = dogaFallback(), metadata = labelLogoMetadata())
        assertEquals(fallbackArt, url)
    }

    @Test
    fun `LML metadata art is used only when the catalog has none`() {
        val url = preferredArtworkUrl(
            info = dogaInfo(),
            fallback = dogaFallback(artworkUrl = null),
            metadata = labelLogoMetadata(),
        )
        assertEquals(lmlArt, url)
    }

    @Test
    fun `no artwork anywhere yields null`() {
        val url = preferredArtworkUrl(info = dogaInfo(), fallback = null, metadata = null)
        assertNull(url)
    }

    // MARK: - Issue #86: a dead catalog URL falls through instead of blanking

    @Test
    fun `every catalog source failing falls through to LML as the last resort`() {
        val url = preferredArtworkUrl(
            info = dogaInfo(artworkUrl = infoArt),
            fallback = dogaFallback(),
            metadata = labelLogoMetadata(),
            failedUrls = setOf(infoArt, fallbackArt),
        )
        assertEquals(lmlArt, url)
    }

    @Test
    fun `a failed info URL falls through to the fallback, not straight to LML`() {
        // Confirms the fallthrough only skips the specific failed URL and
        // keeps walking precedence -- it doesn't collapse straight to LML the
        // instant the first candidate fails.
        val url = preferredArtworkUrl(
            info = dogaInfo(artworkUrl = infoArt),
            fallback = dogaFallback(),
            metadata = labelLogoMetadata(),
            failedUrls = setOf(infoArt),
        )
        assertEquals(fallbackArt, url)
    }

    @Test
    fun `a failed fallback URL cannot mask a working info URL`() {
        // Failures are scoped to the URL that failed, never to "the catalog"
        // as a whole -- a stale failure recorded against a different source's
        // URL must never suppress a healthy one.
        val url = preferredArtworkUrl(
            info = dogaInfo(artworkUrl = infoArt),
            fallback = dogaFallback(),
            metadata = labelLogoMetadata(),
            failedUrls = setOf(fallbackArt),
        )
        assertEquals(infoArt, url)
    }

    @Test
    fun `a failure is keyed by URL -- marking one occurrence skips every source sharing it`() {
        val url = preferredArtworkUrl(
            info = null,
            fallback = dogaFallback(artworkUrl = fallbackArt),
            metadata = labelLogoMetadata(),
            failedUrls = setOf(fallbackArt),
        )
        assertEquals(lmlArt, url)
    }

    @Test
    fun `an empty failure set -- still loading -- never displaces catalog art with LML`() {
        // The #83 invariant, restated for the fallthrough: nothing has been
        // recorded as failed yet (the image may simply still be loading), so
        // the catalog source must still win over LML.
        val url = preferredArtworkUrl(
            info = null,
            fallback = dogaFallback(),
            metadata = labelLogoMetadata(),
            failedUrls = emptySet(),
        )
        assertEquals(fallbackArt, url)
    }

    @Test
    fun `every source failed yields null, not LML`() {
        val url = preferredArtworkUrl(
            info = dogaInfo(artworkUrl = infoArt),
            fallback = dogaFallback(),
            metadata = labelLogoMetadata(),
            failedUrls = setOf(infoArt, fallbackArt, lmlArt),
        )
        assertNull(url)
    }

    // MARK: - Catalog-row resolution (invariant 18's render half)

    @Test
    fun `info present -- render from info, no fallback row, no note`() {
        val resolution = resolveCatalog(info = dogaInfo(), fallback = dogaFallback(), infoFailed = false)
        assertNull(resolution.catalogRow)
        assertNull(resolution.note)
    }

    @Test
    fun `info nil but not yet failed -- live fallback renders un-framed`() {
        val fallback = dogaFallback()
        val resolution = resolveCatalog(info = null, fallback = fallback, infoFailed = false)
        assertEquals(fallback, resolution.catalogRow)
        assertNull(resolution.note)
    }

    @Test
    fun `info nil, failed, fallback present -- catalog from fallback, fallback-row note`() {
        val fallback = dogaFallback()
        val resolution = resolveCatalog(info = null, fallback = fallback, infoFailed = true)
        assertEquals(fallback, resolution.catalogRow)
        assertEquals(CatalogResolution.Note.FALLBACK_ROW, resolution.note)
    }

    @Test
    fun `info nil, failed, nothing renderable -- minimal header, unavailable note`() {
        val resolution = resolveCatalog(info = null, fallback = null, infoFailed = true)
        assertNull(resolution.catalogRow)
        assertEquals(CatalogResolution.Note.UNAVAILABLE, resolution.note)
    }

    // MARK: - Release-section "Label" dedup

    @Test
    fun `LML label is shown only when it differs from the catalog label`() {
        assertTrue(shouldShowMetadataLabel(metadataLabel = "Drag City", catalogLabel = "Sonamos", infoLoaded = true))
        assertFalse(shouldShowMetadataLabel(metadataLabel = "Sonamos", catalogLabel = "Sonamos", infoLoaded = true))
    }

    @Test
    fun `LML label is withheld until the catalog row has settled`() {
        assertFalse(shouldShowMetadataLabel(metadataLabel = "Drag City", catalogLabel = null, infoLoaded = false))
    }

    @Test
    fun `an empty or null LML label is never shown`() {
        assertFalse(shouldShowMetadataLabel(metadataLabel = "", catalogLabel = "Sonamos", infoLoaded = true))
        assertFalse(shouldShowMetadataLabel(metadataLabel = null, catalogLabel = "Sonamos", infoLoaded = true))
    }

    @Test
    fun `LML label shows when there is no catalog label at all`() {
        assertTrue(shouldShowMetadataLabel(metadataLabel = "Drag City", catalogLabel = null, infoLoaded = true))
    }
}

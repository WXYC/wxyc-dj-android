package org.wxyc.dj.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Mirrors the `AlbumMetadata` cases of `DTODecodingTests.swift`. */
class AlbumMetadataTest {
    @Test
    fun `decodes a full LML enrichment response`() {
        val raw = """
            {
              "discogsReleaseId": 1234,
              "discogsArtistId": 56,
              "discogsUrl": "https://www.discogs.com/release/1234",
              "artworkUrl": "https://img.discogs.com/x.jpg",
              "releaseYear": 1997,
              "fullReleaseDate": "1997-04-14",
              "label": "Warp",
              "genres": ["Electronic"],
              "styles": ["IDM", "Experimental"],
              "artistBio": "Sean Booth and Rob Brown...",
              "artistWikipediaUrl": "https://en.wikipedia.org/wiki/Autechre",
              "spotifyUrl": "https://open.spotify.com/album/abc",
              "appleMusicUrl": "https://music.apple.com/album/abc",
              "youtubeMusicUrl": "https://music.youtube.com/search?q=autechre",
              "bandcampUrl": "https://bandcamp.com/search?q=autechre",
              "soundcloudUrl": "https://soundcloud.com/search?q=autechre",
              "tracklist": [
                { "position": "1", "title": "Acroyear2", "duration": "5:48" },
                { "position": "2", "title": "C/Pach", "duration": null }
              ]
            }
        """.trimIndent()
        val metadata = WxycJson.json.decodeFromString(AlbumMetadata.serializer(), raw)
        assertEquals(1997, metadata.releaseYear)
        assertEquals("Warp", metadata.label)
        assertEquals(listOf("IDM", "Experimental"), metadata.styles)
        assertEquals(2, metadata.tracklist?.size)
        assertEquals("Acroyear2", metadata.tracklist?.first()?.title)
        assertEquals("5:48", metadata.tracklist?.first()?.duration)
        assertNull(metadata.tracklist?.last()?.duration)
        assertEquals("https://open.spotify.com/album/abc", StreamingService.Spotify.urlIn(metadata))
    }

    @Test
    fun `tolerates a response missing most fields`() {
        // Real-world: LML had no Discogs match, so only the search-URL
        // fallbacks for the three free streaming services are present.
        val raw = """
            {
              "youtubeMusicUrl": "https://music.youtube.com/search?q=foo",
              "bandcampUrl": "https://bandcamp.com/search?q=foo",
              "soundcloudUrl": "https://soundcloud.com/search?q=foo"
            }
        """.trimIndent()
        val metadata = WxycJson.json.decodeFromString(AlbumMetadata.serializer(), raw)
        assertNull(metadata.releaseYear)
        assertNull(metadata.tracklist)
        assertNull(metadata.spotifyUrl)
        assertNotNull(metadata.youtubeMusicUrl)
    }
}

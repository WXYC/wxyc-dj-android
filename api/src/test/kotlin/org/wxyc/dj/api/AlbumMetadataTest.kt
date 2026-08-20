package org.wxyc.dj.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Mirrors the `AlbumMetadata` cases of `DTODecodingTests.swift`, plus
 * coverage proportionate to [AlbumInfoTest] / [AlbumSearchResultTest]:
 * `GET /proxy/metadata/album`'s response is the widest of the three DTOs
 * (release year, label, genres, styles, tracklist, five streaming-service
 * URLs, Discogs URL, Wikipedia URL) but had only the two headline decode
 * cases.
 */
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

    @Test
    fun `decodes an entirely empty object as every field null`() {
        // LML had nothing at all for this release — every field, including
        // the three free-service search-URL fallbacks, stays unset. The
        // all-defaults AlbumMetadata() equality check exercises every field
        // at once, not just the handful the other tests happen to name.
        assertEquals(AlbumMetadata(), decode("{}"))
    }

    @Test
    fun `an absent genres list decodes to null, not an empty one`() {
        assertNull(decode("{}").genres)
    }

    @Test
    fun `an explicit empty genres array decodes to an empty list, not null`() {
        assertEquals(emptyList<String>(), decode("""{ "genres": [] }""").genres)
    }

    @Test
    fun `an absent styles list decodes to null, not an empty one`() {
        assertNull(decode("{}").styles)
    }

    @Test
    fun `an explicit empty styles array decodes to an empty list, not null`() {
        assertEquals(emptyList<String>(), decode("""{ "styles": [] }""").styles)
    }

    @Test
    fun `an absent tracklist decodes to null, not an empty one`() {
        assertNull(decode("{}").tracklist)
    }

    @Test
    fun `an explicit empty tracklist array decodes to an empty list, not null`() {
        assertEquals(emptyList<AlbumMetadata.Track>(), decode("""{ "tracklist": [] }""").tracklist)
    }

    @Test
    fun `a Track's id combines position and title`() {
        val track = AlbumMetadata.Track(position = "1", title = "Acroyear2", duration = "5:48")
        assertEquals("1|Acroyear2", track.id)
    }

    @Test
    fun `tolerates unrecognized keys, on the response and on a nested track`() {
        val raw = """
            {
              "label": "Warp",
              "someFutureField": "surprise",
              "tracklist": [
                { "position": "1", "title": "Acroyear2", "extraTrackField": true }
              ]
            }
        """.trimIndent()
        val metadata = decode(raw)
        assertEquals("Warp", metadata.label)
        assertEquals("Acroyear2", metadata.tracklist?.single()?.title)
    }

    @Test
    fun `StreamingService urlIn resolves each service to its own metadata field`() {
        val metadata = AlbumMetadata(
            spotifyUrl = "https://open.spotify.com/album/abc",
            appleMusicUrl = "https://music.apple.com/album/abc",
            youtubeMusicUrl = "https://music.youtube.com/search?q=autechre",
            bandcampUrl = "https://bandcamp.com/search?q=autechre",
            soundcloudUrl = "https://soundcloud.com/search?q=autechre",
        )
        assertEquals(metadata.spotifyUrl, StreamingService.Spotify.urlIn(metadata))
        assertEquals(metadata.appleMusicUrl, StreamingService.AppleMusic.urlIn(metadata))
        assertEquals(metadata.youtubeMusicUrl, StreamingService.YoutubeMusic.urlIn(metadata))
        assertEquals(metadata.bandcampUrl, StreamingService.Bandcamp.urlIn(metadata))
        assertEquals(metadata.soundcloudUrl, StreamingService.Soundcloud.urlIn(metadata))
    }

    @Test
    fun `StreamingService urlIn returns null for a service the metadata has no url for`() {
        // Real-world: LML resolved only a Spotify link for this release.
        val metadata = AlbumMetadata(spotifyUrl = "https://open.spotify.com/album/abc")
        assertNull(StreamingService.AppleMusic.urlIn(metadata))
        assertNull(StreamingService.YoutubeMusic.urlIn(metadata))
        assertNull(StreamingService.Bandcamp.urlIn(metadata))
        assertNull(StreamingService.Soundcloud.urlIn(metadata))
    }

    private fun decode(json: String): AlbumMetadata = WxycJson.json.decodeFromString(AlbumMetadata.serializer(), json)
}

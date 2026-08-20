package org.wxyc.dj.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of `GET /proxy/metadata/album`. Backend-Service composes the
 * response from LML's lookup + release endpoints; every field is optional
 * because LML coverage is partial. All keys are top-level camelCase — matches
 * `proxy.controller.ts`'s serializer, not the snake_case library catalog
 * shape. Mirrors `AlbumMetadata.swift`.
 *
 * Every field still carries an explicit `@SerialName`, even where it matches
 * Kotlin's default camelCase spelling exactly — no field here is exempt from
 * the repo's "no global naming strategy" rule just because it happens to
 * agree with it today.
 */
@Serializable
data class AlbumMetadata(
    @SerialName("discogsReleaseId") val discogsReleaseId: Int? = null,
    @SerialName("discogsArtistId") val discogsArtistId: Int? = null,
    @SerialName("discogsUrl") val discogsUrl: String? = null,
    @SerialName("artworkUrl") val artworkUrl: String? = null,
    @SerialName("releaseYear") val releaseYear: Int? = null,
    @SerialName("fullReleaseDate") val fullReleaseDate: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("genres") val genres: List<String>? = null,
    @SerialName("styles") val styles: List<String>? = null,
    @SerialName("artistBio") val artistBio: String? = null,
    @SerialName("artistWikipediaUrl") val artistWikipediaUrl: String? = null,
    @SerialName("spotifyUrl") val spotifyUrl: String? = null,
    @SerialName("appleMusicUrl") val appleMusicUrl: String? = null,
    @SerialName("youtubeMusicUrl") val youtubeMusicUrl: String? = null,
    @SerialName("bandcampUrl") val bandcampUrl: String? = null,
    @SerialName("soundcloudUrl") val soundcloudUrl: String? = null,
    @SerialName("tracklist") val tracklist: List<Track>? = null,
) {
    @Serializable
    data class Track(
        @SerialName("position") val position: String,
        @SerialName("title") val title: String,
        @SerialName("duration") val duration: String? = null,
    ) {
        val id: String get() = "$position|$title"
    }
}

/**
 * The streaming services [AlbumMetadata] carries a per-service URL for, each
 * a search-URL fallback when LML has no direct match. [urlIn] is the
 * indirection a UI iterates over — one loop building a row per service that
 * has a URL, instead of five near-identical hand-written rows each reading a
 * different `AlbumMetadata` field.
 */
enum class StreamingService(val label: String) {
    Spotify("Spotify"),
    AppleMusic("Apple Music"),
    YoutubeMusic("YouTube Music"),
    Bandcamp("Bandcamp"),
    Soundcloud("SoundCloud"),
    ;

    fun urlIn(metadata: AlbumMetadata): String? = when (this) {
        Spotify -> metadata.spotifyUrl
        AppleMusic -> metadata.appleMusicUrl
        YoutubeMusic -> metadata.youtubeMusicUrl
        Bandcamp -> metadata.bandcampUrl
        Soundcloud -> metadata.soundcloudUrl
    }
}

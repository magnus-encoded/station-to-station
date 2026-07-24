package io.github.magnusencoded.setlist2spotify.data.spotify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String = "",
)

@Serializable
data class SpotifyUser(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class TrackSearchResponse(
    val tracks: TrackPage? = null,
)

@Serializable
data class TrackPage(
    val items: List<SpotifyTrack> = emptyList(),
)

@Serializable
data class SpotifyTrack(
    val id: String,
    val name: String,
    val uri: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
) {
    fun artistNames(): String = artists.joinToString(", ") { it.name }
}

@Serializable
data class SpotifyArtist(
    val name: String = "",
)

@Serializable
data class SpotifyAlbum(
    val name: String? = null,
)

@Serializable
data class PlaylistResponse(
    val id: String,
    @SerialName("external_urls") val externalUrls: Map<String, String> = emptyMap(),
)

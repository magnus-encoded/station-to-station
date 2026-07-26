package io.github.magnusencoded.setlist2spotify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spotify's Feb 2026 API migration renamed POST /playlists/{id}/tracks to
 * /playlists/{id}/items; the old path 403s. This guards against silently
 * reverting the endpoint. (Source-level: the HTTP call hardcodes api.spotify.com,
 * so a behavioural test would require injecting the base URL — not worth it here.)
 */
class SpotifyEndpointGuardTest {

    private val source: String by lazy {
        val rel = "src/main/java/io/github/magnusencoded/setlist2spotify/data/spotify/SpotifyClient.kt"
        // Gradle usually runs tests from the module dir, but don't depend on it.
        val file = listOf(rel, "app/$rel", "android/app/$rel").map(::File).firstOrNull { it.exists() }
            ?: error("SpotifyClient.kt not found from ${File(".").absolutePath}")
        file.readText()
    }

    @Test fun usesItemsEndpointForAddingTracks() {
        assertTrue(
            "addTracks must POST to /playlists/{id}/items",
            source.contains("playlists/\$playlistId/items"),
        )
    }

    @Test fun doesNotUseDeprecatedTracksWriteEndpoint() {
        assertFalse(
            "The deprecated /playlists/{id}/tracks endpoint was reintroduced; use /items",
            source.contains("playlists/\$playlistId/tracks"),
        )
    }
}

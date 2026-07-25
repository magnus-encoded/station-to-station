package io.github.magnusencoded.setlist2spotify.data.spotify

import android.net.Uri
import android.util.Base64
import io.github.magnusencoded.setlist2spotify.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

const val SPOTIFY_REDIRECT_URI = "setlist2spotify://callback"
private const val SPOTIFY_SCOPES =
    "playlist-modify-public playlist-modify-private user-read-private ugc-image-upload"

class SpotifyClient(private val settings: SettingsRepository) {

    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // --- OAuth (Authorization Code with PKCE) ---

    suspend fun buildAuthorizationUri(): Uri {
        val clientId = settings.spotifyClientIdValue()
            ?: throw IOException("Spotify Client ID is not configured. Set it in Settings.")
        val verifier = generateCodeVerifier()
        settings.savePkceVerifier(verifier)
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SPOTIFY_REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge(verifier))
            .appendQueryParameter("scope", SPOTIFY_SCOPES)
            // Always show the consent screen so a stale earlier grant without
            // playlist scopes can't be silently reused.
            .appendQueryParameter("show_dialog", "true")
            .build()
    }

    suspend fun exchangeCodeForTokens(code: String) {
        val clientId = settings.spotifyClientIdValue()
            ?: throw IOException("Spotify Client ID is not configured.")
        val verifier = settings.pkceVerifier()
            ?: throw IOException("Login session expired. Start the Spotify login again.")
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", SPOTIFY_REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        val token = requestToken(body)
        settings.saveTokens(token.accessToken, token.refreshToken, token.expiresIn, token.scope)
    }

    private suspend fun refreshAccessToken(): String {
        val clientId = settings.spotifyClientIdValue()
            ?: throw IOException("Spotify Client ID is not configured.")
        val refreshToken = settings.refreshTokenValue()
            ?: throw IOException("Not connected to Spotify. Connect in Settings.")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        val token = requestToken(body)
        settings.saveTokens(token.accessToken, token.refreshToken, token.expiresIn, token.scope)
        return token.accessToken
    }

    /** Null when unknown (logins predating scope persistence). */
    suspend fun hasPlaylistScopes(): Boolean? =
        settings.grantedScope()?.contains("playlist-modify")

    /**
     * Cover upload needs a scope the app did not always ask for, so a login
     * made before covers existed can create playlists but not illustrate them.
     */
    suspend fun hasImageUploadScope(): Boolean =
        settings.grantedScope()?.contains("ugc-image-upload") == true

    private suspend fun requestToken(body: FormBody): TokenResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IOException("Spotify token request failed (${resp.code}): $text")
            }
            json.decodeFromString<TokenResponse>(text)
        }
    }

    private suspend fun accessToken(): String =
        settings.validAccessToken() ?: refreshAccessToken()

    suspend fun isConnected(): Boolean = settings.refreshTokenValue() != null

    // --- Web API ---

    private suspend fun call(request: Request.Builder): String {
        val token = accessToken()
        val req = request.header("Authorization", "Bearer $token").build()
        return withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    if (resp.code == 401) {
                        throw IOException("Spotify session expired. Reconnect in Settings.")
                    }
                    if (resp.code == 403) {
                        // Spotify's bare "Forbidden" body says nothing; the headers
                        // distinguish a scope refusal from an edge/CDN block.
                        val detail = listOf("www-authenticate", "server", "x-robots-tag")
                            .mapNotNull { name -> resp.header(name)?.let { "$name: $it" } }
                            .joinToString("; ")
                        throw SpotifyForbiddenException(
                            "Spotify refused ${req.method} ${req.url.encodedPath} (403). " +
                                "$text${if (detail.isNotEmpty()) " [$detail]" else ""}"
                        )
                    }
                    throw IOException(
                        "Spotify API error ${resp.code} on ${req.method} ${req.url.encodedPath}: $text"
                    )
                }
                text
            }
        }
    }

    suspend fun searchTracks(query: String, limit: Int = 5): List<SpotifyTrack> {
        val url = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("type", "track")
            .addQueryParameter("limit", limit.toString())
            .build()
        val text = call(Request.Builder().url(url))
        return json.decodeFromString<TrackSearchResponse>(text).tracks?.items.orEmpty()
    }

    suspend fun createPlaylist(name: String, description: String, isPublic: Boolean): PlaylistResponse {
        val payload = buildJsonObject {
            put("name", name)
            put("description", description)
            put("public", isPublic)
        }
        // /me/playlists avoids the user-id round trip and the 403s the
        // /users/{id}/playlists endpoint gives on any id mismatch.
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/me/playlists")
            .post(payload.toString().toRequestBody(jsonMediaType))
        return json.decodeFromString(call(request))
    }

    suspend fun currentUser(): SpotifyUser =
        json.decodeFromString(call(Request.Builder().url("https://api.spotify.com/v1/me")))

    /** Reads a playlist by id — used to harvest the creator's setlist.fm stamp from a shared link. */
    suspend fun getPlaylist(playlistId: String): SimplePlaylist {
        val url = "https://api.spotify.com/v1/playlists/$playlistId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id,name,description,owner(id,display_name)")
            .build()
        return json.decodeFromString(call(Request.Builder().url(url)))
    }

    private fun urisBody(uris: List<String>) =
        buildJsonObject { putJsonArray("uris") { uris.forEach { add(it) } } }
            .toString()
            .toRequestBody(jsonMediaType)

    /**
     * Sets the playlist cover. Spotify takes the JPEG base64-encoded as the raw
     * body under an image/jpeg content type — not multipart, and not wrapped in
     * JSON — and answers 202 with nothing in the body.
     */
    suspend fun uploadCover(playlistId: String, jpeg: ByteArray) {
        val body = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            .toRequestBody("image/jpeg".toMediaType())
        call(
            Request.Builder()
                .url("https://api.spotify.com/v1/playlists/$playlistId/images")
                .put(body)
        )
    }

    /** Facts that explain a residual 403 while modifying a playlist we just made. */
    private suspend fun diagnostics(playlistId: String): String = try {
        val me = currentUser()
        val playlist = call(
            Request.Builder()
                .url("https://api.spotify.com/v1/playlists/$playlistId?fields=owner(id),public,collaborative")
        )
        "me=${me.id} product=${me.product} playlist=$playlist scopes=${settings.grantedScope()}"
    } catch (e: Exception) {
        "diagnostics unavailable: ${e.message}"
    }

    /**
     * Fills a freshly created playlist via POST /playlists/{id}/items. The old
     * /tracks path was renamed in Spotify's Feb 2026 API migration and now 403s
     * from the edge (envoy, no www-authenticate); /items takes the same body.
     */
    suspend fun addTracks(playlistId: String, uris: List<String>): AddTracksResult {
        val clean = uris.filter { it.startsWith("spotify:track:") }.distinct()
        if (clean.isEmpty()) throw IOException("No valid Spotify track URIs to add.")
        val url = "https://api.spotify.com/v1/playlists/$playlistId/items"
        try {
            clean.chunked(100).forEach { call(Request.Builder().url(url).post(urisBody(it))) }
        } catch (e: SpotifyForbiddenException) {
            throw SpotifyForbiddenException("${e.message} | ${diagnostics(playlistId)}")
        }
        return AddTracksResult(clean.size, emptyList())
    }
}

data class AddTracksResult(val added: Int, val refused: List<String>)

class SpotifyForbiddenException(message: String) : IOException(message)

private fun generateCodeVerifier(): String {
    val bytes = ByteArray(64)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private fun codeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

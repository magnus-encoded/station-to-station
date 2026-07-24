package io.github.magnusencoded.setlist2spotify.data.spotify

import android.net.Uri
import android.util.Base64
import io.github.magnusencoded.setlist2spotify.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
private const val SPOTIFY_SCOPES = "playlist-modify-public playlist-modify-private"

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
        settings.saveTokens(token.accessToken, token.refreshToken, token.expiresIn)
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
        settings.saveTokens(token.accessToken, token.refreshToken, token.expiresIn)
        return token.accessToken
    }

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
                    throw IOException("Spotify API error ${resp.code}: $text")
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

    suspend fun currentUser(): SpotifyUser =
        json.decodeFromString(call(Request.Builder().url("https://api.spotify.com/v1/me")))

    suspend fun createPlaylist(userId: String, name: String, description: String): PlaylistResponse {
        val payload = buildJsonObject {
            put("name", name)
            put("description", description)
            put("public", false)
        }
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/users/$userId/playlists")
            .post(payload.toString().toRequestBody(jsonMediaType))
        return json.decodeFromString(call(request))
    }

    suspend fun addTracks(playlistId: String, uris: List<String>) {
        for (chunk in uris.chunked(100)) {
            val payload = buildJsonObject {
                putJsonArray("uris") { chunk.forEach(::add) }
            }
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/playlists/$playlistId/tracks")
                .post(payload.toString().toRequestBody(jsonMediaType))
            call(request)
        }
    }
}

private fun generateCodeVerifier(): String {
    val bytes = ByteArray(64)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private fun codeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

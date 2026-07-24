package io.github.magnusencoded.setlist2spotify.data.setlistfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class SetlistFmClient(private val apiKeyProvider: suspend () -> String?) {

    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    // Mirrors the retry behaviour of the Python CLI: retry on 429/5xx with
    // exponential backoff, fail fast on other HTTP errors.
    private suspend fun get(path: String, params: Map<String, String?>): String {
        val apiKey = apiKeyProvider()
            ?: throw IOException("setlist.fm API key is not configured. Set it in Settings.")
        val urlBuilder = "https://api.setlist.fm/rest/1.0/$path".toHttpUrl().newBuilder()
        for ((k, v) in params) {
            if (v != null) urlBuilder.addQueryParameter(k, v)
        }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .build()

        var backoffMs = 1000L
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            val result = withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    when {
                        resp.isSuccessful -> resp.body?.string() ?: ""
                        resp.code == 429 || resp.code >= 500 -> null
                        resp.code == 404 -> throw IOException("Not found (404). Check the name/ID and try again.")
                        resp.code == 403 -> throw IOException("setlist.fm rejected the API key (403).")
                        else -> throw IOException("setlist.fm error ${resp.code}")
                    }
                }
            }
            if (result != null) return result
            if (attempt == maxAttempts) break
            delay(backoffMs)
            backoffMs *= 2
        }
        throw IOException("setlist.fm is rate limiting or unavailable. Try again in a moment.")
    }

    suspend fun searchArtists(name: String, page: Int = 1): ArtistSearchResponse =
        json.decodeFromString(
            get("search/artists", mapOf("artistName" to name, "p" to page.toString(), "sort" to "relevance"))
        )

    suspend fun artistSetlists(mbid: String, page: Int = 1): SetlistsResponse =
        json.decodeFromString(get("artist/$mbid/setlists", mapOf("p" to page.toString())))

    suspend fun userAttended(userId: String, page: Int = 1): SetlistsResponse =
        json.decodeFromString(get("user/$userId/attended", mapOf("p" to page.toString())))
}

package io.github.magnusencoded.stationtostation.data.setlistfm

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

    /** One setlist, fresh — for when it was just edited on setlist.fm. */
    suspend fun setlist(setlistId: String): FmSetlist =
        json.decodeFromString(get("setlist/$setlistId", emptyMap()))

    suspend fun userAttended(userId: String, page: Int = 1): SetlistsResponse =
        json.decodeFromString(get("user/$userId/attended", mapOf("p" to page.toString())))

    /**
     * The festival a setlist belongs to, e.g. "Øyafestivalen 2025" for a show whose
     * venue is only "Tøyenparken".
     *
     * setlist.fm models festivals as a first-class entity but does not expose them in
     * the REST API — the name lives only on the setlist's own web page, which links to
     * `/festival/<year>/<slug>.html`. MusicBrainz has festival events too, and needs no
     * key, but its coverage is patchy (Tons of Rock 2026 is there, Øyafestivalen 2025
     * is not), so it can't be the primary source.
     *
     * Returns null on anything unexpected — the caller falls back to the venue name.
     */
    suspend fun festivalName(setlistUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(setlistUrl).header("Accept", "text/html").build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                parseFestivalName(resp.body?.string().orEmpty())
            }
        }.getOrNull()
    }
}

/** The "played at a festival" link on a setlist page: title="View &lt;name&gt; details". */
private val FESTIVAL_LINK = Regex("""href="[^"]*?/festival/\d{4}/[^"]+"\s+title="View (.+?) detail""")

internal fun parseFestivalName(html: String): String? =
    FESTIVAL_LINK.find(html)?.groupValues?.get(1)?.trim()?.takeUnless { it.isEmpty() }

/**
 * The id at the end of a setlist page's url. Only `/setlist/` and `/upcoming/` count:
 * an artist page (`/setlists/…-23d6a877.html`) and a venue page (`/venue/…-63d41af7.html`)
 * end in exactly the same shape, and taking their id would fetch a gig that isn't the
 * one in front of the user — a wrong show is worse than "that link isn't a gig".
 */
private val SETLIST_ID_IN_URL =
    Regex("""setlist\.fm/(?:setlist|upcoming)/\S*?-([0-9a-f]{5,10})\.html""")

private val BARE_ID = Regex("""[0-9a-f]{5,10}""")

/**
 * A gig id from whatever the user pasted — the setlist.fm page url, or the bare id.
 *
 * This is how a gig that hasn't happened gets into the app: the API's search index
 * stops about a day out, so a show weeks away is only reachable by id, and the id is
 * in the url of the page the user was just on. Null if there is no id in there.
 */
fun parseSetlistId(input: String): String? {
    val s = input.trim()
    SETLIST_ID_IN_URL.find(s)?.let { return it.groupValues[1] }
    return s.takeIf { BARE_ID.matches(it) }
}

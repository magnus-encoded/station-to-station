package io.github.magnusencoded.stationtostation.data.musicbrainz

import io.github.magnusencoded.stationtostation.data.sameSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * An artist's own songs, from MusicBrainz.
 *
 * **Why a second source at all.** The **Log** correction panel used to rank against the
 * pool an **Act** already had, built from setlist.fm's recent setlists. On the device
 * that pool is empty for 8 of the 15 acts on one festival **Bill** — including Øyvind
 * Holm, the case the whole feature was written for. setlist.fm knows who these artists
 * are and has no setlists holding their songs, which is the ordinary situation for a
 * small act. MusicBrainz holds 55 recordings for that same artist.
 *
 * **Why it needs no name matching.** A **Bill**'s **Act** already stores an `mbid`,
 * resolved when its pool was fetched, and setlist.fm's artist ids *are* MusicBrainz ids.
 * So this asks with an identity rather than a name, and introduces no new ambiguity of
 * its own. It inherits whatever the earlier match got wrong — a wrong artist gives a
 * wrong catalogue — which is why the panel names the artist the pool came from, and why
 * "not them? find who plays…" already exists as the way out.
 *
 * **Why the answer may be cached forever.** MusicBrainz is CC0. Under ADR-0005 a source
 * that can require deletion or expiry of cached data is disqualified for anything
 * entering the permanent record; this one has no such clause, so the catalogue is kept
 * on the device indefinitely. That matters here more than it sounds: correction is
 * wanted in a field with no signal, which is exactly where the gig was.
 */
class MusicBrainzClient {

    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every song title MusicBrainz has for [mbid], de-duplicated.
     *
     * Paged, because an artist with a long career exceeds one page and a truncated
     * catalogue silently lacks the one title someone is looking for. Capped, because a
     * catalogue is a prompt and nobody scrolls two thousand rows — and the free-text
     * field is always there for what a cap leaves out.
     */
    suspend fun catalogue(mbid: String, cap: Int = MAX_TITLES): List<String> {
        if (mbid.isBlank()) return emptyList()
        val titles = mutableListOf<String>()
        var offset = 0
        while (titles.size < cap) {
            val page = recordings(mbid, offset)
            if (page.recordings.isEmpty()) break
            titles += page.recordings.map { it.title }
            offset += page.recordings.size
            if (offset >= page.count) break
            // MusicBrainz asks for no more than one request a second, and asking nicely
            // is the whole price of a source with no cache clause.
            delay(1_000)
        }
        return dedupe(titles).take(cap)
    }

    /**
     * Artists whose name looks like [query], best match first.
     *
     * The one place this app asks MusicBrainz by *name* rather than by identity, and it
     * is deliberately not a resolution step: it offers spellings to a person who then
     * picks one. Nothing is keyed on the **mbid** it returns — a planned gig typed in by
     * hand is a local **Gig** either way — so a wrong pick here costs a wrong name and
     * not a wrong join.
     *
     * The disambiguation comment comes back with the name because MusicBrainz has four
     * artists called Nirvana and a list of four identical rows is worse than no list.
     */
    suspend fun searchArtists(query: String, limit: Int = ARTIST_LIMIT): List<MbArtist> {
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        val url = "https://musicbrainz.org/ws/2/artist".toHttpUrl().newBuilder()
            .addQueryParameter("query", q)
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = get(url.toString())
        return parseArtists(body, json).take(limit)
    }

    private suspend fun recordings(mbid: String, offset: Int): RecordingsPage {
        val url = "https://musicbrainz.org/ws/2/recording".toHttpUrl().newBuilder()
            .addQueryParameter("artist", mbid)
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", PAGE.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        return parseRecordings(get(url.toString()), json)
    }

    /**
     * One place the User-Agent is set. MusicBrainz blocks a blank or generic one, so a
     * second call site that forgot it would fail in a way that looks like the query
     * being wrong.
     */
    private suspend fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            // Required by MusicBrainz. Naming the application and a way to reach us is
            // the deal for an open database.
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("MusicBrainz says ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }
    }

    companion object {
        private const val PAGE = 100
        internal const val MAX_TITLES = 400
        /** A prompt, not a search result page: eight rows is what fits above a keyboard. */
        internal const val ARTIST_LIMIT = 8
        /** Below this a query matches most of the database and the list is noise. */
        internal const val MIN_QUERY = 2
    }
}

@Serializable
internal data class RecordingsPage(
    @SerialName("recording-count") val count: Int = 0,
    val recordings: List<Recording> = emptyList(),
)

@Serializable
internal data class Recording(val title: String = "")

/**
 * One artist MusicBrainz offered for a typed name.
 *
 * [disambiguation] is MusicBrainz's own one-line note — "US grunge band", "Norwegian rock
 * band" — and is empty for most artists. It is carried because without it the four
 * artists called Nirvana are four identical rows.
 */
data class MbArtist(val name: String, val mbid: String, val disambiguation: String = "")

@Serializable
private data class ArtistsPage(val artists: List<ArtistHit> = emptyList())

@Serializable
private data class ArtistHit(
    val id: String = "",
    val name: String = "",
    val score: Int = 0,
    val disambiguation: String = "",
)

/**
 * Parsing kept apart from fetching, as with [parseRecordings].
 *
 * Sorted by MusicBrainz's own score rather than trusting document order, and nameless or
 * idless hits are dropped — a row a person cannot read is not a suggestion, and one with
 * no id cannot be followed up later.
 */
internal fun parseArtists(body: String, json: Json = Json { ignoreUnknownKeys = true }): List<MbArtist> =
    runCatching { json.decodeFromString<ArtistsPage>(body) }.getOrDefault(ArtistsPage())
        .artists
        .filter { it.name.isNotBlank() && it.id.isNotBlank() }
        .sortedByDescending { it.score }
        .map { MbArtist(it.name, it.id, it.disambiguation) }

/** Parsing kept apart from fetching, so the shape of a reply is asserted without a socket. */
internal fun parseRecordings(body: String, json: Json = Json { ignoreUnknownKeys = true }): RecordingsPage =
    runCatching { json.decodeFromString<RecordingsPage>(body) }.getOrDefault(RecordingsPage())

/**
 * One entry per song, not per recording.
 *
 * MusicBrainz lists every recording an artist has: a studio take, a live take, a remaster
 * and a radio edit are four rows and one song. Presented raw, the panel would offer the
 * same title four times and bury the rest. Compared with [sameSong], which is the
 * normalisation recognition already uses everywhere, so "Don't" and "Dont" are one row
 * here exactly as they are one song there. The first spelling wins, because it is a
 * title and titles are kept as they are written.
 */
internal fun dedupe(titles: List<String>): List<String> {
    val out = mutableListOf<String>()
    for (t in titles) {
        if (t.isBlank()) continue
        if (out.none { sameSong(it, t) }) out += t
    }
    return out
}

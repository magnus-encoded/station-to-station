package io.github.magnusencoded.stationtostation.data.setlistfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class SetlistFmClient(private val apiKeyProvider: suspend () -> String?) {

    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    // Retry 5xx with exponential backoff, fail fast on everything else — 429
    // included. A 429 is not transient trouble the way a 502 is: it says the
    // quota is already spent, and the next two attempts spend two more units of
    // it to arrive at the same answer. With every tester on one bundled key,
    // that turns a queue into a stampede.
    //
    // ponytail: one bundled key, 1440 requests/day, shared by every tester —
    // that ceiling is the real problem and not retrying merely stops us making
    // it worse. The fix is a key per user (Settings already takes one) or a
    // proxy holding our key and rationing per install.
    private suspend fun get(
        path: String,
        params: Map<String, String?>,
        // setlist.fm returns 404, not an empty 200, for a real user whose attended
        // list has zero shows — a brand-new account looks identical to a typo'd
        // username unless this call site is told to read that 404 as "no shows"
        // rather than "no such user".
        notFoundIsEmpty: Boolean = false,
    ): String {
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
                        resp.code >= 500 -> null
                        resp.code == 429 -> throw IOException(
                            "setlist.fm's request limit for today has been reached. " +
                                "Nothing is wrong — try again later."
                        )
                        resp.code == 404 && notFoundIsEmpty -> """{"total":0}"""
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
        throw IOException("setlist.fm is unavailable. Try again in a moment.")
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
        json.decodeFromString(
            get("user/$userId/attended", mapOf("p" to page.toString()), notFoundIsEmpty = true)
        )

    /**
     * The **Festival** a setlist belongs to — the identity, the name, the range, which
     * acts played which day, and when each of them went on. Null when that night
     * belongs to no festival, which is the common and correct answer.
     *
     * setlist.fm models festivals as a first-class entity but does not expose them in
     * the REST API. The setlist's own web page links to `/festival/<year>/<slug>.html`,
     * and that page carries the rest. MusicBrainz has festival events too, and needs no
     * key, but its coverage is patchy (Tons of Rock 2026 is there, Øyafestivalen 2025
     * is not), so it can't be the primary source.
     *
     * **Two pages, one per festival, paid once.** #166 assumed the setlist page carried
     * all four facts; it does not — it carries the identity and the name, and the range,
     * the day grouping and the set times live on the festival page it links to. The
     * volume is unchanged in the way that matters: a night is asked about once ever, and
     * the second fetch only happens for a night that turned out to be at a festival at
     * all.
     *
     * Everything degrades independently, per ADR-0004: a festival page that cannot be
     * read at all still yields the identity and the name off the setlist page, and a
     * field the parse does not recognise is null rather than a guess. Nothing about a
     * **Festival** is required for a **Gig** to render.
     */
    suspend fun festivalAt(setlistUrl: String): ScrapedFestival? = withContext(Dispatchers.IO) {
        val link = html(setlistUrl)?.let(::parseFestivalLink) ?: return@withContext null
        val page = link.href
            ?.let { runCatching { URI(setlistUrl).resolve(it).toString() }.getOrNull() }
            ?.let { html(it) }
            ?.let(::parseFestivalPage)
        // The name off the setlist page is the one we came for; the festival page's own
        // <h1> is a second opinion on it, never a replacement for the identity.
        link.copy(
            rangeFrom = page?.rangeFrom,
            rangeTo = page?.rangeTo,
            dayMembership = page?.dayMembership,
            setTimes = page?.setTimes,
        )
    }

    /** One page, as text. Null on anything at all going wrong — see [festivalAt]. */
    private fun html(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("Accept", "text/html").build()
        http.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }.getOrNull()
}

/**
 * A **Festival** as setlist.fm's pages give it up, every field independently nullable.
 *
 * Not a `StoredFestival`: this is what was *read*, and turning it into an identity the
 * app owns — minting the local id, deciding it is scraped rather than authored — is the
 * logic layer's job and not the scraper's.
 */
data class ScrapedFestival(
    val name: String? = null,
    /** e.g. `tons-of-rock-2026-6bd52ece`, out of the href — the vendor's own key. */
    val slug: String? = null,
    /** Where the festival page is, relative to the setlist page. */
    val href: String? = null,
    /** dd-MM-yyyy, the shape setlist.fm sends everywhere else in this app. */
    val rangeFrom: String? = null,
    val rangeTo: String? = null,
    /** dd-MM-yyyy to the setlist.fm ids the source says played that day. */
    val dayMembership: Map<String, List<String>>? = null,
    /** Setlist.fm id to `HH:mm`, for the acts whose start time was published. */
    val setTimes: Map<String, String>? = null,
)

/** The "played at a festival" link on a setlist page: title="View &lt;name&gt; details". */
private val FESTIVAL_LINK =
    Regex("""href="([^"]*?/festival/\d{4}/([^"/]+)\.html)"\s+title="View (.+?) detail""")

/**
 * The identity and the name, off the setlist page. Null when the page carries no
 * festival link at all, which is what "this was not a festival" looks like.
 */
internal fun parseFestivalLink(html: String): ScrapedFestival? {
    val m = FESTIVAL_LINK.find(html) ?: return null
    return ScrapedFestival(
        name = m.groupValues[3].trim().takeUnless { it.isEmpty() },
        slug = m.groupValues[2].takeUnless { it.isEmpty() },
        href = m.groupValues[1].takeUnless { it.isEmpty() },
    )
}

/** Kept for the one fact the label used to be: see [parseFestivalLink]. */
internal fun parseFestivalName(html: String): String? = parseFestivalLink(html)?.name

// --- The festival page ------------------------------------------------------------
//
// Three facts and three shapes, each read on its own so that a redesign upstream costs
// the field it touched and never the night. Verified against the real page on
// 2026-08-25.

/** `<div class="condensed dateBlock dtstart">…<span class="value-title" title="2026-06-24">`. */
private val DTSTART = Regex("""dateBlock dtstart.{0,400}?value-title" title="(\d{4}-\d{2}-\d{2})"""", RegexOption.DOT_MATCHES_ALL)

/** The human range beside it: `<span>Wed June 24, 2026 - Sat June 27, 2026</span>`. */
private val RANGE = Regex("""<span>\w{3} (\w+ \d{1,2}, \d{4}) - \w{3} (\w+ \d{1,2}, \d{4})</span>""")

/** `<p class="…GroupedVenueDayBySubVenue-eventDate …">Wednesday, June 24, 2026</p>`. */
private const val DAY_HEADING = "GroupedVenueDayBySubVenue-eventDate"
private val DAY_DATE = Regex("""^[^>]*>([^<]+)<""")

/** One act's row on the day's list, with the scheduled start where there is one. */
private const val DAY_ITEM = "FestivalSetlistListItem-root"
private val ITEM_TIME = Regex("""scheduledStart.{0,600}?<p[^>]*>([^<]+)</p>""", RegexOption.DOT_MATCHES_ALL)
private val ITEM_SETLIST = Regex("""/setlist/[^"]*?-([0-9a-f]{5,10})\.html""")

private val PAGE_DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)
private val PAGE_RANGE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
private val FM_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH)
private val PAGE_TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/**
 * The range, the day grouping and the set times off a festival page. Every field is
 * whatever could be read and null otherwise — a page shaped wrong yields nothing rather
 * than nonsense, and one unreadable field never takes the others with it.
 */
internal fun parseFestivalPage(html: String): ScrapedFestival {
    val range = RANGE.find(html)
    val days = mutableMapOf<String, List<String>>()
    val times = mutableMapOf<String, String>()
    for (chunk in html.split(DAY_HEADING).drop(1)) {
        val date = DAY_DATE.find(chunk)?.groupValues?.get(1)?.let { fmDate(it, PAGE_DAY) }
        val ids = mutableListOf<String>()
        for (item in chunk.split(DAY_ITEM).drop(1)) {
            val id = ITEM_SETLIST.find(item)?.groupValues?.get(1) ?: continue
            ids += id
            ITEM_TIME.find(item)?.groupValues?.get(1)?.let(::pageTime)?.let { times[id] = it }
        }
        if (date != null && ids.isNotEmpty()) days[date] = ids
    }
    return ScrapedFestival(
        rangeFrom = DTSTART.find(html)?.groupValues?.get(1)?.let { fmDate(it, DateTimeFormatter.ISO_LOCAL_DATE) }
            ?: range?.groupValues?.get(1)?.let { fmDate(it, PAGE_RANGE) },
        rangeTo = range?.groupValues?.get(2)?.let { fmDate(it, PAGE_RANGE) },
        dayMembership = days.ifEmpty { null },
        setTimes = times.ifEmpty { null },
    )
}

/** A date the page wrote its way, in the one shape this app stores dates in. */
private fun fmDate(text: String, format: DateTimeFormatter): String? =
    runCatching { LocalDate.parse(text.trim(), format).format(FM_DATE) }.getOrNull()

/** "2:00 pm" as "14:00", so the latest set time is also the largest string. */
private fun pageTime(text: String): String? =
    runCatching { LocalTime.parse(text.trim().uppercase(Locale.ENGLISH), PAGE_TIME).toString() }.getOrNull()

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

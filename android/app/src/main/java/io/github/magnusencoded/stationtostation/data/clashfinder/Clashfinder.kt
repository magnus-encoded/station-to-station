package io.github.magnusencoded.stationtostation.data.clashfinder

import android.webkit.CookieManager
import io.github.magnusencoded.stationtostation.data.ProgrammeAct
import io.github.magnusencoded.stationtostation.data.StoredProgramme
import io.github.magnusencoded.stationtostation.data.USER_AGENT
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.ui.NIGHT_ENDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Clashfinder: the programme source, and the only one.
 *
 * A clashfinder is a festival timetable somebody typed in — stage, act, start *and*
 * end, for ten thousand festivals rather than the one whose markup we happened to
 * parse. It replaced Øya's own page outright: clashfinder carries Øyafestivalen every
 * year, its 2026 entry holds 139 acts against the scraper's 83, and it publishes the
 * end times the scraper had to guess at.
 *
 * **The account is the user's.** Every request carries `authUsername` and
 * `authPublicKey`, and there is deliberately no bundled fallback the way setlist.fm
 * has one: a single credential shared by every install concentrates all of this app's
 * traffic on one account against a host that runs active bot protection. The cost is
 * accepted and stated plainly on the screen — with no account there is no programme
 * feature at all, so the empty state has to say what to get and where.
 *
 * **It suggests, it never decides.** Half of all clashfinders are editable by anyone,
 * the median one is last edited the day before its own festival, and 36% are still
 * being edited after it has begun. These are the *scheduled* times, not the played
 * ones. Nothing here writes a **Gig**: a person reads this and adds **Acts** to a
 * **Bill**, exactly as they would off a poster.
 *
 * **Fantasy line-ups exist and are structurally identical to real ones** — plausible
 * names, normal act counts, no flag saying so. That is why a person picks the festival
 * and nothing is ever auto-selected by name.
 *
 * Data is CC BY-NC 3.0 — perpetual and irrevocable, which is what lets it near a record
 * we promise to keep (ADR-0005). Attribution is a condition of that licence, so the
 * `copyright` line the payload carries is stored with the acts and shown with them.
 */
private const val ORIGIN = "https://clashfinder.com"
private const val HOST = "$ORIGIN/data"

/**
 * The full index, not the curated one.
 *
 * Clashfinder publishes `events/events.json`, about 1,100 hand-curated entries, and
 * this, about 10,500. Øyafestivalen is **not** a core clashfinder, so the curated index
 * would have silently omitted the one festival this feature was built for.
 */
private const val INDEX_PATH = "events/all.json"

/** One account's credentials, as every request carries them. */
data class ClashfinderAuth(val user: String, val publicKey: String)

/**
 * `authPublicKey`: sha256 of username and private key concatenated, no separator.
 *
 * Read off the key generator on the API page rather than its prose, which gives the
 * ingredients and not the order — and a wrong order is a 401 indistinguishable from a
 * mistyped key. That is what the one test vector here is for.
 *
 * The digest is static per account, so it is computed when the credential is saved and
 * not per request.
 */
fun clashfinderPublicKey(user: String, privateKey: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest((user.trim() + privateKey.trim()).toByteArray())
        .joinToString("") { "%02x".format(it) }

/**
 * One line of the index, reduced to what the picker and the matcher actually use.
 *
 * The full index is roughly 4 MB and carries the owner, the edit history, the ACL and
 * more besides. Parsing that every time the picker opens is not acceptable on a phone,
 * so it is reduced once on ingest and only this is cached.
 */
@Serializable
data class ClashfinderFestival(
    /** The identifier a document is fetched by — `oyafestivalen2026`. */
    val id: String = "",
    /** What a person reads: "Øyafestivalen 2026". */
    val name: String = "",
    /** ISO yyyy-MM-dd. The index gives it as a UTC-midnight epoch second. */
    val start: String = "",
    val days: Int = 1,
    val acts: Int = 0,
    val stages: Int = 0,
    /**
     * How many times it has been edited — the best available signal for "somebody is
     * actually keeping this one", and the tie-break between two entries for one
     * festival.
     */
    val edits: Int = 0,
    /** Clashfinder's own curation flag. Rare, and worth ranking on where it is there. */
    val core: Boolean = false,
) {
    fun startsOn(): LocalDate? = runCatching { LocalDate.parse(start) }.getOrNull()

    /**
     * How far this festival is from [day], in days, counting its **whole run**.
     *
     * A festival in progress on [day] is distance zero. Matching on the start alone
     * would miss somebody who is standing at day two of a three-day festival, which is
     * exactly when they are most likely to be looking.
     */
    fun distanceFrom(day: LocalDate): Long {
        val from = startsOn() ?: return Long.MAX_VALUE
        val to = from.plusDays((days.coerceAtLeast(1) - 1).toLong())
        return when {
            day < from -> from.toEpochDay() - day.toEpochDay()
            day > to -> day.toEpochDay() - to.toEpochDay()
            else -> 0L
        }
    }
}

// The index as it arrives: an object keyed by identifier. `name` is the identifier
// again and `desc` is the readable name, which is the opposite way round to how it
// reads. Verified against the live index on 2026-08-31.
@Serializable
private data class IndexEntry(
    val desc: String = "",
    val lastEdit: String = "",
    val edits: Int = 0,
    val numDays: Int = 1,
    val numActs: Int = 0,
    val numStages: Int = 0,
    /** Unix seconds, UTC midnight of the first day. */
    val startDate: Long = 0,
    @SerialName("private") val isPrivate: Boolean = false,
    val coreClashfinder: Boolean = false,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * The index into candidates. Pure: hand it the text, however you got it.
 *
 * An entry with no date is dropped — the picker's whole order is nearness to today, and
 * an undated row could only ever sort last. A private one is dropped too: it is a row
 * that cannot be opened, and offering it is worse than not listing it.
 */
fun parseClashfinderIndex(text: String): List<ClashfinderFestival> {
    val raw = runCatching {
        json.decodeFromString<Map<String, IndexEntry>>(text)
    }.getOrNull() ?: return emptyList()
    return raw.mapNotNull { (id, e) ->
        if (id.isBlank() || e.isPrivate || e.startDate <= 0) return@mapNotNull null
        ClashfinderFestival(
            id = id,
            name = e.desc.ifBlank { id },
            start = Instant.ofEpochSecond(e.startDate).atZone(ZoneOffset.UTC).toLocalDate().toString(),
            days = e.numDays.coerceAtLeast(1),
            acts = e.numActs,
            stages = e.numStages,
            edits = e.edits,
            core = e.coreClashfinder,
        )
    }
}

/** The reduced index, as cached. */
fun encodeFestivals(festivals: List<ClashfinderFestival>): String =
    json.encodeToString(ListSerializer(ClashfinderFestival.serializer()), festivals)

fun decodeFestivals(text: String): List<ClashfinderFestival> = runCatching {
    json.decodeFromString(ListSerializer(ClashfinderFestival.serializer()), text)
}.getOrDefault(emptyList())

/**
 * The candidates for [query], nearest [on] first.
 *
 * **Ordered by nearness, never filtered to the future.** A future-only filter is both
 * more code and less useful: it would exclude Øyafestivalen 2026, which has already
 * happened, and it would shut out anyone recording a festival they went to. Only about
 * 1% of the corpus is future-dated, so sorting by distance puts the hundred-odd
 * upcoming ones at the top by itself and leaves everything else reachable.
 *
 * Where two entries are equally near — and duplicates are common, two spellings of one
 * festival or a full timetable beside a one-act stub — the more complete one comes
 * first: curated, then most edited, then most acts. Both are still listed. Two
 * genuinely competing entries is a choice for the person, not a guess for the app.
 */
fun rankFestivals(
    festivals: List<ClashfinderFestival>,
    on: LocalDate,
    query: String = "",
): List<ClashfinderFestival> {
    val needle = foldName(query)
    // Distance is computed once per festival rather than inside the comparator: it
    // parses a date, and a comparator sees each row a dozen times over ten thousand of
    // them, on the main thread, on every keystroke.
    return festivals
        .filter { needle.isEmpty() || needle in foldName(it.name) || needle in foldName(it.id) }
        .map { it to it.distanceFrom(on) }
        .sortedWith(
            compareBy<Pair<ClashfinderFestival, Long>> { it.second }
                .thenByDescending { it.first.core }
                .thenByDescending { it.first.edits }
                .thenByDescending { it.first.acts }
                .thenBy { it.first.name },
        )
        .map { it.first }
}

/**
 * A name reduced to what two spellings of it have in common.
 *
 * Diacritics off, case off, punctuation and spacing off — so "oya" finds
 * "Øyafestivalen" from a keyboard that has no Ø on it, which is most keyboards and
 * every hurry. The Nordic letters are listed out because they do **not** decompose:
 * NFD splits å into a + ring but leaves ø and æ exactly as they were, so normalisation
 * alone would fail the one case this feature is named after.
 *
 * The same fold decides whether an artist name matches, so a festival search and an
 * artist match agree about what "the same name" means.
 */
fun foldName(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .map { FOLDED[it] ?: it.toString() }
        .joinToString("")
        .filter { it.isLetterOrDigit() }

private val FOLDED = mapOf('ø' to "o", 'æ' to "ae", 'ß' to "ss", 'ł' to "l", 'đ' to "d", 'ð' to "d")

// --- The event document -------------------------------------------------------------

@Serializable
private data class EventDoc(
    val name: String = "",
    val copyright: String = "",
    val lastEdit: String = "",
    val locations: List<EventLocation> = emptyList(),
)

@Serializable
private data class EventLocation(val name: String = "", val events: List<EventAct> = emptyList())

@Serializable
private data class EventAct(
    val name: String = "",
    /** "yyyy-MM-dd HH:mm", already local to the festival — the payload says so itself. */
    val start: String = "",
    val end: String = "",
    /**
     * Present on about one act in fifty, so it resolves nothing on its own — but where
     * it is there it is the only thing in the payload that ties a typed name to an
     * artist, exactly and for free.
     *
     * The payload carries **both** `mbId` and `mbid`, on the same act, with the same
     * value. Only one is read, deliberately: declaring them as two names for one field
     * makes the decoder see a single field supplied twice and reject the whole
     * document. The other arrives as an unknown key and is ignored.
     */
    @SerialName("mbId") val mbId: String = "",
)

/**
 * One clashfinder document into a timetable. Pure: hand it the text, however you got it.
 *
 * The by-stage nesting is flattened out, because a night is read down the clock and not
 * down one stage. An act missing a name, a start or a stage is dropped rather than
 * half-built — a partial act clashes with nothing and is invisible on the timetable,
 * which is the failure that would actually matter. A document that has changed shape
 * yields nothing rather than nonsense.
 */
fun parseClashfinderEvent(text: String, id: String = ""): StoredProgramme {
    val doc = runCatching { json.decodeFromString<EventDoc>(text) }.getOrNull()
        ?: return StoredProgramme(id = id)
    val acts = doc.locations.flatMap { location ->
        val stage = location.name.trim()
        if (stage.isEmpty()) return@flatMap emptyList()
        location.events.mapNotNull { act -> act.toProgrammeAct(stage) }
    }
    return StoredProgramme(
        id = id,
        name = doc.name.trim(),
        copyright = doc.copyright.trim(),
        lastEdit = doc.lastEdit.trim(),
        acts = acts.distinctBy { listOf(it.artist, it.date, it.start, it.stage) }
            .sortedWith(compareBy({ it.startsAt() }, { it.stage })),
    )
}

/**
 * **The night boundary is applied backwards here, on purpose.**
 *
 * Clashfinder already dates an after-midnight act to the next calendar day: a 00:45 set
 * on Friday night is stamped Saturday. [ProgrammeAct.startsAt] independently applies
 * the same rule, pushing any start before [NIGHT_ENDS] forward a day, because that is
 * the app's own night boundary and the record it reads is written the other way round.
 * Splitting the timestamp naively would apply the shift *twice* and land the act a full
 * day late. So the previous calendar date is what gets stored, and the existing rule
 * puts it back. The correction happens once, here at the edge; nothing downstream
 * changes.
 */
private fun EventAct.toProgrammeAct(stage: String): ProgrammeAct? {
    val artist = name.trim().ifBlank { return null }
    val (date, clock) = splitStamp(start) ?: return null
    val night = if (clock < NIGHT_ENDS) date.minusDays(1) else date
    return ProgrammeAct(
        artist = artist,
        date = night.toString(),
        start = clock.toString(),
        stage = stage,
        // Only the clock: the date the end belongs to is derived from the night, the
        // same way the start's is, so a set running past midnight needs no second rule.
        // An end that lands before its own start is discarded downstream (see endTimes).
        end = splitStamp(end)?.second?.toString().orEmpty(),
        // Checked at the edge, for [isClashfinderId]'s reason: this one comes off a
        // document anyone may edit and goes into the *path* of a setlist.fm request.
        // Anything that is not a MusicBrainz id is no worse than the common case, which
        // is no id at all.
        mbid = mbId.trim().takeIf { MBID.matches(it) }.orEmpty(),
    )
}

/** A MusicBrainz id: eight-four-four-four-twelve hex digits, and nothing else. */
private val MBID = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

/** "2026-08-11 21:15" as a date and a clock, or null if it is neither. */
private fun splitStamp(stamp: String): Pair<LocalDate, LocalTime>? {
    val parts = stamp.trim().split(" ", "T").filter { it.isNotBlank() }
    if (parts.size < 2) return null
    val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
    val clock = runCatching { LocalTime.parse(parts[1].take(5)) }.getOrNull() ?: return null
    return date to clock
}

// --- Artist resolution --------------------------------------------------------------

/**
 * The lead artist of a billing: everything before the collaboration clause.
 *
 * A timetable bills a back-to-back set as "Artist b2b Other" and a guest spot as
 * "Artist feat. Someone". Neither string is an artist setlist.fm has ever heard of, but
 * the name in front of it is. An ampersand is deliberately **not** a separator: it is
 * part of the name in Nick Cave & The Bad Seeds and in half the bands ever formed.
 */
fun billingLead(name: String): String {
    val lower = name.lowercase()
    val cut = SPLITS.mapNotNull { s -> lower.indexOf(s).takeIf { it > 0 } }.minOrNull()
    return (if (cut == null) name else name.take(cut)).trim()
}

private val SPLITS = listOf(" b2b ", " vs ", " vs. ", " feat ", " feat. ", " ft ", " ft. ", " featuring ")

/**
 * The one search hit that may be bound to [name] — or null, which is a real answer.
 *
 * **Never bind on a weak match.** setlist.fm's search returns its maximum confidence
 * score for results that are plainly wrong, so the score cannot be used as a gate at
 * all; only exact equality under [foldName] can. The cost of refusing is that an act
 * joins the **Bill** with the name as printed and no artist behind it, which is a
 * visibly incomplete row somebody can fix. The cost of binding wrongly is a timeline
 * quietly full of plausible-looking mistakes, which nobody ever notices to fix.
 *
 * This is also what keeps the entries on a timetable that are not music — a film, a
 * quiz, a notice, a silent disco — from becoming bands. They stay visible on the
 * programme, as published; they simply never match an artist. The payload carries no
 * type field, so there is nothing honest to classify on, and a guess at one would be
 * the same failure in a different coat.
 */
fun matchArtist(name: String, hits: List<FmArtist>): FmArtist? {
    val key = foldName(billingLead(name))
    if (key.isEmpty()) return null
    return hits.firstOrNull { foldName(it.name) == key }
}

// --- The network ---------------------------------------------------------------------

/**
 * Ids come off a document and go into a URL path, so they are checked rather than
 * trusted. Clashfinder's own ids are this alphabet.
 */
fun isClashfinderId(id: String): Boolean =
    id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

/**
 * The two requests this feature makes: one index per refresh, one document per festival
 * a person actually picks.
 *
 * **The budget is deliberately small.** The host serves a CAPTCHA interstitial rather
 * than a rate-limit status when it decides a client is a bot, so bulk fetching is not
 * available and mirroring the corpus is not an option — which is fine, because matching
 * on a name and a date needs neither.
 */
/**
 * The host wants a human at a browser before it will answer this address.
 *
 * Carries [url] because "try again in a while" is not a thing anybody can act on: the
 * check clears by *taking* it, in a browser, on this phone — the interstitial gates the
 * address, so clearing it there clears it for the app's own requests too. The URL is
 * the bare endpoint, without the account's credentials on the query string: it draws
 * the same check, and the key has no business in browser history.
 */
class BrowserCheckRequired(val url: String) : IOException(
    "clashfinder wants a browser check before it will answer this phone."
)

private val REFRESH = Regex(
    """http-equiv=["']refresh["'][^>]*content=["']\s*\d+\s*;\s*(?:url=)?([^"']+)""",
    RegexOption.IGNORE_CASE,
)

/**
 * Where the check actually lives, read out of the interstitial that names it.
 *
 * The 202 is a bare meta-refresh to `/.well-known/sgcaptcha/?r=…&y=ipc:<address>:<token>`,
 * and that address in the token is the thing being cleared — which is why taking the
 * check in the phone's browser clears it for the app's own requests on the same
 * connection. Sending someone to the data URL instead would make them take the check and
 * then download a 4 MB JSON file into their downloads for their trouble, so the `r`
 * parameter — where to go afterwards — is pointed at the front page.
 *
 * Null where the body is some other HTML: then there is nothing to open and the caller
 * falls back to the address it asked for.
 */
fun browserCheckUrl(body: String): String? {
    val target = REFRESH.find(body)?.groupValues?.get(1)?.trim().orEmpty()
    val absolute = when {
        target.startsWith("http") -> target
        target.startsWith("/") -> ORIGIN + target
        else -> return null
    }
    return absolute.replace(Regex("""(?<=[?&])r=[^&]*"""), "r=%2F")
}

/**
 * The one cookie jar, shared with the WebView the check is taken in.
 *
 * The check does not clear an *address* — it clears a *client*, by leaving a cookie
 * behind — which is why taking it in Chrome did nothing for the app: two clients, two
 * jars. Pointing OkHttp at the platform's own store makes the browser that takes the
 * check and the code that makes the request the same client, and the clearance is kept
 * across launches by the platform rather than by us.
 */
private object WebViewCookies : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        CookieManager.getInstance().getCookie(url.toString())
            ?.split(";")
            ?.mapNotNull { Cookie.parse(url, it.trim()) }
            .orEmpty()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val store = CookieManager.getInstance()
        cookies.forEach { store.setCookie(url.toString(), it.toString()) }
        store.flush()
    }
}

class ClashfinderClient(private val auth: suspend () -> ClashfinderAuth?) {

    private val http = OkHttpClient.Builder().cookieJar(WebViewCookies).build()

    private suspend fun get(path: String): String {
        val credentials = auth() ?: throw IOException(
            "No clashfinder account yet. Add your username and private key in Settings."
        )
        val url = "$HOST/$path".toHttpUrl().newBuilder()
            .addQueryParameter("authUsername", credentials.user)
            .addQueryParameter("authPublicKey", credentials.publicKey)
            .build()
        val body = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                // Named, with a way to reach us. The endpoint takes an account's own
                // credentials, so it is meant to be called by programs; saying which
                // program this is beats the library's default and beats pretending to
                // be a browser, which we are not.
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            http.newCall(request)
                .execute().use { resp ->
                    when {
                        resp.isSuccessful -> resp.body?.string().orEmpty()
                        resp.code == 401 || resp.code == 403 -> throw IOException(
                            "clashfinder rejected the account. Check the username and " +
                                "private key in Settings — the private key is not the password."
                        )
                        else -> throw IOException("clashfinder returned ${resp.code}.")
                    }
                }
        }
        // The bot check answers 200-with-HTML rather than a status anyone can read. Say
        // what it is, because "could not read the programme" would send someone looking
        // for a bug in the parser — and hand back a way through it.
        if (!body.trimStart().startsWith("{")) {
            throw BrowserCheckRequired(browserCheckUrl(body) ?: "$HOST/$path")
        }
        return body
    }

    /** The whole catalogue, reduced. One request; the caller caches what comes back. */
    suspend fun index(): List<ClashfinderFestival> {
        val festivals = parseClashfinderIndex(get(INDEX_PATH))
        if (festivals.isEmpty()) throw IOException("Could not read clashfinder's festival list.")
        return festivals
    }

    /**
     * One festival's timetable.
     *
     * An empty parse raises rather than returning an empty programme: a blank timetable
     * on screen reads as a festival with no bands, and the caller would cache it.
     */
    suspend fun event(id: String): StoredProgramme {
        if (!isClashfinderId(id)) throw IOException("$id is not a clashfinder id.")
        val programme = parseClashfinderEvent(get("event/$id.json"), id)
        if (programme.acts.isEmpty()) {
            throw IOException("That clashfinder has no timetable in it yet.")
        }
        return programme
    }
}

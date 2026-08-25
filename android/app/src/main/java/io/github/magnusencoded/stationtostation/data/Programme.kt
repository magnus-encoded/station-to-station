package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.ui.NIGHT_ENDS
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * One act on a published festival programme: who, when, where.
 *
 * Not a [StoredAct]. A **Bill** is the *hedged* case — a poster with names and no
 * nights, which is the whole reason it exists — and this is its opposite: a schedule
 * the festival has committed to, down to the minute and the stage. Folding announced
 * set times into `StoredAct` would put a field on every act that is empty for every
 * festival that works the way Ringnes does, and `StoredAct`'s own doc is emphatic that
 * inventing temporal precision the source does not have is the fabrication to avoid.
 *
 * So this is a separate, read-only record. Nothing here is ever written by a user and
 * nothing here is evidence of attendance — it is the noticeboard, not the timeline.
 *
 * [stage] is the collision axis. Two acts at one festival clash only because a person
 * cannot be in two places, so a clash is defined across *different* stages; two names
 * on one stage at one time would be a mistake in the programme, not a choice to make.
 */
@Serializable
data class ProgrammeAct(
    val artist: String = "",
    /** ISO yyyy-MM-dd, the festival day as the programme lists it. */
    val date: String = "",
    /** HH:mm, local. */
    val start: String = "",
    val stage: String = "",
) {
    /**
     * When this act starts, as a moment.
     *
     * A start before [NIGHT_ENDS] belongs to the *next* calendar day — a programme
     * lists a 01:00 set under the night it belongs to, which is how everyone at the
     * festival talks about it and the same boundary [billNight] already draws in
     * the other direction. Without this an after-midnight act sorts to the front of
     * its own day and clashes with the afternoon.
     */
    fun startsAt(): LocalDateTime? =
        runCatching { LocalDate.parse(date) }.getOrNull()?.let { setTimeOnNight(it, start) }
}

/**
 * An `HH:mm` slot on the night of [date], as a moment — see [ProgrammeAct.startsAt].
 *
 * Shared rather than written twice because it is one rule about the record and not
 * two: a **Festival** page's published set times land in [StoredFestival.setTimes] in
 * the same shape a programme carries them, and an after-midnight set has to close its
 * own night in both places or the two disagree about who played last.
 */
internal fun setTimeOnNight(date: LocalDate, hhmm: String): LocalDateTime? {
    val t = runCatching { LocalTime.parse(hhmm) }.getOrNull() ?: return null
    return if (t < NIGHT_ENDS) date.plusDays(1).atTime(t) else date.atTime(t)
}

/**
 * How long an act runs when the programme does not say — and it never says.
 *
 * ponytail: a flat hour, because the only alternatives are worse. Øya publishes start
 * times and stage only, so an end time is always inferred; the inference below prefers
 * the *next act on the same stage*, which is real information, and falls back to this
 * for the last act of the night, where there is none.
 */
const val DEFAULT_SET_MINUTES = 60L

/**
 * When each act ends, inferred — because no festival publishes it.
 *
 * The next act on the same stage is the honest source: a stage runs one act at a time,
 * so the following start is an upper bound on this one's end, and it is usually close
 * to exact because changeovers are short. Where there is no next act — the last set of
 * the night on that stage — there is nothing to lean on and [DEFAULT_SET_MINUTES]
 * stands in.
 *
 * Returned as a map rather than a field on [ProgrammeAct] because an act's end is not
 * a property of the act. It is a property of the act *and everything after it*, and
 * baking it into the record would make it look like published data.
 */
fun endTimes(acts: List<ProgrammeAct>): Map<ProgrammeAct, LocalDateTime> {
    val ends = mutableMapOf<ProgrammeAct, LocalDateTime>()
    acts.groupBy { it.stage }.forEach { (_, onStage) ->
        val sorted = onStage.mapNotNull { act -> act.startsAt()?.let { act to it } }
            .sortedBy { it.second }
        sorted.forEachIndexed { i, (act, start) ->
            val next = sorted.getOrNull(i + 1)?.second
            // A gap of hours means the stage went quiet, not that the act played on.
            // Cap at the default so an afternoon set doesn't swallow the evening.
            ends[act] = minOf(next ?: start.plusMinutes(DEFAULT_SET_MINUTES),
                start.plusMinutes(DEFAULT_SET_MINUTES))
        }
    }
    return ends
}

/** Half-open: an act ending exactly as another starts is a dash between stages, not a clash. */
private fun overlaps(
    aStart: LocalDateTime, aEnd: LocalDateTime,
    bStart: LocalDateTime, bEnd: LocalDateTime,
): Boolean = aStart < bEnd && bStart < aEnd

/**
 * What [act] is a choice *against*: everything on another stage that overlaps it.
 *
 * This is the question the whole feature exists to answer, and it is asked of one act
 * at a time because that is how it is asked in life — you know who you want to see and
 * you want to know the cost.
 */
fun clashesWith(act: ProgrammeAct, acts: List<ProgrammeAct>): List<ProgrammeAct> {
    val ends = endTimes(acts)
    val start = act.startsAt() ?: return emptyList()
    val end = ends[act] ?: return emptyList()
    return acts.filter { other ->
        other != act && other.stage != act.stage &&
            other.startsAt()?.let { s -> ends[other]?.let { e -> overlaps(start, end, s, e) } } == true
    }.sortedBy { it.startsAt() }
}

/** Everything playing at [moment], across all stages. The "what's on right now" list. */
fun playingAt(moment: LocalDateTime, acts: List<ProgrammeAct>): List<ProgrammeAct> {
    val ends = endTimes(acts)
    return acts.filter { act ->
        val s = act.startsAt() ?: return@filter false
        val e = ends[act] ?: return@filter false
        !moment.isBefore(s) && moment.isBefore(e)
    }.sortedBy { it.stage }
}

/**
 * The next acts to start after [moment], earliest first — the "and then" list.
 *
 * Grouped by start time by the caller if wanted; kept flat here because "what is next"
 * at a festival is genuinely several things at once, which is the point.
 */
fun nextAfter(moment: LocalDateTime, acts: List<ProgrammeAct>, limit: Int = 6): List<ProgrammeAct> =
    acts.filter { it.startsAt()?.isAfter(moment) == true }
        .sortedWith(compareBy({ it.startsAt() }, { it.stage }))
        .take(limit)

/** The festival days the programme covers, in order. */
fun programmeDays(acts: List<ProgrammeAct>): List<LocalDate> =
    acts.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.distinct().sorted()

/** Acts on one festival day, in running order. */
fun actsOn(day: LocalDate, acts: List<ProgrammeAct>): List<ProgrammeAct> =
    acts.filter { it.date == day.toString() }
        .sortedWith(compareBy({ it.startsAt() }, { it.stage }))

private val json = Json { ignoreUnknownKeys = true }

/** Reads a cached programme back. */
fun parseProgramme(text: String): List<ProgrammeAct> =
    runCatching { json.decodeFromString<List<ProgrammeAct>>(text) }.getOrDefault(emptyList())

/** Writes one out, for the local cache and — later — for handing to another phone. */
fun encodeProgramme(acts: List<ProgrammeAct>): String =
    json.encodeToString(ListSerializer(ProgrammeAct.serializer()), acts)

/**
 * The published programme, read off the festival's own page.
 *
 * **The app never carries a copy of anyone's programme.** It is fetched by the user's
 * own device, from the public page, exactly as a browser would — the app is a user
 * agent here, not a publisher. Nothing about a festival's line-up ships inside the
 * binary, and that is a deliberate line, not an oversight.
 *
 * Parsing is by regex over the server-rendered HTML, which is a thing to be honest
 * about: it is coupled to markup nobody promised to keep. That is survivable because
 * of *when* it runs — at home, the night before, with signal and a screen — and
 * because it fails visibly (no acts) rather than subtly. A silent partial parse is
 * the failure that would matter, which is why [oyaProgramme] is all-or-nothing per
 * act: an act missing any of its four fields is dropped rather than half-built.
 *
 * ponytail: one festival's markup, because one festival is what there is. The shape
 * generalises when a second one does.
 */
const val OYA_PROGRAMME_URL = "https://www.oyafestivalen.no/program/program-2026"

private val NORWEGIAN_MONTHS = mapOf(
    "januar" to 1, "februar" to 2, "mars" to 3, "april" to 4, "mai" to 5, "juni" to 6,
    "juli" to 7, "august" to 8, "september" to 9, "oktober" to 10, "november" to 11,
    "desember" to 12,
)

private val BLOCK = Regex("""<h3[^>]*>(.*?)</h3>(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL)
private val LIST_ITEM = Regex("""<li>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
private val DAY = Regex("""(\d{1,2})\.\s*(\p{L}+)""")
private val CLOCK = Regex("""([0-2]\d:[0-5]\d)""")
private val TAGS = Regex("""<!--.*?-->|<[^>]+>""", RegexOption.DOT_MATCHES_ALL)

private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|\w+);""")
private val NAMED = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
)

/**
 * Entities back into characters, because a name is what a person reads, not what a
 * page encodes. Øya's own headliner is written `Nick Cave &amp; The Bad Seeds`, and an
 * act stored that way never matches the **Gig** pasted from setlist.fm — the ampersand
 * is the join between the programme and the timeline, and it has to be an ampersand.
 *
 * ponytail: no HTML parser and not `android.text.Html`, which would drag the whole
 * parser onto a device and out of the JVM tests. The named list is the five that mean
 * anything in a band name; anything numeric is decoded outright.
 *
 * `Character.toChars` and not `toChar()`: the latter narrows an `Int` and would turn
 * every code point above U+FFFF into garbage — a band name is exactly the place an
 * emoji or a rare script turns up. Anything outside Unicode is left as written.
 */
private fun String.entities(): String = ENTITY.replace(this) { m ->
    val e = m.groupValues[1]
    when {
        e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.codePoint()
        e.startsWith("#") -> e.drop(1).toIntOrNull()?.codePoint()
        else -> NAMED[e]
    } ?: m.value
}

/** A code point as text, or null if it is not one — `toChars` throws on the rest. */
private fun Int.codePoint(): String? =
    if (Character.isValidCodePoint(this)) String(Character.toChars(this)) else null

/**
 * Markup out, text in — the pages put artist names inside nested spans and comments.
 *
 * Stripping runs before decoding, so `&lt;b&gt;` in the source survives as the literal
 * text `<b>`. That is right for a name a person reads, and safe only because the
 * result is a display string and a match key: it goes to a Compose `Text`, which
 * renders characters and not markup. Never hand it to a WebView or `Html.fromHtml`.
 */
private fun String.text(): String =
    // Collapse after decoding, and count a decoded non-breaking space as a space: the
    // page uses one to hold a name together, and it is a space to everyone reading it.
    TAGS.replace(this, "").entities().replace(Regex("""[\s\u00A0]+"""), " ").trim()

/**
 * Øya's programme page into acts. Pure: hand it the HTML, however you got it.
 *
 * [year] is a parameter because the page writes "torsdag 13. august" with no year in
 * it at all. Taking it from the url rather than the clock means a programme read in
 * January is not dated to January.
 */
fun oyaProgramme(html: String, year: Int): List<ProgrammeAct> =
    BLOCK.findAll(html).mapNotNull { block ->
        val artist = block.groupValues[1].text().ifBlank { return@mapNotNull null }
        val items = LIST_ITEM.findAll(block.groupValues[2]).map { it.groupValues[1].text() }.toList()
        if (items.size < 3) return@mapNotNull null
        val day = DAY.find(items[0]) ?: return@mapNotNull null
        val month = NORWEGIAN_MONTHS[day.groupValues[2].lowercase()] ?: return@mapNotNull null
        val clock = CLOCK.find(items[1])?.groupValues?.get(1) ?: return@mapNotNull null
        val stage = items[2].ifBlank { return@mapNotNull null }
        ProgrammeAct(
            artist = artist,
            date = "%04d-%02d-%02d".format(year, month, day.groupValues[1].toInt()),
            start = clock,
            stage = stage,
        )
    }.distinctBy { listOf(it.artist, it.date, it.start, it.stage) }
        .sortedWith(compareBy({ it.date }, { it.start }, { it.stage }))
        .toList()

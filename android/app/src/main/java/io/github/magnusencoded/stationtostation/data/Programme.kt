package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.ui.NIGHT_ENDS
import kotlinx.serialization.Serializable
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
 * nothing here is evidence of attendance. A person may add one of these rows to their
 * **Bill** as an **Act** — that is planning to go, and it is still an **Act** until
 * somebody marks it played. The noticeboard can seed the timeline; it never fills it.
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
    /**
     * HH:mm, local — the end the source *published*, blank where it published none.
     *
     * Øya's own page never carried this and the inference below was the whole story.
     * Clashfinder does carry it, on every act of both documents sampled, and it is
     * worth having: the default set length is an hour, a headline set is not, and an
     * hour-long guess makes [clashesWith] report a real conflict as free time.
     */
    val end: String = "",
    /**
     * The MusicBrainz id the source published for this act, blank where it published
     * none — which is about forty-nine acts in fifty.
     *
     * Kept because it is the only field in a timetable that identifies an artist
     * *exactly*. Where it is there, an **Act** added off this row resolves for free and
     * without a name to get wrong.
     */
    val mbid: String = "",
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

    /** The published end as a moment, on the same night rule as [startsAt]. */
    fun endsAt(): LocalDateTime? =
        if (end.isBlank()) null
        else runCatching { LocalDate.parse(date) }.getOrNull()?.let { setTimeOnNight(it, end) }
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
 * How long an act runs when the programme does not say.
 *
 * ponytail: a flat hour, because the only alternatives are worse. It is the last
 * resort of three: a published end where there is one, otherwise the *next act on the
 * same stage*, which is real information, and this for the last act of the night,
 * where there is neither.
 */
const val DEFAULT_SET_MINUTES = 60L

/**
 * When each act ends: as published, or inferred where it is not.
 *
 * A declared end wins outright, uncapped — a 105-minute headline set truncated to the
 * default hour makes [clashesWith] report a genuine conflict as free time, which is
 * the one thing this feature exists to prevent.
 *
 * The inference stays behind it rather than being deleted. Every act in both sampled
 * clashfinder documents carried an end, but that is two documents out of ten thousand,
 * and a malformed act should degrade to a guessed hour rather than drop out of clash
 * detection entirely. It prefers the next act on the same stage: a stage runs one act
 * at a time, so the following start is an upper bound and usually close to exact.
 *
 * Returned as a map rather than a field on [ProgrammeAct] because an *inferred* end is
 * not a property of the act. It is a property of the act and everything after it.
 *
 * A declared end that is not after the start is not honoured — a source that says an
 * act ends before it begins has said nothing, and the inference is the better answer.
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
            ends[act] = act.endsAt()?.takeIf { it.isAfter(start) }
                ?: minOf(next ?: start.plusMinutes(DEFAULT_SET_MINUTES),
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

/**
 * One festival's timetable as this phone holds it: the acts, and whose it is.
 *
 * **The app never carries a copy of anyone's programme.** It is fetched by this device,
 * with the user's own clashfinder account, and kept only here — the app is a user agent
 * and not a publisher, and no line-up ships inside the binary.
 *
 * [copyright] is not decoration. The data is CC BY-NC 3.0 and attribution is a
 * *condition* of that licence, so the line the payload carries is stored with the acts
 * and rendered with them. [lastEdit] is kept for a different reason: a clashfinder is
 * edited up to and past the doors, so how old this copy is decides whether to trust it.
 */
@Serializable
data class StoredProgramme(
    /** The clashfinder's own id — what a refetch asks for. */
    val id: String = "",
    /** The festival's name as its own document gives it: the screen's title. */
    val name: String = "",
    val copyright: String = "",
    val lastEdit: String = "",
    val acts: List<ProgrammeAct> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

/** Reads a cached programme back. Anything unreadable is no programme at all. */
fun parseProgramme(text: String): StoredProgramme =
    runCatching { json.decodeFromString<StoredProgramme>(text) }.getOrDefault(StoredProgramme())

/** Writes one out, for the local cache and — later — for handing to another phone. */
fun encodeProgramme(programme: StoredProgramme): String =
    json.encodeToString(StoredProgramme.serializer(), programme)


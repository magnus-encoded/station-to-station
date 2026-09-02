package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import java.time.LocalDate
import java.time.LocalDateTime
import java.text.Normalizer
import java.time.format.TextStyle
import java.util.Locale

/**
 * **Departures**: a festival's timetable as a board of choices, and what committing it
 * would change on the **Line**.
 *
 * One pure function ([departuresOf]) is the whole feature's logic. The screen draws its
 * return value and the button reads its return value; neither computes anything itself.
 *
 * **The selection is keyed by act, not by rung.** The spec's shape was
 * `rungKey -> actId | NONE`, which needed a rung identity stable across a refetch —
 * a clashfinder is edited up to and past the doors, and a rung keyed by the acts on it
 * loses the picks whenever the source moves a set. Keying the selection by the *act*
 * removes the question rather than answering it: an act's night and name are what the
 * source is least likely to change, a rung is then pure layout and never stored, and
 * "I choose none of these" needs no sentinel because it is simply nothing on that rung
 * being picked. It also allows picking two acts that clash, which the rung-keyed shape
 * forbade — half of one set and half of the other is a real plan, and refusing it would
 * be the app knowing better than the person holding the phone.
 */
data class Departures(
    /** The festival's days, in order. Tabs are views over this; the diff is not scoped to one. */
    val days: List<LocalDate>,
    /** Each day's positions in running order. */
    val positions: Map<LocalDate, List<Position>>,
    val diff: ProgrammeDiff,
)

/**
 * One position in a day's running order: a single act, or a **rung** of acts that
 * overlap and are therefore a choice.
 *
 * Recomputed on every render and never stored — see [Departures] on why a rung has no
 * identity of its own.
 */
data class Position(val options: List<ProgrammeAct>) {
    val isRung: Boolean get() = options.size > 1
}

/**
 * What committing would do to the **Line**, over the whole **Programme**: day tabs
 * never scope it.
 *
 * [remove] is keys rather than acts on purpose — an act the source has since dropped
 * still has a **Gig** on the line, and that **Gig** still has to be removable.
 */
data class ProgrammeDiff(
    val add: List<ProgrammeAct> = emptyList(),
    val remove: List<String> = emptyList(),
    /** The days the *change* touches, derived from the rungs it moves and never from the tab in view. */
    val days: List<LocalDate> = emptyList(),
) {
    val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty()
}

/**
 * What identifies one act across a refetch: the night it plays and who plays it.
 *
 * Not the stage and not the clock — those are exactly what an editor moves in the week
 * before the doors, and ADR-0005 says a refetch may not cost a user their picks. A
 * festival billing one artist twice on one night is the one shape this folds together,
 * and it is rare enough to accept.
 */
fun actKey(act: ProgrammeAct): String = actKey(act.date, act.artist)

fun actKey(date: String, artist: String): String = "$date|${nameKey(artist)}"

/**
 * A name reduced to what two sources can be expected to agree on.
 *
 * Clashfinder disambiguates by country — `Wilco (US)`, `Nick Cave and the Bad Seeds (AU)`
 * — where setlist.fm carries the plain name; one writes `and` where the other writes `&`;
 * and typographic apostrophes differ between them for the same artist. Compared verbatim
 * these are three different artists, so the same gig gets minted twice and the one
 * already on the **Line** never joins the **Festival** — which is the bug this whole
 * feature was written to end.
 *
 * ponytail: a trailing parenthetical is dropped whatever it says, so `Foo (DJ set)` folds
 * into `Foo`. Two sets by one artist on one night is rarer than the country tags this
 * fixes; split the rule if a festival ever bills both.
 */
fun nameKey(artist: String): String =
    Normalizer.normalize(artist.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(MARKS, "")
        .replace(TRAILING_TAG, "")
        .replace("&", "and")
        .replace(NOT_A_NAME, "")

private val MARKS = Regex("\\p{Mn}+")
private val TRAILING_TAG = Regex("\\s*\\([^()]*\\)\\s*$")
private val NOT_A_NAME = Regex("[^\\p{L}\\p{N}]")

/**
 * The act on this night that is this artist — by MusicBrainz id where both sides carry
 * one, by name otherwise.
 *
 * The id is exact and the name is a guess, so the id goes first. It is only there on
 * about one clashfinder act in fifty, which is why [nameKey] has to be good.
 */
fun matchAct(acts: List<ProgrammeAct>, name: String, mbid: String): ProgrammeAct? =
    acts.firstOrNull { it.mbid.isNotBlank() && it.mbid == mbid }
        ?: acts.firstOrNull { nameKey(it.artist) == nameKey(name) }

/** The board and the pending change, together. [picked] and [applied] are act keys. */
fun departuresOf(
    programme: StoredProgramme,
    picked: Set<String>,
    applied: Set<String>,
): Departures {
    val days = programmeDays(programme.acts)
    return Departures(
        days = days,
        positions = days.associateWith { rungs(actsOn(it, programme.acts)) },
        diff = programmeDiff(programme, picked, applied),
    )
}

/**
 * A day's acts gathered into positions: everything transitively overlapping across
 * stages becomes one rung.
 *
 * Transitive because a chain of three where the first and last do not touch is still
 * one decision — you cannot take the first and the last and skip the middle without
 * that being the same walk. Two acts on one stage never join a rung: a stage runs one
 * act at a time, so that is a sequence the festival posed, not a choice.
 */
internal fun rungs(acts: List<ProgrammeAct>): List<Position> {
    val ends = endTimes(acts)
    val groups = mutableListOf<MutableList<ProgrammeAct>>()
    // In running order, so a group is only ever extended forwards. ponytail: an O(n²)
    // scan over one day's acts — a hundred rows at worst, and a union-find would be
    // more code than the thing it speeds up.
    for (act in acts) {
        val touching = groups.filter { g -> g.any { clashes(it, act, ends) } }
        if (touching.isEmpty()) {
            groups += mutableListOf(act)
        } else {
            val first = touching.first()
            first += act
            touching.drop(1).forEach { first += it; groups.remove(it) }
        }
    }
    return groups
        .map { Position(it.sortedWith(compareBy({ a -> a.startsAt() }, { a -> a.stage }))) }
        .sortedWith(compareBy({ it.options.first().startsAt() }, { it.options.first().stage }))
}

private fun clashes(
    a: ProgrammeAct,
    b: ProgrammeAct,
    ends: Map<ProgrammeAct, LocalDateTime>,
): Boolean {
    if (a.stage == b.stage) return false
    val aStart = a.startsAt() ?: return false
    val bStart = b.startsAt() ?: return false
    val aEnd = ends[a] ?: return false
    val bEnd = ends[b] ?: return false
    return overlaps(aStart, aEnd, bStart, bEnd)
}

/** What is picked and not on the line, what is on the line and no longer picked. */
fun programmeDiff(
    programme: StoredProgramme,
    picked: Set<String>,
    applied: Set<String>,
): ProgrammeDiff {
    val byKey = programme.acts.associateBy { actKey(it) }
    val add = (picked - applied).mapNotNull { byKey[it] }
        .sortedWith(compareBy({ it.startsAt() }, { it.stage }))
    val remove = (applied - picked).sorted()
    val days = (add.map { it.date } + remove.map { it.substringBefore('|') })
        .distinct()
        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        .sorted()
    return ProgrammeDiff(add, remove, days)
}

/**
 * What the commit button says — the *change*, never what is on screen, so it can be
 * pressed from any tab without checking the others.
 *
 * Null when nothing has changed: a button that would do nothing should not be offered.
 */
fun commitLabel(
    diff: ProgrammeDiff,
    festival: String,
    firstCommit: Boolean,
): String? = when {
    diff.isEmpty -> null
    diff.add.isNotEmpty() && diff.remove.isNotEmpty() -> "Update ${daysPhrase(diff.days)}"
    diff.add.isEmpty() -> "Remove ${gigs(diff.remove.size)}"
    // The first commit off a programme is named in the language of the festival; a later
    // one is named by how much it adds, so an addition never reads as a first commit.
    firstCommit && diff.days.size == 1 ->
        "Add ${dayName(diff.days.first())}" + festival.trim().takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
    else -> "Add ${diff.add.size} more ${if (diff.add.size == 1) "gig" else "gigs"}"
}

/**
 * The line under the label: the arithmetic the label rounds off.
 *
 * The label is the sentence and this is the receipt — how many in each direction, or
 * which festival and day the sentence was about, or how much is still undecided. Blank
 * where the label already says everything.
 */
fun commitSub(
    diff: ProgrammeDiff,
    festival: String,
    firstCommit: Boolean,
    open: Int,
): String = when {
    diff.isEmpty -> ""
    diff.add.isNotEmpty() && diff.remove.isNotEmpty() -> "+${diff.add.size} · −${diff.remove.size}"
    diff.add.isEmpty() -> festival.trim()
    firstCommit -> gigs(diff.add.size) + if (open > 0) " · $open still open" else ""
    else -> festival.trim()
}

private fun gigs(n: Int): String = "$n ${if (n == 1) "gig" else "gigs"}"

private fun dayName(day: LocalDate): String =
    day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

/** "Thursday", "Thursday and Friday", "Wednesday, Thursday and Friday". */
private fun daysPhrase(days: List<LocalDate>): String {
    val names = days.map(::dayName)
    return when (names.size) {
        0 -> "your programme"
        1 -> names.first()
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

/**
 * The acts whose sets have already finished at [now].
 *
 * Committing one of these is not a plan, it is a recollection — a **Programme** is
 * opened after the festival at least as often as before it, and back-filling a weekend
 * you were at is the ordinary case. A **Gig** minted as planned for a night that is over
 * sits in the future lane for ever, which drew the same **Festival** twice: once above
 * today holding the night just added, once below it holding the nights already
 * evidenced.
 *
 * The claim is `attended` and never `checked_in`: naming a set off a timetable days
 * later is remembering, and a check-in is something only a person standing in front of
 * the stage does.
 */
fun playedActs(acts: List<ProgrammeAct>, now: LocalDateTime): Set<String> {
    val ends = endTimes(acts)
    return acts.filter { ends[it]?.isBefore(now) == true }.map { actKey(it) }.toSet()
}

/** The **Festival** identity a **Programme** adopts: local id first, clashfinder id under it. */
fun programmeFestivalId(programme: StoredProgramme): String =
    festivalIdForSlug("clashfinder:${programme.id}")

/**
 * Which of this **Programme**'s acts are already **Gigs** on the **Line**.
 *
 * By night and artist across the *whole* line rather than only inside this festival:
 * an act already on the line from somewhere else is the duplicate this feature exists
 * to stop minting, so it reads as already applied and the commit adopts it.
 */
fun appliedActs(programme: StoredProgramme, gigs: List<FmSetlist>): Set<String> {
    val byNight = programme.acts.groupBy { it.date }
    return gigs.mapNotNull { gig ->
        val date = gig.localDate()?.toString() ?: return@mapNotNull null
        matchAct(byNight[date].orEmpty(), gig.artist?.name.orEmpty(), gig.artist?.mbid.orEmpty())
            ?.let { actKey(it) }
    }.toSet()
}

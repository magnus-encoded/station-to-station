package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmCity
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmVenue
import io.github.magnusencoded.stationtostation.ui.TimelineNode
import io.github.magnusencoded.stationtostation.ui.groupIntoFestivals
import io.github.magnusencoded.stationtostation.ui.isPlanned
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * setlist.fm's add-a-setlist entry point, for a night they have no record of at all.
 *
 * **Verified 2026-08-05.** It requires a login and takes **no prefill parameters** —
 * the Ringnes festival page's own "Add Setlist" link is a bare `../edit`. There is no
 * url that can carry the facts, which is why the clipboard ([setlistPaste]) is not a
 * shortcut around the form but the only channel into it.
 *
 * There is a *second* url — `/edit?setlist=<id>&step=song`, which lands straight on
 * one setlist's song editor — and this app deliberately does not build it. The id in
 * that parameter is **not** the id the API returns: the page `…-63a80d2f.html` links
 * to `edit?setlist=3a80d2f`, and `edit?setlist=63a80d2f` opens *a different concert
 * entirely* (verified). Constructing it from an `FmSetlist.id` would send the
 * Historian to edit a stranger's night, silently, on a shared public record. Where a
 * record exists the app has its `url` already — [setlistEditEntry] opens that page,
 * whose own "Edit setlist" link is correct by construction.
 */
const val SETLISTFM_ADD_URL = "https://www.setlist.fm/edit"

/**
 * Where the Historian is sent to file this night, and it is one of two places.
 *
 * A gig setlist.fm already has — including the empty-setlist case, a record with a
 * page and no songs — goes to *its own page*, one click from the right edit form. A
 * gig they have never heard of goes to the generic add flow, which is the only door
 * there is. The clipboard carries the set either way.
 */
fun setlistEditEntry(setlist: FmSetlist): String = setlist.url ?: SETLISTFM_ADD_URL

/**
 * One row above today: a **Gig** I hold a ticket for, or the **Festival** a few of
 * them at one venue on one night turn out to be.
 */
sealed interface FutureRow {
    /**
     * A **Gig** I hold a ticket for — or the **Festival** a few of them at one venue
     * on one night turn out to be. Two nights above today at the same place is the
     * same shape as two nights below it, and the lane used to draw them as two loose
     * nodes only because it did its own grouping, which was none (#134).
     */
    data class Ticket(val node: TimelineNode) : FutureRow

    val date: LocalDate?
        // The night a cluster opens, not the night it ends.
        get() = (this as Ticket).node.shows.mapNotNull { it.localDate() }.minOrNull()
}

/**
 * Everything above today, furthest future first — the same descending order the
 * attended rows below already use, which is the whole point: one line, one rule.
 *
 * "Above today" is the nights whose claim in [attendance] is still `planned`, never
 * the nights that happen to sit in `gigPlanned` (#127, #134). Nothing ever leaves
 * that map — it is the only home of the `FmSetlist` for a **Gig** with no import
 * behind it — so membership made every night I ever planned a plan forever.
 *
 * Planned gigs go through the one [groupIntoFestivals] both lanes call, so two tickets
 * for one night at one venue are one **Section** above today exactly as they would be
 * below it. [festivals] are the identities: without one, nothing groups (#166).
 *
 * A row with no date sorts to the *bottom* of the future, not the top. Unknown is not
 * "the furthest away".
 */
fun futureRows(
    tickets: List<FmSetlist>,
    attendance: Map<String, StoredAttendance>,
    festivals: Festivals = Festivals(),
): List<FutureRow> {
    val nodes = groupIntoFestivals(plannedLane(tickets, attendance), festivals)
    return nodes.map(FutureRow::Ticket)
        .sortedWith(compareByDescending(nullsFirst<LocalDate>()) { it.date })
}

/**
 * The nights the future lane is made of: still a plan, newest first.
 *
 * Date-ordered because the lane is drawn newest first and `gigPlanned`'s own order is
 * whatever they happened to be added in. Its own function because the identity
 * resolver has to see the exact same list the lane does.
 */
fun plannedLane(
    gigs: List<FmSetlist>,
    attendance: Map<String, StoredAttendance>,
): List<FmSetlist> = gigs
    .filter { isPlanned(attendance[it.id]?.provenance) }
    .sortedByDescending { it.localDate() }

/**
 * The nights the **Spine** is made of: setlist.fm's **Attended** list, plus my own
 * evidenced nights it has never heard of. Newest first, for [plannedLane]'s reason.
 *
 * The counterpart to [plannedLane], and the half that was missing. The Spine used to
 * be `shows[me]` alone, so a night's only route onto the timeline was setlist.fm
 * knowing about it — and [plannedLane] drops a night the moment it stops being a
 * plan. A night I checked into that setlist.fm has never heard of therefore left the
 * future lane and arrived nowhere: it was on neither list, holding a **Log** and
 * seven photographs that nothing would draw. Nick Cave at Øya 2026 is the night that
 * found this.
 *
 * "Evidenced" is [isPlanned] read the other way round — `attended` and `checked_in`
 * are evidence I was there, and a check-in is the strongest claim this app can hold.
 * A night carrying it must outrank the absence of a vendor's row about it.
 *
 * Deduplicated on the setlist.fm id, which is what the two lists share: a planned
 * night that later turns up in the **Attended** import is one night, and the
 * imported copy wins because it is the published record of the same evening.
 */
fun spineNights(
    attended: List<FmSetlist>,
    planned: List<FmSetlist>,
    attendance: Map<String, StoredAttendance>,
): List<FmSetlist> {
    val known = attended.map { it.id }.toSet()
    val mine = planned.filter { it.id !in known && !isPlanned(attendance[it.id]?.provenance) }
    if (mine.isEmpty()) return attended
    return (attended + mine).sortedByDescending { it.localDate() }
}

/** dd-MM-yyyy, the one date shape this app and setlist.fm both speak. */
private val FM_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH)

fun fmDate(date: LocalDate): String = date.format(FM_DATE)

fun parseFmDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.trim(), FM_DATE) }.getOrNull()

/**
 * A **Log**: the ordered songs *I* observed at one **Gig**, on my own device.
 *
 * Not setlist.fm's setlist. That is the published shared record; this is the witness
 * statement, and the two are kept apart because **the app is the source of truth
 * about what was observed and setlist.fm is a publication target**. A **Log** is
 * freely editable forever — remembering a song three days later costs nothing — and
 * **Publish** never writes back into it.
 *
 * [closed] is the whole reason this record exists rather than a list of strings on
 * the night. A set captured by ticking off songs an artist has played before is
 * **incomplete by construction**: the candidate pool cannot contain a new song, a
 * cover, a guest spot, or anything at all by an artist setlist.fm has never heard of.
 * So a **Log** starts **Open**, renders as unfinished, and only a person may say
 * otherwise. Crucially the bit never makes the round trip — setlist.fm has nowhere to
 * keep it, so a published set coming back would look finished when it isn't, and that
 * is unrecoverable by construction. The fix is that it never leaves.
 *
 * A blank entry in [songs] is a **Gap**: they played something and I could not name
 * it. An acknowledged gap is a true fact; the same song silently missing is the
 * record lying about its own certainty. A song always has a name, so blank is
 * unambiguous and needs no second field.
 *
 * That last sentence was written before [remembered] and is now too strong — see it
 * for what it did not anticipate (#126).
 */
@Serializable
data class StoredLog(
    val songs: List<String> = emptyList(),
    val closed: Boolean = false,
    /**
     * The **Remembered Line**: the words originally written where a title replaced
     * them, blank where nothing was replaced. Parallel to [songs] and the same length.
     *
     * A **Log** is written in the dark while the band is still playing, so sometimes
     * what gets typed is not the title but the only words that could be caught — a
     * line from the chorus, entered as the title because there was nowhere else to put
     * it. Correcting that used to mean choosing between a wrong title and destroying
     * the words that are the actual memory. **The line someone remembered is not
     * inferior data waiting to be replaced**; for the **Reliver** it is often *the*
     * memory, and replacing it makes the record more correct and less true.
     *
     * A new field rather than a reshaped [songs], following the precedent set by
     * `playlistsMade`: an older build reads the key it knows and round-trips its own
     * cache, where a changed type under the same name would have failed to decode and
     * dropped every **Log** with it. An older cache simply has no [remembered] at all,
     * which reads as "nothing was ever replaced" — which is exactly true.
     *
     * Parallel lists only stay parallel if one place keeps them so. That place is the
     * five functions below; nothing else may edit [songs] directly.
     */
    val remembered: List<String> = emptyList(),
) {
    /** Songs actually named. A **Gap** is in the record but is not a title. */
    fun named(): List<String> = songs.filter { it.isNotBlank() }
    val gaps: Int get() = songs.count { it.isBlank() }

    /** The words originally written at [i], or null where the entry is as typed. */
    fun rememberedAt(i: Int): String? = remembered.getOrNull(i)?.takeIf { it.isNotBlank() }

    /** A song, at the end, in the order it was tapped in. */
    fun adding(song: String): StoredLog = copy(songs = songs + song, remembered = aligned() + "")

    /** One entry gone, and the words behind it with it. */
    fun removingAt(i: Int): StoredLog = copy(
        songs = songs.filterIndexed { j, _ -> j != i },
        remembered = aligned().filterIndexed { j, _ -> j != i },
    )

    /**
     * [i] becomes [title], and what was there moves into [remembered].
     *
     * Two rules that are easy to get wrong and are asserted:
     *
     * - **A second correction keeps the first words.** They are the ones written in the
     *   dark; a title I already chose is not a memory to preserve.
     * - **A Gap is not corrected.** "One I couldn't name" is an acknowledged fact, not
     *   an invitation to guess, so this leaves a blank entry alone.
     */
    fun correctingAt(i: Int, title: String): StoredLog {
        val was = songs.getOrNull(i)?.takeIf { it.isNotBlank() } ?: return this
        if (title.isBlank()) return this
        val lines = aligned().toMutableList()
        if (lines[i].isBlank()) lines[i] = was
        return copy(songs = songs.toMutableList().also { it[i] = title }, remembered = lines)
    }

    /** The words come back as the entry. A wrong correction is never a one-way door. */
    fun restoringAt(i: Int): StoredLog {
        val line = rememberedAt(i) ?: return this
        val lines = aligned().toMutableList().also { it[i] = "" }
        return copy(songs = songs.toMutableList().also { it[i] = line }, remembered = lines)
    }

    /** [remembered] at [songs]'s length: an older cache carries none at all. */
    private fun aligned(): List<String> = List(songs.size) { remembered.getOrNull(it) ?: "" }
}

/**
 * The artist's own titles, ranked against the words someone wrote down (#126).
 *
 * **Nothing here classifies.** "This string is not a known song" is genuinely ambiguous
 * between "a title we don't know" and "a lyric whose title differs" — real titles are
 * frequently whole sentences, and remembered lines frequently contain the title — so
 * any classifier applied to the string alone is wrong in both directions. This only
 * *orders* candidates; a tap is what decides, exactly as it is for correcting an
 * **Act**'s name.
 *
 * The whole catalogue is returned, never a filtered shortlist: a remembered line
 * sharing no words with any title still has to be correctable, so a low-ranking answer
 * must stay reachable. When nothing matches, the order is simply the order it came in,
 * which reads as "nothing confident" rather than promoting a bad match to the top.
 *
 * A **contained** title outranks scattered overlap. "Toothpicks and Gum" appears in
 * "All held together by toothpicks and gum" as a contiguous phrase, which is a stronger
 * signal than the same words spread across a sentence — and cheap to compute. Verified
 * against that real case: it scores 1.00 where the next candidate scores 0.25.
 *
 * Normalisation is `songKey`'s, so "Dont" matches "Don't" here exactly as it does
 * everywhere else recognition happens.
 */
fun rankTitles(line: String, catalogue: List<String>): List<String> {
    if (line.isBlank()) return catalogue
    val phrase = line.phrase()
    val words = line.words()
    return catalogue.sortedByDescending { title ->
        val t = title.phrase()
        val contained = if (t.isNotBlank() && phrase.contains(t)) 1.0 else 0.0
        val tokens = title.words()
        val overlap = if (tokens.isEmpty()) 0.0 else tokens.count { it in words }.toDouble() / tokens.size
        contained + overlap
    }
}

/** Words for overlap, where [songKey] throws spacing away too. */
private fun String.words(): Set<String> =
    lowercase().replace("'", "").replace("’", "")
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

/**
 * The same words, in order, with a space at each end — [songKey] with its spacing kept.
 *
 * Containment is tested on this and not on `songKey`, which throws spacing away
 * entirely: on its terms the title *Sand* is inside "toothpick**s and** gum", and a
 * false containment is worth a whole point here, so a two-word coincidence would be
 * promoted above the title someone actually wrote. Right for equality, wrong for
 * substring search.
 *
 * Apostrophes close rather than split, so "Don't" is the one word "dont" that someone
 * typing in the dark would write — the case `songKey` exists for, applied to spacing.
 */
private fun String.phrase(): String =
    lowercase().replace("'", "").replace("’", "")
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        .joinToString(" ", prefix = " ", postfix = " ")

/**
 * Loose song-title equality: case, punctuation and spacing thrown away.
 *
 * "P.I.M.P." typed as "PIMP", "Don't" as "Dont". This is used for *recognition* —
 * deciding whether a band has this song in their history — and never for recording,
 * where the title is kept exactly as it was written. Being strict here would fail the
 * one gesture it exists to serve.
 */
internal fun sameSong(a: String, b: String): Boolean = a.songKey() == b.songKey()

private fun String.songKey(): String = lowercase().filter { it.isLetterOrDigit() }

/**
 * The night's set, in setlist.fm's own paste syntax — the Historian's actual output.
 *
 * **What was verified, 2026-08-05.** setlist.fm's editor has a *Text Field* mode that
 * takes a whole setlist as plain text in one paste and resolves the titles itself,
 * so one paste per night is the real mechanism. Its syntax is one song per line, with
 * markers layered on top: a blank line before an encore's first song, `@Cover[artist]`,
 * `@With[artist]`, `@Tape[note]`, `@Info[note]`, `@Set[name]`, `@Unknown[note]`, and
 * ` / ` between the parts of a medley.
 *
 * **This emits bare titles, one per line, and nothing else — deliberately.** Every one
 * of those markers encodes a fact this app never captured: nobody ticking songs off in
 * a field told it which one was a cover or where the encore began. Emitting a marker
 * would be inventing the fact it marks, on a public shared database, silently. The
 * plain form is the whole of what is known, so it is both the safest output *and* the
 * complete one. Add a marker the day the capture actually asks the question.
 *
 * Order is the payload: a set can play the same song twice and running order is the
 * only thing that distinguishes them — the same reasoning `StoredMedia.songOffsets`
 * is a positional list for.
 *
 * A **Gap** is the one marker emitted, as `@Unknown[]`, and only because setlist.fm
 * has the concept natively — the tutorial documents `@Unknown[optional comment]` and
 * their API already returns nameless placeholder songs for it, which `performed()`
 * has filtered since long before this. Dropping gaps instead would publish a set that
 * silently claims the missing songs were never played. If the marker is ever wrong it
 * fails *loudly*, as a song visibly titled `@Unknown[]` — which someone fixes — rather
 * than quietly, which is the failure mode that actually matters here.
 */
fun setlistPaste(log: StoredLog): String =
    log.songs.joinToString("\n") { it.trim().ifBlank { "@Unknown[]" } }

/**
 * One value the setlist.fm form wants, ready to be handed over.
 *
 * [shown] and [value] differ for exactly one field — the songs, where the value is a
 * fourteen-line paste and what you want to *read* is "14 songs, in order". Everywhere
 * else they are the same string.
 */
data class FilingField(val label: String, val shown: String, val value: String)

/**
 * Everything the setlist.fm add form asks for, in the order it asks for it.
 *
 * The clipboard holds one thing. The form wants five, and the app screen that knows
 * them is not on screen once the browser is — so the night's facts were being carried
 * across the app switch in the Historian's head, which is where a wrong venue comes
 * from. These go into the notification shade instead, which is the one surface that
 * stays in reach while Chrome has the foreground.
 *
 * **The order is the form's, read off it on the Pixel 2026-08-06:** Add artist, Select
 * event date, Add venue, then the songs on the step after. Date before venue is not a
 * preference — the venue field is *disabled* until a date is set, and says so ("Select
 * event date before choosing a venue"). A tray that offered Venue first would be
 * offering a value with nowhere to go.
 *
 * Blank fields are dropped rather than posted empty: a night with no city known
 * should offer four values, not four and a lie.
 */
fun filingFields(setlist: FmSetlist, log: StoredLog): List<FilingField> = listOfNotNull(
    setlist.artist?.name?.takeIf { it.isNotBlank() }?.let { FilingField("Artist", it, it) },
    // Shown as "5 August 2026" rather than 05-08-2026, because this one is **picked,
    // not pasted** — verified on the Pixel: the field opens a calendar widget, so no
    // string can land in it. What the Historian actually does with this value is find
    // that day in a month grid, and a written-out month is the form of it that matches
    // the gesture. The raw date stays the copied value; it costs nothing and the
    // clipboard is the wrong place to editorialise.
    setlist.eventDate?.takeIf { it.isNotBlank() }
        ?.let { FilingField("Date", setlist.readableDate() ?: it, it) },
    setlist.venue?.name?.takeIf { it.isNotBlank() }?.let { FilingField("Venue", it, it) },
    setlist.venue?.city?.name?.takeIf { it.isNotBlank() }?.let { FilingField("City", it, it) },
    log.songs.takeIf { it.isNotEmpty() }?.let {
        FilingField(
            "Songs",
            // Gaps are counted in, because they are in the paste and will appear in
            // the form. "13 songs" beside a fourteen-line paste is the kind of small
            // disagreement that makes someone distrust the whole handoff.
            "${it.size} songs, in order" + if (log.gaps > 0) " · ${log.gaps} unnamed" else "",
            setlistPaste(log),
        )
    },
)

/**
 * A **Gig** this app minted rather than setlist.fm: a synthetic record carrying the
 * local **Gig** id, so every screen that already knows how to draw an `FmSetlist`
 * draws this one too.
 *
 * The lie, stated plainly: [FmSetlist.id] is a setlist.fm id everywhere else in this
 * app, and here it is not. It leaks in exactly two places and both are guarded —
 * anything that would *fetch* this id from setlist.fm (`refreshSelectedSetlist`), and
 * anything that assumes a `url` exists. `url` staying null is what makes the second
 * one detectable: **a local Gig is precisely a gig with no url**, which is also the
 * condition the setlist.fm nudge fires on. `TimelineCache.keyOf` already returns the
 * local id for a gig with no `setlistId`, so adoption later moves no data at all.
 */
fun localGigSetlist(
    gigId: String,
    artist: String,
    date: LocalDate,
    venue: String,
    city: String,
): FmSetlist = FmSetlist(
    id = gigId,
    eventDate = fmDate(date),
    artist = FmArtist(name = artist),
    venue = FmVenue(
        // Null, not "", for an unknown room (#128). Empty strings compare equal, so a
        // blank venue left as "" would make `sameFestival` cluster two nights that
        // merely both lack a venue — an unknown is not a place two gigs have in common.
        name = venue.ifBlank { null },
        city = FmCity(name = city.ifBlank { null }),
    ),
    // No songs, ever. What was played lives in the **Log**, which is a record of my
    // own observation and is deliberately not dressed up as a setlist.fm setlist —
    // that conflation is exactly how a partial capture starts looking complete.
    sets = null,
    url = null,
)

/** A gig this app minted rather than setlist.fm: the one thing that has no page. */
fun FmSetlist.isLocal(): Boolean = url == null

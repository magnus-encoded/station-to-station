package io.github.magnusencoded.setlist2spotify.data

import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmArtist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmCity
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmSetlist
import io.github.magnusencoded.setlist2spotify.data.setlistfm.FmVenue
// NIGHT_ENDS is the check-in window's own boundary and this has to draw the same
// line: an Act tapped at 01:30 belongs to the night that is still going on.
import io.github.magnusencoded.setlist2spotify.ui.NIGHT_ENDS
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A **Bill**: a **Festival** whose **Gigs** don't exist yet.
 *
 * The case this exists for is a festival that is not on setlist.fm at all *and*
 * cannot be, because the thing setlist.fm needs — which night each act plays — is
 * not known to anyone until the poster goes up on the noticeboard during the
 * festival. What *is* knowable in advance is the name, the venue, the date range
 * and the list of names. That is exactly the shape of this record.
 *
 * A **Bill** is not a list of planned gigs. `groupIntoFestivals` clusters on venue
 * *and date*, so an undated act cannot cluster with anything; and a planned gig
 * renders as a night you hold a ticket for, which an act on a hedged lineup is not.
 * Inventing a day per act so the existing machinery would work is precisely the
 * fabrication the record must not commit.
 *
 * [name] doubles as the venue name on the **Gigs** its **Acts** become. One field,
 * deliberately: it is what the **Festival** node reads afterwards
 * (`festivalName()` falls back to the venue), so the same string the user typed on
 * the poster is the one the timeline shows once the nights are real.
 */
@Serializable
data class StoredBill(
    val id: String = "",
    /** "Ringnes Festival 2026". Also the venue on every **Gig** this **Bill** mints. */
    val name: String = "",
    val city: String = "",
    /** dd-MM-yyyy, the shape setlist.fm sends — the range, which *is* known. */
    val from: String = "",
    val to: String = "",
    /** In poster order. Order is the only thing a lineup reliably carries. */
    val acts: List<StoredAct> = emptyList(),
)

/**
 * One name on a **Bill**.
 *
 * [maybe] is the hedge the poster itself makes. It is a property of the **Bill**,
 * never of a **Gig**: the moment an act is dated it played, so there is nothing
 * left to be unsure about and no "unconfirmed gig" state can ever be reached.
 *
 * [candidates] is what #93 asks for — a plausible song set to tick off rather than
 * type — fetched from this artist's recent setlists **while there is still signal**,
 * which is at home the night before and never in the field. Empty is the honest and
 * common answer for a small local act setlist.fm has never heard of.
 *
 * [gigId] is null until someone standing there says which night this played. Then it
 * is a local **Gig** (`createLocalGig`) and this act is done being an act.
 */
@Serializable
data class StoredAct(
    val name: String = "",
    val maybe: Boolean = false,
    val candidates: List<String> = emptyList(),
    val gigId: String? = null,
)

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

/** dd-MM-yyyy, the one date shape this app and setlist.fm both speak. */
private val FM_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH)

fun fmDate(date: LocalDate): String = date.format(FM_DATE)

fun parseFmDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.trim(), FM_DATE) }.getOrNull()

/**
 * The night an **Act** tapped at [now] belongs to. Before [NIGHT_ENDS] it is still
 * last night — you are walking out of the tent at half one and the act you are
 * logging played yesterday's date, which is the one moment this matters.
 *
 * ponytail: today or last night, nothing else. Logging Thursday's act on Saturday
 * is a date picker, and at a three-day festival you log the act as you leave it.
 */
fun billNight(now: LocalDateTime): LocalDate =
    if (now.toLocalTime() < NIGHT_ENDS) now.toLocalDate().minusDays(1) else now.toLocalDate()

/**
 * A pasted lineup, one **Act** per line.
 *
 * A line beginning `?` is a **Maybe** — the poster's own hedge, kept as the poster
 * made it. Blank lines and duplicates are dropped; leading bullets are tolerated
 * because a lineup is usually copied out of a PDF that had them.
 */
fun parseLineup(text: String): List<StoredAct> =
    text.lineSequence()
        .map { it.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val maybe = line.startsWith("?")
            StoredAct(name = line.removePrefix("?").trim(), maybe = maybe)
        }
        .filter { it.name.isNotEmpty() }
        .distinctBy { it.name.lowercase() }
        .toList()

/**
 * The songs an artist has been playing lately, most-played first — the pool an
 * **Act**'s setlist is ticked off from rather than typed.
 *
 * Frequency across the most recent setlists, not the single latest one: a set has a
 * stable core and a rotating edge, and the core is what is worth offering first.
 * Covers and tape tracks come through `performed()`'s filter already.
 */
fun candidateSongs(recent: List<FmSetlist>, take: Int = 4, limit: Int = 40): List<String> {
    val counts = LinkedHashMap<String, Int>()
    recent.take(take).forEach { set ->
        set.performed().forEach { song -> counts[song.name] = (counts[song.name] ?: 0) + 1 }
    }
    // Stable: LinkedHashMap keeps first-seen order, and sortedByDescending is stable,
    // so equal counts stay in the order the most recent setlist played them.
    return counts.entries.sortedByDescending { it.value }.map { it.key }.take(limit)
}

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
 */
@Serializable
data class StoredLog(
    val songs: List<String> = emptyList(),
    val closed: Boolean = false,
) {
    /** Songs actually named. A **Gap** is in the record but is not a title. */
    fun named(): List<String> = songs.filter { it.isNotBlank() }
    val gaps: Int get() = songs.count { it.isBlank() }
}

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
 * The **Gig** an **Act** becomes on the night it plays: a synthetic record carrying
 * the local **Gig** id, so every screen that already knows how to draw an `FmSetlist`
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
        name = venue,
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

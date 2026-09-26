package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.data.clashfinder.foldName
import io.github.magnusencoded.stationtostation.data.setlistfm.FmArtist
import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Collections

/**
 * How well one field of a setlist.fm hit agrees with a **Ticket** (#531, section 2).
 *
 * A level, not a score: the decision below only ever asks "is it Strong", and the
 * ranking only ever compares levels, so there is no number anywhere to tune.
 */
enum class MatchLevel { Strong, Weak, NoMatch }

/**
 * A setlist.fm hit that survived: its artist and date were at least [MatchLevel.Weak].
 * A hit that fails on either never becomes a candidate at all.
 *
 * [venue] is null when the **Ticket** named no venue. Nothing to compare is not a
 * mismatch — the spec's link rule reads "Strong on venue *or* the ticket has no
 * venue" — so it is kept apart from [MatchLevel.NoMatch] rather than folded into it.
 */
data class SetlistFmCandidate(
    val setlist: FmSetlist,
    val artist: MatchLevel,
    val date: MatchLevel,
    val venue: MatchLevel?,
)

/** What a **Ticket** looked up on setlist.fm comes to. The twin of iOS's `SetlistFmMatch`. */
sealed interface SetlistFmMatch {
    /** One hit clears the bar and nothing else is as good: adopt it without asking. */
    data class Linked(val candidate: SetlistFmCandidate) : SetlistFmMatch {
        val id: String get() = candidate.setlist.id
    }

    /**
     * Something survived but nothing is sure. At most [ASK_AT_MOST], best first; the
     * prompt adds "None of these" itself, because that is a choice, not a hit.
     */
    data class Ask(val candidates: List<SetlistFmCandidate>) : SetlistFmMatch

    /** Every hit was dropped, or there was nothing to look up with. */
    data object NoMatch : SetlistFmMatch
}

/** Three is what fits in the review prompt without it becoming a list to scroll. */
const val ASK_AT_MOST = 3

/**
 * Matches a parsed **Ticket** against what setlist.fm's `search/setlists` returned for
 * its artist and day (#531). Pure: the lookup, the clock and the **Line** are all the
 * caller's, which is what lets both platforms be held to one corpus
 * (`fixtures/setlistfm-match/`).
 *
 * [lineArtists] are the artists already on the person's **Line**, as setlist.fm named
 * them. It is there for one rule only: a hit whose MusicBrainz id is the one that
 * artist already carries is the same artist whatever the ticket vendor printed.
 *
 * **Link** is the magic path and the one this is shaped around: Strong on artist and
 * date, Strong on venue or no venue on the ticket, and no other hit as good. A tie at
 * the top is a question, never a coin toss — adoption sharpens a **Gig** (ADR-0002),
 * so it may only happen where nobody would have answered differently.
 */
fun matchSetlistFm(
    ticket: ParsedTicket,
    hits: List<FmSetlist>,
    lineArtists: List<FmArtist>,
): SetlistFmMatch {
    val ranked = rankSetlistFmHits(ticket, hits, lineArtists)
    val best = ranked.firstOrNull() ?: return SetlistFmMatch.NoMatch
    val clearsBar = best.artist == MatchLevel.Strong &&
        best.date == MatchLevel.Strong &&
        (best.venue == null || best.venue == MatchLevel.Strong)
    // Ranked, so anything as good as the best sits right behind it.
    val tied = ranked.getOrNull(1)?.let { it.levels() == best.levels() } == true
    return if (clearsBar && !tied) SetlistFmMatch.Linked(best) else SetlistFmMatch.Ask(ranked.take(ASK_AT_MOST))
}

/**
 * Every hit that survives, with its levels, best first. [matchSetlistFm] decides from
 * this; it is exposed so the corpus can assert the level of a hit the decision cut.
 *
 * Venue first, because that is what the spec ranks by and a hit in the same city is
 * the likelier upgrade than one elsewhere. Artist and then date break a tie in venue,
 * and setlist.fm's own order breaks whatever is left, so the same hits always rank
 * the same way on both platforms.
 */
fun rankSetlistFmHits(
    ticket: ParsedTicket,
    hits: List<FmSetlist>,
    lineArtists: List<FmArtist>,
): List<SetlistFmCandidate> {
    // A lookup needs an artist and a day. Without one there is nothing to hold a hit
    // to, and a hit held to nothing is not a match — it is just a setlist.
    val artist = ticket.artist?.let(::artistWords)?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val night = ticket.date?.let(::parseFmDate) ?: return emptyList()

    // "That artist on the Line" is the one whose name folds to the ticket's.
    val knownIds = lineArtists
        .filter { it.mbid.isNotBlank() && sameWords(artistWords(it.name), artist) }
        .map { it.mbid }
        .toSet()

    val survivors = hits.mapNotNull { hit ->
        val a = artistLevel(artist, knownIds, hit) ?: return@mapNotNull null
        val d = dateLevel(night, hit) ?: return@mapNotNull null
        Triple(hit, a, d)
    }

    val venue = ticket.venue?.takeIf { it.isNotBlank() }
    val candidates = if (venue == null) {
        survivors.map { (hit, a, d) -> SetlistFmCandidate(hit, a, d, venue = null) }
    } else {
        val sameRoom = survivors.map { (hit) -> sameRoom(venue, hit) }
        val ticketCities = ticketCities(venue, survivors.map { it.first }, sameRoom)
        survivors.mapIndexed { i, (hit, a, d) ->
            val level = when {
                sameRoom[i] -> MatchLevel.Strong
                // No city on the ticket and none learned from a hit: the question is
                // asked either way, so the hit keeps the benefit of the doubt rather
                // than being ranked as though it were known to be elsewhere.
                ticketCities.isEmpty() -> MatchLevel.Weak
                cityOf(hit) in ticketCities -> MatchLevel.Weak
                else -> MatchLevel.NoMatch
            }
            SetlistFmCandidate(hit, a, d, level)
        }
    }
    return candidates.sortedWith(
        compareBy<SetlistFmCandidate> { it.venue?.ordinal ?: 0 }
            .thenBy { it.artist.ordinal }
            .thenBy { it.date.ordinal },
    )
}

private fun SetlistFmCandidate.levels() = Triple(artist, date, venue)

/**
 * Strong: the same name after folding, or the MusicBrainz id that artist already has
 * on the **Line**. Weak: one name holds the other, word for word. Null drops the hit.
 */
private fun artistLevel(ticket: List<String>, knownIds: Set<String>, hit: FmSetlist): MatchLevel? {
    val mbid = hit.artist?.mbid.orEmpty()
    if (mbid.isNotBlank() && mbid in knownIds) return MatchLevel.Strong
    val name = artistWords(hit.artist?.name.orEmpty())
    return when {
        sameWords(name, ticket) -> MatchLevel.Strong
        holds(name, ticket) || holds(ticket, name) -> MatchLevel.Weak
        else -> null
    }
}

/**
 * Strong on the day, Weak a day either side — a ticket dated by the doors, a set that
 * ran past midnight. Anything further is another night, and the hit is dropped.
 */
private fun dateLevel(night: LocalDate, hit: FmSetlist): MatchLevel? {
    val day = hit.localDate() ?: return null
    return when (ChronoUnit.DAYS.between(night, day)) {
        0L -> MatchLevel.Strong
        -1L, 1L -> MatchLevel.Weak
        else -> null
    }
}

/** The same room: equal after folding, or one name holding the other. */
private fun sameRoom(venue: String, hit: FmSetlist): Boolean {
    val ticket = roomWords(venue, hit)
    val room = roomWords(hit.venue?.name.orEmpty(), hit)
    if (ticket.isEmpty() || room.isEmpty()) return false
    return sameWords(ticket, room) || holds(ticket, room) || holds(room, ticket)
}

/**
 * Where the **Ticket** says the night is. A ticket has no city field, so this is what
 * can be read off it: a trailing `, City` that names a city a hit is in, and the city
 * of any hit already in the same room. Empty when neither says anything.
 */
private fun ticketCities(venue: String, hits: List<FmSetlist>, sameRoom: List<Boolean>): Set<String> {
    val known = hits.mapNotNull(::cityOf).toSet()
    val suffix = venue.substringAfterLast(',', "").let(::foldWords).joinToString("")
    val fromTicket = setOfNotNull(suffix.takeIf { it.isNotEmpty() && it in known })
    val fromRoom = hits.filterIndexed { i, _ -> sameRoom[i] }.mapNotNull(::cityOf)
    return fromTicket + fromRoom
}

private fun cityOf(hit: FmSetlist): String? =
    foldWords(hit.venue?.city?.name.orEmpty()).joinToString("").takeIf { it.isNotEmpty() }

/**
 * A venue name with a trailing `, Oslo` (or `, Norway`) taken off when it names the
 * hit's own city or country, so `Sentrum Scene, Oslo` = `Sentrum Scene`. Only a
 * segment that *is* the place comes off — `Rockefeller, Torggata 16` keeps its street.
 */
private fun roomWords(name: String, hit: FmSetlist): List<String> {
    val places = listOfNotNull(hit.venue?.city?.name, hit.venue?.city?.country?.name)
        .map { foldWords(it).joinToString("") }
        .filter { it.isNotEmpty() }
    val segments = name.split(',').toMutableList()
    while (segments.size > 1 && foldWords(segments.last()).joinToString("") in places) {
        segments.removeAt(segments.lastIndex)
    }
    return foldWords(segments.joinToString(","))
}

/**
 * An artist name as this matcher compares it. The one place to change if the owner
 * picks `nameKey`'s rule instead (#531, second comment): stripping a trailing
 * parenthetical here makes `Wilco (US)` = `Wilco` Strong, where the spec as written
 * rates it Weak and asks.
 */
private fun artistWords(name: String): List<String> = foldWords(name)

/**
 * The spec's folding — case, accents (ø/o), punctuation, whitespace — kept as words so
 * "one holds the other" can mean whole words: `Owl` is in `Owl Choir` and not in
 * `Owls`. Each word goes through [foldName], so Nordic letters fold exactly as they do
 * for a festival search. `&` reads as `and`, the one spelling difference that is a
 * word rather than a mark.
 */
private fun foldWords(text: String): List<String> =
    text.replace("&", " and ").split(NOT_A_WORD).map(::foldName).filter { it.isNotEmpty() }

// Marks stay inside a word so a decomposed ø or å is folded, not split on.
private val NOT_A_WORD = Regex("[^\\p{L}\\p{N}\\p{M}]+")

/** Equal once spacing is off too: `Kjøkken Hagen` and `Kjøkkenhagen` are one name. */
private fun sameWords(a: List<String>, b: List<String>): Boolean =
    a.isNotEmpty() && a.joinToString("") == b.joinToString("")

/** [outer] holds [inner] as a run of whole words. */
private fun holds(outer: List<String>, inner: List<String>): Boolean =
    inner.isNotEmpty() && Collections.indexOfSubList(outer, inner) >= 0

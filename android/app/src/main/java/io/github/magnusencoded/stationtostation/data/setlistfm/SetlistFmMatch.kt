package io.github.magnusencoded.stationtostation.data.setlistfm

import io.github.magnusencoded.stationtostation.data.parseFmDate
import java.text.Normalizer
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * What a setlist.fm lookup for a **Ticket** has to go on (#531): at least an artist and a
 * night, and the venue when the ticket printed one. [date] is `dd-MM-yyyy`, the one shape
 * both platforms store a night in. Plain fields rather than the **Ticket** itself, so the
 * matcher does not move every time the ticket's own shape does.
 */
data class TicketLookup(
    val artist: String,
    val venue: String?,
    val date: String,
)

/** How well one field of a hit agrees with the **Ticket**. Declared best first. */
enum class MatchLevel { STRONG, WEAK, NONE }

/**
 * One hit's level on each field. [venue] is null when the ticket has no venue to compare,
 * which is neither a match nor a conflict. A hit whose [artist] or [date] is
 * [MatchLevel.NONE] is dropped: it is a different night, not a worse candidate.
 */
data class FieldLevels(
    val artist: MatchLevel,
    val date: MatchLevel,
    val venue: MatchLevel?,
) {
    val survives: Boolean get() = artist != MatchLevel.NONE && date != MatchLevel.NONE

    /** Linked without a question: Strong on artist and date, and on venue if there is one. */
    val meetsTheBar: Boolean
        get() = artist == MatchLevel.STRONG && date == MatchLevel.STRONG &&
            (venue == null || venue == MatchLevel.STRONG)
}

/** A surviving hit and the levels it earned. */
data class SetlistFmCandidate(val setlist: FmSetlist, val levels: FieldLevels)

/** What a lookup's hits come to. */
sealed interface SetlistFmMatch {
    /** The one hit that meets the bar, with nothing else as good. No question asked. */
    data class Linked(val candidate: SetlistFmCandidate) : SetlistFmMatch

    /** At most [MAX_ASKED] hits, best first, for the person to choose among or refuse. */
    data class Ask(val candidates: List<SetlistFmCandidate>) : SetlistFmMatch

    /** Every hit was dropped, or there were none. */
    data object NoMatch : SetlistFmMatch
}

/** The question offers this many hits at most, plus "None of these". */
const val MAX_ASKED = 3

/**
 * Whether a **Ticket** links to one of setlist.fm's `search/setlists` hits, asks about
 * them, or matches none (#531, section 2). Levels, not a hit count: one hit is not proof
 * and two are not doubt, so what decides is how well the best hit agrees, field by field.
 *
 * **Linked** is the magic path and the one to keep wide: the best hit is Strong on artist
 * and date, Strong on venue or the ticket has none, and no other hit has the same levels.
 * **Ask** is everything that survives short of that, including two hits tied at the top.
 * A venue conflict is never read as the ticket being right; setlist.fm may know about the
 * sell-out upgrade the ticket predates, which is why a Weak venue asks rather than drops.
 *
 * The ranking is venue first, as the spec has it, then artist, then date, then the order
 * setlist.fm sent. Venue's own order already puts the same city before a different one.
 *
 * [lineArtists] are the artists on the person's **Line**: a hit carrying the MusicBrainz
 * id the **Line** holds for the ticket's artist is Strong however setlist.fm spells it.
 */
fun matchSetlistFm(
    ticket: TicketLookup,
    hits: List<FmSetlist>,
    lineArtists: List<FmArtist>,
): SetlistFmMatch {
    val ranked = hits
        .map { SetlistFmCandidate(it, fieldLevels(ticket, it, lineArtists)) }
        .filter { it.levels.survives }
        // A stable sort, so hits that level alike keep setlist.fm's order.
        .sortedWith(
            compareBy<SetlistFmCandidate> { it.levels.venue?.ordinal ?: 0 }
                .thenBy { it.levels.artist.ordinal }
                .thenBy { it.levels.date.ordinal },
        )
    val best = ranked.firstOrNull() ?: return SetlistFmMatch.NoMatch
    val tied = ranked.getOrNull(1)?.levels == best.levels
    if (best.levels.meetsTheBar && !tied) return SetlistFmMatch.Linked(best)
    return SetlistFmMatch.Ask(ranked.take(MAX_ASKED))
}

/** One hit's level on each field. Public so the shared fixtures can assert them. */
fun fieldLevels(ticket: TicketLookup, hit: FmSetlist, lineArtists: List<FmArtist>): FieldLevels =
    FieldLevels(
        artist = artistLevel(ticket.artist, hit.artist, lineArtists),
        date = dateLevel(ticket.date, hit.eventDate),
        venue = ticket.venue?.takeIf { it.isNotBlank() }?.let { venueLevel(it, hit.venue) },
    )

/**
 * Strong: the same name after folding, or the MusicBrainz id the **Line** holds for the
 * ticket's artist. Weak: one name contains the other, word for word — `Wilco (US)` and
 * `Wilco`, never `Wilco` inside `Wilcox`.
 */
private fun artistLevel(ticketArtist: String, hit: FmArtist?, lineArtists: List<FmArtist>): MatchLevel {
    val mine = words(ticketArtist)
    val theirs = words(hit?.name.orEmpty())
    if (mine.isEmpty()) return MatchLevel.NONE
    if (theirs.isNotEmpty() && sameName(mine, theirs)) return MatchLevel.STRONG
    val mbid = hit?.mbid.orEmpty()
    if (mbid.isNotBlank() && lineArtists.any { it.mbid == mbid && sameName(words(it.name), mine) }) {
        return MatchLevel.STRONG
    }
    return if (contains(mine, theirs) || contains(theirs, mine)) MatchLevel.WEAK else MatchLevel.NONE
}

/**
 * Strong: the same day. Weak: a day either side, such as a ticket dated by doors after
 * midnight. Both sides are `dd-MM-yyyy` text parsed the
 * same way, so no time zone can move either one (ADR-0002).
 */
private fun dateLevel(ticketDate: String, eventDate: String?): MatchLevel {
    val mine = parseFmDate(ticketDate) ?: return MatchLevel.NONE
    val theirs = eventDate?.let(::parseFmDate) ?: return MatchLevel.NONE
    return when (abs(ChronoUnit.DAYS.between(mine, theirs))) {
        0L -> MatchLevel.STRONG
        1L -> MatchLevel.WEAK
        else -> MatchLevel.NONE
    }
}

/**
 * Strong: the same room after folding, or one name containing the other (`Rockefeller`,
 * `Rockefeller Music Hall`). Weak: another room in the ticket's city. None: anything else,
 * which is a different city or a city the ticket never said.
 *
 * A ticket names its city only as a suffix — `Sentrum Scene, Oslo` or `John Dee Oslo` —
 * so that is where the city is read from, and it is folded off before the rooms compare.
 */
private fun venueLevel(ticketVenue: String, hit: FmVenue?): MatchLevel {
    val (room, suffix) = splitCitySuffix(ticketVenue)
    val mine = words(room)
    val theirs = words(splitCitySuffix(hit?.name.orEmpty()).first)
    if (mine.isNotEmpty() && theirs.isNotEmpty() &&
        (sameName(mine, theirs) || contains(mine, theirs) || contains(theirs, mine))
    ) {
        return MatchLevel.STRONG
    }
    val city = words(hit?.city?.name.orEmpty())
    if (city.isEmpty()) return MatchLevel.NONE
    val sameCity = (suffix != null && sameName(words(suffix), city)) ||
        (mine.size > city.size && mine.takeLast(city.size) == city)
    return if (sameCity) MatchLevel.WEAK else MatchLevel.NONE
}

/** `Sentrum Scene, Oslo` as the room and what follows its last comma. */
private fun splitCitySuffix(venue: String): Pair<String, String?> {
    val comma = venue.lastIndexOf(',')
    if (comma <= 0) return venue to null
    return venue.substring(0, comma) to venue.substring(comma + 1)
}

/**
 * A name as folded words. Words, so containment respects them; folded one by one through
 * [foldName], so the fold is the one the rest of the app matches names by. `&` reads as
 * `and`, the same substitution [io.github.magnusencoded.stationtostation.data.nameKey]
 * makes.
 */
private fun words(text: String): List<String> =
    text.replace("&", " and ").split(WORD_BREAK).map(::foldName).filter { it.isNotEmpty() }

private val WORD_BREAK = Regex("[^\\p{L}\\p{M}\\p{N}]+")

/** Equal once the spacing is gone too, so `AC/DC` is `ACDC` and `Melody's` is `Melodys`. */
private fun sameName(a: List<String>, b: List<String>): Boolean = a.joinToString("") == b.joinToString("")

/** [outer] holds [inner] as a run of whole words. */
private fun contains(outer: List<String>, inner: List<String>): Boolean =
    inner.isNotEmpty() && outer.size >= inner.size && outer.windowed(inner.size).any { it == inner }

/**
 * A name reduced to what two spellings of it have in common: case, diacritics,
 * punctuation and spacing off. The twin of iOS's `foldName` (`Clashfinder.swift`), and
 * like it the Nordic letters are listed out, because NFD splits å into a and a ring but
 * leaves ø and æ as they were — normalising alone would fail on exactly the names this
 * app is full of.
 *
 * ponytail: `nameKey` in `Departures.kt` still folds without this table, where the iOS
 * `nameKey` is built on `foldName`. Moving it over changes stored act keys, so it is its
 * own change.
 */
fun foldName(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val out = StringBuilder()
    var i = 0
    while (i < decomposed.length) {
        val cp = decomposed.codePointAt(i)
        i += Character.charCount(cp)
        if (Character.getType(cp).toByte() in COMBINING) continue
        val folded = FOLDED_LETTERS[cp]
        when {
            folded != null -> out.append(folded)
            Character.isAlphabetic(cp) || Character.isDigit(cp) -> out.appendCodePoint(cp)
        }
    }
    return out.toString()
}

private val COMBINING = setOf(
    Character.NON_SPACING_MARK,
    Character.COMBINING_SPACING_MARK,
    Character.ENCLOSING_MARK,
)

private val FOLDED_LETTERS: Map<Int, String> = mapOf(
    'ø'.code to "o", 'æ'.code to "ae", 'ß'.code to "ss",
    'ł'.code to "l", 'đ'.code to "d", 'ð'.code to "d",
)

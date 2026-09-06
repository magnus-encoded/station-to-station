package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.Locale

/** [StoredAttendance.ticketQr] is base64; these two are the only place that turns it. */
fun ByteArray.toTicketQrBase64(): String = Base64.getEncoder().encodeToString(this)

fun String.decodeTicketQrBase64(): ByteArray? = runCatching { Base64.getDecoder().decode(this) }.getOrNull()

/**
 * What one rasterized page handed back, before any judgment is made about it (#411).
 *
 * [qrBytes] is whatever zxing decoded from the page image, raw. [textBlocks] is ML
 * Kit's OCR result, one entry per recognised text block, in the order it read them —
 * which for most tickets is roughly top-to-bottom, but nothing here relies on that
 * being exact. Both are independently optional: a scanned ticket with no text layer
 * still has pixels a QR decoder can read, and a QR-less confirmation email still has
 * words on it.
 */
data class TicketExtract(
    val qrBytes: ByteArray? = null,
    val textBlocks: List<String> = emptyList(),
) {
    // ByteArray has no structural equals/hashCode; generated data class ones do not
    // do a content-comparison, which silently breaks `==` in tests. Ticket payloads
    // are compared by content everywhere else in this app for the same reason (#28's
    // fingerprints), so this class earns its own equals rather than surprising a test.
    override fun equals(other: Any?): Boolean =
        other is TicketExtract &&
            qrBytes.contentEqualsOrBothNull(other.qrBytes) &&
            textBlocks == other.textBlocks

    override fun hashCode(): Int = (qrBytes?.contentHashCode() ?: 0) * 31 + textBlocks.hashCode()
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == other else contentEquals(other)

/**
 * The pipeline's best guess, reported honestly rather than decided on (#411 comment
 * clarifying #408's spec). Every field is independently nullable — a QR with no
 * readable date is not "half a failure", it is exactly what it says.
 *
 * [date] is dd-MM-yyyy, the one shape this app and setlist.fm both speak (see
 * [fmDate]/[parseFmDate] in Bill.kt).
 */
data class ParsedTicket(
    val qrBytes: ByteArray? = null,
    val artist: String? = null,
    val venue: String? = null,
    val date: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is ParsedTicket &&
            qrBytes.contentEqualsOrBothNull(other.qrBytes) &&
            artist == other.artist &&
            venue == other.venue &&
            date == other.date

    override fun hashCode(): Int =
        listOf(qrBytes?.contentHashCode(), artist, venue, date).hashCode()

    /** Nothing at all came out of the page — the honest "couldn't read this" (story 9). */
    val isEmpty: Boolean get() = qrBytes == null && artist == null && venue == null && date == null

    /** QR + artist + venue + date, all present — the only case allowed to skip the prompt. */
    val isComplete: Boolean get() = qrBytes != null && artist != null && venue != null && date != null
}

/**
 * Reads whatever [TicketExtract] handed back and reports it — never a decision about
 * what to do with it. That split is #408's own spec: the parser's job stops at "here
 * is what I found", and every caller (the ViewModel, the confirm dialog) decides from
 * there.
 *
 * Deliberately generic: no ticketing vendor's layout is assumed. A date is found by
 * trying a handful of common shapes against every text block in turn; the first block
 * that isn't the date line is guessed as the artist, the next as the venue. This is a
 * weak heuristic on purpose — a wrong guess here is caught by the confirm step every
 * caller is required to show (see [routeTicket]), so overfitting it to look smarter
 * than it is would only hide how little it actually knows.
 */
fun parseTicket(extract: TicketExtract): ParsedTicket {
    var date: String? = null
    var dateLineIndex = -1
    extract.textBlocks.forEachIndexed { index, block ->
        if (date == null) {
            val found = findDate(block)
            if (found != null) {
                date = found
                dateLineIndex = index
            }
        }
    }
    val remaining = extract.textBlocks
        .filterIndexed { index, block -> index != dateLineIndex && block.isNotBlank() }
        .map { it.trim() }
    // A vendor commonly styles the event/venue line in caps ("SKAMBANKT",
    // "PARKTEATRET SCENE") while a banner or instructional line reads as ordinary
    // sentence-case prose ("Dette er din billett", "Please retain this ticket") —
    // and OCR reading order puts that banner first on plenty of real tickets. Without
    // this, "first two non-date lines" confidently hands the banner and the small
    // print to the confirm dialog instead of the actual artist and venue. Neither
    // line is discarded, only reordered, so a ticket with no caps-styled line at all
    // still gets the old "first two" answer.
    //
    // Caps alone isn't enough on a real ticket bundled with a marketing insert: a
    // booking code ("OPT2901") and an ad's own tagline ("DEL EN OPPLEVELSE!") are
    // *also* shouty, and OCR read both ahead of the real event line on the ticket
    // this heuristic was first written against — confirmed against the real ML Kit
    // output, not guessed from a PDF's embedded text layer. [isShoutyLabel] additionally
    // excludes anything with a digit (an event/venue name essentially never carries
    // one; a code or address always does), anything ending in the vendor's own
    // structural punctuation (":", "!", "?") — a heading or an ad's tagline, not a
    // name — and anything with a "/" (a seating category like "STÅPLASS/STANDING" or
    // a combined entrance like "Inngang 2/Inngang 4", never an event or venue name).
    //
    // A second real ticket (an Eventim one) has no shouty line at all — its artist
    // and venue print in ordinary title case. There [isShoutyLabel] rightly excludes
    // everything, and the fallback below is what actually finds the answer: on that
    // layout the date sits **sandwiched** between the artist just before it and the
    // venue just after it, skipping the vendor's own label lines (a trailing ":",
    // e.g. "Stageway, ATL & Ramalama presenterer:") on either side.
    //
    // That sandwich only fires when both neighbours exist — deliberately, since on a
    // ticket where the date is the *last* content line (most synthetic and plenty of
    // real ones), there is no line after it to be a venue, and "closest line before
    // the date" would otherwise wrongly grab whatever second line is directly above
    // it instead of the actual first line. Requiring both sides present is what keeps
    // this narrower and more reliable than "first two non-date lines" instead of just
    // a different way to be wrong.
    val (shouty, prose) = remaining.partition { isShoutyLabel(it) }
    val sandwichArtist = dateLineIndex.takeIf { it != -1 }
        ?.let { extract.textBlocks.subList(0, it).lastOrNull { line -> line.isUsableNeighbourLine() } }
    val sandwichVenue = dateLineIndex.takeIf { it != -1 }
        ?.let { extract.textBlocks.subList(it + 1, extract.textBlocks.size).firstOrNull { line -> line.isUsableNeighbourLine() } }
    val artist: String?
    val venue: String?
    if (shouty.isEmpty() && sandwichArtist != null && sandwichVenue != null) {
        artist = sandwichArtist.trim()
        venue = sandwichVenue.trim()
    } else {
        val ordered = shouty + prose
        artist = ordered.getOrNull(0)
        venue = ordered.getOrNull(1)
    }
    return ParsedTicket(qrBytes = extract.qrBytes, artist = artist, venue = venue, date = date)
}

/** A blank line or a vendor's own label line (ending ":") is never the neighbour's answer. */
private fun String.isUsableNeighbourLine(): Boolean = isNotBlank() && !trim().endsWith(":")

/**
 * At least four letters in five uppercase, ignoring non-letters — a vendor's
 * stylised event/venue line, not the prose above or below it on the page. Needs at
 * least two letters at all so a bare separator or order-number line never qualifies.
 *
 * Further exclusions, all confirmed against real tickets' actual OCR output rather
 * than guessed: a digit anywhere rules a line out (a booking code or an address
 * carries one; an event/venue name essentially never does), so does a trailing ":",
 * "!" or "?" (a heading a value sits under, or an ad's own tagline — never the name
 * itself), and so does a "/" (a seating category or a combined entrance, always an
 * enumeration of alternatives rather than a name).
 */
private fun isShoutyLabel(text: String): Boolean {
    val letters = text.filter { it.isLetter() }
    if (letters.length < 2) return false
    if (text.any { it.isDigit() }) return false
    if (text.contains('/')) return false
    if (text.trimEnd().lastOrNull() in setOf(':', '!', '?')) return false
    val upper = letters.count { it.isUpperCase() }
    return upper.toDouble() / letters.length >= 0.8
}

private val DATE_PATTERNS: List<Pair<Regex, DateTimeFormatter>> = listOf(
    // dd-MM-yyyy / dd.MM.yyyy / dd/MM/yyyy
    Regex("""\b(\d{1,2})[-./](\d{1,2})[-./](\d{4})\b""") to
        DateTimeFormatter.ofPattern("d-M-yyyy", Locale.ENGLISH),
    // yyyy-MM-dd (ISO, common in confirmation emails)
    Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""") to
        DateTimeFormatter.ofPattern("yyyy-M-d", Locale.ENGLISH),
)

// "24th June 2026" / "24 June 2026" — the ordinal suffix is stripped before parsing,
// since java.time has no pattern letter for it.
private val LONG_DATE = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]+)\s+(\d{4})\b""")
private val LONG_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

// "June 24, 2026" / "June 24 2026"
private val US_DATE = Regex("""\b([A-Za-z]+)\s+(\d{1,2}),?\s+(\d{4})\b""")
private val US_DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH)

// "28. nov. 2026" / "28 nov 2026" — a real ticket's own shape (Eventim), Norwegian
// vendors' three-letter month abbreviations with an optional trailing period rather
// than a full month name, which java.time's Norwegian locale data doesn't reliably
// match on every Android version. Mapped by hand instead of through a
// DateTimeFormatter for that reason — this is the only date shape here that is.
private val NB_SHORT_DATE = Regex("""\b(\d{1,2})\.?\s+([A-Za-zæøåÆØÅ]{3,})\.?\s+(\d{4})\b""")
private val NB_MONTHS = mapOf(
    "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mai" to 5, "jun" to 6,
    "jul" to 7, "aug" to 8, "sep" to 9, "okt" to 10, "nov" to 11, "des" to 12,
)

/** dd-MM-yyyy, whatever shape the source text used. Null when nothing in [text] parses. */
internal fun findDate(text: String): String? {
    for ((regex, format) in DATE_PATTERNS) {
        regex.find(text)?.let { match ->
            val (a, b, c) = match.destructured
            val candidate = "$a-$b-$c"
            parseWith(candidate, format)?.let { return fmDate(it) }
        }
    }
    LONG_DATE.find(text)?.let { match ->
        val (day, month, year) = match.destructured
        parseWith("$day $month $year", LONG_DATE_FORMAT)?.let { return fmDate(it) }
    }
    US_DATE.find(text)?.let { match ->
        val (month, day, year) = match.destructured
        parseWith("$month $day $year", US_DATE_FORMAT)?.let { return fmDate(it) }
    }
    NB_SHORT_DATE.find(text)?.let { match ->
        val (day, month, year) = match.destructured
        val monthNumber = NB_MONTHS[month.take(3).lowercase(Locale.ROOT)]
        if (monthNumber != null) {
            runCatching { LocalDate.of(year.toInt(), monthNumber, day.toInt()) }.getOrNull()?.let { return fmDate(it) }
        }
    }
    return null
}

private fun parseWith(text: String, format: DateTimeFormatter): LocalDate? =
    try {
        LocalDate.parse(text, format)
    } catch (e: DateTimeParseException) {
        null
    }

/**
 * What a parsed ticket resolves to, once matched against the nights this app already
 * knows about. The three-way split is #411's clarified spec, verbatim: only a full,
 * unambiguous parse may act without a person looking at it first; everything else —
 * including a total miss — puts the guess in front of the user instead of deciding
 * for them.
 */
sealed interface TicketRouting {
    /** A future- or past-dated night this app already has a record of — a match, not a duplicate. */
    data class AlreadyKnown(val gig: FmSetlist) : TicketRouting

    /** A complete, unmatched guess for a future night — goes straight onto the plan. */
    data class NewPlannedGig(
        val artist: String,
        val venue: String,
        val date: String,
        val qrBytes: ByteArray?,
    ) : TicketRouting

    /**
     * Anything short of a full unambiguous parse: some fields missing, or a possible
     * (not certain) match. Always shown to the user before anything is written —
     * including the case where [parsed] is entirely empty, which the confirm screen
     * reads as "couldn't read this ticket" rather than a silent no-op.
     */
    data class NeedsConfirmation(val parsed: ParsedTicket, val possibleMatch: FmSetlist?) : TicketRouting
}

/**
 * Matches a parsed guess against nights this app already has a record of — attended
 * or planned, local or setlist.fm's own. Keyed on date + artist, not venue: a venue
 * printed on a ticket ("The Forum") rarely matches setlist.fm's formatted line ("The
 * Forum, London, England"), so trying to string-match it would reject real matches
 * more often than it would catch a false one. Case-insensitive on the artist name,
 * since a ticket vendor's capitalisation is not a fact worth failing a match over.
 */
fun matchKnownNight(parsed: ParsedTicket, knownGigs: List<FmSetlist>): FmSetlist? {
    val date = parsed.date ?: return null
    val artist = parsed.artist?.trim()?.lowercase(Locale.ROOT) ?: return null
    return knownGigs.firstOrNull { candidate ->
        candidate.eventDate == date && candidate.artist?.name?.trim()?.lowercase(Locale.ROOT) == artist
    }
}

/**
 * Turns a [ParsedTicket] into a routing decision, per #411's clarified spec: only a
 * complete parse (QR + artist + venue + date) may skip the confirm step, and only
 * when it either clearly matches an existing night or clearly doesn't. Everything
 * else — a partial parse, or nothing at all — is [TicketRouting.NeedsConfirmation],
 * never a silent add and never a silent drop.
 *
 * A complete, unmatched parse for a **past** date is also routed to confirmation
 * rather than minted as a plan (story 13): the local-planned-gig path means "I'm
 * going", and an old ticket found while cleaning out email is not that. [today]
 * defaults to the real clock and exists only so a test can pin it.
 */
fun routeTicket(
    parsed: ParsedTicket,
    knownGigs: List<FmSetlist>,
    today: LocalDate = LocalDate.now(),
): TicketRouting {
    val match = matchKnownNight(parsed, knownGigs)
    if (parsed.isComplete) {
        if (match != null) return TicketRouting.AlreadyKnown(match)
        val night = parseFmDate(parsed.date!!)
        if (night != null && night.isAfter(today)) {
            return TicketRouting.NewPlannedGig(parsed.artist!!, parsed.venue!!, parsed.date, parsed.qrBytes)
        }
    }
    return TicketRouting.NeedsConfirmation(parsed, match)
}

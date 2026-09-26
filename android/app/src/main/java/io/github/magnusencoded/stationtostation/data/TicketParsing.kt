package io.github.magnusencoded.stationtostation.data

import io.github.magnusencoded.stationtostation.data.setlistfm.FmSetlist
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Base64
import java.util.Locale

/** [StoredAdmission.payload] is base64; these two are the only place that turns it. */
fun ByteArray.toAdmissionBase64(): String = Base64.getEncoder().encodeToString(this)

fun String.decodeAdmissionBase64(): ByteArray? = runCatching { Base64.getDecoder().decode(this) }.getOrNull()

// --- Evidence ---
//
// The Kotlin twin of `Ticket.swift`'s evidence types (#526), one to one. Nothing in
// this file imports Android, PdfRenderer, ML Kit or zxing: the extractor
// (TicketExtraction.kt) hands these over, and `parseTicketFields` is the only code
// that makes a judgement about them — which is what lets `fixtures/ticket/` drive it
// on the JVM with the same inputs iOS runs.

/** What any source handed back, before any judgement is made about it (#526). */
data class TicketEvidence(
    /** One reading per method that produced any text. */
    val readings: List<TicketReading>,
    /**
     * Every barcode the source showed, in the order found. A ticket can carry several
     * (one Admission each, or a QR beside a Code 128), so the evidence keeps them all;
     * which of them are Admissions is [parseTicketFields]'s call alone (#441).
     */
    val barcodes: List<TicketBarcode> = emptyList(),
)

/** One method's reading of the whole source. */
data class TicketReading(val origin: Origin, val lines: List<String>) {
    enum class Origin { TEXT_LAYER, OCR }
}

/**
 * The barcode as it appears on the ticket, plus what it says when that can be decoded.
 *
 * [image] is a PNG crop of the detected bounds, padded to include the quiet zone.
 * [payload] is the decoded text as UTF-8 (#534), null when the symbology cannot be
 * decoded. [symbology] is `fixtures/ticket/README.md`'s name for it — `qr`, `code128`,
 * `ean13` and so on — not zxing's. [page] is the zero-based page it was found on, when
 * the source has pages, and read as page 0 when null; it orders the Admissions (#441).
 */
class TicketBarcode(
    val image: ByteArray,
    val payload: ByteArray?,
    val symbology: String?,
    val page: Int? = null,
) {
    // ByteArray has no structural equals; compared by content, as ticket payloads are
    // everywhere else in this app (#28's fingerprints).
    override fun equals(other: Any?): Boolean =
        other is TicketBarcode &&
            image.contentEquals(other.image) &&
            payload.contentEqualsOrBothNull(other.payload) &&
            symbology == other.symbology &&
            page == other.page

    override fun hashCode(): Int =
        listOf(image.contentHashCode(), payload?.contentHashCode(), symbology, page).hashCode()

    override fun toString(): String =
        "TicketBarcode(symbology=$symbology, page=$page, payload=${payload?.size} bytes, image=${image.size} bytes)"
}

/**
 * Turns one kind of input into evidence. [S] is the input type: a shared PDF's `Uri`
 * today, and later possibly an image, a web page or an IPC payload.
 *
 * An extractor knows about renderers and recognisers and makes no judgement about
 * what the words mean. That is [parseTicketFields]'s job alone, so a new source is
 * one more extractor and no new rule.
 */
fun interface TicketExtractor<in S> {
    suspend fun extract(source: S): TicketEvidence
}

/**
 * The whole of ticket reading: a source through its extractor, then through the one
 * function that picks fields.
 */
suspend fun <S> parseTicket(source: S, extractor: TicketExtractor<S>): ParsedTicket =
    parseTicketFields(extractor.extract(source))

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == other else contentEquals(other)

/** Where a field of a [ParsedTicket] came from: both readings, or only one of them. */
enum class TicketSupport {
    BOTH, TEXT_LAYER, OCR;

    companion object {
        fun of(origin: TicketReading.Origin): TicketSupport = when (origin) {
            TicketReading.Origin.TEXT_LAYER -> TEXT_LAYER
            TicketReading.Origin.OCR -> OCR
        }
    }
}

/**
 * One scannable barcode — the right of entry for one person (#441, `CONTEXT.md`). A
 * **Ticket** yields one or more.
 *
 * [payload] is the decoded payload, byte for byte as the evidence carried it. [symbology]
 * is `fixtures/ticket/README.md`'s name for the format it was printed in (`qr`,
 * `code128`, …). [page] is the zero-based page it was first found on. [corroborated]
 * says the ticket's own text prints the same code — evidence recorded, never a reason
 * to drop one that isn't.
 *
 * [redrawable] is the check at import (story 29, [checkedForRedraw]): the Admission
 * redrawn in its own symbology read back as the same payload. Null until asked — the
 * parse never asks — and null counts as no. Never stored: [StoredAdmission] has no field
 * for it, and the Room asks again ([doorDrawing]) rather than trust a verdict written by
 * an older build.
 */
class Admission(
    val payload: ByteArray,
    val symbology: String,
    val page: Int = 0,
    val corroborated: Boolean = false,
    val redrawable: Boolean? = null,
) {
    fun withRedrawable(redrawable: Boolean?) = Admission(payload, symbology, page, corroborated, redrawable)

    override fun equals(other: Any?): Boolean =
        other is Admission &&
            payload.contentEquals(other.payload) &&
            symbology == other.symbology &&
            page == other.page &&
            corroborated == other.corroborated &&
            redrawable == other.redrawable

    override fun hashCode(): Int =
        listOf(payload.contentHashCode(), symbology, page, corroborated, redrawable).hashCode()

    override fun toString(): String =
        "Admission(symbology=$symbology, page=$page, corroborated=$corroborated, redrawable=$redrawable, " +
            "payload=${payload.size} bytes)"
}

/**
 * The pipeline's best guess, reported honestly rather than decided on (#411 comment
 * clarifying #408's spec). Every field is independently nullable — an Admission with
 * no readable date is not "half a failure", it is exactly what it says.
 *
 * [admissions] is every Admission the ticket yielded, in page order (#441). A person's
 * corrections to the text never touch it (story 16).
 *
 * [date] is dd-MM-yyyy, the one shape this app and setlist.fm both speak (see
 * [fmDate]/[parseFmDate] in Bill.kt).
 *
 * [artistSupport], [venueSupport] and [dateSupport] say which readings produced each
 * field (#526): null where the field is null, and null on a ticket that was never read
 * from evidence at all — one a link or a person typed. [readingCount] is how many
 * readings the source gave; null or one means there was nothing to cross-check against.
 */
data class ParsedTicket(
    val admissions: List<Admission> = emptyList(),
    val artist: String? = null,
    val venue: String? = null,
    val date: String? = null,
    val artistSupport: TicketSupport? = null,
    val venueSupport: TicketSupport? = null,
    val dateSupport: TicketSupport? = null,
    val readingCount: Int? = null,
) {
    /** Nothing at all came out of the page — the honest "couldn't read this" (story 9). */
    val isEmpty: Boolean get() = admissions.isEmpty() && artist == null && venue == null && date == null

    /**
     * At least one Admission + artist + venue + date. Three facts out of four is not
     * "nearly right", it is a guess with a gap in it. Any symbology counts (#441): a
     * Code 128 is as much an Admission as a QR, whether or not the Room can redraw it yet.
     */
    val isComplete: Boolean get() = admissions.isNotEmpty() && artist != null && venue != null && date != null

    /**
     * Complete, *and* nothing the two readings could have disagreed about was left to
     * one of them alone (#526) — the only shape allowed past the confirm prompt. A
     * field only one reading produced is a guess the other did not back: the vendor
     * logo OCR read as an artist, or a text layer whose font came out garbled.
     *
     * A source with one reading passes on completeness alone. Holding a scan (or a
     * phone below API 35, which has no text layer) to a standard it can never meet
     * would make every such ticket a prompt forever.
     */
    val canSkipPrompt: Boolean
        get() {
            if (!isComplete) return false
            val count = readingCount
            if (count == null || count <= 1) return true
            return listOf(artistSupport, venueSupport, dateSupport).all { it == TicketSupport.BOTH }
        }

    /**
     * Every Admission was redrawn in its own symbology and read back as itself (#441,
     * story 29). [routeTicket] asks this beside [isComplete]/[canSkipPrompt] before it
     * acts without the person: a ticket the app cannot show at the door is shown to them
     * at import instead, while they still hold the PDF. Not part of [canSkipPrompt],
     * which is the shared fixtures' `skipsPrompt` and a property of the *read*: what a
     * platform can redraw is not the same on both (iOS has no Data Matrix generator), so
     * it is not in the corpus both twins assert.
     */
    val redrawsEveryAdmission: Boolean get() = admissions.all { it.redrawable == true }
}

// --- Picking fields ---

/**
 * The pure half of ticket reading: evidence in, a best-effort [ParsedTicket] out —
 * never a decision about what to do with it, which every caller (the ViewModel, the
 * confirm dialog) makes from there. The only code that decides which words are the
 * artist, the venue and the date.
 *
 * The rules are `fixtures/ticket/README.md`'s, and this is a line-for-line twin of
 * `parseTicketFields` in `Ticket.swift`: both platforms run every case in
 * `fixtures/ticket/` (TicketFixturesTest here).
 *
 * **Each reading is guessed on its own, then the two are compared** (#526). A field
 * both readings agree on, after folding case, whitespace and punctuation, has `both`
 * support. Where they differ:
 *
 * - OCR's answer is taken from the lines the text layer also has, and only where those
 *   give nothing from every line. A line with no counterpart in the text layer is
 *   usually a picture — a vendor's logo, a banner image — so an OCR-only line can fill
 *   a field only when nothing the text layer also shows could.
 * - If they still differ, a candidate the other reading also saw wins, the text
 *   layer's first.
 * - If neither saw the other's answer at all, the readings genuinely disagree — a text
 *   layer whose font has no Unicode map, say, beside OCR that reads the page cleanly —
 *   and the candidate with more letters wins.
 *
 * Anything short of agreement is single support, which keeps it out of auto-add.
 */
fun parseTicketFields(evidence: TicketEvidence): ParsedTicket {
    val readings = evidence.readings
        .map { reading -> TicketReading(reading.origin, reading.lines.map(::tidied).filter { it.isNotEmpty() }) }
        .filter { it.lines.isNotEmpty() }

    val text = readings.firstOrNull { it.origin == TicketReading.Origin.TEXT_LAYER }
    val ocr = readings.firstOrNull { it.origin == TicketReading.Origin.OCR }

    var artist: Pair<String, TicketSupport>? = null
    var venue: Pair<String, TicketSupport>? = null
    var date: Pair<LocalDate, TicketSupport>? = null

    if (text != null && ocr != null) {
        val t = guess(text.lines)
        val o = ocrGuess(ocr.lines, besideTextLayer = text.lines)

        fun names(t: String?, o: String?) = crossCheck(
            t, o,
            same = { a, b -> folded(a) == folded(b) },
            ocrSaw = { value -> ocr.lines.any { folded(it).contains(folded(value)) } },
            textSaw = { value -> text.lines.any { folded(it).contains(folded(value)) } },
            ocrIsBetter = { a, b -> letterCount(b) > letterCount(a) },
        )
        artist = names(t.artist, o.artist)
        venue = names(t.venue, o.venue)
        date = crossCheck(
            t.date, o.date,
            same = { a, b -> a == b },
            ocrSaw = { day -> ocr.lines.any { readDate(it) == day } },
            textSaw = { day -> text.lines.any { readDate(it) == day } },
            // Two days neither reading backs the other on: no count of letters
            // settles that, so the text layer's own stands.
            ocrIsBetter = { _, _ -> false },
        )
    } else {
        val only = text ?: ocr
        if (only != null) {
            val found = guess(only.lines)
            val support = TicketSupport.of(only.origin)
            artist = found.artist?.let { it to support }
            venue = found.venue?.let { it to support }
            date = found.date?.let { it to support }
        }
    }

    return ParsedTicket(
        admissions = admissions(evidence),
        artist = artist?.first,
        venue = venue?.first,
        date = date?.first?.let(::fmDate),
        artistSupport = artist?.second,
        venueSupport = venue?.second,
        dateSupport = date?.second,
        readingCount = readings.size,
    )
}

/** `qr`: the QR's name in `fixtures/ticket/README.md`, and what an old `ticketQr` and a `?qr=` link always are. */
const val QR_SYMBOLOGY = "qr"

/**
 * The linear retail formats zxing reports beside a ticket's real code (the Android
 * probe, #441: EAN-13, EAN-8 and UPC-E hits on tickets whose Admission is a QR).
 * Unverified — possibly other print, possibly false positives.
 */
private val RETAIL_SYMBOLOGIES = setOf("ean13", "ean8", "upca", "upce")

/**
 * The 2D symbologies. A ticket that carries any of them is a ticket whose door code is
 * one of them: a linear code beside it is an order or reference number (the #441
 * review's QR beside an order-number Code 128). Provisional, like the retail rule.
 */
private val MATRIX_SYMBOLOGIES = setOf("qr", "aztec", "pdf417", "datamatrix")

/**
 * The evidence's barcodes, reconciled into Admissions (`fixtures/ticket/README.md`,
 * "The Admissions"; the Swift twin is line for line):
 *
 * 1. Only a barcode with a symbology and a non-empty payload can be one.
 * 2. Linear codes are dropped when any 2D code ([MATRIX_SYMBOLOGIES]) was found, on any
 *    page: beside a QR, a Code 128 is the order number. Otherwise retail formats
 *    ([RETAIL_SYMBOLOGIES]) are dropped when anything else was found, and kept only when
 *    they are all there is. Both provisional: a ticket that really is an EAN keeps it,
 *    and one beside a QR loses a probable false positive.
 * 3. In page order (stable: found order within a page).
 * 4. One per payload, first kept: the same code on three pages is one Admission, and
 *    the same payload in two symbologies keeps the first.
 * 5. [Admission.corroborated] when the payload, as strict UTF-8 with whitespace and
 *    `*` taken out, appears in some line of some reading with the same taken out.
 */
private fun admissions(evidence: TicketEvidence): List<Admission> {
    val candidates = evidence.barcodes.mapNotNull { barcode ->
        val symbology = barcode.symbology ?: return@mapNotNull null
        val payload = barcode.payload?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Triple(payload, symbology, barcode.page ?: 0)
    }
    val kept = when {
        candidates.any { it.second in MATRIX_SYMBOLOGIES } -> candidates.filter { it.second in MATRIX_SYMBOLOGIES }
        candidates.all { it.second in RETAIL_SYMBOLOGIES } -> candidates
        else -> candidates.filter { it.second !in RETAIL_SYMBOLOGIES }
    }
    val printed = evidence.readings.flatMap { it.lines }.map(::printedKey)
    return kept
        .sortedBy { it.third }
        .distinctBy { it.first.toList() }
        .map { (payload, symbology, page) ->
            val key = strictUtf8(payload)?.let(::printedKey).orEmpty()
            Admission(
                payload = payload,
                symbology = symbology,
                page = page,
                corroborated = key.isNotEmpty() && printed.any { it.contains(key) },
            )
        }
}

/**
 * Space, tab, newline, carriage return and `*` (a Code 39-style printed delimiter,
 * `*TESTQRAA1*`) taken out: exactly those, the same set as the Swift twin. Not
 * [Char.isWhitespace], which also takes U+001C–001F, so a GS1 payload's GS (FNC1) would
 * vanish here and stay on iOS.
 */
private val PRINTED_KEY_DROPS = setOf(' ', '\t', '\n', '\r', '*')

private fun printedKey(text: String): String = text.filterNot { it in PRINTED_KEY_DROPS }

/** The bytes as UTF-8, or null where they are not valid UTF-8 — never a U+FFFD guess. */
private fun strictUtf8(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

/**
 * OCR's own answer, read beside a text layer: first from the lines the text layer also
 * has, and then — only for a field those leave empty — from every line.
 *
 * The fallback may not hand one line to both names. The two passes order their lines
 * differently, so without that check a line the first pass made the venue could come
 * back from the second as the artist too.
 */
private fun ocrGuess(lines: List<String>, besideTextLayer: List<String>): Guess {
    val textKeys = besideTextLayer.map(::folded).toSet()
    val checked = guess(lines.filter { folded(it) in textKeys })
    val loose = guess(lines)
    val looseArtist = loose.artist
    if (checked.artist == null && looseArtist != null && folded(looseArtist) != checked.venue?.let(::folded)) {
        checked.artist = looseArtist
    }
    val looseVenue = loose.venue
    if (checked.venue == null && looseVenue != null && folded(looseVenue) != checked.artist?.let(::folded)) {
        checked.venue = looseVenue
    }
    if (checked.date == null) checked.date = loose.date
    return checked
}

/** One field's two candidates, compared. See [parseTicketFields] for the order. */
private fun <V : Any> crossCheck(
    t: V?,
    o: V?,
    same: (V, V) -> Boolean,
    ocrSaw: (V) -> Boolean,
    textSaw: (V) -> Boolean,
    ocrIsBetter: (V, V) -> Boolean,
): Pair<V, TicketSupport>? {
    if (t == null) return o?.let { it to TicketSupport.OCR }
    if (o == null) return t to TicketSupport.TEXT_LAYER
    return when {
        same(t, o) -> t to TicketSupport.BOTH
        ocrSaw(t) -> t to TicketSupport.TEXT_LAYER
        textSaw(o) -> o to TicketSupport.OCR
        ocrIsBetter(t, o) -> o to TicketSupport.OCR
        else -> t to TicketSupport.TEXT_LAYER
    }
}

// --- One reading's guess ---

/** What the rules make of one reading on its own, before it is compared with another. */
private class Guess(var artist: String? = null, var venue: String? = null, var date: LocalDate? = null)

/**
 * The rules, in the order they are trusted (#526, the same on both platforms):
 *
 * 1. **A labelled field** says what it is: `Artist: Big Thief`.
 * 2. **`X at Y`** says which half is which.
 * 3. **The lines around the date.** On a layout with no vendor-styled caps line, the
 *    artist prints just above the date and the venue just below it.
 * 4. **The first lines**, with any vendor-styled caps line moved ahead of the prose.
 *
 * Rules 3 and 4 are guesses, and they are made on purpose (#526 chose guessing over
 * iOS's old labelled-only rule): the person reviews every read that is not backed by
 * both readings, and a guess pre-filled into that prompt is one less field to type.
 * What keeps a guess honest is what is *not* a candidate — see [isGuessable].
 *
 * [lines] arrive in reading order; the first match down the page wins.
 */
private fun guess(lines: List<String>): Guess {
    val found = Guess()
    val dateLines = mutableSetOf<Int>()
    var dateIndex: Int? = null

    // The night is the first date down the page that is not a purchase date; a
    // purchase date is taken only when it is the only kind there is. See
    // [isPurchaseDate].
    var purchase: Pair<LocalDate, Int>? = null
    for ((index, line) in lines.withIndex()) {
        val day = readDate(line) ?: continue
        dateLines += index
        if (isPurchaseDate(index, lines)) {
            if (purchase == null) purchase = day to index
        } else if (found.date == null) {
            found.date = day
            dateIndex = index
        }
    }
    if (found.date == null && purchase != null) {
        found.date = purchase.first
        dateIndex = purchase.second
    }

    // Lines a rule has used, and label lines whether or not their value read: a
    // label's line is never a guess at something else.
    val taken = mutableSetOf<Int>()
    for ((index, line) in lines.withIndex()) {
        if (isLabelLine(line)) taken += index
        val next = lines.getOrNull(index + 1)
        val labelledArtist = readLabelled(line, ARTIST_LABELS, next)
        if (labelledArtist != null && found.artist == null) {
            found.artist = labelledArtist.first
            if (labelledArtist.second) taken += index + 1
        }
        val labelledVenue = readLabelled(line, VENUE_LABELS, next)
        if (labelledVenue != null && found.venue == null) {
            found.venue = labelledVenue.first
            if (labelledVenue.second) taken += index + 1
        }
    }

    if (found.artist == null || found.venue == null) {
        for ((index, line) in lines.withIndex()) {
            if (index in dateLines || index in taken) continue
            val (left, right) = readAtSeparator(line) ?: continue
            if (found.artist == null) found.artist = left
            if (found.venue == null) found.venue = right
            taken += index
            break
        }
    }

    if (found.artist != null && found.venue != null) return found

    val pool = lines.indices.filter { it !in dateLines && it !in taken && isGuessable(lines[it]) }
    val shouty = pool.filter { isShouty(lines[it]) }

    // A vendor commonly styles the event and venue lines in caps ("SKAMBANKT",
    // "PARKTEATRET SCENE") while a banner reads as ordinary prose ("Dette er din
    // billett"), and ML Kit's reading order puts that banner first on plenty of real
    // tickets. So caps lines go ahead of prose — reordered, never discarded, so a
    // ticket with no caps line at all still gets its first two lines.
    //
    // A ticket with no caps line at all (an Eventim one) has a different, still generic
    // signal: the date sits sandwiched between the artist just above it and the venue
    // just below. That only fires when both neighbours exist. Where the date is the last
    // line, "the line above it" would grab whatever second line sits there instead of
    // the first.
    val night = dateIndex
    if (shouty.isEmpty() && night != null) {
        val above = pool.lastOrNull { it < night }
        val below = pool.firstOrNull { it > night }
        if (above != null && below != null) {
            if (found.artist == null) found.artist = lines[above]
            if (found.venue == null) found.venue = lines[below]
            return found
        }
    }
    val ordered = (shouty + pool.filter { it !in shouty }).iterator()
    if (found.artist == null) found.artist = if (ordered.hasNext()) lines[ordered.next()] else null
    if (found.venue == null) found.venue = if (ordered.hasNext()) lines[ordered.next()] else null
    return found
}

// --- Artist and venue ---

private val ARTIST_LABELS = listOf("artist", "artists", "act", "performer", "performing", "headliner")
private val VENUE_LABELS = listOf("venue", "location", "place", "where", "hall")

/** `Artist: …` or `Venue: …`, whatever follows the colon. */
private fun isLabelLine(line: String): Boolean {
    val colon = line.indexOf(':')
    if (colon < 0) return false
    val label = line.substring(0, colon).trim().lowercase(Locale.ROOT)
    return label in ARTIST_LABELS || label in VENUE_LABELS
}

/**
 * `Venue: Rockefeller`, or `Venue:` with the value on the line under it — OCR breaks a
 * label off its value about as often as it keeps them together. The flag says the
 * value came off the next line, so no later rule reads that line again.
 */
private fun readLabelled(line: String, labels: List<String>, next: String?): Pair<String, Boolean>? {
    val colon = line.indexOf(':')
    if (colon < 0) return null
    val label = line.substring(0, colon).trim().lowercase(Locale.ROOT)
    if (label !in labels) return null
    val value = tidied(line.substring(colon + 1))
    if (isUsableName(value)) return value to false
    if (next == null || !isUsableName(next) || next.contains(':')) return null
    return next to true
}

/**
 * `Big Band at The Corner Hotel`, `Big Band live @ Sentrum Scene`. Near-universal on
 * tickets and event listings, and it says which half is which.
 *
 * `ARTIST — VENUE` is deliberately still *not* split: a dash separates a great many
 * things on a ticket ("Doors — 19:00", "Stalls — Row F"). A dashed line can still be
 * guessed whole, as any other line can, but never cut in two.
 */
private fun readAtSeparator(line: String): Pair<String, String>? {
    val match = AT_SEPARATOR.find(line) ?: return null
    val artist = tidied(match.groupValues[1])
    val venue = tidied(match.groupValues[2])
    if (!isUsableName(artist) || !isUsableName(venue)) return null
    if (readDate(artist) != null || readDate(venue) != null) return null
    return artist to venue
}

private val AT_SEPARATOR = Regex("""^(.+?)\s+(?:live\s+)?(?:at|@)\s+(.+)$""", RegexOption.IGNORE_CASE)

/**
 * A name has to be pronounceable and short enough to be one. Two letters is the floor
 * because an order line ("#4471193") has none, and eighty the ceiling because a
 * terms-and-conditions sentence is not a venue.
 */
private fun isUsableName(text: String): Boolean =
    letterCount(text) >= 2 && text.codePointCount(0, text.length) <= 80

/**
 * Whether an unlabelled line may be guessed as a name at all. Everything excluded here
 * was confirmed against real tickets' actual OCR output rather than guessed:
 *
 * - **A digit.** An event or venue name essentially never carries one; a booking code,
 *   a price, an address or a door time always does ("OPT2901", "NOK 690,00"). A band
 *   with a digit in its name costs one field to type, not a wrong answer.
 * - **A trailing `:`, `!` or `?`.** A heading a value sits under, or an ad's tagline
 *   ("DEL EN OPPLEVELSE!") — never the name itself.
 * - **A `/`.** A seating category or a combined entrance ("STÅPLASS/STANDING").
 * - **A caps banner about the ticket itself.** See [isTicketBanner].
 *
 * This used to guard only the caps lines here. #526 applies it to every guessed line,
 * the ones around the date included — the old neighbour check (non-blank, no trailing
 * `:`) is gone.
 */
private fun isGuessable(line: String): Boolean {
    if (!isUsableName(line)) return false
    if (line.codePoints().anyMatch { Character.isDigit(it) }) return false
    if (line.contains('/')) return false
    if (line.lastOrNull()?.let { it in ":!?" } == true) return false
    return !isTicketBanner(line)
}

/**
 * A caps line with the word for a ticket in it — `TICKETLINE`, `TICKETMASTER`,
 * `BILLETTSERVICE`, `E-TICKET` — is the vendor's masthead or a heading about the
 * ticket, never the night's artist or venue.
 *
 * `TICKETLINE` became an artist on the Pixel (#526), and the probe found it in the
 * text layer as well as in OCR, at the top of both. So neither the cross-check nor its
 * position on the page tells it from `MORK WATER` two lines below; what does is that it
 * names the ticket. The words are the ticket's own vocabulary, not a list of vendors,
 * and only a caps line is asked: a sentence that mentions a ticket is prose, and prose
 * already ranks below every caps line.
 */
private fun isTicketBanner(line: String): Boolean {
    if (!isShouty(line)) return false
    val key = folded(line)
    return TICKET_WORDS.any { key.contains(it) }
}

/**
 * "Ticket" in English, Norwegian and Danish, and Swedish. Matched inside a word, so a
 * compound (`TICKETLINE`, `BILLETTSERVICE`) counts.
 */
private val TICKET_WORDS = listOf("ticket", "billett", "biljett")

/**
 * At least four letters in five uppercase: a vendor's stylised event or venue line, not
 * the prose above or below it on the page. Only ever asked of a guessable line.
 */
private fun isShouty(line: String): Boolean {
    val letters = line.filter { it.isLetter() }
    if (letters.length < 2) return false
    val upper = letters.count { it.isUpperCase() }
    return upper.toDouble() / letters.length >= 0.8
}

/**
 * Swift's `CharacterSet.letters`, which `Ticket.swift` counts with: Unicode letters
 * *and* marks. Mirrored rather than approximated by [Char.isLetter], so a combining
 * accent weighs the same on both platforms.
 */
private fun isLetterScalar(codePoint: Int): Boolean =
    Character.isLetter(codePoint) || when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> false
    }

/** Swift's `CharacterSet.alphanumerics`: letters, marks and every kind of number. */
private fun isAlphanumericScalar(codePoint: Int): Boolean =
    isLetterScalar(codePoint) || when (Character.getType(codePoint)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

private fun letterCount(text: String): Int = text.codePoints().filter(::isLetterScalar).count().toInt()

/**
 * What two readings of one line have in common: case, whitespace and punctuation
 * dropped. OCR loses a space after a comma as readily as it keeps it, and the text
 * layer never does.
 */
private fun folded(text: String): String = buildString {
    text.lowercase(Locale.ROOT).codePoints().filter(::isAlphanumericScalar).forEach { appendCodePoint(it) }
}

// --- The date ---

/**
 * A date that says when the ticket was bought, not when the night is: one on a line
 * with a purchase word in it (`Kjøpt 01.05.2025`, `Order date: 01.05.2025`), or the
 * value under a purchase label that broke onto a line of its own (`Kjøpsdato:`, then
 * `01.05.2025`).
 *
 * An order block often prints above the event on a text layer — the Eventim ticket
 * the Android probe read gave its purchase date first (#526) — so "the first date down
 * the page" alone is not enough. A purchase date is not thrown away, only passed over:
 * with nothing else to go on it is still the best-known date, and the person reviews
 * the read.
 */
private fun isPurchaseDate(index: Int, lines: List<String>): Boolean {
    if (mentionsPurchase(lines[index])) return true
    val above = lines.getOrNull(index - 1) ?: return false
    return above.trim().endsWith(":") && mentionsPurchase(above)
}

private fun mentionsPurchase(line: String): Boolean {
    val lower = line.lowercase(Locale.ROOT)
    return PURCHASE_WORDS.any { lower.contains(it) }
}

/**
 * Matched inside a word: `kjøp` covers kjøpt, kjøpsdato and kjøpstidspunkt; `bestil`
 * bestilt and bestillingsdato; `order` ordered and order date; `ordre` ordredato.
 */
private val PURCHASE_WORDS = listOf("kjøp", "bestil", "ordre", "order", "purchase", "booked", "booking")

/**
 * dd-MM-yyyy, whatever shape the source text used. Null when nothing in [text] parses.
 * The ticket link's `date=` goes through this too, so a link and a PDF read a date
 * the same way.
 */
internal fun findDate(text: String): String? = readDate(text)?.let(::fmDate)

/**
 * The one field a wrong answer is most costly on, so the patterns are explicit and
 * ordered rather than left to a locale-guessing formatter — the same patterns, in the
 * same order, as `readDate` in `Ticket.swift`.
 *
 * Ambiguity has exactly one rule: **day first** where nothing decides it. `03/04/2026`
 * is the 3rd of April. Where one number is over twelve it decides by itself, in either
 * direction. This is written down because it is the case the two platforms are most
 * likely to answer differently by accident.
 *
 * Two-digit years are not read at all: `14/09/26` could be a year or a day, and the
 * confirmation prompt is a cheaper place to resolve that than a guess is.
 *
 * Only a pattern's first match in the line is tried; if it is not a real day, the next
 * pattern is.
 */
private fun readDate(line: String): LocalDate? {
    for (pattern in DATE_PATTERNS) {
        val match = pattern.regex.find(line) ?: continue
        val (year, month, day) = pattern.read(match.groupValues.drop(1)) ?: continue
        if (month !in 1..12 || day !in 1..31 || year !in 1900..2999) continue
        try {
            return LocalDate.of(year, month, day)
        } catch (e: DateTimeException) {
            continue
        }
    }
    return null
}

/** [read] turns the three captures into year, month, day. */
private class DatePattern(val regex: Regex, val read: (List<String>) -> Triple<Int, Int, Int>?)

/**
 * English and Norwegian month prefixes. A language is not a vendor: this app is used
 * where its user buys tickets, and `14. september 2026` is not an exotic layout there.
 */
private val MONTH_NAMES = mapOf(
    "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "mai" to 5, "jun" to 6,
    "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "okt" to 10, "nov" to 11, "dec" to 12, "des" to 12,
)

private val MONTH_ALTERNATION = MONTH_NAMES.keys.sorted().joinToString("|")

private fun dateRegex(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

private val DATE_PATTERNS = listOf(
    // 2026-09-14, 2026/09/14
    DatePattern(dateRegex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")) {
        val y = it[0].toIntOrNull()
        val m = it[1].toIntOrNull()
        val d = it[2].toIntOrNull()
        if (y == null || m == null || d == null) null else Triple(y, m, d)
    },
    // 14 September 2026, 14. sep. 2026, 14th Sept 2026
    DatePattern(
        dateRegex("""(\d{1,2})(?:st|nd|rd|th)?[.,]?\s*(?:of\s+)?($MONTH_ALTERNATION)[a-zæøå]*\.?[\s,]+(\d{4})"""),
    ) {
        val d = it[0].toIntOrNull()
        val m = MONTH_NAMES[it[1].lowercase(Locale.ROOT)]
        val y = it[2].toIntOrNull()
        if (y == null || m == null || d == null) null else Triple(y, m, d)
    },
    // September 14, 2026 / Sep 14 2026
    DatePattern(
        dateRegex("""($MONTH_ALTERNATION)[a-zæøå]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?[.,]?\s+(\d{4})"""),
    ) {
        val m = MONTH_NAMES[it[0].lowercase(Locale.ROOT)]
        val d = it[1].toIntOrNull()
        val y = it[2].toIntOrNull()
        if (y == null || m == null || d == null) null else Triple(y, m, d)
    },
    // 14/09/2026, 14.09.2026, 14-09-2026 — day first unless a number says otherwise.
    DatePattern(dateRegex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})""")) {
        val a = it[0].toIntOrNull()
        val b = it[1].toIntOrNull()
        val y = it[2].toIntOrNull()
        if (a == null || b == null || y == null) {
            null
        } else if (a > 12 && b <= 12) {
            Triple(y, b, a)
        } else if (b > 12 && a <= 12) {
            Triple(y, a, b)
        } else {
            Triple(y, b, a)
        }
    },
)

// --- Tidying ---

/**
 * What a reader hands back, made comparable: whitespace collapsed (a text layer's
 * trailing `\r` included), and the punctuation a line break leaves stranded taken off
 * either end.
 *
 * A colon is **not** in that set, deliberately. It is the one piece of stranded
 * punctuation that means something: `Artist:` on its own line is a label whose value
 * broke onto the next one, and trimming it turns that line into a word.
 *
 * Split by [Char.isWhitespace] rather than a `\s` regex: Java's `\s` is ASCII-only and
 * would leave a no-break space in place where Swift's split collapses it.
 */
internal fun tidied(text: String): String {
    val collapsed = buildString {
        var gap = false
        for (c in text) {
            if (c.isWhitespace() || c == '\u0085') {
                gap = isNotEmpty()
            } else {
                if (gap) append(' ')
                gap = false
                append(c)
            }
        }
    }
    return collapsed.trim { it in STRANDED }
}

private const val STRANDED = " \t.,;-–—|·•"

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

    /** A complete, unmatched guess for tonight or a later night — goes straight onto the plan. */
    data class NewPlannedGig(
        val artist: String,
        val venue: String,
        val date: String,
        val admissions: List<Admission>,
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
 * complete parse (an Admission + artist + venue + date) may skip the confirm step, and only
 * when it either clearly matches an existing night or clearly doesn't. Everything
 * else — a partial parse, or nothing at all — is [TicketRouting.NeedsConfirmation],
 * never a silent add and never a silent drop.
 *
 * A complete, unmatched parse for a **past** date is also routed to confirmation
 * rather than minted as a plan (story 13): the local-planned-gig path means "I'm
 * going", and an old ticket found while cleaning out email is not that. Tonight is
 * not past: the day of the gig is when a ticket is most often shared, so a ticket
 * dated [today] is minted like a later one — the same line iOS's `routeTicket` draws
 * at the start of today. [today] defaults to the real clock and exists only so a
 * test can pin it.
 *
 * **Nor is a complete parse only one reading backed minted silently** (#526). A PDF is
 * read twice, from its own text layer and by OCR, and an unmatched complete parse is
 * minted without asking only when [ParsedTicket.canSkipPrompt] says every field came
 * out of both — or there was only one reading to begin with, as with a scan. The
 * vendor logo OCR read as an artist is exactly a complete, confident, wrong parse. A
 * match still needs only completeness, as on iOS: it adds nothing new to the line.
 *
 * **Nor is one whose barcode the app cannot show** (#441, story 29), on either path. An
 * Admission that did not read back as itself when redrawn ([checkedForRedraw]; an
 * unchecked one counts as not) sends the ticket to the prompt, which says which barcode
 * it is and to bring the PDF. Found at import, not at the door.
 *
 * **Nor is one for a date a known night is already on** when it matched no act (the
 * #441 review): [knownNightThatDay] says why, and that night goes to the prompt as the
 * possible match.
 */
fun routeTicket(
    parsed: ParsedTicket,
    knownGigs: List<FmSetlist>,
    today: LocalDate = LocalDate.now(),
): TicketRouting {
    val match = matchKnownNight(parsed, knownGigs)
    val sameDay = if (match == null) knownNightThatDay(parsed, knownGigs) else null
    if (parsed.isComplete && parsed.redrawsEveryAdmission) {
        if (match != null) return TicketRouting.AlreadyKnown(match)
        val night = parseFmDate(parsed.date!!)
        if (sameDay == null && parsed.canSkipPrompt && night != null && !night.isBefore(today)) {
            return TicketRouting.NewPlannedGig(parsed.artist!!, parsed.venue!!, parsed.date, parsed.admissions)
        }
    }
    return TicketRouting.NeedsConfirmation(parsed, match ?: sameDay)
}

/**
 * A night already known on the ticket's date, when no artist matched it (the #441
 * review). [routeTicket] never mints past one: a ticket whose artist line carries a tour
 * name (`Dumdumboys – XL [romertallførti]`) is complete and agreed by both readings, and
 * matches no act, yet the person already planned `Dumdumboys` that night. Asked about,
 * with that night as the prompt's possible match, rather than a second night minted.
 *
 * Of several that day (a festival day), the one whose venue folds equal to the ticket's
 * ([nameKey]), else the first. Provisional, as the rule is: generic, no vendor or artist
 * named. The iOS twin is `nightThatDay`.
 */
fun knownNightThatDay(parsed: ParsedTicket, knownGigs: List<FmSetlist>): FmSetlist? {
    val date = parsed.date ?: return null
    val thatDay = knownGigs.filter { it.eventDate == date }
    val venueKey = parsed.venue?.let(::nameKey)?.ifEmpty { null }
    return thatDay.firstOrNull { venueKey != null && nameKey(it.venue?.name.orEmpty()) == venueKey }
        ?: thatDay.firstOrNull()
}

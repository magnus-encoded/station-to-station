import Foundation

/// A PDF shared into the app, read for what it can prove about a night (#412, #408).
///
/// **Evidence, never a Gig.** Every field is optional and independently found: a
/// **Ticket** that yielded only a QR is as real a Ticket as one that yielded
/// everything. What turns one into a **Gig** is a person confirming it, through the
/// same local-planned-gig path a hand-typed night already uses.
///
/// Shared with the Share Extension target, which is why nothing here knows about
/// `FmSetlist`, PDFKit or Vision. The extension extracts; this parses; the app routes.
struct Ticket: Codable, Equatable, Sendable {
    /// The decoded QR payload, kept whatever the text parse managed. On iOS 17+ this
    /// is Vision's `payloadData` verbatim; on iOS 16 Vision only hands back a string,
    /// so it is that string's UTF-8 — a binary payload read on 16 is lossy, and the
    /// day-of view (#414) has to tolerate that.
    ///
    /// The payload only, never the crop the locator found it in (`TicketBarcode.image`).
    /// A **Ticket** is what crosses into the App Group and what the app stores, and the
    /// stored format is still the payload the Room redraws a QR from (#526, #441).
    var qr: Data?
    var artist: String?
    var venue: String?
    /// Midnight of the night, in the reader's own calendar — a **Ticket** is dated at
    /// day precision and nothing finer is ever inferred from it (ADR-0002).
    var date: Date?

    /// Which readings produced each field (#526). Nil where the field is nil, and nil
    /// on a **Ticket** that was never read from evidence at all — one a person typed.
    var artistSupport: TicketSupport?
    var venueSupport: TicketSupport?
    var dateSupport: TicketSupport?
    /// How many readings the source gave. Nil or one means there was nothing to
    /// cross-check against: a scan has only its OCR, and that is all it will ever have.
    var readingCount: Int?

    var isEmpty: Bool { qr == nil && artist == nil && venue == nil && date == nil }

    /// Everything, and therefore the only shape allowed past the confirmation prompt.
    /// Three facts out of four is not "nearly right", it is a guess with a gap in it.
    var isComplete: Bool { qr != nil && artist != nil && venue != nil && date != nil }

    /// Complete, *and* nothing the two readings could have disagreed about was left
    /// to one of them alone. A field only one reading produced is a guess the other
    /// reading did not back: the vendor logo OCR read as an artist, or a text layer
    /// whose font came out garbled. The person sees exactly the same prompt either way;
    /// this only decides whether they are asked at all.
    ///
    /// A source with one reading passes on completeness alone. Holding a scan to a
    /// standard it can never meet would make every scanned ticket a prompt forever.
    var canSkipPrompt: Bool {
        guard isComplete else { return false }
        guard let readingCount, readingCount > 1 else { return true }
        return [artistSupport, venueSupport, dateSupport].allSatisfy { $0 == .both }
    }
}

/// Where a field of a **Ticket** came from: both readings, or only one of them.
enum TicketSupport: String, Codable, Sendable {
    case both, textLayer, ocr

    init(_ origin: TicketReading.Origin) {
        switch origin {
        case .textLayer: self = .textLayer
        case .ocr: self = .ocr
        }
    }
}

/// A **Ticket** waiting on the prompt. Identity of its own because several can queue
/// up, and `.sheet(item:)` has to be able to tell one blank one from the next.
struct TicketDraft: Identifiable, Equatable {
    let id = UUID()
    let ticket: Ticket
}

/// What one PDF turned out to be worth.
///
/// `nothingUsable` is a real answer and not an error: a scanned image with no text
/// layer and no QR is a PDF this app honestly cannot read, and saying so beats
/// planting a wrong night on the **Line** (#408, story 9).
enum TicketParse: Equatable, Sendable {
    case ticket(Ticket)
    case nothingUsable
}

// MARK: - Evidence

/// What any source handed back, before any judgement is made about it (#526).
struct TicketEvidence: Equatable, Sendable {
    /// One reading per method that produced any text.
    var readings: [TicketReading]
    var barcode: TicketBarcode?
}

/// One method's reading of the whole source.
struct TicketReading: Equatable, Sendable {
    enum Origin: String, Codable, Sendable { case textLayer, ocr }
    var origin: Origin
    /// Text lines in reading order: down the page, then across it.
    var lines: [String]
}

/// The barcode as it appears on the ticket, plus what it says when that can be decoded.
struct TicketBarcode: Equatable, Sendable {
    /// PNG crop of the detected bounds, padded to include the quiet zone.
    var image: Data
    /// The decoded payload. nil when the symbology cannot be decoded.
    var payload: Data?
    /// "qr" is the only value today. #441 widens this.
    var symbology: String?
}

/// Turns one kind of input into evidence. `Source` is the input type:
/// PDF `Data` today, and later possibly an image, a web page or an IPC payload.
///
/// An extractor knows about renderers and recognisers and makes no judgement about
/// what the words mean. That is `parseTicketFields`'s job alone, so a new source is one
/// more extractor and no new rule.
protocol TicketExtractor {
    associatedtype Source
    func extract(_ source: Source) async -> TicketEvidence
}

/// The whole of ticket reading: a source through its extractor, then through the one
/// function that picks fields.
func parseTicket<Extractor: TicketExtractor>(_ source: Extractor.Source,
                                             with extractor: Extractor,
                                             calendar: Calendar = .current) async -> TicketParse {
    parseTicketFields(await extractor.extract(source), calendar: calendar)
}

// MARK: - Picking fields

/// The pure half of ticket reading: evidence in, a best-effort **Ticket** or a clear
/// failure out. The only code that decides which words are the artist, the venue and
/// the date.
///
/// Renderer-free on purpose — the seam is here so the rules can be tested without a
/// PDF renderer, an OCR engine or a device, and so the Android twin is held to the same
/// answers on the same inputs: both platforms run every case in `fixtures/ticket/`.
///
/// **Each reading is guessed on its own, then the two are compared** (#526). A field
/// both readings agree on, after folding case, whitespace and punctuation, has `both`
/// support. Where they differ:
///
/// - OCR's answer is taken from the lines the text layer also has, and only where
///   those give nothing from every line. A line with no counterpart in the text layer
///   is usually a picture — a vendor's logo, a banner image — which is exactly how
///   `TICKETLINE` became an artist on the Pixel (#526). So an OCR-only line can fill a
///   field only when nothing the text layer also shows could.
/// - If they still differ, a candidate the other reading also saw wins, the text
///   layer's first.
/// - If neither saw the other's answer at all, the readings genuinely disagree — a
///   text layer whose font has no Unicode map, say, beside OCR that reads the page
///   cleanly — and the candidate with more letters wins.
///
/// Anything short of agreement is single support, which keeps it out of auto-add.
func parseTicketFields(_ evidence: TicketEvidence, calendar: Calendar = .current) -> TicketParse {
    let readings = evidence.readings
        .map { TicketReading(origin: $0.origin, lines: $0.lines.map(tidied).filter { !$0.isEmpty }) }
        .filter { !$0.lines.isEmpty }

    var ticket = Ticket()
    if let payload = evidence.barcode?.payload, !payload.isEmpty { ticket.qr = payload }
    ticket.readingCount = readings.count

    let text = readings.first { $0.origin == .textLayer }
    let ocr = readings.first { $0.origin == .ocr }

    switch (text, ocr) {
    case (nil, nil):
        break
    case (let only?, nil), (nil, let only?):
        let found = guess(only.lines, calendar: calendar)
        let support = TicketSupport(only.origin)
        ticket.artist = found.artist
        ticket.artistSupport = found.artist.map { _ in support }
        ticket.venue = found.venue
        ticket.venueSupport = found.venue.map { _ in support }
        ticket.date = found.date
        ticket.dateSupport = found.date.map { _ in support }
    case (let text?, let ocr?):
        let t = guess(text.lines, calendar: calendar)
        let o = ocrGuess(ocr.lines, besideTextLayer: text.lines, calendar: calendar)

        func names(_ t: String?, _ o: String?) -> (String, TicketSupport)? {
            crossCheck(t, o,
                       same: { folded($0) == folded($1) },
                       ocrSaw: { value in ocr.lines.contains { folded($0).contains(folded(value)) } },
                       textSaw: { value in text.lines.contains { folded($0).contains(folded(value)) } },
                       ocrIsBetter: { letterCount($1) > letterCount($0) })
        }
        (ticket.artist, ticket.artistSupport) = split(names(t.artist, o.artist))
        (ticket.venue, ticket.venueSupport) = split(names(t.venue, o.venue))
        (ticket.date, ticket.dateSupport) = split(crossCheck(
            t.date, o.date,
            same: { $0 == $1 },
            ocrSaw: { day in ocr.lines.contains { readDate($0, calendar: calendar) == day } },
            textSaw: { day in text.lines.contains { readDate($0, calendar: calendar) == day } },
            // Two days neither reading backs the other on: no count of letters
            // settles that, so the text layer's own stands.
            ocrIsBetter: { _, _ in false }))
    }

    return ticket.isEmpty ? .nothingUsable : .ticket(ticket)
}

/// OCR's own answer, read beside a text layer: first from the lines the text layer
/// also has, and then — only for a field those leave empty — from every line.
///
/// The fallback may not hand one line to both names. The two passes order their lines
/// differently, so without that check a line the first pass made the venue could come
/// back from the second as the artist too.
private func ocrGuess(_ lines: [String], besideTextLayer text: [String],
                      calendar: Calendar) -> Guess {
    let textKeys = Set(text.map(folded))
    var checked = guess(lines.filter { textKeys.contains(folded($0)) }, calendar: calendar)
    let loose = guess(lines, calendar: calendar)
    if checked.artist == nil, let artist = loose.artist,
       folded(artist) != checked.venue.map(folded) {
        checked.artist = artist
    }
    if checked.venue == nil, let venue = loose.venue,
       folded(venue) != checked.artist.map(folded) {
        checked.venue = venue
    }
    if checked.date == nil { checked.date = loose.date }
    return checked
}

/// One field's two candidates, compared. See `parseTicketFields` for the order.
private func crossCheck<Value>(_ t: Value?, _ o: Value?,
                               same: (Value, Value) -> Bool,
                               ocrSaw: (Value) -> Bool,
                               textSaw: (Value) -> Bool,
                               ocrIsBetter: (Value, Value) -> Bool) -> (Value, TicketSupport)? {
    switch (t, o) {
    case (nil, nil): return nil
    case (let t?, nil): return (t, .textLayer)
    case (nil, let o?): return (o, .ocr)
    case (let t?, let o?):
        if same(t, o) { return (t, .both) }
        if ocrSaw(t) { return (t, .textLayer) }
        if textSaw(o) { return (o, .ocr) }
        return ocrIsBetter(t, o) ? (o, .ocr) : (t, .textLayer)
    }
}

private func split<Value>(_ pick: (Value, TicketSupport)?) -> (Value?, TicketSupport?) {
    (pick?.0, pick?.1)
}

// MARK: - One reading's guess

/// What the rules make of one reading on its own, before it is compared with another.
private struct Guess {
    var artist: String?
    var venue: String?
    var date: Date?
}

/// The rules, in the order they are trusted (#526, the same on both platforms):
///
/// 1. **A labelled field** says what it is: `Artist: Big Thief`.
/// 2. **`X at Y`** says which half is which.
/// 3. **The lines around the date.** On a layout with no vendor-styled caps line, the
///    artist prints just above the date and the venue just below it.
/// 4. **The first lines**, with any vendor-styled caps line moved ahead of the prose.
///
/// Rules 3 and 4 are guesses, and they are made on purpose (#526 chose guessing over
/// iOS's old labelled-only rule): the person reviews every read that is not backed by
/// both readings, and a guess pre-filled into that prompt is one less field to type.
/// What keeps a guess honest is what is *not* a candidate — see `isGuessable`.
///
/// `lines` arrive in reading order; the first match down the page wins.
private func guess(_ lines: [String], calendar: Calendar) -> Guess {
    var found = Guess()
    var dateLines = Set<Int>()
    var dateIndex: Int?

    for (index, line) in lines.enumerated() {
        guard let day = readDate(line, calendar: calendar) else { continue }
        dateLines.insert(index)
        if found.date == nil {
            found.date = day
            dateIndex = index
        }
    }

    // Lines a rule has used, and label lines whether or not their value read: a
    // label's line is never a guess at something else.
    var taken = Set<Int>()
    for (index, line) in lines.enumerated() {
        if isLabelLine(line) { taken.insert(index) }
        if let (value, usedNext) = readLabelled(line, labels: artistLabels, next: lines[safe: index + 1]),
           found.artist == nil {
            found.artist = value
            if usedNext { taken.insert(index + 1) }
        }
        if let (value, usedNext) = readLabelled(line, labels: venueLabels, next: lines[safe: index + 1]),
           found.venue == nil {
            found.venue = value
            if usedNext { taken.insert(index + 1) }
        }
    }

    if found.artist == nil || found.venue == nil {
        for (index, line) in lines.enumerated()
        where !dateLines.contains(index) && !taken.contains(index) {
            guard let (left, right) = readAtSeparator(line, calendar: calendar) else { continue }
            if found.artist == nil { found.artist = left }
            if found.venue == nil { found.venue = right }
            taken.insert(index)
            break
        }
    }

    guard found.artist == nil || found.venue == nil else { return found }

    let pool = lines.indices.filter {
        !dateLines.contains($0) && !taken.contains($0) && isGuessable(lines[$0])
    }
    let shouty = pool.filter { isShouty(lines[$0]) }

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
    if shouty.isEmpty, let dateIndex,
       let above = pool.last(where: { $0 < dateIndex }),
       let below = pool.first(where: { $0 > dateIndex }) {
        if found.artist == nil { found.artist = lines[above] }
        if found.venue == nil { found.venue = lines[below] }
        return found
    }
    var ordered = (shouty + pool.filter { !shouty.contains($0) }).makeIterator()
    if found.artist == nil { found.artist = ordered.next().map { lines[$0] } }
    if found.venue == nil { found.venue = ordered.next().map { lines[$0] } }
    return found
}

// MARK: - Artist and venue

private let artistLabels = ["artist", "artists", "act", "performer", "performing", "headliner"]
private let venueLabels = ["venue", "location", "place", "where", "hall"]

/// `Artist: …` or `Venue: …`, whatever follows the colon.
private func isLabelLine(_ line: String) -> Bool {
    guard let colon = line.firstIndex(of: ":") else { return false }
    let label = line[line.startIndex..<colon].trimmingCharacters(in: .whitespaces).lowercased()
    return artistLabels.contains(label) || venueLabels.contains(label)
}

/// `Venue: Rockefeller`, or `Venue:` with the value on the line under it — OCR breaks
/// a label off its value about as often as it keeps them together. The flag says the
/// value came off the next line, so no later rule reads that line again.
private func readLabelled(_ line: String, labels: [String], next: String?) -> (String, Bool)? {
    guard let colon = line.firstIndex(of: ":") else { return nil }
    let label = line[line.startIndex..<colon]
        .trimmingCharacters(in: .whitespaces).lowercased()
    guard labels.contains(label) else { return nil }
    let value = tidied(String(line[line.index(after: colon)...]))
    if isUsableName(value) { return (value, false) }
    guard let next, isUsableName(next), !next.contains(":") else { return nil }
    return (next, true)
}

/// `Big Band at The Corner Hotel`, `Big Band live @ Sentrum Scene`. Near-universal on
/// tickets and event listings, and it says which half is which.
///
/// `ARTIST — VENUE` is deliberately still *not* split: a dash separates a great many
/// things on a ticket ("Doors — 19:00", "Stalls — Row F"). A dashed line can still be
/// guessed whole, as any other line can, but never cut in two.
private func readAtSeparator(_ line: String, calendar: Calendar) -> (String, String)? {
    guard let match = atSeparator.firstMatch(
        in: line, range: NSRange(line.startIndex..., in: line)),
          let left = line.substring(match.range(at: 1)),
          let right = line.substring(match.range(at: 2))
    else { return nil }
    let artist = tidied(left)
    let venue = tidied(right)
    guard isUsableName(artist), isUsableName(venue),
          readDate(artist, calendar: calendar) == nil,
          readDate(venue, calendar: calendar) == nil
    else { return nil }
    return (artist, venue)
}

private let atSeparator = try! NSRegularExpression(
    pattern: #"^(.+?)\s+(?:live\s+)?(?:at|@)\s+(.+)$"#, options: [.caseInsensitive])

/// A name has to be pronounceable and short enough to be one. Two letters is the
/// floor because an order line ("#4471193") has none, and eighty the ceiling because a
/// terms-and-conditions sentence is not a venue.
private func isUsableName(_ text: String) -> Bool {
    letterCount(text) >= 2 && text.count <= 80
}

/// Whether an unlabelled line may be guessed as a name at all. Everything excluded
/// here was confirmed against real tickets rather than guessed:
///
/// - **A digit.** An event or venue name essentially never carries one; a booking
///   code, a price, an address or a door time always does ("OPT2901", "NOK 690,00").
///   A band with a digit in its name costs one field to type, not a wrong answer.
/// - **A trailing `:`, `!` or `?`.** A heading a value sits under, or an ad's tagline
///   ("DEL EN OPPLEVELSE!") — never the name itself.
/// - **A `/`.** A seating category or a combined entrance ("STÅPLASS/STANDING").
/// - **A ticket vendor's own name**, on a line of its own. See `vendorNames`.
///
/// This used to guard only the caps lines on Android. #526 applies it to every guessed
/// line, the ones around the date included.
private func isGuessable(_ line: String) -> Bool {
    guard isUsableName(line) else { return false }
    if line.unicodeScalars.contains(where: { CharacterSet.decimalDigits.contains($0) }) { return false }
    if line.contains("/") { return false }
    if let last = line.last, ":!?".contains(last) { return false }
    return !vendorNames.contains(folded(line))
}

/// Ticket vendors whose name prints on the ticket as a line of its own, folded.
///
/// A logo is a picture, and beside a text layer the cross-check already outvotes it.
/// A scan has no text layer to do that, so there the name itself is the only tell:
/// `TICKETLINE` on the Pixel was a scan's worth of evidence, and it became an artist.
/// A name, not a layout — nothing here assumes where a vendor prints anything.
private let vendorNames: Set<String> = [
    "billettservice", "billetto", "eventbrite", "eventim", "livenation", "seetickets",
    "ticketco", "ticketline", "ticketmaster", "tikkio",
]

/// At least four letters in five uppercase: a vendor's stylised event or venue line,
/// not the prose above or below it on the page. Only ever asked of a guessable line.
private func isShouty(_ line: String) -> Bool {
    let letters = line.filter(\.isLetter)
    guard letters.count >= 2 else { return false }
    let upper = letters.filter(\.isUppercase).count
    return Double(upper) / Double(letters.count) >= 0.8
}

private func letterCount(_ text: String) -> Int {
    text.unicodeScalars.filter { CharacterSet.letters.contains($0) }.count
}

/// What two readings of one line have in common: case, whitespace and punctuation
/// dropped. OCR loses a space after a comma as readily as it keeps it, and the text
/// layer never does.
private func folded(_ text: String) -> String {
    var key = ""
    key.unicodeScalars.append(
        contentsOf: text.lowercased().unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) })
    return key
}

// MARK: - The date

/// The one field a wrong answer is most costly on, so the patterns are explicit and
/// ordered rather than left to a locale-guessing formatter.
///
/// Ambiguity has exactly one rule: **day first** where nothing decides it. `03/04/2026`
/// is the 3rd of April. Where one number is over twelve it decides by itself, in
/// either direction. This is written down because it is the case the two platforms are
/// most likely to answer differently by accident.
///
/// Two-digit years are not read at all: `14/09/26` could be a year or a day, and the
/// confirmation prompt is a cheaper place to resolve that than a guess is.
private func readDate(_ line: String, calendar: Calendar) -> Date? {
    for pattern in datePatterns {
        let range = NSRange(line.startIndex..., in: line)
        guard let match = pattern.regex.firstMatch(in: line, range: range) else { continue }
        var parts: [String] = []
        for group in 1..<match.numberOfRanges {
            guard let text = line.substring(match.range(at: group)) else { break }
            parts.append(text)
        }
        guard parts.count == 3, let ymd = pattern.read(parts) else { continue }
        var components = DateComponents()
        components.year = ymd.0
        components.month = ymd.1
        components.day = ymd.2
        guard ymd.1 >= 1, ymd.1 <= 12, ymd.2 >= 1, ymd.2 <= 31, ymd.0 >= 1900, ymd.0 <= 2999,
              let date = calendar.date(from: components),
              calendar.component(.day, from: date) == ymd.2,
              calendar.component(.month, from: date) == ymd.1
        else { continue }
        return calendar.startOfDay(for: date)
    }
    return nil
}

private struct DatePattern {
    let regex: NSRegularExpression
    /// The three captures, as year, month, day.
    let read: ([String]) -> (Int, Int, Int)?
}

/// English and Norwegian month prefixes. A language is not a vendor: this app is used
/// where its user buys tickets, and `14. september 2026` is not an exotic layout there.
private let monthNames: [String: Int] = [
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "mai": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "okt": 10, "nov": 11, "dec": 12, "des": 12,
]

private let monthAlternation = monthNames.keys.sorted().joined(separator: "|")

private let datePatterns: [DatePattern] = [
    // 2026-09-14, 2026/09/14
    DatePattern(regex: rx(#"(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})"#)) {
        guard let y = Int($0[0]), let m = Int($0[1]), let d = Int($0[2]) else { return nil }
        return (y, m, d)
    },
    // 14 September 2026, 14. sep. 2026, 14th Sept 2026
    DatePattern(regex: rx(#"(\d{1,2})(?:st|nd|rd|th)?[.,]?\s*(?:of\s+)?(\#(monthAlternation))[a-zæøå]*\.?[\s,]+(\d{4})"#)) {
        guard let d = Int($0[0]), let m = monthNames[$0[1].lowercased()], let y = Int($0[2])
        else { return nil }
        return (y, m, d)
    },
    // September 14, 2026 / Sep 14 2026
    DatePattern(regex: rx(#"(\#(monthAlternation))[a-zæøå]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?[.,]?\s+(\d{4})"#)) {
        guard let m = monthNames[$0[0].lowercased()], let d = Int($0[1]), let y = Int($0[2])
        else { return nil }
        return (y, m, d)
    },
    // 14/09/2026, 14.09.2026, 14-09-2026 — day first unless a number says otherwise.
    DatePattern(regex: rx(#"(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})"#)) {
        guard let a = Int($0[0]), let b = Int($0[1]), let y = Int($0[2]) else { return nil }
        if a > 12, b <= 12 { return (y, b, a) }
        if b > 12, a <= 12 { return (y, a, b) }
        return (y, b, a)
    },
]

private func rx(_ pattern: String) -> NSRegularExpression {
    try! NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
}

// MARK: - Tidying

/// What a reader hands back, made comparable: whitespace collapsed, and the punctuation
/// a line break leaves stranded taken off either end.
///
/// A colon is **not** in that set, deliberately. It is the one piece of stranded
/// punctuation that means something: `Artist:` on its own line is a label whose value
/// broke onto the next one, and trimming it turns that line into a word.
private func tidied(_ text: String) -> String {
    let collapsed = text.split(whereSeparator: { $0.isWhitespace || $0.isNewline })
        .joined(separator: " ")
    return collapsed.trimmingCharacters(in: CharacterSet(charactersIn: " \t.,;-–—|·•"))
}

private extension String {
    func substring(_ range: NSRange) -> String? {
        guard range.location != NSNotFound, let r = Range(range, in: self) else { return nil }
        return String(self[r])
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

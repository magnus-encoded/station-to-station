import Foundation

/// A PDF shared into the app, read for what it can prove about a night (#412, #408).
///
/// **Evidence, never a Gig.** Every field is optional and independently found: a
/// **Ticket** that yielded only an **Admission** is as real a Ticket as one that
/// yielded everything. What turns one into a **Gig** is a person confirming it, through the
/// same local-planned-gig path a hand-typed night already uses.
///
/// Shared with the Share Extension target, which is why nothing here knows about
/// `FmSetlist`, PDFKit or Vision. The extension extracts; this parses; the app routes.
struct Ticket: Codable, Equatable, Sendable {
    /// Every **Admission** the ticket yielded, in page order (#441), kept whatever the
    /// text parse managed and untouched by a person's corrections to it (story 16).
    ///
    /// Payloads only, never the crop the locator found them in (`TicketBarcode.image`):
    /// a **Ticket** is what crosses into the App Group, and what the app stores is the
    /// payload the Room redraws from.
    var admissions: [Admission] = []
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

    var isEmpty: Bool { admissions.isEmpty && artist == nil && venue == nil && date == nil }

    /// Everything, and therefore the only shape allowed past the confirmation prompt.
    /// Three facts out of four is not "nearly right", it is a guess with a gap in it.
    /// Any **Admission** counts, whatever its symbology (#441): a Code 128 gets you in
    /// as surely as a QR, whether or not the Room can redraw it yet.
    var isComplete: Bool { !admissions.isEmpty && artist != nil && venue != nil && date != nil }

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
    /// Every **Admission** was redrawn in its own symbology and read back as itself
    /// (#441, story 29). `routeTicket` asks this beside `isComplete`/`canSkipPrompt`
    /// before it acts without the person: a ticket the app cannot show at the door is
    /// shown to them at import instead, while they still hold the PDF. Not part of
    /// `canSkipPrompt`, which is the shared fixtures' `skipsPrompt` and a property of
    /// the *read*: what a platform can redraw is not the same on both (CoreImage has no
    /// Data Matrix), so it is not in the corpus both twins assert.
    var redrawsEveryAdmission: Bool { admissions.allSatisfy { $0.redrawable == true } }
}

extension Ticket {
    private enum LegacyKeys: String, CodingKey { case qr }

    /// Decoded field by field so a deposit written before #441 still drains: its single
    /// `qr` reads as one uncorroborated QR **Admission** on page 0, as the stored
    /// `ticketQr` does. In an extension so the memberwise initializer stays.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        admissions = (try? c.decodeIfPresent([Admission].self, forKey: .admissions)) ?? nil ?? []
        artist = (try? c.decodeIfPresent(String.self, forKey: .artist)) ?? nil
        venue = (try? c.decodeIfPresent(String.self, forKey: .venue)) ?? nil
        date = (try? c.decodeIfPresent(Date.self, forKey: .date)) ?? nil
        artistSupport = (try? c.decodeIfPresent(TicketSupport.self, forKey: .artistSupport)) ?? nil
        venueSupport = (try? c.decodeIfPresent(TicketSupport.self, forKey: .venueSupport)) ?? nil
        dateSupport = (try? c.decodeIfPresent(TicketSupport.self, forKey: .dateSupport)) ?? nil
        readingCount = (try? c.decodeIfPresent(Int.self, forKey: .readingCount)) ?? nil
        if admissions.isEmpty,
           let legacy = try? decoder.container(keyedBy: LegacyKeys.self),
           let qr = (try? legacy.decodeIfPresent(Data.self, forKey: .qr)) ?? nil, !qr.isEmpty {
            admissions = [Admission(payload: qr, symbology: qrSymbology)]
        }
    }
}

/// One scannable barcode — the right of entry for one person (#441, `CONTEXT.md`). A
/// **Ticket** yields one or more. Field for field with Android's `Admission`.
///
/// `payload` is the decoded payload, byte for byte as the evidence carried it: Vision's
/// decoded text as UTF-8, as Android stores zxing's, and `payloadData` only for a code
/// with no text (iOS 17+) — a binary payload is lossy on 16 and unverified on 17
/// (`VisionBarcodeLocator`). `symbology` is
/// `fixtures/ticket/README.md`'s name for the format it was printed in. `page` is the
/// zero-based page it was first found on. `corroborated` says the ticket's own text
/// prints the same code — evidence recorded, never a reason to drop one that isn't.
///
/// `redrawable` is the check at import (story 29, `checkedForRedraw` in the app): the
/// Admission redrawn in its own symbology read back as the same payload. Nil until the
/// app has asked — the Share Extension never does — and nil counts as no. Never
/// deposited and never stored: it is left out of the coding keys, `StoredAdmission` has
/// no field for it, and the Room asks again rather than trust a verdict written by an
/// older build.
struct Admission: Codable, Equatable, Sendable {
    var payload: Data
    var symbology: String
    var page: Int = 0
    var corroborated: Bool = false
    var redrawable: Bool? = nil

    private enum CodingKeys: String, CodingKey { case payload, symbology, page, corroborated }
}

/// `qr`: the QR's name in `fixtures/ticket/README.md`, and what an old `ticketQr` and a
/// `{"qr": …}` deposit always were.
let qrSymbology = "qr"

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
    /// Every barcode the source showed, in the order found. A ticket can carry several
    /// (one Admission each, or a QR beside a Code 128), so the evidence keeps them all;
    /// which of them are **Admissions** is `parseTicketFields`'s call alone (#441).
    var barcodes: [TicketBarcode] = []
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
    /// `fixtures/ticket/README.md`'s name for the format (`ticketSymbology`); nil for
    /// one that has no name there, which is then not an **Admission**.
    var symbology: String?
    /// The page it was found on, counted from 0 in the source's own page order.
    var page: Int = 0
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
    ticket.admissions = admissions(evidence)
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

/// The linear retail formats zxing reports beside a ticket's real code (the Android
/// probe, #441: EAN-13, EAN-8 and UPC-E hits on tickets whose Admission is a QR).
/// Unverified — possibly other print, possibly false positives.
private let retailSymbologies: Set<String> = ["ean13", "ean8", "upca", "upce"]

/// The 2D symbologies. A ticket that carries any of them is a ticket whose door code is
/// one of them: a linear code beside it is an order or reference number (the #441
/// review's QR beside an order-number Code 128). Provisional, like the retail rule.
private let matrixSymbologies: Set<String> = ["qr", "aztec", "pdf417", "datamatrix"]

/// The evidence's barcodes, reconciled into **Admissions** (`fixtures/ticket/README.md`,
/// "The Admissions"; the Kotlin twin is line for line):
///
/// 1. Only a barcode with a symbology and a non-empty payload can be one.
/// 2. Linear codes are dropped when any 2D code was found, on any page: beside a QR, a
///    Code 128 is the order number. Otherwise retail formats are dropped when anything
///    else was found, and kept only when they are all there is. Both provisional: a
///    ticket that really is an EAN keeps it, and one beside a QR loses a probable false
///    positive.
/// 3. In page order, and within a page in the order found — sorted on both, because
///    `sorted` is not promised to be stable.
/// 4. One per payload, first kept: the same code on three pages is one **Admission**,
///    and the same payload in two symbologies keeps the first.
/// 5. Corroborated when the payload, as strict UTF-8 with whitespace and `*` taken out,
///    appears in some line of some reading with the same taken out.
private func admissions(_ evidence: TicketEvidence) -> [Admission] {
    let candidates: [(order: Int, payload: Data, symbology: String, page: Int)] =
        evidence.barcodes.enumerated().compactMap { order, barcode in
            guard let symbology = barcode.symbology, let payload = barcode.payload, !payload.isEmpty
            else { return nil }
            return (order, payload, symbology, barcode.page)
        }
    let kept = candidates.contains(where: { matrixSymbologies.contains($0.symbology) })
        ? candidates.filter { matrixSymbologies.contains($0.symbology) }
        : candidates.allSatisfy { retailSymbologies.contains($0.symbology) }
        ? candidates
        : candidates.filter { !retailSymbologies.contains($0.symbology) }
    let printed = evidence.readings.flatMap(\.lines).map(printedKey)
    var seen = Set<Data>()
    return kept
        .sorted { ($0.page, $0.order) < ($1.page, $1.order) }
        .filter { seen.insert($0.payload).inserted }
        .map { found in
            // Foundation's UTF-8 decode is strict: nil, never a U+FFFD guess.
            let key = String(data: found.payload, encoding: .utf8).map(printedKey) ?? ""
            return Admission(payload: found.payload, symbology: found.symbology, page: found.page,
                             corroborated: !key.isEmpty && printed.contains { $0.contains(key) })
        }
}

/// Space, tab, newline, carriage return and `*` (a Code 39-style printed delimiter,
/// `*TESTQRAA1*`) taken out: exactly those, the same set as the Kotlin twin. Not
/// `whitespacesAndNewlines`, whose members differ from Kotlin's `isWhitespace` (that one
/// also takes U+001C–001F, a GS1 payload's GS among them).
private let printedKeyDrops: Set<Unicode.Scalar> = [" ", "\t", "\n", "\r", "*"]

private func printedKey(_ text: String) -> String {
    String(String.UnicodeScalarView(text.unicodeScalars.filter { !printedKeyDrops.contains($0) }))
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

    // The night is the first date down the page that is not a purchase date; a
    // purchase date is taken only when it is the only kind there is. See
    // `isPurchaseDate`.
    var purchase: (day: Date, index: Int)?
    for (index, line) in lines.enumerated() {
        guard let day = readDate(line, calendar: calendar) else { continue }
        dateLines.insert(index)
        if isPurchaseDate(at: index, in: lines) {
            if purchase == nil { purchase = (day, index) }
        } else if found.date == nil {
            found.date = day
            dateIndex = index
        }
    }
    if found.date == nil, let purchase {
        found.date = purchase.day
        dateIndex = purchase.index
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
/// - **A caps banner about the ticket itself.** See `isTicketBanner`.
///
/// This used to guard only the caps lines on Android. #526 applies it to every guessed
/// line, the ones around the date included.
private func isGuessable(_ line: String) -> Bool {
    guard isUsableName(line) else { return false }
    if line.unicodeScalars.contains(where: { CharacterSet.decimalDigits.contains($0) }) { return false }
    if line.contains("/") { return false }
    if let last = line.last, ":!?".contains(last) { return false }
    return !isTicketBanner(line)
}

/// A caps line with the word for a ticket in it — `TICKETLINE`, `TICKETMASTER`,
/// `BILLETTSERVICE`, `E-TICKET` — is the vendor's masthead or a heading about the
/// ticket, never the night's artist or venue.
///
/// `TICKETLINE` became an artist on the Pixel (#526), and the probe found it in the
/// text layer as well as in OCR, at the top of both. So neither the cross-check nor its
/// position on the page tells it from `MORK WATER` two lines below; what does is that it
/// names the ticket. The words are the ticket's own vocabulary, not a list of vendors,
/// and only a caps line is asked: a sentence that mentions a ticket is prose, and prose
/// already ranks below every caps line.
private func isTicketBanner(_ line: String) -> Bool {
    guard isShouty(line) else { return false }
    let key = folded(line)
    return ticketWords.contains { key.contains($0) }
}

/// "Ticket" in English, Norwegian and Danish, and Swedish. Matched inside a word, so a
/// compound (`TICKETLINE`, `BILLETTSERVICE`) counts.
private let ticketWords = ["ticket", "billett", "biljett"]

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

/// A date that says when the ticket was bought, not when the night is: one on a line
/// with a purchase word in it (`Kjøpt 01.05.2025`, `Order date: 01.05.2025`), or the
/// value under a purchase label that broke onto a line of its own (`Kjøpsdato:`, then
/// `01.05.2025`).
///
/// An order block often prints above the event on a text layer — the Eventim ticket
/// the Android probe read gave its purchase date first (#526) — so "the first date
/// down the page" alone is not enough. A purchase date is not thrown away, only
/// passed over: with nothing else to go on it is still the best-known date, and the
/// person reviews the read.
private func isPurchaseDate(at index: Int, in lines: [String]) -> Bool {
    if mentionsPurchase(lines[index]) { return true }
    guard let above = lines[safe: index - 1],
          above.trimmingCharacters(in: .whitespaces).hasSuffix(":") else { return false }
    return mentionsPurchase(above)
}

private func mentionsPurchase(_ line: String) -> Bool {
    let lower = line.lowercased()
    return purchaseWords.contains { lower.contains($0) }
}

/// Matched inside a word: `kjøp` covers kjøpt, kjøpsdato and kjøpstidspunkt; `bestil`
/// bestilt and bestillingsdato; `order` ordered and order date; `ordre` ordredato.
private let purchaseWords = ["kjøp", "bestil", "ordre", "order", "purchase", "booked", "booking"]

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

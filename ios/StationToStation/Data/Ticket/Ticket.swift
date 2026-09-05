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
    /// day-of view (a separate issue) has to tolerate that.
    var qr: Data?
    var artist: String?
    var venue: String?
    /// Midnight of the night, in the reader's own calendar — a **Ticket** is dated at
    /// day precision and nothing finer is ever inferred from it (ADR-0002).
    var date: Date?

    var isEmpty: Bool { qr == nil && artist == nil && venue == nil && date == nil }

    /// Everything, and therefore the only shape allowed past the confirmation prompt.
    /// Three facts out of four is not "nearly right", it is a guess with a gap in it.
    var isComplete: Bool { qr != nil && artist != nil && venue != nil && date != nil }
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

/// The pure half of ticket reading: already-extracted QR bytes and text blocks in,
/// a best-effort **Ticket** or a clear failure out.
///
/// Pre-extracted on purpose — the seam is here so the rules can be tested without a
/// PDF renderer, an OCR engine or a device, and so the Android twin can be held to
/// the same answers on the same inputs.
///
/// `blocks` arrive in the order the extractor read them; the first match down the
/// page wins, which is the only ordering rule and is the same one on both platforms.
func parseTicket(qr: Data?, blocks: [String], calendar: Calendar = .current) -> TicketParse {
    let lines = blocks.map(tidied).filter { !$0.isEmpty }

    var ticket = Ticket(qr: qr?.isEmpty == true ? nil : qr)
    var dateLines = Set<Int>()

    for (index, line) in lines.enumerated() {
        guard let day = readDate(line, calendar: calendar) else { continue }
        dateLines.insert(index)
        if ticket.date == nil { ticket.date = day }
    }

    for (index, line) in lines.enumerated() {
        if let found = readLabelled(line, labels: artistLabels, next: lines[safe: index + 1]),
           ticket.artist == nil {
            ticket.artist = found
        }
        if let found = readLabelled(line, labels: venueLabels, next: lines[safe: index + 1]),
           ticket.venue == nil {
            ticket.venue = found
        }
    }

    if ticket.artist == nil || ticket.venue == nil {
        for (index, line) in lines.enumerated() where !dateLines.contains(index) {
            guard let (left, right) = readAtSeparator(line, calendar: calendar) else { continue }
            if ticket.artist == nil { ticket.artist = left }
            if ticket.venue == nil { ticket.venue = right }
            break
        }
    }

    return ticket.isEmpty ? .nothingUsable : .ticket(ticket)
}

// MARK: - Artist and venue

// Only two rules yield an artist or a venue, and the shortness of that list is the
// decision rather than an omission.
//
// A labelled field says what it is. `ARTIST — VENUE`, the obvious third rule, is
// deliberately *not* one: a dash separates a great many things on a ticket
// ("Doors — 19:00", "Stalls — Row F"), and it is the one rule that could hand a
// complete-looking parse a wrong artist, which is exactly what skips the
// confirmation prompt. A missing artist costs a person one field to type; a
// confidently wrong one corrupts the record and looks fine afterwards.

private let artistLabels = ["artist", "artists", "act", "performer", "performing", "headliner"]
private let venueLabels = ["venue", "location", "place", "where", "hall"]

/// `Venue: Rockefeller`, or `Venue:` with the value on the line under it — OCR breaks
/// a label off its value about as often as it keeps them together.
private func readLabelled(_ line: String, labels: [String], next: String?) -> String? {
    guard let colon = line.firstIndex(of: ":") else { return nil }
    let label = line[line.startIndex..<colon]
        .trimmingCharacters(in: .whitespaces).lowercased()
    guard labels.contains(label) else { return nil }
    let value = tidied(String(line[line.index(after: colon)...]))
    if isUsableName(value) { return value }
    guard let next, isUsableName(next), !next.contains(":") else { return nil }
    return next
}

/// `Big Band at The Corner Hotel`, `Big Band live @ Sentrum Scene`. Near-universal on
/// tickets and event listings, and it says which half is which — unlike a dash.
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
/// floor because an order line ("#4471193", "NOK 690,00") has none, and eighty the
/// ceiling because a terms-and-conditions sentence is not a venue.
private func isUsableName(_ text: String) -> Bool {
    let letters = text.unicodeScalars.filter { CharacterSet.letters.contains($0) }.count
    return letters >= 2 && text.count <= 80
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

/// What OCR hands back, made comparable: whitespace collapsed, and the punctuation a
/// line break leaves stranded taken off either end.
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

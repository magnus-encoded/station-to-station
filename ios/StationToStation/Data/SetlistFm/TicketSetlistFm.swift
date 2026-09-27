import Foundation

// The setlist.fm half of a **Ticket** the confirm prompt is showing (#531): what the
// import's lookup offered, and what the person's answer comes to. Pure, so the prompt's
// three decisions — a sure hit preselected, an edited artist or date hiding the list,
// "None of these" rejecting every hit offered — are held by tests rather than by the
// sheet. Term for term with Android's `TicketSetlistFm.kt`; here it rides on
// `TicketDraft`, where Android's rides on `PendingTicket`.

/// The heading of every setlist.fm "is it this one?" list, and the Gig screen's chip.
/// Word for word with Android's `POSSIBLE_MATCH_TITLE`.
let possibleMatchTitle = "Possible match on setlist.fm"

/// What the import's lookup found for a ticket that still needs confirming. `artist` and
/// `date` (dd-MM-yyyy) are what was looked up; `lookedUpAt` is when, epoch millis — the
/// request went out whatever came back. `candidates` may be empty: the lookup ran and
/// found nothing, which is still worth stamping on the Gig the prompt lands on.
struct TicketSetlistFm: Equatable {
    var candidates: [SetlistFmCandidate]
    var preselectedId: String?
    var artist: String
    var date: String
    var lookedUpAt: Int64

    /// Whether the prompt shows `candidates` for the `artist` and `date` currently in its
    /// fields. Once either is edited the person has named another night, and a list found
    /// for the old one would be answering a question nobody is asking (decision 2).
    func offeredFor(artist: String, date: String) -> Bool {
        !candidates.isEmpty && sameSearch(artist: artist, date: date)
    }

    /// Whether `artist` and `date` are still the ones looked up, give or take spacing.
    func sameSearch(artist: String, date: String) -> Bool {
        guard let night = parseFmDate(date.trimmingCharacters(in: .whitespacesAndNewlines)) else { return false }
        return artist.trimmingCharacters(in: .whitespacesAndNewlines)
            == self.artist.trimmingCharacters(in: .whitespacesAndNewlines)
            && night == parseFmDate(self.date)
    }

    /// What Add with `chosenId` ticked comes to. A candidate chosen is that hit and
    /// nothing rejected; nil ("None of these") rejects every candidate offered (decision
    /// 3). An edited search offered nothing, so it neither chooses nor rejects (decision
    /// 2) — but only a search still standing stamps the lookup.
    func answer(artist: String, date: String, chosenId: String?) -> TicketSetlistFmAnswer {
        guard sameSearch(artist: artist, date: date) else { return .unasked }
        if let chosen = candidates.first(where: { $0.setlist.id == chosenId }) {
            return TicketSetlistFmAnswer(chosen: chosen.setlist, rejectedIds: [], lookedUpAt: lookedUpAt)
        }
        return TicketSetlistFmAnswer(chosen: nil, rejectedIds: candidates.map(\.setlist.id), lookedUpAt: lookedUpAt)
    }

    /// By the hits' ids: `FmSetlist` is not `Equatable`, and an id is what a hit is.
    static func == (a: TicketSetlistFm, b: TicketSetlistFm) -> Bool {
        a.candidates.map(\.setlist.id) == b.candidates.map(\.setlist.id)
            && a.preselectedId == b.preselectedId && a.artist == b.artist
            && a.date == b.date && a.lookedUpAt == b.lookedUpAt
    }
}

/// `TicketSetlistFm.answer`: the hit to take (`chosen`), or the ids to remember as not
/// this night (`rejectedIds`), and the lookup to stamp (`lookedUpAt`, nil for none).
struct TicketSetlistFmAnswer: Equatable {
    let chosen: FmSetlist?
    let rejectedIds: [String]
    let lookedUpAt: Int64?

    /// The prompt showed no candidates for what was saved: nothing chosen, nothing rejected.
    static let unasked = TicketSetlistFmAnswer(chosen: nil, rejectedIds: [], lookedUpAt: nil)

    /// Whether a local Gig's stored lookup has anything to learn from this answer.
    var recordsAnything: Bool { lookedUpAt != nil || !rejectedIds.isEmpty }

    /// `lookup` with this answer written in: stamped, and the rejections added.
    func applyTo(_ lookup: StoredSetlistFmLookup) -> StoredSetlistFmLookup {
        var next = lookedUpAt.map { lookup.lookedUp(at: max($0, lookup.lastLookupAt ?? 0)) } ?? lookup
        var seen = Set<String>()
        next.rejectedIds = (next.rejectedIds + rejectedIds).filter { seen.insert($0).inserted }
        return next
    }

    static func == (a: TicketSetlistFmAnswer, b: TicketSetlistFmAnswer) -> Bool {
        a.chosen?.id == b.chosen?.id && a.rejectedIds == b.rejectedIds && a.lookedUpAt == b.lookedUpAt
    }
}

extension StoredSetlistFmLookup {
    /// The hits a "Possible match on setlist.fm" chip draws: the stored snapshot, held to
    /// the ids still pending (the authority), best first. Empty while ids are pending means
    /// the snapshot was lost and the chip fetches each id when tapped.
    func chipHits() -> [StoredSetlistFmHit] {
        var seen = Set<String>()
        return pendingHits.filter { pendingHitIds.contains($0.id) && seen.insert($0.id).inserted }
    }
}

extension StoredSetlistFmHit {
    /// A candidate row with no question to ask: `artist — venue, city — date`.
    func line() -> String {
        let place = [venue, city]
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
        return [artist.trimmingCharacters(in: .whitespacesAndNewlines), place,
                date.trimmingCharacters(in: .whitespacesAndNewlines)]
            .filter { !$0.isEmpty }
            .joined(separator: " — ")
    }
}

extension FmSetlist {
    /// A stored hit as the matcher would have held it, for a chip whose snapshot was
    /// fetched afresh.
    func asStoredHit() -> StoredSetlistFmHit {
        StoredSetlistFmHit(id: id, artist: artist?.name ?? "", venue: venue?.name ?? "",
                           city: venue?.city?.name ?? "", date: eventDate ?? "", venueLevel: nil)
    }
}

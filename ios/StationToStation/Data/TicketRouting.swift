import Foundation

// Deliberately *not* in `Data/Ticket/`, which is the folder the Share Extension
// compiles: routing needs `FmSetlist` and the nights already on the **Line**, and the
// extension has neither and must not learn them. The extension extracts and parses;
// the app is the only thing that decides what a **Ticket** becomes.

/// What the app should do with a **Ticket** the extension left in the inbox.
enum TicketRoute: Equatable {
    /// The night is already on the **Line**. Nothing is minted; the QR is kept
    /// against the night that was already there.
    case match(String)
    /// Mint an ordinary local planned **Gig**, no prompt. The *only* case that skips
    /// the prompt, and it is narrow on purpose — see `routeTicket`.
    case add(Ticket)
    /// Show the person what was read and let them confirm or fill in the rest. The
    /// normal outcome, not the exception.
    case confirm(Ticket)
    /// The PDF yielded nothing at all. Still shown — an honest blank, not silence.
    case unreadable
}

/// Where a shared **Ticket** goes.
///
/// **Confirm-first is the rule.** The parse function's job is to report what it found;
/// this one's is to route it, and all it routes past a person is a parse with nothing
/// left to ask about: a QR, an artist, a venue and a date, all four present. That is
/// the same rule Android's `TicketRouting` landed with in #411.
///
/// **A past night is never minted silently** even when the parse is complete. An old
/// ticket found while clearing out an inbox is exactly the case that would otherwise
/// plant a phantom plan above today (#408, story 13), and the cost of asking is one
/// tap. `now` is a parameter so that rule is testable rather than clock-dependent.
///
/// A match wins over a mint whether the night ahead or behind: sharing the ticket for
/// a night already logged some other way must be safe to do twice.
func routeTicket(_ parse: TicketParse,
                 knownNights: [FmSetlist],
                 now: Date,
                 calendar: Calendar = .current) -> TicketRoute {
    guard case .ticket(let ticket) = parse else { return .unreadable }
    if let known = knownNight(ticket, among: knownNights, calendar: calendar) {
        return ticket.isComplete ? .match(known.id) : .confirm(ticket)
    }
    guard ticket.isComplete, let date = ticket.date,
          date >= calendar.startOfDay(for: now)
    else { return .confirm(ticket) }
    return .add(ticket)
}

/// The night this **Ticket** is about, if the **Line** already holds it.
///
/// The same shape as the `onLine` check a committed **Programme** already uses: one
/// day, one artist, folded through `nameKey` so `Wilco (US)` and `Wilco` are one act.
/// A **Ticket** with no date matches nothing — a bare artist name is not a night.
///
/// The venue is a fallback identity rather than a second condition. It answers the
/// ticket that named a room and no act; it is never allowed to *veto* an artist match,
/// because one person seeing one artist twice on one day in two rooms is not a case
/// worth modelling, and a renamed venue is.
///
/// The day is compared as `dd-MM-yyyy` text rather than as two `Date`s. `localDate()`
/// reads a published date fixed to GMT and a **Ticket**'s date is the day the person
/// standing at the venue is living through — comparing those two directly lands a day
/// out wherever the zones disagree at midnight, which is the trap `Bill.swift` already
/// documents. `fmDate` is the one shape both sides of this app write.
func knownNight(_ ticket: Ticket,
                among nights: [FmSetlist],
                calendar: Calendar = .current) -> FmSetlist? {
    guard let date = ticket.date else { return nil }
    let day = fmDate(date, calendar: calendar)
    let artistKey = ticket.artist.map(nameKey)
    let venueKey = ticket.venue.map(nameKey)
    return nights.first { night in
        guard night.eventDate == day else { return false }
        if let artistKey, !artistKey.isEmpty {
            return nameKey(night.artist?.name ?? "") == artistKey
        }
        if let venueKey, !venueKey.isEmpty, let room = night.venue?.name {
            return nameKey(room) == venueKey
        }
        return false
    }
}

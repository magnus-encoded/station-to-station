import Foundation

/// The state of a **Gig**, as known — and from it, one decision about what the **Room**
/// offers and what sits in the **Alcove** (#177, Android's `ui/GigOffers.kt`).
///
/// The Swift twin, ported with its table rather than re-derived: the two platforms read
/// one cache and one night, and a rule that holds on one side and not the other is a
/// screen lying on exactly one phone.
///
/// **These are not stages in a queue.** A 1992 import has songs, was **Attended**, was
/// never checked into and has no **Log**; a night in a field is checked into, logged and
/// closed, with a record that is linked and empty. Both are ordinary. They are different
/// *amounts known* about a night, which is why this is a fold over four axes rather than
/// a lifecycle enum.
///
/// What it deliberately does **not** hold: media, playlists, thumbnails. They hang off a
/// night but decide nothing about what it offers.
struct GigAsKnown {
    /// When the night is, as coarse or fine as the source knows. Nil for undated.
    var window: Range<Date>? = nil
    /// `StoredAttendance.provenance`, or nil for a night with no claim at all.
    var provenance: String? = nil
    /// My **Log** of the night. Nil where I never started one.
    var log: StoredLog? = nil
    /// The setlist.fm record, once linked. Nil for a night setlist.fm has not heard of.
    var setlistId: String? = nil
    /// `FmSetlist.performed().count` on that record. A linked record can hold zero.
    var songCount: Int = 0
    /// The calendar entry made for this night, if one was.
    var calendarEvent: String? = nil
    /// The payload of the ticket's QR, as the ticket carried it (#414). Nil for a night
    /// no ticket was parsed for, which is most of them — a night bought at the door, an
    /// import from 1992, anything typed in by hand.
    var ticketQr: Data? = nil

    var checkedIn: Bool { provenance == "checked_in" }
    var linked: Bool { setlistId != nil }
}

/// The single fixture opposite the door: one step right, a destination. It holds exactly
/// one thing and it may be empty.
///
/// Empty is a legitimate answer, not a gap in the table. **The Alcove is empty while the
/// band plays** — you do not want an exit pointed at you mid-set — and closing the
/// **Log** is what furnishes it, which is what makes closing a meaningful act rather
/// than a bookkeeping tap.
///
/// The empty case is `empty` rather than Android's `NONE`: `.none` in Swift is also
/// `Optional`'s, and this value is read through an optional on every screen that has one.
enum Alcove {
    /// Nothing opposite the door. The **Room** is what you came for.
    case empty
    /// Put it in the calendar.
    case addToCalendar
    /// Open the entry already made — it holds the location, and does maps better.
    case openCalendar
    /// Hand the set to setlist.fm: the first unfinished thing on a night nobody posted.
    case setlistFm
    /// Make the playlist: once the night is recorded, this is what remains.
    case spotify
}

/// What is behind the **Curtain** — the **Window** onto a data source, and what pulling
/// it down asks for.
///
/// A returned instruction rather than the call site's choice, because two places
/// deciding when to fetch is how they drift. Mid-set there is no setlist to fetch; on a
/// finished night holding all its songs there is nothing to learn.
///
/// A failed pull changes nothing and shows nothing. Being offline costs nothing.
enum Curtain {
    /// Has the night moved? Ask about the event, not its songs.
    case checkEvent
    /// The artist's own songs, for a **Log** being typed with nothing posted yet.
    case catalogue
    /// Fetch the setlist: someone may have posted it, or filled an empty record.
    case fetchSetlist
    /// It is all here. Ask only whether it changed.
    case checkEdits
}

/// What pulling a `Curtain` down actually asks the plumbing to do — the dispatch, kept
/// pure and separate from the plumbing itself (ADR-0001).
///
/// `Curtain.checkEvent` has no consumer on either platform: no "did this event move"
/// endpoint exists, so it maps to nothing rather than to a fetch pretending to be one.
/// A no-op pull costs nothing, same as an offline one.
enum CurtainAction {
    case nothing
    case fetchCatalogue
    case fetchSetlist
}

func curtainAction(_ curtain: Curtain) -> CurtainAction {
    switch curtain {
    case .catalogue: return .fetchCatalogue
    case .fetchSetlist, .checkEdits: return .fetchSetlist
    case .checkEvent: return .nothing
    }
}

/// What is possible standing in the **Room**. Nothing here is ever taken away by the clock.
struct Room {
    /// Claim that I am here. Never gated on a GPS fix.
    var checkIn: Bool
    /// The **Log** and the media: only once there is something to record.
    var capture: Bool
    /// Hold the ticket up to be scanned (#414). True before the check-in and only where
    /// a ticket was actually parsed; the checked-in state is what replaces it, so the
    /// two are never both showing. A night with no ticket omits it rather than drawing
    /// an empty frame — there is nothing to hold up.
    var qr: Bool
}

/// What a **Gig** offers, decided once, rendered everywhere.
struct GigOffers {
    var room: Room
    var alcove: Alcove
    var curtain: Curtain
    /// The coarse phase, derived from the same value so it cannot disagree.
    var phase: GigLeaf
}

/// What this **Gig** offers, as known.
///
/// The rules, in the order they decide:
///
/// - **Data entry never appears before the set.** There is nothing to record until the
///   artist is playing. Checking in before the listed start widens the opening edge,
///   because standing there is the strongest evidence the thing has begun.
/// - **The Alcove is empty while the band plays, and closing the Log furnishes it.**
/// - **The Alcove holds the first unfinished thing.** A closed set nobody has recorded
///   needs publishing; once it is recorded — by me or by anyone — the playlist is what
///   remains. That is why one past **Room** shows setlist.fm and another shows Spotify.
/// - **An open Log with no linked record pulls the artist's catalogue.** Nobody has
///   posted a setlist mid-set; what helps is the artist's own songs.
/// - **Reopening a Log is free.** The offers follow it back, because remembering a song
///   three years later must cost nothing.
///
/// **Check-in is not an Alcove fixture.** It is how the **Room** and your body come to
/// agree — you can visit any room from the corridor at any time, and checking in is the
/// claim that you are actually in this one. So it lives in the **Room**, and location can
/// only promote it in the rendering. It is not a parameter here: under the table above,
/// check-in is already offered first wherever it is possible at all, so a location hint
/// would change no answer this function gives.
func gigOffers(_ gig: GigAsKnown, now: Date) -> GigOffers {
    let phase = gigLeaf(now: now, window: gig.window, checkedIn: gig.checkedIn)
    let started = phase != .plan
    let open = gig.log != nil && gig.log?.closed == false
    let closed = gig.log?.closed == true
    let recorded = gig.linked && gig.songCount > 0

    let alcove: Alcove
    if !started {
        // Still ahead: the calendar is the only useful action, and once made it is the
        // thing to open — it holds the location already.
        alcove = gig.calendarEvent != nil ? .openCalendar : .addToCalendar
    } else if open {
        // Mid-set, or a set still being typed: no exit pointed at you.
        alcove = .empty
    } else if recorded && (closed || gig.log == nil) {
        // A finished night that is on the record somewhere: the playlist is what is left.
        alcove = .spotify
    } else if closed {
        // A set I said was complete that nobody has posted. Includes a linked record
        // holding no songs, which is the same unfinished thing wearing an id.
        alcove = .setlistFm
    } else {
        // Started, nothing logged, nothing recorded: the **Room** is what you came for.
        alcove = .empty
    }

    let curtain: Curtain
    if !started {
        curtain = .checkEvent
    } else if phase != .publish && !gig.checkedIn {
        // In the window with no claim yet: still the event's own facts, not its songs.
        curtain = .checkEvent
    } else if recorded {
        curtain = .checkEdits
    } else if gig.linked || closed {
        // Nothing posted anywhere. A closed set is looking for its record; a set still
        // being typed is looking for the artist's songs.
        curtain = .fetchSetlist
    } else {
        curtain = .catalogue
    }

    return GigOffers(
        room: Room(
            // The night's own window, the same one a check-in already draws. Claiming
            // it later is what `attended` is for, and this never gates on a location.
            checkIn: gig.window?.contains(now) == true && !gig.checkedIn,
            capture: started,
            // Deliberately *not* gated on the window the check-in above reads. A ticket
            // is scanned at the door, and the door opens before the night's window does
            // — a QR the **Room** hides until the set starts is a QR you cannot get in
            // with. The check-in is what retires it, because being inside is the fact
            // that makes the barcode spent.
            qr: gig.ticketQr != nil && !gig.checkedIn
        ),
        alcove: alcove,
        curtain: curtain,
        phase: phase
    )
}

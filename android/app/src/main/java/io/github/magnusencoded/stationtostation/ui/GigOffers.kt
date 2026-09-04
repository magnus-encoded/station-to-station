package io.github.magnusencoded.stationtostation.ui

import io.github.magnusencoded.stationtostation.data.StoredAttendance
import io.github.magnusencoded.stationtostation.data.StoredLog
import java.time.LocalDateTime

/**
 * The state of a **Gig**, as known — and from it, one decision about what the room
 * offers (#129).
 *
 * `StoredGig` already describes itself as *"one night, as this app knows it"*. This is
 * that sentence made into a value the screen can hold: the time window, the attendance
 * claim, the **Log**, and the setlist record. Before this, every part of the **Gig**
 * screen worked the same question out again from a different subset, and they
 * disagreed — a night with fifteen songs printed "no setlist yet" directly above them.
 *
 * **These are not stages in a queue.** A 1992 import has songs, was **Attended**, was
 * never checked into and has no **Log**; a night in a field is checked into, logged and
 * closed, with a record that is linked and empty. Both are ordinary. They are different
 * *amounts known* about a night, which is why this is a fold over four axes rather than
 * a lifecycle enum.
 *
 * What it deliberately does **not** hold: media, playlists, thumbnails. They hang off a
 * night but decide nothing about what it offers, and pulling them in would make this a
 * screen-state god object.
 */
data class GigAsKnown(
    /** When the night is, as coarse or fine as the source knows. Null for undated. */
    val window: ClosedRange<LocalDateTime>? = null,
    /** [StoredAttendance.Provenance], or null for a night with no claim at all. */
    val provenance: String? = null,
    /** My **Log** of the night. Null where I never started one. */
    val log: StoredLog? = null,
    /** The setlist.fm record, once linked. Null for a night setlist.fm has not heard of. */
    val setlistId: String? = null,
    /** `FmSetlist.performed().size` on that record. A linked record can hold zero. */
    val songCount: Int = 0,
    /** The calendar entry made for this night, if one was. */
    val calendarEvent: String? = null,
    /**
     * The ticket's own QR, decoded off the PDF by #411 and carried here only for the
     * one fact below — presence, not content. Null for a night with no ticket at all,
     * and for every night imported from setlist.fm rather than a ticket.
     */
    val ticketQrBytes: ByteArray? = null,
) {
    val checkedIn: Boolean get() = provenance == StoredAttendance.Provenance.CHECKED_IN
    val linked: Boolean get() = setlistId != null
}

/**
 * The single fixture opposite the door: one step right, a destination. It holds exactly
 * one thing and it may be empty.
 *
 * Empty is a legitimate answer, not a gap in the table. **The alcove is empty while the
 * band plays** — you do not want an exit pointed at you mid-set — and closing the
 * **Log** is what furnishes it, which is what makes closing a meaningful act rather
 * than a bookkeeping tap.
 */
enum class Alcove {
    /** Nothing opposite the door. The room is what you came for. */
    NONE,

    /** Put it in the calendar. */
    ADD_TO_CALENDAR,

    /** Open the entry already made — it holds the location, and does maps better. */
    OPEN_CALENDAR,

    /** Hand the set to setlist.fm: the first unfinished thing on a night nobody posted. */
    SETLIST_FM,

    /** Make the playlist: once the night is recorded, this is what remains. */
    SPOTIFY,
}

/**
 * What is behind the **Curtain** — the **Window** onto a data source, and what pulling
 * it down asks for.
 *
 * A returned instruction rather than the call site's choice, because two places
 * deciding when to fetch is how they drift. Today pull-to-refresh calls one function
 * unconditionally: the same request on a night three weeks away, a night you are
 * standing at, and a night from 1992. Mid-set there is no setlist to fetch; on a
 * finished night holding all its songs there is nothing to learn.
 *
 * A failed pull changes nothing and shows nothing. Being offline costs nothing.
 */
enum class Curtain {
    /** Has the night moved? Ask about the event, not its songs. */
    CHECK_EVENT,

    /** The artist's own songs, for a **Log** being typed with nothing posted yet. */
    CATALOGUE,

    /** Fetch the setlist: someone may have posted it, or filled an empty record. */
    FETCH_SETLIST,

    /** It is all here. Ask only whether it changed. */
    CHECK_EDITS,
}

/**
 * What pulling a [Curtain] down actually asks the plumbing to do — the dispatch, kept
 * pure and separate from the plumbing itself (ADR-0001).
 *
 * [Curtain.CHECK_EVENT] has no consumer yet: no "did this event move" endpoint exists,
 * so it maps to nothing rather than to a fetch pretending to be one. A no-op pull costs
 * nothing, same as an offline one.
 */
enum class CurtainAction {
    NONE,
    FETCH_CATALOGUE,
    FETCH_SETLIST,
}

fun curtainAction(curtain: Curtain): CurtainAction = when (curtain) {
    Curtain.CATALOGUE -> CurtainAction.FETCH_CATALOGUE
    Curtain.FETCH_SETLIST, Curtain.CHECK_EDITS -> CurtainAction.FETCH_SETLIST
    Curtain.CHECK_EVENT -> CurtainAction.NONE
}

/** What is possible standing in the room. Nothing here is ever taken away by the clock. */
data class Room(
    /** Claim that I am here. Never gated on a GPS fix. */
    val checkIn: Boolean,
    /** The **Log** and the media: only once there is something to record. */
    val capture: Boolean,
    /**
     * The ticket's own QR, worth showing at the door — pre-check-in, and only for a
     * night that actually has one. Once checked in the claim is already made and the
     * same conditional branch that flips this off shows "checked in" instead, so
     * there is never a moment with both on screen. No ticket at all omits cleanly:
     * this is false, not a placeholder QR.
     */
    val showQr: Boolean,
)

/** What a **Gig** offers, decided once, rendered everywhere. */
data class GigOffers(
    val room: Room,
    val alcove: Alcove,
    val curtain: Curtain,
    /** The coarse phase, derived from the same value so it cannot disagree. */
    val phase: GigLeaf,
)

/**
 * What this **Gig** offers, as known.
 *
 * The rules, in the order they decide:
 *
 * - **Data entry never appears before the set.** There is nothing to record until the
 *   artist is playing. Checking in before the listed start widens the opening edge,
 *   because standing there is the strongest evidence the thing has begun.
 * - **The alcove is empty while the band plays, and closing the Log furnishes it.**
 * - **The alcove holds the first unfinished thing.** A closed set nobody has recorded
 *   needs publishing; once it is recorded — by me or by anyone — the playlist is what
 *   remains. That is why one past room shows setlist.fm and another shows Spotify.
 * - **An open Log with no linked record pulls the artist's catalogue.** Nobody has
 *   posted a setlist mid-set; what helps is the artist's own songs.
 * - **Reopening a Log is free.** The offers follow it back, because remembering a song
 *   three years later must cost nothing.
 *
 * **Check-in is not an alcove fixture.** It is how the room and your body come to
 * agree — you can visit any room from the corridor at any time, and checking in is the
 * claim that you are actually in this one. So it lives in the room, and location can
 * only promote it in the rendering. It is not a parameter here: under the table above,
 * check-in is already offered first wherever it is possible at all, so a location hint
 * would change no answer this function gives. Of the 18 checked-in nights on the device,
 * 17 have no venue coordinates at all — a gate would have made it impossible to check in
 * at a festival while standing in it.
 */
fun gigOffers(gig: GigAsKnown, now: LocalDateTime): GigOffers {
    val phase = gigLeaf(now, gig.window, gig.checkedIn)
    val started = phase != GigLeaf.PLAN
    val ended = phase == GigLeaf.PUBLISH
    val open = gig.log != null && !gig.log.closed
    val closed = gig.log?.closed == true
    val recorded = gig.linked && gig.songCount > 0

    val alcove = when {
        // Still ahead: the calendar is the only useful action, and once made it is the
        // thing to open — it holds the location already.
        !started -> if (gig.calendarEvent != null) Alcove.OPEN_CALENDAR else Alcove.ADD_TO_CALENDAR
        // Mid-set, or a set still being typed: no exit pointed at you.
        open -> Alcove.NONE
        // A finished night that is on the record somewhere: the playlist is what is left.
        recorded && (closed || gig.log == null) -> Alcove.SPOTIFY
        // A set I said was complete that nobody has posted. Includes a linked record
        // holding no songs, which is the same unfinished thing wearing an id.
        closed -> Alcove.SETLIST_FM
        // Started, nothing logged, nothing recorded: the room is what you came for.
        else -> Alcove.NONE
    }

    val curtain = when {
        !started -> Curtain.CHECK_EVENT
        // In the window with no claim yet: still the event's own facts, not its songs.
        !ended && !gig.checkedIn -> Curtain.CHECK_EVENT
        recorded -> Curtain.CHECK_EDITS
        gig.linked -> Curtain.FETCH_SETLIST
        // Nothing posted anywhere. A closed set is looking for its record; a set still
        // being typed is looking for the artist's songs.
        closed -> Curtain.FETCH_SETLIST
        else -> Curtain.CATALOGUE
    }

    return GigOffers(
        room = Room(
            // The night's own window, the same one a check-in already draws. Claiming
            // it later is what `attended` is for, and this never gates on a location.
            checkIn = gig.window?.let { now in it } == true && !gig.checkedIn,
            capture = started,
            // Pre-check-in only — a claim already made needs no code to prove it, and
            // that is exactly the state the "checked in" branch it swaps with takes over.
            showQr = gig.ticketQrBytes != null && !gig.checkedIn,
        ),
        alcove = alcove,
        curtain = curtain,
        phase = phase,
    )
}

package io.github.magnusencoded.stationtostation.data.setlistfm

import io.github.magnusencoded.stationtostation.data.MatchLevel
import io.github.magnusencoded.stationtostation.data.ParsedTicket
import io.github.magnusencoded.stationtostation.data.SetlistFmCandidate
import io.github.magnusencoded.stationtostation.data.SetlistFmMatch
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmHit
import io.github.magnusencoded.stationtostation.data.StoredSetlistFmLookup
import io.github.magnusencoded.stationtostation.data.TicketRouting
import io.github.magnusencoded.stationtostation.data.isLocal
import io.github.magnusencoded.stationtostation.data.matchSetlistFm
import io.github.magnusencoded.stationtostation.data.parseFmDate
import io.github.magnusencoded.stationtostation.data.storedName
import java.time.Instant
import java.time.ZoneId

/*
 * What a local **Gig**'s setlist.fm lookup comes to (#531, sections 1–4), decided without
 * the network, the clock or the store: the loop, the import and the chip are plumbing
 * that ask these and act. Term for term with iOS's `SetlistFmLookupFlow.swift`, and both
 * assert `fixtures/setlistfm-outcome/`, `fixtures/setlistfm-lookup/plan.json` and
 * `fixtures/setlistfm-question/` case for case.
 *
 * The schedule is [setlistFmLookupDue]'s and the matching [matchSetlistFm]'s; nothing
 * here restates either.
 */

/**
 * One **Gig** the automatic checks are asked about. [date] is setlist.fm's `dd-MM-yyyy`;
 * [participationUntil] is its `gossipParticipationEnds` entry, null where that is 0.
 */
data class LookupGig(
    val id: String,
    val date: String,
    val local: Boolean,
    val lookup: StoredSetlistFmLookup?,
    val participationUntil: Instant?,
)

/**
 * What the loop does next: look up [dueNow], one at a time and in that order, then sleep
 * until [nextWakeAt] (null when nothing is due again).
 */
data class LookupPlan(val dueNow: List<String>, val nextWakeAt: Instant?)

/**
 * Every [gigs]' next lookup by [setlistFmLookupDue], split into the ones due now — by
 * night, earliest first, then by id, so both platforms send them in the same order — and
 * the earliest of the rest. A Gig that is not local, has a chip pending or is past its
 * 14 days is in neither.
 *
 * [sharedQuotaSpentAt] is epoch millis, as `SetlistFmRateLimit` stores it.
 */
fun setlistFmLookupPlan(
    gigs: List<LookupGig>,
    now: Instant,
    zone: ZoneId,
    sharedKey: Boolean,
    sharedQuotaSpentAt: Long?,
): LookupPlan {
    val due = gigs.mapNotNull { gig ->
        val at = setlistFmLookupDue(
            night = parseFmDate(gig.date),
            now = now,
            zone = zone,
            local = gig.local,
            lastLookupAt = gig.lookup?.lastLookupAt?.let(Instant::ofEpochMilli),
            participationUntil = gig.participationUntil,
            possibleMatchPending = gig.lookup?.possibleMatchPending == true,
            sharedKey = sharedKey,
            sharedQuotaSpentAt = sharedQuotaSpentAt,
        ) ?: return@mapNotNull null
        gig to at
    }
    val (ready, later) = due.partition { (_, at) -> !at.isAfter(now) }
    return LookupPlan(
        dueNow = ready.map { it.first }
            .sortedWith(compareBy<LookupGig> { parseFmDate(it.date) }.thenBy { it.id })
            .map { it.id },
        nextWakeAt = later.minOfOrNull { it.second },
    )
}

/**
 * What one lookup came to, and the lookup state to store with it. [next] is always
 * stamped with the lookup, which went out whatever it found.
 */
sealed interface LookupOutcome {
    val next: StoredSetlistFmLookup

    /** One hit is sure: adopt it, with the "Adopted" notice and no question. */
    data class Adopt(val hit: FmSetlist, override val next: StoredSetlistFmLookup) : LookupOutcome

    /** Something, but nothing sure: [next] carries the chip. At most `ASK_AT_MOST`, best first. */
    data class Ask(
        val candidates: List<SetlistFmCandidate>,
        override val next: StoredSetlistFmLookup,
    ) : LookupOutcome

    /** Nothing survived. */
    data class Nothing(override val next: StoredSetlistFmLookup) : LookupOutcome
}

/**
 * One lookup's [hits] held to [ticket] — for a **Gig**, `ParsedTicket(artist, venue,
 * date = eventDate)` — by [matchSetlistFm], after dropping every hit the person already
 * said was not this night: a hit rejected once is never offered again, and one left
 * standing alone may link without asking. [lookup] null is a night never looked up.
 */
fun setlistFmLookupOutcome(
    ticket: ParsedTicket,
    hits: List<FmSetlist>,
    lineArtists: List<FmArtist>,
    lookup: StoredSetlistFmLookup?,
    nowMillis: Long,
): LookupOutcome {
    val before = lookup ?: StoredSetlistFmLookup()
    val offered = hits.filterNot { it.id in before.rejectedIds }
    val stamped = before.lookedUp(nowMillis)
    return when (val match = matchSetlistFm(ticket, offered, lineArtists)) {
        is SetlistFmMatch.Linked -> LookupOutcome.Adopt(match.candidate.setlist, stamped)
        is SetlistFmMatch.Ask ->
            LookupOutcome.Ask(match.candidates, stamped.asking(match.candidates.map { StoredSetlistFmHit.of(it) }))
        SetlistFmMatch.NoMatch -> LookupOutcome.Nothing(stamped)
    }
}

/** What a shared **Ticket** becomes once its routing and its setlist.fm lookup are both in. */
sealed interface TicketImport {
    /** Its Admissions go onto [gigId]: the night was already known, or the sure hit already on the Line. */
    data class Attach(val gigId: String) : TicketImport

    /** Onto [gigId], a local **Gig**, which is then looked up at once: a link adopts, a question is the chip. */
    data class AttachThenLookUp(val gigId: String) : TicketImport

    /** A new **Gig** from the sure [hit], planned as setlist.fm has it. */
    data class MintFromSetlistFm(val hit: FmSetlist) : TicketImport

    /** A new local **Gig**, stamped as looked up, so the schedule does not ask again at once. */
    data object MintLocal : TicketImport

    /** The confirm prompt, with [candidates] above "None of these"; [preselectedId] is ticked. */
    data class Prompt(val candidates: List<SetlistFmCandidate>, val preselectedId: String?) : TicketImport
}

/**
 * [routing] and [match] together, per #531's table. [match] is null where no lookup was
 * made (no artist or date on the ticket) or where it failed; either reads as no match.
 * [knownIds] are the setlist.fm ids of the nights already on the **Line**.
 *
 * | Route             | Match         | Result                                          |
 * |-------------------|---------------|-------------------------------------------------|
 * | NewPlannedGig     | Linked        | Attach if the hit is known, else MintFromSetlistFm |
 * | NewPlannedGig     | Ask           | Prompt                                          |
 * | NewPlannedGig     | NoMatch/null  | MintLocal                                       |
 * | NeedsConfirmation | Linked        | Prompt, the hit preselected                     |
 * | NeedsConfirmation | Ask/NoMatch   | Prompt                                          |
 * | AlreadyKnown      | local Gig     | AttachThenLookUp                                |
 * | AlreadyKnown      | setlist.fm Gig| Attach                                          |
 */
fun ticketImport(routing: TicketRouting, match: SetlistFmMatch?, knownIds: Set<String>): TicketImport =
    when (routing) {
        is TicketRouting.AlreadyKnown ->
            if (routing.gig.isLocal()) TicketImport.AttachThenLookUp(routing.gig.id) else TicketImport.Attach(routing.gig.id)
        is TicketRouting.NewPlannedGig -> when (match) {
            is SetlistFmMatch.Linked ->
                if (match.id in knownIds) TicketImport.Attach(match.id) else TicketImport.MintFromSetlistFm(match.candidate.setlist)
            is SetlistFmMatch.Ask -> TicketImport.Prompt(match.candidates, preselectedId = null)
            SetlistFmMatch.NoMatch, null -> TicketImport.MintLocal
        }
        is TicketRouting.NeedsConfirmation -> when (match) {
            is SetlistFmMatch.Linked -> TicketImport.Prompt(listOf(match.candidate), preselectedId = match.id)
            is SetlistFmMatch.Ask -> TicketImport.Prompt(match.candidates, preselectedId = null)
            SetlistFmMatch.NoMatch, null -> TicketImport.Prompt(emptyList(), preselectedId = null)
        }
    }

/**
 * The question a candidate row asks when the room is in doubt: [hit]'s venue level
 * `weak` or `noMatch`, and a venue named on both sides. Null otherwise, and the row
 * shows `artist — venueLine — date` instead. [fromTicket] is whether the **Gig** has
 * Admissions (always, at import): the venue [yourVenue] came off a ticket, or was typed.
 * Word for word with iOS.
 */
fun setlistFmQuestion(yourVenue: String?, fromTicket: Boolean, hit: StoredSetlistFmHit): String? {
    val yours = yourVenue?.trim().orEmpty()
    val theirs = hit.venue.trim()
    val inDoubt = hit.venueLevel == MatchLevel.Weak.storedName || hit.venueLevel == MatchLevel.NoMatch.storedName
    if (!inDoubt || yours.isEmpty() || theirs.isEmpty()) return null
    val says = if (fromTicket) "Your ticket says $yours." else "You have it at $yours."
    return "$says setlist.fm lists it at $theirs. Same gig?"
}

/** [setlistFmQuestion] for a candidate the prompt holds rather than one the chip stored. */
fun setlistFmQuestion(yourVenue: String?, fromTicket: Boolean, candidate: SetlistFmCandidate): String? =
    setlistFmQuestion(yourVenue, fromTicket, StoredSetlistFmHit.of(candidate))

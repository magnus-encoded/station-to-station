# ADR-0018: The Gig is the atom; everything else is enrichment

**Status:** accepted (2026-09-03)

## Context

#391 decommissioned `StoredBill`/`StoredAct` — the hand-typed poster, and the names on it, kept as
their own persisted record with their own lifecycle. No migration: four users, one Bill between them,
and the acts already marked played already existed as ordinary **Gigs** (`StoredAct.gigId`); the
unmarked acts were accepted as a loss the project owner authorised outright.

That deletion was easy to authorise because the Bill was never load-bearing. Nothing else in the app
read `StoredBill` to know what a night *was* — a Bill was a waiting room an act sat in until it became
a Gig, and the Gig was always the record that mattered: media hung off it, the Log hung off it,
attendance hung off it, calendar events hung off it. Taking the waiting room away lost exactly the
acts that never left it and nothing else.

This is not the first time a record turned out to be a satellite of the Gig rather than a peer to it.
ADR-0004 named the same shape for a different reason — *"the distinction is whether another device is
involved"* — and drew a line between the capture path (durable, never best-effort) and everything that
depends on receiving something from elsewhere (best-effort, may never happen). **Departures** is now a
second instance: a `ProgrammeAct` committed off a festival's published timetable mints a Gig exactly
the way a Bill's act used to, and the programme itself is never stored as the record of anything —
`data/Programme.kt`'s own doc comment states it plainly: *"the noticeboard can seed the timeline; it
never fills it."*

Three records, one shape each time: **Bill** (poster), **Programme** (published timetable), and a
scraped **Festival** identity before #166 gave it one. None of them is where attendance, media, or a
Log can live. All of them exist only to *become* a Gig, or to *label* one after the fact.

## Decision

**The Gig is the only atom of persisted attendance. Every other record is either a source that can
mint one, or a label attached after the fact — never a second place the facts that matter can live.**

Concretely:

- **Media, Log, attendance, and calendar events are keyed on the Gig id and nowhere else.** A record
  that wants to carry any of these has already stopped being enrichment and become a duplicate ledger
  — which is exactly what made the Bill's acts-with-`gigId` shape work (the act pointed at the Gig; it
  never held a second copy of what happened) and exactly what going the other way would break.
- **A minting source (a poster, a programme, a scrape) may be transient, partial, or gone entirely
  with no loss to what already became a Gig.** Deleting the source after the mint is not data loss;
  the Gig it produced is the durable fact.
- **A labelling record (`StoredFestival`) may attach an identity to Gigs that already exist, but
  carries no attendance of its own.** It answers "what was this night part of", never "was I there".

## Consequences

- **A future source can be added or removed with the same confidence #391 removed the Bill with.**
  The question to ask before deleting one is only ever "does this Gig-adjacent record hold any fact
  that does not also live on a Gig" — and the answer, by construction, should be no.
- **Nothing may read a source record to answer a question about a night.** `venueLine()`,
  `filingFields()`, and everything the Historian sends to setlist.fm read off the Gig
  (`FmSetlist`/`StoredGig`), never off a Bill or a Programme — and #391's deletion cost nothing exactly
  because that was already true everywhere except the Bill's own unmarked acts.
- **This generalises ADR-0004's device-boundary line rather than replacing it.** That ADR drew the
  line at *another device*; this one draws a second, narrower line inside a single device — between
  the one record that is ever a fact and the several records that are only ever a way of arriving at
  one.

## Related

- ADR-0004 — the device-boundary version of the same distinction.
- #391 — the Bill/Act decommission this generalises from.
- #166 — the Festival identity, the labelling half of this shape done first.
- `data/Programme.kt` — Departures' own statement of the rule, in one sentence, before this ADR named
  it.

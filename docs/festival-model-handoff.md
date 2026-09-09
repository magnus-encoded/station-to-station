# Handoff: the Festival/Bill/Act/Gig model, for grilling

**Status:** input to a design session, not a decision. Nothing here is agreed.
**Written:** 2026-09-01, out of the clashfinder work on `clashfinder-programme` (#389).
**Goal of the session:** either produce an ADR, or kill the proposal on its merits.

## Why this came up

#389 added **Programme** — a festival timetable fetched from clashfinder. That made four
records that all describe "a festival and the acts on it": `StoredBill`, `StoredFestival`,
`StoredProgramme`, and the derived night/venue grouping. A user-visible bug fell straight
out of it: adding an act from the programme creates a **Bill** act for an artist that is
already a **Gig** on the timeline, because the two live in different stores with different
date formats and neither checks the other.

The bug has a ten-line fix. The question is whether the model that produced it should exist.

## The proposal, as far as it got

1. **Festival becomes a declared entity, not a derived grouping.** It exists because someone
   said so, it may contain zero gigs, and gigs attach to it afterwards. Local identity at
   creation; `setlistFmSlug` / `mbid` / a new `clashfinderId` are *adopted*, nullable, never
   required — the `StoredGig.setlistId` pattern from #34.
2. **Bill and Programme are deleted into it.** A Bill is an authored Festival whose acts have
   no nights; a Programme is a Festival whose acts have stages and times. `StoredFestival`
   already carries `dayMembership` and `setTimes`.
3. **Act becomes Gig with a played flag.**
4. **Membership evidence gets a precedence ladder**, extending the existing `mergedWith` rule:
   `AUTHORED > setlist.fm slug > clashfinder programme > inferred (same night + venue)`.
   Any declaration suppresses the inference.
5. **Binding is a user action, not a heuristic.** Because a local Festival exists to bind
   *to*, clashfinder's "Øyafestivalen 2026" is adopted by a person picking it — no
   name-matching, no auto-merge.

## What is already built (don't rediscover this)

| Thing | Where |
| --- | --- |
| `StoredFestival` — id, name, range, `setlistFmSlug`, `mbid`, `source`, `dayMembership`, `setTimes` | `data/TimelineStore.kt:268` |
| `Festivals` — `byId`, `idByShow`, `of(showId)`, day-membership precedence | `data/TimelineStore.kt:309` |
| Provenance rule: author beats scrape, scrape never overwrites authored | `data/TimelineStore.kt:295` (`mergedWith`) |
| Local identity minted from an upstream slug | `data/TimelineStore.kt:287` (`festivalIdForSlug`) |
| `StoredBill` + its rationale for existing at all | `data/Bill.kt:47` (doc above it from line 20) |
| `StoredAct` + `maybe`, `candidates`, `gigId`, `asked` | `data/Bill.kt:77` |
| `StoredGig` — `setlistId` nullable, "set once, by adoption (#34)" | `data/TimelineStore.kt:89` |
| `StoredProgramme` / `ProgrammeAct` | `data/Programme.kt:223` / `:29` |
| Grouping by identity or by night+venue | `ui/…` `groupIntoFestivals`, called from `data/Bill.kt:205` |
| The duplicate-add bug | `AppViewModel.kt:2072` (`addActFromProgramme`) |

## The objections to grill — these are the session

Ranked by how likely they are to sink the proposal.

**1. `maybe` must never reach a Gig, and the collapse imports it.**
`StoredAct.maybe` is documented as the poster's hedge, *"a property of the Bill, never of a
Gig: the moment an act is dated it played, so there is nothing left to be unsure about and
no 'unconfirmed gig' state can ever be reached"* (`data/Bill.kt:62`). A played flag creates
exactly the state that rule forbids. Either the rule was a mistake, or the collapse is.
**This is the crux — settle it first, everything else is downstream.**

**2. An undated act cannot sit on a date-ordered timeline.**
`StoredBill`'s doc says an undated act can join no grouping and *"inventing a day per act so
the existing machinery would work is precisely the fabrication the record must not commit"*.
The proposal's answer is that undated acts live on the Festival entity and render inside its
node, never on the line. Is that actually true of the current renderer, or is it a rewrite
being waved at?

**3. `StoredAct` carries pre-gig machinery that a played Gig has no use for.**
`candidates`, `matchedArtist`, `mbid`, `asked` exist to pick songs *before* the night. Do
they move onto `StoredGig` — five mostly-empty fields on every one of 214 records — or onto
the membership edge, or somewhere else?

**4. Provenance is per-record today; merged it needs to be per-field.**
A Festival would hold published data (clashfinder's `setTimes`, `dayMembership`) beside
authored data in one record. A refetch replaces the first and must not touch the second.
`mergedWith` resolves whole records, not fields. Does that generalise, or does it need
per-field ownership?

**5. What counts as a show?**
"214 shows since 1992" is currently true because a Gig means attended. With a flag it becomes
`played == true` everywhere, in every count, cluster and export. Enumerate the call sites
before agreeing, not after.

**6. Does a zero-gig Festival break anything that assumes non-empty?**
Sort position on the line, pruning (an authored empty festival must never be pruned; a
derived one should be), deletion semantics (cascade to gigs, or orphan them?).

**7. Two sources disagreeing.**
setlist.fm says this gig is festival A, clashfinder says B. Precedence picks one — is the
loser kept as evidence, or dropped? Same question when a clashfinder is edited after we
cached it.

**8. Is clashfinder-as-evidence too weak to rank at all?**
Half of clashfinders are editable by anyone, fantasy line-ups are structurally identical to
real ones, and 36% are still edited after the festival starts. It is ranked third for those
reasons — but a case can be made that it should never establish membership on its own, only
confirm membership something else already asserted.

## Migration surface

Existing Bills → authored Festivals with undated acts. Existing acts → unplayed gigs
(`gigId` already links the ones that graduated). Existing gigs → `played = true`, all of
them. Cached `programme.json` → converted or discarded. Date formats differ between stores
(`dd-MM-yyyy` on gigs, ISO on programme acts) and that has already caused one bug.

## Constraints that are not up for grabs

- ADR-0005: the record is kept, so nothing that can lose user data on a refetch.
- Clashfinder data is CC BY-NC 3.0; attribution is a licence condition and must be carried on
  whatever record holds the timetable. If festivals sync between phones, the licence question
  arrives with them.
- No fabricated precision: a night nobody knows is not a night we invent.
- ADRs are appended and amended, never rewritten.

## What a good session produces

A decision on objection 1, and then either an ADR proposing the model with objections 2–8
answered in it, or a written reason the current four records should stay — in which case the
ten-line idempotency guard on `addActFromProgramme` is the whole of the work.

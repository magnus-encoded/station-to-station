# ADR-0024: The Active Gig is chosen, not only derived

**Status:** accepted (2026-09-18)

## Context

A **Pass** usually carries a signed claim naming the **Gig** it is about, and that claim decides
where its evidence lands. A completed **Pass** that carried no claim at all still has to land
somewhere, so #498 introduced the **Active Gig**: the night this phone is standing at.

It was derived, on purpose, and the code said why — *"a field would be a second answer that could
disagree with the deadlines"*. The rule was **the latest Check-in still running**, recomputed
from the participation deadlines on every read, with no stored state anywhere to go stale.

That holds for one night at a time and breaks at a festival, which is the case the channel was
built for. Two stages, two **Check-ins**, both inside their windows: the derivation picks the one
checked into most recently, and it has no way to know that the person walked back to the first
stage an hour ago. Nothing they can do changes its mind, because there is nothing to change.

The same problem appears with a stop. Stopping ends participation for every night already stood
in, and there was no way back short of checking in again — which would mint a second **Check-in**
for a night already attended, making an attendance record out of a radio control.

## Decision

**The Active Gig is a stored choice among the nights that are eligible, and the latest Check-in
still running whenever no stored choice is eligible.** It is chosen by tapping a **Gig**'s
**Presence row**.

- **A preference, never a claim.** The stored id says which of the eligible nights is preferred.
  It is never evidence that a night is live: eligibility is still computed only from the
  participation deadlines, and the stored id is filtered through them on every read. This is what
  keeps the original objection answered — there is one answer about which nights are live, and
  the selection can only reorder it, never contradict it.
- **Fallback is the old rule, unchanged.** No selection, or a selection whose night has ended,
  gives the latest **Check-in** still running. Initial selection and selection-after-expiry are
  therefore the same line of code rather than a lifecycle to keep in step, and a **Gig** ending
  hands the radio on without anybody touching anything.
- **Selecting is not attending.** Tapping a **Presence row** mints no **Check-in** and writes no
  attendance. The claim to have been somewhere was made by checking in; this only says which of
  those places the radio speaks for.
- **It decides nothing about a signed Fact.** A **Fact**, a **Check-in** or an **Update** names
  its own **Gig** and stays with it whichever night is active. Only a **Pass** with no claim at
  all reaches for the **Active Gig**, exactly as before.
- **The stored id is the local Gig id.** Adoption (#496) changes the id a night answers to, and a
  selection stored under the mutable id would quietly stop matching the night it named. The
  surface offering the choice holds a **Room**'s id, so the read matches either.
- **Resume is explicit and is this tap.** A dim bullet on an eligible **Presence row** means
  *could be gossiping, is not*; tapping it selects that night and clears the stop. Nothing else
  clears one — reopening a **Log** after a stop still deliberately does not resume, which is the
  rule ADR-0019's notification promised its owner.

## Consequences

- One preference key in the device-local gossip store, excluded from backup like the rest of it.
  It is a user choice rather than presence, so ADR-0023's boundary — *presence that did not
  survive the process was not presence* — is untouched: nothing here claims anybody is anywhere.
- Eligibility is now asked two ways: with the stop applied, which is what the radio runs on, and
  without it, which is what a **Presence row** draws. A stop zeroes every deadline, so asking the
  stopped-aware question for the bullet would make the one control that can undo a stop
  undrawable. The two must not be conflated.
- Nothing crosses the wire and nothing in `ble/` changes. A **Pass** is unaffected; only where an
  unclaimed one is remembered moves.
- **iOS (#501) owes the same decision**, and the same two questions about eligibility. The stored
  key, the fallback rule and "selecting is not attending" are domain, not platform — a build where
  the two sides disagree about which night an unclaimed **Pass** belongs to would produce two
  different **Seen with** records for the same room.
- The notification names the **Active Gig** in its title so a switch is visible where the radio is
  auditable. The people it has heard from stay on the content line, behind `VISIBILITY_PRIVATE`.
  #500 also asked for a Resume action on a disabled notification; there is none, because stopping
  ends the foreground service and a service kept alive only to offer Resume is the background cost
  ADR-0019 refused. The dim **Presence row** is the explicit Resume instead.

## Amends

ADR-0021 and ADR-0023 are unchanged. This supersedes only the reasoning recorded in
`gossipActiveGigId`'s own documentation, that there should be no stateful active **Gig**.

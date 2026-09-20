# ADR-0021: Public Gig facts over blind relays

**Status:** accepted (2026-09-10)

## Context

ADR-0019 won the right for the gossip channel to run in the background, and then paired it
with a propagation rule: a message travels **Contact-to-Contact only, never along a Followed
line**. That rule was argued honestly from ADR-0016 — gossip should ride only on trust that
presence already built — and it is the part that did not survive contact with a venue.

Contact-to-Contact relay is a rendezvous problem. For a fact to move, a device holding it has
to be in radio range of a device that is *already a Contact* of the holder. Early in adoption
that set is usually empty: the people in the room are strangers, and the Contacts are at home.
The carve-out ADR-0019 fought for was for the walk home, and under its own propagation rule
almost nothing made the walk. A relay population of one is not a relay.

The second pressure is the payload. ADR-0019 permits a **Check-in** and states plainly that
anything else "should be read as a new decision, not an extension of this one". Everything the
Gig record actually wants to move between phones — a **Log** line, a request to be witnessed,
the witness itself, a signal that something was useful — is that new decision. This ADR is it,
argued on its own rather than smuggled in as a reading of ADR-0019.

The thing that makes widening affordable is that ADR-0019's disclosure problem can be solved
rather than merely accepted. ADR-0019 hands a relaying device the author's **stable public
key** — the same key on their Card — and names the retroactive deanonymisation that buys as a
cost of doing business, because a per-message signature has to be verifiable by a relay that
only holds long-lived Contact keys. That premise is only true while the relay is the verifier.
If a relay is *blind* — carrying bytes it cannot attribute — the durable key never has to
cross at all.

## Decision

**Gossip facts are public, signed assertions scoped to a Gig and carried by any STS device in
BLE range.** A temporary Gig identity signs each fact; a durable Contact key is sealed inside
the attribution field so a Contact can recognize retained facts after Exchange without making
the content confidential. Blind relays may carry and forward facts without knowing the author,
while Block affects local application admission only.

**This supersedes the Contact-only propagation boundary in ADR-0019; Reconcile, media and
Notes remain private and Contact-scoped.** The design accepts best-effort delivery and
mechanical resource limits in exchange for useful information in sparsely adopted venues.

Four things follow, and they are the whole of what is being decided here:

- **The edge is range, not relationship.** Any device that hears a **Pass** may accept, carry
  and forward it. Nothing about the carrier's relationship to the author is consulted, because
  the carrier cannot determine it.
- **Authorship is temporary on the wire and durable to those who already know.** Each stable
  local Gig holds an independent P-256 signing key; the durable Card key signs that binding
  once, and the signature is sealed under a key derived from the Card public key. Decryption
  alone proves nothing — the durable signature must verify against the exact temporary key and
  scope.
- ~~**The payload is four kinds** — `log`, `request`, `witness`, `receipt`.~~ **Five kinds**, see the amendment of 2026-09-17. A witness embeds the
  complete signed request it attests, so a relay cannot invent a subject's attendance.
  Requests and receipts are one-hop only.
- **Block is admission, not propagation.** A blocked author's facts still cross the radio;
  they never enter this device's record.

Byte-level specification is not repeated here: [`docs/gossip-public-wire.md`](../gossip-public-wire.md)
is normative, and its parameters are ~~measured~~ *simulated* in [`sim/SWEEPS.md`](https://github.com/magnus-encoded/station-to-station/blob/gossip-sim/sim/SWEEPS.md) (branch `gossip-sim`).

## What this does not cover

- **It is not confidential messaging.** Facts are public. Anyone who obtains an author's Card
  public key by another route can recognize their authorship, permanently. Exchange remains
  the disclosure boundary; nothing here distributes a new secret or needs to rotate one when a
  Contact is removed.
- **It does not touch the private half of the app.** Reconcile keeps its foreground-only,
  fingerprint-bound session; media and Notes stay Contact-scoped. ADR-0019's background
  carve-out is narrowed by none of this and reopened by none of it.
- **It does not make delivery a promise.** Best-effort remains best-effort, and every word of
  ADR-0019's §6 on iOS background throttling still holds.
- **It does not settle the receipt hop budget.** Receipts are one-hop today; the sweep of
  alternatives exists and the implementation has not chosen from it.

## Consequences

- **ADR-0019's propagation rule and payload clause are struck**, and its "Disclosure to the
  relaying device" section now describes a cost this design does not pay. See its amendment of
  2026-09-11.
- **The storm gate stopped being a module.** The pure decision ADR-0019 tracked as #410 lives
  in `GossipEnvelope.valid()` and `PublicGossipState.receive()`; the v1 module was deleted in
  #449. **Storm gate** survives in `CONTEXT.md` as the name of the rule.
- **A relay's own key is night-scoped and separate.** Carrying an Envelope never makes a device
  its author — the property the whole blind-relay argument rests on.
- **The thing to watch is the seal, not the edge.** The edge is deliberately open now. What
  keeps this narrow is that the durable key never crosses in the clear; a change that put it on
  the wire, or that made attribution derivable without the Card key, would undo the trade this
  ADR made and is a new decision.

## Related

- ADR-0019 — the decision this supersedes in part, and which still grants the background
  permission this relies on.
- ADR-0016 — presence is the authentication; untouched, because nothing here mints a Contact.
- `docs/gossip-public-wire.md` — the normative wire specification.
- [`sim/SWEEPS.md`](https://github.com/magnus-encoded/station-to-station/blob/gossip-sim/sim/SWEEPS.md) (branch `gossip-sim`) — the ~~measured~~ *simulated* basis for the Carry window and the relay parameters.
- #408 — the epic.


## Amendment — 2026-09-15: evidence and remaining work (#455)

The earlier references to parameters being “measured” in `sim/SWEEPS.md` mean
**simulated**. Those synthetic results do not establish device performance, battery
cost or settled defaults. Carry, usefulness decay and participation grace are
separate provisional parameters. ADR-0019's 2026-09-15 amendment supersedes its
historical participation policy; its iOS background assumptions still need real
locked-phone receive and forward trials.

~~Receipts are accepted as one-hop controls, but no production emitter or useful-peer
ranking exists yet. Binary decaying neighbour credit and random ties describe the
intended policy, not current behaviour.~~ *(Superseded 2026-09-20, see the last amendment.)* #455 gates that work on iPhone viability.
The publication choice between each committed line and whole-Log completion is
still pending; current code publishes committed Log changes during participation.


## Amendment — 2026-09-15: each committed line is public (#455)

The user resolved the publication question: “Each line, correct or not becomes a
fact that is transmitted as the current state.” Each committed addition or
correction during participation publishes immediately. A Fact records the author's
current assertion; it is not a claim that the line has been verified or agreed on.
A correction creates a new signed Fact for the same line, and projection shows the
latest version while retaining earlier Facts as history.

Whole-Log completion is not a publication gate: it starts the existing 30-minute
grace period, capped at 06:00. Uncommitted typing stays in the editor. The existing
participation and stop rules still apply, and edits outside participation remain
local. This supersedes the pending-choice statement above and #455's earlier
whole-Log-Done wording. Both platforms already publish committed line changes
from their writeLog paths; this decision requires no publication toggle or delay.


## Amendment — 2026-09-17: a fifth kind, `update` (#496, #497)

A night checked into under a locally minted id may be catalogued on setlist.fm while
the radio is still running. Nothing in the four kinds could say so: a **Log** line is
the only kind that carried former ids, so a witnessed **Check-in** with no later Log
line lost its witness at the moment of adoption.

`update` is that statement and nothing else. It names the new id and the former one,
carries no text and no line, and so can never read as something a person wrote. Its
rule is the same rule authorship already had: an **Update** relabels only the Facts
its own signer authored, in the same scope. Another author's Update moves nobody's
**Check-in** and nobody's witness. It makes no Log line, no arrivals row, names
nobody present, and neither starts nor extends participation.

It is gossiped, not one-hop: the whole point is that a receiver can carry the earlier
request onto the identified **Gig** without the author ever writing again. Adoption
after participation ends authors nothing and stays a local rename — the witness that
device already holds is unaffected, and nobody else needed telling.


## Amendment - 2026-09-20: what shipped after the v2 tickets (#471)

Verified against code on this date. Nothing here is field-tested.

- **Receipts and ranking exist.** Receipts are authored on receipt of a Contact's Fact
  (both platforms, ADR-0022). Peer ranking is `gossipPreferredPeers` (shuffle under a seedable
  RNG, credited band first, nobody excluded), not `PublicGossipState.offer`. Android has used it
  since #462; iOS since #486 through its four-meeting cap. Credit is close to inert in the field
  by design (identity resolves only after the challenge read; credit window overlaps the
  cooldown); ADR-0022 section 4 says the fix needs a per-pair rotating token. The rule is
  tested; its field effect is unmeasured.
- **Carry window (15 min) and usefulness decay (2 min) are simulated, provisional numbers.**
- **Three lifetimes**, all separate: Envelope expiry (06:00), the carry window, and seen IDs that
  outlive eviction until expiry. Durable Facts outlive all three.
- **A stranger can read the BLE challenge.** It proves a relay key, not Contact membership.
- **Attribution survives removing a Contact.** The Contact's name is snapshotted at recognition
  (#491, #492); before that, removal demoted already-received Facts to "Nearby listener".
- **Presence is fed from the just-accepted batch.** Attribution, not proximity, is the gate
  (`presenceFrom`); a verified Check-in relayed via someone's witness counts, so presence can
  reach one hop beyond your own radio. The nearby window is 5 minutes and nothing re-triggers a
  Contact after it (#493, #494). iOS built its presence surface in #494 and scopes the line to
  the active Gig(s); Android's is not Gig-scoped.
- **Later work:** ADR-0023 (Seen with) and ADR-0024 (Active Gig chosen). Android has the
  Presence-row selection; iOS parity is in progress under #501.
- **iOS persistence landmine.** `PublicGossipState` is `Codable` with synthesized decoding,
  which throws on a missing key even when the property has a default, and `GossipLedger.load()`
  decodes with `try?`, so a new required key silently wipes every saved ledger. New fields must
  go through its hand-written `init(from:)` with `decodeIfPresent`, with a regression test.
- **Verification limits.** No locked-iPhone run, no phone-to-phone Pass, no battery
  measurement. The BLE wiring around `presenceFrom` has no test and no device run. #465 and #467
  remain as field tests.

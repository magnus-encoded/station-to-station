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
- **The payload is four kinds** — `log`, `request`, `witness`, `receipt`. A witness embeds the
  complete signed request it attests, so a relay cannot invent a subject's attendance.
  Requests and receipts are one-hop only.
- **Block is admission, not propagation.** A blocked author's facts still cross the radio;
  they never enter this device's record.

Byte-level specification is not repeated here: [`docs/gossip-public-wire.md`](../gossip-public-wire.md)
is normative, and its parameters are measured in [`sim/SWEEPS.md`](../../sim/SWEEPS.md).

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
- `sim/SWEEPS.md` — the measured basis for the Carry window and the relay parameters.
- #408 — the epic.

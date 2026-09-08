# ADR-0019: The gossip channel is a narrow, named carve-out from ADR-0016

**Status:** accepted (2026-09-04)

## Context

ADR-0016 drew the boundary this project defends: **presence is the authentication**, so
"nothing advertises, listens or accepts in the background" — the radio only speaks while a
human is looking at the Exchange screen, because that window is the only thing standing in
for a verified sender. That decision is not being revisited here.

The **gossip channel** is a different transport with a different job: relaying a **check-in
fact** — "this Contact was at this Gig, at this time" — from the Contact who checked in to
other Contacts who were not standing there to receive it directly. A check-in that only ever
reaches people already in the room is not worth building a radio for; the entire point of a
relay is to reach someone who is *somewhere else*. That is unavoidably a background
operation — nobody keeps Exchange open on the walk home so a friend's phone can hear about a
gig three relay-hops later.

Read naively, ADR-0016's "nothing advertises, listens or accepts in the background" forbids
exactly this. It should not be read that naively: ADR-0016's trust boundary is about *how a
Contact relationship is minted* — presence is what makes an unverified radio write safe,
because nobody can mint a Contact without physically being there. The gossip channel mints no
Contacts and accepts no Cards. It only ever moves a small, signed fact between two ends that
are *already* Contacts, over a session that has to run without anyone present for it to be
useful at all. That is a different question from the one ADR-0016 answered, and this ADR
exists to say so explicitly rather than let the two rules collide by omission.

## Decision

**The gossip channel (check-in relay) is allowed to run in the background, without the
Exchange/Reconcile screen open. This narrows ADR-0016; it does not reopen it.**

Everything ADR-0016 decided about the **Card**, the **Exchange**, and the **Reconcile**
session is unchanged:

- **Reconcile stays foreground-only.** The pairwise sync described in ADR-0016 §"what this
  does not cover" still runs only with a live, fingerprint-bound challenge-response session
  between two people who chose to run it. Nothing here relaxes that.
- **A Contact is still only ever minted in person.** The gossip channel cannot create a
  Contact, cannot carry a Card, and does not touch the arrival rule (#188). It moves along
  edges that already exist.
- **First-hop trust is unchanged.** Presence still authenticates the relationship a message
  can travel along in the first place — a Contact is a person I exchanged keys with in
  person, exactly as ADR-0016 and `UBIQUITOUS_LANGUAGE.md` define it. What changes is only
  that a *later* hop of an already-trusted fact does not require presence to *relay*, because
  presence already did its job when the Contact was formed.

What the carve-out actually permits: a device may run a background listener that accepts
`GossipCheckIn` messages from, and relays them onward to, Contacts it already holds — with no
Exchange screen open and no human watching.

### Propagation rule

A gossip message is accepted from, and relayed to, a **Contact only — never a Followed
line.** A Followed line is one-sided and requires no consent; extending gossip relay to it
would let attendance facts propagate to people who never exchanged keys with anyone in the
chain, which is exactly the kind of trust-without-presence ADR-0016 exists to prevent. The
edge a gossip message travels along must be the same mutual, in-person edge ADR-0016
authenticates — gossip only ever rides on trust presence already built, it does not create
any of its own.

Because a relay hop by definition has no live session with the message's original sender,
trust cannot be established the way Reconcile establishes it (a fingerprint-bound
challenge-response, per ADR-0016 §"what this does not cover"). Instead, each message is
verified **per-message, by signature**: the envelope carries `checkedInBy` (the checking-in
Contact's public key) and a `signature` over the payload, checked against a key the receiving
device already holds for a known Contact. There is no live session to trust and none is
needed — the signature is the only thing that has to survive the hop. Dedup, TTL, and
signature verification are a pure decision function with no I/O — the storm-gate module
tracked in #410 — so that the rule above is enforced the same way, and testably, on both
platforms.

### Platform cost, stated plainly

This is real always-on infrastructure, not a free extension of Exchange:

- **Android** needs a persistent-notification foreground service to keep the BLE listener
  alive against the OS's background restrictions. The user will see a permanent notification
  the whole time gossip relay is active.
- **iOS** gets no equivalent guarantee: background BLE is OS-throttled, delivered on the
  platform's schedule rather than the app's, and can lapse for periods the app does not
  control.

Neither of these is a corner to round off later. They are the price of the carve-out, and
transports built against this decision (#416, #417) inherit that cost rather than discovering
it.

### Disclosure to the relaying device

Stated plainly, per the #410 review that first named it: relaying a message is not blind
forwarding of an opaque envelope. A device that relays a `GossipCheckIn` necessarily learns
the checked-in Contact's **stable public key** — the same key on their Card — along with
which Gig they were at and roughly when. That device need not be a Contact of the person who
checked in; the propagation rule only requires *each hop* to be Contact-to-Contact, so a
message can reach someone two or more hops removed from its author who has never met them.

This is a real disclosure, not a side effect to round off: a stable identity key handed to a
stranger-of-a-friend is deanonymisable retroactively if that key ever surfaces again
elsewhere. It is accepted here because the alternative — a fresh, unlinkable key per
check-in — would make the per-message signature unverifiable by a relay that only holds
long-lived Contact keys, defeating the point of verifying without a live session. The
trade-off is named so a future reader does not mistake today's design for an oversight.

## What this does not cover

Stated explicitly, for the same reason ADR-0016 stated its own list explicitly:

- **It does not weaken Contact-exchange trust.** The Exchange screen, the radio-write rules,
  and the arrival rule (#188) are exactly as ADR-0016 left them. Nothing about minting a
  Contact becomes easier, remote, or background-capable.
- **It does not apply to Reconcile.** The LAN Reconcile session keeps its own
  fingerprint-bound, foreground-only verification. Gossip's per-message signature check is
  not a substitute for it and is not being proposed as one anywhere else in the system.
- **It does not permit any payload beyond a check-in fact.** The gossip channel exists to
  move `GossipCheckIn` messages — Gig id, checking-in Contact, timestamp, expiry, signature —
  and nothing else. It is not a general-purpose background messaging channel, and a future
  proposal to widen its payload should be read as a new decision, not an extension of this
  one.

## Consequences

- **Two transport issues (#416 Android, #417 iOS) are blocked on this ADR** and implement
  exactly the boundary drawn above — the propagation rule, the per-message signature check
  via the #410 storm-gate function, and no more.
- **A future proposal to relay anything other than a check-in fact, or to relay across a
  Followed line, is a new decision**, not a reading of this one. It should be argued on its
  own, the way this ADR had to be argued against ADR-0016 rather than assumed from it.
- **The thing to watch is the payload and the edge.** As long as gossip only ever carries a
  signed check-in fact and only ever travels Contact-to-Contact, this carve-out stays exactly
  as narrow as it is today.

## Related

- ADR-0016 — the decision this narrows; presence still authenticates the Contact edge gossip
  relays along.
- #408 — the epic this ADR is part of.
- #410 — the storm-gate module (dedup/TTL/signature, pure function) that enforces the
  propagation rule.
- #416, #417 — the Android and iOS transport implementations blocked on this ADR.
- `UBIQUITOUS_LANGUAGE.md`, **Contact** and **Followed line** — the edge this decision does
  and does not permit gossip to travel along.

---

## Amendment — 2026-09-08: the advertising Token is keyed on the two public keys, not on an ECDH shared secret

Added while implementing #416 (Android) and #417 (iOS). The body above is unchanged; this
section adds the one thing it left unspecified and corrects the answer the issues assumed.

**What the issues said.** #408 and #416 both describe the rotating per-Contact advertising
value as ~~"an HMAC of the ECDH-shared-secret and a time bucket, truncated"~~, on the
grounds that it needs "no new pairing step, reuses what Exchange already establishes".

**Why that is not implementable against what Exchange actually establishes.** The durable
Contact identity is a single AndroidKeyStore / Secure Enclave keypair created with a
**signing** purpose only (`KeyProperties.PURPOSE_SIGN`, `SHA256withECDSA` over P-256).
Three consequences, all load-bearing:

- Those keys are immutable. An existing key cannot be granted key agreement after the fact,
  and this key is already on every phone that has ever made a Contact.
- `PURPOSE_AGREE_KEY` requires API 31; the app's minSdk is 26.
- A second, agreement-capable keypair would have to be carried on the **Card** handed over
  in person — which is precisely the new pairing step the issue rules out, and it would
  leave every Contact made before that change unable to gossip until the two people met
  again.

So the premise "reuses what Exchange already establishes" is the part that survives, and
"ECDH" is the part that does not.

**What is implemented instead.** The **Token** is
`HMAC-SHA256(key = <lower key> "\n" <higher key>, msg = "station-to-station/gossip-token/1\n" <bucket>)`,
truncated to its first 8 bytes, where the two keys are the peers' base64 Contact identity
public keys sorted lexicographically and the bucket is `floorDiv(epochSecond, 900)` — a
quarter of an hour. A scanner matches against a table covering the previous, current and
next bucket, so a clock a few minutes out still recognises a Contact. Both platforms must
produce these bytes identically; it is a wire format, not an implementation detail.

**What this costs, stated plainly.** A shared secret is computable only by the two of them.
This value is computable by anyone holding *both* public keys — a mutual Contact of both,
or, under this ADR's own "Disclosure to the relaying device" section, a device that relayed
a message authored by one of them and separately holds the other's key. What such a device
learns is that those two people are within radio range of it. It cannot forge a **Pass**
(that needs a signature over a nonce it cannot produce) and it cannot mint a **Check-in**
(same key, same reason). It is a linkability weakening of the advertisement, not a break of
the trust model, and it is accepted here for the same reason the disclosure above is: the
alternative costs every existing Contact a second in-person meeting.

**What would change this.** If the Contact identity ever gains an agreement-capable
counterpart for other reasons — a raised minSdk plus a Card format change, say — the Token
should move to the ECDH form the issues originally described, and only the derivation
changes; nothing else in this ADR depends on it.

**Also settled here, because both platforms have to agree:** the **Pass** header
`station-to-station/gossip-pass/1`, the possession-proof domain separator
`station-to-station/gossip-auth/1`, a 32-byte nonce, and tab-separated fields in
newline-separated records. The per-peer rate the storm-gate explicitly delegated to #416 and
#417 is one minute, which with `GOSSIP_MAX_BATCH` puts the ceiling at 64 messages per minute
per peer.

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

## Amendment — 2026-09-08

Written while implementing #417 (iOS), the first of the two transports. Three things this ADR
left unwritten that both transports need to agree on, and one product fact it named but did not
quantify. Nothing here reverses a decision above; it fills gaps the transports would otherwise
have each filled differently.

### 1. The rotating per-Contact token, specified

The ADR requires that gossip travel Contact-to-Contact and never along a **Followed line**, and
the transports need to recognise a **Contact** over the air to do it. That recognition must not
be a stable identifier — a device that broadcast one would be trackable by anyone standing near
it all night, which is a worse disclosure than the one this ADR already accepts.

The token is therefore derived, per **Contact** and per time bucket, from the secret only that
pair can compute:

```
secret  = ECDH(my identity private key, their identity public key)   // P-256, raw X, no KDF
bucket  = floor(unix_seconds / 900)                                   // 15 minutes
token   = lower_hex(HMAC-SHA256(secret, "station-to-station/gossip-token/1" || "\n" || bucket)[0..16])
```

- **Raw ECDH, not X9.63-with-KDF.** Android's `KeyAgreement.getInstance("ECDH")` produces the
  bare X coordinate; iOS matches it with `SecKeyCopyKeyExchangeResult(.ecdhKeyExchangeStandard)`.
  A KDF on one side only would produce two phones that never recognise each other, silently.
- **`floor`, not truncation.** Both languages divide toward zero, which disagrees with `floor`
  for pre-epoch instants. Both twins floor explicitly.
- **A writer publishes its current bucket; a reader accepts ±1.** Fifteen minutes of clock skew
  is tolerated, an hour is not.
- **The domain separator is load-bearing.** The same secret must never produce a value that is
  valid in two places — the argument `gossipPayloadV1` already makes for the signed payload.

Fixed cross-platform vector, asserted by `GossipTokenTests` and its Android twin:

```
secret = 00 01 02 … 1f      bucket = 1987284      → cd60b9fef6f960c7b6803f1b45608f0f
```

**What this discloses**, stated because it is not nothing: a device that connects learns *how
many* Contacts the other holds, because the offer is one token per Contact. It cannot learn who
any of them are, and the count is only visible to something close enough to hold a BLE
connection open. This is smaller than the disclosure "Disclosure to the relaying device" above
already accepts.

### 2. Messages are held until they expire, not relayed once

`gossipStormGate` (#410) deliberately does not decide this and names the transports as its
owners. The decision: an accepted message is **held and offered to every Contact met before it
expires**, with per-Contact delivery recorded so it is never offered to the same person twice.

The alternative — relay at the moment of acceptance — was rejected because on iOS the moment a
message is accepted is almost never a moment when the Contact who needs it is in range. A relay
that fired only at acceptance would deliver to whoever happened to be standing there, which is
the population that least needs it.

The bound is the same expiry the gate applies, so nothing is held past the night it is about,
and the store is pruned on every read and every write rather than on a schedule. This is a
record of other people's whereabouts and it is treated as one.

### 3. The per-peer rate bound

The other thing the gate names and does not do. Both transports bound a **Contact** to one
handover per 30 seconds and 240 offered messages per rolling hour, charged on what was offered
rather than what was accepted. That is the real ceiling on the taken-over-phone attack the gate
describes: 240 signature verifications an hour, however many batches arrive.

### 4. "OS-throttled and opportunistic", quantified for iOS

"Platform cost, stated plainly" above is right and is not specific enough to set an expectation
with. On iOS, concretely:

- A backgrounded scan is coalesced with every other app's; a peer found in under a second in the
  foreground can take minutes in the background, or the length of the meeting.
- Duplicate advertisements are never delivered in the background, so a meeting that fails is not
  retried by the radio.
- **A backgrounded iPhone is invisible to an Android scanner.** iOS moves a backgrounded app's
  service UUID into a manufacturer-specific overflow area that only another iOS device
  explicitly scanning for that exact UUID can read. iOS↔Android gossip works when the iPhone is
  the one scanning, or when it is in the foreground. iPhone↔iPhone works both ways, slowly.
- Force-quitting the app ends background delivery until the next manual launch. So do Low Power
  Mode and a Bluetooth toggle.

**The product consequence, which is the part that matters:** a check-in reaches a Contact
*eventually and probably*, if the two phones are in the same place for long enough. Nothing in
the app may tell a user their arrival "was sent" or "will reach" anyone. The walk-home case this
ADR exists for is exactly the case this delivers; a message channel is exactly what it is not.

### Still open

- **No user-facing switch.** The gossip radio runs whenever the device holds at least one
  **Contact**, and the only way to stop it is to remove every Contact or deny the Bluetooth
  permission. A per-feature toggle is a product question this ADR did not settle and #417 did
  not invent; it should be raised as its own issue.

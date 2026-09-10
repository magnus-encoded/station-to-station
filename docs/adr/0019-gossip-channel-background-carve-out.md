# ADR-0019: The gossip channel is a narrow, named carve-out from ADR-0016

**Status:** accepted (2026-09-04); ~~Contact-only propagation~~ **superseded by ADR-0021** (amended 2026-09-11)

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

~~A gossip message is accepted from, and relayed to, a **Contact only — never a Followed
line.** A Followed line is one-sided and requires no consent; extending gossip relay to it
would let attendance facts propagate to people who never exchanged keys with anyone in the
chain, which is exactly the kind of trust-without-presence ADR-0016 exists to prevent. The
edge a gossip message travels along must be the same mutual, in-person edge ADR-0016
authenticates — gossip only ever rides on trust presence already built, it does not create
any of its own.~~ **Superseded by ADR-0021 — see the amendment of 2026-09-11 below.**

Because a relay hop by definition has no live session with the message's original sender,
trust cannot be established the way Reconcile establishes it (a fingerprint-bound
challenge-response, per ADR-0016 §"what this does not cover"). Instead, each message is
verified **per-message, by signature**: ~~the envelope carries `checkedInBy` (the checking-in
Contact's public key) and a `signature` over the payload, checked against a key the receiving
device already holds for a known Contact.~~ **v2 signs with a per-Gig temporary key and seals
the durable attribution; see ADR-0021.** There is no live session to trust and none is
needed — the signature is the only thing that has to survive the hop. Dedup, TTL, and
signature verification are a pure decision function with no I/O — ~~the storm-gate module
tracked in #410~~ **`PublicGossipState.receive` and `GossipEnvelope.valid()`** — so that the
rule above is enforced the same way, and testably, on both platforms.

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
- ~~**It does not permit any payload beyond a check-in fact.** The gossip channel exists to
  move `GossipCheckIn` messages — Gig id, checking-in Contact, timestamp, expiry, signature —
  and nothing else. It is not a general-purpose background messaging channel, and a future
  proposal to widen its payload should be read as a new decision, not an extension of this
  one.~~ **Widened by ADR-0021 to four kinds — `log`, `request`, `witness`, `receipt` — as
  its own decision, which is exactly the "new decision" this clause asked for.**

## Consequences

- **Two transport issues (#416 Android, #417 iOS) are blocked on this ADR** and implement
  exactly the boundary drawn above — ~~the propagation rule, the per-message signature check
  via the #410 storm-gate function~~ **the per-message signature check, now in
  `PublicGossipState.receive`**, and no more.
- **A future proposal to relay anything other than a check-in fact, or to relay across a
  Followed line, is a new decision**, not a reading of this one. It should be argued on its
  own, the way this ADR had to be argued against ADR-0016 rather than assumed from it.
  **ADR-0021 is that decision, made 2026-09-10.**
- ~~**The thing to watch is the payload and the edge.** As long as gossip only ever carries a
  signed check-in fact and only ever travels Contact-to-Contact, this carve-out stays exactly
  as narrow as it is today.~~ **Both widened by ADR-0021: the payload is four kinds, and the
  edge is any device in range. What stays narrow is the background permission this ADR
  granted, which ADR-0021 did not touch.**

## Related

- ADR-0016 — the decision this narrows; presence still authenticates the Contact edge gossip
  relays along.
- #408 — the epic this ADR is part of.
- ~~#410 — the storm-gate module (dedup/TTL/signature, pure function) that enforces the
  propagation rule.~~ **Deleted in #449; the same decision now lives in
  `PublicGossipState.receive` and `GossipEnvelope.valid()`.**
- ADR-0021 — supersedes the propagation rule below; gossip facts are public and carried by
  blind relays.
- #416, #417 — the Android and iOS transport implementations blocked on this ADR.
- `UBIQUITOUS_LANGUAGE.md`, **Contact** and **Followed line** — the edge this decision does
  and does not permit gossip to travel along.

---

## Amendment — 2026-09-08: one gossip wire format, spoken by both transports

Written while implementing #416 (Android) and #417 (iOS). The two were built in parallel and
arrived at two incompatible answers to the same unwritten questions, which is the honest
reason this section exists. It states the **single** scheme both transports now implement,
byte for byte, and says which side yielded and why. Nothing here reverses a decision in the
body above; it fills gaps the body left, and corrects one answer the issues assumed.

### 1. The Token is keyed on the two public keys, not on an ECDH shared secret

The ADR requires that gossip travel Contact-to-Contact and never along a **Followed line**,
so the transports have to recognise a **Contact** over the air. That recognition must not be
a stable identifier: a device broadcasting one would be trackable all night by anyone
standing near it, which is a worse disclosure than the one this ADR already accepts.

**What the issues said.** #408 and #416 both describe the rotating per-Contact value as
~~"an HMAC of the ECDH-shared-secret and a time bucket, truncated"~~, on the grounds that it
needs "no new pairing step, reuses what Exchange already establishes".

**Why that is not implementable against what Exchange actually establishes.** The durable
Contact identity is a single AndroidKeyStore / Secure Enclave keypair created with a
**signing** purpose only (`KeyProperties.PURPOSE_SIGN`, `SHA256withECDSA` over P-256). Three
consequences, all load-bearing:

- Those keys are immutable. An existing key cannot be granted key agreement after the fact,
  and this key is already on every phone that has ever made a Contact.
- `PURPOSE_AGREE_KEY` requires API 31; the app's minSdk is 26.
- A second, agreement-capable keypair would have to be carried on the **Card** handed over in
  person — which is precisely the new pairing step the issue rules out, and it would leave
  every Contact made before that change unable to gossip until the two people met again.

iOS can perform the agreement (`SecKeyCopyKeyExchangeResult`), and #417 implemented it before
this was noticed. It yielded: **a derivation only one platform can compute is not a wire
format.** So the premise "reuses what Exchange already establishes" is the part that
survives, and "ECDH" is the part that does not.

**What is implemented, on both platforms:**

```
keys    = the two peers' base64 Contact identity public keys, sorted lexicographically
bucket  = floorDiv(unix_seconds, 900)                                    // 15 minutes
token   = lower_hex(HMAC-SHA256(key = keys[0] || "\n" || keys[1],
                                msg = "station-to-station/gossip-token/1" || "\n" || bucket
                               )[0..8])
```

- **Sorted, so both ends derive the same bytes.** Without it a token would only ever be
  recognised in one direction, which looks exactly like a flaky radio.
- **`floor`, not truncation.** Both languages divide toward zero, which disagrees with `floor`
  for pre-epoch instants. Both twins floor explicitly; Kotlin uses `Math.floorDiv`, Swift does
  it by hand.
- **Eight bytes, sixteen lower-case hex characters.** Short enough to fit an Android scan
  response beside the service UUID, wide enough that a collision is not a thing that happens.
- **A publisher offers its current bucket; a reader accepts ±1.** Fifteen minutes of clock
  skew is tolerated, an hour is not.
- **A key carrying the joining newline is refused, not escaped**, so two different pairs can
  never encode identically.
- **The domain separator is load-bearing.** The same key material must never produce a value
  valid in two places — the argument `gossipPayload` already makes for the signed payload.

Fixed cross-platform vector, asserted first in both `GossipTokenTests.swift` and
`GossipTokenTest.kt`. If it changes, an iPhone stops recognising a Pixel it has already met,
silently — no error, no log, just two phones that never gossip again:

```
mine = "AAAAkey-mine"   theirs = "ZZZZkey-theirs"   bucket = 1987284   → 09f8a789e6db4230
```

(`1987284` is `floorDiv(1788555600, 900)`, and 1788555600 is 2026-09-04T21:00:00Z — a bucket
boundary, chosen because the boundary is where a floor/truncate disagreement shows.)

**What this costs, stated plainly.** A shared secret is computable only by the two of them.
This value is computable by anyone holding *both* public keys — a mutual Contact of both, or,
under this ADR's own "Disclosure to the relaying device" section, a device that relayed a
message authored by one of them and separately holds the other's key. What such a device
learns is that those two people are within radio range of it. It cannot forge a **Pass** (that
needs a signature over a nonce it cannot produce) and it cannot mint a **Check-in** (same key,
same reason). It is a linkability weakening of the *recogniser*, not a break of the trust
model, and it is accepted here for the same reason the disclosure above is: the alternative
costs every existing Contact a second in-person meeting.

**It is also why the possession proof in §2 is not optional.** A token derived from public
material is a rendezvous hint and nothing more. The signature over a fresh nonce is what
turns "someone who knows both our keys" into "you".

**What would change this.** If the Contact identity ever gains an agreement-capable
counterpart for other reasons — a raised minSdk plus a Card format change, say — the Token
should move to the ECDH form the issues originally described. Only the derivation changes;
nothing else here depends on it.

### 2. The meeting, in two GATT operations

One service, two characteristics, fixed on both platforms:

```
service    7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7721
challenge  7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7722   read
pass       7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7723   write
```

1. The connecting side **reads the challenge**: a fresh 32-byte nonce and the listener's token
   offer, one token per **Contact** for the current bucket. It resolves that offer against its
   own table. No match and it hangs up, having learnt nothing it did not already know.
2. It **writes one Pass**: its own identity key, a signature over the nonce, and the batch.
   The listener checks the key belongs to a **Contact**, checks the signature against the
   nonce *it* issued, spends that nonce, and hands `(from, batch)` to `gossipStormGate` — the
   only thing that judges a message.

**Push-only.** No message is ever read back. Every device runs both halves of the radio, so a
device with something to say connects and writes, and a device with nothing to say never has
to be believed about anything. The one thing read is the challenge, which is unsigned, names
nobody, and is believed about nothing.

**The listener never names itself.** The obvious challenge — "my identity key and a nonce" —
would hand a stable, lifelong identifier to any radio that connects, in the background, all
night. That is the exact disclosure the rotating Token exists to prevent. The offer discloses
only *how many* Contacts the listener holds, and only to something close enough to hold a
connection open — smaller than the disclosure "Disclosure to the relaying device" already
accepts.

The grammar, hand-built rather than left to two JSON encoders, because "whatever the two
platforms happen to agree on" is not a specification. Tab-separated fields in newline-
separated records; UTF-8; a base64 value contains neither separator and a gig id has been
through `isSafeGossipId`, so no field can carry its own:

```
challenge := "station-to-station/gossip-challenge/1" "\n" base64(nonce) ("\n" token)*
pass      := "station-to-station/gossip-pass/1" "\n" from "\t" proof ("\n" record)*
record    := messageId "\t" gigId "\t" checkedInBy "\t" checkedInAt "\t" expiresAt "\t" signature
proof     := base64(sign("station-to-station/gossip-auth/1" "\n" base64(nonce)))
```

Timestamps are decimal epoch seconds, bounded on read. Unreadable *records* are skipped
rather than fatal — one corrupt record must not let a device in the chain suppress the
message next to it; unreadable *headers* are refused whole. A Pass is capped at 40,000 bytes
before anything is decoded, which is separate from `GOSSIP_MAX_BATCH` and not a substitute
for it: that one bounds what is *read*, this one bounds what a peer's write may cost in
memory before anything has been decided at all.

Three domain separators for three uses of the one identity key — the LAN reconcile challenge
(#265), the signed check-in payload, and this possession proof — so a signature made for one
can never be presented as an answer to another.

**Framing, since a batch does not fit in an MTU.** The two directions are framed differently
because GATT frames them differently. A read is a long read: the reader asks again at a rising
offset until it gets a short answer, and the writer serves slices. A write is a sequence of
appends, each at offset 0, terminated by a **zero-length write**, which is therefore
meaningful and never ignored. Not offsets, and not a free choice: a CoreBluetooth central
performs no prepared writes and silently truncates a `writeValue` past the negotiated MTU, so
an iPhone chunks by hand and every chunk arrives looking like the start of the value.
Append-and-terminate is the one framing that reads identically on both platforms, so Android's
peripheral appends to match.

### 3. The advertised token is an Android-only shortcut, not the path

Android also puts one token in its scan response, so an Android scanner can recognise another
Android without opening a connection. **An iPhone cannot do that.** CoreBluetooth's
`startAdvertising` honours a local name and a service-UUID list and nothing else, and a
*backgrounded* iPhone drops the name and moves its service UUIDs into an overflow area only
another iOS device explicitly scanning for that exact UUID can read.

So the advertisement is a saving, never a gate: a scanner that treated missing manufacturer
data as "not a Contact" would never speak to an iPhone. The challenge read is the path both
platforms share, and the connect-and-read baseline runs beside the shortcut rather than
behind it.

This is the shape the **Card** already has, and it is why it is allowed here: one payload, a
cross-platform BLE route that is the required baseline, and a faster Android-only route beside
it carrying identical bytes (`NearbyPeers`, ADR-0016). The bonus layer never replaces or forks
the cross-platform one.

### 4. Messages are held until they expire, not relayed once

`gossipStormGate` (#410) deliberately does not decide this and names the transports as its
owners. The decision: an accepted message is **held and offered to every Contact met before it
expires**, with per-Contact delivery recorded so it is never offered to the same person twice,
and recorded only once the bytes are known to have landed.

The alternative — relay at the moment of acceptance — was rejected because on iOS the moment a
message is accepted is almost never a moment when the Contact who needs it is in range. A
relay that fired only at acceptance would deliver to whoever happened to be standing there,
which is the population that least needs it.

The bound is the same expiry the gate applies, so nothing is held past the night it is about,
and the store is pruned on every read and every write rather than on a schedule. This is a
record of other people's whereabouts and it is treated as one.

### 5. The per-peer rate bound

The other thing the gate names and does not do — nothing in a pure function of a single batch
knows the time between calls.

**One Pass per Contact per minute**, on both platforms: `GOSSIP_PEER_COOLDOWN` on Android,
`gossipPeerCooldown` on iOS. A minute rather than a second because a Pass carries the peer's
whole live outbox, not an increment; two phones that meet have said everything they have to
say on the first connection, and the next one exists only to catch what arrived in between. It
is the *sending* cooldown as well, which is why the two platforms hold the same value even
though it is local admission policy rather than a wire term: a platform with a shorter one
would spend a connection, a nonce and a signature on every push the other refuses.

With `GOSSIP_MAX_BATCH` at 64 that is a ceiling of 64 messages per minute per peer. iOS
additionally caps a Contact at **240 offered messages per rolling hour**, charged on what was
offered rather than what was accepted, which is the real bound on the taken-over-phone attack
the gate describes: 240 signature verifications an hour, however many batches arrive. Android
does not implement the hourly window; the cooldown alone bounds it, more loosely. A refused
Pass is not remembered on either platform — nothing is written, so the same messages are
judged normally on the next connection.

### 6. "OS-throttled and opportunistic", quantified for iOS

"Platform cost, stated plainly" above is right and is not specific enough to set an
expectation with. On iOS, concretely:

- A backgrounded scan is coalesced with every other app's; a peer found in under a second in
  the foreground can take minutes in the background, or the length of the meeting.
- Duplicate advertisements are never delivered in the background, so a meeting that fails is
  not retried by the radio.
- **A backgrounded iPhone is invisible to an Android scanner**, for the overflow-area reason
  in §3. iOS↔Android gossip works when the iPhone is the one scanning, or when it is in the
  foreground. iPhone↔iPhone works both ways, slowly.
- Force-quitting the app ends background delivery until the next manual launch. So do Low
  Power Mode and a Bluetooth toggle.

Android's own throttle is chosen rather than imposed: `ADVERTISE_MODE_LOW_POWER` and
`SCAN_MODE_BALANCED`, and a foreground service that runs only when the device holds a
**Contact** *and* is either carrying a live message, inside a **Gig**'s night window, or has
been told to always relay. Nothing schedules its shutdown; it falls out of the expiry the gate
already enforces.

**The product consequence, which is the part that matters:** a check-in reaches a Contact
*eventually and probably*, if the two phones are in the same place for long enough. Nothing in
the app may tell a user their arrival "was sent" or "will reach" anyone. The walk-home case
this ADR exists for is exactly the case this delivers; a message channel is exactly what it is
not.

### Still open

- **No user-facing off switch.** Android decides *when* the radio runs (above), but neither
  platform offers "gossip off" while Contacts exist: the only way to stop it is to remove
  every Contact or deny the Bluetooth permission. That is a product question this ADR did not
  settle and neither #416 nor #417 invented an answer to; it should be raised as its own
  issue.
- **No cross-platform integration test.** Both sides assert the same fixed Token vector and
  the same wire grammar from the same fixtures, which is what catches a drift in the bytes. It
  is not the same as two phones in a room, and nothing in CI can be.

---

## Amendment — 2026-09-11: the propagation rule is superseded, and the storm gate is gone

Written after #449 deleted the v1 pipeline. Two things above are no longer true, and the
house rule is that they stay on the page struck through rather than disappear. Nothing here
reopens the background permission this ADR exists to grant — that survives intact and is
still the only thing on this page load-bearing for the current design.

### 1. Contact-only propagation is superseded by ADR-0021

The body argues, at length, that a gossip message must travel only along the mutual,
in-person edge ADR-0016 authenticates. **ADR-0021 reverses that**: gossip facts are public,
signed assertions carried by *any* device in BLE range, including devices that hold no
Contact relationship with the author and cannot tell who the author is.

The reasoning that replaced it is on ADR-0021's own page and belongs there, but the short
form is the one this ADR could not see: Contact-to-Contact relay is a rendezvous problem, and
in a sparsely adopted venue the set of devices that are both a Contact *and* in range is
usually empty. The carve-out this ADR won was for the walk home; the rule it paired with the
carve-out meant almost nothing made the walk.

What replaced the trust argument is not a weaker version of it. A fact is signed by a
**temporary per-Gig key**, and the durable Contact key that proves authorship is *sealed*
into the attribution field under a key derived from the author's Card public key. A blind
relay carries bytes it cannot attribute; a Contact who already holds that Card key recognises
the author permanently, including after removing the Contact. So the disclosure this ADR
accepted under "Disclosure to the relaying device" — a stable identity key handed to a
stranger-of-a-friend — is one v2 does not make. That section is now describing a cost the
design no longer pays, and it is the one place where being superseded made the record
*better*.

**Block** consequently changed meaning: it governs local application admission only. A
blocked author's facts still cross the radio, they simply never enter this device's record.

### 2. The storm gate is not a module any more

The body names `gossipStormGate` (#410) three times as the pure decision function enforcing
all of this. That module was deleted in **#449**, along with the rest of the v1 pipeline it
belonged to, having been unreachable since `2a1838e` replaced the v1 transport.

The decision it held did not go anywhere — it was not weakened and it was not spread around.
It moved into two named places, both still pure and still asserted from both platforms'
suites:

- **`GossipEnvelope.valid()`** — the per-Envelope grammar: field bounds, the content hash
  matching the signed bytes, the signature, and the recursive check that a `witness` embeds a
  real `request` it did not author.
- **`PublicGossipState.receive()`** — the stateful half: dedup against `seen`, expiry and
  clock-skew bounds, one-hop enforcement for `request` and `receipt`, the `blocked` check, and
  admission to the outbox.

`CONTEXT.md` keeps the term **Storm gate** for the rule, because the rule is what the
vocabulary was ever about. It is no longer the name of a file.

### What did not change

- **The background permission.** A device may still advertise, listen and relay with no
  Exchange screen open and nobody watching. Every word of "Platform cost, stated plainly" and
  of §6 in the 2026-09-08 amendment still applies unchanged.
- **Reconcile, media and Notes.** Still foreground-only, still Contact-scoped, still
  fingerprint-bound. ADR-0021 says so explicitly, and it is the boundary that makes widening
  gossip affordable: the private things never moved.
- **A Contact is still only ever minted in person.** Gossip mints nothing and carries no Card.
- **§1 of the 2026-09-08 amendment is now history rather than specification.** The pairwise
  **Token** it settled, and the fixed cross-platform vector it turns on, described v1
  recognition. v2 has no Contact tokens on the air at all — Android advertises the service
  continuously — so nothing derives that value any more. The argument for why a derivation
  only one platform can compute is not a wire format is worth keeping; the derivation is not.

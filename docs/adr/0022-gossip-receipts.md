# ADR-0022: Receipts credit the neighbour that delivered, and a partial version ships

**Status:** accepted (2026-09-16)

## Context

ADR-0021 widened public gossip to blind relays and listed, among the things a **Gig** record
wants to move between phones, "a signal that something was useful". The receiving half of that
signal was built: `PublicGossipState` has carried a `useful` map, consumed a `receipt` **Fact**
on arrival, and pruned the credit on expiry for months. Nothing has ever authored one. A sink
with no source, and therefore a routing policy that has never once changed a decision.

Issue #455 gated the usefulness work behind the locked-iPhone viability experiment. The user
lifted that gate on 2026-09-16, having deferred iPhone hardware indefinitely the day before:
a gate on an indefinitely deferred item is a permanent block. The instruction was explicit —
**ship a crappy version that actually does something, and tune from real usage.**

Three things had to be decided to do that, and only one of them was written down anywhere.

## Decision

### 1. Credit is for a neighbour *this* device should prefer, and the two directions read
different fields

This is the part the shipped receiving half had wrong, and it only became visible once
something authored a receipt.

`receive` credited `useful[envelope.text]` unconditionally. Play that through. This phone
receives a **Fact** from neighbour `N`, recognises it as a **Contact**'s, and authors a receipt
naming `N`. The receipt travels one hop *to `N`*. At `N`, the arriving receipt's `text` is `N`'s
own handle — so `N` would credit itself, which is not a routing fact about anybody. Worse, a
receipt that reached anyone else would write a stranger's handle into their table and tell them
which neighbours this phone stands next to.

So the field read depends on the direction:

- **A receipt this device authored** (`local`) names the delivering neighbour in `text`, and
  that is the handle credited. The evidence is local and first-hand, which is story 36.
- **A receipt arriving over the air** credits `from` — the handle the transport proved. The
  sender found this device worth sending to, which is a real routing fact for this device, and
  it is one no third party can forge into the table.

Because a receipt is one-hop, `from == author` on the wire, so this is not a new trust
assumption; it is reading the field that was already proved instead of the one that was not.

### 2. A receipt is addressed, not gossiped

`offer` withholds a held receipt from every peer but the one its `text` names. This is what
makes the leak in the paragraph above structurally impossible rather than merely unlikely, and
it is the only place in the design where a **Fact** has a recipient. A receipt is worth exactly
nothing to anybody else; the alternative is broadcasting a list of who this phone stood near.

`passBatch` gained the other half: a receipt rides only a **Pass** signed as its own author,
the same rule a `request` already lived under and for the same reason — the receiver refuses a
one-hop control whose author the **Pass** does not prove. A receipt whose turn it is not waits
for a **Pass** signed as the relay.

### 3. The emitter is reachable only from the receive path

`receiptFor` is pure, takes the delivering neighbour and whether the **Fact** was recognised as
a **Contact**'s *now*, and is called from exactly one place on each platform: the code that has
just admitted a batch. Story 37 — attribution learned later, after an **Exchange**, must not
count as fast delivery — is therefore structural rather than a rule. There is deliberately **no
timestamp comparison** guarding it: a comparison would imply lateness is reachable, and it is
not. The test asserts it anyway.

### 4. Ranking is about which neighbour to push to, and it lives on Android

Stories 38 and 39 say "useful neighbours offered first, with random tie-breaks", and #455's
code map pointed at `offer(peer, …)`. That pointer is at the wrong function: `offer` takes one
peer and returns **Facts**, so usefulness cannot rank anything inside it. The question is which
*neighbour* to spend a connection on.

`gossipPreferredPeers` is that rule: shuffle the whole candidate list under an injected RNG,
then put the credited band first. Preference, never exclusion — every candidate seen is still
in the list, because a phone that only ever spoke to neighbours that had already proved useful
would never learn that any other one is. The RNG is a parameter for one reason: a test cannot
assert "randomly" without a seed.

It binds on Android and not on iOS, and that asymmetry is deliberate. Android's `GossipCentral`
holds one connection at a time, so a sighting it takes is a sighting it spends — but the scan
reports one device at a time, so without a gathering window there is no set to prefer *within*
and the first advertisement always wins. Hence `GOSSIP_PICK_WINDOW_MS`: a second and a half of
sightings, then one choice. `GossipTransport` on iOS opens a meeting with every peripheral
`didDiscover` reports and has no scarce slot to ration, so the rule lives in `GossipBudget.swift`
with its twin test but no caller. Wiring it is a change to `beginMeeting`, worth making when
iOS caps concurrent meetings.

### 5. The decay is its own constant

`PUBLIC_RECEIPT_MS` / `publicReceiptMs`, two minutes, separate from the 15-minute **Carry**
window and from the 30-minute grace period. The three answer different questions — how long a
**Fact** is worth relaying, how long a routing hint is worth trusting, how long the user stays
in the night — and tying any two together means tuning one silently retunes another (story 41).

## Parameters, and what each one rests on (story 42)

| Parameter | Value | Status | Evidence |
| --------- | ----- | ------ | -------- |
| Carry window | 15 min | **provisional** | A knee in a dense synthetic model (`sim/SWEEPS.md`). Simulation, comparing policies — not a measurement of this value. |
| Usefulness decay | 2 min | **provisional** | None. A guess about how long a crowd holds still. |
| Pick window | 1.5 s | **provisional** | None. Chosen against the same discovery budget as the scan mode; costs that latency on every push. |
| Grace after Done | 30 min | **provisional** | A product choice, stated as one in #455. Not a battery figure. |
| Peer cooldown | 1 min | settled as a wire-adjacent term | Both platforms must agree or every second push is spent on nothing; the argument is in `GossipBudget.swift`. |
| Max batch / wire bytes | 64 / 40,000 | settled | Wire terms. A peer truncating differently reads a different **Pass**. |

**No value in this table was settled by simulation, and none may be.** `sim/SWEEPS.md` compares
policies against each other; quoting it as a measurement of a parameter is the specific mistake
this row of the table exists to prevent. The policy is to tune from real usage.

## Consequences

What this buys: receipts are real end to end. A **Fact** recognised as a **Contact**'s produces
a signed receipt that names the neighbour who handed it over, reaches only that neighbour, and
moves both devices' routing preference — without ever entering `facts`, retiring an **Envelope**
or touching the **Storm gate**.

What ships knowingly missing, said here rather than discovered later:

- **Handle stability is unproven, and this is the most likely way v1 is a no-op in the field.**
  Credit is keyed by whatever handle the transport proved. On a platform that rotates its BLE
  address, the next meeting is a different key and arrives uncredited, so credit may never
  accumulate across meetings. The failure mode is *no preference* — today's behaviour — never a
  peer that stops being offered, because story 39 forbids exclusion. It is the first thing to
  measure.
- **A receipt costs a round of bytes for a hint that expires in two minutes.** Whether that
  trade is worth it is a question for a real night, not for the simulator.
- **No locked-iPhone proof.** #446 stays deferred. Receipts shipping does not establish that an
  iPhone relays anything at all.
- **iOS does not rank.** See above.

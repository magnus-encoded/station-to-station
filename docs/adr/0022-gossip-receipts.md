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

#### 2a. An address is only an address in the namespace a meeting proves (amended 2026-09-16)

This ADR said "the one its `text` names" without saying *which key* names it, and the first
implementation named the wrong one. The receipt was addressed with the **Pass** signer, and a
**Pass** is signed as the relay key only when it carries no request of its signer's own —
`passAuthor` reaches for a **Gig** key exactly when it does. The one egress compares against
`challenge.from`, which is always a relay key. So every receipt authored on a request-carrying
**Pass** named a **Gig** key that no peer would ever equal: it stayed in `held` until
`PUBLIC_RECEIPT_MS` expired, was never offered to anyone, and its local credit was written to
`useful[<Gig key>]` where the ranker reads `useful[<relay key>]`. Both halves silently dead.

There are two keys in play and they are not interchangeable. A **Gig** key is a *signing*
namespace: it says who asserted something. A relay key is the *addressing* namespace: it is the
only identity a meeting ever presents, because the challenge carries nothing else. An address
written in the signing namespace is not a weak address, it is not an address.

The fix keeps the address structural — `offer` still compares one field to the one peer, and
there is still exactly one egress — by refusing to author an address that cannot be resolved.
`passRelay` reads the relay key off the wire rule rather than guessing it: a **Pass** whose
batch carries a request its own signer authored (the only kind the receiver will admit) was
signed as a **Gig**, and this device has no way to name its sender; anything else was signed as
the relay. When it returns nothing, no receipt is authored at all.

The rejected alternative was to widen `offer` to compare against a set of keys believed to
belong to one peer. That turns a structural rule into a policy one and reintroduces exactly the
leak section 1 removed, because the set would have to be built from keys nobody proved.

The cost is stated rather than hidden: credit is skipped on the push where a neighbour carries
its own request, which is a common push. It is not lost for good — that request is marked
delivered, so the same neighbour's next **Pass** to this device carries none (`passBatch` admits
no request without one to sign as) and is addressable, and the 1-minute peer cooldown is inside
the 2-minute decay. If measurement shows credit still never accumulates, the answer is to carry
the signer's relay key on the **Pass** and prove it against the same nonce, which is a wire
change and wants its own ADR.

That deferral has now been traced rather than assumed, and it holds with two edges worth
writing down. `held.delivered` is per-peer and `offer` skips anything already delivered to the
peer it is building for, so a request that reached this device is not offered back to it and
`passAuthor` finds nothing to sign as on the next **Pass**. `gossipPassDue` spaces those
**Passes** by `GOSSIP_PEER_COOLDOWN`, one minute, which is inside `PUBLIC_RECEIPT_MS`. But
`delivered` is only recorded when a push completes to its last chunk, so a link that keeps
dying re-offers the same request and keeps producing unaddressable **Passes**; and a *new*
request from the same neighbour buys another deferral of its own. The deferral is per request,
not once per neighbour.

#### 2b. One receipt per **Gig** record per batch, not one per **Fact** (amended 2026-09-16)

A receipt carries the **Fact**'s `gigId`, `formerIds` and `scope` and nothing else that varies
within a batch, so two **Facts** of the same record — two lines of one `log`, the ordinary
shape of a **Pass** — author byte-identical receipts with the same `id`. Authored one at a
time, the second is a duplicate, and the **Storm gate** answers a duplicate by dropping the
held copy. Two recognised **Facts** from one neighbour therefore produced no offerable receipt
at all.

The invariant is that a receipt is a fact about the *neighbour*, not about the line: "you
handed me something I wanted" is said once per record however many lines arrived. `receiptsFor`
holds it — filter to recognised, keep one per `(gigId, formerIds, scope)`, author those — and
the **Storm gate** is untouched, because the collision was made in the authoring, not in
`receive`. The filter runs before the de-duplication, so an unrecognised first line of a record
cannot mask a recognised second one.

`receiptsFor` sits inside the §2a guard, never around it: when `passRelay` returns nothing,
there is nobody to owe and no batch to de-duplicate.

### 3. The emitter is reachable only from the receive path

`receiptFor` is pure, takes the delivering neighbour and whether the **Fact** was recognised as
a **Contact**'s *now*, and is called from exactly one place on each platform: the code that has
just admitted a batch. Story 37 — attribution learned later, after an **Exchange**, must not
count as fast delivery — is therefore structural rather than a rule. There is deliberately **no
timestamp comparison** guarding it: a comparison would imply lateness is reachable, and it is
not.

**No test asserts this, and an earlier version of this ADR said one did.** The property is
"`receiptFor` has exactly one production caller per platform, and that caller is the receive
path" — a fact about the call graph, which no call of `receiptFor` can witness, because every
call a test can make is by definition a second caller. The test that was cited asserts something
real but different: that `recognised = false` yields nothing, which is the argument's contract
(story 36), not story 37. The two tests are renamed to say so. Story 37 is held by the shape of
the code and by review, and the honest place to record that is here rather than in a green
assertion that looks like proof of something it never touched.

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

**What the ranker is keyed by, corrected.** A review read `resolved: MutableMap<String,String>`
in `GossipRadio.kt` as making the BLE MAC the ranking key, and concluded the ranker is keyed by
an identifier that rotates by design. That is not what the code does, and the real problem is
worse rather than better, so it is worth being exact.

The key `credited` is asked about is the peer's **nightly relay key** — `GigIdentity("relay-<date>")`,
a device-local keystore identity, the same key its challenge carries and the same key its **Pass**
is normally signed with. It is stable for a whole night. `resolved` is not the ranking key; it is
a *memo* from a BLE address to that handle, and the address is only its lookup key.

The memo is the part that does not work, and the reason is not rotation:

- An entry is written in exactly one place, after a challenge read completes
  (`GossipRadio.kt`, `onCharacteristicRead`). **A sighting can therefore never be credited before
  this phone has already connected to that address once.** A first sighting of anybody is
  uncredited by construction, which is precisely the case ranking exists to decide.
- Having connected, `attempted` holds that address out of the candidate set for
  `GOSSIP_PEER_COOLDOWN`, one minute. Credit for that peer lives `PUBLIC_RECEIPT_MS`, two
  minutes, and is earned on the *other* half of the radio — a receipt arriving from that peer's
  central into this phone's peripheral. So the interval in which a sighting of a peer is both
  eligible and credited is at most about a minute wide, per peer, per credit event.
- **Address rotation is not the binding constraint.** Both platforms rotate a resolvable private
  address on the order of fifteen minutes; credit expires in two. The decay outruns the rotation
  by a factor of seven. Rotation costs a memo entry that had usually expired anyway.
- `resolved` is cleared on `stop()`. For the same reason, this is not the binding constraint
  either.

**Conclusion: ranking cannot be keyed usefully at sighting time under the advertisement this
channel ships, and no change inside `GossipRadio` can make it so.** Crediting a sighting requires
something in the advertisement to key on, and the channel deliberately advertises a bare
connectable service UUID — the only thing a backgrounded iPhone can be relied on to broadcast
(see the file comment in `GossipRadio.kt`, and ADR-0019). Putting a stable identifier in service
data would be a wire change that a backgrounded iPhone cannot hold up its end of, and a stable
broadcast identifier is a tracking beacon for anyone with a scanner — a privacy cost this design
has refused everywhere else. **So nothing was invented here.** `gossipPreferredPeers` stays, with
its behaviour stated correctly rather than dressed up: today it is, in all but a narrow window, a
shuffle.

What would actually be needed, if this is ever worth doing: a per-pair rotating token in the
advertisement that only a device holding the pair secret can recognise — which is exactly what
gossip v1 had and what ADR-0021 removed when it widened relaying to blind edges. Reintroducing
it means reintroducing the thing that made v1 Contact-only. That is a design decision, not a
patch, and it is out of scope here.

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

## How we will know (the measurement, #444)

Every reason this feature might be a no-op presents identically from the outside — as "no
preference", which is also what correct looks like when nobody is in the room. `GossipTally` on
both platforms counts six things and prints one line at the end of the night:

| Counter | What a zero means |
| ------- | ----------------- |
| receipts authored | Nothing was ever recognised as a **Contact**'s at receive time. The feature never started. |
| receipts declined | Never zero and never dominant is the expectation. A **Pass** arrived that §2a could not address, so nothing was owed on it. If this dwarfs *authored*, the deferral in §2a is not deferring — it is losing — and the wire change §2a names is due. |
| receipts offered | Receipts exist but never reach a **Pass** — addressing or `passBatch` is eating them. |
| receipts delivered | Passes carrying receipts never complete. A transport problem, not a policy one. |
| pick windows | The central never ranked anything: it is never seeing two peers in one window. |
| credit hits | **The falsifier.** Windows ran and not one candidate held live credit. Ranking is a shuffle, and no amount of tuning the decay changes that. |

Counts of events, never of peers; nothing in the tally names anybody, which is why it is safe to
log. It is deliberately not wiped by `radioStopped` — a tally that resets when the relay stops is
a tally nobody can read, because stopping is when you go looking.

`credit hits` is expected to be zero, for the reason §4 gives. Measuring it is how that stops
being an argument and becomes a fact.

## Consequences

What this buys: receipts are real end to end. A **Fact** recognised as a **Contact**'s produces
a signed receipt that names the neighbour who handed it over, reaches only that neighbour, and
moves both devices' routing preference — without ever entering `facts`, retiring an **Envelope**
or touching the **Storm gate**.

What ships knowingly missing, said here rather than discovered later:

- **Ranking is a shuffle in almost every real window, and this is known rather than suspected.**
  Not because handles rotate — they do not; the nightly relay key is stable for the night — but
  because a sighting cannot carry a handle at all, so no peer can be credited until after this
  phone has already connected to it. §4 works the timing through. The failure mode is *no
  preference* — today's behaviour — never a peer that stops being offered, because story 39
  forbids exclusion. Shipping it anyway is a deliberate choice: the ranking rule is the part
  worth having written down and agreed across both platforms, and it costs nothing while it
  never fires.
- **The pick window no longer throws the room away while the central is busy.** It used to clear
  `sighted` before the `busy || !running` guard, so a window closing mid-connection discarded its
  whole candidate set. Harmless in practice — `pauseForPush` takes the scan down, so no sighting
  arrives to open a window during a push — but the ordering should not have depended on that.
  Retained sightings are re-checked against the peer cooldown when the window finally closes.
- **No receipt is authored for a **Pass** that carries its signer's own request.** Section 2a:
  such a **Pass** proves a **Gig** key, and this device cannot address one. The credit is
  deferred, not lost — that request is marked delivered to this peer, so the same neighbour's
  next **Pass** carries none and is addressable — but the deferral is one `GOSSIP_PEER_COOLDOWN`
  long, and it only holds for a push that completed. A push that dies before
  `confirmDelivery` leaves the request undelivered, and the next **Pass** carries it again.
- **One receipt per **Gig** record per batch, not one per **Fact**.** Section 2b. Two lines of
  one `log` author byte-identical receipts, so the batch owes one thing, said once.
- **The three fixes above do not add up to working ranking, and this is stated rather than
  implied.** Addressing and de-duplication make authoring, offering and delivery real — all
  three were dead before, in the ordinary cases — so the receipt half of this ADR now works end
  to end. The *consumer* is what does not: for a credit hit, this phone must (a) hold live
  credit for a peer, which decays in `PUBLIC_RECEIPT_MS`, and (b) already have `resolved` an
  address to that peer's relay key, which only a completed connection writes, and (c) be past
  that address's one-minute `GOSSIP_PEER_COOLDOWN`. The credit is earned on the peripheral half
  of the radio and spent on the central half, and nothing carries it across. The honest summary
  is that receipts now cross the wire and ranking still almost never fires. `GossipTally` is
  here to say which of those two sentences the field disagrees with.
- **A receipt costs a round of bytes for a hint that expires in two minutes.** Whether that
  trade is worth it is a question for a real night, not for the simulator.
- **No locked-iPhone proof.** #446 stays deferred. Receipts shipping does not establish that an
  iPhone relays anything at all.
- **iOS does not rank.** See above.

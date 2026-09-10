# Handoff: finishing public gossip v2 (#408)

**Written** 2026-09-10, ~02:00, by the session that took over when the previous
implementation session hit its 5-hour quota mid-flight.
**Branch** `gossip-ble-diagnostics`. **Normative spec** [`gossip-public-wire.md`](gossip-public-wire.md).
**Simulator brief** [`../handoff.md`](../handoff.md).

Nothing in this document is a decision. It is a survey of where the work actually
stands, written so the next pass does not spend its first hour rediscovering it.
Findings below were produced by reading the code, not by running it — where one
matters enough to act on, verify it before you build on it.

## The one fact that matters

**The v2 code is an unwired island.** As of `19abda1`:

```
grep -rln 'PublicGossipState|GossipEnvelope|GigIdentity|PublicGossipPass' android/ ios/
```

returns exactly six files: the two `PublicGossip` implementations, the two
`GigIdentity` implementations, and their two test files. `GossipRadio.kt` (792
lines) and `GossipWire.swift` still speak only v1 Contact-only gossip. Nothing
calls `offer()`, `receive()`, `project()`, `encodePublicGossipPass` or
`decodePublicGossipPass`. `TimelineStore` has no slot for `PublicGossipState` on
either platform, and no SwiftUI or Compose surface renders a projected fact.

What exists is a correct-looking wire format, a state machine, and an identity
scheme, with unit tests that exercise them in isolation. What does not exist is a
feature. Two phones running this branch today gossip exactly as they did before.

## Work order

Do these in order. Each step is independently committable, and the branch should
be green after each one — see *Rules of engagement*.

### 1. iOS cannot author an Envelope. Close that first.

`GigIdentity.swift` has no method that signs with the per-Gig P-256 key, and no
method that exports the temporary public key as SPKI. `PublicGossip.swift`'s
`GossipEnvelope.signed(_:)` therefore has nothing to hand its closure, and the
`author` field has nothing to fill it with. Until this is closed, iOS is
receive-only and the feature is half a feature.

The trap, and it is a quiet one: the spec requires a **base64 DER ECDSA
signature**, matching Android's `Signature.getInstance("SHA256withECDSA")` in
`GigIdentity.kt:31-33`. CryptoKit's `ECDSASignature.rawRepresentation` is 64 raw
bytes of `r‖s` and is **not** DER. `rawRepresentation` is the obvious call, it is
already used correctly two lines away in `GigIdentity.swift:19,24` for raw
keychain storage, and if you reach for it here every iOS-authored fact fails
verification on every receiver — including locally — while
`PublicGossipState.receive` returns a bare `false` and logs nothing. Use
`.derRepresentation`. `ContactIdentity.swift:45` already does this for the Card
key; follow it.

The masked-attribution path is the good news: `GigIdentity.swift:39-48` and
`GigIdentity.kt:45-60` agree exactly on AES-256-GCM, both domain-separation
strings, and the nonce‖ciphertext‖tag layout. Leave it alone.

### 2. Prove parity with a shared fixture before wiring anything

**This project already decided how to do this.** #435/#436 reconciled two
incompatible Android and iOS gossip schemes and settled it with a fixed vector
asserted identically on both platforms — "Both platforms assert the fixed vector
`09f8a789e6db4230`". Do the same for v2 rather than inventing a third approach.

`fixtures/gossip/` exists and is empty — the previous pass created it and never
filled it. `fixtures/weave/` is the precedent for the file layout: named case
directories, a `README.md`, and a loader per platform (`WeaveFixture.kt` and its
Swift twin).

Write a fixture holding a signed Envelope and a signed Pass, and have both
platforms' tests decode it, re-encode it, and assert byte equality. Field order,
base64 padding, hash hex case, how `line` and the epoch millis render as strings,
negative-number formatting, UTF-8 byte-length versus character-count checks, and
the empty-optional encoding are all places where two independently written
implementations agree until the day they don't. A fixture in CI is the only thing
that catches that; two phones on a desk will not, because failure is silent.

### 3. Wire v2 into the radio, both platforms — and do not preserve v1

Android: `GossipRadio.kt` → `GossipWire.kt` / `GossipMint.kt` / `GossipStormGate.kt`.
iOS: `GossipWire.swift` (entirely `gossip-challenge/1`) plus `GossipChannel.swift:109`.

**Check before you build compatibility machinery: the gossip transport has never
shipped.** `v1.8.0` — the latest release, 2026-09-07 — contains exactly five gossip
paths: `GossipStormGate` on both platforms with its tests, and ADR-0019.
`GossipRadio.kt`, `GossipWire.kt` and `GossipToken.kt` are in **no tag at all**.
Verify it yourself in one line:

```sh
git ls-tree -r --name-only v1.8.0 | grep -i gossip
```

So there is no installed base to negotiate with. **Replace v1; do not write version
dispatch to coexist with it.** A discriminator that protects nobody is code that
has to be carried, tested and eventually removed. The `/1` and `/2` header strings
still differ, which is enough to make a stale build fail closed rather than
misparse — that is all the safety this needs.

The one real caveat, worth naming rather than assuming away: `sideload-iphone.sh`
re-signs the newest CI build onto the user's own iPhone, and `testflight-setup.txt`
describes the same path. So a handful of the user's personal devices may be running
non-release builds that speak v1. That is a fleet of two that a reinstall fixes,
not an installed base — but say so in the PR rather than letting someone discover
it when their phone goes quiet.

`GOSSIP_MAX_WIRE_BYTES = 40_000` already exists at `GossipWire.kt:114` and matches
the spec's Pass limit — reuse it, don't redeclare it. The existing GATT
chunk-and-empty-terminator framing and the 512-byte attribute ceiling stay as they
are.

### 4. Persist, then project

`PublicGossipState` is `@Serializable` on Android and `Codable`-clean on Swift, so
persistence should be a slot in the existing `TimelineCache` on both sides
(`TimelineStore.swift` ~445/653/663; `TimelineStore.kt` already round-trips
`MutableMap`/`MutableSet` of `@Serializable` types at ~672). `project(gigIds:)` is
the only place facts become a deduplicated timeline and it is called from nowhere.

### 5. Bugs worth fixing while you are in there

- **The Pass byte budget is computed twice, inconsistently, and fails closed.**
  `offer()` reserves a flat 512 bytes of header (`PublicGossip.kt:118`) while
  `encodePublicGossipPass` (`:71-75`) actually writes the 32-byte header line plus
  `from\tproof`, each of which `decodePublicGossipPass` permits up to 256 bytes.
  When the real header exceeds the reservation the encoder returns `null` and the
  **entire Pass** is dropped rather than trimmed. The symptom in the field is
  "gossip randomly stops relaying", with nothing in the log. Compute the budget
  once, and make the encoder trim rather than fail.
- **Receipts do not affect neighbour priority.** The spec says a received receipt
  does. `receive()` populates the `useful` map (`:113`), and `offer()` sorts
  strictly by `createdAt` descending (`:119`) and never reads it. Either wire it or
  amend the spec, but do not leave the map dead.
- **One-hop enforcement for `request` and `receipt` is not enforced on the wire.**
  `receive()` declines to put them in the outbox, which is the right local
  behaviour, but nothing rejects a relayed one arriving from a third party.
- **`expiresAt` is never derived.** The spec puts message expiry at 06:00 at the
  end of the Gig's night. `valid()` applies only a generic 30-hour sanity cap
  (`:40`). Whatever mints Envelopes has to compute the real value.
- **Concurrency.** `PublicGossipState`'s collections are plain and unsynchronised,
  and `GigIdentity`'s Keystore calls are synchronous. Once this sits between GATT
  callback threads and a Compose-observed state flow that is a race and an ANR,
  not a style note.

### 6. The simulator, and what it is for

`sim/` is a deliberate simplification of `handoff.md`, not an attempt at it:
`models.py` holds three enums, `simulation.py` holds one monolithic
`run_simulation` that replays an immutable `Fact`/`Encounter` trace through
policies named by string literal, and `information.py`, `population.py`,
`mobility.py`, `policies.py`, `application.py` and `metrics.py` are one-line
docstring stubs. Four tests, in `unittest` rather than the brief's `pytest`. All
four pass.

**Build on it; do not restructure it.** What it already does — replay one evolving
workload through several policies and compare them — is verbatim the brief's own
stated first milestone. Decomposing it into the brief's full venue/mobility/BLE/
policy-protocol layering now would spend the pass on architecture instead of on
the questions the spec is actually waiting for.

Those questions are the four things `gossip-public-wire.md` marks as unsettled:

| Parameter | Spec wording | Sim status |
|---|---|---|
| Carry window, 15 min | "a provisional default" | `config.carry_seconds` exists and one test exercises it. Needs a sweep and a coverage metric. |
| Receipts one-hop | "pending further simulation" | Not modelled at all. No hop count, no receipt type, no hop limit. Biggest gap. |
| Usefulness, 2 min, binary, random ties | same sentence | Half-modelled: `useful[sender] = now`, read only inside the `focus` branch against a hard-coded `now - 120`. Not general, not parametrised, no random tie-breaking. |
| 128 Envelopes / 128 KB / 8,192 seen IDs / cooldown | "remain tunable" | Present as config, never swept. |

Shortest path, all additive:

1. `hop_count` on facts and a `max_hops` check in the forwarding loop → answers
   one-hop receipts.
2. A carry-window sweep helper and a `coverage_percent` reducer → answers the
   Carry window.
3. `is_useful(now, delivered_at, window_seconds=120)` as a standalone function
   with the random tie-breaking the spec calls for, then sweep the window.
4. Reducers for median/p95 convergence and receipt probability. The brief's
   "primary product metrics" are entirely absent today; only raw counters exist.
5. One test per new function. Migrating to `pytest` is cheap, do it while you are
   there.

**Then close the loop.** A number that stays in `sim/` has not answered anything.
When the sweeps produce defensible values, amend `gossip-public-wire.md` to state
them and say what produced them, so the next reader finds a decision instead of
another provisional default.

### 7. ADR-0019 does not cover what v2 does

This is not paperwork, and nothing in `gossip-public-wire.md` addresses it.

ADR-0019 is **accepted**, and its entire justification is Contact-gated: it carves
gossip out of ADR-0016's "nothing advertises, listens or accepts in the background"
specifically to relay "a check-in fact — *this Contact was at this Gig* — from the
Contact who checked in to other Contacts". Every sentence of that carve-out assumes
both endpoints are Contacts.

v2 discards that premise. Facts are public, any user is a blind relay, and a
stranger's phone now carries and forwards bytes on behalf of people it has never
exchanged with. ADR-0016 — *presence is the authentication* — is precisely the
decision blind relay puts under load, and ADR-0019's carve-out was written narrow
on purpose so that it would not silently widen.

So this branch owes an ADR: either an amendment to 0019 or a new one that
supersedes the Contact-only part of it, saying what the widened boundary is and why
masked attribution is a sufficient answer to the objection 0016 raises. House rule,
stated in `docs/festival-model-handoff.md`: **ADRs are appended and amended, never
rewritten.**

**But it is not a gate, and it does not come first.** The design is still moving and
none of it is in the wild, which is the cheapest this will ever be to change —
spending the pass writing down a boundary that the next hour's work moves is worse
than useless, because a wrong ADR is harder to dislodge than no ADR. Get the design
right while it is still free, then write the ADR as the record of where it landed,
in time for the PR. If the argument turns out not to be makeable, that is a finding
about the design and worth surfacing immediately — not a reason to keep drafting.

## Landing it

One PR for the whole feature at the end — not a PR per slice. `Fixes #408.` in the
body. #436 is the model for shape and depth: prose that argues the design
decisions, a `## What is here` section annotating the files, and the reasoning for
anything that diverges from what the issue assumed. Do not merge it.

Commit incrementally along the way regardless; the single PR is about how the work
is *reviewed*, not about hoarding it in the working tree until the end.

## Rules of engagement

- **Check both CI workflows after every push.** The previous pass pushed a
  test-only commit (`c4f86a7`) whose subject landed in the *next* commit, watched
  the iOS failure, and never looked at Android — which had failed identically.
  `gh run list --branch gossip-ble-diagnostics --limit 4`.
- **Never `git add -A`.** `pc/` (a Rust crate) and `sideload-iphone.sh` are
  untracked on purpose and are not yours to land. `.cursor/` and
  `testflight-setup.txt` are now ignored.
- **Commit incrementally.** The previous session ended mid-thought with four files
  on disk that no build had ever seen. Small commits mean the next interruption
  costs less.
- `core.autocrlf=true` on this clone, because the files came off Windows. The
  "LF will be replaced by CRLF" warnings are expected and are not a problem.
- The full per-platform gap analyses that this document condenses are in
  `docs/notes/` alongside it, if you want the clause-by-clause detail.

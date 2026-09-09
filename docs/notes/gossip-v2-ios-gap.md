# iOS gap analysis: public Gig gossip v2 (#408)

## 1. Wire parity: PublicGossip.swift vs PublicGossip.kt

No byte-level divergence found in `PublicGossip.swift`/`.kt`:
- Field order/count identical (10 fields between header and signature, 12-field tab record).
- `formerIds` comma-join/split: both drop empty subsequences (Swift `split(separator:)` default
  `omittingEmptySubsequences: true`; Kotlin `.filter { it.isNotEmpty() }`) -> same result.
- base64 alphabet: both use standard (`Data.base64EncodedString` / `java.util.Base64.getEncoder()`),
  same padding behaviour.
- hash hex: both lower-case (`%02x` / `"%02x".format`).
- Int64/Long `.toString()` formatting for createdAt/expiresAt/line: identical decimal, and both
  reject negatives via range checks before they'd matter (line >= -1, createdAt >= 0).
- `valid()` logic is semantically identical modulo statement ordering (AND-combined, so order is
  irrelevant to outcome), including the witness-kind extra checks.
- Pass framing (`encodePublicGossipPass`/`decodePublicGossipPass`): identical header, identical
  claim-line tab format, identical 64-record cap and `gossipMaxWireBytes` ceiling.
- `PublicGossipState.receive/offer/project`: logic matches field-for-field, including the
  120s usefulness window, 128/128000 held eviction, and the `line:`-keyed log/witness/receipt
  differentiation in `project`.

**No divergence in this pair as committed.** This is because both were evidently written from
the same spec pass. The risk is downstream, not in what's checked in — see below.

## 2. Wire parity: GigIdentity.swift vs GigIdentity.kt — REAL DIVERGENCE RISK

- `GossipEnvelope.field` 12/signature is defined by the spec (line 22) as
  "base64 DER ECDSA signature", over the temporary per-Gig key from `GigIdentity`.
- Android's `GigIdentity.kt:31-33` signs with `Signature.getInstance("SHA256withECDSA")`,
  which is DER by construction (ASN.1 SEQUENCE of r,s) — correct, matches spec and matches
  `ContactChallenge.kt`'s `verifyChallenge`.
- **iOS's `GigIdentity.swift` has no equivalent signing method at all.** It only exposes
  `key(scope:) -> P256.Signing.PrivateKey?` (raw key) and `attribution(scope:author:)` (which
  signs with `ContactIdentity.sign`, the *durable* Card key, not the Gig temp key — correct for
  that call, see below). There is no `sign(bytes:) -> Data?` for the temporary Gig key that
  `GossipEnvelope.signed(_:)` (PublicGossip.swift:28) expects as its closure argument.
  Whoever wires this next will call `P256.Signing.PrivateKey.signature(for:)`, which returns
  `ECDSASignature`. **`.rawRepresentation` on that type is 64 raw bytes (r||s), not DER** —
  only `.derRepresentation` is DER. If the closure passed to `.signed()` uses
  `.rawRepresentation` (the more "obvious" CryptoKit call, and the one already used
  incorrectly-adjacent in `GigIdentity.key(scope:)` for *key storage*, which correctly uses raw
  bytes there since that's for keychain persistence, not wire signature), the iOS-authored
  envelope's signature will fail `verifyChallenge` (which expects DER, see
  `ContactIdentity.swift:45` and Android's `Signature`-based decode) on every other platform's
  receiver, and locally too once decoded through the same DER-parsing path. This is a **silent,
  100%-reproducible cross-platform break**: iOS-authored `log`/`witness` facts would never
  validate anywhere, satisfied by `envelope.valid()` failing at the `verifyChallenge` guard
  (PublicGossip.swift:46) — the fact is just silently dropped (`receive` returns false), no
  error surfaced anywhere in the current code.
  - **Concrete instruction for whoever finishes this:** add
    `static func sign(scope: String, _ data: Data) -> Data?` to `GigIdentity.swift` that does
    `key(scope:)?.signature(for: data).derRepresentation`, not `.rawRepresentation`.
- The **durable/masked-attribution path** (`GigIdentity.attribution`, spec lines 10-17) is
  correctly matched to Android: both derive `recognitionKey` as SHA-256 of
  `gossip-mask/2\n<durable-pubkey>\n<scope>` (iOS `GigIdentity.swift:39-41` vs Android
  `GigIdentity.kt:45-46` — identical string, identical digest), both AES-256-GCM with a 12-byte
  nonce prefix (iOS via `AES.GCM.SealedBox.combined`, Android via `cipher.iv + ciphertext`),
  and `recognizeGossip` on both sides slices/parses the same nonce(12)+ciphertext+tag(16)
  layout. `combined` for CryptoKit's `AES.GCM` is documented as nonce || ciphertext || tag,
  matching Android's explicit `iv + doFinal(...)` (GCM tag is appended to `doFinal` output by
  the JCE provider) — **this pairing is correct and is the one place the two implementations
  actually agree end-to-end with real crypto**, unlike the temp-key signature above which is
  simply unimplemented on iOS.
- Minor asymmetry, not a bug: Android's `GigIdentity` is a class instantiated with `scope`;
  iOS's is a `static` enum keyed by `scope` string per call. Fine, no wire effect.
- `GigIdentity.swift.attribution` never persists/derives its own public key for use as
  `GossipEnvelope.author` — that's on the caller (unwritten) to fetch via
  `GigIdentity.key(scope:)?.publicKey.x963Representation` or similar and base64-SPKI it to match
  Android's `publicKey()` (`gossipBase64(store().getCertificate(alias).publicKey.encoded)`,
  which is SPKI DER from a self-signed cert). **iOS has no method producing this SPKI-encoded
  public key string at all** — `P256.Signing.PublicKey` doesn't have a plain `.derRepresentation`
  SPKI encoder built in without going through the same `SecKeyCopyExternalRepresentation` +
  X.509-wrap dance `ContactIdentity.swift:36-45` already does for the Card key. This needs to be
  replicated for the Gig temp key or the `author` field's base64 SPKI will not parse against
  Android's decoder (`decodePublicKey` in ContactChallenge.kt presumably expects
  `X509EncodedKeySpec`). This is the same shape of gap as the missing `sign` method — the
  temp-key half of `GigIdentity.swift` is incomplete, only the durable-key attribution half is
  done.

## 3. Spec conformance (docs/gossip-public-wire.md), clause by clause

- **L10-12 (per-Gig key + scope, durable key signs identity binding):** PARTIAL. Scope/binding
  string logic present and correct in `GigIdentity.swift:36-38` (identical to Kotlin). But no
  public-key export and no temp-key `sign` method (see #2) — the "signs" half of "the durable
  key signs ... once per binding" is implemented, but nothing yet produces the *envelope*
  signature from the temp key that the durable signature is embedded to prove attribution over.
- **L13-17 (masking AES-GCM, sealed = nonce/ciphertext/tag, durable key never on wire):**
  IMPLEMENTED (`GigIdentity.swift:28-34`, `recognizeGossip:42-48`). Matches spec and Android.
- **L19-24 (Envelope 12 tab fields, canonical signed bytes, hash):** IMPLEMENTED
  (`PublicGossip.swift:22-27,57`). Field order matches spec order exactly.
- **L26-29 (kinds, witness embeds signed request, one-hop request/receipt):**
  PARTIAL. `valid()` enforces kind whitelist and the witness-embeds-valid-request rule
  (PublicGossip.swift:49-53). But "Requests and receipts are one-hop only" and "A received
  receipt affects neighbour priority, never the referenced Envelope's outbox" are not enforced
  or implemented anywhere in this file — `receive()` treats `request`/`receipt` identically to
  other kinds except for the `held` gate (line 112) and the `useful` map (line 119); there is no
  one-hop enforcement (nothing prevents a `request`/`receipt` from being re-offered to a third
  peer) and no "neighbour priority" concept exists at all in `PublicGossipState`. MISSING/PARTIAL
  — needs the transport layer that doesn't exist yet, or a field/rule this struct should enforce
  and currently doesn't.
- **L31-35 (Pass format, relay never becomes author, size limits, GATT chunk framing):**
  IMPLEMENTED for the Pass/Envelope encode/decode and byte ceilings
  (`encodePublicGossipPass`/`gossipMaxWireBytes` reused from `GossipWire.swift`). The GATT
  chunk-and-empty-terminator framing itself is **not present in `PublicGossip.swift`** — it's a
  transport-layer detail that belongs in a v2-aware `GossipWire.swift`/`GossipChannel.swift`,
  neither of which reference `PublicGossip` at all yet. MISSING (transport wiring, see #4).
- **L37-41 (outbox first/second-copy, Carry window, seen-until-expiry, durable-survives):**
  IMPLEMENTED in `PublicGossipState.receive/prune` (PublicGossip.swift:99-121) — matches
  spec description closely, including the "first accepted copy may enter the outbox, a second
  valid copy removes it" (`seen[id] != nil` -> `held.removeValue`, line 108).
- **L43-46 (resource defaults, capacity exhaustion drops incoming, usefulness 2min binary):**
  PARTIAL. Envelope/KB caps (128/128000), 8192 seen IDs are correct constants. "per-peer
  cooldown and bounded BLE sessions" and "capacity exhaustion drops incoming transport work
  rather than evicting seen IDs" are transport-layer guarantees not implementable purely in
  this struct — no evidence yet that the drop-vs-evict distinction is honored by whatever calls
  `receive()`, since nothing calls it yet. "Usefulness lasts two minutes, binary" is implemented
  (`useful[id] = min(expiresAt, now+120000)`) but nothing reads `useful` anywhere in this file or
  in `offer()` — so "affects neighbour priority" from L29 is genuinely unimplemented, not merely
  unwired: there is no field on `PublicHeld`/`offer()` that would let `useful` affect priority
  even once a transport exists.

## 4. Correctness bugs

- No functional bugs found within `PublicGossip.swift` in isolation (it's well-covered by
  `PublicGossipTests.swift` per the commit list). The bugs are all at the boundary of what's
  missing (see #2's DER/rawRepresentation trap) rather than in code that runs today.
- `decodePublicEnvelope` (PublicGossip.swift:58-67) does not bound-check `f[8]` (`line`)
  against the `-1...4096` range at decode time — only `valid()` does. Not a bug per se (decode
  intentionally admits malformed-but-parseable input for `valid()` to reject), but worth noting
  that `GossipEnvelope(id: f[0], ...)` is constructed and handed around before `valid()` is
  called by any caller — if any future caller reads fields off a decoded-but-not-yet-validated
  envelope (e.g., logs `envelope.line` for diagnostics) before validating, that's an
  attacker-controlled unvalidated value ("4294967295" -> Int-parse-clamped only by Int64/Int
  overflow behaviour, not the spec's line ceiling).

## 5. What wiring remains

- **`GossipWire.swift`** is entirely v1 (`gossip-challenge/1`, `gossip-pass/1`,
  `GossipCheckIn`) — the Contact-only transport spec says #408 "supersedes". Nothing in it
  imports or references `GossipEnvelope`/`PublicGossipPass`. A v2 transport (new characteristic
  UUIDs or a version field bump on `gossipChallengeV1`/`gossipPassV1`, new
  `encode/decodeGossipChallenge`-equivalents for the public-fact Pass) does not exist. This is
  the single largest remaining task — literally nothing calls `encodePublicGossipPass` or
  `PublicGossipState.receive` from any transport code.
- **Protocol version bump**: belongs in `GossipWire.swift`'s `gossipChallengeV1`/`gossipPassV1`
  constants (rename to `.../2` or add a parallel `.../2` set) plus wherever `GossipChannel.swift`
  negotiates/dispatches on that header string (`GossipChannel.swift:109` area) — needs a
  version-dispatch so an old-protocol peer and a new one don't silently misparse each other's
  Pass. Nothing in the current diff touches `GossipChannel.swift` at all.
- **Persistence**: `PublicGossipState` is `Codable` (facts/seen/held/blocked/recognition/useful
  are all `Codable`-clean primitives/structs) — no blocking issue there. It needs to be added to
  `TimelineCache`/`TimelineStore.swift`'s persisted state (`TimelineStore.swift` currently has no
  reference to `PublicGossipState`, `GossipEnvelope`, or anything in `Data/Gossip/`) and to
  `TimelineStore.load()/save()`'s codec (see `TimelineCache: Codable` at TimelineStore.swift:445
  and the actor's `load()/save()` at 653/663) the same way `StoredLog`/`StoredAttendance` are
  handled today.
- **Projection to SwiftUI**: `TimelineStore.project(gigIds:)`-equivalent call
  (`PublicGossipState.project(gigIds:)` exists, PublicGossip.swift:137-147) is never invoked by
  anything; there's no `@Published`/view-facing surface exposing projected facts to a SwiftUI
  screen. The existing `StoredLog`/timeline views that show gig activity would need a new data
  source merging `PublicGossipState.project()` output alongside local logs, which doesn't exist.
- **iOS-specific transport constraints the spec's 40,000-byte/64-envelope Pass assumes away:**
  - `gossipMaxWireBytes = 40_000` is shared from `GossipWire.swift` and already accounts for the
    v1 transport's GATT chunk-and-terminator framing description in that file's header comment
    — so the *framing* strategy (append-at-offset-0, zero-length terminator) is documented and
    presumably reusable, but nothing in `PublicGossip.swift` or any v2 file implements chunking
    for the larger Pass records (12-field, longer than v1's shorter `GossipCheckIn` records) over
    a background-mode central/peripheral session.
  - **Background execution**: CoreBluetooth central scanning/peripheral advertising in the
    background is heavily throttled by iOS (slower scan intervals, no local name, overflow-area
    service UUIDs per `GossipWire.swift`'s own comments) — a 40 KB Pass taking multiple GATT
    round trips at background scan cadence could easily exceed the ~10s iOS gives a background
    BLE central before suspending it again. Nothing in the current diff addresses retry/resume
    of a partially-sent 40 KB Pass across a background suspend.
  - **512-byte attribute ceiling**: spec L34-35 explicitly assumes the existing chunk framing
    handles this, consistent with `GossipWire.swift`'s documented approach — not a new problem,
    but the "GATT writes retain the existing... framing" sentence in the spec is a claim that
    hasn't been verified against actual v2 record sizes (an 8,192-byte Envelope limit is 16x the
    512-byte ceiling per record, meaning a single Envelope alone needs ~16 chunks minimum, not
    accounted for in any sizing comment left in the new files).

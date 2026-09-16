# Handoff — receipts and usefulness, shippable v1

For whoever picks up #444/#445 next. Written 2026-09-16 on `gossip-ble-diagnostics`.

Line spans are hints, not addresses. Find the symbol by name; the lines drift.
`A` = `android/app/src/main/java/io/github/magnusencoded/stationtostation`,
`I` = `ios/StationToStation`.

## The decision that changed the shape

#455 gates usefulness work (#444) behind the locked-iPhone viability experiment
(#446 priority 1). The user lifted that gate on 2026-09-16, having deferred iPhone
hardware indefinitely on 2026-09-15:

> "We may well have to tune parameters like that when we have real world usage.
> All those 3 reasons seem arbitrary to me. I want the shape of the work now to be
> something where we can get to a crappy version of the feature but one that
> actually does something that we can ship. Even if it has some things missing."

A gate on an indefinitely deferred item is a permanent block, and the three stated
reasons — sequencing, unmeasured parameters, an unresolved neighbour handle — do
not survive contact with the code. **Ship a partial version. Tune from real usage.**

Nothing here settles a parameter from simulation. #445's rule stands: `sim/` is for
comparing policies, never for accepting a value. The values below stay provisional
and say so in the docs.

## What already exists — read this before planning anything

The receiving half of receipts is built, on both platforms. This is the reason v1
is small.

| Piece | Android | iOS |
| ----- | ------- | --- |
| Neighbour handle is already a parameter | `receive(envelope, from, now, local)` `PublicGossip.kt:194` | `receive(_:from:now:local:)` `PublicGossip.swift:158` |
| Delivering neighbour recorded per **Fact** | `PublicHeld(..., mutableSetOf(from))` `PublicGossip.kt:~207` | `PublicHeld(..., delivered: [from])` `PublicGossip.swift:169` |
| Credit map | `val useful` `PublicGossip.kt:170` | `var useful` `PublicGossip.swift:133` |
| Receipt consumed, 2-minute decay | `useful[envelope.text] = minOf(expiresAt, now + 120000)` in `receive` | same, in `receive` |
| Credit pruned on expiry | `prune` `PublicGossip.kt:189` | `prune` `PublicGossip.swift:152` |
| Per-peer dedup of offers | `offer(peer, ...)` / `delivered(peer, ids)` `PublicGossip.kt:213,223` | `offer(to:...)` / `delivered(to:ids:)` `PublicGossip.swift:178,188` |

Two consequences:

- **Story 36 already has its data.** "Only the neighbour that delivered a Fact
  directly gets credit" needs the delivering neighbour per Fact. `held.delivered`
  is seeded with exactly that and nothing else.
- **The neighbour-handle research question does not block this work.** The core is
  handle-agnostic: it stores whatever string the transport passes as `from`, and
  matches it against whatever `offer` is given as `peer`. The open question is
  handle *quality* across meetings and platforms, not plumbing. v1 uses the handle
  the transports already pass.

## What is actually missing

1. **No emitter.** Nothing anywhere authors a `receipt` Envelope. `CONTEXT.md`
   already says so. A sink with no source.
2. **`offer` ignores `useful`.** Both platforms sort held Facts by `createdAt` then
   `id`, descending. Usefulness is never read.
3. **No random tie-break.** `docs/gossip-public-wire.md` claims "random ties";
   it is not implemented.

## The work

### A. The emitter — stories 35, 36, 37

A pure function beside `recognizeContacts` that returns a receipt **Fact**, or
nothing, for a newly admitted Fact.

- It fires when the Fact was recognised as a **Contact**'s *at receive time*.
- Its `text` is the delivering neighbour, read from `held.delivered`.
- Story 37 falls out of the design rather than needing a rule: attribution learned
  later, through `recognizeContacts` after an Exchange, is a different code path and
  authors nothing. Do not add a timestamp comparison to enforce this; if the pure
  function can only be reached from the receive path, lateness is structurally
  impossible. Test it anyway.

Then wire it: receive → recognised → author receipt → it rides the next **Pass**
back to that neighbour. A receipt is one-hop by kind, which `receive` already
enforces (`from != envelope.author` is refused for `request` and `receipt`).

### B. Ranking — stories 38, 39

Prefer neighbours holding live credit in `useful`; break ties randomly; never
exclude a neighbour without credit.

**This does not belong in `offer`.** `offer(peer, ...)` takes one peer and returns
Facts, so usefulness cannot rank anything inside it. #455's code map annotates
`offer` as the place that "must start ranking by usefulness" — read that as a
pointer at the wrong function. Stories 38 and 39 are about *which neighbour to push
to*, which on Android lives in `GossipService.startRadio` (`GossipService.kt:140`,
peer selection around the `publicPassFor` call at `:192`) reading `GossipPresence`
(`GossipPresence.kt:29`). Confirm the reading with the issue author before building;
if they meant Fact ordering instead, they should say what it would mean.

The tie-break needs a seam for a seed, or the test cannot assert it. Inject the RNG.

### C. Safety rails — story 40

Receipts must never retire an Envelope and never bypass the **Storm gate**.

Reading `receive` on both platforms, this is *already* true: a `receipt` is excluded
from `facts`, and excluded from `held` unless local. It has no tests. Pin it now,
because it is the property that makes shipping a half-built version safe.

### D. Parameters — stories 41, 42

Keep the decay its own constant, separate from the **Carry** window
(`PUBLIC_CARRY_MS` / `publicCarryMs`, 15 min) and from grace. Today the 2-minute
decay is an inline `120000` inside `receive` on both platforms — name it.

Mark every value provisional in the docs, citing its evidence, and state the policy:
tune from real usage. Do not quote `sim/SWEEPS.md` as measurement.

## Definition of done

- Stories 35–41 have seam-1 tests on Android with a matching case on iOS. Every case
  on one platform needs its twin; #455 is explicit about this.
- A receipt is authored on prompt recognition, names the delivering neighbour, and
  reaches only that neighbour.
- Neighbour selection prefers credit, ties break randomly under an injected seed,
  and an uncredited neighbour is still offered.
- Receipts provably never enter `facts` or `held`, never retire an Envelope, never
  touch the Storm gate.
- The decay is a named constant, separate from carry and grace, documented as
  provisional.
- CI green on both platforms.

Existing seam-1 tests to extend rather than replace: `PublicGossipTest.kt` (14),
`GossipLifecycleTest.kt` (8), `GossipPolicyTest.kt` (11); `PublicGossipTests.swift`,
`GossipLifecycleTests.swift`.

## What v1 ships knowingly missing

Say these in the ADR rather than discovering them later.

- **Handle stability is unproven.** Credit is keyed by whatever the transport passes.
  On iOS, where addresses rotate, credit may not accumulate across meetings. The
  failure mode is no preference — today's behaviour — not breakage, because story 39
  forbids exclusion. This is the single most likely thing to make v1 a no-op in the
  field, and the first thing to measure.
- **No measured parameter values.** Carry, decay and grace stay provisional.
- **No locked-iPhone proof.** #446 stays deferred. Receipts shipping does not
  establish that an iPhone relays anything.

## Environment — this cost a full session, do not rediscover it

- **This PC cannot build the app.** No JDK, no Android SDK, no container runtime.
  `android/.github/workflows/android.yml` says so in a comment. Every change is a
  push and a CI round trip of about five minutes; take APKs from the run's
  `app-debug` and `app-debug-androidTest` artifacts with `gh run download`.
  This favours seam-1 and seam-2 work, which is what this slice is.
- **Pixel 7 Pro** reachable over network adb as `android_ixpfgrt2.local:39573`.
  Grant `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` and
  `ACCESS_FINE_LOCATION` to `...stationtostation.debug` after each install.
- **pinet** reachable with `~/.ssh/id_ed25519_pinet` (regenerated 2026-09-16; use
  the hostname, not `10.42.0.1`, which fails host-key verification). Its Bluetooth
  comes up unconfigured: the controller boots with the default address
  `43:45:C0:00:1F:AC`, so the kernel marks it unconfigured, BlueZ cannot see it and
  `hciconfig up` fails. Fix without rebooting:
  `sudo btmgmt --index 0 public-addr B8:27:EB:D4:E1:A6`. This may not survive a
  reboot; a systemd unit would settle it and has not been written. **Never touch
  pinet's networking — it is the PC's route to the internet.**
- The gossip peripheral also runs on this laptop unprivileged, out of a venv built
  with `--system-site-packages` plus `cryptography` and `dbus-python`. Useful for
  discovery work; it never accepted a connection, so do not trust it as a peer.

## Corrections to #455 itself

The issue is stale in ways that cost planning time:

- **"Local branches still to review and land" is wrong.** `prep/log-merge-no-drop`
  landed on this branch as `fcb09bf` and `5dd379b`. No `prep/*` branch exists.
- **Stories 22 and 23 look done.** The no-drop rule is wired into production on both
  platforms: `unionLog` calls `StoredLog.absorbing` (`TimelineStore.kt:635`, used by
  `merging`, `logs()` and `Handover.kt:403`; iOS via `HandoverPlan.swift:321`).
  Verify, then close them.
- The publication question is settled (each committed **Log** line publishes) and
  iOS adoption parity landed in `9bca267`.

## Parked, with a diagnosis

The Android central-send acceptance (`centralSendsOwnCheckInRequest`) does **not**
pass. Against the real Pi the phone now connects, negotiates a 517-byte MTU, reads
the challenge, resolves the peer and builds a 648-byte **Pass** in 3 chunks — then
the Pi refuses the first chunk with status 13, `GATT_INVALID_ATTRIBUTE_LENGTH`.
Whether the phone over-sends or the peripheral under-declares its maximum is not
established. This is story 49's territory. See the entry above in
`docs/gossip-v2-next-pass.md` for the full run.

Two commits came out of that attempt and are worth keeping regardless: `9949fe0`
takes the scan off air during a push, `cec983d` puts the reason a push gave up into
the log. Note `cec983d` has a gap — the Pass-refusal path still records no reason.

---

## Done, 2026-09-16 — `355eee7`, CI green on both platforms

Sections A, B, C and D above are built. ADR-0022 is the record; read it rather than
re-deriving. Three things departed from the plan above, all deliberate:

1. **`receive` credited the wrong field, and the plan above repeats the mistake.** The
   shipped half took `useful[envelope.text]` unconditionally. With an emitter in place
   that means the *recipient* of a receipt credits its own handle, and a receipt reaching
   anyone else writes a stranger in and leaks who this phone stands near. Credit now reads
   `text` only for a receipt this device authored, and `from` — the proved handle — for one
   off the wire. This is the single correction worth carrying forward if any of this is
   revisited.
2. **A receipt is addressed.** `offer` withholds it from every peer but the one it names,
   and `passBatch` keeps it only on a **Pass** signed as its author. Neither was in the plan;
   without them the leak above is policy rather than structure.
3. **Ranking binds on Android only.** The reading in section B was right — it is peer
   selection, not `offer` — but Android needed a gathering window before there was a set to
   prefer within, and iOS has no scarce connection slot, so the rule sits in
   `GossipBudget.swift` with its twin test and no caller.

### Still open from this document

- **Stories 22 and 23 are not verified or closed.** The claim above that they look done was
  not checked here.
- The `#455` corrections above were not written back to the issue.
- Story 49 (the Pi's `GATT_INVALID_ATTRIBUTE_LENGTH`) is untouched, and `cec983d`'s gap —
  the Pass-refusal path recording no reason — is still there.
- Handle stability is the thing to measure first. See ADR-0022's consequences.

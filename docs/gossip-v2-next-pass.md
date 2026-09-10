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

## Verifying on the Pixel

A Pixel 7 Pro is attached over **wireless** adb at `192.168.1.216:39851`, Android 17
/ API 37, with both `io.github.magnusencoded.stationtostation` and its `.debug`
variant installed — the debug build is `1.8.0.860` from 2026-09-09 15:48.

**Bluetooth is on and every runtime permission the radio needs is already granted**
on the debug variant: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`,
`ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`. That matters more than it looks,
because an unattended agent cannot tap a permission dialog. If v2 adds a
permission, grant it from the shell — `adb shell pm grant <pkg> <permission>` —
rather than installing and waiting for a prompt nobody will answer.

### There is a second BLE radio, and it is not a phone

Gossip needs two peers. Neither of the obvious candidates is available — but a
third one is, and it changes what is testable tonight.

Not available:

- no iPhone attached — `idevice_id -l` returns `Unable to retrieve device list`
- **this host has no Bluetooth hardware at all** — `rfkill list` is empty,
  `bluetoothctl list` shows no controller, no USB Bluetooth device

Available, and verified working at 02:30 on 2026-09-10:

- **`pi@pinet.local`** (Raspberry Pi 3B+, BlueZ 5.66, controller
  `B8:27:EB:7E:4B:0D`, powered) is in BLE range of the Pixel and **already sees the
  gossip advertisement**. Log in with `ssh -i ~/.ssh/id_ed25519_pinet pi@pinet.local`
  — the key is on this box; the default key name is not, so the `-i` is required.

`bleak` is installed there (`pip3 install --break-system-packages bleak`), and two
scripts are left in `/home/pi/`:

```sh
ssh -i ~/.ssh/id_ed25519_pinet pi@pinet.local 'python3 /home/pi/gossip-scan.py 25'
ssh -i ~/.ssh/id_ed25519_pinet pi@pinet.local 'python3 /home/pi/gossip-connect.py'
```

`gossip-scan.py` filters adverts by the two service UUIDs from `GossipRadio.kt:73`
and `BleProbe.kt:47` and prints address, RSSI and manufacturer data.
`gossip-connect.py` finds a gossip advertiser, connects, and looks for the gossip
service in its GATT database, retrying up to four times. What the two actually
returned:

```
GOSSIP 43:99:60:54:F3:E4 rssi=-67 mfg={65535: '12b4c67c7606cd72'}
GOSSIP 58:10:05:8A:A2:59 rssi=-63 mfg={65535: '12b4c67c7606cd72'}
… 7 addresses in 25 seconds, all the same manufacturer payload

attempt 1: connecting to 7B:42:C3:3E:50:76
  connected, mtu 23
  GOSSIP SERVICE FOUND
    char 7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7723 ['write']
    char 7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7722 ['read']
```

Read that carefully, because it tells you several things at once. The service UUID
is in the advertisement, not the scan response, exactly as `GossipRadio.kt:381-384`
builds it. The token rides manufacturer data under company ID `0xFFFF`
(`TEST_COMPANY_ID`), as `:389-391` intends. The seven addresses are **one phone**:
Android rotates its BLE address, and the constant token across all seven is what
makes them recognisable as the same advertiser — the privacy property the design
relies on, observed from outside for the first time. And a stranger with no Contact
and no app can reach both characteristics: pass at `…7723` write-only, challenge at
`…7722` read-only, matching `GossipRadio.kt:76` and `:79`.

`-63 dBm` is a comfortable margin, so range is not the constraint. Two caveats that
will cost you time if you meet them cold:

- **Discovery is reliable; connecting is not, and that may be telling you
  something.** Scanning found the phone on every single attempt. Connecting
  succeeded twice and then failed four times in a row with `TimeoutError`, while
  scanning from the same host in the same minute kept working and the phone kept
  logging `advertising a token for one contact`. So the phone was there and
  advertising throughout — the connect specifically is what failed.

  The likely cause is in our own code. `GOSSIP_ADVERTISE_SLOT` is **four seconds**
  (`GossipToken.kt:295`), and `rotate()` stops and restarts the advertiser on that
  cadence to show the next Contact's token (`GossipRadio.kt:362-402`). Android
  regenerates its resolvable private address when the advertiser restarts, which is
  why one phone showed up as seven addresses in twenty-five seconds. A central that
  discovers an address therefore has **at most four seconds, and on average two**,
  to complete a connection before the address it holds is gone.

  Do not file that as a test-harness annoyance and move on. `GOSSIP_PUSH_TIMEOUT_MS`
  is 20 s (`GossipRadio.kt:92`), which budgets generously for the whole push but
  cannot help if the *connect* has to land inside a two-second residual window. It
  is worth establishing whether Android's own scan-then-`connectGatt` path is
  subject to the same race — it may not be, since it can connect by resolved
  identity rather than by the address it happened to observe — but "it works
  between two Androids" would be a happy accident of platform behaviour, not a
  property the transport design has earned. If it is real, it is a v2 transport bug
  that predates your work and is worth fixing while the wire is still free to
  change: the fix is to keep the advertiser up across token changes, or to widen the
  slot, not to retry harder on the far side.
- **That `mtu 23` is bleak's default, not a negotiated value** — it warns as much.
  Call `_acquire_mtu()` before believing any MTU number, and do not conclude
  anything about chunking from 23 until you have.

### What that buys you, and what it does not

It is not a second copy of the app, so it cannot complete a v2 Pass on its own. But
it is a **real, scriptable, third-party GATT peer**, and that covers most of what a
second phone would have proved:

- that the advert is well-formed and the phone discoverable — **done, above**
- that a stranger can connect and reach the gossip characteristics — **done**
- **that the chunk-and-empty-terminator framing survives a real ATT ceiling on a
  real link** — the most valuable thing left here, because that framing is the part
  of the transport a JVM test cannot exercise and the part most likely to be wrong
- that a malformed, oversized or truncated Pass is rejected the way the spec says,
  which is *easier* from a script than from a second phone, because you can send
  exactly the bytes you want to send

Writing a synthetic Pass to `…7723` from the Pi and watching the Pixel accept or
reject it in logcat is a genuine end-to-end test of the receive path, and it is
reachable tonight. Authoring, signing, relaying onward and projecting still have no
second endpoint — say so in the PR rather than implying the whole path was
exercised.

**Do not restart networking on pinet.** It is this machine's only route to the
internet (`CachyOs --eth--> pinet --wifi--> router`). Bluetooth work is unrelated
to that and safe; `systemctl restart` of anything network-shaped is not. The Pi is
a 3B+ with 856 MB RAM, so keep scripts small and do not build anything there.

### What one Pixel does prove, and it is the thing CI cannot

`GigIdentity` generates and signs with a P-256 key in the **Android Keystore**,
which does not exist in a JVM unit test. CI has therefore never executed that code
— the green Android run proves it compiles, not that it can mint a key or produce a
signature that `verifyChallenge` accepts. The Pixel is the only place that can be
established before this ships, and it is the highest-value use of the device.

**But there is no instrumented test to put it in: `android/app/src/androidTest/`
does not exist.** This repo has no on-device test source set at all, so
`connectedDebugAndroidTest` is not a thing you can run — don't try. Two ways to
close that, and they are not equivalent:

- **Create the source set** and write the first instrumented test. It is standard
  Gradle wiring, it is the durable answer, and every later Keystore or radio change
  gets to reuse it. It is also new infrastructure for this project, which makes it a
  judgement call rather than a chore — if you do it, keep it minimal and say why in
  the PR.
- **Exercise it from the running app and read logcat.** Cheaper, needs no new
  infrastructure, proves the same single fact tonight, and leaves nothing behind.

Prefer the first if the pass is going well, the second if it is not. Either way,
*something* must execute that Keystore path before the PR, because nothing ever has.

The real log tags are **`GossipRadio`** and **`GossipService`** — the on-device docs
name no gossip-specific filter, so use these:

```sh
adb logcat -c && adb logcat -s GossipRadio:V GossipService:V
```

Also reachable on one phone: install and launch without crashing, the encode/decode
round-trip against the shared fixture via the existing JVM unit tests, the radio
actually starting to advertise and scan, and the push-failure diagnostics this
branch already added.

### Verified command sequence

Run and confirmed working at 02:15 on 2026-09-10, against this branch. Note the
timings — a build-install-launch cycle is **over six minutes**, so budget for it
rather than looping on it.

```sh
cd /home/dizzi90/Documents/station-to-station/android
./gradlew :app:assembleDebug     # BUILD SUCCESSFUL in 4m34s
./gradlew :app:installDebug      # BUILD SUCCESSFUL in 1m49s, to 'Pixel 7 Pro - 17'
adb -s 192.168.1.216:39851 shell am start \
  -n io.github.magnusencoded.stationtostation.debug/io.github.magnusencoded.stationtostation.MainActivity
```

Launch was clean: `Displayed …MainActivity +1s155ms`, `GossipService` foreground
service started, BLE GATT app registered, no `FATAL` or `AndroidRuntime`.
`local.properties` sets `sdk.dir=/opt/android-sdk` and that is what Gradle used —
the mismatch with `ANDROID_HOME` is a non-issue, ignore it.

### If adb has dropped

Wireless adb here is a **paired wireless-debugging session** on port 39851, not a
classic `adb tcpip 5555`. The launcher reconnects before handing over; mid-pass:

```sh
adb connect 192.168.1.216:39851 && adb devices -l
```

If that fails the phone needs re-pairing by hand, which is a human task — note it
and carry on with what does not need the device rather than burning the pass on it.

**The link is more robust tonight than it looks.** The phone is on a wireless
charger at 100%, and a charging device does not enter Doze — which is the main
thing that would otherwise kill the connection while you work.

One thing deliberately **not** changed: `stay_on_while_plugged_in` is `0`, so the
screen locks after its 30-minute timeout. Leave it that way. `GossipService` is a
foreground service and ADR-0019 exists precisely so this runs with nobody looking
at the phone — forcing the screen awake would make the observation less
representative, not more.

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

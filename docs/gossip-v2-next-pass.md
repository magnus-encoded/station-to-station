# Handoff: finishing public gossip v2 (#408)

**Written** 2026-09-10, ~02:00, by the session that took over when the previous
implementation session hit its 5-hour quota mid-flight.
**Branch** `gossip-ble-diagnostics`. **Normative spec** [`gossip-public-wire.md`](gossip-public-wire.md).
**Simulator brief** [`../handoff.md`](../handoff.md).

**Current checkpoint:** see the progress sections at the end. The original survey
below describes `19abda1`, not today's branch. V2 radio transport now runs on both
platforms; Android public state now has a shared persisted owner.

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

`bleak` is installed there (`pip3 install --break-system-packages bleak`), and four
scripts are left in `/home/pi/`, each about thirty lines and meant to be edited:

```sh
ssh -i ~/.ssh/id_ed25519_pinet pi@pinet.local 'python3 /home/pi/gossip-scan.py 25'
ssh -i ~/.ssh/id_ed25519_pinet pi@pinet.local 'python3 /home/pi/gossip-connect.py'
ssh -i ~/.ssh/id_ed25519_pinet pi@pinet.local 'python3 /home/pi/gossip-race.py'
ssh -i ~/.ssh/id_ed25519_pinet pi@pinet.local 'python3 /home/pi/gossip-challenge.py'
```

`gossip-scan.py` filters adverts by the two service UUIDs from `GossipRadio.kt:73`
and `BleProbe.kt:47` and prints address, RSSI and manufacturer data.
`gossip-connect.py` finds a gossip advertiser, connects, and looks for the gossip
service in its GATT database, retrying up to four times. `gossip-race.py` and
`gossip-challenge.py` are the connect-timing and challenge-read experiments below.
Writing a Pass to `…7723` is the obvious fifth, and is left for you because its
bytes depend on the wire format you are about to change. What the first two
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

  The first hypothesis was a stale address, and it is worth following because the
  *mechanism* it identifies is real even though the conclusion turned out to be
  wrong. `GOSSIP_ADVERTISE_SLOT` is **four seconds**
  (`GossipToken.kt:295`), and `rotate()` stops and restarts the advertiser on that
  cadence to show the next Contact's token (`GossipRadio.kt:362-402`). Android
  regenerates its resolvable private address when the advertiser restarts, which is
  why one phone showed up as seven addresses in twenty-five seconds. A central that
  discovers an address therefore has **at most four seconds, and on average two**,
  to complete a connection before the address it holds is gone. That much is
  certain. What does not follow — and what the next experiment disproves — is that
  the staleness is what breaks the connection.

  **The obvious explanation has been tested and does not hold.** A third script,
  `/home/pi/gossip-race.py`, connects from inside the detection callback instead of
  scanning to completion first, closing the window to almost nothing. Six trials:

```
trial 1: OK   scan-stop 0.02s  connect+read 4.23s  challenge 99B
trial 2: FAIL scan-stop 0.03s  gave up 12.05s  TimeoutError
trial 3: FAIL scan-stop 0.01s  gave up 12.02s  TimeoutError
trial 4: OK   scan-stop 0.05s  connect+read 4.01s  challenge 99B
trial 5: FAIL scan-stop 0.02s  gave up 12.03s  TimeoutError
trial 6: FAIL scan-stop 0.01s  gave up 12.02s  TimeoutError
=== 2/6 connected ===
```

  So "the central was too slow" is out: the gap between seeing the advert and
  starting the connection was twenty to fifty *milliseconds*, and it still failed
  two thirds of the time. Note what the successes cost — **4.01 s and 4.23 s**,
  within a rounding error of one `GOSSIP_ADVERTISE_SLOT`. That is the shape of a
  connection that only completes once the advertiser has cycled, not one that races
  a rotation and wins.

  The reading that fits is that `rotate()`'s stop/start leaves a dead window in
  which the phone is not connectable at all, and a connection request that arrives
  inside it is simply lost. Four times a minute, for a night, on a transport whose
  entire purpose is opportunistic contact between passers-by who may be in range
  for only a few seconds.

  Do not file that as a test-harness annoyance and move on. `GOSSIP_PUSH_TIMEOUT_MS`
  is 20 s (`GossipRadio.kt:92`), which budgets generously for the whole push but
  cannot help if the *connect* has to land inside a two-second residual window. It
  is still worth establishing whether Android's own scan-then-`connectGatt` path is
  subject to the same window — it may not be, since it can connect by resolved
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
- that an unauthenticated stranger can **read the challenge**: 99 bytes beginning
  `station-to-station/gossi…`, read from `…7722` by a device that has never paired,
  bonded or exchanged a Card. Presumably intended, since a peer must have the
  challenge to sign it — but it is now an observed fact rather than an assumption,
  and the ADR should say so out loud
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

### The APK on the phone, and the faster way to replace it

The debug variant on the Pixel was installed at **02:13 on 2026-09-10** from this
branch, so it already matches HEAD's code — every commit since is docs-only. The
release variant (`versionCode=470`, last updated 2026-09-07) is the shipped `v1.8.0`
and contains no gossip transport at all; leave it alone, it is useful precisely as a
thing that does not speak v2.

**CI green does not mean the phone is current** — they are independent, and they only
agree here by accident of timing. But you have a shortcut for keeping them in step.
Android CI uploads the debug and measure APKs as artifacts (`android.yml:47-69`),
and `android/app/debug.keystore` is **committed on purpose**, so a CI-built APK is
signed with the same key as your local build:

```kotlin
// Committed debug key so every machine and CI build signs identically,
// letting `adb install -r` update a device without wiping app data.
```

Verified: the keystore's fingerprint `DE:F9:E5:8D…` is the signature of the APK
currently installed. So this works, updates in place, and **keeps app data** —
which matters more than usual here, because the app's data is where `GigIdentity`'s
Android Keystore entry and the Contact the radio advertises for actually live. An
uninstall/reinstall would silently destroy the on-device state you are trying to
prove things about.

```sh
gh run download --repo <this repo> -n app-debug --dir /tmp/apk
adb -s 192.168.1.216:39851 install -r /tmp/apk/app-debug.apk
```

Use it when CI has already built the commit you want. It is **not** a substitute for
`installDebug` while iterating — pushing and waiting for a runner is slower than the
4m34s local build. Its value is at the end of a slice, when you want the phone
running exactly the artifact CI signed off on rather than something from your tree.

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

## Progress after the model restart (2026-09-10)

`82ac167` restored the iOS persistence-key parity assertion; CI passed. `139381a`
fixed a separate data-loss defect: TimelineCache encoded publicGossip but its custom
Swift decoder ignored it. The restart/unrelated-save regression passed iOS CI.

`0e23e9d` adds required shared OpenSSL-signed Envelope/Pass vectors under
`fixtures/gossip/`, asserted byte-for-byte on both platforms. The encoder now owns
one exact byte budget and trims the batch to a fitting prefix; offer no longer
reserves a guessed 512-byte header. Local Android PublicGossip/TimelineStore tests
passed. Both native CI workflows passed for this commit.

The four interrupted production edits were reviewed and withdrawn before fixing CI.
Their diff is preserved locally; the Android edit referenced an undefined batch,
and the proposed usefulness ordering indexed envelope IDs instead of neighbour IDs.
Neither behaviour was landed.

### Remaining integration work — these are verified gaps

- Android authoring and reception share GossipStore; iOS GossipChannel now uses
  GossipLedger for durable public state (`bb46b8a`). TimelineCache.publicGossip is
  still an unused placeholder on both platforms. Projection must use these actual
  stores, not the placeholder.
- Current “check-in” authoring creates a log at line 0 saying “Checked in”. This is
  not the agreed direct request/witness flow and collides with real Log lines.
- Author scopes now use random persisted bindings to the stable local Gig on both
  platforms (`feddf42`, `11c2f78`), resolving either a local or external UI key before
  authoring. Gig merges and former-ID propagation still need integration coverage.
- Successful v2 handoffs persist in the public stores. Receipts, neighbour priority,
  direct-only verification, attribution, blocking, persisted projection and UI remain unwired.
- Pixel Keystore test actually passed: `OK (1 test)`, 0.163 seconds, on the debug
  app installed in place. It checked concurrent first use, reopening the same key,
  distinct scopes, valid DER signing and tamper rejection. Only its test keys were
  removed. `867125c` adds it and CI compilation/artifacts. Android CI passed.
  iOS initially failed because the simulator host lacks Keychain entitlement
  (-34018); `65361cb` probes that precise limitation before skipping the Keychain
  test. Its iOS CI passed. Software-key wire/signature tests still run normally.
- Simulator coverage counts nodes rather than fact/recipient pairs and its receipt
  metric is not a model of routing receipts. The claimed 15-minute “measured default”
  is unsupported. Complete the planned sweeps and correct the spec and ADR to match
  the resulting implementation. Do not treat the earlier progress paragraph as
  evidence that persistence, public check-ins or simulation decisions were finished.

No feature PR has been opened. Do not close #408 while these gaps remain.

### Current transport slice (resumed 07:52)

`2a1838e` replaces radio decoding/sending with v2 on both platforms,
uses signed challenges and temporary nightly relay keys, and removes Android's
Contact-token advertisement rotation. Android allows a no-Contact relay at its
Gig; iOS can likewise start for a known current night. V2 handoff IDs are now
recorded in memory after the empty terminator completes. Android public-state
callback access is serialized. State persistence/application authoring remains the
next separate slice; no claim of full feature completion is warranted.

Local Android compile, APK build and all 22 selected PublicGossip/GossipPolicy tests
passed (17m52s on this host). Both CI workflows passed for `2a1838e`: Android
`34444445691`, iOS `34444445648`. Android also passed for the opt-in device test
commit `c8af2a0` (`34445013383`); that commit did not trigger iOS. A manual Pi
peer lives at `docs/prototypes/gossip_v2_peer.py` and is copied to `/home/pi/`.
It verifies a signed v2 challenge and sends a signed synthetic Fact through 20-byte
ATT writes plus an empty terminator, with separate connection trials and no hidden
retries. Its hardware result is recorded below. The debug APK built from this
transport source is installed in place. At this morning's hour normal lifecycle
correctly leaves the service off (contacts=1, holding=false, gigTonight=false,
alwaysRelay=false). `am start-foreground-service` cannot start its non-exported
component; `run-as ... am` also fails the shell calling-package check. Do not change
exported status or the user's settings to bypass that.

The opt-in `GossipRadioDeviceTest` compiled and its APK is installed in place. It hosts the production
GossipPeripheral directly under instrumentation, waits for six verified Pi Passes,
asserts one retained in-memory Fact and an empty outbox after duplicate reception,
then stops the radio and removes only its test key. Run with `-e manual_ble_peer true`
and `-e class io.github.magnusencoded.stationtostation.GossipRadioDeviceTest`, alongside
`python3 /home/pi/gossip_v2_peer.py 6`. This tests the radio and state-machine receive
path; it does not exercise the GossipService lifecycle or the phone's central/send
path. The service's morning refusal is expected, not a radio defect.

### Real ATT investigation (2026-09-10, 13:00)

The first six v2 Pi trials all connected and verified the signed challenge, but
all failed during writes with BlueZ `UNLIKELY_ERROR`: 9.16, 6.71, 7.28, 6.18,
7.28, 9.29 seconds. No complete Pass reached the receiver. The device assertion
failed after 90 seconds. This is **not** a hardware pass despite green CI.

A seventh, traced trial reproduced it. `btmon` showed the Pi opening EATT
channels, Android refusing them for insufficient authentication, and the Pi
initiating pairing. Pairing failed (no confirmation agent); Android then
disconnected while normal 20-byte ATT writes were still being acknowledged.
There was no ATT Unlikely Error response in that trace: BlueZ reported it after
the remote disconnection. This isolates a different failure from advertisement
rotation, and a controlled plain-ATT comparison is pending. Temporary Pi script
`/tmp/gossip-plain-att.sh` sets BlueZ GATT Channels=1 for six trials and restores
the original config and Bluetooth service via an EXIT trap. No networking changes.
BlueZ documents that setting in its [configuration source](https://github.com/bluez/bluez/blob/5.66/src/main.conf).

The controlled comparison **passed** with the production APK unchanged. Six
plain-ATT trials completed, each writing 1,164 bytes in 20-byte chunks and an
empty terminator:

| Trial | Discovery through verified challenge | Through complete Pass |
| --- | --- | --- |
| 1 | 5.58 s | 11.51 s |
| 2 | 3.78 s | 9.77 s |
| 3 | 7.40 s | 14.16 s |
| 4 | 4.93 s | 11.55 s |
| 5 | 5.13 s | 11.07 s |
| 6 | 3.47 s | 9.41 s |

Pixel instrumentation: **OK (1 test), 81.387 seconds**. Six verified Passes reached
PublicGossipState, one Fact remained, and the duplicate closed the outbox storm
gate. This proves signed challenge/Pass interoperability with an independent
Python signer, real ATT framing above 512 total bytes, and receive-side admission.
It does not prove durable persistence, UI projection, phone central/send, iOS radio,
or malformed-frame rejection over ATT. Six successful connections support the
continuous-advertisement change, but the original v1 measurements did not control
for BlueZ EATT, so they do not prove rotation alone caused the earlier failures.

The Pi config was restored byte-for-byte to its saved original (`#Channels = 3`),
and Bluetooth is active. The temporary script's cleanup could not unlink its
root-owned backup; it was subsequently compared and removed with sudo. Networking
was untouched. For future runs, change its cleanup to `sudo -n rm -f "$backup"`.
The peer harness now reports failure stage/byte offset and exits nonzero when any
trial fails. Its executable copy remains `/home/pi/gossip_v2_peer.py`.

### Android persistence slice (2026-09-10, 13:24)

`73ee8bc` adds atomic public-v2 transactions and detached snapshots to the existing
GossipStore DataStore. The test first failed at the absent API, then passed against
the real file-backed implementation (3m04s). Two store callers concurrently add
facts, record a handoff and a duplicate, close the DataStore, and reopen it. The
test verifies retained facts, delivered-peer suppression, the closed storm gate,
snapshot isolation and independent expiry of relay memory.

`040a76d` wires AppViewModel authoring and GossipService receiving to that shared
store. The radio observes committed snapshots for offers; successful handoff IDs
are written back atomically. Notification counts and service-start eligibility use
the public outbox. Periodic pruning retains application facts. All 23 selected
PublicGossipStore/PublicGossip/GossipPolicy tests passed locally (3m42s), including
compilation of both production callers. Android CI passed for `040a76d` (`34471107690`, 5m29s) and for `73ee8bc`
(`34470732035`, 6m08s). iOS was not triggered by these Android-only commits and
remains green on `2a1838e` (`34444445648`).

The raw public state stays in the existing device-local `gossip.preferences_pb`,
already excluded by both backup rule files, instead of being mixed into timeline
exports. This deliberately avoids TimelineStore's per-instance read/write lock
(which is not safe for simultaneous ViewModel and Service instances). The earlier
TimelineCache slot is still unused and must not be mistaken for this state owner.

The Pixel hardware result above predates these persistence changes: it exercised
the transport source, not the service's new storage wiring. That wiring has local
real-DataStore regression coverage, but has not yet run on the Pixel. iOS durable
state, correct request/witness authoring, stable random local Gig bindings,
attribution/blocking/projection/UI, receipts/priority and corrected simulator
sweeps remain unfinished. No PR has been opened.

### Simulator slice (2026-09-10, 17:38) — done by another session

`276b0ff` closes work-order section 6. It touches only `sim/` and the parameter
paragraphs of `docs/gossip-public-wire.md`, so it triggered no CI run; that is the
path filter, and green on `android/` and `ios/` is unchanged.

The coverage reducer counted nodes against a fact/recipient-pair denominator. Every
number it produced is withdrawn rather than re-scaled, including the sweep the spec
cited for the 15-minute Carry window: that run compared 60 and 900 seconds on a
trace whose only encounter was at t=1, where the two cannot differ. The spec now
says so.

Added: relay depth and an optional hop limit on Facts, witness receipts modelled as
messages that spread under their own hop budget and die at the author, usefulness as
a standalone predicate with the spec's random tie, nearest-rank percentiles, and a
clustered venue trace. Sixteen tests, all pytest. `cd sim && python -m
station_to_station_sim.sweeps` reprints every table across five independent traces;
`sim/SWEEPS.md` records them with the method and the caveats.

**Read the tables and draw your own conclusions.** The absolute levels are one
synthetic crowd model with invented meeting probabilities, encounter bandwidth and
Fact size; only the knees are stable across seeds. Where a table and the Pixel or
the Pi disagree, the hardware is right. Where the sweeps plausibly bear on code,
most-confident first:

1. Anything that shortens the effective Carry window — iOS background scheduling in
   `GossipChannel`, outbox eviction under the 128-envelope and 128 KB caps, the
   storm gate's removal of a duplicated copy, and `GossipService` pruning.
2. Whether a hop count or TTL belongs on the wire. The Envelope has none today; the
   relay-depth table is what one would cost, and `mean_hops` says how deep real
   deliveries go.
3. Receipts, which are unbuilt. The receipt-hop table sweeps that budget with its
   cost in bytes and in duplicate arrivals at the author — including whether they
   need the seen-ID treatment Facts get.
4. What "recent" means in projection and UI: median and p95 seconds from authoring.
5. The usefulness window. Lowest confidence, and the sweep is explicitly inconclusive
   — `focus` exhausts its copy budget before the window decides anything, so the
   spec's two-minute default remains unsupported.

At the user's request, CONTEXT.md was reread in full. Its Gossip definitions and
Contact-only relationship clauses still describe v1 and conflict with the agreed
v2 model. Update that authored vocabulary along with the final ADR; do not let
those stale clauses silently reinstate Contact gating or check-in-only payloads.
The current radio and shared Android persistence source commits are both covered
by green native CI as specified above. Only the intentionally untracked `pc/`,
`scratchpad/` and `sideload-iphone.sh` remain outside commits.


### Local author scope slice (2026-09-10, 18:25 Oslo)

- Read `sim/SWEEPS.md` and the implementation signposts; reproduced all 16 tests
  using `sim/.venv/bin/python -m pytest -q` and completed the five-seed sweeps.
  The 120-user encounter model does not establish behaviour with 3 users among
  300 concertgoers. Reducing `users` alone retains a fixed encounter-attempt rate
  and increases attempts per user; it is not a low-adoption experiment.
- `feddf42`: iOS persists random author scopes in the ledger, preserves old ledger
  decoding, retains scopes on Contact removal, and fails closed on a failed initial
  scope write. AppModel resolves the stable local Gig before calling the channel.
- `11c2f78`: Android uses an atomic DataStore binding, independent of relay expiry.
  AppViewModel resolves the stable local Gig. Both PublicGossipStore tests passed
  locally (9m29s); the Keystore instrumentation test now obtains its scopes from
  the store. Android CI `34502024630` passed (5m59s); iOS scope CI `34501554881`
  passed (7m38s).
- Pixel is connected. Confirmed today's local `GossipRelay Test` at `Pinet`, without
  a setlist.fm ID. Only read the fixture; no end-to-end check-in claim is warranted.
- Next check-in slice must fix more than `kind = "log"`: a Pass proves the relay's
  key, whereas the request is signed by the Gig author. Establish direct authorship
  before witnessing; currently receive does not enforce that distinction. Keep the
  direct witness response separate from the usefulness receipt sweep. The bogus
  line-0 authoring and witness projection remain open.

### iOS persistence and Pixel verification (2026-09-10, 18:35 Oslo)

- `bb46b8a` replaces GossipChannel's memory-only public state with GossipLedger
  transactions. Local authoring, received Passes and successful handoffs persist;
  offers read detached snapshots. Facts survive relay expiry and Contact removal.
  Failed file writes no longer publish an uncommitted ledger cache. A regression
  test covers concurrent author/radio admission, reopening, duplicate suppression,
  delivery suppression and retained facts after expiry.
- Local Android app/test APK build passed (5m29s). Installed both in place with
  `adb install -r`; ran `GigIdentityDeviceTest` on the Pixel: **OK (1 test),
  0.285 seconds**. This now exercises GossipStore scope lookup before real Android
  Keystore key creation/signing, including concurrent key access and tamper rejection.
  Only temporary test storage and test-created keys were removed. Relaunched the app;
  all 88 Gigs and today's test Gig remain. This does not verify the real check-in UI
  or witness flow, which is still incomplete; no synthetic check-in was added to it.
- iOS persistence CI `34502349823` passed in full (6m6s), including the device build. No PR opened: check-in semantics and the remaining
  projection/routing/UI work still prevent calling #408 complete.


### Direct control admission slice (2026-09-10, resumed evening)

- `a5dd811` makes both state machines reject remote `request` and `receipt`
  envelopes unless the authenticated Pass sender is their author. This happens
  before seen/storm-gate changes: a relayed replay cannot suppress a later direct
  delivery or retire a locally held control. Ordinary facts remain relayable.
- Matching Android/iOS regression tests cover replay-before-direct delivery and
  local outbox preservation. Android focused PublicGossip tests passed locally
  (2m13s); Android CI `34531330532` passed. iOS CI `34531330596` passed (6m30s).
- This is an admission boundary only. Existing app authoring still emits the bogus
  line-0 check-in Log; direct request authoring, appropriate Pass signing and witness
  responses remain the next integration slice. Do not claim check-in is complete.

- Added opt-in `GossipRadioDeviceTest.indirectControlsAreRejectedWithoutPoisoningDirectDelivery`
  (`manual_ble_controls=true`) and Pi harness `--controls`: four authenticated
  Passes deliver indirect/direct copies of a request, then of a usefulness receipt.
  Assertions require admission `[false, true, false, true]`, exactly two seen IDs,
  no facts/outbox entries, and one usefulness credit. Local app/test build passed
  (4m2s); both APKs installed with `adb install -r`.
- **Actual device attempt did not verify this behaviour.** The Pi delivered 0/4:
  Bleak reported no Bluetooth adapters, and the Pixel assertion timed out after
  90.077s. The temporary plain-ATT Bluetooth configuration was restored byte-for-byte.
  `bluetoothctl list` was empty; `btmgmt info` reported zero indices; `hciconfig`
  showed UART hci0 DOWN RAW, default address 43:45:C0:00:1F:AC, zero ACL MTU.
  Kernel logs show default-address initialization at the Pi's 22:01 boot, before
  this test. Bluetooth service is active; hciuart service inactive (kernel serdev
  owns this controller). Do not blindly start a competing UART attach process.
  No networking, firmware or kernel-driver changes were made. Restoring a usable
  Pi controller is needed before rerunning; previous successful BLE evidence does
  not establish this new guard. Pixel app relaunched after instrumentation.


### Direct-control BLE retry passed (2026-09-10, 23:35 Oslo)

- After Claude restored the Pi controller address, independently confirmed BlueZ
  exposes B8:27:EB:7E:4B:0D. Reused the temporary `Channels = 1` wrapper; no boot
  service or networking changes made here.
- Pi `gossip_v2_peer.py --controls`: **4/4 completed**, each 629 bytes in 20-byte
  writes plus empty terminator. Trial durations 8.96, 7.64, 7.09, 7.21 seconds.
- Pixel `indirectControlsAreRejectedWithoutPoisoningDirectDelivery`, with
  `manual_ble_controls=true`: **OK (1 test), 42.894 seconds**. Verified admission
  `[false, true, false, true]` for relayed/direct request then relayed/direct receipt,
  two seen IDs, no durable facts or held controls, and only the direct receipt's
  usefulness credit. This supersedes the preceding hardware-blocked attempt.
- Wrapper confirmed original Bluetooth configuration restored byte-for-byte.
  Pixel app relaunched. This proves the real receive/framing/authentication/state
  boundary under plain ATT; it does not prove production check-in authoring,
  persistence/UI projection, the phone central/send path, or iOS radio behaviour.

### Authored check-in and direct witness (2026-09-11, 07:50 Oslo)

Issue #442. Check-in no longer authors a synthetic Log at line 0. It authors a
`request` under the checked-in Gig identity, and the radio's Pass is signed by that
same key whenever the batch carries a locally-authored request, so the Pass proves
the request author rather than the nightly relay. Other facts still travel under the
relay key; a Pass never carries two different authors' requests.

- A `request` is now durable evidence and enters `facts`. Arriving directly is not
  itself a witness: the witness is a separate signed fact embedding the complete
  signed request, produced only by a device holding its own local claim for the same
  Gig. `checkInEvidence` returns `(asserted, witnessed)` as two independent answers,
  so self-asserted attendance stays distinct from witnessed check-in.
- `localAuthors` is persisted, so a process restored by the radio after restart can
  still tell its own claim from a stranger's. Covered by the Android store test and
  the iOS ledger test.
- Receipts are unchanged and still excluded from the record: the witness response and
  the usefulness receipt remain separate signals.

`GossipRadioDeviceTest.indirectControlsAreRejectedWithoutPoisoningDirectDelivery` was
updated for this: the direct request is now expected as exactly one durable fact, the
direct receipt still is not. Without that change the retained regression would have
failed on the new admission rule rather than on a defect.

- Android `:app:testDebugUnitTest`: full suite green on this host.
- Pi `gossip_v2_peer.py --controls`: first attempt **0/4**, all four failing mid-write
  with BlueZ `UNLIKELY_ERROR` at bytes 540/480/520/500 of 629. This is the documented
  EATT failure, not a regression — the earlier run's `Channels = 1` workaround had
  been restored to the packaged default. The wrapper now lives at
  `/home/pi/gossip-plain-att.sh` rather than `/tmp`, with the `sudo -n rm -f` cleanup
  the previous run recommended.
- Rerun under that wrapper: **4/4 completed**, 629 bytes each, trials 8.50, 8.05,
  8.65, 6.87 seconds. Pixel `indirectControlsAreRejectedWithoutPoisoningDirectDelivery`
  with `manual_ble_controls=true`: **OK (1 test), 76.055 seconds**. BlueZ config
  confirmed restored to `#Channels = 3` and Bluetooth active.
- **iOS is unverified on hardware here.** This host is Linux; the Swift changes mirror
  the Kotlin ones and carry matching unit tests, but they have only CI to prove them.
- Still not proven: UI projection of witnessed check-in, the phone central/send path
  for a request, and the iOS radio. The issue defers projection to a later slice.

### Witnessed check-in reaches the screens (2026-09-11, 09:20 Oslo)

The remaining half of #442. The ledger knew which check-ins were witnessed; nothing
asked it.

- `PublicGossipState.witnessedGigIds()` on both platforms answers for the whole
  timeline in one pass. Asking per **Gig** would have meant minting a **Gig** identity
  per row just to learn its author key, which is why the projection is a set and not a
  predicate. It returns each witnessed claim's current id *and* its `formerIds`, so a
  setlist.fm id arriving after the night still matches the row.
- Only claims whose author is in `localAuthors` project. Witnessing a stranger is not
  evidence about your own night, and the Android and iOS tests both assert that
  direction explicitly — it is the easy thing to get backwards.
- `checkInEvidence(gigIds:author:)` is unchanged and still answers for any author. The
  two questions share `witnessedClaims()` but not its filter; collapsing them into one
  filtered helper broke the existing test, which was right to break.
- Android: `UiState.witnessedGigs`, collected from `GossipStore.publicStates` so a
  witness that lands while the app is closed still projects on the next launch. The
  chip reads `checked in · witnessed`.
- iOS: `GossipChannel.observeWitnessed`, a callback rather than polling because a
  witness arrives on the radio's queue. `AppModel.state.witnessedGigs`; `GigView`'s
  existing `✓ checked in` line becomes `✓ checked in · witnessed`.
- Self-assertion is untouched on both. `StoredAttendance.CHECKED_IN` remains the
  record and this only decorates it — a night nobody else was running the radio for is
  still a night the user attended.

The **Pass** author rule was inline in a `GossipService` lambda and in
`GossipChannel.publicPass`, so the acceptance criterion about proving the request
author's key had no unit coverage on either platform. Extracted to `passAuthor` /
`passBatch` in the shared wire file and tested. One behaviour follows from making it
explicit: a **Pass** signed by the relay now drops requests instead of carrying them.
`offer` can only return this device's own held requests, so nothing changes in
practice, but a foreign request in that batch would have been bytes the receiver was
bound to reject.

- Android `:app:testDebugUnitTest`: full suite green, three new tests.
- Pixel `indirectControlsAreRejectedWithoutPoisoningDirectDelivery` rerun after the
  refactor, Pi **4/4**: **OK (1 test), 51.714 seconds**. BlueZ restored to
  `#Channels = 3`, Bluetooth active.
- iOS again has only CI. Still unproven on hardware: the phone's own central/send path
  for a request, and the iOS radio. Verifying the central path needs the Pi to run a
  GATT *server* the phone connects to, which the current peer harness does not do.

### Completion resumed (2026-09-15)

The workspace already contained an uncommitted Android/iOS implementation of public
Log authoring/display, stable line numbers, blocking and participation deadlines,
plus an untracked sparse-adoption sweep. Those changes were preserved. They are
not all accepted or verified simply because the unit tests pass.

This pass closes a projection gap in that work: both screens now use
`PublicGossipState.arrivals` to include the request embedded in a relayed witness.
The direct request need never arrive at this device. Direct and witnessed copies
are deduplicated by claim ID, a block on the claimant suppresses the arrival, and
received evidence remains durable after transport expiry. Contact recognition now
also examines verified embedded claims, so a later Exchange attributes the actual
claimant as well as the witness. It does not undo a temporary-author block.
Matching Android/iOS regression cases cover these behaviours, including a real
AES-GCM sealed attribution binding using generated P-256 test keys.

The expanded sparse sweep produced 630 runs across three crowd sizes, two adoption
levels and five seeds, varying carry, receipt budget, usefulness decay and storage
limits. `sim/SWEEPS.md` records the command, assumptions, results and unmodelled
parameters. `docs/gossip-public-wire.md` no longer presents the dense model's carry
knee as sufficient evidence for sparse adoption. No routing defaults were changed.

Validation:

- Full Android `:app:testDebugUnitTest`: 761 tests, zero failures/errors/skips,
  build successful in 4m59s. This includes the relayed-arrival projection cases.
- Simulator: 17 tests passed after updating the adoption regression to 300 people.
- The additional cryptographic recognition regression was added after the full
  Android run. Focused `PublicGossipTest` rerun: 14 tests, zero failures/errors/skips;
  build successful in 29s.
- CI is green on `89bd25d`: [Android run 34921560663](https://github.com/magnus-encoded/station-to-station/actions/runs/34921560663)
  and [iOS run 34921560636](https://github.com/magnus-encoded/station-to-station/actions/runs/34921560636).
  iOS initially hit a SwiftUI type-check timeout in the nested gossip attribution
  row; extracting that row into a helper fixed it. The successful run includes
  633 simulator tests (5 skipped, zero failures) and the unsigned device build,
  not iPhone radio validation.
- Local debug and instrumentation APK builds succeeded in 2m5s. Both APKs
  installed successfully on the Pixel 7 Pro. Wireless adb then hung, went offline,
  and repeatedly disappeared; the identity instrumentation test did not produce
  a result. Installation alone is not a device-test pass.
- The Pi peer currently reports zero controllers through `btmgmt`, with its UART
  interface DOWN/RAW and zero MTUs. No fresh two-peer BLE run was possible.
  Locked-phone relay, device central/send flow and battery measurements remain
  unverified by this pass.

Remaining completion work:

- **Reopening resolved by the user, 2026-09-15:** resume gossip until the 06:00
  night cutoff. `StoredLog.completing(false)` clears `completedAt`; matching
  Android/iOS regressions assert the restored deadline and its hard cutoff.
- #443 merge of two already-authored Gigs: **decided by the user 2026-09-15: no
  fix.** It only happens when a person makes two Gigs for one night, writes a Log on
  both, and then adopts the setlist.fm id held by one of them. Adoption combines the
  two Gigs immediately. The public Facts of the losing Gig stay orphaned under its old
  id until they expire. The local Log is a separate bug, found 2026-09-15:
  `unionLog` keeps the longer Log and **silently drops the other**. User rule:
  **never drop handwritten data.** That fix is in progress on
  `prep/log-merge-no-drop`. Follow-ups: hold to reorder Log lines, and have
  Departures commits and Ticket adds check for an existing Gig before they create
  one. `mergeGigs` has no caller in the app.
- Implement #444 usefulness generation and visible-neighbour priority after the
  early iPhone viability experiment required by #448. The current simulator does
  not select a supported decay value or validate radio performance.
- Complete #446 device verification and #448 lifecycle/application tests, then
  align #415's final record with the accepted implementation. Keep #408 open.


### Prep reviewed after rate-limit reset (2026-09-15)

Read `scratchpad/astra-handoff-2026-09-15.md` and all four linked prep notes.
Verified locally that `a5ebd40` and `38cadfe` contain tests only and both prep
worktrees exist. Their reported passing test runs were not independently rerun:
the current execution sandbox cannot start Gradle or adb (details below).

Corrections to the prep:

- The original merge test did not call `TimelineStore.mergeGigs` and used one
  signing key for both scopes. The revised Android test invokes the actual merge,
  uses two keys, restarts the gossip store and checks that both identities and
  their facts survive. Adoption now calls the actual timeline adoption method.
  Matching iOS ledger coverage has been added. These tests do not claim that
  publication wiring or merged-away projection is complete.
- The ignored per-Gig relay test received its fact three hours before the check,
  so the 15-minute carry window would already remove it. It could pass without
  a participation fix. The replacement receives fresh facts one second before
  grace ends and checks both sides of the exact cutoff while another Gig remains
  active, then reopens and checks that the facts remain available.
- The other ignored test incorrectly used radio eligibility as a proxy for an
  unfinished text edit. Android's Log editor already keeps typed text locally
  until its field Done callback calls `onAdd`. No failing assertion about radio
  participation is retained to stand in for that UI requirement. Publication
  policy clarification was requested; production publication behaviour is unchanged.

Uncommitted implementation now carries per-Gig participation deadlines through
Android's service snapshot and into `PublicGossipState.offer`. iOS derives the
same map from persisted attendance and Logs for each outgoing Pass and uses it
for the app's aggregate radio deadline. Known local and external ids share a
cutoff; expired known Gigs are filtered before the batch budget. Unknown nights
can still be carried blindly while a checked-in Gig keeps the radio active.
Filtering does not delete facts or reset carry retention. Matching lifecycle
tests cover cutoff, reopening, retention and manual stop. This work is **not yet
compiled or accepted**.

Current verification limits:

- `git diff --check` passes. No new test suite pass is claimed.
- Cherry-pick failed with `.git/sequencer: Read-only file system`; applied the
  two test patches to the working tree and revised them there. Nothing committed
  or pushed in this resumption.
- Gradle wrapper could not write its cache lock. Retrying the installed Gradle
  with a writable `/tmp/gossip-gradle` home failed before compilation with
  `Could not determine a usable wildcard IP for this machine` in
  `FileLockContentionHandler`.
- adb failed to start its listener with `Operation not permitted`; this is a
  sandbox restriction, not new evidence that the Pixel itself is offline.
- GitHub API access fails. Prior green CI on `89bd25d` does not cover these edits.
- Pi reboot/power-cycle was not attempted; the prep warns it interrupts this
  computer's internet. The prep's controller diagnosis has not been refreshed.

Still outstanding: merged-away Gig projection policy/integration, #444 receipt
creation and neighbour scheduling, actual app/device acceptance, and final ADR
alignment. The checked-in/request/witness and radio viability requirements remain
open; do not close #408 based on the newly added tests.

### Pixel + Pi BLE rerun on 1357ae9 (2026-09-15, 13:10 Oslo)

- The Pi controller came back unconfigured after the reboot: default address, no public address, and
  `bthelper` failing. Fixed with `sudo btmgmt -i hci0 public-addr B8:27:EB:D4:E1:A6 && bluetoothctl power on`.
  This is **not persistent**: repeat it after each boot. Its address is now `D4:E1:A6`, not the earlier `7E:4B:0D`.
- Local debug and androidTest APKs built from `1357ae9` and installed in place on the Pixel 7 Pro over wireless adb.
- `publicPassesCrossTheRealGattLinkAndCloseTheStormGate`, under `/home/pi/gossip-plain-att.sh 6`:
  - First attempt: 5/6 trials, then **FAIL**. Trial 6 hit `Invalid Handle` at about the moment the
    90-second await expired. Pi trials now take about 14-17 s each including the challenge, against
    about 7-9 s on 09-10, so six trials barely fit in 90 s.
  - Rerun, with the Pi started 4 s after instrumentation: **6/6, OK (1 test), 83.9 s**.
  - Consider raising the await, or reducing to five trials.
- `indirectControlsAreRejectedWithoutPoisoningDirectDelivery` with `manual_ble_controls=true`, under `--controls`:
  Pi 4/4, **OK (1 test), 50.1 s**. BlueZ restored to `#Channels = 3`, and Bluetooth active.
- Still unproven: the phone central/send path, the iOS radio, locked-phone relay and battery.


### #455 resumed — 2026-09-15 evening

- `9bca267` brings iOS adoption to Android parity: adopting an already-held
  setlist.fm id combines into the older Gig through the same path as explicit
  merging. Both Logs survive; stronger attendance survives. Matching Android/iOS
  store tests cover both survivor directions, stable line numbers, restart and
  repeated adoption. The decided orphaned-public-Facts edge case is unchanged.
- Local focused Android verification: TimelineStoreTest 76 + LogTest 22, zero
  failures/errors/skips. Debug and androidTest APK builds succeeded (1m6s total).
- ADR-0019/0021 amendments retain historical decisions while correcting current
  participation rules and simulation claims. The wire record now explicitly says
  receipt authoring and neighbour ranking are not implemented.
- Reviewed the pre-existing uncommitted central-send instrumentation test and Pi
  GATT server. The server registers/advertises on pinet with its existing Bluetooth
  settings. A five-second readiness probe printed READY then timed out with no
  sender, as expected; this is not a transport acceptance pass.
- Direct Pi access: `ssh -i ~/.ssh/id_ed25519_pinet pi@10.42.0.1`. Bluetooth powered,
  address B8:27:EB:D4:E1:A6. No restart or networking change was needed.
- Pixel unavailable: adb discovery reports 192.168.1.216:5555 but connection is
  refused. No iPhone accessible through idevice_id. Device access requested.
- Publication choice requested: committed line versus whole-Log completion.
  Existing committed-line publication remains unchanged pending the answer.
  #455 requires the early locked-iPhone experiment before usefulness work.

Central-send acceptance, when the Pixel is available:

1. Install both newly built APKs in place (`adb -s SERIAL install -r ...`).
2. On Pi, run `sudo /usr/bin/python3 /tmp/gossip-455-server.py --timeout 150`
   (copied from `docs/prototypes/gossip_v2_server.py`). Wait for READY.
3. On Pixel, run `adb -s SERIAL shell am instrument -w -e manual_ble_server true
   -e class io.github.magnusencoded.stationtostation.GossipRadioDeviceTest#centralSendsOwnCheckInRequest
   io.github.magnusencoded.stationtostation.debug.test/androidx.test.runner.AndroidJUnitRunner`.
4. Require BOTH instrumentation success and the Pi's independent verified request
   PASS, recording the build SHA and Fact id. This tests production central radio
   with an injected signed request, not app Check in wiring or witness projection.


CI verification for this resumption:

- [iOS run 35009342006](https://github.com/magnus-encoded/station-to-station/actions/runs/35009342006),
  `9bca267`: green. 642 tests, 5 skipped, zero failures; the new adoption restart
  case passed. Unsigned iPhone build and IPA packaging passed. This is simulator
  and build evidence, not iPhone radio validation.
- [Android run 35009506760](https://github.com/magnus-encoded/station-to-station/actions/runs/35009506760),
  `c9cc2ef`: green, including unit tests, debug/device-test APKs and measure APK.
- #455 is still incomplete: publication choice, phone access and the locked-iPhone
  experiment are pending. Receipt generation/ranking remains gated on that
  experiment. Cross-platform/locked-phone acceptance, battery measurements and
  remaining application-level lifecycle/UI checks are not established by CI.


### User decision — iPhone testing deferred (2026-09-15)

No iPhones are available at present. The user explicitly deferred testing in
response to the locked-phone viability request. Do not keep requesting iPhone
access or attempt those hardware trials until devices are available again.
Locked-iPhone receive/forward, two backgrounded iPhones and the iPhone portions of
cross-platform and battery acceptance remain deferred and unverified, not passed.
Unit tests and CI remain applicable; their results do not establish radio viability.
The publication-policy question remains unanswered. Deferring the experiment
provides no evidence for settling routing parameters or iPhone relay assumptions.


### User decision — publication resolved (2026-09-15)

“Each line, correct or not becomes a fact that is transmitted as the current state.”
Publish every committed addition/correction during participation; do not wait for
whole-Log completion or verification of correctness. Corrections replace the
projected current version while preserving earlier Facts. Whole-Log completion
only starts grace. Uncommitted typing remains local, as do post-participation edits.

Confirmed by reading Android AppViewModel.writeLog/publishLog and iOS
AppModel.writeLog/GossipChannel.publishLog: both already publish committed changes
without a closed-Log requirement. No production behavior changed and no test rerun
was needed for this documentation-only decision. ADR-0021 and the wire record now
state the settled rule; earlier pending-choice entries are historical.


### Android central-send acceptance — attempted on hardware (2026-09-16)

Ran against a Pixel 7 Pro (`cheetah`, Android 17) over network adb, with the
gossip v2 peripheral hosted on the development laptop rather than the Pi.

What the attempt established:

- **The Pi is not required to host the peer, and neither is root.** The laptop's
  own controller (`24:0A:64:9F:B2:FD`, BlueZ 5.87) runs
  `docs/prototypes/gossip_v2_server.py` as an ordinary user out of a venv built
  with `--system-site-packages` plus `cryptography` and `dbus-python`. It prints
  `READY` and advertises the service. No system setting was changed.
- **Discovery works on real hardware, for the first time.** The production
  `GossipCentral` scan found the advertised service from a third-party BLE
  peripheral and opened a push. Discovery took 20–60 s against the one-second
  advertising interval, which is inside the balanced scan's expected window.
- **The connect never completes.** Every push ends in the `"connect"` phase.
  Before the scan fix the drop arrived in about four seconds; after it, no GATT
  callback arrives at all and the push ends on its own 20 s timeout —
  `no answer while waiting on "connect"`. Nothing reaches service discovery, so
  neither the challenge read nor the Pass has ever been exercised on hardware.

Two fixes landed from the attempt, both green in CI:

- `9949fe0` takes the scan off air for the duration of a push and gives it back
  in `finish`, with a `stop` in flight taking precedence. Scanning through a
  connect is the usual cause of an instant drop on Android. The logs confirm the
  pause and the restart behave; it did not make the connect succeed.
- `cec983d` puts the reason a push gave up into logcat, not only the radio panel.
  A device test has no panel, and the phase name alone hid the diagnosis.

Still unresolved, and **the acceptance does not pass**:

- Whether the laptop's advertisement is connectable at all is unverified. A BlueZ
  advert that registers but goes out non-connectable would produce exactly this
  symptom — seen, never connectable — and would be a property of this substitute
  peer, not of the app. Confirming it needs `btmon`, which needs root; this host
  has no passwordless sudo.
- The Pi remains unreachable. `~/.ssh/id_ed25519_pinet`, named in the entry above,
  is not present on this machine, and `pi@pinet.local` (10.42.0.1) rejects the
  available key. The Pi is the peer the protocol was written against and the one
  where the peripheral was verified, so it is still the reference.

Next step is one of: root on this host to read `btmon` and settle whether the
advert is connectable, or the Pi key back, and then re-run the protocol above.
Do not record a pass until the Pi's independent verified-request `PASS` and the
instrumentation success land together.

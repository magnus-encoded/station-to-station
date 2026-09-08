# Handoff — #416 Gossip transport, Android background BLE service

Written 2026-09-08. Delete this file before merging; it is a handoff note, not documentation.

## Where the work is

| | |
| --- | --- |
| Branch | `gossip-android-transport` |
| Pushed to origin | **Yes** — `origin/gossip-android-transport`, tracking set |
| Worktree | `/home/dizzi90/Documents/station-to-station` (the main checkout, **not** a separate worktree) |
| Branched off | `origin/main` at `a26db17` |
| CI | **Green.** Run `34210279743` — `testDebugUnitTest` + `assembleDebug` + measure APK all pass |
| PR | **Not opened yet.** Nobody has run `gh pr create`. |

Two commits on the branch:

1. `Gossip transport: the Android background radio (#416)` — everything below.
2. `Fix the outbox test's own arithmetic` — one test assertion that had the exclusion
   backwards. CI caught it; the production code was right.

**Careful with git here.** A sibling agent is implementing #417 (iOS) concurrently in this
same working directory on its own branch. Stay on `gossip-android-transport`, do not run
destructive git commands, and do not touch `ios/` or the untracked files at the repo root
(`.claude/`, `.cursor/`, `pc/`, `StationToStation.ipa`, `sideload-iphone.sh`,
`testflight-setup.txt`, `docs/festival-model-handoff.md`) — none of those are mine.

## What is implemented

### New — the pure layer, `android/app/src/main/java/io/github/magnusencoded/stationtostation/data/gossip/`

| File | One line |
| --- | --- |
| `GossipToken.kt` | Derives and matches the rotating advertising **Token**; three-bucket lookup table; round-robin over Contacts for the single advert slot |
| `GossipWire.kt` | The **Pass** envelope on the wire — encode/decode, the auth payload, the size ceiling |
| `GossipHeld.kt` | What a device carries; derives the gate's seen set from it; the outbox rule; disk encoding |
| `GossipPolicy.kt` | The per-peer cooldown, the service lifecycle predicate, and the gig-night helpers |
| `GossipMint.kt` | Mints this phone's own signed check-in |
| `GossipStore.kt` | DataStore (`gossip.preferences_pb`) behind an atomic read-prune-edit-write |
| `GossipService.kt` | The foreground service: owns both radio halves, runs the storm-gate, folds the result through the store, keeps the notification honest |

### New — the radio

- `.../ble/GossipRadio.kt` — `GossipPeripheral` (GATT server: challenge read, handover write,
  advertising rotation) and `GossipCentral` (filtered scan, token match, connect, prove, push).

### New — tests (all JVM, no device)

`GossipTokenTest.kt`, `GossipWireTest.kt`, `GossipHeldTest.kt`, `GossipPolicyTest.kt`,
`GossipMintTest.kt`, under `android/app/src/test/java/io/github/magnusencoded/stationtostation/`.

### Modified

| File | One line |
| --- | --- |
| `CONTEXT.md` | New **Gossip** section naming Check-in, Gossip, Carry, Pass, Token, Storm gate, plus one relationship line |
| `docs/adr/0019-gossip-channel-background-carve-out.md` | Appended dated amendment (see "Decisions" below); body untouched |
| `AndroidManifest.xml` | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, the `<service>` element |
| `res/xml/backup_rules.xml`, `res/xml/full_backup_content.xml` | Both exclude `datastore/gossip.preferences_pb` — they must stay in step |
| `res/values/strings.xml` | Notification channel and notification strings |
| `data/SettingsRepository.kt` | `alwaysRelay` flow + `saveAlwaysRelay`, default false |
| `AppViewModel.kt` | `gossip` store; `gossipAbout(gigId)` on check-in; `syncGossip()` on launch, check-in, new Contact, setting change; `alwaysRelay` in `UiState`; `setAlwaysRelay` |
| `ui/SettingsScreen.kt` | "Passing on check-ins" section with the always-carry switch |

## Decisions a new agent must not silently re-decide

These are on-wire or trust decisions. Changing any of them breaks parity with #417 or with
#410's storm-gate.

### 1. The Token is NOT an ECDH-shared-secret HMAC

#408 and #416 both specify "an HMAC of the ECDH-shared-secret and a time bucket, truncated".
**That is not implementable** against what an Exchange leaves behind:

- `ContactIdentity.kt` creates one AndroidKeyStore EC P-256 key with `PURPOSE_SIGN` only.
- Keystore keys are immutable, and this key is already on every phone with a Contact.
- `PURPOSE_AGREE_KEY` needs API 31; minSdk is 26.
- An agreement-capable second key would have to ride the **Card** — the new pairing step the
  issue forbids — and would strand every existing Contact until the two people met again.

**What is implemented instead**, and what iOS must match byte for byte:

```
token = HMAC-SHA256(
    key = <lower key> "\n" <higher key>,           // base64 SPKI identity keys, sorted lexicographically
    msg = "station-to-station/gossip-token/1\n" + bucket
).take(8)                                          // first 8 bytes

bucket = floorDiv(epochSecond, 900)                // 15 minutes; floorDiv, not /
```

A scanner builds a table over buckets `-1, 0, +1` and matches only on an exactly-8-byte
advert. Advertising rotates one Contact's token every 4 s (`GOSSIP_ADVERTISE_SLOT`), ordered
by sorted key so the sequence is deterministic.

**The cost, accepted deliberately:** unlike a shared secret this is computable by anyone
holding *both* public keys. That is a linkability weakening of the advertisement only — it
forges nothing, because a Pass needs a signature over a nonce and a Check-in needs the
author's private key. It is argued in full in the ADR-0019 amendment.

### 2. Everything else on the wire

- `GOSSIP_PASS_V1 = "station-to-station/gossip-pass/1"` — the Pass header.
- `GOSSIP_AUTH_V1 = "station-to-station/gossip-auth/1"` — domain separator for the
  possession proof, distinct from `GOSSIP_PAYLOAD_V1` (#410) and from reconcile's challenge.
- Auth payload signed by the pusher: `GOSSIP_AUTH_V1 + "\n" + base64(nonce)`.
- Nonce: **32 bytes**, fresh per device address, issued at offset 0 of the challenge read,
  spent on one Pass.
- Pass grammar: line 0 header; line 1 `from \t proof`; lines 2.. records of
  `messageId \t gigId \t checkedInBy \t checkedInAt \t expiresAt \t signature`, times as
  **epoch seconds**. Tabs and newlines because base64 contains neither and gig ids pass
  `isSafeGossipId`.
- Unparseable individual records are **skipped, not fatal** — otherwise any device in the
  chain can kill a message by corrupting its neighbour.
- `GOSSIP_MAX_WIRE_BYTES = 40_000`, refused whole.

Service/characteristic UUIDs (deliberately a different service from the Exchange probe's
`…7711`, because it has a different trust rule):

```
service    7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7721
challenge  7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7722   READ
handover   7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7723   WRITE
```

Manufacturer data uses company id `0xFFFF`, as `BleCardPeripheral` already does.

### 3. Naming: "Pass", not "Handover"

`Handover` in this codebase already means *moving to a new phone* (`HandoverScreen`,
`HandoverWire`, `runHandoverSource`, …). The gossip transfer unit was renamed to **Pass**
before the code landed, and named in `CONTEXT.md` first. Do not reintroduce "handover" here.

### 4. Nothing decides about a message except the storm-gate

`GossipService.accept()` calls `gossipStormGate(seen, batch, from, now, contacts, nightEndFor)`
and nothing else. TTL, dedup, signature verification, the Contact test and the relay audience
are all #410's, untouched. If an `if` about a message seems to belong in the transport, it
belongs in the gate.

One store, not two: `GossipHeld` is persisted and `seenFrom(held)` derives the gate's seen
argument, so the seen set and the outbox cannot drift. Held entries take the expiry the gate
settled on (`plan.seen`), never the author's raw claim.

### 5. The per-peer rate the gate delegated

`GOSSIP_PEER_COOLDOWN = 1 minute`, charged on **both** directions (reading a Pass and pushing
one). With `GOSSIP_MAX_BATCH = 64` that is the 64-messages-per-minute-per-peer ceiling. A
refused Pass is not remembered — same shape as a `BATCH_LIMIT` overflow. Not persisted, on
purpose: the radio stopping is the harder bound, and persisting it would only delay the first
Pass after a restart, which is when there is most to say.

### 6. The service lifecycle — the thing the issue asks to have flagged for review

`gossipRelayShouldRun(contacts, holding, gigTonight, alwaysRelay)` in `GossipPolicy.kt`:
at least one Contact, then **any** of

- carrying a live message (covers the sender; self-stops when the night's messages expire),
- a Gig inside its night window (covers the receiver; this is what makes a relay chain
  possible at all),
- the opt-in always-carry setting, **off by default**.

Deliberately *not* reasons: the app being open (would recreate the limitation ADR-0019 carved
itself out of), and a boot receiver (`START_NOT_STICKY`; opening the app brings it back).

Notification: channel `gossip`, `IMPORTANCE_LOW`, ongoing, with an explicit **Stop** action
routed through the same service class. Advertising is `ADVERTISE_MODE_LOW_POWER`, scanning is
`SCAN_MODE_BALANCED` — both a step down from the Exchange probe's `LOW_LATENCY`, on the
grounds that no human is waiting.

## What is still missing to close out #416

1. **Open the PR.** Base `main`, head `gossip-android-transport`, body must contain
   `Fixes #416`. Not done.
2. **Two-phone in-range relay test — not performed.** Only one Android device is paired
   (Pixel 7 Pro at `192.168.1.216:42031`, `cheetah_beta`, API 37). The iOS twin (#417) was
   still being written when this was handed off.
3. **Three-device chain test — not performed**, same reason. The acceptance criteria ask for
   it; the PR must say plainly that it was not done rather than implying it was.
4. **APK never installed.** It was downloaded to
   `/tmp/claude-1000/-home-dizzi90-Documents-station-to-station/cbab70ee-d9d4-40b4-9a04-7ec234d0be96/scratchpad/apk/app-debug.apk`
   from run `34210279743` and `adb install -r` was never run. `adb`/`fastboot` live at
   `/opt/android-sdk/platform-tools`; connect with `adb connect 192.168.1.216:42031`.
   Even single-device, worth confirming: the service starts after a check-in, the
   notification appears with a working Stop, `adb shell dumpsys activity services` shows it,
   and logcat shows advertising and scanning starting.
5. **Battery figure never measured.** `SCAN_MODE_BALANCED` was chosen over `LOW_POWER` on
   reasoning, not data; `GossipCentral.start()`'s comment says so and says the trade should be
   revisited from a real night rather than guessed at twice.
6. **Tell the #417 agent the Token derivation changed.** They are working from the same
   ADR-0019 and the same issue text, which still says ECDH. If nobody tells them, the two
   platforms will not recognise each other's adverts. Everything they need is in section 2 of
   "Decisions" above and in the ADR amendment.
7. **Delete this file** before the PR merges.

## Gotchas

- **No local JDK or Gradle.** "Build" means push and read CI. `gh run watch <id> --exit-status`.
- **`gh run download` fails when run from outside a git repo** (it shells out to git). Pass an
  absolute `-D` path and run it from the repo, or from anywhere with `-R`.
- **CRLF.** `core.autocrlf=true` on this clone; git warns on every add. Harmless, ignore it.
- **Live Exchange BLE state, captured earlier** (logcat saved at
  `.../scratchpad/logcat_exchange.txt`): with the Exchange screen open on both phones, Nearby
  Connections drives Bluetooth Classic *plus* BLE extended *plus* BLE legacy advertising at
  once, mDNS advertises on port 59621, and BLE scanning runs at `low-latency`. Two
  consequences for this work: the gossip radio is a second, independent advertiser/scanner
  that can be live at the same time as all of that, and a device that is mid-Exchange is a
  noisy radio environment to test gossip discovery in — do the relay test with the Exchange
  screen closed.
- **`::rotate` was a real bug and is fixed.** A method reference builds a fresh `Runnable`
  each mention, so `postDelayed(::rotate)` / `removeCallbacks(::rotate)` never matched and the
  advertiser kept rotating after `stop()`. There is a kept `rotation` field now. Do not
  "simplify" it back.
- The `nightEndFor` map only covers Gigs on this timeline. That is correct — a Contact may be
  at a show nobody else knows about, and the gate falls back to its own 30-hour ceiling.

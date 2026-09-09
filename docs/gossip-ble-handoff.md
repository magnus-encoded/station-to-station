# Handoff: gossip check-ins do not cross between the Pixel and the iPhone

**Status:** Android → iOS is fixed and **confirmed on hardware**. iOS → Android is narrowed to
one line of Swift and is neither confirmed nor fixed. The Android *central* path is now also
confirmed end-to-end against a synthetic peer — see *The rig*.
**Written:** 2026-09-09, out of a debugging session on `gossip-ble-diagnostics`. Updated the
same day with the live-capture results, and again that evening with the debugging rig.
**Branches:** `gossip-write-limit` (`566ab67`) is the fix alone and is **PR #438** to `main`.
`gossip-presence` is the presence work alone and is **PR #439** to `main`.
`gossip-ble-diagnostics` carries the logging and the on-screen relay panel, and is the branch
this doc lives on.
**Constraint from the user:** the logging **stays off `main`**. Honoured — both PRs above are
clean of it; see *Next steps*.

## The symptom

Both phones added the same event from setlist.fm and checked in. Each phone's gossip log shows
its own entry and neither shows the other's. The same night, the Oslo Black Pride poster
transferred between the two phones fine — so the devices are paired, mutually **Contacts**, and
in range.

## What the poster proves, and what it does not

Reconcile is the **LAN/mDNS/TLS-socket** path (`data/ContactReconcile.kt`,
`data/exchange/ContactSession.kt`). Gossip is **BLE-only** (`ble/GossipRadio.kt`). They share no
transport.

`ContactReconcile.kt:50-56` requires a verified challenge-response against `Friend.publicKey`
before it will produce a non-empty plan. So the poster crossing **does** prove the two phones
are Contacts holding each other's correct identity keys. It proves nothing about BLE.

Do not re-derive this. An early hypothesis in the session was that the phones were not Contacts;
it was wrong, and the poster is the disproof.

## Eliminated — do not re-investigate

Each of these was checked by direct file comparison across the two platforms:

- **Wire format** — `GossipWire.kt` / `GossipWire.swift`, header, field order, separators.
  `GossipWireTest.kt` asserts the grammar, not just a round trip.
- **Token derivation** — `data/gossip/GossipToken.kt` and `Data/Gossip/GossipToken.swift` are
  identical. HMAC-SHA256 over `"station-to-station/gossip-token/1\n<bucket>"`, keyed by the two
  identity public keys sorted and `\n`-joined, 15-minute floored buckets, ±1 skew, truncated to
  8 bytes, lower-case hex.
- **Identity key encoding** — ECDSA P-256, base64 X.509 SubjectPublicKeyInfo on both sides.
- **Service and characteristic UUIDs**, BLE roles, runtime permissions.
- **gigId derivation** — `GossipService.kt:282-288` keys off `TimelineCache.keyOf`, the
  setlist.fm id. Both phones added the same setlist.fm event, so the ids match.
- **Storm gate** — `data/GossipStormGate.kt`. An unknown gigId is *not* a rejection;
  `effectiveExpiry` falls back to the signed expiry when `nightEndFor` returns null.

## The decisive evidence

One line, from a Pixel bug report captured 2026-09-08:

```
W GossipRadio: gossip push to a peer gave up in "pass"
```

`phase` only reaches `"pass"` (`GossipRadio.kt:673`) after discovery, connection, MTU
negotiation, service discovery, a challenge read that resolved to a **Contact**, `due()`
passing, a non-empty outbox, and successful signing and encoding. So everything up to the write
worked, and the failure is the Pass write itself.

## Root cause (Android → iOS), fixed in `5aaee6c`

`GossipCentral` requested `requestMtu(517)` and then chunked the Pass into `attMtu - 3` = **514**
byte writes. The Core Spec caps one attribute *value* at **512** octets no matter how large the
MTU is. These are two different limits and they agree only below an MTU of 515 — which is why an
Android-to-Android push never showed the bug.

CoreBluetooth enforces the attribute cap: it refuses the write with `Invalid Attribute Value
Length` **before `didReceiveWrite` runs**, so the iPhone saw nothing and the Pixel logged only
that it gave up. iOS had it right all along — `GossipTransport.swift:317` uses
`maximumWriteValueLength(for: .withResponse)`.

The fix, `GossipRadio.kt:104` and `:129`:

```kotlin
private const val GOSSIP_MAX_ATTRIBUTE_BYTES = 512

internal fun gossipWriteLimit(attMtu: Int): Int = minOf(attMtu - 3, GOSSIP_MAX_ATTRIBUTE_BYTES)
```

Regression test: `GossipWireTest.kt:251`, `a write is bounded by the attribute limit as well as
the MTU` — asserts the crossover at 515 rather than only the 517 case.

**Confirmed on hardware, 2026-09-09.** Live logcat off the Pixel, four consecutive complete
pushes to the paired iPhone across a five-minute capture:

```
14:20:20.489  mtu negotiated to 517 (status=0), using 517
14:20:22.576  push: 1 message(s), 572 bytes in 3 chunk(s) of 512, mtu=517
14:20:22.773  push delivered to a contact
```

572 bytes → `[512, 60]` plus the empty terminator = 3 chunks, and **no**
`pass chunk … refused, status=7` anywhere in the capture. Repeated at 14:22:23, 14:23:24 and
14:24:25. Android → iOS is fixed.

**By design, not faults, in that same capture:**

- *The same message re-sent every ~60 s.* The outbox is derived from `held` and gated by
  `gossipPassDue`; the receiving storm gate dedupes. Working as intended.
- *`contact resolved but still inside the push cooldown`* and *`gave up in "challenge"`.* Both
  are the cooldown doing its job on a peer already spoken to.
- *`saw a gossip radio (…)` appearing once per address and never again.* The `logged` set is
  per-address and never cleared.
- *A 4.0-second log cadence.* `rotate()` stops and restarts advertising every
  `GOSSIP_ADVERTISE_SLOT`.

## Still unexplained: iOS → Android

The 512 fix cannot explain this direction; the iPhone's chunking was always correct.

**Struck: the one-shot-discovery hypothesis.** It said nothing re-arms `scanLocked()` when a
check-in is created, so a Pass minted after discovery has no discovery event to ride out on.
The capture disproves it. The iPhone connected to the Pixel's server **about forty times** in
five minutes, read the 99-byte challenge every time, and disconnected in 50–110 ms **without
writing**. Discovery is not the problem — it discovers constantly. It gets all the way to
holding a nonce and then has nothing to say.

**Where it actually dies.** `GossipChannel.pass(to:nonce:now:)`
(`ios/.../Data/Gossip/GossipChannel.swift:148-160`) has four paths that return nil. Three are
eliminated by the evidence: Android *resolved the challenge the iPhone served*, which proves
iOS holds both an identity key and this contact. That leaves **`batch.isEmpty`** — the iPhone
builds an empty batch and correctly declines to send an empty Pass.

Why the batch is empty is the open question. `GossipLedger.offer` filters
`!$0.deliveredTo.contains(contact)` (`:75-81`) and `hold` seeds `deliveredTo` with only the
author's own key (`:130-136`), so either the iPhone believes it already delivered to this
contact, or it is holding nothing for tonight at all.

**Ruled out: the expiry trap.** The iPhone's gig is dated 9 September 2026 and shows ✓ checked
in, so its own check-in has not aged out.

**The test that decides it.** Make a *fresh* gig dated today on the iPhone and check into it.
A new gigId means a new messageId, which means `deliveredTo` is seeded with only its own key
and cannot already contain the Pixel. Then watch the Pixel for `accepted a pass of N
message(s) from a contact`, or for one of the four drop reasons. If a fresh check-in crosses
and an older one does not, the fault is in `deliveredTo` bookkeeping; if neither crosses, it
is `hold`.

**Latent, not convicted:** `GossipTransport.swift` has no `peripheralManagerDidStartAdvertising`
handler, so a failed `startAdvertising` is silent and never retried.

Also still true, from the header comments in that file: a backgrounded iPhone moves its service
UUID into an overflow area an Android scanner cannot see, and `AllowDuplicates` is ignored in
the background regardless. **Run every test with the iOS app in the foreground.**

Fixing this needs an iOS-side change, which this setup cannot currently deploy — the iPhone
cannot be rebuilt. Hence the diagnostics all landing on the Android side.

## iOS has no UI surface for received gossip

Worth knowing before reaching for a screenshot as evidence: **no iOS UI file references gossip
at all.** The SHARED band on `NightGrid.swift:102-125` is media (`mine` + `received`), not
gossip. A relayed check-in arriving on the iPhone would show nothing, so "Nothing shared yet"
on that screen neither confirms nor denies delivery. Only the Pixel's log can answer.

## The diagnostics on this branch

The Pixel is the only end that can report, so `5aaee6c` makes it report on both directions.

**Push side (Android → iOS)** — every previously silent `finish(false)` now names the gate that
closed: challenge read status (`:630`), unreadable challenge (`:634`), unresolved token with
offered/table counts (`:641`), advertised-vs-offered mismatch (`:649`), cooldown (`:654`), empty
outbox (`:659`), signing failure (`:663`). Chunk plan at `:681`, refusal with GATT status at
`:696`, negotiated MTU at `:611`, success at `:581`.

**Server side (iOS → Android)** — this is the *only* evidence available about the other
direction. Peer connect/disconnect (`:259`, `:261`), challenge reads (`:205`), incoming chunk
size/offset/`preparedWrite` (`:238`), and every `deliver()` drop reason (`:295`-`:307`) plus an
accept line (`:310`).

Also: `startScan` / `startAdvertising` `runCatching` blocks now log failures (`:380`, `:527`)
instead of swallowing them, and `GossipService.sync()` logs the run decision with all four
inputs.

## The rig

Built 2026-09-09 on the CachyOS box, because the edit → CI → download-artifact → sideload loop
was costing minutes per question in a problem that needs dozens of questions.

### `st2s`, at `~/.local/bin/st2s`

One script, three targets. `st2s` with no argument prints the commands.

| Command | What it is for |
| --- | --- |
| `st2s test` | JVM unit tests. **~1.8 s** on a no-change rerun with the configuration cache warm. The real fast loop for `GossipPolicy` / storm-gate / write-limit work. |
| `st2s push` | Build the debug APK and install it straight onto the Pixel over wireless adb. Replaces the whole CI-artifact cycle. |
| `st2s plog` | Live gossip events off the Pixel, with the 4-second advertise heartbeat filtered out. |
| `st2s pstate` | What the Pixel's own BT stack says it is doing, from `dumpsys`, rather than from our logs. |
| `st2s ui` / `up` / `down` | The Waydroid container. UI only — see below. |

The Pixel endpoint is `ST2S_PIXEL`, currently `192.168.1.216:32907`. It **moves whenever
Android's wireless debugging restarts** — re-read it from Developer options and re-export.
The box reaches it through pinet's NAT out to the 192.168.1.x LAN, which works.

### Waydroid runs the app but can never test the radio

LineageOS 20 / Android 13, VANILLA, session managed by `waydroid-container`. Good for the
presence line, the gig screen, the relay notification, navigation.

**It has no Bluetooth stack at all**, and this is structural, not a configuration miss:
`/vendor/bin/hw/` ships 17 HAL services and `android.hardware.bluetooth` is not among them,
there is no bluetooth apex, and `service list` registers no bluetooth service. A USB dongle
does not help — Waydroid is an LXC container sharing the host kernel, so the host would see
the dongle fine, but there is nothing on the Android side for it to bind to. Do not re-derive
this; the answer is no.

`GossipPresence.met()` only fires on a real BLE exchange, so `NearbyContacts` renders empty in
Waydroid no matter what. Seeing the "also here" line there needs a `BuildConfig.DEBUG`-guarded
seeding intent modelled on `handleHandoverDebugIntent` (`MainActivity.kt:169-190`). Offered,
deliberately not written — it is diagnostics, and would have to stay off `main`.

### 8 GB is the binding constraint

Firefox + a booted Android container + a Gradle daemon do not fit. Measured: a full
`assembleDebug` with the session up took a **global OOM** that killed two container processes
and then the Gradle daemon at 1.45 GB RSS, surfacing as *"Gradle build daemon disappeared
unexpectedly"*. Two mitigations, both in place:

- `free_ram_for_build()` in `st2s` stops the Waydroid session around a build and restarts it
  after. `ST2S_KEEP_SESSION=1` skips it.
- `~/.gradle/gradle.properties` sets `kotlin.compiler.execution.strategy=in-process`, which
  collapses two JVMs (daemon 1.4 GB + Kotlin daemon 0.9 GB) into one bounded heap. It lives in
  `GRADLE_USER_HOME` **on purpose**: values there beat the project's, so CI runners keep the
  committed `-Xmx2048m` while this box gets settings that fit 8 GB.

The real fix is the RAM upgrade already noted in `CLAUDE.md` (2× DDR4 SO-DIMM, ~EUR 25).

### The Pi is now a BLE peer

`pinet` has a BCM43455 (WiFi and Bluetooth on one chip). Its controller was dead on arrival
and is now fixed permanently.

**Root cause.** `/proc/device-tree/soc/serial@7e201000/bluetooth/local-bd-address` is six zero
bytes on this board — the Pi firmware never filled it in. So `btbcm` fell back to Broadcom's
placeholder `43:45:C0:00:1F:AC`, set `HCI_QUIRK_INVALID_BDADDR`, and the kernel parked the
controller as `HCI_UNCONFIGURED`. Nothing was faulty; the radio simply had no identity. The
symptoms all follow from that and are individually misleading:

```
hciconfig hci0        -> DOWN RAW, ACL MTU 0:0
hciconfig hci0 up     -> Can't init device hci0: Operation not supported (95)
bluetoothctl show     -> No default controller available
btmgmt info           -> Index list with 0 items      # it is on the *unconfigured* list
btmgmt config         -> Unconfigured controller, missing options: public-address
```

Firmware *did* load at boot (`BCM4345C0 … Patch`, build 0382) and rfkill was clear
(`soft=0 hard=0`) — both dead ends, do not chase them again.

**Fix.** `/etc/systemd/system/bt-bdaddr.service`, enabled, hands it
`B8:27:EB:7E:4B:0D` at boot — the Raspberry Pi OUI plus this board's serial-derived suffix,
one past `eth0`'s. A BD_ADDR is a different address space from an Ethernet MAC, so it cannot
collide on the LAN. `bluetoothd` sees the index-added and powers the controller on by itself.
Verified: `UP RUNNING`, ACL MTU 1021:8, roles central **and** peripheral, 13 devices in a 10 s
scan. **The WiFi uplink is unaffected** — different driver (`brcmfmac` over SDIO vs `hci_uart`
over UART) despite the shared silicon.

**Driving it** (all need `sudo`, and `--index 0`):

```
btmgmt --index 0 add-adv -u 7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7721 -c -g 1   # advertise gossip
btmgmt --index 0 rm-adv 1                                                  # stop
btmgmt --index 0 advinfo                                                   # instances
btmgmt --index 0 find -l                                                   # LE scan
```

**Two `btmgmt` gotchas that cost time.** It is interactive by default, so `btmgmt --help`
looks like a hang — it is sitting at its own prompt. And **never redirect its stdin from
`/dev/null`**: it runs a mainloop and exits on EOF *before the mgmt reply arrives*, so the
command silently does nothing and still exits 0.

### What the Pi peer confirmed, and what it did not

A peer advertising the gossip service UUID with **no manufacturer data** lands in exactly the
branch `GossipRadio.onScanResult` reserves for iPhones — `push(address, null)`. So the Pi is a
faithful stand-in for a *foregrounded* iPhone up to the point where a real GATT service would
be needed, with no crypto to reimplement.

Live off the Pixel, running the `gossip-ble-diagnostics` build 1.8.0.860:

```
17:47:16.069  saw a gossip radio (no token — an iPhone, or an android with nobody to advertise to)
17:47:17.402  a peer connected to our server
17:47:17.471  mtu negotiated to 517 (status=0), using 517
17:47:17.895  gossip push to a peer gave up in "challenge" (peer never resolved)
17:47:29.351  a peer disconnected from our server (status=0)
```

Scan filter matched, iPhone branch taken, connect and MTU negotiation both fine, and it gave up
exactly where it should — the Pi advertises the UUID with nothing behind it. **The Android
central path works end to end**, and the `why` strings on this branch name the stage correctly.

**This does not re-open the struck discovery hypothesis.** A control run with the same radio
advertising *without* the service UUID produced no `saw a gossip radio` line and no connection,
with the 60 s cooldown already expired — but that outcome is guaranteed by
`ScanFilter.setServiceUuid` and is a sanity check, not a finding. Discovery was already proven
not to be the problem by the iPhone connecting ~40 times in five minutes.

**The pairing prompt on the Pixel is not ours.** Both characteristics are declared
`PERMISSION_READ` / `PERMISSION_WRITE` with no encryption, so gossip never requires a bond. The
prompt and the subsequent `Detect bonding failure` lines come from Android's companion-device
layer (`CDM_BluetoothOobPairing*`) reacting to an unknown device, and changed nothing about the
test. Fortnite and Fitbit also wake up for stray BLE peers on that phone — ambient noise in any
capture, not evidence.

**What the Pi still cannot do.** It cannot answer the open iOS question, which is why the
iPhone's `batch` is empty — that is iOS app logic. What it *could* do, with work, is serve a
real gossip GATT service and push a well-formed Pass, which would exercise the Android
**server** side (`deliver()`'s four drop reasons and the accept line) without needing a second
phone. That harness is not written.

## Getting a live log off the Pixel, without a PC

Superseded by `st2s plog` above whenever the box is available. Still the right route with no PC
to hand. The bug report dance is superseded by both. **Shizuku + `rish` in Termux** gives a
uid-2000 shell on the phone itself, which is `adb shell` in everything that matters here:

1. Shizuku app → start via **wireless debugging** (its own pairing flow works; `adb pair` from
   Termux does not — see the dead end below).
2. In Shizuku, **Download `rish` files**. They land in `Download/`, possibly in a subfolder.
3. In Termux, copy both with absolute paths — Termux's `~/storage/downloads` symlink is not
   always where the files are:

   ```
   cp /storage/emulated/0/Download/rish/rish /storage/emulated/0/Download/rish/rish_shizuku.dex ~/
   chmod +x ~/rish
   ```

4. `./rish` → a `cheetah:/ $` prompt. On Android 14+ it prints *"app_process cannot load
   writable dex. Attempting to remove the write permission…"* — benign, it proceeds.
5. Then, live:

   ```
   logcat -v time GossipRadio:I GossipService:I '*:S'
   ```

**Dead end, do not retry.** On-device `adb` cannot bootstrap itself. `adb tcpip 5555` is sent
*through* an existing authorised connection and fails with `no devices/emulators found`.
`adb pair` fails with `protocol fault (couldn't read status message): Success` because bare
`localhost` defaults to 5555 (nothing listening), Termux has no mDNS resolver for `.local`,
and the pairing port is single-use and dies with the dialog. Split-screen and `ADB_MDNS=0`
both failed.

**Capture for at least three minutes.** `GOSSIP_PEER_COOLDOWN` is 60 s, so a 33-second window
showing no push is not evidence of anything. Force-stop the app first.

## Next steps

1. **Install the branch APK on the Pixel.** From the box this is now just `st2s push` — build
   and install in one step, no CI round trip. The APK on the phone as of 2026-09-09 15:48 is
   1.8.0.860 off `gossip-ble-diagnostics`.

   Without the box: every push to every branch builds one — Android CI, artifact `app-debug`,
   7-day retention. `gh run list --branch gossip-ble-diagnostics` then
   `gh run download <id> -n app-debug`, or from the run page in a browser (the GitHub mobile
   app cannot download artifacts). **The nightly.link and run URLs in earlier versions of this
   doc are stale and 404** — artifact ids change every run, so never hard-code one here again.

   Same committed debug key throughout, so any of these install over the top and keep app data
   and Contacts.

2. **Run the fresh-gig test** described under *Still unexplained*: a new gig dated today on the
   iPhone, checked into, both apps in the foreground, `rish` logcat running on the Pixel.

3. **Read the answer off one question now, not two.** Android → iOS is settled (below). What is
   left is whether the iPhone ever writes: look for `accepted a pass of N message(s) from a
   contact`, or for one of the four drop reasons — unreadable pass, key not a contact,
   undecodable proof, failed possession proof. Silence on all five, with
   `a peer read our challenge` still appearing, means `batch.isEmpty` again and the work is in
   `GossipLedger`.

   The on-screen relay panel (this branch) shows the same thing without a terminal: live
   connections, the last event with an age, and the gate reason when the relay is off.

4. **Branch split — done, and both PRs are open.** `gossip-write-limit` (`566ab67`) carries the
   512-byte fix and its test alone: **PR #438**. The presence work — `gossipNearby`,
   `GossipPresence`, the notification and the "also here" line — is product rather than
   diagnostics and went out as **PR #439** (`gossip-presence`, `7d13933`). Both await review.
   The logging and the relay panel stay on `gossip-ble-diagnostics` and are **not for `main`**.

   Note the presence work has still not been *seen* working on a phone, because
   `GossipPresence.met()` only fires on a real BLE exchange and Waydroid has no radio. Its
   policy is covered by unit tests; the rendering is not.

5. **Unblocked by the rig, if it is worth doing.** A gossip GATT service on the Pi would let
   the Android accept path be tested without a second phone — see *What the Pi still cannot
   do*. Worth it only if the iOS side stays unfixable for a while; the fresh-gig test in step 2
   is cheaper and answers the live question.

## Files that matter

| Thing | Where |
| --- | --- |
| Android BLE, both roles, and the fix | `android/app/src/main/java/io/github/magnusencoded/stationtostation/ble/GossipRadio.kt` |
| Android service, relay decision, accept path | `android/.../data/gossip/GossipService.kt` |
| iOS BLE, both roles | `ios/StationToStation/Data/Gossip/GossipTransport.swift` |
| Token derivation, both platforms, identical | `.../data/gossip/GossipToken.kt`, `.../Data/Gossip/GossipToken.swift` |
| Accept/relay rules | `android/.../data/GossipStormGate.kt` |
| Wire grammar and the regression test | `android/app/src/test/.../GossipWireTest.kt` |
| The LAN path, which is *not* gossip | `android/.../data/ContactReconcile.kt` |
| The design this all serves | `docs/adr/0019-gossip-channel-background-carve-out.md` |

Rig files, deliberately **not** in the repo — they are machine-local to the CachyOS box and to
pinet, and belong to neither `main` nor this branch:

| Thing | Where |
| --- | --- |
| The build/install/log loop | `~/.local/bin/st2s` on the CachyOS box |
| Memory settings that keep a build from OOMing | `~/.gradle/gradle.properties` on that box |
| The Pi's BD address fix | `/etc/systemd/system/bt-bdaddr.service` on pinet |

Issues: #416 (Android gossip transport), #417 (iOS), #415 (the ADR).

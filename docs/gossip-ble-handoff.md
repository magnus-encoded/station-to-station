# Handoff: gossip check-ins do not cross between the Pixel and the iPhone

**Status:** Android → iOS is fixed and **confirmed on hardware**. iOS → Android is narrowed to
one line of Swift and is neither confirmed nor fixed.
**Written:** 2026-09-09, out of a debugging session on `gossip-ble-diagnostics`. Updated the
same day with the live-capture results.
**Branches:** `gossip-write-limit` (`566ab67`) is the fix alone and is **PR #438** to `main`.
`gossip-ble-diagnostics` carries the logging, the on-screen relay panel, and the presence work.
**Constraint from the user:** the logging **stays off `main`**. Honoured — see *Next steps* for
the three-way split, including which part of this branch *is* product and should follow.

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

## Getting a live log off the Pixel, without a PC

The bug report dance below is superseded. **Shizuku + `rish` in Termux** gives a uid-2000
shell on the phone itself, which is `adb shell` in everything that matters here:

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

1. **Install the branch APK on the Pixel.** Every push to every branch builds one — Android CI,
   artifact `app-debug`, 7-day retention. Get the current one with
   `gh run list --branch gossip-ble-diagnostics` then
   `gh run download <id> -n app-debug`, or from the run page in a browser (the GitHub mobile
   app cannot download artifacts). **The nightly.link and run URLs in earlier versions of this
   doc are stale and 404** — artifact ids change every run, so never hard-code one here again.

   Same committed debug key as the current build, so it installs over the top and keeps app
   data and Contacts.

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

4. **Branch split — done.** `gossip-write-limit` (`566ab67`) carries the fix and its test alone
   and is open as PR #438. The logging and the relay panel stay here and are not for `main`.
   The presence work — `gossipNearby`, `GossipPresence`, the notification and the "also here"
   line — is product rather than diagnostics, is isolated in `2610710` with policy tests, and
   should go to `main` as its own PR once it has been seen working on a phone.

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

Issues: #416 (Android gossip transport), #417 (iOS), #415 (the ADR).

# Handoff — #417, Gossip transport (iOS background BLE)

Written 2026-09-08, mid-issue, for whoever picks this up next.

## Where it is

| | |
| --- | --- |
| Branch | `gossip-transport-ios-417` |
| Pushed to origin | **Yes**, tracking `origin/gossip-transport-ios-417` |
| Worktree | `/home/dizzi90/Documents/station-to-station/.claude/worktrees/agent-gossip-ios-417` |
| Based on | `a26db17` (`origin/main` at branch time) |
| CI | **Green.** Run `34210345894` — unit tests passed, unsigned Debug `.ipa` built and uploaded as `StationToStation-ipa` |
| PR | **Not opened yet.** This is the main thing left. |

Two commits: `513c786` (the work) and one small follow-up lifting an `await` out of an
`XCTAssert` autoclosure, which was the only thing CI caught.

The worktree was created by hand (`git worktree add`), because `EnterWorktree` refuses to run
from a subagent with a cwd override. Use absolute paths; a fish shell here needs globs quoted
(`--include='*.swift'`).

## What is implemented

### New, `ios/StationToStation/Data/Gossip/`

| File | What it is |
| --- | --- |
| `GossipToken.swift` | The rotating per-**Contact** token: bucket arithmetic, HMAC derivation, the offer a device publishes, and resolution of an offer back to one Contact. Pure. |
| `GossipWire.swift` | The cross-platform byte contract — service and characteristic UUIDs, the token-offer and batch encodings, and `gossipCheckInMessage` which mints this device's own signed check-in. Pure. |
| `GossipBudget.swift` | The per-peer rate bound the storm gate explicitly delegates: cooldown, rolling hourly ceiling, pruning. Pure. |
| `GossipLedger.swift` | `actor` over one JSON file: the seen set, held messages with per-Contact delivery marks, and per-Contact budgets. Pruned on every read and write. |
| `GossipChannel.swift` | `actor`, the composition root. Holds the per-Contact ECDH secrets (memory only), and is the only thing the radio may ask questions of. Calls `gossipAdmit`, then `gossipStormGate`, then the ledger. |
| `GossipTransport.swift` | CoreBluetooth central + peripheral, background state restoration, the three-step handshake. Judges nothing. Its file header is the written-out statement of what iOS background delivery actually is — read it before believing a bug report. |

### Modified

| File | Change |
| --- | --- |
| `Data/Exchange/ContactIdentity.swift` | Added `sharedSecret(with:)` — raw ECDH over the two identity keys via `SecKeyCopyKeyExchangeResult(.ecdhKeyExchangeStandard)`. |
| `App.swift` | An `AppDelegate` via `@UIApplicationDelegateAdaptor`, for one line: `GossipTransport.shared.wakeAtLaunch()`. It must be there and nowhere else — see gotchas. |
| `AppModel.swift` | `gossipContactsChanged()` (the channel's whole lifecycle), called from the friend writes and from `loadPlannedGigs`; `checkIn` now also mints the signed check-in into the channel. |
| `ios/project.yml` | `UIBackgroundModes: [bluetooth-central, bluetooth-peripheral]`, and the usage-description comments rewritten so "foreground-only" is now said per-radio rather than across the board. |
| `docs/adr/0019-…md` | A dated amendment (see below). |
| `CONTEXT.md` | A **Gossip** subsection under *People and exchange*: **Gossip**, **Gossip token**, **Storm gate**. |

### Tests, `ios/StationToStationTests/`

`GossipTokenTests`, `GossipWireTests`, `GossipBudgetTests`, `GossipLedgerTests`. All pass on CI.
They follow `GossipStormGateTests`' conventions: real P-256 identities, `now =
Date(timeIntervalSince1970: 1_788_555_600)`.

## What is left

1. **Open the PR against `main`** with `Fixes #417` in the body. Nothing else is blocking it.
2. **Acceptance criterion (c) — a real two/three-device relay test — has not been done and
   could not be by me.** No local Xcode; the phone's installed build predates all of this.
   Someone with two devices has to sign the CI `.ipa` (`~/.local/bin/iloader.AppImage`,
   `usbmuxd` running, paired over USB) and watch a check-in hop. Simulators cannot exercise
   background BLE at all. Do not mark the issue verified without this.
3. **Tell #416 about the contract.** The ADR amendment is written so its author can match it
   without reading Swift; the fixed token vector is the thing to check first.

## Decisions a new agent must not silently re-decide

All of these are now written into the ADR-0019 amendment (2026-09-08), which is the document to
argue with rather than the code.

- **Nothing from #410 was reimplemented or modified.** `Data/GossipStormGate.swift` is
  untouched. TTL (`gossipMaxLifetime`, the night-end ceiling), the dedup key (`messageId` =
  SHA-256 of the canonical payload), the signature scheme (P-256 / SHA-256 / DER over
  `gossipPayloadV1`) and `gossipMaxBatch` are all its values, used as-is. If its interface had
  not fit, the instruction was to stop and flag — it fit.
- **Token derivation:** `HMAC-SHA256(ECDH(mine, theirs), "station-to-station/gossip-token/1" +
  "\n" + bucket)`, first 16 bytes, lower-case hex; bucket = `floor(unix_seconds / 900)`. A
  writer publishes its current bucket, a reader accepts ±1. Raw ECDH (bare X, no KDF) —
  matching Java's `KeyAgreement("ECDH")`; an X9.63 KDF on one side only would make two phones
  that never recognise each other, silently. Floor rather than truncate, because both languages
  divide toward zero and would disagree before 1970.
  **Fixed vector:** secret `00 01 … 1f`, bucket `1987284` → `cd60b9fef6f960c7b6803f1b45608f0f`.
  This is asserted in `GossipTokenTests` and must be asserted identically on Android.
- **The token cannot ride the iOS advertisement** — `startAdvertising` honours only a local
  name and a service UUID list. It rides the first GATT read instead. The derivation is what
  must match across platforms; the carrier is allowed to differ.
- **Write framing is append-and-terminate**, not offsets: sequential chunked writes at offset 0,
  ended by a zero-length write. CoreBluetooth performs no prepared writes and silently
  truncates past the MTU. Reads *are* offset-addressed (long reads, `sliceForOffset`). Android
  must chunk its writes the same way or an iPhone sees only the last chunk. Written up in
  `GossipWire.swift`'s header.
- **Store-and-forward until expiry**, not relay-once-at-acceptance. The storm gate names this as
  the transport's call; on iOS the acceptance moment is almost never a moment the target Contact
  is in range.
- **Per-peer rate bound:** 30 s cooldown, 240 offered messages per rolling hour, charged on
  messages *offered* rather than accepted, keyed by resolved Contact key (never the BLE address,
  which rotates).
- **Lifecycle:** the gossip radio runs whenever the device holds ≥1 Contact, and never
  otherwise. Not screen-scoped, not gig-scoped. The `gossip.hasContacts` `UserDefaults` flag is
  load-bearing, not a cache — see gotchas.
- **No user-facing on/off switch was built.** Deliberate, and flagged as an open item in the ADR
  amendment: it is a product question, and inventing one here would have been scope creep.

## Gotchas

- **The `CBCentralManager`/`CBPeripheralManager` must be constructed inside
  `didFinishLaunchingWithOptions`, synchronously.** That is the only moment iOS hands back a
  restored background session. Move it into `AppModel.init`, a `.task`, or anywhere lazier and
  `willRestoreState` never arrives; the feature then silently degrades to "works while the app
  is open", which is exactly what ADR-0019 exists to avoid — and nothing fails visibly.
- **`gossip.hasContacts` in `UserDefaults` gates the whole thing at launch.** The contact list
  is behind an `async` cache load and is not readable that early. It also keeps a phone that has
  never met anybody from constructing a manager at all, which is what stops the Bluetooth
  permission prompt from moving off the Exchange screen.
- **A backgrounded iPhone is invisible to an Android scanner.** iOS drops the local name and
  moves the service UUID into an overflow area only iOS reads. iOS↔Android gossip works when the
  iPhone is scanning or is in the foreground. This is a product fact, not a bug to fix.
- **Do not rename the restore identifiers.** `…gossip.central` / `…gossip.peripheral` are names
  iOS holds across launches; changing one drops whatever session it was holding.
- **`GossipRelay` is already taken** — it is a struct in `GossipStormGate.swift`. The
  composition root is `GossipChannel` for that reason.
- **`CONTEXT.md` will likely conflict with #416.** Both halves of this pair were told to add the
  gossip vocabulary and #410 did not. Same for the ADR file if the Android agent also amends it.
  Merge, do not overwrite.
- **The repo has `core.autocrlf=true`** (files came off Windows). The CRLF warnings on every
  commit here are expected.
- **Live-device log capture was blocked.** `usbmuxd` is up and the iPhone
  (`00008110-001671043C00A01E`) is attached, and `idevicesyslog` is installed, but the capture
  command was refused by the permission classifier. Judged non-blocking: the existing Exchange
  BLE behaviour is fully readable from `BleExchange.swift`, and the build on the phone predates
  every line of this work, so a syslog of an in-progress Exchange would have said nothing about
  the gossip layer. If someone wants it, add a Bash permission rule first.

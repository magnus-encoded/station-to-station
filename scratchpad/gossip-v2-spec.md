Parent: #408. This spec consolidates #442, #443, #444, #445, #446, #448 and #415 into the finished state of public gossip v2. Where an older issue disagrees with this spec, this spec wins. The dated decisions are listed under **Decisions already made**.

> **Code map note.** The line spans below are valid at commit `71efa2f` on `gossip-ble-diagnostics`. Spans marked `~` are approximate. Lines drift, so find a symbol by name and use the span as a hint. `A` = `android/app/src/main/java/io/github/magnusencoded/stationtostation`, `I` = `ios/StationToStation`.

## Problem Statement

At a concert, I want the people around me, and the **Contacts** I know, to see what is being played and who is here, **without a signal and without a server**. Today, most of this works in unit tests and in CI. But the parts that make it real are unfinished or unverified:

- Nothing ever authors a **receipt**, so routing never learns which neighbours are useful.
- No iPhone has ever carried a **Fact** while locked.
- A phone has never sent its own check-in request as the BLE central.
- The rules for when my edits go public are not settled.

I also lose trust when the app loses my handwritten **Log** lines. Today, combining two **Gigs** for one night silently keeps only the longer **Log**.

## Solution

When I **Check in** at a **Gig**, my phone joins **Gossip** for that night. Nothing else is needed: there is no sharing toggle, and there are no Contacts to pick.

- My check-in is a signed request. A checked-in phone next to me witnesses it automatically. The witness travels hop by hop and shows as "checked in · witnessed" to anyone whose phone receives it.
- My **Log** lines become public **Facts** when I press Done.
- Any Station to Station device in range can **Carry** my Facts as a **Blind relay**. Only people holding my **Card** can tell they are mine. I can **Block** an author on my own device.
- My participation ends 30 minutes after I complete the Log, and never later than 06:00. It also ends when I stop it. If I reopen the Log, it resumes until 06:00.
- If no Gig is active, the radio is off.
- Routing slowly prefers neighbours that proved useful, but never excludes the others.
- It works the same on Android and iOS. It is proven on real phones, including a locked iPhone, with measured battery cost.

## User Stories

### Check-in and witness (#442)
1. As a concertgoer, I want Check in to start gossip immediately, so that I don't have to find a separate sharing setting.
2. As a concertgoer, I want to capture my Log right after I check in, even when nobody is nearby, so that a missing witness never blocks me.
3. As a concertgoer, I want a checked-in phone next to me to witness my check-in automatically, so that my presence has evidence stronger than my own claim.
4. As a concertgoer, I want to see "checked in" and "checked in · witnessed" as different states, so that I can tell self-assertion from evidence.
5. As a concertgoer, I want someone's check-in to reach me when only its witness reaches me, so that I see arrivals that happened outside my radio range.
6. As a concertgoer, I want the same arrival to appear once, whether I received the request, the witness or both, so that the list is not inflated.
7. As a privacy-conscious user, I want my request to travel only one hop, so that strangers far away cannot relay my unwitnessed claim.
8. As a concertgoer, I want my phone to witness others only when I also checked in at the same Gig, so that witnesses mean shared presence.
9. As a user on either platform, I want my phone to send my request when it connects as central, so that check-in works no matter which side starts the connection.

### Publication and projection (#443)
10. As a concertgoer, I want a Log line to become public only when I commit it, so that half-typed words never leave my phone.
11. As a concertgoer, I want a correction to replace my earlier public line, keeping its history, so that the newest version shows and nothing is silently rewritten.
12. As a concertgoer, I want other people's Log lines shown inline in my Log view with attribution, so that I can build the setlist together with the room.
13. As a concertgoer, I want received lines never republished as mine, so that **Carrying is not authoring**.
14. As a concertgoer, I want received Facts never to change my own capture state or timers, so that other people cannot keep my radio on.
15. As a Contact, I want to recognise my friend's Facts after an Exchange, even for Facts I received earlier, so that attribution catches up.
16. As a Contact, I want to keep recognising someone's authorship after I remove them as a Contact, so that recognition is honest about what cannot be revoked.
17. As a stranger relay, I want to carry Facts whose author I cannot see, so that news crosses the room.
18. As a user, I want to Block an author so their Facts never enter my record, while my phone still carries them for others, so that my blocks are invisible and do not break the network.
19. As a user, I want a block on the person checking in to also hide their arrival inside someone else's witness, so that blocks cannot be bypassed by relaying.
20. As a user whose Gig later gets a setlist.fm id, I want my public Facts to keep matching that Gig, so that adoption does not orphan my Log.
21. As a user, I want edits made after the night ends to stay local, so that late corrections do not re-enter a closed channel.
22. As a user, I want **every handwritten Log line kept** when two Gigs for one night combine, so that the highest-grade data is never lost.
23. As a user, I want combining or handing over the same Log twice to add nothing new, so that repeated handovers do not duplicate lines.

### Participation lifecycle (#448)
24. As a concertgoer, I want gossip to continue for 30 minutes after I complete my Log, so that an encore and late arrivals still reach me.
25. As a concertgoer, I want the grace period capped at 06:00, so that gossip never runs into the next day.
26. As a concertgoer, I want an encore Fact received during grace to appear inline without reopening my Log or resetting timers, so that incoming traffic cannot extend my participation.
27. As a concertgoer, I want reopening a completed Log to resume gossip until 06:00, so that I can keep correcting during the night.
28. As a user, I want the radio completely off when no Gig is active, even when I still hold Envelopes, so that background battery use has a clear boundary.
29. As a user at a festival, I want another active Gig to keep the radio on without relaying the ended Gig's Envelopes, so that expired nights do not ride another session.
30. As an Android user, I want the notification's stop action to end participation, so that I have a user-facing off switch.
31. As an iOS user, I want a stop in the app that ends participation the same way, so that both platforms honour stop.
32. As a user, I want stopping the radio never to delete the Facts I received, so that my night's record stays.
33. As a user, I want my Facts and my grace deadline to survive an app restart, so that a crash doesn't end my night early.
34. As a concertgoer, I want the Gig screen to show the actions for my current stage (planned today, at the venue, checked in and capturing, set complete), so that the next thing is always the obvious thing.

### Routing and usefulness (#444, #445)
35. As a Contact, I want my phone to send a receipt when it promptly recognises a received Fact as my Contact's, so that the delivering neighbour gets credit.
36. As a relay, I want only the neighbour that delivered a Fact directly to get credit, so that routing evidence is local and honest.
37. As a relay, I want attribution learned later, after an Exchange, not to count as fast delivery, so that credit is not faked.
38. As a relay, I want useful neighbours offered first, with random tie-breaks, so that routing improves but no single neighbour wins.
39. As a relay, I want neighbours without credit still offered, so that preference never becomes exclusion.
40. As a relay, I want receipts never to retire an Envelope or bypass the **Storm gate**, so that receipts cannot be used to censor.
41. As a maintainer, I want the carry window, the usefulness decay and the grace period to stay separate settings, so that tuning one does not quietly change another.
42. As a maintainer, I want each parameter marked provisional or settled, citing its evidence, so that nobody quotes a simulation as a measurement.

### Device verification (#446)
43. As an iPhone user, I want my locked phone to receive a Fact, so that the app works in my pocket.
44. As an iPhone user, I want my locked phone to forward that Fact to a newly met, different peer, so that relays work through iPhones.
45. As a user, I want two backgrounded iPhones to exchange Facts, so that iOS-only crowds work.
46. As a user, I want Android and iOS to exchange authored and relayed Facts, and requests with witnesses, both ways, so that mixed crowds work.
47. As a user, I want Facts to persist and show after a restart on real devices, so that on-device behaviour matches the unit tests.
48. As a user, I want the screen-off battery cost measured in mAh for three states (gossip off, active with no peers, active with traffic), so that I can trust the app in my pocket.
49. As a maintainer, I want malformed frames over real ATT rejected, and EATT interop checked, so that radio edge cases are proven, not assumed.

### Records (#415)
50. As a future contributor, I want the ADRs and `CONTEXT.md` to describe what shipped, with the old decisions kept as history, so that I can trust the docs.

## Implementation Decisions

### Decisions already made
- **Check in** is the consent to join gossip and the start of participation. There is no separate toggle.
- **Done** is the publication boundary. What happens to edits made during participation *before* Done is **still open**. Today, every in-participation `writeLog` publishes. The user must decide between publishing immediately and waiting for Done. `GossipLifecycleTest` has an `@Ignore`d case for this.
- **Reopening** a completed Log resumes participation until the 06:00 cutoff (decided 2026-09-15).
- **Grace** is 30 minutes after completion, capped at 06:00. This is a provisional product choice, not a battery figure. Incoming traffic never extends it.
- **No active Gig means no radio**, including blind relay. A planned Gig tonight is not enough. The user can stop participation on both platforms.
- **Combining two Gigs** whose Logs were both published gets no gossip fix (decided 2026-09-15). The losing Gig's Facts stay under its old id until they expire. Adoption through `adoptSetlistId` is the only combine path. `mergeGigs` has no caller in the app.
- **Handwritten Log data is never dropped** (user rule, 2026-09-15). Combining two Logs is a sequence alignment: matching entries appear once, and every unmatched entry from both sides is kept, in order. Survivor lines keep their line numbers, and added lines get fresh ones. The combine is idempotent. The fix is on the local branch `prep/log-merge-no-drop` (`StoredLog.absorbing`).
- **Receipts** are routing evidence only. Credit is a binary, decaying boost for the neighbour that delivered a Fact directly. Ties are broken randomly. Receipts never exclude a neighbour, never retire an Envelope and never affect the Storm gate. Index credit by neighbour, not by Envelope id: an earlier attempt indexed Envelope ids, so avoid that.
- Usefulness work (#444) comes **after** the locked-iPhone viability experiment (#446 priority 1).
- The 15-minute carry window and the 2-minute usefulness decay are **provisional**. `sim/SWEEPS.md` is simulation, not measurement. No hourly battery budget is accepted.
- No media or **Notes** travel over gossip. There is no build attestation.

### Wire and state (pure core; both platforms mirror each other)
- `GossipEnvelope`: the signed **Fact**. It carries `gigId`, cumulative `formerIds` (at most 32), scope, temporary author key, times, kind (`log | request | witness | receipt`), line, text and sealed attribution.
  - Android: `A/data/gossip/PublicGossip.kt:81-121` (`valid` 101-119, `sameGig` 120).
  - iOS: `I/Data/Gossip/PublicGossip.swift:31-81`.
- `PublicGossipState` is the single decision point: the **Storm gate**, projection and offers.
  - Android: `PublicGossip.kt:164-277`.
    - `receive` 194-212: admission, one-hop controls, receipt → `useful`.
    - `offer(peer, now, participationEnds)` 213-222: **must start ranking by usefulness, with random ties**.
    - `project` 224-231; `arrivals` 240-247; `checkInEvidence` 250-256; `recognizeContacts` 178-187; `isBlocked` 175.
  - iOS: `PublicGossip.swift:127-252`.
    - `receive` 158-177; `offer` 178-187; `project` 191-201; `arrivals` 209-222; `checkInEvidence` 225-231.
- One-hop signing rules:
  - Android: `passAuthor`/`passBatch` at `PublicGossip.kt:290-296`; `witnessRequest` 299-313.
  - iOS: `PublicGossip.swift:259-284`.
- Log diff to Facts: `gossipLogChanges` (Android `PublicGossip.kt:316-324`, iOS `PublicGossip.swift:286-292`). Display alignment: `weaveGossip` (Android 330-344, iOS 302-315).
- Attribution:
  - `GigIdentity`: Android `A/data/gossip/GigIdentity.kt:19-42`, iOS `I/Data/Gossip/GigIdentity.swift:6-49`.
  - Recognition: `recognizeGossip` (Android 50-61, iOS 56-62).
- **New receipt authoring**: a pure function next to `recognizeContacts` builds a `receipt` Envelope when a newly admitted Fact is recognised within the prompt window. `receive` then records the delivering neighbour per Fact. The neighbour handle must work across platforms, and a MAC address does not. This is still open research: use a per-meeting challenge identity and record the choice.

### Policy and lifecycle
- Participation deadline:
  - Android: `gossipParticipationUntil` at `A/data/gossip/GossipPolicy.kt:60-67`; night end 84-87; tonight 97-104.
  - iOS: `I/Data/GigTimeState.swift:200-205`.
- Per-Gig deadlines fed into `offer`:
  - Android: `gossipParticipationEnds` at `A/data/gossip/GossipService.kt:371-387`.
  - iOS: `GigTimeState.swift:209-222`.
- Stop:
  - Android: `GossipStore.stopParticipation` at `A/data/gossip/GossipStore.kt:46`, triggered by `ACTION_STOP` in `GossipService.onStartCommand` (~99-127).
  - iOS: `GossipTransport.stopParticipation` at `I/Data/Gossip/GossipTransport.swift:181-185`.
- Line identity: `StoredLog.completing`, `lineNumberAt`, `adding`/`removing`.
  - Android: `A/data/Bill.kt:175-295`.
  - iOS: `I/Data/StoredLog.swift:27-173`.

### Persistence and author scope
- Android `GossipStore` (`A/data/gossip/GossipStore.kt:33-84`): `authorScope(localGigId)` 51-67 is persisted per local Gig and survives adoption. `updatePublic` 76-83 is the transactional state write.
- iOS `GossipLedger` actor (`I/Data/Gossip/GossipLedger.swift:30-180`): `authorScope` 49-58, `receivePublicFacts` 75-83, `blockAuthor` 93-99, persistence 139-167.
- Combining Gigs:
  - Android `A/data/TimelineStore.kt`: `adoptSetlistId` 931-945, `merging` 1312-1342, `unionLog` 634-637, which is replaced by `StoredLog.absorbing`.
  - iOS `I/Data/TimelineStore.swift`: `adoptSetlistId` 970-981 **does not combine**, and `mergeGigs` 990-1025 did not fold `gigLogs`. **Bring iOS adoption to parity with Android**: a second Gig holding the setlist.fm id combines the two, and nothing is lost.

### Radio and transport
- Android radio, `A/ble/GossipRadio.kt`:
  - `GossipPeripheral` 136-360: challenge read 175-199, write reassembly 201-239, advertising 316-340.
  - `GossipCentral` 363-618: push 460-617, chunked `writeNext` 607-615.
- Android service, `GossipService.startRadio` 140-233:
  - Delivery → receive → recognise → auto-witness 170-183.
  - Central `publicPassFor` → offer 190-200.
  - **Neighbour priority for which peer to connect to belongs here**, reading `GossipPresence` (`A/data/gossip/GossipPresence.kt:29-41`).
- iOS radio, `I/Data/Gossip/GossipTransport.swift:108-571`: scan 236-244, advertise 246-265, `beginMeeting` 275-288, `send` 302-317, central delegate 339-389, peripheral-manager writes 519-570.
- iOS channel, `GossipChannel` (`I/Data/Gossip/GossipChannel.swift:23-225`): `checkedIn` 119-135 authors the request, `publishLog` 137-153, `publicPass` 161-177, `receivePublic` 179-195 (auto-witness 185).
- iOS budgets: `I/Data/Gossip/GossipBudget.swift:57-113`.

### App layer
- Android `A/AppViewModel.kt`:
  - `writeLog` 2178-2187 and `publishLog` 2189-2217: eligibility, monotone revision and `formerIds`. **The Done-vs-immediate decision lands here.**
  - `checkIn` 2443-2456, `gossipAbout` 2478-2498 (authors the request), `blockGossip` 2135-2139, `adoptSetlistLink` 2340-2364.
- iOS `I/AppModel.swift`: `writeLog` 2004-2022, `checkIn` 1636-1664, `blockGossip` 1986, `adoptSetlistLink` 690-708.
- UI:
  - Android `A/ui/StationScreen.kt:3986-4030`: Log section, arrivals, woven rows, attribution with Block. `CheckInDialog` 1260-1288.
  - iOS `I/UI/GigView.swift:94-128` (gossip section) and `gossipAttribution` 455-466.
  - The stage affordances from #448 extend these screens. Do not add a second lifecycle state machine.

### Records
- Update `docs/adr/0019-gossip-channel-background-carve-out.md`:
  - §6 is stale.
  - "No user-facing off switch" is wrong, because Android's notification stop is one.
  - §4 still says "offered to every Contact".
- Update `docs/adr/0021-public-gossip-facts.md`: "measured" should read "simulated".
- Update `docs/gossip-public-wire.md`: "random ties" is not implemented yet.
- Update `CONTEXT.md`: receipts have no production emitter yet.
- Document the three separate lifetimes: Envelope expiry at 06:00, the carry window, and seen ids outliving eviction.
- Document that a stranger can read the BLE challenge.
- Keep the original decisions as dated history.

## Testing Decisions

- **Good tests** check external behaviour through the highest seam: given Facts, a clock and Gig state, what is admitted, shown, offered and kept. They never inspect private maps or radio callbacks.
- **Seam 1, the primary seam: `PublicGossipState` plus `gossipParticipationUntil` with an injected clock.** It covers the Storm gate, projection, arrivals, blocks, receipt authoring and credit, offer ranking (ties must be seedable), per-Gig cutoffs, grace, 05:59/06:00, encore and reopen. Existing tests to follow:
  - Android: `PublicGossipTest.kt` (14), `GossipLifecycleTest.kt` (8), `GossipPolicyTest.kt` (11).
  - iOS: `PublicGossipTests.swift`, `GossipLifecycleTests.swift`, `GigTimeStateTests.swift`.
  - Every case on one platform needs a matching case on the other.
- **Seam 2: stores across a restart.** It covers author scope, adoption, combining Gigs without losing a Log line, idempotent handover, and Facts plus grace surviving a restart. Existing tests:
  - Android: `PublicGossipStoreTest.kt`, `TimelineStoreTest.kt`, `LogTest.kt`, `HandoverTest`.
  - iOS: `GossipLedgerTests.swift`, `TimelineStoreTests.swift`, `StoredLogTests.swift`, `HandoverPlanTests`.
- **Seam 3: real radio, opt-in.**
  - Android `androidTest/GossipRadioDeviceTest.kt`, with `docs/prototypes/gossip_v2_peer.py` on the Pi as the BLE peer. The Pi's controller is currently down and needs a reboot.
  - The phone-to-phone and locked-iPhone checklists in #446 are manual protocols with recorded results. They are not CI tests.
  - Battery: screen-off mAh, baseline subtracted.
- Still missing at seam 1 or 2:
  - absent witness unlocks capture;
  - unfinished edits before Done (after the decision);
  - restart keeps grace at app level;
  - `ACTION_STOP`;
  - receipt authoring, credit and ranking;
  - iOS adoption parity.
- Simulation: `sim/` (17 tests) is for comparing policies, never for accepting a parameter.

## Out of Scope

- Guaranteed delivery, and always-on relay outside an active Gig.
- Media, **Notes** or **Reconcile** over gossip. Late corrections catch up through Reconcile, which is a separate feature.
- An automatic gossip fix for two authored Gigs combined on one device (decided: none).
- Choosing final values for carry, decay or grace. #445 keeps them provisional until device measurement.
- Hold to reorder Log lines, and checking for an existing Gig on Departures commits and Ticket adds (follow-ups outside gossip).
- Build attestation, blacklists or server-side anything (ADR-0003).

## Further Notes

- **pinet is critical path** for the development PC's network. Rebooting it to recover Bluetooth cuts the PC's internet, so ask first. Never touch its networking.
- iOS can only be built in CI from this Linux host. A green CI run is not device validation.
- Local branches still to review and land:
  - `prep/log-merge-no-drop`: the Log combine fix; idempotency in progress.
  - `prep/448-lifecycle-tests`: already folded into `71efa2f`.
  - `prep/443-gig-merge`: tests only.
- Progress log: `docs/gossip-v2-next-pass.md`.

# Gossip spec inputs (2026-09-15, branch gossip-ble-diagnostics)

Paths: `A=android/app/src/main/java/io/github/magnusencoded/stationtostation`, `I=ios/StationToStation`.

# PART A — Issue digest

## #408 Public blind-relay gossip (parent; keep OPEN)
- Fact = small self-contained public item, one Envelope per Fact; Pass carries several Envelopes, mixed authors. Kinds: request (check-in), witness, log, receipt.
- Content public; attribution sealed under a temporary per-Gig identity (survives gigId change, ends with Gig). Exchange = irreversible mask-off; Contact removal does not revoke recognition.
- Any STS device may Carry (blind relay); no Contact gating. Block = local admission only, never affects Carry/forwarding.
- Routing receiver-led, no inventory exchange. 1st receipt -> outbox; 2nd receipt closes Storm gate & removes; remember own (Envelope, neighbour) handoffs, no repeat.
- Envelope expiry = Gig night end (06:00). Local Carry window shorter (15 min provisional), may bin under pressure; seen-IDs kept until Gig end.
- Log replacement = complete assertion per (Gig, author, line); newest shown, all retained; per-author Logs stored separately, combined projection for display; author-local line numbers not global.
- `gigId` + cumulative `formerlyKnownAs` on Facts; prefer duplicate Gigs to automatic false merge; witnessing needs shared id.
- Unwitnessed request is one-hop/local; checked-in same-Gig device witnesses directly & automatically; witness Fact enters Gossip; direct sight stronger than relayed arrival (keep distinct).
- Delivery receipt = routing evidence only: binary useful-path boost, random ties, never exclusive, no Storm-gate effect, may be delayed by later Exchange.
- No media/Notes over gossip; no build attestation/blacklists; neutral size/time/storage bounds + aggregate diagnostics.
- Open research: receipt one-hop vs multi-hop; cross-platform neighbour handle (MAC not usable); default Carry window/fresh per relay; Spray-N token rule; adaptive fanout inputs.
- Status (next-pass.md 2026-09-15): relayed-witness arrivals projected on both platforms, dedup by claim ID, block on claimant suppresses; Exchange recognizes embedded claimant. 630-run sparse sweep in `sim/SWEEPS.md`; no routing defaults changed. Android 761 tests green; iOS CI green on 89bd25d. No hardware two-peer run (Pi controller down).

## #415 ADR/glossary alignment (docs)
- First pass #450 (`e761926`): ADR-0019 amended (struck, dated 2026-09-11), ADR-0021 reshaped, CONTEXT.md: Fact, Attribution, Blind relay, Block; Reconcile kept separate.
- Must align with #448: Check in = participation start, Done = publication, completion grace, no-active-Gig radio shutdown, 06:00 hard end; retain original decisions as history.
- Open 1: BLE challenge readable by unpaired strangers — disclosure not documented.
- Open 2: distinguish message expiry vs 15-min Carry window vs seen-IDs outliving eviction.
- Open 3: ADR-0019 "no user-facing off switch" is now wrong (Android notification stop -> `GossipStore.stopParticipation`; iOS `GossipTransport.stopParticipation`).
- Open 4: record #445 settled vs provisional parameters, #446 limits (plain ATT/BlueZ `Channels=1`, EATT unresolved).
- Stale: ADR-0019 §6 (Contact/held message keeps service alive, "nothing schedules shutdown"), §4 "offered to every Contact", ADR-0021 "measured in SWEEPS", wire spec "random ties" (not implemented), CONTEXT receipt has no production emitter.
- iOS background viability and battery must stay explicitly unverified.

## #442 Check-in request/witness
- Check in starts gossip participation and opens local capture immediately; no witness required; lack of peers never blocks recording.
- Witness evidence distinct from self-assertion (`checkInEvidence` returns pair; UI "checked in · witnessed").
- Request is one-hop: Pass must be signed as request author (`passAuthor`/`passBatch`); other devices' requests wait for own Pass.
- Remote request never makes a device a witness unless it has own local claim for same Gig (`localClaimFor`).
- Relayed witness embeds full signed claim; arrivals derived from it even if request never arrived.
- Verified: Pi(central)->Pixel(peripheral) 4/4. NOT verified: phone-as-central sending its request (either platform); iOS no device run. Deferred to phone-to-phone test rather than Pi GATT server harness.

## #443 Publication / projection
- Done in a Log field publishes during eligible participation; unfinished typing stays uncommitted.
- Incoming Facts appear inline, attributed, not republished as mine; do not change capture state or restart timers.
- After gossip window: corrections stay local, may catch up via Reconcile.
- DECIDED 2026-09-15: reopening a completed Log resumes gossip until 06:00 night cutoff (`completing(false)` clears `completedAt`; tests both platforms).
- DECIDED: two-authored-Gig merge via setlist.fm adoption gets NO gossip fix; losing Gig's public Facts stay orphaned under old id until expiry.
- DECIDED (user rule): never drop handwritten Log data. Bug: Android `unionLog` keeps longer Log, drops other; fix in progress on `prep/log-merge-no-drop`. (Also observed: iOS `mergeGigs` does not fold `gigLogs` at all — gone Gig's Log stays keyed under old id.)
- `mergeGigs` has no app caller; adoption (`adoptSetlistId`) is the merge path.
- Follow-ups: hold-to-reorder Log lines; Departures commits and Ticket adds should check for existing Gig before creating.
- OPEN: do Log edits during participation publish immediately (current: every in-participation `writeLog` publishes, incl. remove/correct) or wait for Done (field Done callback `onAdd`)? Prep test for "unfinished edits not published before Done" left ignored pending this.
- OPEN: merged-away Gig projection policy/integration.

## #444 Participation + usefulness routing
- Participation: starts at Check in; 30-min provisional grace after set completion, capped by 06:00; incoming traffic cannot extend; manual stop ends it.
- No active Gig => no radio, even with held envelopes/Contacts (policy changed from v1).
- Another active Gig keeps radio; expired Gig's envelopes must not ride another Gig's session (implemented: per-Gig `participationEnds` into `offer`; unknown nights still carried blindly while radio active).
- Grace separate from Carry TTL and usefulness decay.
- Receipt: emit when received Fact promptly recognised as Contact's; credit immediate delivering neighbour; later attribution must not claim fast delivery.
- Binary decaying boost among currently visible neighbours, random ties, no single winner, no name/key ordering; receipt does not retire envelope; Storm gate preserved.
- Exists: receipt kind validation, one-hop admission, `useful` map (hardcoded 2 min, keyed by receipt text), delivered-peer suppression.
- MISSING: no receipt ever authored; no delivering-neighbour record per fact; `offer` ignores `useful`; no neighbour priority in central connection choice; diagnostics. Earlier attempt indexed envelope IDs not neighbours — avoid.
- Sequenced after early iPhone viability experiment (#448).

## #445 Parameters
- 15-min Carry and 2-min usefulness are provisional, not settled; sparse sweep usefulness window inconclusive.
- 30-min grace is provisional product choice, not a battery optimisation; don't conflate with Carry TTL.
- No hourly battery budget accepted (1 pp/h ceiling rejected as requirement); incremental battery measurements should inform grace.

## #446 Device verification
- Priority: locked iPhone receive, then forward while still locked to a newly encountered different peer; then two backgrounded iPhones. Early viability experiment, before routing optimisation.
- Verify each discovery/connection/data direction separately; backgrounding != force-quit; OS restoration separate.
- Screen-off battery: gossip disabled / active no peers / active with traffic; subtract baseline; use mAh not %.
- Existing Pi->Pixel plain-ATT results (challenge auth, >512 B framing, duplicate suppression, direct-control admission) don't prove these.
- Checklist open: phone central send path, Android<->iOS authored & relayed facts, cross-platform request+witness, persistence after restart, visible projection, stable advertising, EATT (`Channels=3`) interop, malformed frames over ATT, in-place install.
- Blocker: Pi controller zero indices (UART hci0 DOWN/RAW); don't touch Pi networking.

## #448 Lifecycle spec (approved)
- Gig screen stages: planned today (nav/calendar) -> at venue (Ticket + Check in) -> checked-in capture (setlist.fm contribution affordance) -> set complete (playlist export).
- Check in = consent/start; no separate sharing toggle. Done = publication boundary; self-contained replacements with history.
- Completion -> 30-min grace ≤06:00; encore arrives inline without reopening or timer reset. User reopening now DECIDED: resume to 06:00.
- No active Gig = no radio incl. blind relay; planned Gig tonight insufficient.
- Stopping radio never deletes received facts; post-window edits local, Reconcile catch-up (full implementation out of scope).
- Android notification stop ends participation; iOS background attempted where permitted, not assumed dead.
- Tests: injected clock at Gig time/state + gossip policy/projection boundary; no second lifecycle state machine. Cases: absent witness, 29:59/30:00, encore during grace, 05:59/06:00, held envelopes w/o active Gig, another active Gig, Done eligibility, post-cutoff local, restart keeps facts + grace.
- Coverage now (prep-448-tests + GossipLifecycleTest): held-no-radio, second Gig active, ended Gig stops transmitting, manual stop, 05:59:59/06:00, encore no timer change, Done eligibility — Android; iOS `GossipLifecycleTests` 2 tests (ended gig/other active; no attendance + encore). Still missing both: absent-witness unlocks capture, stage UI affordances, unfinished-edit-before-Done, restart-keeps-grace at app level, GossipService ACTION_STOP.
- Out of scope: guaranteed delivery, always-on relay, media, Contact-only restoration, new Carry TTL/decay.

# PART B — Code map

## Android (`A`)
### Wire / state — `A/data/gossip/PublicGossip.kt`
- `:51` PUBLIC_CARRY_MS (15 min) constant
- `:55` publicGossipAuthPayload; `:58` PublicGossipChallenge; `:59` publicChallengeProof; `:62-67` encodePublicGossipChallenge; `:69-77` decodePublicGossipChallenge — BLE challenge proving temporary key
- `:81-121` GossipEnvelope — signed Fact (gigId, formerIds, scope, author, createdAt, expiresAt, kind, line, text, attribution)
- `:95` fields; `:97` payload; `:98` record; `:99` signed — canonical signing
- `:101-119` valid — kind/size/time/signature checks (receipt kind at 103,108)
- `:120` sameGig — shared gigId/formerIds
- `:127-134` decodePublicEnvelope; `:136-149` PublicGossipPass/encodePublicGossipPass; `:150-157` decodePublicGossipPass — Pass framing
- `:160` PublicHeld — outbox entry (delivered peers set, seeded with `from`)
- `:164-277` PublicGossipState — facts, held, seen, useful, blocked, recognition, localAuthors
  - `:175` isBlocked; `:178-187` recognizeContacts (incl. embedded claims); `:189-193` prune
  - `:194-212` receive — admission, Storm gate, one-hop controls, receipt -> useful (2 min)
  - `:213-222` offer(peer, now, participationEnds) — per-peer batch, filters expired known Gigs, sorts createdAt/id (ignores useful)
  - `:223` delivered; `:224-231` project(gigIds) — admitted, unblocked, newest-per-line
  - `:235-237` localClaimFor — own request that can witness
  - `:240-247` arrivals — request or witness-embedded claim, unblocked, not local, dedup id
  - `:250-256` checkInEvidence — (self-asserted, witnessed); `:259-261` witnessedClaims; `:274-276` witnessedGigIds
- `:290` passAuthor; `:294-296` passBatch — one-hop request signing rules
- `:299-313` witnessRequest — build witness embedding claim
- `:316-324` gossipLogChanges(before, after) — (line, text) replacements from Log diff
- `:328` GossipLogRow; `:330-344` weaveGossip — align received facts into base rows
### Identity / store / policy
- `A/data/gossip/GigIdentity.kt:19-42` GigIdentity (store 22-30, publicKey 31, sign 32-34, attribution 35-41 sealed AES-GCM binding); `:44` identityBinding; `:46` recognitionKey; `:50-61` recognizeGossip — Contact recognition of temporary author
- `A/data/gossip/GossipStore.kt:33-84` GossipStore — `:45` stoppedAt, `:46` stopParticipation, `:51-67` authorScope(localGigId) persisted per local Gig, `:72` decodePublic, `:76-83` updatePublic (transactional state write)
- `A/data/gossip/GossipIds.kt:39` isSafeGossipId; `:56` gossipExpiry (night 06:00); `:74` contactKeysOf
- `A/data/gossip/GossipPolicy.kt:52` gossipPassDue (cooldown); `:56` gossipRelayShouldRun; `:60-67` gossipParticipationUntil (check-in, 30-min grace, stoppedAt, legacy closed); `:84-87` gossipNightEnds; `:97-104` gossipGigTonight; `:128` gossipNearby
- `A/data/gossip/GossipPresence.kt:29-41` GossipPresence — recent authenticated BLE speech ("also here"), met/forget
- `A/data/gossip/GossipRadioStatus.kt:17-96` GossipPeer/GossipNote/GossipStatus/GossipRadioStatus — diagnostics
### Service / radio
- `A/data/gossip/GossipService.kt:60-345` GossipService (FGS); `:71-73` participationEnds/activeUntil; `:100-127` onStartCommand (ACTION_STOP ~99-101); `:140-233` startRadio — peripheral delivery -> receive, recognizeContacts, auto-witness (170-183); central publicPassFor -> offer/passAuthor/passBatch (190-200); delivered persistence ~208; `:243-247` refresh; `:272-316` foreground notification; `:333-343` sync
- `:357-363` gigDatesOf; `:366-367` gossipActiveUntil; `:371-387` gossipParticipationEnds — per-Gig deadline map from TimelineStore
- `A/ble/GossipRadio.kt:113-124` PublicGossipDelivery + ByteArray.intoChunks; `:133` gossipWriteLimit (512 cap); `:136-360` GossipPeripheral (read 175-199 challenge, write 201-239 reassembly, advertise 316-340); `:363-618` GossipCentral (scan 401-411, push 460-617: MTU, services, challenge read, chunked writeNext 607-615)
### App layer
- `A/AppViewModel.kt:2135-2139` blockGossip; `:2176` setLogClosed -> completing; `:2178-2187` writeLog (save, publishLog, syncGossip); `:2189-2217` publishLog — eligibility via gossipParticipationUntil, monotone revision, formerIds, signs log Envelopes
- `:2340-2364` adoptSetlistLink -> timelines.adoptSetlistId; `:2380` checkInDue; `:2399-2411` offerCheckIn; `:2443-2456` checkIn; `:2478-2498` gossipAbout — authors `kind="request"` under authorScope; `:2508-2513` syncGossip
- `A/data/TimelineStore.kt:526` gigLogs; `:588` logs() = gigLogs.combined(::unionLog); `:634-637` unionLog — BUG keeps longer Log, drops other; `:648-649` unionAttendance; `:931-945` adoptSetlistId; `:997-1008` mergeGigs (no app caller); `:1016-1019` saveLog; `:1021-~1023` updatePublicGossip; `:1287-1301` withGigFacts; `:1312-1342` merging (folds gigLogs with unionLog at 1340); `:1345-1349` folded
- `A/data/Bill.kt:175-295` StoredLog — `:220` lineNumbers, nextLineNumber; `:231-232` completing (sets/clears completedAt); `:234` lineNumberAt; ~244-254 adding/removing keep stable line ids
### UI
- `A/ui/StationScreen.kt:3986-4030` gig Log section — project/arrivals ("· checked in" line 3989-3996), weaveGossip rows 3997, attribution name/"Nearby listener" + Block TextButton 4014-4022, inline unmatched fact text; `:1260-1288` CheckInDialog
- `A/ui/LogEditor.kt:311-336` AdoptSetlistDialog (field Done -> onAdd in editor)

## iOS (`I`)
- `I/Data/Gossip/PublicGossip.swift:8-29` challenge payload/proof/encode/decode; `:31-81` GossipEnvelope (fields 45-48, payload 49, record 50, signed 51-57, sameGig 58-60, valid 61-80); `:82` gossipHash; `:83-92` decodePublicEnvelope; `:93-121` PublicGossipPass/Delivery/encode/decode; `:122-126` PublicHeld
- `:127-252` PublicGossipState — isBlocked 138-140; recognizeContacts 142-150; prune 152-156; receive 158-177 (receipt/useful 166-175, carry 169); offer 178-187 (participationEnds); delivered 188-190; project 191-201; localClaim 203-206; arrivals 209-222; checkInEvidence 225-231; witnessedClaims 234-236; witnessedGigIds 248-251
- `:259-261` passAuthor; `:268-271` passBatch; `:274-284` witnessRequest; `:286-292` gossipLogChanges; `:295-299` GossipLogRow; `:302-315` weaveGossip
- `I/Data/Gossip/GigIdentity.swift:6-49` GigIdentity (key 8-27, publicKeyBase64 31-33, sign 36-41, attribution 42-48); `:50-52` identityBinding; `:53-55` recognitionKey; `:56-62` recognizeGossip
- `I/Data/Gossip/GossipLedger.swift:30-180` GossipLedger actor — authorScope 49-58; publicSnapshot 61-65; receivePublic 69-72; receivePublicFacts 75-83; recognizeContacts 85-91; blockAuthor 93-99; deliveredPublic 101-107; budget/spend 112-122; forgetAll 126-135; load/write/persist 139-167; pruned 175-179; `:189-194` StoredGossip
- `I/Data/Gossip/GossipChannel.swift:23-225` GossipChannel actor — observePublic 62-65; blockAuthor 67-70; observeWitnessed 80-83; publishWitnessed 85-90; setContacts 101-105; setNightEnds 107; checkedIn 119-135 (authors request); publishLog 137-153; relayScope 156-159; publicPass 161-177 (gossipParticipationEnds -> offer); receivePublic 179-195 (auto-witness 185); forgetAll 198-202; challenge 212-217; confirmDelivery 221-224
- `I/Data/Gossip/GossipTransport.swift:78` gossipUntilKey; `:108-325` GossipTransport (wakeAtLaunch 164-167, contactsChanged 170-177, stopParticipation 181-185 / stoppedAt ~179, startLocked 194-218, stopLocked 220-234, scan 236-244, advertise 246-265, beginMeeting 275-288, send 302-317, writeNext 319-324); `:329-337` GossipMeeting; `:339-389` central delegate; `:391-435` peripheral delegate; `:437-571` peripheral-manager delegate (challenge answer 510-517, writes 519-570)
- `I/Data/Gossip/GossipGatt.swift:109-122` Data.gossipChunks; `I/Data/Gossip/GossipBudget.swift:57-113` GossipPeerBudget/GossipAdmission/gossipAdmit/gossipPruneBudgets (per-peer cooldown/hourly budget); `I/Data/Gossip/GossipIds.swift:28-59` isSafeGossipId/gossipExpiry/contactKeysOf
- `I/Data/GigTimeState.swift:76-78` withinCheckInWindow; `:200-205` gossipParticipationUntil; `:209-222` gossipParticipationEnds (per-Gig map)
- `I/Data/StoredLog.swift:27-173` StoredLog — lineNumbers/nextLineNumber 61-62; completing 88-93; lineNumberAt 95; adding 116-124; removingAt 127-135; correctingAt 145-152; restoringAt 155-162
- `I/AppModel.swift:474-498` gossipContactsChanged (deadlines 481); `:690-708` adoptSetlistLink; `:1568-1574` checkInDue; `:1589-1602` offerCheckIn; `:1636-1664` checkIn; `:1986` blockGossip; `:2000-2002` setLogClosed; `:2004-2022` writeLog — eligibility + GossipChannel.publishLog
- `I/Data/TimelineStore.swift:545` gigLogs; `:701-708` saveLog; `:712-716` log; `:970-981` adoptSetlistId; `:990-1025` mergeGigs (folds media/playlists/offsets/attendance/calendar/planned — NOT gigLogs); `:1028-1034` updatePublicGossip; `:1191-1206` withGigFacts; `:1215-1221` folded
- `I/UI/GigView.swift:94-128` gossip section — project/arrivals "· checked in", weaveGossip rows, inline unmatched facts; `:455-466` gossipAttribution (name/Nearby + Block button 464)

## Tests
Android `android/app/src/test/java/io/github/magnusencoded/stationtostation/`
- `PublicGossipTest.kt` (14) — Pass sign/roundtrip, challenge, oversized batch prefix, one-hop controls vs Storm gate, stranger carry + 2nd-copy gate, block still carried/no repeat handoff, replacement convergence + history, altered payload, direct request witnessed vs self-assertion, embedded-claim recognition w/ block, alignment reprises
- `PublicGossipStoreTest.kt` (4) — authorScope concurrency/expiry/restart, publication follows setlist adoption, explicit merge keeps both scopes, author+radio updates survive restart
- `GossipLifecycleTest.kt` (8) — #448: held envelopes no radio, no attendance, second Gig active, ended Gig stops transmitting, manual stop, 05:59:59/06:00, encore during grace, Done eligibility vs cutoff
- `GossipPolicyTest.kt` (11) — participation/completion no renew, peer cooldown, night end caps, tonight window, presence ordering
- `GossipRadioFramingTest.kt` (2) — chunk reassembly, write limit vs MTU/512
- `LogTest.kt` (17) — stable line numbers on delete, completion across restart, corrections/restore, paste
- `TimelineStoreTest.kt` (75) — timeline persistence incl. merges/adoption; `GigTimeStateTest.kt` (23) — stage windows incl. 06:00
- androidTest `GossipRadioDeviceTest.kt` (2, opt-in manual_ble_peer) — real GATT Pass from Pi peer + Storm gate; indirect controls rejected, one usefulness credit. `GigIdentityDeviceTest.kt` (1) — Keystore key persists, signs verifiable envelopes
iOS `ios/StationToStationTests/`
- `PublicGossipTests.swift` (14) — mirrors PublicGossipTest
- `GossipLedgerTests.swift` (7) — authorScope persistence/fail-closed, adoption+merge scopes, public state restart/relay expiry, contact budget forgetting
- `GossipLifecycleTests.swift` (2) — ended Gig stops sending while another active; no attendance + encore not extending
- `GossipBudgetTests.swift` (10) — per-peer cooldown/hourly ceiling/clock skew
- `GigTimeStateTests.swift` (23) — incl. testGossipParticipationEndsWithoutRenewingAfterCompletion; `StoredLogTests.swift` (22) — line identity, corrections; `GigIdentityTests.swift` (1) — SPKI/DER signing; `TimelineStoreTests.swift` (46) — incl. public gossip survives restart
Simulator / prototype
- `sim/src/station_to_station_sim/` — models, population, mobility, information, policies (Contact-gated control, Epidemic, Spray, adaptive), application (projection/witness), metrics, simulation (`run_simulation`), sweeps, sparse_sweeps; `sim/tests/` test_relay, test_metrics, test_sparse_sweeps, test_package (17 pass); `sim/SWEEPS.md` 630-run sparse sweep, defaults provisional
- `docs/prototypes/gossip_v2_peer.py` — BlueZ Pi central peer: signed v2 Pass over plain ATT to Pixel; `--controls` mode; does not test phone central path
- `docs/prototypes/gossip-relay-antfarm.PROTOTYPE.html` — visual relay prototype
Docs: `docs/gossip-public-wire.md` (wire spec, Carry figures), `docs/gossip-ble-handoff.md`, `docs/gossip-v2-next-pass.md` (progress log)

## ADRs (`docs/adr/`)
- `0019-gossip-channel-background-carve-out.md` — v1 Contact-only carve-out from ADR-0016; amended 2026-09-11 (struck); §6 background/lifecycle stale vs #448
- `0021-public-gossip-facts.md` — public Gig Facts over blind relays (v2 decision)
- `0016-presence-is-the-authentication.md` — radio unverified; presence authenticates
- `0018-the-gig-is-the-atom.md` — Gig as atom; everything else enrichment (merge/identity)
- `0004-best-effort-enrichment.md` — best effort, scoped invariant
- `0003-no-backend-for-the-social-layer.md` — no server; P2P only

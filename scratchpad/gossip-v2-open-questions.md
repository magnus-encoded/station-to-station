# Gossip v2 ship: open questions for the user

Running autonomously per instruction (2026-09-17): never block on a question.
Anything that would normally need the user's call is logged here instead,
with my default/working assumption, and work continues. Review this file
when you're back.

---

## #477 (Android lifecycle) — merged as PR #487

1. **Stop-then-reopen product decision.** The implementer decided that reopening the Log
   after `ACTION_STOP` does **not** resume gossip participation (only reopening a
   completed-but-not-stopped Log resumes, per story 27). Their reasoning: if a later Log
   edit silently undid a stop, the notification stop action wouldn't be a real off switch
   (story 30/31). I agree with this reading and let it stand — flagging in case you read
   story 27 differently. If you want stop-then-reopen to also resume, it's a small change
   in the new `gossipStop` seam in `GossipService.kt`.
2. **Removed `always_relay` setting.** Dead code with no UI caller, already contradicted
   the "no active Gig → radio off" rule and ADR-0019's 2026-09-15 amendment. Assumed
   fine to delete outright; logged here in case there was a reason it was kept around.
3. **Local Android build friction (not blocking, not acted on):** `android/gradlew` has
   CRLF line endings from the Windows migration, and the JVM needs `LANG=C.UTF-8` or
   Kotlin test compilation dies on an em-dash in a test class filename. CI isn't affected.
   Worth fixing if local Android builds on this box are going to be routine — left alone
   for now since it was out of scope for the ticket.

## #478 (iOS lifecycle) — merged as PR #488

4. **iOS stores the participation deadline on disk (UserDefaults), Android doesn't.**
   Deliberate, not a divergence: CoreBluetooth restoration runs at app launch before the
   timeline loads, so iOS can't recompute-from-scratch the way Android's design assumes.
   The derived value still overwrites the cached one on every recompute, so behaviour
   matches. Documented in the PR and ADR-0019 amendment so it doesn't read as a bug later.
5. **iOS's "Stop gossip" button can go stale for a bit** after grace expires with nobody
   touching the app (deadline is event-refreshed, not live/ticking). Implementer called
   this cosmetic and correctly scoped a live countdown as out of bounds for this ticket.
   Left as-is.
6. **Cross-platform asymmetry flagged, not fixed:** iOS now publishes the participation
   deadline into UI state so the stop control has one source of truth; Android's
   notification doesn't have an equivalent published field yet. Candidate for #471 (docs)
   or a small Android follow-up — not blocking anything on the ship path, noting here so
   it isn't lost.

## #479 (Android witnessed check-in) — merged as PR #489

7. **Most of #479 was already built by #460-462.** Only the self-witness negative test,
   the absent-witness-doesn't-block-capture test, and the "checked in · witnessed" chip
   on the Gig screen were actually missing. Flagging because #480/#481 etc. may be
   similarly smaller than their tickets suggest — implementer recommended each next
   agent audit `main` against its AC before assuming a full build is needed. No action
   needed from you; just context for why turnaround may be faster than the ticket size implies.
8. **No ADR/CONTEXT.md update for #479.** Implementer judged it not ADR-worthy (no
   decision reversed, "witness" already established vocabulary) and left docs to #471.
   I agree — noting in case you'd have wanted it flagged separately.
9. **Pre-existing quirk, not caused by this ticket:** a Gig checked in *before*
   `adoptSetlistLink` runs may have a claim whose `formerIds` don't include the later
   setlist.fm id, so the "witnessed" chip can go quiet after adoption. Already true on
   the Walk before this PR. Not fixed, not blocking — worth a follow-up issue at some
   point but out of scope for gossip v2 ship.

## #480 (iOS witnessed check-in) — merged as PR #490

10. **Same pattern as #479: iOS was already ~90% there.** Only the self-witness rule
    needed extracting into a testable `witnessFor` function (mirroring Android's #489)
    plus two new tests. The UI chip the ticket described as "likely the actual gap" had
    already landed in #462 — implementer verified via `git log -L`.
11. **Known, accepted test-coverage gap on both platforms:** the negative self-witness
    test's `sign` closure ignores its `GossipEnvelope` argument, so neither Android's nor
    iOS's test would catch `witnessFor` accidentally signing with the *request's* identity
    instead of the *local claim's*. Implementer judged this a hypothetical not worth
    diverging Android/iOS test shape over. Candidate for a small follow-up on both
    platforms, not blocking.

## #481 (Android attribution and blocking) — merged as PR #491

12. **Behaviour change, called out by the implementer:** removing a Contact used to
    silently demote their already-received Facts (and witnessed arrivals) to "Nearby
    listener" in the UI, because the name was looked up live instead of snapshotted at
    recognition time. Fixed to match story 16 ("keep recognising someone's authorship
    after removing them as a Contact") — recognition now snapshots the name so it
    survives removal. This is a real, user-visible UI change from previous behaviour;
    flagging since it wasn't explicitly re-confirmed by you, though it matches the
    written spec exactly.
13. **No Block button on an arrivals/check-in row yet** — only log-Fact rows have one
    today. The filtering logic for a blocked check-in author is correct and tested, but
    the affordance to actually block from an arrivals row is deferred to #483/#484 (the
    presence-feed tickets), same pattern as #479 deferring the arrivals *line* itself.
    Not a gap in this ticket's scope, just tracking where the UI catches up.
14. **Permission note (not a question, just visibility):** the implementer wanted to
    `git commit --amend` / force-push to clean up a naming fix, and the auto-mode
    classifier blocked it — correctly, per your standing instructions (never
    amend/force-push without being asked). It landed as a second commit instead. No
    action needed; this is the system working as configured.

## #482 (iOS attribution and blocking) — merged as PR #492

15. **Same live-lookup bug found on iOS as Android** (story 16 violation), fixed the same
    way. Also fixed a second copy of the same bug in the arrivals line that Android's PR
    didn't call out separately (iOS had it in two places, Android in one — same root cause).
16. **A real persistence hazard caught and fixed, worth knowing about generally:** iOS's
    `PublicGossipState` uses Swift's synthesized `Codable`, which throws on a missing key
    even when the property has a default (unlike Android's kotlinx.serialization, which
    tolerates it). Adding the new `contactNames` field the naive way would have made
    `GossipLedger.load()` (which decodes with `try?`) silently wipe every existing
    ledger — Facts, blocks, grace deadline — on any device upgrading with saved state.
    Fixed with a hand-written decoder and a regression test. Flagged as a standing
    landmine: any future field added to iOS's `PublicGossipState` needs to go through
    that same decoder, or a well-intentioned addition will reintroduce a silent data-wipe
    bug. Worth a note in CONTEXT.md via #471.
17. **Operational note, not a product question:** this PR's branch went stale against
    `main` while its implementer was rate-limited mid-task, and the merge required
    conflict resolution. I did this by hand — on the first attempt I mistakenly ran the
    conflict-resolving `git merge` in the main checkout (on your own `timeline-quick-
    add-friend-gig` branch) instead of the agent's isolated worktree. Caught it before
    anything was committed or pushed (`git merge --abort`), your branch was untouched
    and is exactly as you left it. Redid it correctly in the worktree. No follow-up
    needed, just flagging the near-miss for transparency.

## #483 (Android presence feed) — merged as PR #493

18. **A genuine widening of what "presence" means, recorded but not ADR'd.** v1 presence
    meant "these two phones spoke directly, right now." v2 presence now also includes a
    verified check-in relayed to you via someone else's witness — so it can outrun your
    own radio by one hop, bounded by a 5-minute window and each Gig's participation
    deadline. Implementer judged this an extension of ADR-0021's existing position
    (attribution, not proximity, governs who may be named), not a new decision. I agree,
    flagging since "who can see me" changing shape is worth your awareness.
19. **Known limitation, not filed as a separate issue:** a Contact who checked in shows in
    "who is also here" for ~5 minutes after their check-in reaches you, then goes quiet —
    nothing currently re-triggers presence after that (a completed in-person Exchange
    would be the natural second trigger, carried over unfinished from v1). Not blocking
    ship; candidate follow-up if you want it filed.
20. **Verification gap, stated plainly by the implementer:** the pure logic (`presenceFrom`)
    is tested at the seam; the actual BLE wiring around it (gigIds capture, transaction
    ordering, notification refresh) has no test and wasn't device-verified — needs two
    real phones passing to each other, which is exactly what #467 (field testing) will
    exercise. Not a gap I can close before then.

## #484 (iOS presence feed) — merged as PR #494

21. **iOS never had a "who is also here" surface at all — #439 was Android-only.** The
    ticket assumed iOS just needed rewiring; the implementer instead built the missing
    substrate (a nearby-window constant, a presence map, a Gig-page line) from scratch
    rather than land dead code. This is more than the ticket literally asked for but is
    exactly what "cohesive UX across both platforms" requires — flagging as a bigger-than-
    expected addition, not a scope complaint.
22. **iOS's presence line is Gig-scoped; Android's isn't.** Android's "who is also here"
    lives on a screen with no Gig dimension, so it can't have the bug. iOS's equivalent
    line lives on the Gig screen, so the implementer scoped it to the currently-active
    Gig(s) to avoid printing last month's attendee under tonight's setlist. Correct for
    where each currently lives, but if Android's line ever moves onto a Gig-scoped screen
    it would need the same scoping. Noted as a latent parity gap, not urgent.
23. **Confirmed on iOS too: presence never re-triggers after ~5 minutes** (a check-in is
    admitted exactly once, can't re-enter the "just arrived" set). Same limitation as
    Android (#483), same non-fix. Consistent, not a regression.
24. **Color choice:** iOS used "ink" (neutral) rather than reusing Android's amber for the
    presence line, because amber already means "mine" elsewhere in iOS's Gig view. A
    considered platform-appropriate divergence, not an inconsistency to fix.

## #485 (Android receipt credit) — closed, no code needed

25. **Already fully built** by the earlier v2 swap (#462/ADR-0022) — audited, confirmed,
    closed with no PR. Genuinely good news, not a shortcut: `gossipPreferredPeers` ranks
    by credit with seeded random ties, uncredited peers stay eligible, and it's tested.
26. **Important honesty flag from the implementer:** credit-based routing is "close to
    inert in the field" by design, not by bug. A peer is only credited once its identity
    resolves from a BLE challenge read, and the credit window (2 min) mostly overlaps
    the connection cooldown (1 min), leaving roughly a 1-minute window per peer where a
    sighting is both eligible and credited. ADR-0022 already documents that fixing this
    needs a per-pair rotating token in the advertisement — deliberately removed earlier
    when relaying widened to blind edges (a real tradeoff, not an oversight). So: the
    *rule* is correct and tested, but don't expect it to visibly steer routing on a real
    night yet. `GossipTally.ranked` exists specifically to measure this once #467 (field
    testing) happens.
27. **#471 should fix a code-map error:** the spec pointed at `PublicGossipState.offer()`
    for peer ranking; ranking actually lives in `gossipPreferredPeers`. `offer()` takes
    one peer at a time and can't rank anything. Noted for the docs ticket.

## 2026-09-20 session (afk resume)

28. **Local .git was corrupted** (43 zero-byte loose objects dated today, plus remote-tracking
    refs pointing at them; an interrupted fetch/compact). Repaired non-destructively: all empty
    objects moved to /tmp/git-empty-objs (not deleted), 5 remote-tracking refs deleted and
    re-fetched. `git fsck` now shows only dangling objects. Local branches were never touched.
29. **Two PRs raced for #499** (#506 draft by one agent, #508 by the `sandcastle` automation).
    #506 was the fuller one (Gig-id aliases, dial-out direction, ADR-0023, type-checker fix), so
    I merged #506 and closed #508 as superseded. The `sandcastle` label/branches mean an outside
    automation is also working this repo; it may collide with me on #501 (I checked: no #501 branch
    existed when I dispatched).
30. **#501 issue text was stale** (says "existing Stop/Resume control": iOS has Stop only, no
    Resume; the issue also predates ADR-0024's per-Gig stop semantics). Implementer told to build
    Resume; issue body not edited.
31. **1.8.1 was never tagged**: main carries the 1.8.1 version bump but the only tags are up to
    v1.8.0 and no 1.8.1 GitHub release exists. 1.9.0 will go out as the next tag; v1.8.1 stays
    untagged unless you want it back-filled.
32. **#463's pre-release checks are human-only** ("check the 1500 ms pick window" and "install on a
    real phone and check in once"). I cannot do them. Per your instruction to cut 1.9 when v2 is
    shipped I am cutting it anyway; the tag publishes to the Play *alpha* track (closed testers),
    marked experimental. Do the real-phone check-in when you have the build.

## 2026-09-20: shipped

33. **v1.9.0 is out** (tag on 11ef5dd; APK + unsigned debug IPA on the GitHub release, Play alpha
    track via the tag workflow). Release notes say experimental and not field-tested. #463 closed.
34. **Docs PR #509 had no CI** (docs-only; workflows are path-filtered), so slopguard/lint never ran on it.
35. **Still open by design:** #465 (Pi refuses Pass chunk 0, ATT 13), #467 (first phone-to-phone Pass,
    locked iPhone), #445 (research). These are the field tests. Not done: the pre-release real-phone
    check-in and 1500 ms pick-window check from #463.
36. **Known small follow-ups (unfiled):** Android `UiState.gossipStoppedGigs` is unfiltered against `now`
    (inert today, a trap for a second consumer); presence goes quiet ~5 min after a check-in until a
    new trigger; iOS Stop/Resume button can lag until an event refreshes it; iOS #486 meeting cap is a
    throughput tradeoff worth watching in the field.
37. **IPA signing question you asked**: answered in chat (Wi-Fi refresh for free ID, or Ad Hoc + OTA
    with the $99 account). Nothing built yet.

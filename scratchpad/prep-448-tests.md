# #448 lifecycle tests (Android)

Worktree: /home/dizzi90/Documents/station-to-station/.claude/worktrees/agent-a5c978a3b56300e89
Branch: prep/448-lifecycle-tests (from gossip-ble-diagnostics @ 89bd25d), committed, not pushed.
File: android/app/src/test/java/io/github/magnusencoded/stationtostation/GossipLifecycleTest.kt
Ran locally (GossipLifecycleTest + GossipPolicyTest): 9 new tests, 7 pass, 2 ignored, 0 fail.
(Local gradle works: LF copy of gradlew, no ANDROID_HOME override.)

## Passing
- Held envelopes without an active Gig do not keep radio on (real TimelineStore + gossipActiveUntil)
- No attendance -> gossipActiveUntil null
- Second Gig still active keeps radio on after first Gig's grace ends
- Manual stop (stoppedAt) ends participation read from store
- 05:59:59 true / 06:00 false explicit boundary
- Encore during grace: closed/completedAt/timer unchanged; only the new line is a change
- Done inside participation eligible; 30 min later not; Done itself yields no line changes

## Ignored (missing behaviour)
- Ended Gig's envelopes still transmitted while another Gig keeps radio on: offer() has no participation notion.
- Unfinished edits published before Done: AppViewModel.publishLog publishes every in-participation edit. Confirm product intent before implementing.

## Not covered
Absent witness unlocks capture, per-stage UI controls (UI-level); incoming traffic restart (no traffic input exists); publishLog is private, tests use its inputs.

## iOS mirrors needed
Same cases in GigTimeStateTests/GossipLedgerTests: multi-gig active-until with held ledger, store-level stop, 05:59 boundary, encore during grace, Done/cutoff eligibility (AppModel.swift:2019), plus the two ignored gaps.

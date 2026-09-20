# Prep: #443 Gig merge/adoption for public gossip author scopes

Worktree: `/home/dizzi90/Documents/station-to-station/.claude/worktrees/agent-a078324e017eb02c4`
Branch: `prep/443-gig-merge` (from `89bd25d`), commit `a5ebd40`. Not pushed.
(The worktree was first created on an older base, `1e551e4`. The branch was cut from 89bd25d explicitly.)

## What the issue requires (merge slice)
"Keep author scopes tied to persistent local Gig representations as external IDs change. Carry former IDs,
handle later setlist.fm adoption and define/test explicit Gig merging without silently conflating unrelated
authors/scopes." Next-pass doc (~949): scopes persist, but that does not settle merging two already-authored
local Gig representations.

## What I implemented
Tests only. No production change: every behaviour I could pin down unambiguously already works.
`android/app/src/test/java/io/github/magnusencoded/stationtostation/PublicGossipStoreTest.kt`:
- `publicationUnderAScopeFollowsSetlistAdoptionAcrossRestart`: publishes a Log fact under a local-Gig scope,
  then a replacement under the setlist.fm id with the local id in `formerIds`. The scope stays the same.
  After a restart, the replacement projects under both ids.
- `mergingTwoAuthoredLocalGigsKeepsBothScopesDistinct`: two local Gigs each publish under their own scope.
  After a restart both bindings are unchanged, neither is rebound to the other, and the survivor
  projects only its own line.
- `@Ignore factsFromAMergedAwayGigStillProjectOntoTheSurvivor`: holds the open question below.

## Gap found
`TimelineStore.merging` (Android) and `mergeGigs` (iOS) drop the loser's local id and record no alias.
The loser's facts were published with `gigId` = its local id (plus its own `formerIds`). After the merge,
`PublicGossipState.project(survivorIds)` no longer matches them. `publishLog` builds `former` only from
facts by the *survivor's* author key, so it never adds the loser's ids either. The loser's self-authored
public Log disappears from the survivor's night, although peers still hold it under the loser's id.

## Open questions (need the user / #408)
1. Merged-away ids: record them on the survivor (e.g. `StoredGig.formerIds`) and project by them, which leaves
   two self-authored scopes visible side by side?
2. Or have the survivor re-publish under the loser's scope, with the survivor id in `formerIds`, so peers can
   re-home it too?
3. Or retire the loser's facts?
4. Should the merge rebind the loser's `authorScope` binding to the survivor? I'd say no: two keys would then
   sign for one Gig. The test asserts no rebind.

## iOS mirror still needed
- `ios/StationToStationTests/GossipLedgerTests.swift`: add counterparts of the three tests against
  `GossipLedger.authorScope(localGigId:)` and `PublicGossipState.project(gigIds:)`
  (`ios/StationToStation/Data/Gossip/GossipLedger.swift:49`, `PublicGossip.swift:188`). Skip the merged-away test.
- Once an open question is decided: update `TimelineStore.mergeGigs`/`adoptSetlistId`
  (`ios/StationToStation/Data/TimelineStore.swift:970,990`) and the Android `TimelineStore.merging`, plus the
  `former` computation in Android `AppViewModel.publishLog` and its iOS AppModel counterpart.

## Test results
Ran locally (JDK available, via an LF copy of gradlew):
`./gradlew :app:testDebugUnitTest --tests '*PublicGossip*'`. BUILD SUCCESSFUL.
PublicGossipTest 14/14. PublicGossipStoreTest 5 tests, 4 passed, 1 skipped.
The ignored test was not un-ignored to watch it fail; the failure is inferred from reading `project()`.

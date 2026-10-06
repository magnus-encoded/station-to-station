# iOS features

The iOS twin of Android's `features/<name>/`. Which features exist and which one owns a
behaviour is logic and matches Android (ADR-0001, amendment); everything inside a feature is
Swift plumbing.

## Layout

- `ios/StationToStation/Features/<Name>/`, with Android's names: Settings, Handover, Contacts,
  Navigation, Setlists, Planning, Tickets, Gig, GigMedia, Gossip, Playlist. A feature's
  controller and its views live together there.
- `ios/StationToStation/Shared/`: views no feature owns (speech, swipe-back, BLE probe).
- `ios/StationToStation/Data/` stays flat, the twin of Android's `data/`. Don't move files in
  it: the TicketShare extension sources `Data/Ticket` by path.
- `ios/project.yml` sources whole folders, so a new folder needs no project edit.

## The controller pattern

The seam is `StateHost` (`Features/StateHost.swift`):

```swift
@MainActor protocol StateHost: AnyObject {
    var state: UiState { get set }
    func fail(_ error: Error)
}
```

`AppModel` conforms. `UiState` is not redesigned: one value, written in place.

A controller is a `@MainActor final class` that takes:

- `let host: StateHost` (held strongly: AppModel lives as long as the app, and a Task that
  outlives a test's host keeps it alive instead of crashing on it), and writes `host.state.x = …` exactly where `AppModel`
  wrote `state.x = …`, so a moved method stays almost byte-identical;
- its `Data/` dependencies (clients, stores, `Settings`);
- other controllers as direct (strong) references. A closure is left only where a direct
  reference would make the lazy wiring in `AppModel` cyclic, or the callee is `AppModel` itself.
  Pure reads of `UiState` shared by several features (`knownNights`, `lineArtists`,
  `sortedPlanned`) live in `Features/Planning/Line.swift`.

`AppModel` owns every controller (`let contacts: ContactsController`, …) and keeps only:
composition, `fail` and error handling (`consumeError`, `consumeNotice`), and the
orchestration that genuinely spans features.

Views call `model.<feature>.method()`. No delegator is left behind on `AppModel`: when a
method moves, every call site moves with it.

## Tests first

Before moving any method that changes state across an `await`, or that touches another
feature, write characterization tests against the current behaviour, then move it with the
tests green. The test builds the controller over `FakeState`
(`StationToStationTests/Features/FakeState.swift`), which holds a plain `UiState` and records
every `fail` in `failures`.

A pure delegation to `Data/` (no `await` between state writes, no other feature touched) needs
only to compile.

There is no local Swift toolchain: push and let `.github/workflows/ios.yml` build and test.

## Where every AppModel method goes

Private helpers move with the public methods that call them. Where Android's controller holds
the twin, that decides; the Tickets, maybes and curtain rows are rulings.

| Method | Feature |
|---|---|
| `init` | stays on AppModel |
| `consumeError` | stays on AppModel |
| `consumeNotice` | stays on AppModel |
| `fail` | stays on AppModel |
| `errorKind(of:)` | stays on AppModel |
| `isSharedQuota` | stays on AppModel |
| `loadTimeline` | stays on AppModel |
| `refreshTimeline` | stays on AppModel |
| `loadFixture` | stays on AppModel |
| `sortedPlanned` | Planning |
| `loadPlannedGigs` | Planning |
| `refreshPlannedGigs` | Planning |
| `suggestArtists` | Planning |
| `clearArtistSuggestions` | Planning |
| `addGig` | Planning |
| `addPlannedGigByHand` | Planning |
| `mintPlannedGig` | Planning |
| `addLocalGig` | Planning |
| `commitProgramme` | Planning |
| `addPlannedGig` | Planning |
| `planFmGig` | Planning |
| `markCalendarAdded` | Planning |
| `addToCalendar` | Planning |
| `joinGig` | Planning |
| `lineArtists` | Planning |
| `drainTicketInbox` | Tickets |
| `settleDeposit` | Tickets |
| `routeShared` | Tickets |
| `attachAdmissions` | Tickets |
| `put` | Tickets |
| `confirmTicket` | Tickets |
| `dismissTicket` | Tickets |
| `ticketSearch` (and `TicketSearch`) | Tickets |
| `photosLostByDeleting` | Gig |
| `standing` | Gig |
| `deleteGig` | Gig |
| `onLine` | Gig |
| `adoptSetlistLink` | Gig |
| `adoptSetlist` | Gig |
| `removePlannedGig` | Gig |
| `checkInDue` | Gig |
| `hasLocationPermission` | Gig |
| `requestLocationPermission` | Gig |
| `offerCheckIn` | Gig |
| `dismissCheckInOffer` | Gig |
| `venueCoords` | Gig |
| `checkIn` | Gig |
| `setGigNote` | Gig |
| `setGigVerdict` | Gig |
| `writeToLog` | Gig |
| `addToLog` | Gig |
| `removeFromLog` | Gig |
| `correctLogEntry` | Gig |
| `restoreLogEntry` | Gig |
| `setLogClosed` | Gig |
| `writeLog` | Gig |
| `storeAttendance` | Gig |
| `loadGigMedia` | GigMedia |
| `songOffsets` | GigMedia |
| `stampSong` | GigMedia |
| `refreshSuggestions` | GigMedia |
| `attachMedia` | GigMedia |
| `moveMedia` | GigMedia |
| `removeMedia` | GigMedia |
| `toggleLineHidden` | Navigation |
| `setZoomedOut` | Navigation |
| `toggleContactLight` | Navigation |
| `setShowWithheld` | Navigation |
| `resolveFestivals` | Navigation |
| `toggleFestival` | Navigation |
| `backOutOfFestivals` | Navigation |
| `openGig` | Navigation |
| `open` | Navigation |
| `openTimeline` | Navigation |
| `openPlace` | Navigation |
| `openAddGig` | Navigation |
| `refreshLine` | Contacts |
| `loadFriendTimelines` | Contacts |
| `myCardURL` | Contacts |
| `myProbeCard` | Contacts |
| `saveMyCardName` | Contacts |
| `landContactNights` | Contacts |
| `holdMediaOffers` | Contacts |
| `acceptMediaOffer` | Contacts |
| `declineMediaOffer` | Contacts |
| `maybesOnSelected` | Contacts |
| `joinNight` | Contacts |
| `dismissMaybe` | Contacts |
| `answerMaybe` | Contacts |
| `adoptMaybe` | Contacts |
| `undoMaybe` | Contacts |
| `startContactExchange` | Contacts |
| `stopContactExchange` | Contacts |
| `addFriend` | Contacts |
| `confirmFriendOverwrite` | Contacts |
| `dismissFriendOverwrite` | Contacts |
| `writeFriend` | Contacts |
| `addFriendByUsername` | Contacts |
| `handleFriendLink` | Contacts |
| `removeFriend` | Contacts |
| `openSharedConcerts` | Contacts |
| `offerHandover` | Handover |
| `joinHandover` | Handover |
| `cancelHandover` | Handover |
| `dismissHandover` | Handover |
| `rememberHandoverRefs` | Handover |
| `handoverRef` | Handover |
| `storeHandoverAccounts` | Handover |
| `receiveHandoverAccounts` | Handover |
| `sendHandoverAccounts` | Handover |
| `myIdentities` | Handover |
| `accountsPayload` | Handover |
| `saveSettings` | Settings |
| `saveClashfinderAccount` | Settings |
| `loginSpotify` | Settings |
| `markOnboarded` | Settings |
| `disconnectSpotify` | Settings |
| `saveMySetlistFmUser` | Settings |
| `setArtistQuery` | Setlists |
| `setUserQuery` | Setlists |
| `searchArtists` | Setlists |
| `openArtist` | Setlists |
| `openUserAttended` | Setlists |
| `loadMoreSetlists` | Setlists |
| `fetchCatalogue` | Setlists |
| `pullCurtain` | Setlists |
| `refreshSelectedSetlist` | Setlists |
| `stampLookup` | Setlists |
| `refreshLocalGig` | Setlists |
| `lookUpLocalGig` | Setlists |
| `startLookupChecks` | Setlists |
| `stopLookupChecks` | Setlists |
| `lookupPlan` | Setlists |
| `setlistFmChipHits` | Setlists |
| `acceptSetlistFmMatch` | Setlists |
| `rejectSetlistFmMatches` | Setlists |
| `selectSetlist` | Playlist |
| `markSelectedOwnership` | Playlist |
| `discoverFriendFromPlaylist` | Playlist |
| `findCandidates` | Playlist |
| `updateMatch` | Playlist |
| `toggleIncluded` | Playlist |
| `chooseCandidate` | Playlist |
| `setPlaylistName` | Playlist |
| `setPlaylistPublic` | Playlist |
| `dismissCreated` | Playlist |
| `researchSong` | Playlist |
| `createPlaylist` | Playlist |
| `removePlaylist` | Playlist |
| `loadCoverCandidates` | Playlist |
| `setCover` | Playlist |
| `setCoverFrame` | Playlist |
| `refreshCoverCandidates` | Playlist |
| `uploadCover` | Playlist |
| `refreshWitnessed` | Gossip |
| `gossipContactsChanged` | Gossip |
| `blockGossip` | Gossip |
| `stopGossip` | Gossip |
| `selectGossipGig` | Gossip |
| `resumeGossip` | Gossip |
| `refreshGossipPresence` | Gossip |

`selectSetlist` opens a Gig and fans out to Gig, GigMedia, Playlist and Setlists state; it
sits in Playlist because Android's does, and is the first candidate for characterization tests.

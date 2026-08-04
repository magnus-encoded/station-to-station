# Graph Report - .  (2026-08-04)

## Corpus Check
- 113 files · ~85,246 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1572 nodes · 3281 edges · 98 communities (84 shown, 14 thin omitted)
- Extraction: 84% EXTRACTED · 16% INFERRED · 0% AMBIGUOUS · INFERRED: 529 edges (avg confidence: 0.8)
- Token cost: 73,930 input · 0 output

## Community Hubs (Navigation)
- Friend Links & Nearby Peers
- Android Timeline Store
- iOS setlist.fm & Spotify Models
- iOS Test Suite
- iOS Spotify Client & PKCE
- Python setlist.fm CLI
- Android View Model
- Android Spotify Client
- Android Station Screen
- iOS Station View Drawing
- Spotify Models & Track Ranking
- iOS App Model
- Android Lane Geometry
- Gig Time State
- iOS QR Exchange Scanner
- iOS setlist.fm Decoding
- iOS Views & Navigation
- Android setlist.fm Client
- iOS Timeline Store
- iOS BLE Central
- iOS Friends Model
- Android Photo Repository
- iOS Auth Errors & Tokens
- iOS setlist.fm Client
- iOS Weave Timeline Tests
- iOS Settings & Credentials
- iOS Lane Geometry Tests
- iOS Confirm View
- Android Card Wire Format
- Android Screen Navigation
- Android Check-In Logic
- iOS Card Wire & Peers
- Android UI State & Gig Links
- Android Weave Timeline Tests
- Setlist Numbering Tests
- Weave Fixtures & Domain Terms
- iOS Setlist Model Helpers
- iOS Station Timeline Loading
- Android Gig Photo Actions
- Android Exchange Session
- iOS App Routes
- iOS App Model State
- iOS Setlists & Settings Views
- iOS Card Wire Tests
- Woven Row Ownership
- CI Workflows & Credentials
- Cross-Platform Fixtures & Language
- Android Exchange Screen
- iOS Friend Actions
- Android BLE Probe
- iOS Festival Grouping Tests
- iOS Codable Defaults
- Android setlist.fm Models
- Android Festival Screen
- iOS IPv4 HTTPS Client
- iOS Timeline Festivals
- Android Festival Grouping
- Check-In Window Tests
- Android Main Activity
- Android Confirm Screen
- P2P Friends & Media Terms
- iOS BLE Peripheral
- iOS Exchange Session
- iOS Friends View
- iOS Gig View
- Android Peer Merge Tests
- Python CI & Attendance Terms
- iOS Planned Gigs
- iOS Playlist Creation Flow
- Android BLE Peripheral
- Android Nearby Name Limit
- Android Setlist Helpers
- Android Weave Fixture Tests
- iOS Lane Staleness Tests
- iOS Song Matching
- Android BLE GATT Callbacks
- Android Gig Link Parsing
- iOS Config & Errors
- App Identity & Spotify Login
- iOS BLE Name & Chunking
- Android Device Location
- Android Calendar Insert
- Offset Formatting Tests
- Android Nested Scroll
- iOS Banner Modifier
- iOS Lane Width Tests
- iOS Exchange Card Actions
- Resolution Zoom Ladder
- Android Event Rows
- Android Swipe Back
- Spotify Endpoint Guard
- Gradle Wrapper Script
- Ship Script
- Android Maps Intent
- Android Timelines Screen

## God Nodes (most connected - your core abstractions)
1. `AppViewModel` - 114 edges
2. `AppModel` - 64 edges
3. `TimelineStoreTest` - 33 edges
4. `Settings` - 27 edges
5. `weaveTimelines()` - 27 edges
6. `Row` - 26 edges
7. `TimelineStoreTests` - 25 edges
8. `BleCardCentral` - 24 edges
9. `StationView` - 24 edges
10. `StationTimelineScreen()` - 22 edges

## Surprising Connections (you probably didn't know these)
- `Bundled credentials via Info.plist (SpotifyClientId, SetlistFmApiKey)` --semantically_similar_to--> `Bundled build-time credentials (Android)`  [INFERRED] [semantically similar]
  ios/project.yml → .github/workflows/android.yml
- `ASWebAuthenticationSession Spotify login` --semantically_similar_to--> `Spotify Authorization Code + PKCE login`  [INFERRED] [semantically similar]
  ios/README.md → android/README.md
- `Android CI workflow` --references--> `Station to Station (Android app)`  [INFERRED]
  .github/workflows/android.yml → android/README.md
- `Bundled build-time credentials (Android)` --shares_data_with--> `Spotify Authorization Code + PKCE login`  [INFERRED]
  .github/workflows/android.yml → android/README.md
- `setlistfm_cli.py user-attended invocation` --conceptually_related_to--> `Attended`  [INFERRED]
  .github/workflows/generate-concerts.yml → UBIQUITOUS_LANGUAGE.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Credentials baked in at build time across both platforms and CI** — _github_workflows_android_bundled_credentials, ios_project_bundled_credentials, _github_workflows_ios_credential_overrides, android_readme_spotify_pkce_login, android_readme_setlistfm_no_user_login [INFERRED 0.85]
- **Cross-platform weave contract: fixtures, store document, expected rows, iOS bundling** — fixtures_weave_readme_weave_fixtures, fixtures_weave_readme_timelines_json, fixtures_weave_readme_expected_json, fixtures_weave_readme_timelinestore, ios_project_fixture_deeplink [EXTRACTED 1.00]
- **The meeting grammar: two lines cross, join, and part** — ubiquitous_language_line, ubiquitous_language_spine, ubiquitous_language_lane, ubiquitous_language_crossing, ubiquitous_language_joined, ubiquitous_language_parting, ubiquitous_language_meeting_green [EXTRACTED 1.00]

## Communities (98 total, 14 thin omitted)

### Community 0 - "Friend Links & Nearby Peers"
Cohesion: 0.05
Nodes (19): decodeFriends(), encodeFriends(), Friend, friendFromUri(), gigIdFromInvite(), gigInviteUri(), Uri, sfmStamp() (+11 more)

### Community 1 - "Android Timeline Store"
Cohesion: 0.09
Nodes (7): FmSetlist, Provenance, StoredAttendance, StoredPlaylist, TimelineCache, TimelineStore, TimelineStoreTest

### Community 2 - "iOS setlist.fm & Spotify Models"
Cohesion: 0.05
Nodes (45): CodingKey, Decodable, ArtistSearchResponse, SetlistsResponse, FmArtist, FmSetlist, CodingKeys, accessToken (+37 more)

### Community 3 - "iOS Test Suite"
Cohesion: 0.08
Nodes (14): SpotifyEndpointGuardTests, .source, Me, StationSnapshotTests, .repoRoot, URL, FmSetlist, URL (+6 more)

### Community 4 - "iOS Spotify Client & PKCE"
Cohesion: 0.08
Nodes (25): ASPresentationAnchor, ASWebAuthenticationPresentationContextProviding, ASWebAuthenticationSession, AuthenticationServices, CryptoKit, AddTracksResult, Array, AuthAnchor (+17 more)

### Community 5 - "Python setlist.fm CLI"
Cohesion: 0.08
Nodes (31): argument, command, group, option, pass_context, patch, artist(), cli() (+23 more)

### Community 6 - "Android View Model"
Cohesion: 0.07
Nodes (4): AppViewModel, StateFlow, AndroidViewModel, Job

### Community 7 - "Android Spotify Client"
Cohesion: 0.11
Nodes (16): AddTracksResult, AddTracksResult, codeChallenge(), generateCodeVerifier(), ByteArray, PlaylistResponse, SimplePlaylist, SpotifyTrack (+8 more)

### Community 8 - "Android Station Screen"
Cohesion: 0.17
Nodes (32): AddPlannedGigDialog(), CheckInDialog(), EmptyTimeline(), EncoreLabel(), EventTag(), FuturePrompt(), gigInviteChooser(), GigPhotos() (+24 more)

### Community 9 - "iOS Station View Drawing"
Cohesion: 0.13
Nodes (28): CGSize, GraphicsContext, crossingX(), festivalDateRange(), laneColor(), laneXf(), lineDrawnOffset(), linesAt() (+20 more)

### Community 10 - "Spotify Models & Track Ranking"
Cohesion: 0.12
Nodes (17): PlaylistResponse, SimplePlaylist, SpotifyAlbum, SpotifyArtist, SpotifyTrack, SpotifyUser, TokenResponse, TrackPage (+9 more)

### Community 11 - "iOS App Model"
Cohesion: 0.12
Nodes (15): AppModel, Error, FmArtist, Int, SpotifyTrack, Void, Error, userMessage() (+7 more)

### Community 12 - "Android Lane Geometry"
Cohesion: 0.16
Nodes (22): crossingX(), hostLane(), joinedAt(), Dp, Friend, WovenRow, laneStep(), laneX() (+14 more)

### Community 13 - "Gig Time State"
Cohesion: 0.09
Nodes (10): formatCountdown(), GigTimeState, APPROACHING, DAY_OF, FUTURE, PAST, plannedStatus(), showsMediaBlock() (+2 more)

### Community 14 - "iOS QR Exchange Scanner"
Cohesion: 0.10
Nodes (20): AVCaptureConnection, AVCaptureMetadataOutput, AVCaptureMetadataOutputObjectsDelegate, AVCaptureVideoPreviewLayer, AVFoundation, AVMetadataObject, CoreImage.CIFilterBuiltins, QrExchangeBody (+12 more)

### Community 15 - "iOS setlist.fm Decoding"
Cohesion: 0.09
Nodes (20): Encodable, Encoder, FmCountry, DefaultCodable, KeyedDecodingContainer, Decoder, FmArtist, .id (+12 more)

### Community 16 - "iOS Views & Navigation"
Cohesion: 0.15
Nodes (14): .body, View, ProbeCard, ConfirmView, ExchangeView, .body, .looking, .qrExchange (+6 more)

### Community 17 - "Android setlist.fm Client"
Cohesion: 0.13
Nodes (8): ArtistSearchResponse, FmSetlist, SetlistsResponse, parseFestivalName(), parseSetlistId(), SetlistFmClient, FestivalNameParseTest, SetlistIdParseTest

### Community 18 - "iOS Timeline Store"
Cohesion: 0.17
Nodes (13): Codable, StoredAttendance, StoredPlaylist, Decoder, Double, FmSetlist, Int, Int64 (+5 more)

### Community 19 - "iOS BLE Central"
Cohesion: 0.15
Nodes (14): Any, CBCentralManager, CBCentralManagerDelegate, CBCharacteristic, CBManagerState, CBPeripheral, CBPeripheralDelegate, CBService (+6 more)

### Community 20 - "iOS Friends Model"
Cohesion: 0.14
Nodes (14): Hashable, decodeFriends(), encodeFriends(), Friend, .id, .shareURL, friendFromURL(), sfmStamp() (+6 more)

### Community 21 - "Android Photo Repository"
Cohesion: 0.23
Nodes (7): GalleryPhoto, Bitmap, ByteArray, Uri, PhotoRepository, requiredPermissions(), toJpeg()

### Community 22 - "iOS Auth Errors & Tokens"
Cohesion: 0.16
Nodes (10): AppError, .errorDescription, SpotifyForbidden, .errorDescription, String, .nilIfBlank, Double, formEncode() (+2 more)

### Community 23 - "iOS setlist.fm Client"
Cohesion: 0.17
Nodes (9): parseFestivalName(), SetlistFmClient, ArtistSearchResponse, Data, Date, FmSetlist, Int, SetlistsResponse (+1 more)

### Community 24 - "iOS Weave Timeline Tests"
Cohesion: 0.29
Nodes (5): Set, weaveTimelines(), .rows, FmSetlist, WeaveTimelinesTests

### Community 25 - "iOS Settings & Credentials"
Cohesion: 0.12
Nodes (13): Settings, .grantedScope, .hasBundledSetlistFmKey, .hasBundledSpotifyClientId, .mySetlistFmUser, .pkceVerifier, .refreshTokenValue, .setlistFmApiKey (+5 more)

### Community 26 - "iOS Lane Geometry Tests"
Cohesion: 0.24
Nodes (10): hostLane(), joinedAt(), nodeHost(), Friend, .lanes, LaneGeometryTests, .lanes, Bool (+2 more)

### Community 27 - "iOS Confirm View"
Cohesion: 0.17
Nodes (16): CandidatePicker, .body, CreatedSheet, .body, formatDuration(), SongMatchRow, .body, .label (+8 more)

### Community 28 - "Android Card Wire Format"
Cohesion: 0.18
Nodes (5): esc(), ByteArray, parseProbeCard(), ProbeCard, CardWireTest

### Community 29 - "Android Screen Navigation"
Cohesion: 0.14
Nodes (11): AppNavigation(), BleProbeScreen(), FriendTimelineScreen(), FriendsScreen(), ArtistTab(), SearchScreen(), UserTab(), SetlistsScreen() (+3 more)

### Community 30 - "Android Check-In Logic"
Cohesion: 0.21
Nodes (6): atVenue(), checkInCandidate(), cityCoords(), metersBetween(), CheckInCandidateTest, CheckInDistanceTest

### Community 31 - "iOS Card Wire & Peers"
Cohesion: 0.16
Nodes (11): Equatable, Identifiable, ExchangePeer, formDecode(), formEncode(), mergePeers(), PeerHit, sliceForOffset() (+3 more)

### Community 32 - "Android UI State & Gig Links"
Cohesion: 0.13
Nodes (11): CoverCandidate, GigLink, SETLIST, SINGLE_LINE, WOVEN, MediaThumb, parseGigLink(), SetlistSource (+3 more)

### Community 33 - "Android Weave Timeline Tests"
Cohesion: 0.30
Nodes (3): Friend, weaveTimelines(), WeaveTimelinesTest

### Community 34 - "Setlist Numbering Tests"
Cohesion: 0.28
Nodes (3): FmSet, FmSong, SetlistNumberingTest

### Community 35 - "Weave Fixtures & Domain Terms"
Cohesion: 0.18
Nodes (16): expected.json (weave rows, newest first), The weave (row-building algorithm, implemented twice), Absorb, Amber (mine), Crossing, Edge, Festival, Festival name (+8 more)

### Community 36 - "iOS Setlist Model Helpers"
Cohesion: 0.20
Nodes (8): FmSets, FmTour, FmVenue, FmSet, FmSetlist, Date, FmSong, Int

### Community 37 - "iOS Station Timeline Loading"
Cohesion: 0.20
Nodes (8): StationView, .body, .earliest, .menu, .showingLanes, .timeline, Bool, Task

### Community 39 - "Android Exchange Session"
Cohesion: 0.27
Nodes (7): ExchangePeer, ExchangeSession, friendFromCard(), Friend, ProbeCard, StateFlow, BleCardPeripheral

### Community 40 - "iOS App Routes"
Cohesion: 0.15
Nodes (14): App, Nav, Route, confirm, exchange, friends, gig, search (+6 more)

### Community 41 - "iOS App Model State"
Cohesion: 0.16
Nodes (11): FixtureDoc, SetlistSource, artist, user, SongMatch, .isCover, Bool, FmSetlist (+3 more)

### Community 42 - "iOS Setlists & Settings Views"
Cohesion: 0.15
Nodes (10): SetlistRow, .body, SetlistsView, .body, FmSetlist, Int, SettingsView, .body (+2 more)

### Community 43 - "iOS Card Wire Tests"
Cohesion: 0.28
Nodes (5): friendFromCard(), parseProbeCard(), ProbeCard, Friend, CardWireTests

### Community 44 - "Woven Row Ownership"
Cohesion: 0.14
Nodes (15): newestFirst(), RowOwnership, mine, theirs, together, Date, WovenRow, .date (+7 more)

### Community 45 - "CI Workflows & Credentials"
Cohesion: 0.18
Nodes (14): Android CI workflow, Bundled build-time credentials (Android), Unsigned debug APK artifact, No branch filter on push (CI is the only build loop), Credential overrides step (xcodebuild args), iOS CI workflow, Dynamic iOS Simulator selection, Timeline snapshot artifact (instrumentation, never a gate) (+6 more)

### Community 46 - "Cross-Platform Fixtures & Language"
Cohesion: 0.18
Nodes (14): StationSnapshotTests, Fixtures live outside android/ and ios/ on purpose, timelines.json fixture (TimelineCache document), TimelineStore / TimelineCache, Weave fixtures (cross-platform contract documents), station-to-station://fixture/<name> seeding, SetlistToSpotifyTests unit-test target, CFBundleURLTypes: station-to-station and legacy setlist2spotify (+6 more)

### Community 47 - "Android Exchange Screen"
Cohesion: 0.24
Nodes (13): android, ConnectingBeat(), ExchangeScreen(), FriendRow(), Bitmap, ExchangePeer, Friend, LookingForPeople() (+5 more)

### Community 49 - "Android BLE Probe"
Cohesion: 0.19
Nodes (7): BleCardCentral, ExchangeTiming, ByteArray, PeerHit, sliceForOffset(), writeAtOffset(), ScanResult

### Community 50 - "iOS Festival Grouping Tests"
Cohesion: 0.32
Nodes (5): FmCity, FmVenue, groupIntoFestivals(), FestivalGroupingTests, FmSetlist

### Community 51 - "iOS Codable Defaults"
Cohesion: 0.22
Nodes (13): BoolFalse, DefaultValueProvider, EmptyArray, .defaultValue, EmptyString, EmptyStringMap, .defaultValue, ExpiresDefault (+5 more)

### Community 52 - "Android setlist.fm Models"
Cohesion: 0.18
Nodes (12): ArtistSearchResponse, FmArtist, FmCity, FmCoords, FmCountry, FmSet, FmSets, FmSong (+4 more)

### Community 53 - "Android Festival Screen"
Cohesion: 0.29
Nodes (12): absorbs(), festivalDateRange(), FestivalItem(), festivalName(), hosts(), Color, Dp, FmSetlist (+4 more)

### Community 54 - "iOS IPv4 HTTPS Client"
Cohesion: 0.22
Nodes (9): IPv4Https, Response, Data, Error, Int, URL, Network, Result (+1 more)

### Community 55 - "iOS Timeline Festivals"
Cohesion: 0.29
Nodes (12): absorbs(), festivalName(), hosts(), laneReachesBack(), sameFestival(), Bool, FmSetlist, TimelineNode (+4 more)

### Community 56 - "Android Festival Grouping"
Cohesion: 0.30
Nodes (4): Concert, Festival, groupIntoFestivals(), FestivalGroupingTest

### Community 57 - "Check-In Window Tests"
Cohesion: 0.29
Nodes (4): canCheckInManually(), FmSetlist, withinCheckInWindow(), CheckInWindowTest

### Community 58 - "Android Main Activity"
Cohesion: 0.25
Nodes (6): AppTheme(), Intent, MainActivity, Bundle, ComponentActivity, KeyEvent

### Community 59 - "Android Confirm Screen"
Cohesion: 0.31
Nodes (10): CandidatePicker(), ConfirmScreen(), ConfirmScreenContent(), CoverPicker(), formatDuration(), Bitmap, SongMatch, Uri (+2 more)

### Community 60 - "P2P Friends & Media Terms"
Cohesion: 0.22
Nodes (11): Gallery photo playlist cover, Bluetooth + camera usage descriptions for the Exchange, Serverless peer-to-peer friends model, Attach (attach is share), Audience, Card, Contact, Exchange (+3 more)

### Community 61 - "iOS BLE Peripheral"
Cohesion: 0.24
Nodes (6): CBATTRequest, CBPeripheralManager, CBPeripheralManagerDelegate, CoreBluetooth, BleCardPeripheral, UUID

### Community 62 - "iOS Exchange Session"
Cohesion: 0.36
Nodes (6): DispatchWorkItem, ExchangeSession, ExchangePeer, Friend, ProbeCard, Void

### Community 63 - "iOS Friends View"
Cohesion: 0.25
Nodes (5): Friend, URL, FriendsView, .body, URL

### Community 64 - "iOS Gig View"
Cohesion: 0.29
Nodes (8): EventRow, encore, song, eventRows(), GigView, .body, FmSetlist, Int

### Community 65 - "Android Peer Merge Tests"
Cohesion: 0.38
Nodes (3): PeerHit, mergePeers(), ExchangeMergeTest

### Community 66 - "Python CI & Attendance Terms"
Cohesion: 0.28
Nodes (9): Python CI workflow, Generate Concerts Report workflow, setlistfm_cli.py user-attended invocation, click dependency, requests dependency, Attended, Pointer (BYOS link), Reconcile (+1 more)

### Community 69 - "Android BLE Peripheral"
Cohesion: 0.22
Nodes (5): BleCardPeripheral, ProbeCard, truncateToBytes(), BluetoothGattServer, BluetoothLeAdvertiser

### Community 70 - "Android Nearby Name Limit"
Cohesion: 0.25
Nodes (3): NearbyNameLimitProbe, EndpointDiscoveryCallback, DiscoveredEndpointInfo

### Community 72 - "Android Weave Fixture Tests"
Cohesion: 0.28
Nodes (5): Expected, Fixture, Friend, WovenRow, WeaveFixturesTest

### Community 73 - "iOS Lane Staleness Tests"
Cohesion: 0.42
Nodes (3): laneIsStale(), LaneStalenessTests, FmSetlist

### Community 75 - "Android BLE GATT Callbacks"
Cohesion: 0.46
Nodes (3): BluetoothGattCallback, BluetoothGatt, BluetoothGattCharacteristic

### Community 77 - "iOS Config & Errors"
Cohesion: 0.32
Nodes (4): Foundation, Config, .bundledSetlistFmApiKey, .bundledSpotifyClientId

### Community 78 - "App Identity & Spotify Login"
Cohesion: 0.38
Nodes (7): Playlist naming: year – artist – venue, setlist.fm has no user login (API key + public usernames), Spotify Authorization Code + PKCE login, Station to Station (Android app), station-to-station:// URL scheme, ASWebAuthenticationSession Spotify login, Station to Station (iOS SwiftUI port)

### Community 79 - "iOS BLE Name & Chunking"
Cohesion: 0.29
Nodes (3): nameFromManufacturerData(), Data, writeAtOffset()

### Community 81 - "Android Calendar Insert"
Cohesion: 0.47
Nodes (5): insertCalendarEvent(), FmSetlist, Uri, primaryCalendarId(), ContentResolver

### Community 83 - "Android Nested Scroll"
Cohesion: 0.40
Nodes (4): NestedScrollConnection, NestedScrollSource, Offset, Velocity

### Community 84 - "iOS Banner Modifier"
Cohesion: 0.33
Nodes (3): Content, BannersModifier, ViewModifier

### Community 85 - "iOS Lane Width Tests"
Cohesion: 0.53
Nodes (5): laneStep(), stripWidth(), CGFloat, Int, .laneWidth

### Community 87 - "Resolution Zoom Ladder"
Cohesion: 0.40
Nodes (5): Festival resolution, Gig resolution, My timeline, Resolution (zoom ladder rung), Timelines resolution

### Community 88 - "Android Event Rows"
Cohesion: 0.67
Nodes (4): Encore, EventRow, eventRows(), SongItem

### Community 89 - "Android Swipe Back"
Cohesion: 0.50
Nodes (3): Dp, Modifier, swipeRightToBack()

### Community 91 - "Gradle Wrapper Script"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **117 isolated node(s):** `ARTIST`, `USER`, `SETLIST`, `SINGLE_LINE`, `WOVEN` (+112 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **14 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `String` connect `iOS Auth Errors & Tokens` to `iOS setlist.fm & Spotify Models`, `iOS Test Suite`, `iOS Spotify Client & PKCE`, `iOS Station View Drawing`, `iOS App Model`, `iOS QR Exchange Scanner`, `iOS setlist.fm Decoding`, `iOS Views & Navigation`, `iOS Timeline Store`, `iOS BLE Central`, `iOS Friends Model`, `iOS setlist.fm Client`, `iOS Weave Timeline Tests`, `iOS Settings & Credentials`, `iOS Confirm View`, `iOS Card Wire & Peers`, `iOS Setlist Model Helpers`, `iOS Station Timeline Loading`, `iOS App Model State`, `iOS Setlists & Settings Views`, `iOS Card Wire Tests`, `Woven Row Ownership`, `iOS Festival Grouping Tests`, `iOS Codable Defaults`, `iOS IPv4 HTTPS Client`, `iOS Timeline Festivals`, `iOS BLE Peripheral`, `iOS Friends View`, `iOS Gig View`, `iOS Lane Staleness Tests`, `iOS Config & Errors`, `iOS BLE Name & Chunking`?**
  _High betweenness centrality (0.360) - this node is a cross-community bridge._
- **Why does `AppViewModel` connect `Android View Model` to `Android UI State & Gig Links`, `Friend Links & Nearby Peers`, `iOS Planned Gigs`, `iOS Playlist Creation Flow`, `Android Gig Photo Actions`, `Android Station Screen`, `iOS Song Matching`, `Android Exchange Screen`, `iOS Friend Actions`, `iOS Exchange Card Actions`, `Android Festival Grouping`, `Check-In Window Tests`, `Android Main Activity`, `Android Confirm Screen`, `Android Screen Navigation`?**
  _High betweenness centrality (0.299) - this node is a cross-community bridge._
- **Why does `BleProbeScreen()` connect `Android Screen Navigation` to `Friend Links & Nearby Peers`, `Android BLE Peripheral`, `Android Nearby Name Limit`, `Android Station Screen`, `Android BLE Probe`, `Android Card Wire Format`?**
  _High betweenness centrality (0.090) - this node is a cross-community bridge._
- **Are the 63 inferred relationships involving `Text` (e.g. with `BleProbeScreen()` and `CandidatePicker()`) actually correct?**
  _`Text` has 63 INFERRED edges - model-reasoned connections that need verification._
- **Are the 5 inferred relationships involving `AppModel` (e.g. with `SetlistFmClient` and `Settings`) actually correct?**
  _`AppModel` has 5 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ARTIST`, `USER`, `SETLIST` to the rest of the system?**
  _117 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Friend Links & Nearby Peers` be split into smaller, more focused modules?**
  _Cohesion score 0.053544494720965306 - nodes in this community are weakly interconnected._
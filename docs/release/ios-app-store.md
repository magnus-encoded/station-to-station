# iOS: from sideload to App Store

Audit of what stands between today's unsigned sideload IPA and TestFlight / App Store.
Ordered by what blocks first. Each item is tagged:

- **[code]** found in code, with `file:line` (relative to repo root, at `473b610`).
- **[guess]** inference or knowledge of Apple/Spotify policy, not verified here.
- **Who:** `agent` (can be done in a PR) or `human` (Apple account, payment, legal, a dashboard).

## 1. Hard blockers: nothing uploads without these

- [ ] **Apple Developer Program membership (99 USD/yr).** **Who: human.**
  [code] CI builds unsigned on purpose: `.github/workflows/ios.yml:108-121`,
  `ios/README.md` ("A properly signed .ipa would additionally need an Apple Developer account").
  Decide individual vs. organisation enrolment: the seller name shown on the store is the
  enrolled legal name [guess]. Trader status under the EU DSA must be declared to sell in
  the EU, and a trader's address/phone becomes public [guess].
- [ ] **Bundle ids are under a namespace the human must own.** **Who: human to register, agent to change if needed.**
  [code] `io.github.magnusencoded.stationtostation` and `.ticketshare` (`ios/project.yml:38`, `:172`).
  [guess] Apple does not verify reverse-DNS ownership, so these are usable as-is once
  registered in Certificates, Identifiers & Profiles. Changing later is a one-way door
  (a new app record), so decide before the first upload.
- [ ] **App Group must be registered and enabled on both App IDs.** **Who: human (portal) or agent via fastlane `produce`/API key.**
  [code] `group.io.github.magnusencoded.stationtostation` on both targets
  (`ios/project.yml:55-59`, `:191-195`). [guess] With automatic signing Xcode can create it; in CI
  with manual profiles it must exist first, and both provisioning profiles must include it.
- [ ] **Signing is disabled in every target.** **Who: agent** (after human supplies a team id + secrets).
  [code] `CODE_SIGNING_ALLOWED: "NO"` on app (`ios/project.yml:46`) and extension (`:174`);
  no `DEVELOPMENT_TEAM` anywhere in `ios/project.yml`. Needs a Release config with
  `DEVELOPMENT_TEAM`, `CODE_SIGN_STYLE`, and the override kept only for simulator/test builds.
- [ ] **No Release archive / export path in CI.** **Who: agent** (secrets: human).
  [code] CI only does `xcodebuild build -configuration Debug` and zips `Payload/`
  (`.github/workflows/ios.yml:108-130`); that IPA cannot be uploaded. Needs
  `xcodebuild archive` + `-exportArchive` (method `app-store-connect`) or fastlane
  `gym` + `pilot`, triggered on tag/dispatch. Secrets the human must create: an App Store
  Connect API key (`.p8`, key id, issuer id), and either a distribution cert + profiles
  or fastlane `match` storage. No `Fastfile` exists [code: none found under `ios/`].
- [ ] **App Store Connect app record.** **Who: human.** Name, primary language, SKU, bundle id
  selection. [guess] "Station to Station" must be unused on the store; check early.
- [ ] **Build number must increase per upload.** **Who: agent.**
  [code] `CURRENT_PROJECT_VERSION: "1"` hardcoded (`ios/project.yml:20`); CI should set it
  from `github.run_number`. `MARKETING_VERSION: "1.12.0"` (`:19`) is fine.
- [ ] **Privacy manifest missing.** **Who: agent.**
  [code] No `PrivacyInfo.xcprivacy` in `ios/`. Required since May 2024 for apps using
  required-reason APIs; upload is rejected (ITMS-91053) without declared reasons [guess: policy].
  Required-reason APIs actually used:
  - `UserDefaults` — `ios/StationToStation/Data/Settings.swift:13`,
    `Data/Gossip/GossipTransport.swift:203-215`, `AppModel.swift:340` → reason `CA92.1`.
  - File timestamp/size via `FileManager.attributesOfItem` —
    `Data/Exchange/ContactSession.swift:136`, `Data/Exchange/HandoverSession.swift:102`
    (reads `.size` only) [guess: still counts as the file-timestamp category; declare `C617.1`
    or `0A2A.1` to be safe, or switch to `URLResourceValues.fileSize`].
  - `PHAsset.creationDate` (`Data/PhotoLibrary.swift:68`) is not a required-reason API [guess].
  - The extension (`TicketShare`) needs its own manifest if it touches `UserDefaults` [guess; not seen in code].
  Set `NSPrivacyTracking=false`, empty tracking domains, and collected-data types matching §3.
- [ ] **Export compliance.** **Who: agent adds the key; human answers the questionnaire once.**
  [code] Uses CryptoKit (AES-GCM in `Data/Gossip/GossipRecognition.swift:18-20`, signing in
  `Data/Exchange/ContactChallenge.swift:1`) and custom TLS over Network.framework
  (`Data/Exchange/HandoverWire.swift:317-330`). [guess] This is authentication/data-protection
  use of OS-provided crypto, which qualifies for the exemption; set
  `ITSAppUsesNonExemptEncryption: false` in Info.plist to skip the per-build prompt. The
  peer-to-peer TLS channel carrying user content is the one part a human should confirm
  against the questionnaire; if it is judged non-exempt, a France declaration and a yearly
  self-classification report apply.

## 2. Review-guideline risks: likely rejection or later pull

- [ ] **Spotify Developer Mode user cap (biggest product risk).** **Who: human.**
  [code] One shared client id baked in (`ios/project.yml:17`); Settings tells users to
  "ask for one of the five slots" (`ios/StationToStation/UI/SettingsView.swift:678-682`).
  [guess] A Development Mode Spotify app only works for allow-listed users; extended quota is
  granted to organisations, not hobby apps. A reviewer who is not allow-listed cannot log in to
  Spotify → playlist creation fails → rejection under 2.1 (app completeness). Mitigations:
  (a) review notes saying Spotify is optional and the core record works without it,
  plus a demo path; (b) bring-your-own client id (already supported in Settings,
  `SettingsView.swift:683`) explained in notes; (c) apply for extended quota.
- [ ] **4.2 Minimal functionality / reviewer can't reach the core.** **Who: agent (demo data path) + human (review notes).**
  [code] Core flows need a setlist.fm username/API key (`SETLISTFM_API_KEY: ""`,
  `ios/project.yml:18`; CI injects from a secret, `ios.yml:63-75`) and a second phone for
  Exchange/Reconcile. The `station-to-station://fixture/<name>` seed exists
  (`ios/project.yml:28-35`, `AppModel.swift:340`). [guess] Ship the release build with a
  setlist.fm key and give the reviewer a username with history, or a visible "try with sample
  data" entry. Fixture loading is only reachable via URL/argv, which a reviewer won't find.
- [ ] **Background Bluetooth (2.5.4).** **Who: human (review notes), agent (draft).**
  [code] `UIBackgroundModes: bluetooth-central, bluetooth-peripheral` (`ios/project.yml:95-97`)
  for Gossip. [guess] Reviewers ask for justification of background modes; explain the
  check-in relay in notes, ideally with a short video.
- [ ] **5.1.1 Permission strings.** **Who: agent.**
  [code] Present and purpose-specific: Bluetooth (`:85`), Camera (`:98`), Local network +
  Bonjour (`:111-115`), Photos (`:119`), Location when-in-use (`:124`), Calendars (`:132-137`).
  No Contacts/Microphone usage found [code: no `CNContact` in `ios/StationToStation`].
  [guess] Calendar: if only adding events, `NSCalendarsWriteOnlyAccessUsageDescription`
  + write-only request on iOS 17 is the reviewer-preferred shape over full access
  (check `Data/CalendarInsert.swift`). Photos: if `PHAsset.creationDate` scanning of the
  library (`Data/PhotoLibrary.swift:68-92`) asks for full access, the string should say the
  app looks for photos taken during a gig, not only "photos you attach".
- [ ] **5.1.1(v) Account deletion.** **Who: none, likely.** [guess] No app account exists
  (no server, per `docs/privacy-policy.md`), so the rule does not apply. Spotify/Clashfinder
  logins are third-party; keep a "log out / forget credentials" control.
- [ ] **Privacy policy URL.** **Who: human (enter URL), agent (keep current).**
  [code] `docs/privacy-policy.md` exists and states no server, no analytics. [guess] It is
  published on `magnus-encoded.github.io/station-to-station/`; App Store Connect needs the
  direct URL. Needs a contact address for data requests.
- [ ] **Third-party marks (4.1 / 5.2).** **Who: human (risk call).**
  [code] Logo image sets `settings_spotify`, `settings_setlistfm`, `settings_musicbrainz`,
  `settings_clashfinder` in `ios/StationToStation/Assets.xcassets/`. [guess] Spotify's
  branding rules require their logo/attribution when showing Spotify content; ensure use matches
  their guidelines and no screenshot implies endorsement. Don't use "Spotify" in the app name or keywords.
- [ ] **Test fixtures and debug surfaces ship in the bundle.** **Who: agent.**
  [code] `fixtures/weave` bundled as a resource (`ios/project.yml:33-35`); `UI/BleProbeView.swift`
  exists. [guess] Not a rejection risk alone; gate the probe behind `#if DEBUG`.
- [ ] **Hardcoded third-party credentials in the binary.** **Who: human (risk call).**
  [code] Spotify client id in `ios/project.yml:17` (PKCE, no secret, so fine). The setlist.fm
  API key injected at build time (`ios.yml:70`) becomes extractable from a public binary
  [guess]; check setlist.fm's API terms allow that for a distributed app.

## 3. App Privacy ("nutrition label") answers

Derived from network code. Hosts contacted [code]: `api.spotify.com`, `accounts.spotify.com`
(`Data/Spotify/SpotifyClient.swift:129,151`), `api.setlist.fm` (`Data/SetlistFm/SetlistFmClient.swift:76`),
`musicbrainz.org` (`Data/MusicBrainzClient.swift:77,99`), `clashfinder.com`
(`Data/Clashfinder/Clashfinder.swift:28`). No analytics/crash SDKs [code: no
Crashlytics/Sentry/ATT/IDFA/`identifierForVendor` matches]. Peer-to-peer data goes to other
users' phones, not to the developer.

Proposed answers [guess, human must confirm]:

- **Data used to track you:** none.
- **Data collected by the developer:** "No, we do not collect data from this app." Apple
  defines "collect" as transmitted off-device in a way the developer or third-party partners can
  access beyond real-time servicing. Calls to Spotify/setlist.fm/MusicBrainz/Clashfinder are
  user-initiated lookups to services the user has their own relationship with [guess: Apple
  counts third-party SDKs/partners, arguably not independent services the user logs in to].
  Conservative alternative: declare "User Content: Other" (setlist/playlist names sent to
  Spotify) and "Location: Coarse" only if location ever leaves the device. [code]
  `Data/DeviceLocation.swift` is used for on-device venue comparison (`project.yml:122-126`);
  verify it is never sent.
- **Gossip / Exchange:** check-ins and cards go device-to-device; not developer collection [guess].
- **Keychain:** tokens `AfterFirstUnlockThisDeviceOnly` (`Data/KeychainStore.swift:53`) — on-device only.

## 4. Store listing assets

- [ ] **App icon.** **Who: none.** [code] Single 1024×1024 RGB PNG, no alpha
  (`ios/StationToStation/Assets.xcassets/AppIcon.appiconset/icon-1024.png`) — meets the
  marketing-icon requirement.
- [ ] **Screenshots.** **Who: agent** (simulator + fixture deep link, e.g. a fastlane `snapshot`
  or `xcrun simctl io screenshot` job). [guess] Required: 6.9" iPhone set; iPad 13" set too,
  because the app declares iPad (`TARGETED_DEVICE_FAMILY: "1,2"`, `ios/project.yml:39`).
  Dropping iPad (`"1"`) halves this and avoids iPad-layout review — a human product call.
- [ ] **Description, keywords, support URL, age rating, category (Music).** **Who: agent drafts, human enters/approves.**
- [ ] **Review notes + demo instructions** (see §2). **Who: agent drafts, human submits.**

## 5. Path to TestFlight (suggested order)

1. Human: enrol, create App ID ×2 + App Group, create App Store Connect record, create API key, add secrets.
2. Agent: `PrivacyInfo.xcprivacy` (both targets), `ITSAppUsesNonExemptEncryption=false`,
   Release signing settings in `project.yml`, build-number from CI, `#if DEBUG` probe.
3. Agent: new workflow (`ios-testflight.yml`, dispatch/tag only) — archive, export, upload with
   `xcrun altool --upload-app` or fastlane `pilot`, authenticated with the API key.
4. Human: internal TestFlight testing needs no review; external testers need Beta App Review
   (same guideline risks as §2, especially Spotify login).
5. Human: App Privacy answers, privacy URL, export compliance, submit for review.

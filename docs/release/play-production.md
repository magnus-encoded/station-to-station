# Android: from the alpha track to production

Audited on 2026-10-06 against `main` at 473b610 (1.12.0). This is research only. Nothing here is implemented.

**Tags:**
- **[code]** means the claim was found in the code, cited as file:line.
- **[guess]** means Play policy as the auditor remembers it, or an inference. Check every [guess] in the Play Console before acting on it.

**Who:**
- **agent** means an agent can do it from the repo.
- **human** means it needs the Play Console account, a legal decision, or real people.

Items are ordered by what blocks first. Paths under `data/` and `ui/` are relative to
`android/app/src/main/java/io/github/magnusencoded/stationtostation/`.

## 1. Production access gate (personal developer account)

- [ ] **Account type and creation date.** [guess] Personal accounts created after 13 Nov 2023
  cannot publish to production until a closed test has had at least 12 opted-in testers for
  14 days in a row. After that, you apply for production access from the Dashboard.
  Organisation accounts are exempt. **Who: human.**
- [ ] **Alpha tester count.** [code] Alpha is a closed track, and CI publishes there by default
  (`.github/workflows/android-release.yml:27`, `DEFAULT_PUBLISH_TRACK: alpha`). Only the Console
  shows whether 12 opted-in testers have stayed for 14 days. [guess] Testers who opt out
  break the streak. **Who: human:** recruit the testers and send them the opt-in link. This is the
  longest item (at least 14 days), so start it first.
- [ ] **Apply for production access** once the 14 days are up. [guess] Review takes about 7 days.
  **Who: human.**

## 2. Sensitive-permission declarations

- [ ] **Photo and video permissions declaration.** This is the highest rejection risk.
  - [code] The manifest declares `READ_MEDIA_IMAGES` and `READ_MEDIA_VISUAL_USER_SELECTED`
    (`android/app/src/main/AndroidManifest.xml:12-13`).
  - [code] The app requests them at `data/photos/PhotoRepository.kt:475-481`.
  - [code] The purpose is scanning the gallery for photos taken on the night of the show
    (`AndroidManifest.xml:6-8`).
  - [code] The system picker is already used in another flow (`ui/EventScreen.kt:577`).
  - [guess] Since 2025 Play allows broad photo access only when it is core to the app.
    Otherwise it expects the photo picker.

  Two options:
  - **Human:** file the declaration, with a video of the night scan.
  - **Agent:** go picker-only by dropping `READ_MEDIA_IMAGES`. That loses the automatic
    scan, so the human decides the product question first.
- [ ] **Foreground service declaration.**
  - [code] `GossipService` declares `foregroundServiceType="connectedDevice"`
    (`AndroidManifest.xml:44-45,148-151`).
  - [code] `targetSdk` is 36 (`android/app/build.gradle.kts:44`).
  - [guess] Play requires an FGS-type declaration with a video of the user-visible notification.
  - [guess] It may already be filed, since alpha has carried Gossip since 1.10.0 (`docs/privacy-policy.md:210`).

  **Who: human.** An agent can record the video over adb (`docs/agents/device.md`).
- [ ] **Location (foreground only).**
  - [code] The manifest declares `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` (`AndroidManifest.xml:55-56`).
  - [code] It deliberately omits `ACCESS_BACKGROUND_LOCATION` (`AndroidManifest.xml:50-53`).
  - [code] The app takes a single fix in the foreground (`data/DeviceLocation.kt:43,71`).

  [guess] No background-location declaration is needed. Still, state in the FGS
  declaration that the gossip service never reads location. **Who: human.**
- [ ] **Nearby, Bluetooth and calendar: nothing to file.**
  - [code] `BLUETOOTH_SCAN` and `NEARBY_WIFI_DEVICES` carry `neverForLocation`
    (`AndroidManifest.xml:33-35,59-61`).
  - [code] Calendar read and write are declared at `AndroidManifest.xml:19-20`.
  - [guess] None of these is in the restricted set that requires a form.
- [ ] **Contacts: nothing to file.** [code] There is no `READ_CONTACTS`. A Contact here is a peer exchanged
  in person, not the address book (`CONTEXT.md`).
- [ ] **Note.** [code] `android.hardware.bluetooth_le` is `required="true"` (`AndroidManifest.xml:31`), so
  devices without BLE cannot install the app. This limits reach but does not block release.

## 3. Data safety form

[code] There is no developer server, no analytics, no crash reporting and no ads (`docs/privacy-policy.md:9-11`).

[code] Network traffic goes only to services the user invokes:
- Spotify: `api.spotify.com` and `accounts.spotify.com`
- setlist.fm: `api.setlist.fm` (`data/setlistfm/SetlistFmClient.kt:68`)
- MusicBrainz (`data/musicbrainz/MusicBrainzClient.kt:84,94`)
- clashfinder
- Nearby and BLE peers

[code] The bundled SDKs are Google Play services Nearby (`android/app/build.gradle.kts:272`) and ML Kit
text recognition, which runs on device (`build.gradle.kts:268`).

[code] Location is never transmitted (`docs/privacy-policy.md:74`).

Suggested answers. Every row is a **[guess]** for the human to confirm:

| Question | Suggested answer |
|---|---|
| Collects or shares data? | Yes, a narrow set. Transfers the user starts and expects may be exempt from "shared". |
| Location | Not collected. It is only read on device. |
| Photos | Not collected. They are copied into app storage (`AndroidManifest.xml:152-154`). A Spotify playlist cover upload is user-initiated. |
| Name / user IDs | Shared with peers (the contact card), and with setlist.fm and Spotify (the usernames) (`docs/privacy-policy.md:13-20`) |
| Other user content (gigs, check-ins) | Shared with nearby peers by Gossip, ephemerally |
| Encrypted in transit | Yes for HTTPS. For BLE gossip, check ADR-0019 and `docs/gossip-public-wire.md`. |
| Deletion request | Not applicable. There are no accounts and all data is local. |
| SDK disclosures | Check Google's published Data safety entries for Nearby and ML Kit. |

- [ ] **Agent:** cross-check the table with the `play-policy-insights` skill.
- [ ] **Human:** submit the form.

## 4. Privacy policy URL

- [ ] The policy is published but the in-app link points at the site root.
  - [code] The policy lives at `docs/privacy-policy.md`, last updated 5 Oct 2026.
  - [code] Settings links to `https://magnus-encoded.github.io/station-to-station/` (`ui/SettingsScreen.kt:728`),
    the site root rather than the policy page.
  - [guess] Play needs a public URL that names the developer and gives a contact address.

  **Who:** an agent checks the live page. The human enters the URL in the Console and owns the legal text.

## 5. Other App content items

- [ ] **Content rating (IARC):** answer Yes to user-to-user sharing because of the peer exchange. [guess] **Who: human.**
- [ ] **Target audience:** 13+ or 18+, to stay out of the Families policy. [guess] **Who: human.**
- [ ] **Ads:** No. [code] There is no ads SDK in `build.gradle.kts:235-272`. **Who: human.**
- [ ] **App access:** there is no login wall. [guess] Add reviewer notes on how to see Gossip and check-in with one phone.
  **Who:** an agent drafts the notes and the human pastes them.

## 6. targetSdk deadline

- [x] **Already met.**
  - [code] `targetSdk = 36`, `compileSdk = 37` and `minSdk = 26` (`android/app/build.gradle.kts:39,43,44`).
  - [guess] Since 31 Aug 2026, Play requires target 35 for updates and 36 for new apps, so this passes.
  - [guess] The next bump is due around Aug 2027.

  **Who: agent**, later.
- [x] **64-bit only:** [code] `build.gradle.kts:185`. Nothing to do.

## 7. Store listing assets

- [ ] **No listing assets in the repo.**
  - [code] There is no `fastlane/metadata` and no feature graphic.
  - [code] `tools/publish_play.py` uploads only the bundle and the release notes (`tools/publish_play.py:1-10`).
  - [code] `tools/render_icon.py` exists for the launcher icon.
  - [guess] Production needs a short description, a full description, a 512px icon, a 1024x500
    feature graphic and at least 2 phone screenshots. Some may already be on the alpha
    listing; only the Console shows that.

  **Who:** an agent takes screenshots over adb and drafts the copy from `docs/personas.md`. The human approves and uploads.

## 8. Release signing and the CI path

- [x] **Play App Signing with an upload key.** [code] The repo holds only the upload key
  (`build.gradle.kts:77-80`). CI restores it from secrets, checks it, and verifies the
  signatures (`android-release.yml`, "Restore the upload keystore" through "Verify both artifacts are signed").
- [x] **versionCode** is the commit count. [code] Tags come from main (`tools/to-release.md`).
- [ ] **Promotion to production.**
  - [code] A manual dispatch accepts `track: production` (`android-release.yml:16-23`).
  - [code] An already-uploaded versionCode is released without being uploaded again.
  - [code] `tools/publish_play.py:47` notes that production wants a staged roll-out.

  **Who: agent:** check that the script sets `userFraction`.

  [guess] The first production release may have to be started in the Console, because the
  API is refused until production access is granted. **Who: human** dispatches.
- [ ] **Open testing (beta).** [code] `beta` is a dispatch option. [guess] Open testing is also
  locked until production access is granted. The order is therefore: closed test, then
  access, then optional beta, then production. **Who: human.**

## Human-only summary

1. Confirm the account type, then run the closed test: 12 or more opted-in testers for 14 days.
2. Apply for production access.
3. Decide on photo access: file the declaration, or go picker-only.
4. File the connectedDevice FGS declaration.
5. Fill Data safety, content rating, target audience, ads and app access.
6. Set the privacy policy URL and own its legal text.
7. Approve and upload the store listing assets.
8. Dispatch the production run.

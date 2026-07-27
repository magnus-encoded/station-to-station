# Setlist to Spotify (iOS)

A native SwiftUI port of the Android app in this repository — same features, same
serverless friends model, so an iPhone user can be tested against (and use) the
same peer-to-peer flow. It lets you:

1. **Find setlists** — search for an artist, or load the concerts you marked as
   attended with your setlist.fm user ID.
2. **Pick a setlist** to convert.
3. **Confirm the Spotify matches** — every song is looked up on Spotify. Include/
   exclude songs, pick an alternative match, or re-search manually. Covers are
   searched under the original artist; tape (intro/outro) tracks are excluded by
   default.
4. **Create the playlist** in your Spotify account and open or share it.

**Friends** (peer-to-peer, no server): set your setlist.fm username, share your
friend card (`setlist2spotify://friend?…`), add a friend by username or from a
shared Spotify playlist link, and tap a friend to see concerts you both attended.

## Logins

- **Spotify** — one-tap "Log in with Spotify" via `ASWebAuthenticationSession`
  (Authorization Code + PKCE, no client secret). The Client ID is baked in at
  build time via the `SPOTIFY_CLIENT_ID` build setting (set the `SPOTIFY_CLIENT_ID`
  repo secret for CI). Register the app at
  <https://developer.spotify.com/dashboard> with redirect URI
  `setlist2spotify://callback`. With no bundled ID, Settings falls back to manual
  Client ID entry.
- **setlist.fm** — no user login: an API key (baked in via `SETLISTFM_API_KEY`, or
  entered in Settings) plus public usernames.

## Building

The Xcode project is generated from [`project.yml`](project.yml) with
[XcodeGen](https://github.com/yonaskolb/XcodeGen) — there is no committed
`.xcodeproj`.

```sh
cd ios
brew install xcodegen   # once
xcodegen generate       # writes SetlistToSpotify.xcodeproj + Info.plist
open SetlistToSpotify.xcodeproj
```

Or from the command line (what CI does — a simulator build needs no signing):

```sh
xcodebuild build -scheme SetlistToSpotify -sdk iphonesimulator
xcodebuild test  -scheme SetlistToSpotify -destination 'platform=iOS Simulator,name=iPhone 15'
```

Requires Xcode 15+ (iOS 16 deployment target). CI (`.github/workflows/ios.yml`)
builds an unsigned simulator `.app` and uploads it as an artifact — the direct
analog of the Android job's debug APK. A signed device `.ipa` would additionally
need an Apple Developer account and signing secrets.

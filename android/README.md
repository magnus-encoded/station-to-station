# Station to Station (Android)

The Android app. It lets you:

1. **Find setlists** — search for an artist, or load the concerts you marked as
   attended with your setlist.fm user ID.
2. **Pick a setlist** to convert.
3. **Confirm the Spotify matches** — every song in the setlist is looked up on
   Spotify. You can include/exclude songs, pick an alternative match from the
   candidate list, or re-search Spotify manually for a song before anything is
   created. Covers are searched under the original artist, and tape
   (intro/outro) tracks are excluded by default.
4. **Pick a cover** — the app looks in your gallery for photos taken on the
   night of the show and offers them as the playlist cover. Gallery access is
   only requested when you tap to look, and skipping it leaves Spotify's own
   album-art collage.
5. **Create the playlist** in your Spotify account (created as private) and
   open it directly in Spotify.

Playlists are named `year – artist – venue` (for example
`2026 – Trivium – Ekebergsletta`), so an alphabetical playlist library falls
into chronological order. The full date, city, tour and a link back to the
setlist live in the playlist description.

## Logins

- **Spotify** — one-tap "Log in with Spotify" (Authorization Code + PKCE, no
  client secret). Uploading a photo cover needs the `ugc-image-upload` scope, so
  a login made before covers existed must be renewed once: log out in Settings
  and log back in. Settings says so when that applies. The Spotify Client ID is baked in at build time via the
  `SPOTIFY_CLIENT_ID` Gradle property / environment variable (set the
  `SPOTIFY_CLIENT_ID` repo secret for CI builds). Register the app at
  <https://developer.spotify.com/dashboard> with redirect URI
  `station-to-station://callback`. If a build has no bundled ID, the Settings
  screen falls back to manual Client ID entry.
- **setlist.fm** — the setlist.fm API has **no user login** (no OAuth, no
  Google): it uses an API key plus public usernames. The key is baked in the
  same way via `SETLISTFM_API_KEY` (or entered in Settings). To find your
  username, the "My concerts" tab links to setlist.fm's own sign-in page,
  which supports Google login.

## Building

Open the `android/` directory in Android Studio (Ladybug or newer), or build
from the command line with the Android SDK installed:

```sh
cd android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and Android SDK 37 (`compileSdk = 37`, `targetSdk = 36`).

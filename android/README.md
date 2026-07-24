# Setlist to Spotify (Android)

An Android companion app for the setlist.fm CLI in this repository. It lets you:

1. **Find setlists** — search for an artist, or load the concerts you marked as
   attended with your setlist.fm user ID.
2. **Pick a setlist** to convert.
3. **Confirm the Spotify matches** — every song in the setlist is looked up on
   Spotify. You can include/exclude songs, pick an alternative match from the
   candidate list, or re-search Spotify manually for a song before anything is
   created. Covers are searched under the original artist, and tape
   (intro/outro) tracks are excluded by default.
4. **Create the playlist** in your Spotify account (created as private) and
   open it directly in Spotify.

## Logins

- **Spotify** — one-tap "Log in with Spotify" (Authorization Code + PKCE, no
  client secret). The Spotify Client ID is baked in at build time via the
  `SPOTIFY_CLIENT_ID` Gradle property / environment variable (set the
  `SPOTIFY_CLIENT_ID` repo secret for CI builds). Register the app at
  <https://developer.spotify.com/dashboard> with redirect URI
  `setlist2spotify://callback`. If a build has no bundled ID, the Settings
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

Requires JDK 17+ and Android SDK 35.

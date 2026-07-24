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

## Setup

### setlist.fm API key

Request a free key at <https://api.setlist.fm/docs/1.0/index.html> and enter it
in the app under **Settings**.

### Spotify

The app uses the Authorization Code flow with PKCE, so no client secret is
needed — but you need your own Spotify application:

1. Create an app at <https://developer.spotify.com/dashboard>.
2. Add this redirect URI to the app: `setlist2spotify://callback`
3. Copy the **Client ID** into the app's Settings and tap **Connect Spotify**.

## Building

Open the `android/` directory in Android Studio (Ladybug or newer), or build
from the command line with the Android SDK installed:

```sh
cd android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and Android SDK 35.

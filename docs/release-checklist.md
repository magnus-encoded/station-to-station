# Getting Station to Station onto Google Play

State as of 12 August 2026, evening. Written to be readable at a festival with
no laptop.

## The shape of it

Personal developer accounts created after 13 Nov 2023 cannot publish to
production until they have run a closed test with **12 testers opted in
continuously for 14 days**. Open testing and pre-registration are both behind
that same gate, so neither is available yet.

The 14 days starts when the **twelfth tester opts in**, not when the track goes
live. Uploading starts the review clock only.

Realistic path to a public link: recruit at Øya, 14 days from the twelfth
opt-in, apply for production, production review. Early September, not this
weekend.

## Done

- Package name `io.github.magnusencoded.stationtostation`, locked on first upload
- Upload keystore created, Play App Signing on first upload
- Signed bundle built, keyed, verified
- Content rating: Teen / PEGI Parental Guidance / 12+
- Data safety: User IDs and Photos, each collected and shared, optional, app
  functionality. Verified line by line against the code
- Privacy policy live at
  https://magnus-encoded.github.io/station-to-station/privacy-policy.html
- Sign-in details: Spotify burner supplied to Google
- Icon, feature graphic, three screenshots
- Listing copy drafted

## Remaining, in order

1. **Add the Spotify burner to Spotify's User Management allowlist.**
   Blocking. The burner is what Google's reviewer will use, and a
   non-allowlisted account is refused at `/authorize`. Without this the
   reviewer meets an error where the login should be.

2. **Create the Google Group for testers**, set so anyone can join. This is the
   recruiting mechanic: a plain email list means typing addresses in a field,
   a group means someone taps a link and is a tester.

3. **Upload the bundle** and submit for review. Review must clear before anyone
   can install, so this happens tonight, not tomorrow morning.

4. **Recruit at Øya.** Aim well past 12, ideally 20+. Continuity is the fragile
   part: dropping below 12 breaks the window and restarts the wait.

## Facts that bite

**versionCode must always exceed the last accepted one**, or Play rejects the
upload. ~~versionCode is 1. Bump `appVersionCode` in `android/gradle.properties`
before every subsequent upload.~~ No longer done by hand: `android-release.yml`
derives it from `git rev-list --count HEAD` and passes it in as `VERSION_CODE`
(see the comment in `android/app/build.gradle.kts`). `appVersionCode=1` in
`android/gradle.properties` is now only the fallback for local and debug builds.
The consequence to remember is that tags must be cut from `main` — a tag on a
branch that is behind produces a lower count than the last upload.

**The bundle has the setlist.fm key baked in.** That was scoped to a small
cohort sharing 1440 requests/day. Do not promote this bundle to production
unchanged: production needs a fresh build with the key dropped, or a proxy, or
the raised quota if it lands. When CI does tagged publish builds, give the
closed-testing job the `SETLISTFM_API_KEY` secret and the production job
nothing, so a keyed production build becomes impossible rather than merely
discouraged.

**Do not post the signup link on socials yet.** One key, 1440 requests/day, and
a first import is the most expensive call there is. A post that does well means
strangers whose first launch shows them nothing. Socials after the quota
increase, or after the 12 are secured.

**Spotify is capped at 5 users, permanently.** Not a development-mode phase
that ends. As of 15 May 2025 Spotify only accepts quota extension requests from
registered organisations, and the bar includes 250k monthly active users. As an
individual publisher there is no path to it. The five slots are all there will
ever be: one is you, one is the reviewer burner, three remain. Spend them
deliberately.

The core of the app is unaffected: import, timeline, photos, notes, comparing
timelines over Bluetooth. None of it touches Spotify. Keep Spotify export out
of the store listing's headline features — advertising something five people
can use earns one-star reviews from everyone else — and mention it in the
release notes instead, so nobody finds a dead button on their own.

If Spotify export ever needs to be a real feature, the options are: register a
business entity and clear their bar, or drop the Spotify dependency and export
somewhere without a partner gate. Not a decision for this weekend.

**Keystore.** `android/upload-keystore.jks`, alias `upload`, credentials in
`android/keystore.properties`. Both gitignored. Back the .jks up somewhere off
this machine. Losing it is recoverable (Play can reset an upload key) but
tedious. The setlist.fm key lives in `~/.gradle/gradle.properties`, outside the
repo.

## Where things are

```
bundle       android/app/build/outputs/bundle/release/app-release.aab
icon         ~/Downloads/play-icon-512.png
feature      ~/Downloads/feature-graphic.png
screenshots  ~/Downloads/screenshot-1-timeline.png
             ~/Downloads/screenshot-2-festival.png
             ~/Downloads/screenshot-3-setlist.png
```

Rebuild:

```
JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
cd android && ./gradlew.bat bundleRelease
```

## Release notes for this build

```
First public build. Expect rough edges.

Spotify export is limited during this test. Everything else works.

Please report anything broken.
```

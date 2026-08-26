# Measuring on the device

Builds are made in CI and installed over Wi-Fi, never built locally. Three things
about that arrangement have already produced a wrong conclusion each, so they are
written down here rather than rediscovered.

## Never judge performance from the CI APK

The artifact the everyday loop produces is `assembleDebug` (`app-debug`), and a debuggable APK is
roughly a hundred times janker than the same code with the flag off. Measured on a
Pixel 7 Pro, same commit of `main`, same eight scripted swipes down the
**Timeline**, only `isDebuggable` differing:

|                  | `debuggable=true` | `debuggable=false` |
| ---------------- | ----------------- | ------------------ |
| Janky frames     | 52/373 = 13.9%    | 1/883 = **0.11%**  |
| 50th percentile  | 10ms              | **5ms**            |
| 90th percentile  | 26ms              | **8ms**            |
| 99th percentile  | 69ms              | **12ms**           |
| Missed Vsync     | 25                | **0**              |
| Slow UI thread   | 50                | **1**              |

The GPU sat at 1–2ms in both — it is never the bottleneck. A debuggable APK has to
keep every method deoptimizable so a debugger can attach, which blocks inlining and
holds ART to conservative JIT. Note the frame counts: the non-debuggable build
keeps up with the 120Hz panel and the debuggable one cannot.

Note also that R8 is off in **both** build types (`release { isMinifyEnabled = false }`),
so the usual debug-versus-release folklore about shrinking does not apply here. The
flag is the whole story.

This reading — "20% janky frames, UI-thread bound, GPU idle" — looks exactly like a
composition problem in the **Timeline** and is not one. Do not optimise against a
number taken from the debug APK.

That second artifact now exists: the `measure` build type is the debug build with
`isDebuggable = false` and nothing else moved, and `android.yml` builds it on every
run as `app-measure` (`./gradlew assembleMeasure`). It keeps the `.debug` package id
and the committed debug key, so it installs straight over a debug build, and its
`versionName` ends in `-measure` so you can tell which of the two is on the phone.
Take performance numbers from that one, never from `app-debug`.

To take a real number:

```sh
adb shell dumpsys gfxinfo <pkg> reset
for i in 1 2 3 4 5 6 7 8; do adb shell input swipe 540 1800 540 500 250; done
adb shell dumpsys gfxinfo <pkg> | grep -iE "Total frames|Janky frames:|percentile"
```

Let the app settle a few seconds after launch first, or cold-start cost roughly
doubles the numbers. `Number High input latency` is inflated by injecting swipes
over wireless adb and can be ignored; the percentiles are measured on-device and
cannot be.

## `BuildConfig.DEBUG` follows `isDebuggable`, not the build type's name

AGP derives it from the flag. Setting `isDebuggable = false` on the `debug` build
type silently switches off everything behind `if (BuildConfig.DEBUG)` — today that
is `logWovenRows`, the **Woven** geometry dump, which is the instrument the whole
of #116 exists to provide. So flipping the flag is not the single-variable
experiment it looks like.

It happened not to disturb the measurement above, because the dump is called from
`LaunchedEffect(rows, lanes)` and so never re-runs during a scroll — but that is a
fact worth checking rather than assuming next time.

## The version on screen does not say which branch is on the phone

`versionName` is `appVersionName` (see `android/gradle.properties`) with
`.GITHUB_RUN_NUMBER` appended on debug and measure builds, and that counter is global and
monotonic across the Android workflow. Any PR touching `android/**` bumps it,
including one whose feature is unrelated — so **a higher version can be an older
feature set**. A build from an iOS-media branch (which touches `android/` for a
shared rule) outranked the geometry branch under test and looked newer.

Identify a build by a feature it carries, not by its number. Download from a named
run rather than reusing whatever is already installed:

```sh
gh run list --branch <branch> --workflow "Android CI" --limit 1 --json databaseId --jq '.[0].databaseId'
gh run download <id> -n app-debug -D <dir>
```

Then confirm after installing. For geometry work, `adb logcat -s Woven` prints the
header `geometry in dp at laneWidth=… rowHeight=…` only on builds that have it.
`BuildConfig.GIT_SHA` carries the commit for the same reason.

## What the Pixel cannot currently show

The device is the reference for anything visual, but a **Spine** with no
**Crossing** in it exercises none of the interesting geometry — no meeting green,
no merge, no stroke weight above one person. Seeding a **Spine** from a bundled
weave fixture (`station-to-station://fixture/<name>`) is an iOS-only entry point
today; `TimelineLogic` says so, and the reasoning was sound when Android had no
use for it. It has one now.

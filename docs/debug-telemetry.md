# Debug feature quality

The Android **debug** APK records real OpenTelemetry spans locally. Release and measure
variants compile a no-op and contain no telemetry SDK. The debug package is
`io.github.magnusencoded.stationtostation.debug`; it coexists with Play and has separate
app data. Use the debug app for the usage sample to describe your actual experience.

## What is observed

- `app.ready`: Activity instrumentation begins → launch state becomes ready.
- `screen.enter` / `screen.visible`: allowlisted Navigation routes and visible duration.
- `feature.loading`: observed busy-state intervals for search, setlists, planning,
  timelines, Contact Line, song matching, playlist creation, cover and media suggestions.
- `feature.error`: rising error-present signal, associated with the current route.
- `app.crash`: uncaught exception occurrence; messages and stack traces are omitted.
- `app.main_thread_stall`: foreground heartbeat delayed over five seconds. This is a
  watchdog signal, not an OS-confirmed ANR. A debugger can also cause it.

StateFlow can conflate short transitions. A settled loading flag does not prove success;
interrupted intervals are counted separately. These are observation signals rather than
complete analytics funnels. No operation is declared failed merely because an unrelated
snackbar appeared. Routes inside the same screen and individual gestures are not tracked.

No queries, names, Gig IDs, Contacts, media, URLs, entered text, credential values,
exception messages, stack traces or persistent device identifiers enter the export.
A random process identifier groups spans from one process; it resets at restart.
OTLP JSON batches live under `no_backup/telemetry`, with three files of roughly 2 MiB
and seven-day expiry checked on export. The SDK queue is bounded to 512 spans; batches
flush every second. Process death or a full queue can lose recent samples. Export errors
never interrupt the app. No network exporter, backend or additional permission is added.

## Collect and inspect

With the Pixel connected through adb:

```sh
python3 tools/telemetry/quality_report.py --model Pixel_11_Pro --output /tmp/station-quality.md
```

Use `--model Pixel_11_Pro` to follow that phone across wireless port changes, or
`--serial HOST:PORT` for an exact device. Model selection refuses missing or ambiguous targets. The tool reads only the
telemetry files using debug `run-as`, deduplicates span IDs, tolerates an incomplete last
batch and atomically updates a Markdown report. A disconnected device makes collection
fail and preserves the previous report; check the report's generated and latest-trace times.

The report prioritizes crashes, stalls and error signals, then loads exceeding two seconds.
It shows sample counts and withholds p95 with fewer than five settled samples. A feature
with no evidence is unassessed. Reproduce a candidate in its actual flow, diagnose the
cause, add meaningful coverage, fix it and compare the same flow afterward. Confirm frame
or latency performance using the **measure** build as described in
[measuring-on-device.md](measuring-on-device.md); debug instrumentation is diagnostic.
Never optimize against debug frame timings or infer low value from low feature usage.

A local user timer can run collection every five minutes while this computer and adb are
available. It produces evidence for the next quality review; it does not run an AI agent
or silently modify app behavior. Disable collection with
`systemctl --user disable --now station-quality.timer`.

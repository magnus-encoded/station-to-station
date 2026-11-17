# Lane freshness fixtures

Whether a **Contact**'s **Lane** needs a setlist.fm fetch when the strip opens (#405):
`laneNeedsFetch` in `Bill.kt` and `Bill.swift`, clause for clause. `LaneFreshnessTest`
(Android) and `LaneFreshnessTests` (iOS) assert `cases.json` case for case. Fixtures are
required, never skipped if missing. Synthetic only.

Each case gives:

- `username`: the Contact's setlist.fm username. Blank is no username.
- `held`: the Lane I already hold, as the `dd-MM-yyyy` dates of its Nights. `null` (or
  left out) is nothing held; `[]` is a Lane setlist.fm answered with no Nights in it.
- `fetch`: what `laneNeedsFetch` must answer.

## What these cannot show

The loop this replaces is two passes, not one: a Contact with no Nights is fetched, the
empty answer is held, and the second pass must not fetch again. That needs `holdLanes`
between the passes, so both suites assert it in code
(`a contact with no nights is not fetched on a second pass`) rather than through this file.

# Tour cases

The script specification is [#587](https://github.com/magnus-encoded/station-to-station/issues/587).
`cases.json` encodes every Shared cases row. Skip covers S1–S20; resume and
out-of-order events cover every waiting step, S1–S19. S20 is terminal.

Each case starts from `initial` (otherwise unstarted, online), then drives `checks`
in order. `restart` is a harness action: persist and reconstruct state before the
next event. Each `expect` asserts the step and ordered emitted commands; other
fields assert only the named observations. `expected` holds whole-case results.
Command strings use the epic's notation; `venue` is the demo Exchange location.
S18 includes `returnedFromPhotos` before `mediaAdded`, whose visibility is chosen
by the user. The fixture harness may supply the location and visibility without
changing the script sequence.

`completedEffects` lets resume cases assert that completed lookup/import work is
not repeated. Fill cases supply stub responses and assert the added songs separately
from the user's two existing entries. No service calls are needed.

Android's `TourFixtures.load()` and iOS's `TourFixtures.load()` expose the same JSON
to their test targets. Their smoke tests validate loading and corpus coverage;
script behavior is asserted by the platform Tour implementation tests.

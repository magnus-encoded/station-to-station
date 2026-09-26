# setlist.fm question fixtures

The question a candidate row asks when the room is in doubt (#531):
`setlistFmQuestion` in `SetlistFmLookupFlow.kt` and `SetlistFmLookupFlow.swift`, word
for word. `SetlistFmLookupFlowTest` (Android) and `SetlistFmLookupFlowTests` (iOS)
assert `cases.json` case for case. Synthetic only.

Each case gives `yourVenue` (the Gig's venue, or `null`), `fromTicket` (whether the Gig
has Admissions) and `hit`, a stored `pendingHits` element. `expect` is the question, or
`null` where the row shows `artist — venueLine — date` instead.

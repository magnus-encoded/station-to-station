# When a local Gig is next looked up on setlist.fm (#531)

Shared cases for the lookup schedule, asserted case for case by
`SetlistFmLookupScheduleTest.kt` and `SetlistFmLookupScheduleTests.swift`. Fixtures are
required, never skipped if missing.

Every instant is UTC and both suites read them in a UTC zone, so the 06:00 night boundary
is the same instant wherever the tests run. `night` is setlist.fm's `dd-MM-yyyy`.

The night of 25-09-2026 is the one most cases use. Its window opens at
`2026-09-25T00:00:00Z` and closes at `2026-09-26T06:00:00Z`, so the 14 days of looking
after it end at `2026-10-10T06:00:00Z`.

`due.json`: the automatic schedule. `expectDue` is when the next lookup is due, and `null`
means none is (not local, a pending chip, no date, or past the 14 days). A due time equal
to `now` means "look now".

- `participationUntil` is the Gig's entry from `gossipParticipationEnds`, with its `0`
  written as `null`.
- `sharedQuotaSpentAt` is the stored shared-quota refusal. It counts only when `sharedKey`
  is true.

`manual.json`: a pull to refresh on a local Gig. The answer is `lookUpNow` or `friction`.

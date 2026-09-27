# setlist.fm lookup outcome fixtures

What one lookup of a local **Gig** comes to (#531): `setlistFmLookupOutcome` in
`SetlistFmLookupFlow.kt` and `SetlistFmLookupFlow.swift`. Rejected hits are dropped
before the matcher runs, and the stored lookup state that goes with the answer is
asserted whole. `SetlistFmLookupFlowTest` (Android) and `SetlistFmLookupFlowTests`
(iOS) read them from here.

**Synthetic only**, for `fixtures/setlistfm-match/`'s reason: every artist, venue and
id here is made up.

One directory per case:

- `ticket.json` and `search-setlists.json` — as in `fixtures/setlistfm-match/`.
- `before.json` — `now`, the lookup's time in epoch millis, and `lookup`, the stored
  `setlistFmLookup` before it, or `null` for a night never looked up.
- `expected.json`:
  - `outcome` — `adopt`, `ask` or `nothing`.
  - `adopt` — the setlist id adopted, when `adopt`.
  - `ask` — the candidates on the chip, best first, when `ask`.
  - `after` — the stored `setlistFmLookup` to write, in full, `pendingHits` included.
  - `about` — why the case exists. The suites ignore it.

Adding a case needs no code change, because the suites iterate this directory.

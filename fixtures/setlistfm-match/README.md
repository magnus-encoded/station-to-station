# setlist.fm match fixtures

The matcher from #531 exists twice — `SetlistFmMatch.kt` and `SetlistFmMatch.swift`.
These are the cases both copies must agree on, answer for answer and level for level.
Neither platform owns them. `SetlistFmMatchFixturesTest` (Android) and
`SetlistFmMatchFixtureTests` (iOS) read them from here.

**Synthetic only.** Every artist, venue, id and MusicBrainz id here is made up. Real
tickets carry names, order numbers and barcodes that get someone in the door, so the
real-ticket corpus (#531's test plan) lives somewhere else and is redacted before
anything from it lands in this repo.

One directory per case:

- `ticket.json` — what the parser handed over: `artist`, `venue` (optional) and `date`
  (`dd-MM-yyyy`, the day). `line` is optional and lists the artists already on the
  person's **Line** as `{ mbid, name }`. It is the input to the "same MusicBrainz id"
  rule.
- `search-setlists.json` — a `search/setlists` response exactly as setlist.fm shapes
  one, so a response recorded from the live API can be dropped in as it is.
- `expected.json`:
  - `outcome` — `linked`, `ask` or `noMatch`.
  - `linked` — the setlist id adopted, when `linked`.
  - `ask` — the candidates shown, best first, at most three, when `ask`.
  - `levels` — every hit that survives, by setlist id, with `artist`, `date` and
    `venue` each `strong`, `weak` or `noMatch`. `venue` is `null` when the ticket named
    no venue. A hit missing from `levels` must have been dropped, so drops are
    asserted too.
  - `about` — why the case exists. The suites ignore it.

Adding a case needs no code change, because the suites iterate this directory.

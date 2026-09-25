# setlist.fm match fixtures

The matcher that decides whether a **Ticket** links to a setlist.fm setlist exists twice,
once in Kotlin (`data/setlistfm/SetlistFmMatch.kt`) and once in Swift
(`Data/SetlistFm/SetlistFmMatch.swift`). These are the cases both copies must agree on
(#531, section 2 of its spec).

One JSON file per case:

- `ticket` — the fields a lookup has: `artist`, `venue` (or `null`) and `date` as
  `dd-MM-yyyy`, the one shape both platforms store a night in.
- `lineArtists` — the artists already on the person's **Line**, as `{mbid, name}`. A hit
  whose MusicBrainz id is the one held here for the ticket's artist is Strong on artist.
- `response` — a `search/setlists` response body, exactly as setlist.fm sends it. Each
  platform decodes it with its own client model, so a fixture is also a recorded response.
- `levels` — the expected level of each hit, by setlist.fm id: `artist` and `date` are
  `strong`, `weak` or `none` (and `none` drops the hit); `venue` is the same, or `null`
  when the ticket has no venue to compare.
- `expected` — `outcome` is `linked`, `ask` or `noMatch`, and `ids` is the linked id, the
  asked candidates best first, or empty.

`note` says what the case is for. Invented data throughout; the repository is public.

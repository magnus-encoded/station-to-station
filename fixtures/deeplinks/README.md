# Deep link fixtures

`station-to-station://<screen>[/<id>][/<action>][?params]` is read by `parseDeepLink`
into a `LinkIntent`, once in Kotlin and once in Swift. `cases.json` is the contract both
copies must agree on; neither platform owns it. Acting on an intent is plumbing and is
not tested here.

## Schema

`cases.json` is an array of cases:

```json
{ "link": "station-to-station://timeline?date=2026-08-07",
  "about": "optional: why this case exists",
  "intent": { "type": "open", "screen": "timeline", "date": "2026-08-07" } }
```

`intent: null` means the link does nothing. A key that is absent means null; no intent
carries an explicit null. Both platforms parse the link string whole, render the intent
to this shape, and compare, reporting every mismatch together.

| `type` | Keys | Link |
| ------ | ---- | ---- |
| `open` | `screen` (`timeline`, `timelines`, `programme`, `settings`), `date`? | the screen alone; only the first two take `?date=` |
| `openGig` | `id` | `gig/<id>` and the legacy `gig?id=<id>` |
| `addGig` | `artist`?, `venue`?, `date`? | `timeline/add-gig?…` |
| `writeToLog` | `gigId`, `appends` (texts, in order), `replacements` (song number → text) | `gig/<id>/write-to-log?…` |
| `legacyPlace` | `gigId`, `as` (`setlist`, `singleLine`, `woven`) | `<gigId>`, `<line>/<gigId>`, `Friends/<gigId>` |
| `me` | | `me` |
| `fixture` | `name`, `open` | `fixture/<name>[/open]` |
| `passThrough` | `kind` (`friend`, `handover`, `callback`, `ticket`) | the link goes unchanged to its own handler, whose parser is not this one |

## Rules

- **Screen and action names match case-insensitively**; ids and text keep their case.
- **Reserved screen names** are `timeline`, `timelines`, `gig`, `log`, `programme` and
  `settings`, checked before the legacy place grammar. `log` does nothing.
- **A place has one or two segments.** Anything else under a name that is not reserved
  does nothing: three segments under an unknown screen is an unknown screen.
- **`date`** is ISO `yyyy-MM-dd` and a real calendar day, or it is dropped and the rest
  of the link stands.
- **A text the link gave blank** is not given: `artist=` is no artist.
- **`write-to-log` items** are raw and in order. An item with no `=` is a song; `N=text`
  replaces song N, counting from 1. A number below 1, a non-number and an empty text are
  ignored. `&`, `=` and `#` in a song are percent-encoded, and `3` alone is the song "3".
- **`+` is a plus.** Spaces are `%20`.
- **`setlist2spotify://`** is the old scheme and reads the same.

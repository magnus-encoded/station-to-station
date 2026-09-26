# Ticket fixtures

A **Ticket** is read twice (#526): once from the PDF's own text layer and once by OCR.
`parseTicketFields` compares the two readings and picks the artist, the venue and the
date. It exists twice, in Swift and in Kotlin, and these are the cases both copies must
agree on. Neither platform owns them. This file is the contract: the schema, what each
platform's test does with it, and the rules the cases pin.

## Schema

One file per case:

```json
{
  "about": "what the case is, and which lines are real, placed or reconstructed",
  "readings": [{ "origin": "textLayer", "lines": ["…"] }, { "origin": "ocr", "lines": ["…"] }],
  "barcodes": [{ "symbology": "qr", "payload": "SYNTHETIC-…" }],
  "expected": {
    "artist": { "value": "…", "support": "both" },
    "venue": "unchecked",
    "date": { "value": "dd-MM-yyyy", "support": "ocr" },
    "barcode": "SYNTHETIC-…",
    "skipsPrompt": true
  },
  "knownFailure": { "artist": "why no rule gets this right yet" }
}
```

- **`readings`**: exactly what an extractor hands the parser, one entry per reader
  that produced text, so no PDF is needed. `origin` is `textLayer` or `ocr`. Lines are
  as captured, including a text layer's trailing `\r` (see *Lines*).
- **`barcodes`**: every barcode the evidence carries, in the order found. It may be
  empty. `symbology` is one of `qr`, `code128`, `ean13`, `ean8`, `upce`, `aztec`,
  `pdf417`, `datamatrix` (zxing's `QR_CODE` is `qr`, `CODE_128` is `code128`, and so
  on; Vision's `.qr` is `qr`). `payload` is the decoded payload as text. Every payload
  here is synthetic: never a real ticket's payload, and never a hash of one.
- **`expected`**: every key is always present.
  - `artist`, `venue`, `date`: `{value, support}`, or `null` when nothing may be
    found, or `"unchecked"` when the case deliberately asserts nothing about it.
    `date` is `dd-MM-yyyy`. `support` is `both` when both readings produced the value,
    otherwise the one origin that did (`textLayer` or `ocr`).
  - `barcode`: the payload of the one barcode the result carries, as text, or `null`.
  - `skipsPrompt`: whether a complete read may be added without asking, or
    `"unchecked"`.
- **`knownFailure`** (optional): a map from a field name (`artist`, `venue`, `date` or
  `skipsPrompt`) to the reason it is expected to fail on both platforms. The expected
  value is still the right answer.

### What each platform's test does

- Assert every field that has a value, including `null`. For `artist`, `venue` and
  `date` that means both the value and its support.
- `"unchecked"`: assert nothing about that field. It is used only where the answer
  depends on text nobody has seen: a line the probe redacted, or a placement that was
  reconstructed. Asserting it would pin the redaction rather than the rule.
- `knownFailure`: run that field's assertions as an expected failure, and strictly. The
  test fails if the field starts reading right, so the flag gets removed together with
  the fix. iOS wraps just that field's assertions in `XCTExpectFailure`'s block form. A
  platform without strict xfail asserts that the field is *not* the expected value.
  Every other field of the case is asserted as usual.
- Read every `*.json` in the folder, fail if there are fewer than the current count,
  and print how many ran.
- Android's `unsupportedBarcodeFormat` (#534) is outside this corpus. Don't assert it.

## Lines

Before any rule runs, each line is **tidied**. Runs of whitespace, including `\r` and
`\n`, collapse to one space. Then any of ` \t.,;-–—|·•` is trimmed off either end. A
colon is kept, because `Artist:` on its own line is a label. Lines that are empty after
tidying are dropped.

**Redaction marker.** `████` is a line the probe redacted. It has no letters and no
digits, so no rule can use it, and it keeps the real line count and positions. Both
parsers treat it like any other line, which in practice means ignoring it.

**Folding**, used wherever two lines are compared: lowercase, then keep only letters
and digits. `Rockefeller,Oslo` and `Rockefeller, Oslo` fold to the same key.

## The rules

### One reading on its own

1. **The date.** Every line that reads as a date is a date line, and no date line is
   ever a name. The night is **the first date line down the reading that is not a
   purchase date**. A purchase date is used only when the reading has no other date.
   - A **purchase date** is a date on a line containing one of `kjøp`, `bestil`,
     `ordre`, `order`, `purchase`, `booked`, `booking` (lowercased, matched inside
     words, so `Kjøpsdato`, `Ordered` and `Order date` count). It is also the value on
     the line under a label that ends in `:` and contains one of those words
     (`Kjøpsdato:`, then `01.05.2025`).
   - Why: the probe's Eventim text layer prints its order block above the event, so
     "first date down the page" took the purchase date (01-05-2025) instead of the
     night. A label is the generic tell, whatever the vendor. A purchase date is passed
     over but not thrown away, because with nothing else it is still the best-known
     date, and the person reviews the read anyway. Nothing prefers a date with a time
     of day: no case needs it, so it is not a rule.
2. **A labelled field**: `Artist: …` / `Venue: …` (labels `artist`, `artists`, `act`,
   `performer`, `performing`, `headliner` / `venue`, `location`, `place`, `where`,
   `hall`). The value can also be on the next line when the label stands alone. A label
   line is never guessed as anything else. The first match wins.
3. **`X at Y`** / `X live at Y` / `X @ Y`, on a line that is not a date line.
4. **Guessing**, for whatever is still missing. Only **guessable** lines are
   candidates. A guessable line:
   - has at least 2 letters and at most 80 characters
   - has no digit (a code, price, address or door time)
   - has no `/` (a seating category, `STÅPLASS/STANDING`)
   - doesn't end in `:`, `!` or `?` (a heading, or an ad's tagline)
   - is not a **ticket banner**: a caps line whose folded text contains `ticket`,
     `billett` or `biljett` (`TICKETLINE`, `BILLETTSERVICE`, `E-TICKET`). See below.

   A **caps line** is a guessable line where at least 4 in 5 letters are uppercase.
   - **Around the date:** when the reading has no caps line, and there is a guessable
     line both above and below the night's date line, the nearest guessable line above
     it is the artist and the nearest guessable line below it is the venue. Lines in
     between that aren't guessable are skipped. This is the "caps guard also applies to
     the lines around the date" in the spec, and Android's current neighbour check
     (non-blank, no trailing `:`) has to become this guard.
   - **Otherwise:** the caps lines in reading order, then the other guessable lines in
     reading order. The first is the artist and the second is the venue.

**The banner guard, and why it is a word.** The probe found `TICKETLINE` in the text
layer as well as in OCR, at line 0 of both, with `MORK WATER` two lines below. So
agreement between the readings can't keep it out. Its position can't either, because
readings have no page boundaries and a second page's masthead lands mid-reading. What
tells it apart is that it names the ticket. The words are the ticket's own vocabulary
in the app's languages, not a list of vendors (#441 keeps vendor lists out of scope).
Only caps lines are asked, so `The Ticketmen` in ordinary case is still a name.

### Two readings

Each reading gets candidates by the rules above. The text layer is guessed from all its
lines. OCR is guessed first from only the lines the text layer also has (by folded
key). Only for a field that leaves empty is OCR guessed again from all its lines, and
that second pass may not give one line both names. An OCR-only line is usually a
picture, such as a logo, so it may fill a field only when nothing the text layer also
shows could.

Then per field:
1. If the two candidates fold equal, the text layer's spelling wins, with support `both`.
2. If the text layer's candidate appears in some OCR line (folded, as a substring), it
   wins, with support `textLayer`.
3. If OCR's candidate appears in some text-layer line, it wins, with support `ocr`.
4. Otherwise, for names, the one with more letters wins with its own single support,
   and a tie goes to the text layer. For the date, the text layer's wins.

For the date, "equal" means the same day, and "appears" means that some line of the
other reading reads as that day. When only one reading has a candidate, that candidate
wins with its origin's support.

### The barcode, completeness and the prompt

- The evidence carries **every** barcode (`barcodes`). Until #441, the result carries
  one: **the first `qr` entry with a non-empty payload**. This is the first QR, not the
  first barcode, because real tickets show EAN and UPC candidates beside their QR (the
  probe, #441), and a Code 128 is not something the Room can redraw as a QR. So a
  ticket whose only codes are Code 128 (Eventim) is never complete.
- `isComplete`: the barcode, artist, venue and date are all present.
- `skipsPrompt`: complete, and either the source had one reading or every field has
  `both` support.

## The cases, and what is real

"Real" means device output, verbatim apart from redaction. "Placed" means real text put
back at a position the probe had redacted. "Reconstructed" means written by us. Each
case's `about` says which lines are which.

| Case | Readings |
|---|---|
| `dayof2-*` | The text layer is the one recorded in #526. The Vision and ML Kit readings are **reconstructed** (no PDF in the repo, and no iPhone probe yet). The garbled text layer is reconstructed. |
| `ticketline-*` | **Real** Pixel readings of `phone-future` (the Gig `f9c51210`), from the Android probe (2026-09-25). Redacted lines are the marker. The venue is `unchecked` because the probe couldn't confirm it, and `skipsPrompt` is `unchecked` because it depends on the venue. |
| `skambankt-billettservice-ocr-only` | **Real** ML Kit lines, page 1 of `real-eticket-2`. The probe's own lines are 11, 15, 17, 19, 25, 27 and 62. The rest of lines 0–25 are **placed** from the real ML Kit blocks in Android's `TicketParsingTest`, which match the probe's skeleton one to one. The buyer name and numbers are made up. |
| `skambankt-billettservice-text-layer-and-ocr` | **Real** text-layer skeleton: only `PARKTEATRET SCENE` survived redaction, and `SKAMBANKT` isn't a text-layer line at all. The date line is reconstructed. Only the date is asserted. |
| `dumdumboys-eventim-ocr-only` | **Real** ML Kit blocks from Android's `TicketParsingTest`. Personal data and the printed barcode number are made up. `knownFailure: artist`. |
| `dumdumboys-eventim-text-layer-and-ocr` | **Real** text-layer skeleton of `real-eventim-2` (3 real lines). The purchase-date line, the date, the venue and the address are **reconstructed**. It pins the date rule. `knownFailure: artist`, and the venue is `unchecked`. |
| `ocs-*` | **Real** Pixel readings of PDFs the probe generated (Ocean Colour Scene, not valid for admission). `ocs-flattened-ocr-only` is the OCR-only twin of every `ocs-*` case. `ocs-artist-image` has `knownFailure` on artist, venue and skipsPrompt (see *Decisions*). |
| `date-rule-*`, `guard-around-the-date` | **Synthetic**, written by hand to pin one rule each. |

Replace a reconstructed or redacted reading with a captured one whenever the real PDF
turns up, and drop `unchecked` and `knownFailure` as the evidence allows. The broader
corpus of real tickets matched against setlist.fm is #531; this is its parser-level
subset.

## Decisions left open

- **An artist drawn as an image** (`ocs-artist-image`). The rule that keeps an OCR-only
  logo out also keeps out an OCR-only artist whenever the text layer has any other
  candidate. Here both readings then agree on the wrong fields, and a complete read
  would skip the prompt. Fixing it means weakening the TICKETLINE protection, so the
  case is marked as a known failure until someone decides.
- **Dumdumboys**: the event line is `Dumdumboys – XL [romertallførti]`. Nothing generic
  says where the band's name ends.

# Ticket fixtures

A **Ticket** is read twice (#526): once from the PDF's own text layer and once by OCR.
`parseTicketFields` compares the two readings and picks the artist, the venue and the
date. It exists twice, in Swift and in Kotlin, and these are the cases both copies must
agree on. Neither platform owns them.

One file per case:

- `about`: what the case is, and which of its lines are real and which are reconstructed.
- `readings`: a list of `{origin, lines}`, with `origin` either `textLayer` or `ocr`.
  These are exactly what an extractor hands the parser, so no PDF is needed.
- `barcode` (optional): the decoded payload, as text.
- `expected`:
  - `artist`, `venue`, `date`: each `{value, support}`, or `null` when nothing is found.
    `date` is `dd-MM-yyyy`. `support` is `both` when both readings produced the value,
    otherwise the one origin that did (`textLayer` or `ocr`).
  - `skipsPrompt`: whether a complete read may be added without asking. It is only
    true when a barcode is present, and every field has `both` support or there was
    only one reading.

Each ticket has a case with both readings and a case with OCR only. The OCR-only case
is what a phone older than Android 15 gives (no text-layer API), and what a scan gives
anywhere.

## What is real

- **dayof2**: the text layer is the one recorded in #526. The OCR readings are
  reconstructed from it, because the PDF is not in the repo.
- **ticketline**: only `TICKETLINE`, `MORK WATER` and the date come from the Pixel's Gig
  `f9c51210`. **Unverified assumption:** the vendor name is drawn as an image, so only OCR
  sees it. If the real PDF has `TICKETLINE` in its text layer too, agreement alone will
  not keep it out, and the vendor-name guard in `isGuessable` is what does.
- **skambankt-insert** and **eventim**: the OCR lines are real ML Kit output, trimmed, from
  the Android tests the caps heuristic was tuned on. Their text layers are reconstructed.

Replace a reconstructed reading with a captured one whenever the real PDF turns up. The
broader corpus of real tickets matched against setlist.fm is #531; this is its
parser-level subset.

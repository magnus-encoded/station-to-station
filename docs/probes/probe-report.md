# Android ticket PDF evidence — Pixel 7 Pro

Measured 2026-09-25, Europe/Oslo. Base: `4436bd2`; branch: `probe/android-ticket-evidence`. Measurement only: no production source changes, ticket import, release installation, PR, or issue comment.

## Device and method

Pixel 7 Pro; `ro.build.version.sdk=37`, `ro.build.version.release=17`. Only `io.github.magnusencoded.stationtostation.debug` and its instrumentation APK were installed. Full capture passed (`OK (1 test)`, 233.684 s); the separate actual-production-renderer test passed (`OK (1 test)`, 1.224 s).

24 PDFs, 32 pages: eight local inputs and sixteen generated PDFs. Every page was rendered at 200 and 300 dpi; each bitmap ran unhinted ZXing, TRY_HARDER, and TRY_HARDER with GenericMultipleBarcodeReader, plus ML Kit OCR. Android text contents were collected in returned order. The probe has no page cap. Current-extraction simulation retains only the first unhinted 200-dpi result from the first five pages and pools their OCR blocks.

Raw PDFs, JSON, text, and previews remain in `~/ticket-probe-out/` (private directory with a deny-all .gitignore); raw JSON is under `raw/`, one file per PDF. Device copies remain in the debug app's external `files/probe/` directory. Real credentials and unreviewed personal text are not included here. Drafts conservatively replace even harmless unreviewed text; they are not complete semantic fixtures until locally reviewed. Unknown expected fields/support are intentionally blank.

The generated Ocean Colour Scene example uses the artist, venue, festival and date from the [setlist.fm entry](https://www.setlist.fm/setlist/ocean-colour-scene/2026/auditorio-marina-norte-valencia-spain-1b498dc4.html): Auditorio Marina Norte, Valencia, 25 September 2026, Visor Fest. It is labelled synthetic and not valid for admission. The PDF is local at `~/ticket-probe-out/inputs/synthetic-qr_code.pdf` and on the phone at `Download/ticket_ocs_1b498dc4.pdf`. Matching to setlist.fm was **not** executed; the linked-ID expectation is a proposed future matcher fixture. It replaces the requested generated Static Halo example.

## Answers to the nine questions

1. **Observed on device:** unhinted decoding missed all three Billettservice QR codes and both Code 128 codes in each Eventim PDF. TRY_HARDER found them at both resolutions. 300 dpi did not rescue those misses in unhinted mode. Multiple decoding found both generated codes on one page. Higher resolution changed additional EAN/UPC candidates, but did not establish extra admissions. The small synthetic corner QR worked even unhinted at 200 dpi; this is not proof all small codes work.

2. **Observed on device:** rawBytes differed from UTF-8 decoded text for the tested QR, Code 128, Aztec and Data Matrix examples. Passing rawBytes through an ISO-8859-1 String is not equivalent to encoding the decoded payload. The follow-up invoked main's actual `qrBitmap(..., 480)`, using its actual stored-bytes-to-String conversion. All tested real QR redraws decoded to different text. Eventim Code 128 redraws changed both text and symbology. **Yes: main redraws wrong barcodes for these observed inputs.** This establishes a payload/symbology defect, not the behavior of a venue's admission scanner. Some codes missed by today's extractor were tested counterfactually as if stored; those failures do not imply today's extractor actually saves them.

   Same-format default-text round trips preserved decoded text in 41 of 42 per-PDF distinct observations; the exception was the binary QR. These are observations, not 42 globally unique credentials. In the binary QR, the original six non-UTF-8 payload bytes were in BYTE_SEGMENTS, whereas rawBytes had 19 bytes and decoded-text-as-UTF-8 had nine. UTF-8 text encoding preserved decoded text but changed the byte segment. Re-encoding concatenated byte segments with ISO-8859-1 preserved the six bytes and decoded text in this example. Default text encoding preserved the segment bytes but changed decoded text. Neither “always rawBytes” nor “always UTF-8 text” is a universal binary-payload rule. MultiFormatWriter has a String input here; raw-byte experiments use an explicitly recorded reversible Latin-1 bridge, not a byte-array API.

3. **Observed on device:** real/local inputs include QR and Code 128. The confirmed Billettservice sample here has three QR codes, not the Code 128 anticipated in the handoff; Eventim has two Code 128 codes per PDF. ZXing decoded those with TRY_HARDER and redrew their decoded text in the same format losslessly by the text-equality criterion. Additional EAN_13, EAN_8 and UPC_E detections are **unverified candidates**, potentially unrelated printed codes or false positives. No admission validity was tested. Synthetic QR, Code 128, PDF417, Aztec and Data Matrix all decoded. “Lossless” here does not mean identical module patterns, raw decoder internals, or proven gate acceptance.

4. **Observed on device:** counts are below. Strongly supported ticket-code counts: Billettservice three distinct QR (today keeps zero); each Eventim PDF two distinct Code 128 (today zero); numbered ticket two distinct QR (today one); other local inputs one QR (today one). The union of all decoder candidates can be larger and is separately labelled. Generated repeated-three-pages contains one repeated payload; two-pages contains two distinct payloads, of which today's extraction keeps one. Payload hashes and per-page occurrences in each draft expose duplicates without revealing credentials.

5. **Observed on device:** Android text-layer access works on SDK 37. Relative to independently extracted Poppler text, word-multiset coverage was 100% on seven local inputs and 99.42% on the other Eventim PDF. This supports substantial legibility/completeness of embedded text, not visual or semantic completeness. All eight local inputs had different exact text-layer and OCR line sequences. No geometric sorting was applied. Text contents can contain several newline-separated lines; bounds belong to the returned content object, not necessarily each split line, and some bounds lists are empty. Flattened synthetic PDF has no nonempty text-layer lines. The image-only artist/vendor is absent from embedded text but appears in OCR.

6. **Observed on device:** in `phone-future`, TICKETLINE is in **both** text layer and OCR, not OCR alone. MORK WATER is likewise in both. Both occur on page 1, text-content object 0, at zero-based split-line indices 0 and 2 respectively (bounds empty); OCR line indices are also 0 and 2. All three parser inputs select TICKETLINE as artist and MORK WATER as venue. **Inferred from the supplied example's intended interpretation:** MORK WATER is the artist; the actual venue remains unconfirmed. Text-layer presence alone cannot establish how visible glyphs were drawn (a PDF can have a hidden text layer).

7. **Observed on device, expected-field judgments where known:** every PDF has a parser table below. Billettservice OCR lines recover Skambankt / Parkteatret Scene; pooled blocks do not, and text-only misses the artist. Eventim Dumdumboys is not recovered as artist by any input; text-layer order selects another printed date (01-05-2025), whereas pooled OCR and OCR lines select 28-11-2026. Image-only artist requires OCR; image-only vendor poisons OCR parsing while text-only succeeds. Flattened requires OCR. All three synthetic date formats parse correctly. Remaining real expected fields are unscored, not presumed correct. Completeness depends on a barcode as well as textual fields, so current extraction misses can make an otherwise useful parse incomplete. Empty-known-nights routing uses actual today 2026-09-25; same-day examples go to confirmation because routing requires a strictly future date.

8. **Inferred from production source, with exercised effects above:** the extraction already caps pages, chooses resolution, keeps only the first barcode, discards barcode type/segments/geometry, pools pages, and chooses OCR block rather than line granularity. Parsing then chooses dates and artist/venue using ordering and capitalization heuristics. Exact source references are listed below. Thus current plumbing is not interpretation-free.

9. **Inferred from production source:** #514's clipData fallback is **absent** in `handleTicketIntent`: ACTION_SEND uses EXTRA_STREAM then intent.data; ACTION_VIEW uses intent.data. Report only; not fixed.

## Production-source decisions

Paths below are relative to `android/app/src/main/java/io/github/magnusencoded/stationtostation/`, at base `4436bd2`.

| Source | Existing decision |
| --- | --- |
| data/TicketExtraction.kt:43,55 | First five pages only. |
| data/TicketExtraction.kt:46-50 | First successful barcode across pages; pooled OCR blocks; page association lost. |
| data/TicketExtraction.kt:60-72 | Approximately 200 dpi, white bitmap, display rendering. |
| data/TicketExtraction.kt:85-90 | Unhinted single-result reader; rawBytes preferred over UTF-8 text; type, points and metadata discarded. |
| data/TicketExtraction.kt:96-104 | ML Kit OCR, block strings rather than lines/bounds; OCR failure becomes no text. |
| data/TicketParsing.kt:88-153 | First detected date, exclusion of date-containing input blocks, ordering/capitalization selection of artist and venue. |
| data/TicketParsing.kt:170-178 | Shouty-label heuristic, not a vendor identity check. |
| data/TicketParsing.kt:210-234 | Date-format precedence within a block can outrank textual order; not simply the first date printed on the page. |
| data/TicketParsing.kt:299-313 | Completeness, existing-night matching, and strictly-future routing. |
| ui/StationScreen.kt:3580-3582 | Stored bytes decoded as ISO-8859-1 before redraw. |
| ui/ExchangeScreen.kt:567-584 | MultiFormatWriter always draws QR_CODE. |
| MainActivity.kt:125-133 | EXTRA_STREAM/data only; no clipData fallback. |

## Per-PDF observations

U/H/M are **distinct format + decoded-text-hash pairs across the PDF** in unhinted / TRY_HARDER / multiple mode. Union includes candidates from either resolution and is not an admission count. Per-page result counts and stage timings are in [measurements.json](measurements.json); format/hash occurrences and round-trip comparisons are in [fixtures-draft](fixtures-draft/).

Parser checks are artist/venue/date in that order: ✓ matches the draft expectation, ✗ differs or missing, ? unknown expectation. Redaction placeholders do not identify the original text. “Complete” and route are actual parser outputs with today's extraction payload, not improved decoder results. TL/OCR are nonempty text-layer lines / 200-dpi OCR lines. Times are a single debug run; barcode time includes luminance conversion but excludes round-trip experiments. Total includes experiments and parser calls. PSS is a sampled process maximum, **not** a true peak or PDF-only memory cost.

### phone-future

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 7/7; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 13.22 / 923.98 / 1557.03 / 2.65 |
| Total ms; sampled maximum PSS KiB | 3193.11; 321892 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | TICKETLINE | MORK WATER | 28-09-2026 | true | NewPlannedGig | ✗/?/✓ |
| textLayerLines | TICKETLINE | MORK WATER | 28-09-2026 | true | NewPlannedGig | ✗/?/✓ |
| ocrLines | TICKETLINE | MORK WATER | 28-09-2026 | true | NewPlannedGig | ✗/?/✓ |

### real-billettservice-candidate

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 34/39; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 339.39 / 1429.75 / 2380.66 / 2.36 |
| Total ms; sampled maximum PSS KiB | 5055.17; 515549 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] | — | false | NeedsConfirmation | ?/?/? |
| textLayerLines | [REDACTED_UNREVIEWED_TEXT] | [ORDER_OR_REFERENCE] | — | false | NeedsConfirmation | ?/?/? |
| ocrLines | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] | — | false | NeedsConfirmation | ?/?/? |

### real-eticket-2

| Measurement | Observed |
| --- | --- |
| Pages; formats | 3; QR_CODE, UPC_E |
| Distinct U/H/M at 200; 300 dpi | 0/3/3; 0/3/4 |
| Candidate union; today's retained count | 4; 0 |
| TL/OCR lines; identical sequence | 132/237; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 381.35 / 4975.7 / 49172.06 / 3.71 |
| Total ms; sampled maximum PSS KiB | 56460.97; 523390 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | KJØPTE BILL. REFUNDERES IKKE | [REDACTED_UNREVIEWED_TEXT] | 29-01-2015 | false | NeedsConfirmation | ✗/✗/? |
| textLayerLines | [REDACTED_UNREVIEWED_TEXT] | PARKTEATRET SCENE | 29-01-2015 | false | NeedsConfirmation | ✗/✓/? |
| ocrLines | SKAMBANKT | PARKTEATRET SCENE | 29-01-2015 | false | NeedsConfirmation | ✓/✓/? |

### real-eticket

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; EAN_13, EAN_8, QR_CODE, UPC_E |
| Distinct U/H/M at 200; 300 dpi | 1/1/5; 1/1/2 |
| Candidate union; today's retained count | 5; 1 |
| TL/OCR lines; identical sequence | 39/67; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 165.43 / 1267.51 / 16168.34 / 0.9 |
| Total ms; sampled maximum PSS KiB | 18896.49; 453377 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] | 27-06-2026 | true | NeedsConfirmation | ?/?/? |
| textLayerLines | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] | 27-06-2026 | true | NeedsConfirmation | ?/?/? |
| ocrLines | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] | 27-06-2026 | true | NeedsConfirmation | ?/?/? |

### real-eventim-2

| Measurement | Observed |
| --- | --- |
| Pages; formats | 2; CODE_128, EAN_8, UPC_E |
| Distinct U/H/M at 200; 300 dpi | 0/2/2; 0/2/5 |
| Candidate union; today's retained count | 5; 0 |
| TL/OCR lines; identical sequence | 84/96; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 235.37 / 2750.79 / 30749.89 / 2.86 |
| Total ms; sampled maximum PSS KiB | 34854.73; 472583 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] / [REDACTED_UNREVIEWED_TEXT] | 28-11-2026 | false | NeedsConfirmation | ✗/?/✓ |
| textLayerLines | [ORDER_OR_REFERENCE] | [REDACTED_UNREVIEWED_TEXT] | 01-05-2025 | false | NeedsConfirmation | ✗/?/✗ |
| ocrLines | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] | 28-11-2026 | false | NeedsConfirmation | ✗/?/✓ |

### real-eventim

| Measurement | Observed |
| --- | --- |
| Pages; formats | 2; CODE_128, EAN_8, UPC_E |
| Distinct U/H/M at 200; 300 dpi | 0/2/4; 0/2/3 |
| Candidate union; today's retained count | 5; 0 |
| TL/OCR lines; identical sequence | 82/89; false |
| Android TL word coverage vs Poppler | 99.42% |
| Render / OCR / barcode / text-layer ms | 203.48 / 2167.88 / 27520.79 / 2.45 |
| Total ms; sampled maximum PSS KiB | 30874.10; 580972 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | [REDACTED_UNREVIEWED_TEXT] | [ORDER_OR_REFERENCE] | 11-08-2024 | false | NeedsConfirmation | ?/?/? |
| textLayerLines | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] | 11-08-2024 | false | NeedsConfirmation | ?/?/? |
| ocrLines | [REDACTED_UNREVIEWED_TEXT] | [ORDER_OR_REFERENCE] | 11-08-2024 | false | NeedsConfirmation | ?/?/? |

### real-host-ticket

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 34/42; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 273.29 / 1497.44 / 2452.32 / 2.24 |
| Total ms; sampled maximum PSS KiB | 5091.66; 483519 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] | — | false | NeedsConfirmation | ?/?/? |
| textLayerLines | [REDACTED_UNREVIEWED_TEXT] | [ORDER_OR_REFERENCE] | — | false | NeedsConfirmation | ?/?/? |
| ocrLines | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] | — | false | NeedsConfirmation | ?/?/? |

### real-numbered-ticket

| Measurement | Observed |
| --- | --- |
| Pages; formats | 2; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 2/2/2; 2/2/2 |
| Candidate union; today's retained count | 2; 1 |
| TL/OCR lines; identical sequence | 74/95; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 176.24 / 3768.86 / 4876.73 / 4.36 |
| Total ms; sampled maximum PSS KiB | 10925.76; 563877 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] | 21-05-2024 | true | NeedsConfirmation | ?/?/? |
| textLayerLines | [REDACTED_UNREVIEWED_TEXT] | [ORDER_OR_REFERENCE] | 21-05-2024 | true | NeedsConfirmation | ?/?/? |
| ocrLines | [REDACTED_UNREVIEWED_TEXT] | [REDACTED_UNREVIEWED_TEXT] | 21-05-2024 | true | NeedsConfirmation | ?/?/? |

### synthetic-artist-image

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 4/5; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 94.41 / 1372.77 / 2640.39 / 0.48 |
| Total ms; sampled maximum PSS KiB | 4885.66; 569338 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Auditorio Marina Norte, Valencia | Synthetic test ticket - not valid for admission | 25-09-2026 | true | NeedsConfirmation | ✗/✗/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-aztec

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; AZTEC |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 5/5; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 89.99 / 1600.3 / 2808.28 / 0.81 |
| Total ms; sampled maximum PSS KiB | 5437.74; 523556 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-binary-qr

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 5/5; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 85.1 / 1018.44 / 2663.1 / 0.57 |
| Total ms; sampled maximum PSS KiB | 4752.68; 526168 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-code_128

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; CODE_128 |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 5/5; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 46.68 / 304.43 / 4049.99 / 0.3 |
| Total ms; sampled maximum PSS KiB | 4806.05; 598571 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-data_matrix

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; DATA_MATRIX |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 5/5; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 54.86 / 401.95 / 1652.15 / 0.31 |
| Total ms; sampled maximum PSS KiB | 2690.19; 527147 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-date-english

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 5/5; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 57.99 / 458.19 / 1640.65 / 0.3 |
| Total ms; sampled maximum PSS KiB | 2780.57; 489755 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 24-06-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 24-06-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 24-06-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-date-norwegian

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 5/5; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 54.2 / 364.34 / 1646.27 / 0.29 |
| Total ms; sampled maximum PSS KiB | 2666.57; 543417 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 28-11-2026 | true | NewPlannedGig | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 28-11-2026 | true | NewPlannedGig | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 28-11-2026 | true | NewPlannedGig | ✓/✓/✓ |

### synthetic-date-numeric

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 5/5; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 54.41 / 376.19 / 1765.31 / 0.35 |
| Total ms; sampled maximum PSS KiB | 2811.33; 524144 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 24-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 24-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 24-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-flattened

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 0/5; false |
| Android TL word coverage vs Poppler | N/A |
| Render / OCR / barcode / text-layer ms | 210.4 / 377.65 / 1681.26 / 0.07 |
| Total ms; sampled maximum PSS KiB | 2908.29; 501260 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | — | — | — | false | NeedsConfirmation | ✗/✗/✗ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-pdf_417

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; PDF_417 |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 5/5; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 43.68 / 331.2 / 2010.48 / 0.33 |
| Total ms; sampled maximum PSS KiB | 2775.10; 521833 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-qr_code

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 5/5; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 57.89 / 348.94 / 1807.24 / 0.29 |
| Total ms; sampled maximum PSS KiB | 2907.39; 613986 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-repeat-three

| Measurement | Observed |
| --- | --- |
| Pages; formats | 3; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 15/15; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 168.4 / 1047.83 / 5450.04 / 0.92 |
| Total ms; sampled maximum PSS KiB | 8370.75; 626690 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-small-corner

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 35/35; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 88.34 / 904.65 / 1891.44 / 2.77 |
| Total ms; sampled maximum PSS KiB | 3466.39; 647467 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-two-one-page

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/2; 1/1/2 |
| Candidate union; today's retained count | 2; 1 |
| TL/OCR lines; identical sequence | 5/5; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 79.2 / 358.21 / 3732.15 / 0.31 |
| Total ms; sampled maximum PSS KiB | 4931.93; 645507 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-two-pages

| Measurement | Observed |
| --- | --- |
| Pages; formats | 2; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 2/2/2; 2/2/2 |
| Candidate union; today's retained count | 2; 1 |
| TL/OCR lines; identical sequence | 10/10; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 124.77 / 868.56 / 3877.35 / 0.67 |
| Total ms; sampled maximum PSS KiB | 5975.38; 588167 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |

### synthetic-vendor-image

| Measurement | Observed |
| --- | --- |
| Pages; formats | 1; QR_CODE |
| Distinct U/H/M at 200; 300 dpi | 1/1/1; 1/1/1 |
| Candidate union; today's retained count | 1; 1 |
| TL/OCR lines; identical sequence | 5/6; false |
| Android TL word coverage vs Poppler | 100.00% |
| Render / OCR / barcode / text-layer ms | 73.9 / 435.63 / 2026.22 / 0.38 |
| Total ms; sampled maximum PSS KiB | 3122.80; 641167 |

| Input | Artist | Venue | Date | Complete | Route | A/V/D check |
| --- | --- | --- | --- | --- | --- | --- |
| pooledOcrBlocks | TICKETLINE | Ocean Colour Scene | 25-09-2026 | true | NeedsConfirmation | ✗/✗/✓ |
| textLayerLines | Ocean Colour Scene | Auditorio Marina Norte, Valencia | 25-09-2026 | true | NeedsConfirmation | ✓/✓/✓ |
| ocrLines | TICKETLINE | Ocean Colour Scene | 25-09-2026 | true | NeedsConfirmation | ✗/✗/✓ |

## Reproduction and limitations

Probe: [TicketEvidenceProbe.kt](../../android/app/src/androidTest/java/io/github/magnusencoded/stationtostation/TicketEvidenceProbe.kt). Redaction: [summarize_ticket_probe.py](../../android/app/src/androidTest/tools/summarize_ticket_probe.py).

Build from `android/` with `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`. Install only the debug and debug-test APKs. Choose the connected adb serial explicitly. Run the methods separately and in order:

```sh
adb -s DEVICE shell am instrument -w -e ticketProbe true -e prepareOnly true -e class 'io.github.magnusencoded.stationtostation.TicketEvidenceProbe#capture' io.github.magnusencoded.stationtostation.debug.test/androidx.test.runner.AndroidJUnitRunner
# Push approved PDFs with privacy-safe aliases into the app-created directory:
# /sdcard/Android/data/io.github.magnusencoded.stationtostation.debug/files/probe/inputs/
adb -s DEVICE shell am instrument -w -e ticketProbe true -e class 'io.github.magnusencoded.stationtostation.TicketEvidenceProbe#capture' io.github.magnusencoded.stationtostation.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s DEVICE shell am instrument -w -e ticketProbe true -e class 'io.github.magnusencoded.stationtostation.TicketEvidenceProbe#redrawStoredBytes' io.github.magnusencoded.stationtostation.debug.test/androidx.test.runner.AndroidJUnitRunner
# Pull files/probe/raw into the private local output directory, never the repo.
python android/app/src/androidTest/tools/summarize_ticket_probe.py /PRIVATE/ticket-probe-out/raw docs/probes/fixtures-draft
```

Opt-in is required; otherwise tests return without probing. Capture regenerates the sixteen synthetic PDFs and replaces same-named raw JSON. Do not run the entire class expecting ordered tests. Local input aliases intentionally omit order numbers; `real-billettservice-candidate` is a historical candidate name, **not vendor confirmation**. The confirmed Billettservice input is `real-eticket-2`; `real-eticket` is Ticketmaster. `phone-future` provenance is unverified. Expired/used inputs were explicitly approved by the user under the same privacy rules.

Summed PDF time was 230640.79 ms: 3171.99 rendering, 29351.19 OCR, 176220.14 barcode decoding, 30.68 text layer; remainder includes round trips, bookkeeping and parsing. Maximum sampled PSS was 647467 KiB. This is a single ordered, cold/warm-mixed debug run, not a benchmark or memory ceiling. No >5-page input was included, so the production cap is source evidence, not a measured long-PDF failure. Bounds from result points do not enclose full symbols/quiet zones; one-dimensional codes can have zero-height point boxes. Multiple-reader candidate counts are not proven exhaustive. No original scanner, hidden-layer authenticity, encrypted-PDF behavior, other Android version, or actual app import/share route was tested.

The draft schema follows [#526](https://github.com/magnus-encoded/station-to-station/issues/526)'s readings and expected-field shape, with probe metadata extensions, under docs only. Background: [#441](https://github.com/magnus-encoded/station-to-station/issues/441), [#531](https://github.com/magnus-encoded/station-to-station/issues/531), [#514](https://github.com/magnus-encoded/station-to-station/issues/514). No production fixes or shared-spec decisions are made here.

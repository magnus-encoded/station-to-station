# Timeline fixtures

`timelines.json` is read and written by both twins, and neither carries a key it does not
know on save (ADR-0020, #107). A field only one side understands survives until the other
side writes, and then it is gone. These files are the stored format written down once, so
each twin's test can load it, save it, and check that nothing was lost. Neither twin owns
them.

## `admissions/` (#441)

`timelines.json` holds two nights:

- **`g-admissions`**: the current shape. `StoredAttendance.admissions` holds two
  **Admissions**, a QR on page 0 and a corroborated Code 128 on page 1. Each is
  `{payload, symbology, page, corroborated}`, and `payload` is base64 of the decoded bytes.
- **`g-legacy`**: the shape before #441. A single `ticketQr`, base64, on a checked-in
  claim.

Each twin's test (`TimelineStoreTest` on Android, `TimelineStoreTests` on iOS) asserts:

1. `g-admissions` loads as exactly those two **Admissions**, in order.
2. `g-legacy` loads as exactly one: its payload, symbology `qr`, page 0, uncorroborated.
   The rest of its claim (`checked_in`, `checkedInAt`) is unchanged.
3. After a save and a reload, both nights still hold the same **Admissions**.
4. The written file has no `ticketQr` anywhere. Every written **Admission** has exactly
   the keys `payload`, `symbology`, `page` and `corroborated`.

Payloads are synthetic. Decoded, they are `SYNTHETIC-TIMELINE-QR-1`,
`000000000000000000000021` and `SYNTHETIC-LEGACY-QR`.

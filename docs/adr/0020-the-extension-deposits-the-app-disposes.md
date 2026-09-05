# ADR-0020: The extension deposits, the app disposes

**Status:** accepted (2026-09-05)

## Context

#412 adds the first iOS Share Extension this app has ever had, so that a PDF **Ticket** can be
shared into it from Mail or a wallet app. An extension is a **separate process** with its own
sandbox, and nothing in this codebase had ever needed to hand data across one before. The issue
said so explicitly and asked for the choice to be surfaced rather than made silently.

Three things constrain the answer.

**The store has exactly one writer, and its lock is a language feature.** `TimelineStore` is a
single `timelines.json` in Application Support, read-modify-written through `writeMerged`. Its own
doc comment says what the serialization is for: *"`save` is read-modify-write and several call sites
fire independently … without serialization two overlapping saves both read the old cache and the
loser's writes vanish."* The mechanism is a Swift `actor`, which serialises callers **within one
process and only within one process**. An extension writing that file is not a second call site; it
is a second writer with no lock at all, and the failure it produces is silent — a night that was
saved and then was not, discovered weeks later, with nothing on screen having looked wrong.

**A ticket PDF is not innocuous.** It carries the buyer's name, an order reference, sometimes a card
fragment, and a QR that is a credential for getting through a door. Whatever is written into a
shared container is readable by every target in the group and is in the device backup.

**The App Group may not exist at runtime.** This app is distributed as an unsigned Debug `.ipa` and
re-signed by whoever installs it. App Group entitlements are provisioned at signing time, and a free
Apple ID does not necessarily get them. `containerURL(forSecurityApplicationGroupIdentifier:)`
answers nil in that case, with no error and no warning.

## Decision

**The App Group container is a one-way drop box, not shared ownership of the store.**

- The extension **rasterizes, extracts, parses, and deposits** — one self-contained JSON file per
  shared **Ticket**, in `<group>/ticket-inbox/`, written to `.part` and renamed only once the bytes
  are down. It never opens `timelines.json`.
- The app **drains** — reads every deposit and deletes it in the same pass, on cold launch and on
  every foreground. It remains the only writer of the store, so nothing about `TimelineStore`
  changes and no cross-process lock is needed anywhere.

Each file therefore has exactly one writer and one reader, and `rename(2)` is atomic. That is the
whole of the concurrency story, and it is small on purpose.

**The PDF is never written down.** Parsing happens in the extension, so the document exists only in
that process's memory for as long as the share sheet is up. What crosses is four optional facts and
the QR payload, under complete file protection, deleted the moment it has been read.

**A missing container degrades; it does not crash.** `TicketInbox.directory` is optional at every
call site. The extension says it could not reach the app; the app simply finds an empty box.

### What was rejected

**The extension writing into `timelines.json` directly.** The issue offered this as an option
("or write directly into the shared store, whichever fits"). It is the one option the store's own
design forbids, for the reason quoted above.

**A custom URL scheme handoff, with no App Group at all.** The app already answers
`station-to-station://`, so this is the cheaper-looking option, and it is what the "simpler handoff"
in the issue would have meant. It fails on two counts. The payload is a QR of arbitrary bytes plus
free text, which does not belong in a URL. And a Share Extension cannot reliably open its host app:
`NSExtensionContext.open(_:)` is documented for widgets, and the responder-chain walk that works
today is exactly the trick App Review rejects. The App Group is the only sanctioned shared storage
between an extension and its host, so a design that avoids it is a design that avoids the mechanism
the platform provides.

**Confirming inside the extension.** Nicer to use — no app switch — but confirming means matching
against the nights already on the **Line**, which means reading the store from the extension, which
is the thing this ADR exists to prevent. The prompt belongs to the app.

## Consequences

- **The handoff is not instant, and this is visible.** The share sheet does not bring the app
  forward, so a **Ticket** sits in the box until the app is next opened. The extension says so in
  as many words rather than implying the night has already landed.
- **Draining is destructive**, so it must be called from one place per entry point. Two speculative
  drains racing would split one batch of tickets across two routing passes.
- **A signing identity without App Groups silently disables the feature.** The share sheet still
  offers the app and the extension still parses; the deposit fails and it says so. That is the
  honest failure, but it can only be verified on a device with a real signing identity — which is
  outstanding at merge time.
- **This is now the pattern for the next extension.** Anything else this app grows — a widget, an
  action extension — deposits into the same container and lets the app dispose of it. That is the
  point of writing it down: the alternative gets re-proposed as "it's only one small write".
- **Memory is the extension's real budget constraint.** Rasterizing a PDF page for Vision is the
  most expensive thing this app does anywhere, in the process that is given the least room. Pages
  past the third are not read and the long edge is capped; both numbers are in `TicketExtractor`
  with their reasoning.
- **Android has no equivalent decision to make.** Its share target is an `ACTION_SEND` intent into
  `MainActivity` — the same process, the same store, no boundary. The two platforms diverge here
  entirely below the logic line, which is what ADR-0001 says plumbing is for. The pure parse
  function above it is the same on both.

## A note on where the QR lands, which is *not* this decision

This ADR governs the boundary between the two processes and stops at the app's front door. Where the
QR is then written is settled by the twin that got there first: Android's #411 landed
`StoredAttendance.ticketQr`, base64, inside `gigAttendance`, and iOS writes that same field under
that same name. It is deliberately not a top-level key of iOS's own invention — the cache file is
read by both twins and Android has no unknown-key carrying on *save*, so a key only one side knew
would survive until the next write from the other and then vanish. That is the data loss #107 exists
to prevent, and it is the reason #414's first attempt at a persisted key was correctly reverted.

## Related

- ADR-0001 — the logic/plumbing split this sits under. `parseTicket` is logic; everything in
  `TicketShare/` is plumbing.
- ADR-0016 — presence is the authentication. Untouched: nothing here crosses a radio, and the drop
  box is between two processes on one phone.
- ADR-0017 — platform-native at both faces. This is the iOS face of a feature whose Android face is
  an intent filter; the parse function between them is identical.
- `CONTEXT.md` — **Ticket**.

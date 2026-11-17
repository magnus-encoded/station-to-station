# ADR-0001: Shared logic above per-platform plumbing

**Status:** accepted (2026-08-03)

## Context

The app ships twice — a SwiftUI build under `ios/` and a Compose build under `android/` — and the
two must not diverge. The mechanism so far has been discipline plus a shared corpus: `Timeline.swift`
says it was *"ported from the Android FestivalScreen.kt/StationScreen.kt logic, term for term"*,
`timelines.json` is written field-for-field the same on both sides, and `fixtures/weave/` is asserted
by `WeaveFixtureTests` and `WeaveFixturesTest` alike.

Divergence has still cost real commits. `280e05f` had to bring iOS playlist naming back in line with
Android's. `NodePlace` existed on Android only, threading a placement choice through four signatures
that iOS hardcodes. And the untested Timelines resolution differs structurally between the two
renderers.

The tempting fix is to make the two codebases structurally identical. An architecture review on
2026-08-03 proposed exactly that — restructuring Android's file layout to match iOS's. That is the
wrong instrument: it fights both platforms' idioms and buys no contract.

## Decision

Two layers, and only one of them is shared.

**The logic layer** owns the sequence and the rules. It is stateless, holds nothing of its own, and
reaches the device only by calling plumbing that is handed to it. Its shape is the same on both
platforms and its tests are the same assertions on both platforms. Anything two builds must agree
about lives here: the weave, the lane placement, the playlist-name derivation, the order in which a
timeline is loaded, resolved and saved.

**Plumbing** owns the device. It is allowed to be stateful and unlovely, because the OS makes it so:
an `actor` and `URLSession` and `Bundle` on iOS, a `ViewModel` with `StateFlow` and
`SharedPreferences` on Android. It is idiomatic per platform and it is *not* expected to match across
them.

Parity is asserted at the logic layer, not the plumbing layer. Where the two platforms already
disagree, the side that is right wins — usually Android, which is ahead, but not always: `NodePlace`
was Android-side flexibility that was never built on iOS and the question it existed to answer had
since been settled, so there Android caught down — #68 deleted it.

## Consequences

- Testing the logic layer means handing it a fake plumbing. That is the whole seam; there is no other
  machinery, and it covers call-order rules ("don't load the cache when a fixture was seeded") that a
  pure function could not express.
- Applying the split on Android will sometimes read as rearrangement for its own sake, because the
  rules are already there and already work. Accepted: the point is that OS-specific and shared code
  stop being interleaved, so the shared half can be asserted.
- Plumbing differences are not defects. A future review must not file "iOS and Android structure
  these differently" as a finding unless the difference is above the plumbing line.
- Shared logic with one consumer is not shared — it is merely extracted. A change that puts a rule in
  the logic layer on one platform only has not honoured this ADR.

## Related

- `CONTEXT.md` — the domain vocabulary the logic layer is written in.
- `fixtures/weave/` — the corpus that asserts agreement.

## Amendment (2026-10-05): the set of features is logic

The Decision above draws the line between rules and device, and leaves unsaid where the app's
*features* fall. #647 read that silence as plumbing: it split Android's `AppViewModel` into
`features/<name>/` controllers and noted that `AppModel.swift` was "not required to match the
layout".

That reading was wrong. **Which features the app has, and which feature owns a behaviour, is part
of the logic layer.** Playlist, Handover, Contacts, Navigation, Setlists, Planning, Tickets, Gig,
GigMedia, Gossip and Settings are units of the product, not of either platform, and "where does
adopting a maybe live?" has one answer on both sides (Contacts). So the feature names and their
boundaries match across platforms. A controller found on one side has its twin under the same name
on the other.

What stays plumbing is everything *inside* a feature: how it holds and publishes state, how
views reach it, its concurrency. On iOS a controller is a `@MainActor` class over a `StateHost`; on
Android it takes `state`/`update` and a `CoroutineScope`. Those are expected to differ.

Because the boundaries are logic, the domain decides them, not whichever platform was split
first. Android's split put Ticket routing and confirmation inside Planning. But a **Ticket** can
be for a night already past, so it is not a planning thing, and Tickets is its own feature on both
platforms. Android is the side that moves. A difference in the door does not make a feature
either: iOS receives a shared Ticket through a share extension that deposits it for the app to
collect (ADR-0020), Android through an intent filter, and both doors lead into Tickets.

The reason is the one the Context gives, applied one level up: divergence costs commits, and most
recent work lands on both platforms as a pair (#682–#685). A fix ported by looking up the same
feature name is a lookup, not a search.

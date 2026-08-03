# ADR-0001: Shared logic above per-platform plumbing

**Status:** accepted (2026-08-03)

## Context

The app ships twice — a SwiftUI build under `ios/` and a Compose build under `android/` — and the
two must not diverge. The mechanism so far has been discipline plus a shared corpus: `Timeline.swift`
says it was *"ported from the Android FestivalScreen.kt/StationScreen.kt logic, term for term"*,
`timelines.json` is written field-for-field the same on both sides, and `fixtures/weave/` is asserted
by `WeaveFixtureTests` and `WeaveFixturesTest` alike.

Divergence has still cost real commits. `280e05f` had to bring iOS playlist naming back in line with
Android's. `NodePlace` exists on Android only, threading a placement choice through four signatures
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
is Android-side flexibility that was never built on iOS and the question it existed to answer has
since been settled, so there Android catches down.

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

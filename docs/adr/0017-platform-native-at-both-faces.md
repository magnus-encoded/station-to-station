# ADR-0017: Platform-native at both faces, identical in between

**Status:** accepted (2026-08-25)

## Context

ADR-0001 split the OS-facing half of the app and the split has held: the logic layer is the same
shape on both platforms and asserted by one corpus, `fixtures/weave/` plus paired unit tests either
side, while plumbing below it is idiomatic per platform and explicitly not expected to match.

The user-facing half has never had the equivalent. It has meaning written down
(`UBIQUITOUS_LANGUAGE.md`) and topology written down (ADR-0006), both in prose, and **nothing that
fails when either is broken**. That is the whole difference between the two halves, and it is why one
drifted and the other did not.

What filled the gap was a slogan. #12 decided iOS as *"native, not a port"* and scoped it in the same
paragraph — the split is by **volatility, not by platform**, and a grammar that took two issues and
several device passes to settle is not to be re-derived. The comment at
`ios/StationToStation/UI/StationView.swift:11` carries the slogan without the scope, and that comment
is what an iOS session reads. An unwritten layer got filled from it and then over-filled into
navigation: `SwipeBack.swift` records the sequence in its own doc comment — custom chevrons replaced
the system back button, which disabled the interactive edge-pop, so a threshold-based replacement was
hand-rolled with no interactive tracking and no cancel. A decorative choice cost a platform
affordance, then paid again to replace it with something worse.

`IPv4Https.swift` is the same situation handled correctly, and the model this ADR asks for: it
diverges from Android with no counterpart, and it names the constraint, cites on-device evidence,
says why the platform API was insufficient, and fences its own scope.

The test below is not original. It is the distinction drawn in Christine Røde's *Designing for both
iOS and Android — the right way* (Config London 2025): inventing an interface the platform has no
answer for is the point of designing an app, while rebuilding one it already ships is where the cost
lands — late, at accessibility and at the next OS restyle.

## Decision

**Four layers. The two that touch something outside the app adapt to it; the two in the middle do
not.**

| Layer | Owns | Parity |
| ----- | ---- | ------ |
| **Plumbing** | the device | idiomatic per platform; not expected to match (ADR-0001) |
| **Logic** | rules and sequence | identical, fixture-asserted (ADR-0001) |
| **Grammar** | meaning and topology | identical |
| **Expression** | material, shape, and the vehicle | platform-native; divergence is the **default** |

**Grammar** is what a **Crossing** is (one **Node**, never two joined by a rung), what **Amber**
means (mine, at every **Resolution**), that a **Festival** uncollapses in place and never becomes a
screen, and the corridor's topology — one place for each place, and leaving a room returns you where
you stood. It is identical across platforms not by policy but by construction: it is not made of
controls, so there is nothing for a platform to have an opinion about.

**Expression** is material and shadow and shape, element order, and **the vehicle** — which control
or gesture moves you along the topology. Blur behind a sheet on iOS and flat overlapping colour on
Android are the same design. So is a carousel re-derived from Android's app switcher instead of
ported from iOS's.

**The test that separates them is: who owns the problem?**

- If every app on the phone has this problem — go back, switch tabs, present a list, show a modal,
  pick a date — **the platform owns it** and has shipped a thorough, accessible, free,
  auto-restyling answer. Building our own is a defect however good it looks, and the cost arrives
  late: at VoiceOver, at dynamic type, at the next OS restyle.
- If only this app has this problem — how two **Lines** meet, what **Mine** looks like at every
  **Resolution**, what colour a night belonging to neither person is — **we own it.** No platform has
  an answer to defer to. Design it once; it is identical on both platforms because it is meaning,
  not chrome.

The test is a documentation lookup — *does UIKit or Material already ship an answer?* — and not a
taste judgment. That is deliberate. It can be adjudicated by someone with no platform expertise,
which is the only kind of rule that will actually be enforced here.

**Divergence is recorded only where it crosses into Grammar.** Expression divergence is expected and
is not documented; a register of every shadow rots within a release and then lies. A divergence that
touches Grammar carries its reason at the site, in the form `IPv4Https.swift` already uses: name it,
cite what makes it true, fence the scope.

```swift
// diverges: Android <what it does>; we <what we do> — <reason>. Grammar: <what it touches>.
```

`grep -rn "diverges:"` is then the parity record, generated rather than maintained, and structurally
incapable of drifting from the code.

**Two kinds of reason are legitimate.** A **constraint** — no API, a system gesture conflict, a
documented HIG or Material rule. Or an **opportunity** — the platform's own metaphor answers it
better. The second is not the lesser reason: refusing it would forbid re-deriving the tab switcher
from Android's app switcher, which is the best kind of adaptation there is.

**A reason must be falsifiable.** It names an API, a guideline section, a system gesture, or an
on-device measurement. *"More idiomatic"* is not a reason, because it names nothing that could be
checked, and a rule whose reasons cannot be checked decays into a ritual of producing
plausible-sounding ones.

## Consequences

- **ADR-0006 straddles this boundary and must say which half of it travels.** Its topology — one
  place for each place, state is not location, a **Resolution** is not a screen — is Grammar and
  binds both platforms. Its vehicle claims — which gesture, where the switch sits — are Expression
  and do not. Filed as a dated amendment to 0006, not a rewrite.
- **`SwipeBack.swift` and the custom chevrons on `GigView` and `ProgrammeView` are defects under this
  ADR.** `NavigationStack` already implements **Outward**, and better — interactive tracking renders
  the spatial relationship between two places while you move between them, where a threshold pop is a
  teleport. `.navigationBarBackButtonHidden(true)` disabled it and `swipeBack` was hand-rolled to
  cover the loss. The platform answer is stronger on our own criterion here, not merely cheaper.
- **`SwipeBack.kt` is not the same case.** Android's system back is handled by Navigation Compose and
  was never disabled, so `swipeRightToBack` is an *additional* control for **Outward** rather than a
  replacement for one the app broke. That is Expression, and permitted. It stays a smell under
  ADR-0006 — two routes to the same thing — and the custom route gives up predictive back's peek, so
  it is worth revisiting. It is not a boundary crossing.
- **Grammar still has no executable check, and this ADR does not close that hole.** It is the same
  absence that produced the drift, and prose that drifted once will drift again. #35's iOS CI
  screenshots are the nearest existing lever.
- **Plumbing's behavioural contract is unasserted too.** ADR-0001's *"plumbing differences are not
  defects"* is correct, but read carelessly it licenses never asking whether two idiomatic
  implementations agree about error semantics, permission denial, timeout and retry, or partial
  failure. Logic tests are handed a fake plumbing by design, so no fixture can see it. #308 was this
  class; cross-platform **Exchange** (#30) is where it becomes expensive.
- **Lag is not divergence.** iOS missing something Android has is a backlog item, not a boundary
  crossing. Filing the two together produces an audit nobody can act on.
- **"iOS shows more controls than Android" is not by itself a finding.** Expression needs no
  permission. It becomes a finding only where affordance density changes what a user must do to reach
  something — at which point it is Grammar and needs a reason.
- **Scope: the user-facing face only.** Implementation idiom below the plumbing line — the missing
  `remember(keys)` equivalent in #308 — is a real gap and is not covered here.

## Not settled by this ADR

Four questions this leaves open, named so they are not mistaken for decided:

1. **Is the burden symmetric?** If only iOS must name its divergences, Android becomes the reference
   implementation by default and these documents become description rather than specification. If it
   is symmetric, it taxes the faster loop.
2. **Grandfathering.** Does the audit adjudicate every existing Grammar crossing now, or mark them
   `unexamined` and adjudicate on next touch? This is the difference between a handful of issues and
   a great many.
3. **Where meaning ends and treatment begins in colour.** **Amber** means mine, and that is Grammar.
   Whether amber *glows*, or what blurs behind it, is presumably Expression — but the seam has not
   been drawn.
4. **Affordance density.** ADR-0006 answers *where is this?* It does not answer how many switches are
   on a wall, or in what order. The candidate test — *would someone who learned this on one platform
   fail to find it on the other?* — has not been stress-tested.

## Related

- ADR-0001 — the same decision on the OS-facing face; this ADR is its twin, and copies its structure
  deliberately.
- ADR-0006 — the corridor. Grammar above, Expression below; see the amendment.
- #12 — *"native, not a port"*, and the volatility scope the slogan lost.
- ~~`UBIQUITOUS_LANGUAGE.md` — where Grammar is written.~~ (see amendment 2026-08-25) Gestures were
  never named there, which is why a gesture did not read as a concept.
- `ios/.../UI/SwipeBack.swift` — the worked example above.
- `ios/.../Data/SetlistFm/IPv4Https.swift` — the model for a divergence that names its reason.
- #145 — ADR-0006's own worked example, and the divergence that surfaced this.
- #308, #30 — the below-the-line contract gap this ADR names but does not close.
- Christine Røde, *Designing for both iOS and Android — the right way*, Config London 2025.

## Amendment — 2026-08-25

**Grammar is written in `CONTEXT.md`, not `UBIQUITOUS_LANGUAGE.md`.** The Related entry above named
the wrong file. The two had diverged into rival glossaries under the same `# Ubiquitous Language`
heading, and the sections this ADR leans on — **The room**, **The flyover**, **The Bill family**,
**Navigation grammar** — exist only in `CONTEXT.md`. `UBIQUITOUS_LANGUAGE.md` is the
`domain-modeling` skill's regenerated model of the same vocabulary: not hand-authored, and not to be
hand-patched. Both files now say so at the top.

This is the ADR's own diagnosis applied to itself — Grammar written in prose, with nothing that
fails when it is broken, drifted — and this document pointed an agent at the copy that was behind.

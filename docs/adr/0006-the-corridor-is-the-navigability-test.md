# ADR-0006: The corridor is the navigability test

**Status:** accepted (2026-08-11, formalising a model in use since the timeline grammar was pinned)

## Context

Navigation decisions arrive one at a time and each sounds locally reasonable. A screen for reviewing
what you share. A separate view of a friend's line. A mode for editing. Taken individually every one
of them is defensible; taken together they produce an app where the same thing is reachable from
three places, where a gesture means different things depending on how you got somewhere, and where
nobody can say what "back" means.

The app already had a spatial grammar — vertical is time with up being later, horizontal is depth
from **Outer** to **Inner**, swipe right goes back out, pinch replaces it at the outermost rung — but
it was a description of what had been built rather than a rule that could reject a proposal.

The model that turns it into a rule came out of a design conversation: **the timeline is a corridor,
and a Gig is a room off it.** Each room has one door, an **Alcove** opposite the door holding the one
thing that room is for, and a **Curtain** over a **Window** onto a data source. Movement in the
landscape has one place for each place.

## Decision

**Any navigation proposal must be expressible in the corridor. If it requires a second version of a
place, it is wrong as designed.**

The test is one question: *where is this, physically?* A proposal answers with a location, or with a
change of state at a location the user already occupies, or it fails.

Three consequences the model makes non-negotiable:

- **One place for each place.** A second corridor, or a second copy of a room, breaks the model.
  Two routes to the same thing is a smell; two *versions* of the same thing is a defect.
- **A gesture means one thing at each Resolution.** Swipe right is outward everywhere. Where there
  is nothing further out, it may be reassigned, but never so that it means two things at one
  **Resolution**.
- **State is not location.** Lighting, tinting and filtering are properties of where you are
  standing, so they need no destination, no journey and no return journey. A light is toggled by the
  same switch that turned it on.

**Worked example, and the reason this is written down.** The contact's-eye view (#145) was first
proposed as a screen, which the test rejects: it is a second version of the corridor. Reframed as a
lighting change it passes, because nothing moves and it is coherent at every **Resolution** — the
timeline, a room, a single photograph. The gesture then follows from the model rather than being
assigned: swipe right is free at the outermost rung, the switch is on the left as you face down the
corridor, and because it is a light rather than a place, the same gesture returns. An earlier
attempt to justify it as "continuing the outward axis" was wrong precisely because it implied a
journey.

## Consequences

- **Some genuinely useful features have to be redesigned rather than added.** That is the cost, and
  it is the point: the redesign is usually better, because being forced to answer "where is this"
  tends to reveal that the thing was a state all along.
- **The metaphor is a test, not a theme.** No literal doors, no skeuomorphic rooms. It constrains
  structure, not decoration.
- **New gestures are scarce, and scarcity is enforced by the model rather than by taste.** Swipe
  left is **Exchange**; pinch is zoom to other **Lines**; swipe right is outward. What is left is
  genuinely free space, and it is small.
- **"Add a settings screen for it" is usually the failing answer.** If a thing is about where you
  are, it belongs where you are.
- **The Alcove is a single slot, so it forces a choice.** A room offers one destination opposite the
  door — the calendar before a night, the Spotify terminal after it. Two things competing for the
  alcove is a signal that the state model is wrong, not that the alcove should hold two things.
- **It generalises to the iOS build.** The grammar is shared logic in the ADR-0001 sense; ~~a platform
  may not resolve a navigation question differently, because the corridor is the same corridor.~~
  **a platform may not resolve a question of *topology* differently, because the corridor is the same
  corridor — but the control that moves you along it is the platform's to choose** (narrowed
  2026-08-25, see the amendment below and ADR-0017).

## Amendment (2026-08-25): the corridor binds topology, not the vehicle

**What changed.** The last consequence claimed navigation whole. It is narrowed to the corridor's
**topology** — what places exist, how they connect, that there is one of each, that leaving a room
returns you where you stood. The **vehicle** — which control or gesture moves you along that
topology — is Expression under ADR-0017 and is the platform's to choose.

**What made it change.** Two things, in order.

The corridor's force comes from spatial cognition rather than from convention, which is what made the
strong claim look right: a mental map is deeper than any OS's habits, so it seemed to follow that the
whole of navigation travels between platforms. But the spatial system supplies the machinery to
*build* a map of an environment, not a prior about which gesture pops a room. What must be identical
is therefore the map. The vehicle is indifferent to it.

Then the strong reading was found doing damage. `ios/StationToStation/UI/SwipeBack.swift` hid the
system back button behind custom chevrons — which disabled the interactive edge-pop — and hand-rolled
a threshold replacement with no interactive tracking and no cancel. That is not a defence of the
corridor: the system gesture *implements* the topology, and better, because tracking renders the
spatial relationship between two places while you move between them, where a threshold pop is a
teleport. The strong claim was used to justify a version of the corridor that is worse at being a
corridor.

**What this does not change.** *One place for each place*, *state is not location*, and *a
**Resolution** is not a screen* are all topology and all still bind both platforms. So does *a gesture
means one thing at each **Resolution*** — that is a consistency rule *within* a platform, and it never
required the two platforms to pick the same gesture. The worked example above stands: the contact's-eye
view is a light and not a place on both builds. Which control turns that light on is now Expression,
and where a build offers a second switch for one light, it is *one place for each place* that decides
it — not this bullet.

**What the strong version was doing, and still does.** It was not idle. It rejects "add a settings
screen for it", second copies of a room, and gestures that mean two things at one **Resolution** —
and every one of those fails the *topology* test, not the vehicle test. The narrowing costs the model
nothing it was actually being used for.

## Related

- ADR-0017 — Grammar and Expression; this amendment is its first application.
- `CONTEXT.md` — **Outer**, **Inner**, **Resolution**, **Room**, **Alcove**, **Curtain**, **Window**.
- ADR-0001 — the grammar is shared logic, not per-platform plumbing.
- #145 — the worked example above.
- #129 — the **Gig** lifecycle, which decides what the alcove holds at each phase.

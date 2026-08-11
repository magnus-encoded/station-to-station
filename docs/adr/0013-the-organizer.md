# ADR-0013: The Organizer

**Status:** accepted as **not a user** (2026-08-11). Nothing is being built.

## Context

Event organisers' and artists' commercial interest. Not a user of the app, and penciled in early
explicitly as a "not today" persona, kept so that a sponsorship feature would be reasoned about
deliberately rather than bolted on at the moment money appeared.

## Decision

**The Organizer is recorded as a non-user stakeholder and nothing is built for them.**

If anything ever is, one constraint is fixed in advance: **Organizer-funded content stays
presentation-layer** — logos, images, bonus material — alongside an untouched, still crowd-sourced
setlist. **Sponsor money must never be able to touch the Historian's record.**

This is also the single carve-out to ADR-0003: a publisher publishing their own data about their own
event is not us holding somebody's private record.

## Consequences

- **The constraint is written before the money exists**, which is the only time it can be written
  honestly.
- **Partial-setlist content packs remain conceivable** — an artist releasing an early partial set
  with the encore withheld as a surprise — but nothing about them is designed.
- **They are the counterparty to the Volunteer** (ADR-0014). The party that would pay for content
  packs is the party underpaying the volunteer, and where those interests conflict the Volunteer
  wins.

## Related

- ADR-0003 no backend (the carve-out), ADR-0008 the Historian, ADR-0014 the Volunteer.

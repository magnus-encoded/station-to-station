# ADR-0008: The Historian

**Status:** accepted (2026-08-11, recording a persona in use since the original Collector/Historian split)

## Context

Wants documentation: true facts about what happened when. The distinguishing feature is direction.
The Historian's data flows *outward*, toward a correct shared record, where every other persona's
flows inward toward their own.

## Decision

**The Historian is a first-class persona, and the outward path is a supported direction of travel.**
Posting a **Log** to setlist.fm is a first-class action rather than an afterthought, and the app
prefers correct upstream data (real venues, real MBIDs) over locally convenient approximations.

**The app is not a correctness enforcer.** Nothing is gated on being accurate, and no record is
marked deficient for being local-only.

## Consequences

- **Venue is a fact about a night, not a convenience field.** This is the persona behind #128 and
  ADR-0002: a festival name in a venue field is not cosmetic, it is a false fact that also defeats
  correspondence between two records of one night.
- **Prompting a record to graduate is legitimate but surface-dependent.** See ADR-0012. Nagging is
  only nagging on the phone.
- **Outward flow never becomes automatic.** The app does not post on the user's behalf; ADR-0003
  applies to corrections as much as to content.
- Deferred outward flows, such as venue coordinates going back to setlist.fm, stay deferred rather
  than being dropped.

## Related

- ADR-0002 dating at the resolution known, ADR-0007 the Collector, ADR-0012 the Journalist.

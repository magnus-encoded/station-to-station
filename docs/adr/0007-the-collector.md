# ADR-0007: The Collector

**Status:** accepted (2026-08-11, recording a persona in use since the original Collector/Historian split)

## Context

The first persona named, and the one whose motive sits closest to the app's surface: the collection
itself is the point. But it doubles as a status symbol, since comparing notes is part of the draw,
and that second half generates design pressure the first half would not.

## Decision

**The Collector is a first-class persona and the app's default reading of its own user.** The
collection is treated as an object of value in itself: complete, ordered, and worth keeping.

The status half is served *only* through primitives that are real. Attendance is a real fact, the
gold star is earned by a live check-in, and **Gaps** stay honest. Vanity signals shaped like
follower counts are rejected here for the same reason they are rejected for the Tastemaker
(ADR-0011).

## Consequences

- **Completeness is a motive the app may serve but never manufacture.** Showing what is missing is
  legitimate; nagging about it is not, and the surface axis in ADR-0012 decides which is which.
- **This is the persona that needs device handover to work** (#141). A collection that does not
  survive a new phone is not a collection.
- **The friction with the Historian is narrower than it looks.** setlist.fm is crowd-sourced and
  largely accurate, so the only thing a status-motivated Collector might fudge is claiming
  attendance after the fact, which is exactly what attestation guards.
- **It pulls against the Volunteer** (ADR-0014): a fuller record is a richer record, and a crowd
  shot is fuller than an empty stage.

## Related

- ADR-0008 the Historian, ADR-0011 the Tastemaker, ADR-0014 the Volunteer.

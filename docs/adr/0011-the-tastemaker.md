# ADR-0011: The Tastemaker

**Status:** accepted as **deferred** (2026-08-11). Not being designed further.

## Context

Wants reach. The obvious way to serve reach is the one this project will not take: followers, feeds,
discovery and promotion, all of which need the backend ADR-0003 refuses, and all of which reshape a
private record into content.

## Decision

**The Tastemaker is named and deliberately deferred, not dropped.** If reach is ever served it will
be through merit primitives that are real — gigs actually attended, check-ins actually made — closer
to a local-guide model than a follower count. **Follower-count-shaped signals are rejected outright,
now and later.**

**The sharper reason for the deferral**, established during the Journalist review: export already
answers "how is it published". What does not travel with an export is the cred primitive, because
gold-star attestation lives inside the app and there is nothing to verify it against outside the
contact graph. So export serves the Journalist fully and the Tastemaker not at all.

## Consequences

- **Publishing infrastructure is out of scope by default.** A proposal adding a feed, a directory or
  a discovery surface argues against this ADR and ADR-0003 together.
- **The deferral is architectural, not a backlog item.** Nothing needs building now for it to become
  possible later, because publishing sits above the existing model.
- If it is ever taken up, the open question is verifiable attestation outside the contact graph.

## Related

- ADR-0003 no backend, ADR-0012 the Journalist (the half that is served).

# ADR-0005: A revocable cache clause disqualifies a data source

**Status:** accepted (2026-08-10)

## Context

The app needs to know about gigs that have not happened yet, and the obvious sources are the
commercial live-music feeds: Songkick, Bandsintown, Ticketmaster. Each has a real API with real
coverage, and each was evaluated.

The objection raised against them at first was the wrong one. It is genuinely doubtful that a
provider can own the *fact* that a band plays a venue on a date, when the same fact is on the
venue's own website. That argument is probably right and it does not help, because it is not the
constraint that bites.

The constraint that bites is the **cache clause**. These terms generally require that data be
refreshed or purged on a schedule, or removed on request, or not retained beyond a session. That is
compatible with a listings app and incompatible with this one, because here a gig you attended
becomes a permanent, offline, exported, decades-long record. A source that can require deletion is a
source that can reach into somebody's concert history and take a night out of it years later.

The general form is worth stating because it is cheaper than reading each provider's terms in turn.

## Decision

**A data source whose terms allow it to require deletion or expiry of cached data is disqualified as
a source for anything that enters the permanent record.**

Consequences for the sources evaluated:

- **Songkick and Bandsintown: closed**, marked won't-fix rather than deferred. Not because the API
  is inadequate — it is fine — but because it cannot supply a record we promise to keep.
- **MusicBrainz and setlist.fm upcoming events: open**, and the ones to build on. Their licensing is
  compatible with a permanent local copy.
- **Discovery is a pluggable seam** (#125). The best source differs by country and by scene, and
  this rule will disqualify future candidates too, so the shape must accommodate swapping them.

**The scope of the rule.** It applies to anything that lands in the record. It does not forbid a
source being used transiently — a lookup that informs a decision and is not retained is a different
thing — but the moment output is stored on a **Gig**, this applies.

## Consequences

- **Coverage is worse than it could be, deliberately.** Some upcoming gigs will not be discoverable,
  and the user will sometimes have to add a night by hand. That is the cost of the record being
  durable.
- **A shorter evaluation for future sources.** Read the retention and deletion terms first. If the
  provider can require purging, stop there rather than assessing coverage and quality.
- **It reinforces ADR-0003 from a different direction.** There, nobody else holds the record; here,
  nobody else may compel its contents to change. Both are the same property: the record is the
  user's and is not revocable by a third party.
- **This is not a claim that the underlying facts are proprietary.** Where a fact is independently
  available — from a venue, from MusicBrainz, from the user — it may be recorded. The rule is about
  accepting *terms*, not about who owns a date.
- **Re-evaluation is legitimate if terms change.** These are contractual, not technical, judgements,
  and a provider that drops its cache clause becomes eligible again.

## Related

- ADR-0003 — no backend; the same durability property from the storage side.
- #59 — the original source evaluation.
- #125 — discovery sources as a pluggable seam.

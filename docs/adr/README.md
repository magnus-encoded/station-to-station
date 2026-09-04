# Architectural decisions

Decisions that are costly to reverse, with the reasoning that produced them. The point of writing
them down is not ceremony: a good idea gets re-proposed every few months, and without the argument
recorded it gets re-argued from scratch or, worse, silently reversed.

An ADR belongs here when the decision is **architecturally significant** — it constrains what can be
built later, or it is expensive to undo. A decision that is easy to change belongs in a comment next
to the code it affects.

## Revising one

**Append and strike through. Never replace.** A superseded line stays on the page with `~~strikethrough~~`, and the reasoning that replaced it is added below under a dated `## Amendment` heading naming what changed and what made it change.

The reason is the same one that justifies the directory at all: an ADR whose wrong turns have been edited out reads as though the decision was always obvious, which is exactly the record that lets it be silently reversed later. **What a decision used to say is evidence about how much weight to give what it says now** — ADR-0009 was accepted one day before the fact that broke it, and that gap is the most useful thing on the page.

Issues elsewhere may be edited freely; this rule is for `docs/adr/` only.

## Structural

| | Decision | Status |
| --- | --- | --- |
| [0001](0001-logic-layer-above-plumbing.md) | Shared logic above per-platform plumbing | accepted |
| [0002](0002-time-at-the-resolution-known.md) | A night is dated at the resolution it is known | accepted |
| [0003](0003-no-backend-for-the-social-layer.md) | No backend for the social layer | accepted |
| [0004](0004-best-effort-enrichment.md) | Best-effort enrichment, and the scoped invariant | accepted |
| [0005](0005-a-revocable-cache-clause-disqualifies-a-source.md) | A revocable cache clause disqualifies a data source | accepted |
| [0006](0006-the-corridor-is-the-navigability-test.md) | The corridor is the navigability test | accepted; ~~binds navigation~~ **binds topology** (amended 2026-08-25) |
| [0016](0016-presence-is-the-authentication.md) | Presence is the authentication, so the radio is not verified | accepted |
| [0017](0017-platform-native-at-both-faces.md) | Platform-native at both faces, identical in between | accepted |
| [0019](0019-gossip-channel-background-carve-out.md) | The gossip channel is a narrow, named carve-out from ADR-0016 | accepted |

## Who it is for

Each persona is its own decision, because each carries its own status and its own constraint. The
status column is doing real work here: two of them are people nothing is built for.

| | Persona | Status |
| --- | --- | --- |
| [0007](0007-the-collector.md) | The Collector | served |
| [0008](0008-the-historian.md) | The Historian | served |
| [0009](0009-the-reliver.md) | The Reliver | ~~served; low net-new need~~ **served for five accounts** (amended 2026-08-15) |
| [0010](0010-the-friendgroup-member.md) | The Friendgroup Member | served |
| [0011](0011-the-tastemaker.md) | The Tastemaker | **deferred** |
| [0012](0012-the-journalist.md) | The Journalist | served |
| [0013](0013-the-organizer.md) | The Organizer | **not a user**, nothing built |
| [0014](0014-the-volunteer.md) | The Volunteer | **not a user**, protected |
| [0015](0015-the-holdout.md) | The Holdout | served; the first who pulls the other way |

`docs/personas.md` is the map across them: how to apply the set, where their motives pull against
each other, and the resolutions that settled particular conflicts. Read it alongside these, because
the tensions live *between* the ADRs and no single one holds them.

## Reading order

0003 first if you are new: most of the rest sits on it, and 0004 follows from it directly. 0001
governs day-to-day code structure and 0006 governs anything that adds a place or a route between
places — its topology, not the control that carries you. 0002 and 0005 are
narrower and can be read when you touch dates or data sources. 0016 before touching anything that
hands over a **Card** — a radio, a link, a code — or before proposing a handshake for one, and 0019
right after it before touching the gossip check-in relay — it narrows 0016 rather than replacing it.
0017 before
touching UI on either platform, with 0001 and 0006 beside it: the three together are the parity model,
and 0017 is the one an iOS session most often needs first. The persona ADRs are reference rather
than reading: consult the ones a piece of work touches.

## Related

- `CONTEXT.md` and `UBIQUITOUS_LANGUAGE.md` — the vocabulary these are written in. Read first.
- `docs/personas.md` — the map across 0007–0015.
- `docs/persona-review-0002.md` — the personas arguing over a draft ADR, as stories.

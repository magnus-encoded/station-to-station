# Architectural decisions

Decisions that are costly to reverse, with the reasoning that produced them. The point of writing
them down is not ceremony: a good idea gets re-proposed every few months, and without the argument
recorded it gets re-argued from scratch or, worse, silently reversed.

An ADR belongs here when the decision is **architecturally significant** — it constrains what can be
built later, or it is expensive to undo. A decision that is easy to change belongs in a comment next
to the code it affects.

## Structural

| | Decision | Status |
| --- | --- | --- |
| [0001](0001-logic-layer-above-plumbing.md) | Shared logic above per-platform plumbing | accepted |
| [0002](0002-time-at-the-resolution-known.md) | A night is dated at the resolution it is known | accepted |
| [0003](0003-no-backend-for-the-social-layer.md) | No backend for the social layer | accepted |
| [0004](0004-best-effort-enrichment.md) | Best-effort enrichment, and the scoped invariant | accepted |
| [0005](0005-a-revocable-cache-clause-disqualifies-a-source.md) | A revocable cache clause disqualifies a data source | accepted |
| [0006](0006-the-corridor-is-the-navigability-test.md) | The corridor is the navigability test | accepted |

## Who it is for

Each persona is its own decision, because each carries its own status and its own constraint. The
status column is doing real work here: two of them are people nothing is built for.

| | Persona | Status |
| --- | --- | --- |
| [0007](0007-the-collector.md) | The Collector | served |
| [0008](0008-the-historian.md) | The Historian | served |
| [0009](0009-the-reliver.md) | The Reliver | served; low net-new need |
| [0010](0010-the-friendgroup-member.md) | The Friendgroup Member | served |
| [0011](0011-the-tastemaker.md) | The Tastemaker | **deferred** |
| [0012](0012-the-journalist.md) | The Journalist | served |
| [0013](0013-the-organizer.md) | The Organizer | **not a user**, nothing built |
| [0014](0014-the-volunteer.md) | The Volunteer | **not a user**, protected |

`docs/personas.md` is the map across them: how to apply the set, where their motives pull against
each other, and the resolutions that settled particular conflicts. Read it alongside these, because
the tensions live *between* the ADRs and no single one holds them.

## Reading order

0003 first if you are new: most of the rest sits on it, and 0004 follows from it directly. 0001
governs day-to-day code structure and 0006 governs anything that adds navigation. 0002 and 0005 are
narrower and can be read when you touch dates or data sources. The persona ADRs are reference rather
than reading: consult the ones a piece of work touches.

## Related

- `CONTEXT.md` and `UBIQUITOUS_LANGUAGE.md` — the vocabulary these are written in. Read first.
- `docs/personas.md` — the map across 0007–0014.
- `docs/persona-review-0002.md` — the personas arguing over a draft ADR, as stories.

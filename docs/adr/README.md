# Architectural decisions

Decisions that are costly to reverse, with the reasoning that produced them. The point of writing
them down is not ceremony: it is that a good idea gets re-proposed every few months, and without the
argument recorded it gets re-argued from scratch or, worse, silently reversed.

An ADR belongs here when the decision is **architecturally significant** — it constrains what can be
built later, or it is expensive to undo. A decision that is easy to change belongs in a comment next
to the code it affects.

| | Decision | Status |
| --- | --- | --- |
| [0001](0001-logic-layer-above-plumbing.md) | Shared logic above per-platform plumbing | accepted 2026-08-03 |
| [0002](0002-time-at-the-resolution-known.md) | A night is dated at the resolution it is known | accepted 2026-08-06 |
| [0003](0003-no-backend-for-the-social-layer.md) | No backend for the social layer | accepted 2026-08-11 |
| [0004](0004-best-effort-enrichment.md) | Best-effort enrichment, and the scoped invariant | accepted 2026-08-11 |
| [0005](0005-a-revocable-cache-clause-disqualifies-a-source.md) | A revocable cache clause disqualifies a data source | accepted 2026-08-11 |

## Reading order

0003 first if you are new: it is the premise most of the rest sits on. 0004 follows from it directly.
0001 is the one that governs day-to-day code structure. 0002 and 0005 are narrower and can be read
when you touch dates or data sources.

## Related

- `CONTEXT.md` and `UBIQUITOUS_LANGUAGE.md` — the vocabulary these are written in. Read first.
- `docs/personas.md` — who the decisions are for, including the two non-users.
- `docs/persona-review-0002.md` — the personas arguing over a draft ADR, as stories.

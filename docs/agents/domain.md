# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root — the glossary of the timeline's vocabulary.
- **`docs/adr/`** — read ADRs that touch the area you're about to work in. Start from
  [`docs/adr/README.md`](../adr/README.md), which indexes them and gives a reading order.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest
creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and
`/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

Single-context repo:

```
/
├── CONTEXT.md
├── docs/adr/                   ← architectural + persona decisions
├── ios/                        ← SwiftUI app
├── android/                    ← Compose app
└── fixtures/weave/             ← the corpus both platforms assert against
```

The original Python CLI the repo grew out of is defunct: archived on the `cli` branch,
removed from `main`.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a
test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary
explicitly avoids — it carries an "Aliases to avoid" column for exactly this, and its preamble
records that every ambiguity in it cost a build/install/look round trip.

In particular: **Line**, **Spine**, **Lane**, **Edge**, **Node**, **Crossing**, **Joined**,
**Parting**, **Resolution**, **Gig**, **Festival**, **Absorb**, **Attended**, **Mine/Theirs/
Together**, **Amber**, **Lane colour**, **Meeting green**, **Followed line**, **Contact**,
**Card**, **Exchange**, **Attach**, **Personal**, **Audience**, **Reconcile**, **Pointer**,
**Thumbnail**.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing
language the project doesn't use (reconsider) or there's a real gap (note it for
`/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 — but worth reopening because…_

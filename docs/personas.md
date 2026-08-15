# Personas

**Each persona is now an ADR** — [0007–0015](adr/README.md#who-it-is-for) — because naming one is a
scoping decision with its own status and its own constraints. Two of them are people nothing is
built for.

This file holds what the ADRs cannot hold individually: **how to apply the set, and where their
motives pull against each other.** The tensions live between the ADRs, so they are collected here.

| | | |
| --- | --- | --- |
| [The Collector](adr/0007-the-collector.md) | the collection itself, and comparing it | served |
| [The Historian](adr/0008-the-historian.md) | true facts, flowing outward | served |
| [The Reliver](adr/0009-the-reliver.md) | re-experiencing it | served by playlists |
| [The Friendgroup Member](adr/0010-the-friendgroup-member.md) | the nights we shared | served |
| [The Tastemaker](adr/0011-the-tastemaker.md) | reach | **deferred** |
| [The Journalist](adr/0012-the-journalist.md) | writing *about* gigs | served |
| [The Organizer](adr/0013-the-organizer.md) | an event's commercial interest | **not a user** |
| [The Volunteer](adr/0014-the-volunteer.md) | working the gate, in your photograph | **not a user**, protected |
| [The Holdout](adr/0015-the-holdout.md) | keeping the exit open | served; pulls the other way |

## How to apply the set

A scoping heuristic, not a research artefact. One user is usually several of these at once —
**overlap is expected, not a modelling failure.**

The useful question is rarely "which persona is this feature for". It is **"does another persona's
motive pull against it on the *same* surface"**. That is a design decision to make explicitly rather
than let slide.

A second question, contributed by the Journalist: **which surface does this belong to?** Phone at
the gig is capture — one-handed, wrong-tolerant, coarse. Laptop at home is where things get written,
enriched, published and fixed upstream. Decide that before adding a prompt, because nagging is only
nagging on the phone.

**A caution about the heuristic itself.** A feature no persona wants is normally a signal to cut it
— but see the protective hole below. When the heuristic says cut something that is obviously right,
the heuristic is what is wrong.

## Where they pull against each other

- **Collector ↔ Historian**, on anything that compares collections. Narrower than it first looks:
  setlist.fm is crowd-sourced and largely accurate, so the only thing a status-motivated Collector
  might fudge is *claiming attendance after the fact*. That is exactly what attestation guards,
  rather than fraud-proofing the whole record.
- **Historian ↔ Collector**, on prompting a record to graduate. Resolved by the surface axis: the
  phone records the finding durably, the desk is where it becomes actionable.
- **Friendgroup Member ↔ Volunteer**, the sharpest live one. Communal keepsakes *are* crowd
  photographs, which are the photographs with volunteers in them.
- **Journalist ↔ Volunteer**, and **this one is unresolved on purpose.** Press photography has a
  real tradition of public interest overriding a bystander's preference, and her workflow explicitly
  involves contacts' photographs. Sometimes her claim wins. There is no rule yet, and inventing one
  would be worse than recording the gap.
- **Organizer ↔ Volunteer**, the tidy one: the party that would pay for content packs is the party
  underpaying the volunteer. The Volunteer wins.
- **Organizer ↔ Historian**: sponsor money must never touch the record. Presentation-layer only.
- **Holdout ↔ Reliver**, and **this one is unresolved on purpose.** The Reliver is served *by
  playlists*, which means served by Spotify — proprietary, and permanently capped at five accounts.
  The Holdout will not have one. Nothing objected to that dependency until he was named, so it read
  as settled when it is not. What serves the Reliver's motive without Spotify has no answer yet.
- **Holdout ↔ Collector**: comparison wants identity, identity wants accounts, and he refuses the
  account. Comparison features have to fail soft — absent, not broken.
- **Holdout ↔ Historian**, the resolved one: both want the fact to survive and they disagree about
  where it goes. Upstream to setlist.fm needs an account; he wants it portable instead. Local
  **Gig**s and `adoptSetlistId` are what let both be true later.

## Standing resolutions

- **Attestation is a badge on an entry, not a gate on the entry itself** (2026-07-29). Anything may
  be added from memory. What cannot be earned without a live in-the-moment check-in is the gold
  star. I decide I was there; the app can only add a confirmation.
- **"Communal" keepsakes need no shared backend state.** Converting a gig to a playlist prompts "you
  were at this gig with X — send them this playlist?". Each person ends up with their own copy;
  there is no canonical shared object to keep in sync.
- **We resist global, top-down publishing structures for user-generated data.** It keeps the design
  honest and keeps servers out of the social layer (ADR-0003).
- ~~**No persona is protective, and that is a known hole**~~ (2026-08-11). All eight pull toward more
  record, more comparison, more sharing. The Journalist looks like the exception and is not: her
  private notes are instrumental, material saved for an article rather than material protected from
  exposure. So requirements about *exposure* — encrypting a transfer, limiting blast radius,
  reviewing what you are exposing — cannot be generated by this set and must be argued on their own
  terms. The Volunteer covers the third-party half of it; the owner-protecting half has no persona
  at all.

  **Half-closed** (2026-08-15). [The Holdout](adr/0015-the-holdout.md) is the owner-protecting half,
  named for exactly this hole. Requirements about *blast radius on someone else's behalf* still come
  from the Volunteer and only from her, and neither of them speaks for a user who is careless rather
  than careful — so the hole is narrower now, not gone.

## Related

- [`adr/README.md`](adr/README.md) — all decisions, structural and persona.
- [`persona-review-0002.md`](persona-review-0002.md) — these personas arguing over a draft ADR, as
  stories.
- [`adr/0002-time-at-the-resolution-known.md`](adr/0002-time-at-the-resolution-known.md) — what that
  review changed.

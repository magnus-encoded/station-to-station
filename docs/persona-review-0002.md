# ADR-0002 persona review — findings as stories

**Status:** review input (2026-08-06). Not an ADR — it holds no number and lives outside `docs/adr/`.
Written against the original draft; ADR-0002 has since been revised to absorb it, so read the ADR for
what was decided and this for why.

Source: a bounded discussion between five user personas (Collector, Historian, Reliver, Friendgroup
Member, Tastemaker) on what ADR-0002 adds or subtracts for each of them. The Organizer was excluded —
not a user. The **Journalist** (`docs/personas.md`) was named *as a result of* this review and so
argues in none of the stories below.

Four findings. Three are things the draft ADR got wrong; one is a question nobody had asked.

---

## Finding 1 — The ADR ignores a prior ruling that already solves its hardest objection

The draft never mentions **attestation**, and the counting objection it therefore can't answer is the
one that most threatens it: if backfilled nights count the same as attended-and-confirmed ones, the
timeline's own number stops meaning anything.

It is already decided. *Attestation is a badge on top of an entry, not a gate on the entry itself*
(2026-07-29). Anything may be added from memory; what cannot be earned without a live in-the-moment
check-in is the gold star. So a backfilled Ringnes 2017 is a real entry that can never be attested —
**coarse and unconfirmed are separate axes, and the mechanism to keep them separate already exists.**

> **As the Collector,** I want a backfilled night to be a full entry in my collection, so that
> recording what I actually attended is never treated as second-class.

> **As the Tastemaker,** I want a backfilled night to be ineligible for the gold star, so that a cred
> primitive built on attendance cannot be inflated by anything typed in from memory.

> **As the Historian,** I want *coarse* and *unattested* to be distinct properties of a record, so
> that "I don't know which night" is never confused with "I can't show I was there".

> **As the Collector,** I want the timeline's count to state what it counts, so that a number that
> grows by six festivals overnight is not silently redefined.

**Note for the ADR:** the count reads "202 shows · since 1992" today. It has always meant *shows
setlist.fm knew about* — a limitation being read as a definition. Whatever it becomes, the ADR should
say, rather than leaving it to whoever next edits the header.

---

## Finding 2 — The Historian's outward flow is subtracted, and the draft doesn't admit it

"Consequences" lists costs to the code and none to a persona's motive. The Historian's motive is data
moving **outward** toward a correct shared record, and "publishability is derived" means most of this
never leaves the phone.

The sharper version of the objection: the ADR treats an artist's absence from MusicBrainz as
permanent weather. Silent Majority — playing since 1983, at Ringnes since 2002 — *should* have an
mbid. There is no story for a record ever **graduating**.

> **As the Historian,** I want a local record to know whether it *could* become publishable, so that
> the reason it is local is a fact about the world rather than a shrug.

> **As the Historian,** I want the app to tell me when the only thing standing between my record and
> the shared one is an artist nobody has added upstream, so that I can go and fix the actual problem.

> **As the Historian,** I want a record to become publishable by itself when the obstacle disappears,
> so that a band added to MusicBrainz next year does not leave my night stranded by an old decision.

> **As the Collector,** I do *not* want graduation to be nagging, so that a permanently local record
> is a settled state and not an unfinished chore.

---

## Finding 3 — Open question 1 cannot stay open

Whether a coarse node can be a **Crossing** is filed as an open question. Two personas need it
answered, and if it is not decided explicitly it will be decided implicitly by whoever writes the
rendering code.

The disagreement is real and should be resolved on the merits, not split:

- **Friendgroup Member:** the festival *is* the thing we shared; the night is a detail neither of us
  cares about.
- **Historian:** a merge asserts you were in the same field on the same evening, which neither of you
  knows.

> **As the Friendgroup Member,** I want a festival we both attended to appear as one node where our
> lines meet, so that a shared past is visible in the same language the app uses for a shared night.

> **As the Historian,** I want a merge at festival resolution to be visibly weaker than a merge at
> night resolution, so that the picture never claims more precision than the data has.

> **As the Collector,** I want to compare a coarse node against a friend's timeline, so that
> backfilled history is as socially useful as attended history.

> **As the Friendgroup Member,** I want the answer written down before the code, so that the visual
> language's amber/green rules are changed deliberately rather than by accident.

---

## Finding 4 — Open question 4 dissolves, and exposes a new one

The draft's contradiction — "only a person may sharpen a resolution" versus adoption of a setlist.fm
id sharpening the date automatically — is a wording bug. **Adoption is a person's act**: they tapped
the button and pasted the link. Rewording to *"no automatic process sharpens a resolution without a
person's action"* removes the contradiction.

That surfaced a question nobody had asked: **is sharpening reversible?**

> **As the Historian,** I want no background process to sharpen a record's resolution, so that
> precision in the record always traces to something a person did.

> **As the Reliver,** I want to find out when a night I remembered as one year turns out to be
> another, so that a correction reaches me instead of being applied silently.

> **As the Friendgroup Member,** I want a sharpened resolution to be reversible, so that adopting a
> wrong setlist.fm id is not a one-way door on a memory.

---

## Finding 5 — a dependency, not a defect

Raised by the Reliver and easy to lose: **media on a festival-level node**. #74 is listed under
"Related" in the draft, but for the Reliver it is a *dependency* — without it, backfill adds nodes
they can do nothing with. A coarse node has no setlist, therefore no playlist, so photographs are the
only thing that makes a 2017 node worth opening.

> **As the Reliver,** I want photographs to attach to a festival I attended rather than to one night
> inside it, so that a backfilled edition is something I can sit with and not just a row on a line.

> **As the Collector,** I want a coarse node to render as itself rather than as a night missing its
> date, so that my collection does not read as broken where it is merely honest.

---

## Related

- `docs/adr/0002-time-at-the-resolution-known.md` — the draft under review, since revised.
- `docs/personas.md` — the personas, including the Journalist this review produced.
- #74 — media that belongs to a Festival, not to one Gig inside it (Finding 5's dependency).
- #18 — the BLE attestation mechanism the gold star in Finding 1 rests on.
- #55 — the planned-gig leaf as a function of time.

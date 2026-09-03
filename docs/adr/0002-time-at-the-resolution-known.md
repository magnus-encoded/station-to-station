# ADR-0002: A night is dated at the resolution it is known

**Status:** accepted (2026-08-06). Grilled through a persona review — see
`docs/persona-review-0002.md` for the findings this revision absorbs.

## Context

[[setlist.fm]] has been doing two jobs, and they have never been separated.

**As a source** it is excellent: it filled the line with 202 shows since 1992, and nothing here
proposes giving that up.

**As the schema** it has been setting the terms. A record needs an artist that resolves to a
MusicBrainz id and an exact night, because those are the fields `FmSetlist` has. That was not a
decision anyone made — it arrived free with using their model as ours.

Three changes have already demoted parts of that schema from *precondition* to *attribute*:

- **#107** — a Gig gets an identity the app owns; the setlist.fm id becomes an attribute.
- **#121** — `url == null` *is* the definition of local. Their record's absence became a property of
  ours rather than a gap in ours.
- **The Log** (#121) — "the app is the source of truth about what was observed and setlist.fm is a
  publication target". Songs stopped needing their record to exist.

~~Identity: done. Songs: done. **Time is the one that was never carried through.**~~
**Identity: done. Songs: done. Time: this ADR. Festivalhood: #166, the fifth** (amended 2026-08-25 —
see the amendment below).

`FmSetlist.eventDate` is a `dd-MM-yyyy` string and `localGigSetlist` must supply one, which is why
`markActPlayed` calls `billNight(now)` (`AppViewModel.kt:1262`). Not because the night is known — because
the record cannot exist without a day. That is the same dilemma `StoredBill` was created to escape
("inventing a day per act is precisely the fabrication the record must not commit"), solved for the
lineup and left standing for the night.

Two cases force it, both verified 2026-08-06:

**An artist that cannot have an mbid.** Silent Majority, Ringnes' house band since 1983, has no
[[MusicBrainz]] release for *Back from the dead* (2016) and no MB artist entry that is a 2016 band. Six
other bands hold the name — Long Island hardcore, Swiss hip-hop, 70s soul, LDS, Ohio punk, Malaysian
grindcore — so `fetchCandidates`' `firstOrNull { name matches }` binds to a stranger, and
`disambiguateAct` correctly exhausts every namesake and finds none. The app can learn *this band is
not on setlist.fm* and currently discards the finding.

**A night known only to the year.** `HilsenfraRINGNEShistorie.pdf` records who played which edition
and nothing finer. Ringnes ran annually 2001–2005, moved to galleries abroad 2006–2010, returned as a
biennial work 2011–2023, and returns in 2026 — so "every Ringnes since 2013" is 2013, 2015, 2017,
2019, 2021, 2023. Six editions, a lineup each, and no memory of which night anyone played.

There is precedent in the code already: `gigLeaf` takes a **window** rather than an instant, "as
coarse as the caller knows", and #55 states the planned-gig leaf is a function of time. Coarse time is
an idea this codebase has, applied only to the future.

## Decision

**A Gig carries a date at the resolution actually known** — a night, a festival edition, or a year —
and setlist.fm's exact date becomes an attribute it may acquire, exactly as its id did in #107.

**Coarse is not incomplete.** "Silent Majority, Ringnes 2017" is a whole fact, not a partial one. It
renders as itself, never as a night missing its date, and nothing in the app offers to complete it.
This is the `StoredLog.closed` rule applied to time: **no automatic process sharpens a resolution
without a person's action.** Adoption of a setlist.fm id *is* such an action — they tapped the button
— so adoption may sharpen; a background import or round trip may not.

**One derivation, three consequences — never asserted.** A record that has an artist resolving
upstream, day resolution, and a venue is **publishable, checkinable, and verifiable**. Not three
bespoke guards but one derived predicate, in the same way `local` needs none today. Silent Majority
fails it on the artist; a 2017 memory fails it on the day. Nothing else in the app needs to ask.

**Attendance is asserted, never granted.** I decide I was there; the app can only add a confirmation
on top. This is the 2026-07-29 ruling — *attestation is a badge on an entry, not a gate on the entry
itself* — and it is what keeps the derivation above from becoming a gate. A backfilled Ringnes 2017 is
a full entry that can never earn the gold star, because the star needs a live in-the-moment check-in.
**Coarse and unattested are separate axes**; the mechanism keeping them separate already exists.

Accordingly the timeline's count means *nights I say I was at*, not *nights setlist.fm knew about*.
The header has read "202 shows · since 1992" because that was the limitation, not the definition.

**Correspondence between people needs a key the record can actually have.** `setlistId` is currently
the only one (`TimelineStore.kt:80-82`), so two people who each entered a night locally never cross —
*even with identical exact dates*. That is a defect independent of resolution, and this ADR would
otherwise make it worse by making local-forever a permanent first-class state. An artist can only be
one place on one night, so **artist + venue + day derives a correspondence key both devices agree on**,
with no server and no setlist.fm — `uuidFrom("gig:$artist|$venue|$day")`, the same shape as
`gigIdForSetlistId`. setlist.fm's own model helps here: it distinguishes festival from venue and warns
against recording the festival as the venue, so the venue component is a real venue on both sides.

## Consequences

- **`markActPlayed` stops inventing a day.** An act marked played on a past Bill is dated to the
  edition, and the 06:00 `billNight` boundary becomes one resolution among several rather than a
  correctness rule.
- **Sorting is already solved.** "A Bill sorts by when it *starts*" — a coarse node sorts by the start
  of its range, which is what `FutureRow` does today. No new rule.
- **A backfill import may never mark attendance.** The history PDF says who played; it never says who
  was seen. An import creates the Bill and its acts unmarked, and the person ticks off what they
  remember. Its year lists are also *appearances*, not Ringnes appearances — `The Silent Majority
  (…/2006/…)` and `Girl From Saskatoon (2005/2007/2008)` are the gallery years — so read naively that
  document places bands at a festival that was not running.
- **`fetchCandidates` matches artist names with exact `equals`.** The history says *The* Silent
  Majority, the 2026 programme says Silent Majority. Same failure family as the namesakes, different
  cause, and it will bite any backfill.
- **The two unfinished items in #121 become the whole of the work**: a Bill must be able to sit in the
  past, and an act must be markable played without being dated. No new record type is needed.
- **Every screen that reads `eventDate` for display must learn resolutions.** This is the cost, and it
  is the reason this is an ADR rather than a patch.
- **The derived key sits *beside* the local id, never replaces it.** `TimelineStore.kt:76-78` gives the
  reason: media hangs off a gig and is irreplaceable, so "a key that can change — or that a night can
  fail to have — is a key that can orphan a keepsake." A content-derived key changes the moment someone
  fixes a typo in an artist name. Storage stays on the stable local id; the derived key is for
  correspondence only, and `keyOf` gains a third case.
- **Normalisation is the work, not the hashing.** Two devices that normalise differently derive
  different keys and *silently* fail to cross — indistinguishable from "we weren't at the same gig".
  This is the same failure family as `fetchCandidates`' exact `equals` above, and it needs a way to be
  seen rather than only a way to be avoided.
- **A coarse record derives no key.** It has no day, so nothing to hash. A coarse node meeting a dated
  one is therefore a **render-time** correspondence, never a stored one — which is what keeps "only a
  person may sharpen" intact while still drawing the Crossing.
- **A coarse node crosses when its range holds exactly one occurrence of that artist.** Lilliedugg
  played Ringnes 2017 once, so a 2017 memory and a friend's dated record provably mean the same night.
  Motorpsycho played several nights in 2021, so the same comparison would merge two people who were
  there on different evenings. **Uniqueness is the test, not resolution.** For artists setlist.fm knows,
  the occurrences in a range are countable.
- **The Historian's outward flow is genuinely reduced, and the app's job is to record the finding.**
  When `disambiguateAct` exhausts every namesake it has learned a durable fact — *this band is not on
  setlist.fm* — and currently discards it. Kept, a record knows whether it *could* become publishable,
  and re-derives that by itself if the artist is added upstream later; nothing is stranded by an old
  decision. The app never nags about it: editing MusicBrainz is desk work, not something to prompt for
  while taking notes at a gig (see `docs/personas.md`, the Journalist).

## Amendment (2026-08-25): festivalhood is the fifth demotion

**What changed.** The Context lists three demotions from *precondition* to *attribute* and names time
as the last one outstanding. There is a fifth, landed by #166: **festivalhood**.

Before it, a **Festival** was a precondition of a shape. The app inferred one from `FmSetlist` alone —
same venue, within a four-day window — and a cluster that matched *was* a festival, labelled with its
venue when no name had resolved. Nothing had told the app that any of those nights were a festival;
the schema's own fields decided it. That is exactly the pattern this ADR names: a fact about the world
arriving free with using setlist.fm's model as ours.

Now a **Festival** is a `StoredFestival` — an identity the app owns, minted by `uuidFrom`, carrying
the vendors' keys (`setlistFmSlug`, `mbid`) beside it as enrichment the way a **Gig** has carried its
setlist.fm id since #107. A run of nights is a festival when a source *says so*, and otherwise it is a
**Section**: several shows on one evening, named from its own acts. Two nights at one venue with
nothing that knows what they were are now two **Nodes**, which is the smaller true thing.

**What made it change.** A headline show with support is indistinguishable, in the data, from one day
of a festival — so the inference could not be made correct, only made quieter. It drew a room on the
Line as though it were an event, and the only fix is evidence: ask a source that knows, and take *no*
for a durable answer.

**What it costs.** The same thing every demotion here costs. Where a shape used to be free, the fact
now has to be learned and stored, and the record has to be honest when it has not been: a **Section**
is what an unidentified evening stays, and no process turns it into a **Festival** on its own. That is
this ADR's own rule about sharpening, applied to a second axis.

**Related:** ADR-0004 (each field of a scraped identity degrades to null independently), ADR-0017 (the
grouping rule is the identical in-between, so both builds assert it case for case).

## Resolved by the persona review

1. **Can a coarse node be a Crossing?** Yes — when the range holds exactly one occurrence of the
   artist, as a render-time correspondence. The review found the question was aimed at the wrong
   thing: the blocker was never *coarse*, it was *local*. See the Decision's correspondence key and
   the uniqueness rule in Consequences. The Historian's objection — a merge asserts you were in the
   same field on the same evening — is answered for Lilliedugg and upheld for Motorpsycho, rather than
   split.
4. **Does anything sharpen automatically?** A wording bug, now fixed. Adoption is a person's act, so
   *"no automatic process sharpens a resolution without a person's action"* carries the intent without
   the contradiction.
5. **Is this needed at all before it is needed?** Yes, and for a reason the draft did not have: the
   fine↔fine correspondence defect is live today, with or without backfill. Two people who each added
   the same night by hand do not cross right now.

## Open questions

1. **Where does the resolution live?** On the app-side `StoredGig`, with `localGigSetlist` synthesising
   whatever display string is needed — or on the synthetic `FmSetlist` itself. The first is cheaper and
   keeps the lie in #121 from growing; the second is fewer places to look. Pick before implementing.
2. **Is three resolutions right?** Night / edition / year is a guess from two examples. A festival
   edition is arguably just a named range, in which case there are two — exact and range — and
   "edition" is a label on a range rather than a kind of its own.
3. **Is sharpening reversible?** Surfaced by the review when the contradiction above dissolved.
   Adopting a wrong setlist.fm id sharpens a resolution on a person's action, as intended — but if it
   is a one-way door, a mistaken adoption permanently overwrites what they actually remembered. The
   Reliver also wants to *find out* when a night moves, rather than have it move silently.
4. **How does a failed correspondence become visible?** Two devices that normalise a venue or artist
   differently derive different keys and simply never cross, which looks exactly like not having been
   there together. Nobody has a way to see this happen.

## Amendment (2026-09-03): the Bill machinery this ADR points at is gone

**What changed.** #391 decommissioned `StoredBill`/`StoredAct` outright — no migration, the loss
authorised by the project owner. Every site-specific reference above (`markActPlayed`,
`billNight`, `fetchCandidates`, `disambiguateAct`, "an act marked played without being dated") named
code that no longer exists. **Departures**, committing a published programme's rows through
`AppViewModel.commitProgramme`, replaced the poster as the way a planned night enters the timeline —
and a Departures row already carries an exact date from the programme, so the coarse-resolution
problem this ADR was written to solve (a festival edition with no day, a year with no edition) does
not arise on that path at all.

**What still holds and what does not.** The generalisation is untouched: ~~`Gig` carries a date at
the resolution actually known~~ **a Gig carries a date at the resolution actually known**, and the
five-item demotion sequence (identity, songs, time, festivalhood, …) is a real pattern independent
of which feature motivated it. What does not survive is the *plan*: this ADR sketched Resolution as
riding in on the Bill's backfill machinery, and that vehicle is gone. Date precision below "exact
night" is accordingly **deferred, not decided** — nothing in #391 required it, and no coarse-date
feature has been built to replace the one this ADR assumed.

**What this means for the open questions.** All four stand exactly as asked; none were answered by
#391, because #391 touched the Bill's *lineup* half, not this ADR's *time* half. A future
implementation of Resolution needs its own concrete carrier (a coarse Gig, a coarse Departures
row, or something else) rather than `StoredBill`'s.

**Related:** #391 — the decommission; `CONTEXT.md`'s Bill/Act entries, retired the same day.

## Related

- `docs/adr/0001-logic-layer-above-plumbing.md` — this ADR's rules belong to the logic layer.
- `docs/persona-review-0002.md` — the review that produced this revision.
- `docs/personas.md` — the six personas the review argued from.
- `CONTEXT.md` — the vocabulary; **Resolution** is a new term and needs an entry, and `CONTEXT.md:91`
  ("a Gig attended by two people produces exactly one Crossing") is a promise only the correspondence
  key can keep.
- #34, #93, #121 — the local-gig machinery this generalises.
- #55 — the planned-gig leaf as a function of time; the same idea, other direction.

**Dependency, not a neighbour:** #74 — media that belongs to a Festival, not to one Gig inside it. A
coarse node has no setlist and therefore no playlist, so photographs are the only thing that makes a
backfilled 2017 node worth opening. Without #74, backfill adds rows nobody can do anything with.

- #18 — the BLE attestation the gold star rests on.

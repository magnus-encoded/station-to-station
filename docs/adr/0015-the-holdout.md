# ADR-0015: The Holdout

**Status:** accepted as **served, and the first persona who pulls the other way** (2026-08-15)

## Context

`docs/personas.md` has carried a standing admission since 2026-08-11: *"No persona is protective, and
that is a known hole."* All eight named personas pull toward more record, more comparison, more
sharing. The Journalist looks like the exception and is not — her private notes are material saved
for an article, not material protected from exposure. So every requirement about **exposure and
dependency** — encrypting a transfer, bringing your own key, working without an account, leaving with
your data — has had to be argued on its own terms, one at a time, with no persona behind it.

That is not a theoretical gap. Three shipped pieces of work were argued *ad hoc* and would each have
been predicted by a persona that did not exist:

- **The zero-account floor** (#225/#226). Argued on the fix axis — "the app asserts something untrue"
  — rather than on anyone's behalf.
- **Bring-your-own key, and the bundled one masked** (#227). Whose key, whose quota, whose
  rate limit.
- **No backend for the social layer** (ADR-0003). Decided on architectural grounds. This is the
  persona whose *motive* it happens to serve.

## Decision

**The Holdout is a first-class persona: a user whose consent to a dependency is conditional on being
able to leave it.**

He is deliberately a caricature, and that is the point. Stereotyping is what makes a persona usable —
a sketch you can picture arguing produces sharper answers than a neutral description of a
requirement. *Would the Holdout accept this?* has an answer. *Is this a privacy problem?* does not.
The Volunteer (ADR-0014) was named on exactly this reasoning.

**Doctorow-shaped, not Stallman-shaped.** The distinction is load-bearing. A pure free-software
absolutist would not carry an Android phone and would therefore never reach any surface this project
builds — a persona who cannot be a user generates no requirements. The Holdout is the pragmatist
version: he carries the phone, he uses commercial services tactically and with his eyes open, and he
minds what is collected about him. He reads the terms. He has opinions about lock-in that he will
share whether or not you asked. He donates to the EFF, runs Linux on the desktop, and has said "I use
arch, btw" out loud and unironically.

His refusals are specific rather than general: *"setlist.fm? Why would I sign up to that?"* He is not
refusing the app. He is refusing to make a third party the custodian of his own record.

**Autonomy and privacy are one motive here, not two.** An earlier draft of this ADR split them — the
free-software hawk and the privacy hawk as separate personas. That split is rejected. The Doctorow
shape is the existence proof that they are one worldview: minding what is collected and minding
whether you can leave are the same objection to the same arrangement, made at two moments. Splitting
them would have produced two vague personas instead of one sharp one.

## Consequences

- **Every feature that needs an account must degrade to something, not to nothing.** This is the
  general form of #225. A surface that is blank without setlist.fm is a surface that tells him the
  app was never for him.
- **Export is the first concrete thing he is owed, and it is #106** — the night as durable Markdown
  in a folder the user picks. No other persona generates it: the Collector wants the collection *in*
  the app (#141 device handover is his, and is a different requirement — it moves the collection
  between two installs of *this* app), the Historian wants facts flowing outward *to setlist.fm*,
  where upstream is a destination rather than portability. Flowing outward to nowhere in particular
  is the Holdout's ask alone. This does not change #106's spec; it changes its priority, from a
  keepsake feature to the condition of his consent.
- **A durable export cannot carry vendor pointers and call itself durable.** Raised against #106 as
  "should playlists export as `.m3u8`" and answered no: an m3u8 of `spotify:track:` URIs looks
  durable and is not, which is worse than its absence. The form that survives is open identifiers
  (MusicBrainz MBIDs) in the front matter, resolvable by anything — best-effort per song, since
  setlist.fm gives a title and title → work → recording is lossy for live material.
- **He pulls hardest against the Reliver** (ADR-0009). The Reliver is served *by playlists* — that
  is, served by Spotify, a proprietary service with a permanent five-user development cap. The
  Holdout will not have a Spotify account. **Unresolved on purpose:** what the Reliver's motive is
  served by when Spotify is off the table has no answer yet, and inventing one here would be worse
  than recording the gap.

  ~~This tension was invisible before he was named; nothing in the persona set objected to that
  dependency, so it read as settled when it is not.~~ **Corrected 2026-08-15, same day:** false, and
  it took credit that belongs to ADR-0009. That ADR's own amendment had already reopened the music
  primitive hours earlier, on the ground that a permanent five-user allowlist contradicts the
  premise that every capability is unlocked by a source *the user chooses to plug in*. The Holdout
  does not discover this tension. What he adds is a person who holds the position permanently rather
  than a term that happens to be bad: ADR-0009 reopened the question about *this vendor*, and he is
  why the answer cannot be *a better vendor of the same shape*.
- **He pulls against the Collector** (ADR-0007) on anything that compares collections. Comparison
  wants identity, identity wants accounts, and he refuses the account. Comparison features must
  therefore fail soft — absent, not broken.
- **He pulls against the Historian** (ADR-0008), narrowly. Both want the fact to survive; they
  disagree about where it should go. The Historian pushes it upstream to setlist.fm, which needs an
  account. The Holdout wants it portable rather than uploaded. The existing local-**Gig** machinery
  (`adoptSetlistId`) is what lets both be true later, so this one has a resolution and the others do
  not.
- **He closes the protective hole only halfway.** `docs/personas.md` names two halves: the
  third-party half, which the Volunteer covers, and the owner-protecting half, which had nothing. The
  Holdout is the owner-protecting half. Requirements about *blast radius on someone else's behalf*
  still come from the Volunteer, and neither of them speaks for a user who is careless rather than
  careful.
- **He is not a licence to build a threat model nobody asked for.** He is a scoping heuristic like
  the rest of the set. A feature he would tolerate but dislike is not thereby wrong.

## Related

- ADR-0003 no backend for the social layer — decided on other grounds, serves his motive.
- ADR-0007 the Collector, ADR-0008 the Historian, ADR-0009 the Reliver, ADR-0014 the Volunteer.
- #106 the vault — the export he is owed. #222 the music primitive, reopened on the vendor's terms.
- #225 / #226 the zero-account floor; #227 bring-your-own key.
- `docs/personas.md` — the standing resolution about the protective hole, now amended.

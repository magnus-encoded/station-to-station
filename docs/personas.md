# Personas

A scoping heuristic, not a research artefact. One user is usually several of these at once —
**overlap is expected, not a modelling failure.** The useful question is rarely "which persona is
this feature for"; it is "does another persona's motive pull against it on the *same* surface".
That is a design decision to make explicitly rather than let slide.

## The six

- **The Collector** — motivated by the collection itself, but it doubles as a status symbol:
  comparing notes with others is part of the draw. Creates real tension with the Historian on any
  feature that lets collections be compared.
- **The Historian** — wants documentation, true facts about what happened when. Data flows *outward*,
  toward a correct shared record.
- **The Reliver** — wants to re-experience former glories. Largely served already by playlist export.
- **The Friendgroup Member** — social. The multi-timeline view is a symbol of her friendships; wants
  friends at future gigs and to find each other on arrival. Keepsakes read as communal.
- **The Tastemaker** *(future, not MVP)* — wants reach, earned through real merit primitives rather
  than vanity metrics. Follower-count-shaped signals were explicitly rejected. Deliberately not
  designed further yet.
- **The Journalist** — writes *about* gigs. See below.

**Not a user: the Organizer.** Event organisers' and artists' interest, relevant only to possible
future content packs (an artist enriching their own gig entries with logos or images). Not being
built; kept so a sponsorship feature gets reasoned about rather than bolted on. Any such content
stays presentation-layer — sponsor money must never touch the Historian's record.

## The Journalist

Named 2026-08-06, during the ADR-0002 persona review.

The objection to naming it is fair and worth recording: it looks like the overlap between the
Historian (documenting truthfully) and the Tastemaker (publishing for reach). Half of it is. The
*published* half is Tastemaker-shaped. But the private half — notes for me and my contacts, a
personal corpus — belongs to neither: the Historian's data wants to reach a shared record, the
Tastemaker's wants an audience, and a note nobody else reads wants neither.

What the persona actually adds is an axis none of the other five have: **which surface, and how much
attention.**

- **Phone, at the gig** — capture. One-handed, wrong-tolerant, coarse. "Lilliedugg, Ringnes 2017"
  typed from memory belongs here.
- **Laptop, at home** — write, enrich, publish, fix upstream. Editing MusicBrainz so an artist finally
  gains an mbid is desk work.

**Apply it like this:** when a persona's motive implies work the app *could* prompt for, ask which
surface that work belongs to before adding the prompt. The phone's job is to record the finding
durably — *this band is not on setlist.fm, checked* — and the desk surface is where it becomes
actionable. That dissolves the apparent conflict between the Historian wanting records to graduate
and the Collector not wanting a permanently local record to read as an unfinished chore. Nagging is
only nagging on the phone.

### The workflow it describes

A music journalist with three contacts covers Lilliedugg at Ringnes 2017. Her check-in was signed.
Two contacts posted to contacts-only: "Awesome concert" and "Best since Wannsrækk in 2013". At home
she exports to an Obsidian vault — her own photographs and her contacts', cloud pointers rendering
inline with playback controls, and her private notes, the ones holding the comparison she is saving
for the article. She can see the reactions her contacts left. She is weighing whether a glowing
review matching the fan reaction or a trashing is the better piece to publish.

Two things fall out of this that the other personas do not show:

- **The gold star has no external value.** Her readers trust that she was where she said she was, and
  her photographs settle any argument. Attestation is an in-app primitive; it does not travel with a
  published piece and does not need to.
- **Notes are media with a private bool, and export is the publishing path.** Sharing with contacts
  is an *act* — you send a copy, they own theirs — so there is no mine/shared/public tri-state to
  model. Whether it is a critic filing to a newspaper or a normal user posting to Facebook, they
  export and publish themselves. The app builds no feed, no discovery, no promotion.

Sending text to another device relinquishes control over it. That is true of every messaging app
already, and forging a quote is as easy as it is pointless. Text differs from photographs in kind —
a decontextualised sentence is a quote, and a review has a subject who can be harmed — but not in a
way sharing mechanics can fix, so the model does not pretend otherwise.

### Why the publishing half stays deferred

Not "unclear how it is published" — export answers that. The real reason: the cred primitive (gigs
attended, gold-star attestation) lives inside the app and does not travel with an export. A signed
attendance receipt embedded in a published piece is possible in principle, but nothing exists to
verify it against outside the contact graph. So export serves the Journalist fully and the Tastemaker
not at all. That is a sharper deferral than the one it replaces.

## Standing resolutions

- **Attestation is a badge on an entry, not a gate on the entry itself** (2026-07-29). Anything may be
  added from memory. What cannot be earned without a live in-the-moment check-in is the gold star.
  I decide I was there; the app can only add a confirmation.
- **"Communal" keepsakes need no shared backend state.** Converting a gig to a playlist prompts "you
  were at this gig with X — send them this playlist?". Each person ends up with their own copy; there
  is no canonical shared object to keep in sync.
- **Collector-vs-Historian friction is narrow.** setlist.fm is crowd-sourced and largely accurate, and
  there is little incentive to fabricate a setlist. The one thing a status-motivated Collector might
  fudge is *claiming attendance after the fact* — which is precisely what attestation guards, rather
  than fraud-proofing the whole record.
- **We resist global, top-down publishing structures for user-generated data.** It keeps the design
  honest and keeps servers out of the social layer.

## Related

- `docs/persona-review-0002.md` — these personas arguing over a draft ADR, as stories.
- `docs/adr/0002-time-at-the-resolution-known.md` — what that review changed.

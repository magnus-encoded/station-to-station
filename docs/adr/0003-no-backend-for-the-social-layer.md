# ADR-0003: No backend for the social layer

**Status:** accepted (2026-08-11, recording a decision in force since the project began)

## Context

This is the most consequential architectural decision in the project and it has never been written
down. It has been applied consistently — in `friends-no-backend`, in the **Exchange** design, in
#28's **Reconcile**, in the rejection of a retraction mechanism — but always as a premise rather
than a decision with reasons, which makes it the kind of thing a reasonable person re-proposes every
few months.

The pressure to add a server is constant and each instance sounds modest. A relay would make media
sharing reliable. A directory would make finding contacts easy. A revocation service would make
retraction work. A sync service would remove the device-handover problem entirely. Every one of
these is a real improvement to a real weakness.

The counter-pressure is what the record *is*. A timeline here is somebody's concert-going life,
often decades of it, including nights nobody else knows about, photographs of people who never
consented to being photographed (see the **Volunteer** in `docs/personas.md`), and **Personal**
items withheld from everyone. It is also intended to outlive the phone it is on, the app version
that wrote it, and plausibly the company that would run the server.

## Decision

**The social layer holds no server. User data lives on the user's device and moves directly between
devices.**

Concretely:

- **Contacts** are exchanged phone to phone, in person. There is no directory, no account, no
  user id issued by us.
- **Reconcile** is pairwise and direct: two devices intersect what they both **Attended** and
  exchange what the other lacks.
- Media bytes go peer to peer, or through storage the *user* owns (**Pointer**, BYOS). We never hold
  them.
- There is no feed, no discovery, no promotion, and no top-down publishing. Export is the publishing
  path, and the user publishes.
- Third-party APIs (setlist.fm, Spotify, MusicBrainz) are read as sources. They are not our backend
  and nothing we own accumulates there.

**The one carve-out, defined narrowly:** a server is permissible for a *publisher* — the Organizer
case, an event organiser or artist enriching their own gig entries. That is somebody publishing
their own data about their own event, not us holding somebody's private record. Any such content
stays presentation-layer and must never touch the Historian's record.

## Consequences

- **Several features are permanently weaker, and that is the price.** Retraction cannot be
  guaranteed, because there is no channel that reliably reaches a device. First-contact sync is a
  large transfer with no server to stage it. A lost phone with no backup is a lost record. These are
  accepted, not open problems awaiting a server.
- **Best-effort is the honest promise for anything involving another device.** See ADR-0004.
- **Correctness has to be earned without a coordinator.** Two devices must converge with no
  authority to arbitrate, which is why merges are unions with deterministic tie-breaks (older id
  wins) rather than last-writer-wins, and why identity is content-addressed or exchanged in person.
- **"Just add a small service" is out of scope by default.** A proposal that adds one must argue
  against this ADR explicitly rather than treat it as an unstated preference.
- **Privacy is structural rather than promised.** There is no breach to have, no subpoena to answer,
  and no policy change that could retroactively expose the record — because nobody but the user
  holds it. This is the property that makes the **Volunteer**'s exposure bounded by the sender's
  contact list rather than by an index.
- **No operating cost, and therefore no business model pressure on the data.** The record cannot
  become the product, because it is not in our possession.

## Related

- `docs/personas.md` — the Organizer carve-out, and the Volunteer this protects.
- `UBIQUITOUS_LANGUAGE.md` — **Reconcile**, **Contact**, **Pointer**, **Personal**, **Audience**.
- ADR-0004 — best-effort enrichment, which follows from this.
- #28, #102, #103, #104 — the peer-to-peer sharing design this constrains.

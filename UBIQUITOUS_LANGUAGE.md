# Ubiquitous Language

The vocabulary of the timeline — the fractal spine that is Station to Station's whole
visual language. Most of these terms were used loosely for a whole session before they
were pinned down, and every ambiguity below cost a build/install/look round trip. Use
these words exactly; if a new concept appears, name it here **before** building it.

## The line

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Line** | One person's concert-going life, drawn as a continuous vertical stroke. A line is a life: it runs unbroken, it is never displaced, and it never detours to visit something. | timeline (ambiguous — see below), rail, track, spine |
| **Spine** | The fixed x-position my own **Line** occupies in every row, at every **Resolution**. Geometry, not a drawing: `SpineX`. | my rail, the main line |
| **Lane** | The x-position a friend's **Line** occupies when the strip is open. Lane 1 is nearest my **Spine** and belongs to the most recently added friend. | rail, column, track |
| **Edge** | The stretch of **Line** between two of my own **Nodes**. Shows only someone else attended make my edge *longer*; they never compress my line. | gap, spacing |
| **Node** | A point on a **Line** marking something that happened: a **Gig**, or a **Section** or **Festival** standing for many. | dot, marker, stop, station |
| **Crossing** | The single **Node** for a night two people were both at. There is exactly one — never one node each joined by a rung, which reads as two concerts. | merge point, shared node (acceptable informally), intersection |
| **Joined** | The state of two **Lines** after a **Crossing**: they are one line, in the meeting's colour, and stay joined through a run of shared nights until one of them wasn't there. | merged (fine), braided, woven |
| **Parting** | Where a **Joined** run ends because only one of them was at the next thing. The visitor draws its own way back to its **Lane**; my **Line** does not move. | split, diverge, unmerge |

## Resolutions (the zoom ladder)

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Resolution** | One rung of the continuous-zoom ladder. Zooming changes resolution; it never navigates to a screen. | view, page, screen, level |
| **Timelines resolution** | Zoomed out: my **Line** plus every known friend's, date-synced at the same scale. Reached by pinching out — the strip beside my spine opens *in place*. | multi-timeline view, comparison view, woven view |
| **My timeline** | The single-line resolution: my own **Gigs** and **Festivals**. The starting position. | home, main screen |
| **Festival resolution** | A **Festival** uncollapsed **in place**, listing the **Gigs** inside it. Never a screen of its own. | festival screen, festival page |
| **Gig resolution** | One night: its setlist, its media, the playlist export. | event screen, concert detail |

## Movement

A **Movement** is Grammar and is named here. The **control** that triggers one — which gesture,
which button, where it sits — is Expression under ADR-0017 and is the platform's to choose, so it is
deliberately *not* named here. The absence is the rule, not an omission: two builds performing the
same **Movement** by different gestures agree, and two builds where the same gesture performs
different **Movements** do not.

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Movement** | A change of position or **Resolution** in the corridor. Always one of the three below; a proposal that is none of them is proposing a new place, which is ADR-0006's test. | transition, navigation (ambiguous — it also names the control) |
| **Outward** | Leaving an **Inner** place for the **Outer** one and arriving back at the position you left. Never a new place, and never a second copy of the one you came from. | back (fine informally), up, dismiss |
| **Opening the strip** | The **Lanes** appearing beside my **Spine** *in place* at the outermost rung. A change of **Resolution**, so there is no journey and no return journey. | opening the timelines view, zooming to a screen |
| **Lighting** | A change to how the place you are standing in is shown, coherent at every **Resolution** at once. Needs no destination, and the switch that turned it on turns it off. | mode, filter view, toggling a screen |

## Events

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Gig** | One artist, one night. The atom of a timeline. The word shown to users when nobody else is on screen ("13 gigs"). | show, concert, event, setlist |
| **Festival** | A **Node** standing for several **Gigs** that a named event says belong together. Festivalhood is an **identity**, never a shape: nothing is ever inferred from a venue and a run of dates. Two nights at one venue with nothing that knows what they were are two **Nodes**. Collapsed by default at every resolution. | cluster (internal only), run, multi-day |
| **Festival identity** | What makes a **Festival** one: setlist.fm's own festival entity — its key, name, date range and day-by-day lineup — or a **Bill** I authored. Held under a stable local id; a **Gig** carries its membership. Absent it there is no festival, however the nights are shaped. | festival name, event name |
| **Section** | The **Node** for several **Gigs** in one room on one night with no **Festival identity** behind them — a headline show with support. Billed by its own acts ("Devin Townsend (Haken)"), never by the room, and never spanning two nights. | festival (it is not one), cluster, evening |
| **Absorb** | What a **Node** of mine does to a friend's: the same **Festival identity**, the same **Gig**, or the same evening in the same room folds in, marking the node shared, instead of sitting beside it as a second node. | merge (reserved for lines), group |
| **Attended** | On someone's setlist.fm attended list. The *only* thing that makes a **Gig** theirs. | went to, logged |
| **Bill** | A **Festival** whose **Gigs** don't exist yet: a name, a venue, a date range, and a list of **Acts** with no day each. What a poster tells you before you get there. One **Node**, above today, and it stays one **Node** as its **Acts** are dated. | lineup (fine informally), poster, programme |
| **Act** | A name on a **Bill**. Not a **Gig** — it has no date, and it may never play. It *becomes* a **Gig** the moment someone standing there says which night it played. | artist, slot, booking |
| **Maybe** | An **Act** the poster itself hedges — "bringing guitars, we'll see". A **Bill** that can only express "confirmed" is lying about what is known. | tentative, unconfirmed (fine) |
| **Surprise** | An **Act** that was never on the **Bill** and walked on stage anyway. Added dated, in the field, in one gesture — it is only ever discovered *after* it happened. | unannounced, secret set |
| **Local** | A **Gig** with no setlist.fm id: it exists on this phone and nowhere else. A property of the *record*, not of how the claim was made — so it is worth showing, where "self-reported" (true of nearly every **Gig** here) is not. A **Local** **Gig** cannot be a **Crossing**, which is what the setlist.fm nudge is trying to fix. | self-logged, unverified, private, offline |
| **Log** | The ordered songs *I* observed at a **Gig**, written down on my own device. Mine, first-hand, and **never setlist.fm's setlist** — that is the published shared record, this is the witness statement it may one day be built from. A **Gig** has at most one **Log**; a **Log** without a **Gig** is not a thing. | setlist (reserved — that word means setlist.fm's record), notes, capture, transcript |
| **Open** / **Closed** | A **Log**'s own account of whether it is finished. **Open** is the default and means *"there may be more"*; **Closed** is a claim only its owner makes, deliberately, saying *"that was the whole set"*. A capture built by ticking off songs an artist has played before is **incomplete by construction** — it cannot reach a new song, a cover, or an artist with no history — so **Open** is the honest starting state and nothing but a person may change it. | complete/incomplete (fine informally), done, finalised |
| **Gap** | An entry in a **Log** for a song that *was played* and could not be named. A stated **Gap** is a true fact and belongs in the record; the same song silently left out is a **Log** lying about its own certainty. | missing, unknown (fine), blank |
| **Performance** | One line in a set list: *this song, played by this artist, on this night.* That is the whole fact, and it is **independent of any vendor**. A Spotify track is not what a **Performance** is — it is one *resolution* of it, on one service, and a lossy one: the studio recording is a different take of the same song, played by nobody, on no night. A **Performance** is what survives when Spotify does not. (Unrelated to "performance" as speed; that word only appears in `docs/measuring-on-device.md`.) | track, song (fine loosely), setlist entry, item |
| **Resolver** | Anything that turns a **Performance** into something playable *here*: Spotify, a MusicBrainz lookup, a folder of files, a person with a memory. A **Resolver** is swappable by construction, and one failing to answer is a normal outcome — it means "not on that service", never "this song did not happen". | provider, backend, source (reserved) |
| **Playlist** | A **projection** of a **Gig**'s **Performances** through one **Resolver**. Not a stored object and never the record: it is derived on demand, it is per-service, and the same night projects differently on two services and to nothing at all on a third. What gets exported is the **Performances**; the playlist is what someone's player makes of them. | export (reserved — see below), the playlist (there is no *the*) |
| **Publish** | Handing a **Log** to setlist.fm — their web form, their login, our clipboard. It is a **deliberate act afterwards**, never a button offered mid-set, and it **never** changes a **Log** from **Open** to **Closed**: setlist.fm has nowhere to keep that fact, so a published set that came back would look finished when it isn't. | upload, sync, submit (fine), contribute |

## Ownership and sharing

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Mine** | A **Gig** on *my own* timeline. Never inferred from the node holding it — a friend's festival contains only their gigs, and reading ownership off the container marked all of them mine. | ours, attended |
| **Theirs** | A **Gig** on a friend's timeline and not on mine. | not mine |
| **Together** | A **Gig** on both. The number this resolution exists to surface — always stated first: "6 together · 8 yours · 31 theirs". | shared (fine), co-attended, both |
| **Yours/theirs wording** | Only ever shown when someone else is on screen. On **My timeline** a festival reads "13 gigs" — whose is not a question anyone is asking. | — |

## Colour grammar

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Amber** | Mine. My **Line**, my **Nodes**, at every **Resolution**. Brightness carries one extra meaning only: brighter = most recent. | highlight, accent (amber is not "the accent colour" any more — it means *mine*) |
| **Lane colour** | One per friend, cool tones, assigned by lane index. | their accent |
| **Meeting green** | A **Crossing** and the **Joined** stretch that follows it. A meeting belongs to neither person, so it is never amber and never a lane colour. | shared amber, highlight |

## People and exchange

There are **two different relationships to a person**, and the single word "friend" for
both is the ambiguity most likely to turn into a privacy bug. They differ in what they
carry, how they are established, and what they permit.

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Followed line** | A person whose **Line** I pull and draw. One-sided, needs no consent and grants none: their attendance is public setlist.fm data, and following takes nothing from them. Addable remotely. | friend, contact, connection |
| **Contact** | A person I have exchanged keys with **in person**, mutually. The only relationship bytes can flow along. Not addable remotely — ever. | friend, follower, buddy |
| **Card** | What is handed over in an **Exchange**: a public key, a display name, and *optionally* a setlist.fm username. The key is the identity; setlist.fm is an attribute. | profile, account |
| **Exchange** | Two people, standing together, each getting their phone out: the other appears, you tap, *"Connecting with dizzi90"*, and you are contacts from then on. Physical presence is the authentication — the whole point, not an implementation detail. **The moment is one thing; the mechanism is three** (Nearby between Androids, BLE GATT everywhere else, QR when the radio sulks) and the user must never be shown which one ran. Budget: two seconds. | pairing, friend request, connect |
| **Mutual** | The stored bit saying an **Exchange** happened. Outlives the radio session that created it; a **Contact** is exactly a person this bit is set for. | connected, paired |

A person can be a **Followed line**, a **Contact**, both, or neither. Following someone
never makes them a **Contact**, and a **Contact** need not be on setlist.fm at all.

## Media

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Attach** | Putting media on a **Gig**. **Attach is share** — there is no second gesture and no recipient picker. Media generated at a gig is of shared interest by default. | upload, post, add |
| **Personal** | Attached, but never sent: on my own **Gig resolution**, held back from everyone. One bit, default off, the only exception the model has. Excluding a *named person* is deliberately not representable — that is the share sheet's job. | private (fine), hidden, secret |
| **My media** / **Received media** | Whose camera it came from. Always distinguishable — a crowd-sourced entry where you cannot tell what you shot is a worse record, not a richer one. Same instinct as **Amber**. | our photos, the gallery |
| **Audience** | Who **Received media** reaches: **Contacts** who **Attended** the same **Gig**. Derived from data already held, never a list anyone maintains. Check-in is not the gate — it is one kind of evidence for **Attended**. | recipients, share list, circle, group |
| **Reconcile** | The pairwise sync between two **Contacts**: exchange whatever's in the shared band that the other is missing — the whole **Audience** tier, not gated by which **Gigs** either side **Attended**. Idempotent, unordered, and **without a time bound** — which is why a **Contact** made years later enriches an old **Gig** with no backfill path to build. | push, sync (fine), publish |
| **Pointer** | A link into the owner's own cloud (BYOS). What actually crosses the radio; the bytes ride the recipient's internet later. Cross-platform, this is the whole payload. | url (fine), reference |
| **Thumbnail** | The small copy kept forever. The **durable floor** of a keepsake: full-res is best-effort and a **Pointer** can rot, but the grid of that night still renders in ten years. Exchanged in person on Android; fetched from the cloud on iOS, where the radio is too slow to carry it. | preview, cache |

## Relationships

- A **Line** belongs to exactly one person and occupies one **Spine** or **Lane**.
- An **Act** is not on any **Line**. Only the **Gig** it becomes is, and only once it has a
  date — a **Bill** may never produce a **Gig** at all, and that is not a failure state.
- A **Log** belongs to one **Gig** and to me. It outlives **Publish** and is never replaced by
  what comes back from setlist.fm: where the two differ, both are shown and neither is merged.
- A **Log** is a sequence of **Performances**, and so is setlist.fm's setlist. A **Gap** is a
  **Performance** whose song could not be named — the night and the artist are still known,
  which is why it is a fact and not a blank.
- A **Performance** is never stored as a vendor id. Vendor ids are cached *answers* from a
  **Resolver** and may be thrown away without losing anything; the song title and the artist
  are the fact. This is why an export carries titles and open identifiers and never
  `spotify:track:` URIs — a file full of one vendor's pointers is not durable, it is a bet.
- **Publish** is one-way. Nothing setlist.fm returns can make a **Log** **Closed**, because
  their record has no field for it — the knowledge would be destroyed by the round trip, so
  it is never allowed to make the trip.
- An **Act** dated in the field is **Attended** on the strength of standing there, which is
  the same evidence a check-in carries. Its **Gig** has no setlist.fm id and so cannot be a
  **Crossing** until one is adopted — that is what the setlist.fm nudge is *for*.
- A **Gig** sits on every **Line** whose owner **Attended** it.
- A **Gig** attended by two people produces exactly one **Crossing**, on the owner's **Spine**.
- A **Festival** is a set of **Gigs**; it **Absorbs** a friend's cluster rather than duplicating it.
- Zooming moves between **Resolutions**; it never pushes a screen.
- A **Followed line** grants nothing; only a **Contact** can receive media.
- **Attach** puts media on a **Gig** and sends it to the **Audience**, unless **Personal**.
- **Reconcile** runs between **Contacts**, over the whole shared band — not over **Attended**
  in common, not over what was attached recently, and not over who was checked in at the time.

## Example dialogue

> **Dev:** "When their **Line** reaches a **Crossing**, does my **Line** move to meet it?"
> **Designer:** "Never. The **Crossing** happens on my **Spine**; theirs is the one that travels, and they stay **Joined** until a **Parting**."

> **Dev:** "This **Festival** has three gigs of theirs inside it — is it **Together**?"
> **Designer:** "Only if a **Gig** is on both lists. **Absorb** puts their cluster in my node; it doesn't make the nights shared."

> **Dev:** "Alice wants the gig to show she was there with Bob and Charlie, but only Bob to get her photos."
> **Designer:** "The first is a **Followed line** drawing public data — she couldn't withhold it if she wanted to. The second isn't in the model: her **Audience** is every **Contact** who **Attended**. If the photo is only Bob's business she marks it **Personal** and sends it to him in whatever chat app they already use."

> **Dev:** "I added a **Contact** today and we were both at a gig in 2026. Do I need to re-share?"
> **Designer:** "There is nothing to re-share. **Reconcile** has no time bound — the first sync just has a bigger diff."

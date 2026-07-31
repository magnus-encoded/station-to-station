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
| **Node** | A point on a **Line** marking something that happened: a **Gig**, or a **Festival** standing for many. | dot, marker, stop, station |
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

## Events

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Gig** | One artist, one night. The atom of a timeline. The word shown to users when nobody else is on screen ("13 gigs"). | show, concert, event, setlist |
| **Festival** | Two or more **Gigs** at the same venue within a few days, collapsed into one **Node**. Collapsed by default at every resolution. | cluster (internal only), run, multi-day |
| **Festival name** | The festival's real name — "Øyafestivalen 2025", not the venue "Tøyenparken". Comes from setlist.fm's festival entity, scraped from the setlist page; the venue name is the fallback. | venue, event name |
| **Absorb** | What my **Festival** does to a friend's cluster at the same venue and dates: it folds in, marking the festival shared, instead of sitting beside it as a second node. | merge (reserved for lines), group |
| **Attended** | On someone's setlist.fm attended list. The *only* thing that makes a **Gig** theirs. | went to, logged |

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
| **Exchange** | Two people, standing together, each getting their phone out: discovery over Nearby, then **Cards** swapped. Physical presence is the authentication — that is the whole point, not an implementation detail. | pairing, friend request, connect |
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
| **Reconcile** | The pairwise sync between two **Contacts**: intersect the gigs we both **Attended**, exchange what the other is missing. Idempotent, unordered, and **without a time bound** — which is why a **Contact** made years later enriches an old **Gig** with no backfill path to build. | push, sync (fine), publish |
| **Pointer** | A link into the owner's own cloud (BYOS). What actually crosses the radio; the bytes ride the recipient's internet later. Cross-platform, this is the whole payload. | url (fine), reference |
| **Thumbnail** | The small copy kept forever. The **durable floor** of a keepsake: full-res is best-effort and a **Pointer** can rot, but the grid of that night still renders in ten years. Exchanged in person on Android; fetched from the cloud on iOS, where the radio is too slow to carry it. | preview, cache |

## Relationships

- A **Line** belongs to exactly one person and occupies one **Spine** or **Lane**.
- A **Gig** sits on every **Line** whose owner **Attended** it.
- A **Gig** attended by two people produces exactly one **Crossing**, on the owner's **Spine**.
- A **Festival** is a set of **Gigs**; it **Absorbs** a friend's cluster rather than duplicating it.
- Zooming moves between **Resolutions**; it never pushes a screen.
- A **Followed line** grants nothing; only a **Contact** can receive media.
- **Attach** puts media on a **Gig** and sends it to the **Audience**, unless **Personal**.
- **Reconcile** runs between **Contacts**, over **Attended** in common — not over what was
  attached recently, and not over who was checked in at the time.

## Example dialogue

> **Dev:** "When their **Line** reaches a **Crossing**, does my **Line** move to meet it?"
> **Designer:** "Never. The **Crossing** happens on my **Spine**; theirs is the one that travels, and they stay **Joined** until a **Parting**."

> **Dev:** "This **Festival** has three gigs of theirs inside it — is it **Together**?"
> **Designer:** "Only if a **Gig** is on both lists. **Absorb** puts their cluster in my node; it doesn't make the nights shared."

> **Dev:** "Alice wants the gig to show she was there with Bob and Charlie, but only Bob to get her photos."
> **Designer:** "The first is a **Followed line** drawing public data — she couldn't withhold it if she wanted to. The second isn't in the model: her **Audience** is every **Contact** who **Attended**. If the photo is only Bob's business she marks it **Personal** and sends it to him in whatever chat app they already use."

> **Dev:** "I added a **Contact** today and we were both at a gig in 2026. Do I need to re-share?"
> **Designer:** "There is nothing to re-share. **Reconcile** has no time bound — the first sync just has a bigger diff."

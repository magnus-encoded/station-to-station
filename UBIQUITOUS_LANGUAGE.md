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

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Card** | The setlist.fm ↔ Spotify identity handed over in an exchange. | profile, contact, account |
| **Exchange** | Two phones swapping **Cards**, after which each has the other's timeline. Discovery is currently mocked. | pairing, connect, friend request |
| **Known timeline** | A friend whose **Card** I hold, and therefore a **Lane** when zoomed out. | friend (fine), contact |

## Relationships

- A **Line** belongs to exactly one person and occupies one **Spine** or **Lane**.
- A **Gig** sits on every **Line** whose owner **Attended** it.
- A **Gig** attended by two people produces exactly one **Crossing**, on the owner's **Spine**.
- A **Festival** is a set of **Gigs**; it **Absorbs** a friend's cluster rather than duplicating it.
- Zooming moves between **Resolutions**; it never pushes a screen.

## Example dialogue

> **Dev:** "When their **Line** reaches a **Crossing**, does my **Line** move to meet it?"
> **Designer:** "Never. The **Crossing** happens on my **Spine**; theirs is the one that travels, and they stay **Joined** until a **Parting**."

> **Dev:** "This **Festival** has three gigs of theirs inside it — is it **Together**?"
> **Designer:** "Only if a **Gig** is on both lists. **Absorb** puts their cluster in my node; it doesn't make the nights shared."

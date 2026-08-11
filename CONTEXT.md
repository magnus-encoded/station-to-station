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
| **Resolution** | One rung of the continuous-zoom ladder. Zooming changes resolution; it never navigates to a screen. The rungs are named by direction — **Outer** and **Inner** — never by "higher" or "lower", which point opposite ways depending on who is reading. | view, page, screen, level, higher/lower resolution, zoom level |
| **Outer** | Towards **Timelines resolution**: less detail, more nights on screen. Also **broader**. **Timelines resolution** is the outermost rung. | higher resolution, zoomed up, coarser (fine informally) |
| **Inner** | Towards **Gig resolution**: more detail, fewer nights. Also **narrower**. **Gig resolution** is the innermost rung. | lower resolution, deeper (reserved — see **Depth**), finer |
| **Date precision** | How exactly a night's date is known — day, month or year. ADR-0002 is titled *"a night is dated at the resolution it is known"* and means this, not a rung. Unqualified, **Resolution** never means precision. | date resolution, the resolution it is known |
| **Timelines resolution** | Zoomed out: my **Line** plus every known friend's, date-synced at the same scale. Reached by pinching out — the strip beside my spine opens *in place*. | multi-timeline view, comparison view, woven view |
| **My timeline** | The single-line resolution: my own **Gigs** and **Festivals**. The starting position. | home, main screen |
| **Festival resolution** | A **Festival** uncollapsed **in place**, listing the **Gigs** inside it. Never a screen of its own. | festival screen, festival page |
| **Gig resolution** | One night: its setlist, its media, the playlist export. | event screen, concert detail |

## Navigation grammar

The two axes mean different things, always, everywhere. Undocumented, this gets
re-derived differently each time it comes up.

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Vertical is time** | Up is later. A planned gig is not an exception: a night three weeks away sits above tonight, the same as a night in 1992 sits below it. | scroll order, chronology (fine informally), sort direction |
| **Horizontal is depth** | Left to right is **Outer** to **Inner**: outermost **Resolution** → **Gig** → the **Gig**'s **Alcove**. Nothing else is ever expressed sideways. | swipe navigation, tabs, paging |
| **Depth** | Position along the horizontal axis. One step right is one rung **Inner**. | level, hierarchy, drill-down |
| **Back out** | Swipe right. Always one rung **Outer**, on every screen — `swipeRightToBack`. There is no other back gesture and no per-screen exception. | dismiss, pop, close, up navigation |
| **Pinch** | Replaces **Back out** at the outermost rung, where there is nothing further **Outer** to go. Pinching changes **Resolution** in place; it never pushes a screen. | zoom (fine), expand, collapse |

Because **Horizontal is depth**, what a **Gig** offers first is a *position* and not a
button: it is literally what sits one step right of that link. That is why the **Alcove**
is a destination.

## Events

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Gig** | One artist, one night. The atom of a timeline. The word shown to users when nobody else is on screen ("13 gigs"). | show, concert, event, setlist |
| **Festival** | Two or more **Gigs** at the same venue within a few days, collapsed into one **Node**. Collapsed by default at every resolution. | cluster (internal only), run, multi-day |
| **Festival name** | The festival's real name — "Øyafestivalen 2025", not the venue "Tøyenparken". Comes from setlist.fm's festival entity, scraped from the setlist page; the venue name is the fallback. | venue, event name |
| **Absorb** | What my **Festival** does to a friend's cluster at the same venue and dates: it folds in, marking the festival shared, instead of sitting beside it as a second node. | merge (reserved for lines), group |
| **Attended** | On someone's setlist.fm attended list. The *only* thing that makes a **Gig** theirs. | went to, logged |

## The Bill family

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Bill** | A **Festival** whose **Gigs** don't exist yet — for a festival that is not on setlist.fm and cannot be, because which night each act plays is not known to anyone until the poster goes up. What *is* knowable in advance is the name, the venue, the date range and the list of names, and that is exactly what a **Bill** holds. Not a list of planned **Gigs**: inventing a day per act so the existing machinery works is the fabrication the record must not commit. `StoredBill`. | lineup, poster (fine informally), programme, planned festival |
| **Act** | One name on a **Bill**, in poster order. Becomes a **Gig** when it is marked as played — the moment an act is dated it played, so no "unconfirmed gig" state can ever be reached. `StoredAct`. | artist (an **Act** may not resolve to one), slot, booking |
| **Surprise** | An **Act** typed by hand that the poster did not announce. Has nothing to return to if it is undone, unlike an **Act** off the **Bill**. | guest, unlisted, extra |
| **Log** | My own record of what was played on a night, on this phone — the witness statement, not the published one. Freely editable forever; remembering a song three days later costs nothing. Starts **Open** and only a person may close it, because a set captured by ticking off songs an artist has played before is incomplete by construction. Shown as "Your log of this night". `StoredLog`. | setlist (reserved for setlist.fm's), notes, logcat / `android.util.Log` (unrelated — this collision has already cost one conversation) |
| **Gap** | A blank entry in a **Log**: they played something and I could not name it. An acknowledged gap is a true fact; the same song silently missing is the record lying about its own certainty. A song always has a name, so blank is unambiguous. | unknown, blank, missing |

## The room

**Gig resolution** is a room off a corridor, and the three things it decides are kept
apart on purpose.

| Term | Definition | Aliases to avoid |
| ---- | ---------- | ---------------- |
| **Room** | What you can do standing on a **Gig**: the **Log**, the media, the check-in, the terminals. **Nothing here is ever removed** — time decides which action is offered first, never what remains possible. | screen, detail view, page |
| **Alcove** | The single fixture opposite the door: one step right, a destination, holding exactly one thing — and it may be empty. Empty while the band plays; closing the **Log** furnishes it. | button, CTA, headline, primary action, hero (the point is that it is a position, not a control) |
| **Curtain** | What pulling down does: draw it back and see what the **Window** says about this night *now*. A returned instruction, not a call site's choice, and it may be a curtain onto an empty view. Failing changes nothing. | refresh (fine informally), sync, reload |
| **Window** | The data source behind a **Curtain** — setlist.fm or MusicBrainz. Which window a **Room** has depends on what is already known about the night. | api, endpoint, feed |

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
| **Attach** | Putting media on a **Gig**. **Attach asks, once** — the gesture that adds a photograph is the gesture that says which **Band** it lands in, and there is no second step and no recipient picker. Nothing is ever placed by a default. | upload, post, add |
| **Band** | One of the two runs a **Gig**'s media is drawn in: shared above, vault below. **Position is the bit** — which band an item sits in *is* whether it is **Personal**. Never a badge, and never a colour: **Amber** means mine in both bands. | row (fine), tier, section |
| **Personal** | In the vault **Band**: attached, but never sent, held back from everyone. One of two named destinations rather than an exception — a photograph is in the commons or it is just for you, and both are said out loud. Excluding a *named person* is deliberately not representable — that is the share sheet's job. | private (fine), hidden, secret |
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
- A **Bill** is a set of **Acts**; an **Act** becomes a **Gig** when it is marked as played.
- A **Log** belongs to one **Gig**, holds **Gaps** among its songs, and is never overwritten
  by setlist.fm.
- One step **Inner** from a **Gig** is its **Alcove**; **Back out** is one step **Outer**, always.
- A **Followed line** grants nothing; only a **Contact** can receive media.
- **Attach** puts media on a **Gig** in one **Band** or the other; the shared one reaches
  the **Audience**, the vault reaches nobody. Dragging it between them is what changes its
  mind, and is the only way to stop offering something.
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

> **Dev:** "Swiping right from a **Gig** goes to the higher resolution, right?"
> **Designer:** "Say **Outer**. 'Higher resolution' means more detail to half the room and fewer nights to the other half — and ADR-0002 uses the word for **Date precision** on top of that. **Back out** is one rung **Outer**, and that sentence can only be read one way."

> **Dev:** "The band is still playing and the **Log** is open. What's in the **Alcove**?"
> **Designer:** "Nothing. You don't want an exit pointed at you mid-set. The **Room** still has the **Log**, the media and the check-in — closing the **Log** is what furnishes the **Alcove**."

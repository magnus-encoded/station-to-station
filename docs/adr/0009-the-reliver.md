# ADR-0009: The Reliver

**Status:** ~~accepted (2026-08-11). Considered served; low net-new feature need.~~
**Amended 2026-08-15: served for five accounts.** See the amendment below.

## Context

Wants to re-experience former glories rather than document or compare. Unusual among the personas in
that the feature serving them existed before the persona did: playlist export.

## Decision

**The Reliver is a first-class persona who is ~~already served~~ served for five accounts.**
~~Playlist conversion, the night's photograph as cover art, and the ordered set are their whole
surface.~~ Playlist conversion is *a* surface, not the whole one — see the amendment.

**No net-new work is scoped for them without a specific reason.** Naming them is still useful,
because it stops the existing playlist path being treated as a legacy feature to trade away.

## Consequences

- **The playlist path is load-bearing and may not be casually degraded.** ~~It is the one complete
  journey in the app~~ It is the one complete journey in the app *for five accounts*, and it is
  somebody's entire motive.
- **They are why "make a playlist" is an Alcove destination once a night is finished** (#129) rather
  than something buried.
- **Accounts moving to a new phone matters more than it appears** (#143), because Spotify is this
  persona's only surface and an unauthenticated new device serves them not at all.
- A quiet persona is not an absent one. If a change makes playlists worse in exchange for something
  else, this ADR is the objection.

## Amendment (2026-08-15): the five-user cap, and what "served" was resting on

**This ADR was accepted on 2026-08-11. The five-user cap was worked around on 2026-08-12.** It was
never revisited, so "considered served" survived a day past the fact that broke it. That gap is why
the original text stays on the page.

Spotify closed quota extensions to individuals in May 2025, so the development-mode allowlist is
**permanent, not a phase**. Five accounts can use the playlist path. Everyone else must register
their own Spotify developer application and paste a client id, with a redirect URI that fails
silently when wrong.

**The workaround is inversely matched to the persona it rescues.** The Collector might register a
developer application — completeness is their motive. The Reliver is by definition the persona
*least* likely to: low-effort, nostalgic, here to re-experience rather than administer. The escape
hatch asks for the most effort from the one persona whose entire surface depends on it, which means
the path is engineered and still, for them, closed.

### The original error was narrower and older than the cap

This ADR recorded its own flaw in the Context: *"the feature serving them existed before the persona
did."* The persona was named **from** the feature, and so the feature became the definition of the
motive. Re-experiencing a night is broader than converting one:

- **the setlist** — what was played, in order, which is precisely what a photo gallery cannot hold
- **media pinned to song-nodes**, so the night is re-entered through its own order
- **the recording as an index** (#27) — the set becomes a seek bar

None of that touches Spotify, and setlist.fm ships its own key, so it stands with no account at all.
The zero-account floor was always real; this ADR just never claimed any of it for the Reliver.

**The marginal-value test that exposes this:** what does the app give a Reliver that their photo
application does not? Under the original text and past the fifth account, the honest answer is
*nothing* — and that is a statement about this ADR, not about the app.

### Consequences that change

- **No longer protect-only.** The Reliver shares the Collector's floor, which raises #106 (the night
  as durable Markdown) and #27 rather than adding a surface of their own.
- **A source can stop being pluggable without any code changing.** ADR-0005 established that a
  source's *terms* can disqualify it. A permanent five-user allowlist is the same category of fact,
  and it contradicts the architecture's own premise that every capability is unlocked by a source
  **the user chooses to plug in** — a source only five users may plug in is not that.
- **The music primitive is now an open question**, not a settled one. Deliberately left open here.

## Related

- ADR-0007 the Collector; ADR-0005 (a source's terms can disqualify it); #143 accounts; #129 the Gig
  lifecycle; #106 the vault; #27 the recording as an index.

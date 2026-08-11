# ADR-0012: The Journalist

**Status:** accepted (2026-08-06, during the ADR-0002 persona review)

## Context

Writes *about* gigs: notes, prose, a personal corpus. The objection to naming it is fair and worth
recording, because it looks like the overlap of the Historian (documenting truthfully) and the
Tastemaker (publishing for reach). Half of it is. But the private half belongs to neither: the
Historian's data wants a shared record, the Tastemaker's wants an audience, and a note nobody else
reads wants neither.

## Decision

**The Journalist is a first-class persona, and what it contributes is an axis the other five lack:
which surface, and how much attention.**

- **Phone, at the gig** — capture. One-handed, wrong-tolerant, coarse.
- **Laptop, at home** — write, enrich, publish, fix upstream.

**Applied as a rule:** when a persona's motive implies work the app could prompt for, decide which
surface that work belongs to *before* adding the prompt. The phone's job is to record a finding
durably; the desk is where it becomes actionable.

**Notes are media with a Personal bit, and export is the publishing path.** The app builds no feed,
no discovery and no promotion.

## Consequences

- **It dissolves a standing tension**: the Historian wanting records to graduate against the
  Collector not wanting a local record to read as an unfinished chore. Nagging is only nagging on
  the phone.
- **The gold star has no external value and needs none.** Her readers trust her and her photographs
  settle arguments. Attestation is an in-app primitive.
- **She is the one persona with a legitimate claim that can beat the Volunteer** (ADR-0014). Press
  photography has a real tradition of public interest overriding a bystander's preference, and her
  workflow explicitly involves contacts' photographs. There is no tidy rule for this yet, and
  inventing one would be worse than recording the gap.
- Sharing text relinquishes control over it. That is true of every messaging app, and the model does
  not pretend sharing mechanics can fix it.

## Related

- ADR-0011 the Tastemaker (the deferred half), ADR-0014 the Volunteer, ADR-0002.

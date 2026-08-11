# ADR-0004: Best-effort enrichment, and the scoped invariant

**Status:** accepted (2026-08-11, formalising the resolution of the 2026-08-04 grilling)

## Context

#105 records that this principle governs a growing amount of the design and *"is written down
nowhere"*. That is the gap this closes.

#28 states an invariant: *for every gig two **Contacts** both **Attended**, each holds the other's
non-**Personal** media.* Read plainly, that is a guarantee, and it is unachievable by construction.
A **Contact** who never configures storage can never satisfy it. Neither can one who is offline all
festival, reinstalls, changes phone, or simply never opens the app again. Under ADR-0003 there is no
server to hold the bytes in the meantime, so there is nothing that could make the guarantee true.

The failure mode of pretending otherwise is specific and bad: spinners that never resolve, error
banners for a normal state, retry-nagging, and screens that break when something never arrives.
Absence would be modelled as failure, when absence is simply the common case.

## Decision

**Anything that depends on another device is best-effort enrichment. It may never happen, and
nothing may depend on it.**

And, separately, **the invariant is kept but scoped**, because the two statements answer different
questions:

- **Product level:** receipt is best-effort. A **Contact**'s media may never arrive. This is what a
  user is told, and what every screen must be built for.
- **Inside Reconcile:** *given two reachable devices with resolvable **Pointers**, the sync converges
  and is idempotent.* This is testable, and #103 asserts it.

Neither sentence is a softened version of the other. The first is a promise about the world, the
second is a property of the algorithm.

**What follows for the UI:**

- Absence of a contact's media is a state, not an error. No spinner waits on it, nothing blocks, and
  no banner reports it.
- A partial result is the normal result. "Thumbnail here, full-res still climbing" is a steady
  state.
- The one honest exception is a user who has configured no storage at all, which is a distinct
  situation with a one-time message rather than a recurring failure.

**What follows for the sync:** idempotent, unordered, and unbounded in time. A **Contact** added in
2027 enriches a 2026 **Gig** with no backfill path to build, because there was never a deadline to
miss.

## Consequences

- **"Did it work?" is not always answerable, and the UI must not ask.** This is the hardest part to
  hold, because it runs against the instinct to report status.
- **Nothing may be sequenced behind receipt.** A feature that cannot render until a contact's media
  arrives is a feature that will sometimes never render.
- **Testing splits accordingly.** The product-level promise is asserted by showing screens behave
  correctly with nothing received; the algorithmic invariant is asserted with two reachable fake
  devices. Conflating them produces a test that is either vacuous or flaky.
- **This does not license silent failure everywhere.** The capture path is the opposite case: what
  the user records in the moment must be written durably and is not best-effort. The distinction is
  whether another device is involved.
- **It makes the unbounded-time property free rather than expensive.** Since nothing waits, "years
  later" and "ten minutes later" are the same code path.

## Related

- ADR-0003 — no backend, which is why receipt cannot be guaranteed.
- #28 — the original invariant this scopes.
- #103 — the sync that asserts the algorithmic half.
- #105 — the vocabulary corrections that surfaced the gap.

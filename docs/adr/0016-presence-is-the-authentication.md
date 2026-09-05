# ADR-0016: Presence is the authentication, so the radio is not verified

**Status:** accepted (2026-08-20)

## Context

`UBIQUITOUS_LANGUAGE.md` says of the **Exchange**: *"Physical presence is the authentication — the
whole point, not an implementation detail."* It says it as a **product** decision, about what the
moment feels like. It has never said what follows from it as a **security** decision, and that
half is what keeps getting rediscovered as a hole.

The concrete shape of the hole: a **Card** arriving over BLE carries a `publicKey` that is verified
against nothing. Nothing proves the sender holds the matching private key. Any central in range can
write to the inbound characteristic — it is `PERMISSION_WRITE`, with no encryption and no bonding —
while the victim has the Exchange screen open. The same is true of the Nearby swap between two
Androids. The security review that filed #188 raised exactly this, and the only answer in the
repository was a one-line code comment in `BleProbe.kt` (*"Being in the Exchange is the consent, so
there is nothing to prompt"*) — an argument good enough to half-win the point and too thin to
survive the next review. Left as it is, someone eventually builds a handshake this project decided
not to build.

Two mechanisms were considered and are the ones a reviewer will reach for again:

- **Correlating an inbound write with the peer the user tapped.**
- **A challenge-response proving the sender holds the private key**, which the LAN **Reconcile**
  session already does, fingerprint-bound (#265).

## Decision

**Physical presence is the authentication. The radio path therefore does not verify the sender of a
**Card**, and the trust boundary this project defends is the boundary of presence: anything that can
create trust *without* anyone being anywhere is the bug.**

Three consequences, in the order they bind:

**1. A radio **Card** is accepted unverified, from any radio in range, while the Exchange screen is
open — and only then.** The window is exactly as long as the user is looking at the screen. Nothing
advertises, listens or accepts in the background.

**2. Correlating the write with the tapped peer is not built.** In an ordinary **Exchange** only one
side taps. The other side receives a write from a radio it never tapped, and *that is the legitimate
case* — a strict rule breaks the common path. The variant that works on the tapping side alone
leaves the other side open, so it is writing the thing twice for half a fix.

**3. A challenge-response over the radio is not built.** The mechanism exists and would work. The
threat it closes requires an attacker physically present during the two-second window of a
deliberate **Exchange**, standing next to two people who are looking at their phones and at each
other, at risk of being confronted — for the ability to plant one record that reads as received. The
cost is a second handshake on a second transport, inside a two-second budget. The benefit does not
carry it.

What is built instead is the arrival rule (#188): a **Card** for someone new is written silently, a
**Card** bringing a first key to a **Followed line** is a silent promotion, a **Card** that changes
a **Contact** already held asks first, and a **Card** saying nothing new does nothing. That rule
does not authenticate anybody. It bounds what an unauthenticated **Card** is allowed to *do* — and
what it is allowed to do is add, never silently rewrite.

## What this does not cover

Stated explicitly, because a bounded argument read as an unbounded one is how the next hole gets
argued away:

- **It does not cover the deep link.** A `station-to-station://friend?…` link is opened by any web
  page, any chat message, any other installed app, and needs nobody to be anywhere. Presence is not
  the authentication there because there is no presence at all. That is why a link cannot carry a
  key and cannot mint a **Contact** (#271) — the reasoning above is precisely what forbids it.
- **It does not cover the QR code**, which encodes the same link and so can only ever produce a
  **Followed line**. Correct rather than a gap: the promotion case is what upgrades that record when
  the two people actually meet.
- **It does not cover a compromised or hostile phone belonging to a real **Contact**.** Presence
  authenticates *that someone stood there*, not that their device is honest.
- **It does not cover impersonation before any key is held.** A stranger in range can present any
  name and any username. What they cannot do is silently overwrite someone already held.
- **It does not license verification-free trust anywhere else.** The LAN **Reconcile** session
  verifies, with a fingerprint-bound challenge-response (#265), because it runs with nobody looking
  and nobody standing anywhere. The rule is presence *or* proof, never neither.

## Consequences

- **A new transport must justify itself against presence, not against convenience.** Any door that
  hands over a **Card** must either require two people to be standing together, or carry proof — or
  it must be incapable of producing a **Contact**.
- **A future review that proposes a radio handshake should read this and argue with it**, rather
  than reading unverified code as an oversight. If the answer changes, it changes by amendment
  below.
- **The thing to watch is not the crypto, it is the doors.** Every new way a **Card** can arrive
  routes through the one arrival rule; a door that grows its own check is the regression this
  decision most wants caught.
- **`ProbeCard.publicKey` is unverified by construction, and that is not a bug to be fixed by
  verifying it.** It is an identity to be compared against on later, absent-presence transports.

## Related

- ADR-0003 no backend for the social layer — why there is no third party who could vouch for anyone.
- ADR-0014 the Volunteer, ADR-0015 the Holdout — the two protective personas this bounds.
- ADR-0019 the gossip channel background carve-out — narrows this decision's "nothing
  advertises, listens or accepts in the background" for a check-in relay between existing
  Contacts; it does not reopen it. Reconcile itself stays foreground-only, exactly as below.
- `UBIQUITOUS_LANGUAGE.md`, **Exchange** and **Contact** — the product half of the same sentence.
- #188 the arrival rule (this decision's companion), #271 a link cannot mint a **Contact**,
  #265 the LAN session that *does* verify, #187 the username at the same trust boundary.

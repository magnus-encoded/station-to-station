# ADR-0023: The Room remembers who was seen

**Status:** accepted (2026-09-17)

## Context

ADR-0021 made a **Fact** the durable unit of the gossip channel and left everything about the
radio deliberately perishable: `seen` ids, `held` **Carry** windows and receipt credit all
expire, and `GossipPresence` — who is in the room — is in memory only, process-wide, and
documented with the line *"presence that did not survive the process was not presence."*

That was the right rule for a notification. It is the wrong rule for a **Gig**. Issue #498 asks
a **Room** to say who was there, on a night that may be months old, on a phone that has been
restarted a hundred times since. Under the existing rule the answer is always nobody.

The two are not the same question wearing different tenses. "Is someone here" must decay,
because a **Contact** who walked off announces nothing and a stale yes is a lie about the
present. "Who was there" must not, because the night happened.

There is a second gap. A verified **Check-in** is already durable — it is a `request` **Fact**,
and `arrivals` finds it, witness-carried hops included. But a completed **Pass** leaves no
**Fact** behind at all. Two phones meet, each proves a key to the other, and the only trace is
`useful`, a two-minute routing preference that prunes itself. The strongest evidence this
device has — *I met that phone myself* — was the one kind it did not write down.

## Decision

**A Gig keeps a durable, device-local record of the devices this phone completed a Pass with,
and the Room reads "Seen with" from that record together with the Check-ins it already holds.**

- **One new durable fact, and only one.** `PublicGossipState.metDevices`: per **Gig**, the key
  the transport proved and when it was last proved. Everything else the line needs is derived
  at read time from **Facts** that were already durable. No new **Fact** kind, nothing new on
  the wire, no BLE seam change — this is evidence *about* the transport, not a payload in it.
- **Presence stays ephemeral.** `GossipPresence` is untouched and `presenceFrom` is not widened.
  Two questions, two mechanisms; the alternative — persisting presence — would have made the
  notification lie about the present in order to make a **Room** honest about the past.
- **Direct and attributed are different evidence and are ranked, not merged.** A completed
  **Pass** is this device's own eyes and counts a device whether or not anyone can name it. A
  verified **Check-in** is somebody's signed assertion and counts for the **Gig** it names even
  when a witness carried the last hop. Directly met **Contacts** sort first, newest first; those
  known only from a **Check-in** follow.
- **An advertisement is not evidence.** The token in one is deliberately unlinkable and a scan
  hit says only that something is transmitting. The distinction ADR-0021 drew between carrying
  and knowing is the same distinction here.
- **Unnamed devices are counted, never subtracted.** *+ N others* is a count of directly met
  devices that resolve to no name — a stranger, or a **Blocked** **Contact**. It is never
  `total - named`, because a **Contact** named from a relayed **Check-in** contributes a name
  and no device, and the subtraction would report a number this phone never observed.
- **Block is admission, not a rewrite.** A blocked **Contact** met directly is counted and not
  named; a blocked **Check-in** that only ever arrived relayed was never admitted and adds
  nothing. That is ADR-0021's rule applied unchanged.
- **Identity is folded, ids are unioned.** A device is counted once per **Gig** after resolving
  its key through `recognition`, and a night is read under every id it has been known by
  (#496's adoption included) — union by *device*, never by id, or adoption counts the same
  phone twice.

## Consequences

A **Contact** met on their night-scoped **Gig** key and again on their nightly relay key can
show as named *and* as one of the others. Nothing on the wire links those two keys, by design —
that is ADR-0021's **Attribution** working — so folding them would be this device asserting
something it cannot see. Two keys of the same *stranger* are likewise two devices. The line is
a floor on the room, not a census, and it is worded ("Seen with") so it does not claim to be one.

The record is device-local and never leaves the phone, so it is not a thing a **Contact** can
dispute, correct or be notified of. It also inherits the **Attribution** rule in full: removing
a **Contact** does not erase a name already recognised on an old night.

~~iOS has the same domain and needs the counterpart decision; it is not #497, which is ID
adoption. Flagged rather than implemented here.~~

## Amendment, 2026-09-18 (#499, iOS)

The flag above is cleared: iOS now holds the same record, with the same read-model semantics —
`PublicGossipState.metDevices`, `rememberPass`, `seenWith`, the `SeenWith` type, and a
`seenWithLine` worded identically to Android's, down to *"1 other"* and the `" + "` join. Two
apps showing the same night must not phrase it differently. There is **no wire format here**, so
nothing in `fixtures/weave/` moves and the two implementations are twins by test, not by bytes:
`ios/StationToStationTests/SeenWithTests.swift` mirrors `SeenWithTest.kt` case for case,
including the double-key divergence above.

Three shapes differ from Android's, both because the host does:

- **Where the active **Gig** is computed.** Android's `GossipService` holds a `@Volatile
  activeGigId` refreshed on its own loop. iOS has no service to hold one, so
  `gossipActiveGigId(cache:stoppedAt:now:)` is derived in `GossipChannel` from the timeline it
  already loads on both paths. That is the *stronger* form of the rule the Android doc comment
  argues for — no stateful field that can disagree with the participation deadlines.
- **Where the outbound half hooks.** Android records the dial-out direction in `onPushed`; iOS
  records it in `GossipChannel.confirmDelivery(to:)`, not in `publicPass(to:nonce:)`. Bytes that
  never landed are not a **Pass**, which is the same reason delivery itself is marked there. The
  peer key written down is `challenge.from` — the relay key the peer's signed challenge proved,
  the same key space as the inbound `pass.from`, so `recognition` folds both directions onto one
  identity. It is followed by the same `publishWitnessed` every other write on that actor
  ends with: Android's **Room** is fed by a Flow off its store and updates itself, while iOS's
  is fed by a callback that has to be called.
- **Where the aliases come from.** Android's `gossipGigAliases` takes an `adopted` map from the
  gossip store; iOS derives the set from `StoredGig.setlistId` alone, the same source
  `gossipWitnessedIds` already reads. Adoption on iOS writes the setlist.fm id onto the **Gig**,
  and the **Update** **Fact** it also authors is already followed by `linkedIds` on the
  **Check-in** half — so a second map would be a second answer about the same night.

One iOS-only test guards a thing Swift makes easy to get wrong: a record written before
`metDevices` existed must decode as a night with no met devices, never as an empty night. That is
what `init(from:)`'s `decodeIfPresent` on every key is for, and the new field joins it.

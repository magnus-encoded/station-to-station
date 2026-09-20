# Public Gig gossip, version 2

This continues #408 and supersedes its original Contact-only transport. Facts are public;
only attribution is masked. A holder of an author's previously exchanged Card public key
can recognize their Gig authorship permanently, including after removing the Contact.
Anyone who obtains that Card key by another route can also recognize it. This is not
confidential messaging. Exchange remains the disclosure boundary; there is no new secret
to distribute to every recipient or rotate when a Contact is removed.

Each stable local Gig gets an independent P-256 signing key and random scope ID. Changes
to the external Gig ID retain that key and carry cumulative former IDs. The durable key
signs `station-to-station/gossip-identity/2\n<scope>\n<gig-public-key>` once per binding.
That signature is masked with AES-256-GCM under SHA-256 of
`station-to-station/gossip-mask/2\n<card-public-key>\n<scope>`. The sealed representation
is nonce (12 bytes), ciphertext, tag (16 bytes), base64. The durable public key is never
included in public Gossip. Decryption alone does not establish attribution: the durable
signature must verify against the exact temporary key and scope.

An Envelope record is 12 tab-separated fields: content hash, Gig ID, comma-separated former
IDs, scope, temporary author public key (base64 SPKI), authored epoch milliseconds, expiry
epoch milliseconds, kind, author-local line number, base64 UTF-8 text, masked attribution,
base64 DER ECDSA signature. Empty optional fields remain present. Canonical signed bytes
are `station-to-station/gossip-fact/2` followed by the ten fields between hash and signature,
separated by newlines. The content hash is lower-case SHA-256 of those bytes.

Kinds are `log`, `request`, `witness`, `receipt` and (added 2026-09-17, ADR-0021) `update`, which
relabels its own signer's earlier Facts under a newly adopted Gig ID and is gossiped, not one-hop. Log replacements carry one complete line;
blank text is a Gap. A witness embeds the complete signed direct request as its text, so a
relay cannot invent the subject's attendance. Requests and receipts are one-hop only.
A receipt affects neighbour priority only, never the referenced Envelope's outbox. It is
authored on receiving a Fact that is recognised as a Contact's at that moment, names the
neighbour that delivered it, and is offered to that neighbour alone — the only addressed
Envelope on the wire. The name is always the sender's nightly relay key, the only identity a
meeting presents, so a Pass signed as a Gig — which is a Pass carrying its signer's own
request — earns no receipt at all rather than one addressed to a key nobody answers to.
One batch owes at most one receipt per Gig record, never one per Fact: a receipt says what the
neighbour did, not what the line was, and two lines of one log would otherwise author the same
bytes twice. It rides only a Pass signed as its own author, as a request does.
Its author credits the neighbour the text names; a receiver credits the proved sender, never
the text, so no relay can nominate a third party. See ADR-0022.

A central first reads `station-to-station/gossip-challenge/2`, a base64 32-byte
nonce, the peripheral's temporary SPKI relay key, and a base64 DER signature, each
on its own line. The signature covers `station-to-station/gossip-challenge-proof/2`
plus a newline and the base64 nonce. The complete challenge fits 512 bytes.
A Pass proof instead signs `station-to-station/gossip-auth/2` plus a newline and
that nonce; the separate domains prevent using a challenge signature as a Pass proof.
A v1 header is rejected. Android advertises the service continuously, without
Contact tokens or an application-driven four-second advertisement restart.

A Pass starts `station-to-station/gossip-pass/2`, then the relay's temporary public key and
nonce signature separated by a tab, followed by Envelope records. A relay has a separate
night-scoped key; carrying an Envelope never makes it its author. Pass limit is 40,000 bytes,
64 Envelopes; Envelope limit 8,192 bytes; Log text limit 512 UTF-8 bytes. GATT writes retain
the existing chunk-and-empty-terminator framing and 512-byte attribute ceiling.

The first accepted copy may enter the outbox. A second valid copy removes it. Successful
handoffs are remembered locally; no peer inventory is requested. Message expiry is 06:00
at the end of the Gig's night. A 15-minute Carry window, starting at the first accepted copy
on each relay and capped by message expiry, is a provisional default. Seen IDs survive
outbox eviction until message expiry. Durable admitted facts survive both timers.

Resource defaults remain tunable: 128 Envelopes / 128 KB carried, 8,192 live seen IDs,
per-peer cooldown and bounded BLE sessions. Capacity exhaustion drops incoming transport
work rather than evicting seen IDs and allowing repeated re-entry.

The Carry window's 15 minutes shows a knee in the dense synthetic model. Across five
synthetic venue traces, 900 seconds reaches 94.9-95.6% of fact/recipient pairs, 300
seconds reaches 36-52%, and an unlimited window reaches 99.7-100%: shortening it costs
far more than lengthening it gains. Median delivery is about 20 minutes from authoring
and p95 about 39 minutes, at a mean relay depth of 7.5-8.3 hops. There is no hop count
on the wire, and the same sweeps show what imposing one would cost. The usefulness policy is a binary boost with a provisional two-minute
decay. ~~and random ties. Android ranks peers after a 1.5-second window of sightings, preferring
credited neighbours and never excluding the others; iOS holds the same rule but has no scarce
connection slot to apply it to (ADR-0022).~~ Amended 2026-09-20: ties are random.
`gossipPreferredPeers` (Android `GossipPolicy.kt`, iOS `GossipBudget.swift`) shuffles the
candidates under an injected RNG, then puts credited peers first; it never excludes anyone. It
ranks peers at the radio; it is not part of `PublicGossipState.offer`, which takes one peer. Both
platforms rank after a 1.5-second window of sightings; on iOS the only production caller is the
concurrent-meeting cap (4 meetings, ADR-0022 amendment, #486). Credit is nearly inert in the
field by design: a peer resolves to its relay key only after a completed challenge read, and the
two-minute credit window overlaps the one-minute peer cooldown, so a sighting is both eligible
and credited for about a minute at most. ADR-0022 section 4 says a real fix needs a per-pair
rotating token in the advertisement, which v2 deliberately does not have. Receipts remain one-hop; the sweep of receipt hop
budgets, with what each extra hop costs in bytes and in duplicate arrivals at the author,
does not settle production direct witnessing or usefulness signals.

The sparse-adoption sweep (2026-09-15, [results and assumptions](https://github.com/magnus-encoded/station-to-station/blob/gossip-sim/sim/SWEEPS.md#sparse-adoption--2026-09-15))
filters app participants from an unchanged full-crowd trace. With 300 concertgoers,
900 seconds delivers 0–16.67% of fact/recipient pairs with three app users and
2.22–3.33% with ten, across five seeds. Longer retention helps some traces but does
not ensure delivery. Storage and usefulness-window sweeps are inconclusive at this
load, and the simulator does not model seen-ID limits or peer cooldown. Keep these
defaults provisional; none of these results measures venue performance, iPhone
background availability or battery drain.

The earlier claim that a `sim/` sweep supported the 15-minute window is withdrawn: it
compared 60 and 900 seconds on a trace whose only encounter happened at t=1, where the
two cannot differ. Its coverage reducer also counted nodes against a fact/recipient-pair
denominator, so no figure it produced should be re-scaled or reused.

All of this is one synthetic crowd model. The knees are stable across seeds; the absolute
levels are not evidence about a real venue, and none of it is a claim about iOS
background scheduling, which remains OS-throttled. Method, tables and caveats are in
[`sim/SWEEPS.md`](https://github.com/magnus-encoded/station-to-station/blob/gossip-sim/sim/SWEEPS.md) on branch `gossip-sim`; reproduce with
`cd sim && python -m station_to_station_sim.sweeps`.


### Three lifetimes and what a stranger can read (2026-09-20, #471)

Three clocks are separate and none implies another. Envelope expiry is 06:00 at the end of the
Gig's night and is signed into the Envelope. The Carry window (15 minutes, capped by expiry) only
bounds how long a relay offers its copy. Seen IDs outlive outbox eviction until expiry, so an
evicted Envelope is not re-accepted. Durable admitted Facts outlive all of them. Presence
(five-minute nearby window) and participation grace (30 minutes, capped at 06:00) are further,
unrelated clocks.

The BLE challenge is readable by any device in range, stranger included. It shows that the
peripheral holds its night-scoped relay key; a Pass proof shows possession of that key, not
Contact membership and not app attestation. Attribution stays sealed (see above).

### Verification status (2026-09-20)

Not yet field-tested. Behaviour is covered by unit and seam tests and by simulation
(`sim/SWEEPS.md`, synthetic crowds); the simulated figures above are not measurements. Nothing
here has been verified on real devices as a whole: no locked-iPhone receive or forward run, no
phone-to-phone Pass, no battery measurement. Pixel/Pi plain-ATT receive tests passed earlier;
the Pi ATT 13 bug (#465) and field testing (#467) remain open.

### Publication boundary — settled 2026-09-15 (#455)

Each committed local Log addition or correction publishes its current assertion
while participation is active, whether correct or not. A correction is a new
signed Fact for the same line; receivers project the latest version and retain
history. Completing the whole Log starts grace, rather than releasing a withheld
batch. Uncommitted typing and edits outside participation stay local.

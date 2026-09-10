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

Kinds are `log`, `request`, `witness`, `receipt`. Log replacements carry one complete line;
blank text is a Gap. A witness embeds the complete signed direct request as its text, so a
relay cannot invent the subject's attendance. Requests and receipts are one-hop only.
A received receipt affects neighbour priority, never the referenced Envelope's outbox.

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
at the end of the Gig's night. A 15-minute Carry window, refreshed only at the first receipt
on each relay and capped by message expiry, is a provisional default. Seen IDs survive
outbox eviction until message expiry. Durable admitted facts survive both timers.

Resource defaults remain tunable: 128 Envelopes / 128 KB carried, 8,192 live seen IDs,
per-peer cooldown and bounded BLE sessions. Capacity exhaustion drops incoming transport
work rather than evicting seen IDs and allowing repeated re-entry. The first deterministic
sweep in `sim/` keeps the 15-minute Carry window: its 60-second and 900-second runs have
identical coverage when the next encounter is immediate, while the 60-second run correctly
drops a later encounter. Usefulness lasts two minutes, binary, with random ties. Receipts
remain one-hop until a trace with receipt hops exists; the simulator currently measures
receipt probability only as delivery of a witnessed Fact. These are measured defaults, not
claims about iOS background scheduling, which remains OS-throttled.

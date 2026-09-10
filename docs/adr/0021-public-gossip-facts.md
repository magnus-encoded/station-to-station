# Public Gig facts over blind relays

**Status: accepted**

Gossip facts are public, signed assertions scoped to a Gig and carried by any STS device in
BLE range. A temporary Gig identity signs each fact; a durable Contact key is sealed inside
the attribution field so a Contact can recognize retained facts after Exchange without
making the content confidential. Blind relays may carry and forward facts without knowing
the author, while Block affects local application admission only. This supersedes the
Contact-only propagation boundary in ADR-0019; Reconcile, media and Notes remain private and
Contact-scoped. The design accepts best-effort delivery and mechanical resource limits in
exchange for useful information in sparsely adopted venues.

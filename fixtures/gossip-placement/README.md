# Gossip placement fixtures

`cases.json` is the document both native suites assert `weaveGossip` against. Neither
platform owns it. Setlist weave cases stay in their own tests; this corpus is only the
placement of received **Log** lines among my rows.

Per case:

- `mine` — my rows top to bottom. `""` is a **Gap**, `null` a non-song row (an encore marker).
- `theirs` — per author, `{ "<line number>": "<text>" }`. Numbers are the author's own and
  may have holes. `""` is a tombstone: the number is removed and later numbers collapse.
- `expected` — the woven rows. `base` is my row index, or `null` for a received line with no
  row of mine. `lines` is every received line on that row as `"<author>:<line number>"`.

Cases are written in the order the issue states them. Adding one needs no code change.

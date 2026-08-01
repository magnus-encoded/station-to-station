# Weave fixtures

The weave exists twice — once in Kotlin, once (soon) in Swift. These are the documents
both copies must agree on. Neither platform owns them: they live outside `android/` and
`ios/` on purpose.

One directory per case:

- `timelines.json` — a real store document (`TimelineCache`, see `data/TimelineStore.kt`),
  holding facts only. Two extra keys carry what the store never holds because the device
  knows it from elsewhere:
  - `me` — which key in `shows` is my own **Line**.
  - `friends` — in **Lane** order, nearest my **Spine** first. On device this is the
    friends list reversed; here it is written out so a fixture needs no outside context.

  Both are ignored by the store's own parser, so each file still loads as a plain
  `TimelineCache` — the test asserts exactly that.

- `expected.json` — the rows the weave must produce, newest first. Per row:
  - `key` — the row's identity (`c-<setlist id>-<depth>`, `f-<first show id>`).
  - `node` — `gig` or `festival`.
  - `title` — the artist, or the **Festival name**.
  - `ownership` — `mine`, `theirs`, or `together`. **Together** means a **Gig** on both
    lists: a **Festival** that merely **Absorbs** a friend's cluster is `mine`, not
    `together`, however much of their run it swallowed.
  - `with` — the friends on this node, in lane order.
  - `together` / `theirs` — the counts the row reads out ("1 together · 2 yours · 2 theirs").
    `theirs` is a *union* across friends, deduped by setlist id, and it overlaps `together`.
  - `hosts` — which line each friend is drawn on here: `spine` when they came to meet me,
    `laneN` otherwise. **Lane 1 is nearest the spine**; the Kotlin lane indices are 0-based,
    so `lane1` is index 0.

Adding a case needs no code change — the suites iterate this directory. Keep them small
and hand-readable; a 169-show dump proves nothing a handful of nights doesn't.

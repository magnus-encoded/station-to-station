# Parameter sweeps

`docs/gossip-public-wire.md` names four parameters it could not justify. This file
records what the simulator says about them, how to reproduce it, and what the
numbers are not evidence for.

```sh
cd sim && python -m station_to_station_sim.sweeps
```

Deterministic, and printed across five independently generated traces so that a
knee can be told apart from an artefact of one crowd.

## What is being simulated

`mobility.venue_trace` builds one night at one venue: 120 people in 8 clusters over
four hours, meeting often inside a cluster and rarely across one, producing ~13,300
directional encounters. Twenty people author one Fact each, at random times in the
first half of the night. Every user has four Contacts drawn at random.

Coverage is counted over **fact/recipient pairs**, never over nodes, and the
author's own copy never counts as a delivery. Counting nodes — "did this phone
receive anything at all" — caps a twenty-fact run at 5% and cannot tell a run where
everyone holds one Fact from one where a fifth of the crowd holds all twenty. The
earlier reducer counted nodes against a pairs denominator, so every figure it
produced before this commit should be discarded rather than re-scaled.

## What these numbers are not

The encounter model is invented. Meeting probabilities, encounter bandwidth
(4,000 bytes) and Fact size (600 bytes) are assumptions, not measurements of a real
venue or a real radio — the only measured byte figure anywhere in this branch is the
1,164-byte Pass the Pi wrote to the Pixel.

So: **the knees are the finding, and they are stable across seeds. The levels are
not.** Nothing here is evidence about iOS background scheduling, which is
OS-throttled and unmodelled, or about how many people are actually in BLE range at
a real gig. Where a table and the hardware disagree, the hardware is right.

## Carry window

| carry (s) | 60 | 300 | 900 | 1800 | 3600 | 14400 |
| --- | --- | --- | --- | --- | --- | --- |
| coverage % | 0.4–0.8 | 35.9–51.7 | **94.9–95.6** | 98.3–98.9 | 99.3–99.7 | 99.7–100 |

Ranges are the spread across the five traces. The knee is at 900 seconds in every
one of them. Quadrupling the window to an hour adds about four points; quartering it
to five minutes removes about sixty.

## Convergence, at the 15-minute window

| | coverage % | median s | p95 s | mean hops |
| --- | --- | --- | --- | --- |
| across five traces | 94.9–95.6 | 1104–1240 | 2229–2362 | 7.53–8.27 |

Seconds are from a Fact being authored to it arriving at one recipient, counted per
pair. Roughly 20 minutes median, 39 minutes at p95.

## Fact relay depth

| max hops | 1 | 2 | 3 | 4 | 6 | 8 | none |
| --- | --- | --- | --- | --- | --- | --- | --- |
| coverage % | 5.0–6.4 | 9.5–11.6 | 15.2–22.0 | 28.4–34.1 | 52.9–64.6 | 73.7–81.1 | 94.9–95.6 |

The Envelope carries no hop count, so nothing in the protocol imposes a limit today.
This is what one would cost if it did.

## Receipt hop budget

Fraction of witness receipts reaching the author / receipt bytes / duplicate arrivals:

| hops | 1 | 2 | 3 | 8 |
| --- | --- | --- | --- | --- |
| seed 7 | 0.34 / 6 KB / 0 | 1.00 / 701 KB / 878 | 1.00 / 2,989 KB / 5,016 | 1.00 / 4,009 KB / 8,166 |
| across five traces | 0.30–0.41 | 1.00 | 1.00 | 1.00 |

A receipt is modelled as replicating like any other blind-relay message, under its own
hop budget, and dying when it arrives. Modelling relay as a hand-off instead makes a
receipt a random walk and flattens this table to ~0.34 at every budget — an artefact of
that choice, not a property of the protocol, and the first version of this sweep had it.
Fact coverage is unchanged across the whole row: at these encounter sizes receipts do not
crowd out Facts.

## Usefulness window — no answer

| window (s) | 30 | 60 | 120 | 300 | 600 |
| --- | --- | --- | --- | --- | --- |
| coverage % | 2.1–2.2 | 2.1–2.2 | 2.1–2.2 | 2.1–2.2 | 2.1–2.4 |

The `focus` policy reaches about 2% coverage in this trace regardless of the window, on
around 61 transmissions. Its copy budget is exhausted long before usefulness decides
anything, so **this sweep does not measure the two-minute window** — it measures a policy
that is not moving Facts. The spec's two-minute default is still unsupported, and would
need either a working copy-budget policy or a different trace to test it.

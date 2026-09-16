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

## Sparse adoption — 2026-09-15

Run from `sim/`:

```sh
PYTHONPATH=src .venv/bin/python -m station_to_station_sim.sparse_sweeps > /tmp/gossip-sparse-sweeps.csv
```

630 runs: seeds 7, 11, 23, 42, 99; three and ten app users sampled from crowds
of 120, 300 and 1,000. Each adoption level filters the same full-crowd trace.
Non-users do not relay. The full crowd has eight fixed clusters, one pair-sampling
round every ten seconds, `round(crowd / 3)` attempted pairs per round, and acceptance
probabilities 0.55 within a cluster and 0.06 across clusters. Encounters are recorded
in both directions, one second apart. These are synthetic successful opportunities;
OS throttling, discovery failures and locked-iPhone availability are not modelled.

Each participant authors one 600-byte fact in the first two hours of a four-hour
trace. All participants recognize one another (an optimistic Contact graph), and
all other participants are intended recipients. Encounters allow 4,000 bytes.
There is no platform-specific radio contention or lifecycle grace-period model.

Coverage ranges across five seeds for **300 concertgoers**:

| Carry seconds | Three app users | Ten app users |
| --- | --- | --- |
| 60 | 0% | 0–1.11% |
| 300 | 0–16.67% | 0–2.22% |
| 900 | 0–16.67% | 2.22–3.33% |
| 1,800 | 0–33.33% | 2.22–7.78% |
| 3,600 | 0–33.33% | 5.56–23.33% |

The denominator is six fact/recipient pairs with three users and ninety with ten.
Latency columns in the output include only delivered pairs; a missing value means
no delivery. Low latency among a few successes does not imply broad convergence.
The output also records connections, bytes, duplicates, evictions, peak measured
outbox bytes and receipt probability. Estimated joules use the simulator's input
costs and are not measured phone battery drain.

At the 900-second baseline, sweeping 1/4/16/128 held facts and
600/2,400/9,600/128,000 carried bytes leaves coverage unchanged in the 300-person
traces. This workload puts too little pressure on storage to choose its limits.
Seen-ID limits and peer cooldown are not modelled by this simulator and are not
validated by these runs.

Receipt budgets of one, two and three hops leave fact coverage unchanged. For ten
users, aggregate traffic ranges are 1,920–3,360 bytes at one hop and
2,160–3,840 at three. These are the simulator's **relayed witness receipts**, not
production direct check-in witnesses or the production usefulness signal. They
cannot settle either production mechanism's hop rule.

The focus-policy usefulness windows of 30/60/120/300/600 seconds also produce
identical coverage here. This sparse workload adds no evidence for choosing the
120-second decay. The dense focus-policy copy-budget limitation described above
still applies; neither experiment proves neighbour-priority effectiveness.

**Decision:** keep carry, usefulness and storage values provisional. The former
95% dense-trace coverage does not describe sparse adoption. These runs establish
that this particular sparse trace is opportunity-limited and that a longer carry
window alone does not ensure delivery; they do not establish venue performance.

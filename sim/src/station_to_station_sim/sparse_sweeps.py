"""Sparse adoption: sample app users from a full crowd's unchanged encounter trace.

    PYTHONPATH=src python -m station_to_station_sim.sparse_sweeps

Non-users never relay. Reducing adoption filters opportunities; it cannot increase
an app user's encounter rate. All timing and energy inputs remain synthetic.
"""
from dataclasses import replace
from random import Random

from .metrics import DEFAULT_START, convergence_metrics
from .mobility import venue_trace
from .simulation import Config, Fact, run_simulation

SEEDS = (7, 11, 23, 42, 99)


def sparse_trace(users: int, crowd: int = 120, seed: int = 7) -> Config:
    if not 2 <= users <= crowd:
        raise ValueError("at least two app users within the full crowd are required")
    full = venue_trace(users=crowd, facts=0, seed=seed,
                       density=max(1, round(crowd / 3)))
    rng = Random(seed + 10000)
    participants = tuple(sorted(rng.sample(full.users, users)))
    included = set(participants)
    # Every participant authors once, and knows the other participants. This is
    # deliberately optimistic about recognition, not a population adoption estimate.
    facts = tuple(Fact(f"f{i}", author, rng.randrange(0, 7200), 36000)
                  for i, author in enumerate(participants))
    return replace(full, users=participants, facts=facts,
                   contacts=tuple((a, b) for a in participants for b in participants if a < b),
                   encounters=tuple(e for e in full.encounters
                                    if e.sender in included and e.receiver in included))


def main() -> None:
    print("crowd,users,seed,parameter,value,policy,opportunities,coverage_pct,median_s,p95_s,connections,bytes,duplicates,evictions,peak_outbox_bytes,receipt_probability,estimated_j")
    variants = [("carry_seconds", value, "epidemic") for value in (60, 300, 900, 1800, 3600)]
    variants += [("receipt_max_hops", value, "epidemic") for value in (1, 2, 3)]
    variants += [("usefulness_seconds", value, "focus") for value in (30, 60, 120, 300, 600)]
    variants += [("max_held", value, "epidemic") for value in (1, 4, 16, 128)]
    variants += [("max_bytes", value, "epidemic") for value in (600, 2400, 9600, 128000)]
    for crowd in (120, 300, 1000):
        for users in (3, 10):
            for seed in SEEDS:
                base = sparse_trace(users, crowd, seed)
                for parameter, value, policy in variants:
                    config = replace(base, **{parameter: value})
                    result = run_simulation(config, policy, seed, DEFAULT_START)
                    metrics = convergence_metrics(result, config)
                    median = "" if metrics.median_seconds is None else f"{metrics.median_seconds:.1f}"
                    p95 = "" if metrics.p95_seconds is None else f"{metrics.p95_seconds:.1f}"
                    receipt = "" if metrics.receipt_probability is None else f"{metrics.receipt_probability:.3f}"
                    print(f"{crowd},{users},{seed},{parameter},{value},{policy},{len(config.encounters)},"
                          f"{metrics.coverage_percent:.3f},{median},{p95},{result.connections},"
                          f"{result.bytes},{result.duplicates},{result.evictions},{result.peak_outbox_bytes},"
                          f"{receipt},{result.energy_j:.5f}")


if __name__ == "__main__":
    main()

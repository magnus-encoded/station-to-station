"""Run every parameter sweep the wire spec is waiting on, and print the tables.

    python -m station_to_station_sim.sweeps

Deterministic: same seeds, same numbers, every run. Each table is printed across
several independent traces so a knee can be told apart from an artefact of one.
"""

from dataclasses import replace

from .metrics import DEFAULT_START, convergence_metrics
from .mobility import venue_trace
from .simulation import run_simulation

SEEDS = (7, 11, 23, 42, 99)
CARRY_WINDOWS = (60, 300, 900, 1800, 3600, 14400)
HOP_LIMITS = (1, 2, 3, 4, 6, 8, None)
RECEIPT_HOPS = (1, 2, 3, 4, 8)
USEFULNESS_WINDOWS = (30, 60, 120, 300, 600)


def _row(label: str, cells: list[str]) -> str:
    return f"{label:>9} " + " ".join(f"{cell:>13}" for cell in cells)


def _run(seed: int, **overrides):
    config = venue_trace(seed=seed)
    result = run_simulation(replace(config, **overrides), "epidemic", 1, DEFAULT_START)
    return config, result


def carry_window() -> None:
    print("\n== Carry window: coverage %, by trace seed ==")
    print(_row("carry", [str(w) for w in CARRY_WINDOWS]))
    for seed in SEEDS:
        cells = []
        for window in CARRY_WINDOWS:
            config, result = _run(seed, carry_seconds=window)
            cells.append(f"{convergence_metrics(result, config).coverage_percent:.1f}")
        print(_row(f"seed {seed}", cells))


def convergence() -> None:
    print("\n== Convergence at the 15-minute default: seconds from authoring ==")
    print(_row("", ["coverage %", "median", "p95", "mean hops"]))
    for seed in SEEDS:
        config, result = _run(seed)
        m = convergence_metrics(result, config)
        print(_row(f"seed {seed}", [f"{m.coverage_percent:.1f}", f"{m.median_seconds:.0f}",
                                    f"{m.p95_seconds:.0f}", f"{m.mean_hops:.2f}"]))


def relay_depth() -> None:
    print("\n== Fact relay depth limit: coverage %, by trace seed ==")
    print(_row("max_hops", [str(h) for h in HOP_LIMITS]))
    for seed in SEEDS:
        cells = []
        for limit in HOP_LIMITS:
            config, result = _run(seed, max_hops=limit)
            cells.append(f"{convergence_metrics(result, config).coverage_percent:.1f}")
        print(_row(f"seed {seed}", cells))


def receipt_hops() -> None:
    print("\n== Receipt hop budget: fraction home / receipt KB / duplicate arrivals ==")
    print(_row("hops", [str(h) for h in RECEIPT_HOPS]))
    for seed in SEEDS:
        cells = []
        for limit in RECEIPT_HOPS:
            _, result = _run(seed, receipt_max_hops=limit)
            home = result.receipts_delivered / result.receipts_generated
            cells.append(f"{home:.2f}/{result.receipt_bytes / 1000:.0f}/{result.duplicate_receipts}")
        print(_row(f"seed {seed}", cells))


def usefulness() -> None:
    print("\n== Usefulness window, focus policy: coverage % / transmissions ==")
    print(_row("seconds", [str(w) for w in USEFULNESS_WINDOWS]))
    for seed in SEEDS:
        cells = []
        for window in USEFULNESS_WINDOWS:
            config = venue_trace(seed=seed)
            result = run_simulation(replace(config, usefulness_seconds=window), "focus", 1, DEFAULT_START)
            cells.append(f"{convergence_metrics(result, config).coverage_percent:.1f}/{result.transmissions}")
        print(_row(f"seed {seed}", cells))


def main() -> None:
    for table in (carry_window, convergence, relay_depth, receipt_hops, usefulness):
        table()
    print("\nOne synthetic crowd model. Knees are stable across seeds; absolute")
    print("percentages are not measurements of a real venue. See sim/SWEEPS.md.")


if __name__ == "__main__":
    main()

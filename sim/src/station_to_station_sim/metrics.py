"""Small, deterministic reducers used to compare relay policies.

Coverage is counted over fact/recipient pairs, not over nodes. Counting nodes —
"did this phone receive anything at all" — caps a two-fact run at 50% and reports
a run where everyone holds one fact identically to one where half the crowd holds
both. Every number here is per pair, and the author's own copy is never counted as
a delivery it had to earn.
"""

from dataclasses import dataclass, replace
from datetime import datetime, timezone
from statistics import fmean, median
from typing import Iterable

from .simulation import Config, Result, run_simulation

DEFAULT_START = datetime(2026, 9, 10, tzinfo=timezone.utc)


@dataclass(frozen=True)
class ConvergenceMetrics:
    coverage_percent: float
    median_seconds: float | None
    p95_seconds: float | None
    #: Fraction of witness receipts that reached the author, or None if none were owed.
    receipt_probability: float | None
    #: Mean relay depth of earned deliveries. The author's own copy is hop 0.
    mean_hops: float | None


def _earned(result: Result, config: Config) -> list[tuple[str, str, int]]:
    """Every (fact, recipient, arrival) a relay actually had to deliver."""
    return [(fact.id, user, result.received[user][fact.id])
            for fact in config.facts for user in config.users
            if user != fact.author and fact.id in result.received.get(user, {})]


def eligible_pairs(config: Config) -> int:
    return sum(len([u for u in config.users if u != fact.author]) for fact in config.facts)


def coverage_percent(result: Result, config: Config) -> float:
    total = eligible_pairs(config)
    return len(_earned(result, config)) * 100.0 / total if total else 0.0


def percentile_seconds(times: list[int], fraction: float) -> float | None:
    """Nearest-rank percentile, so a single sample is its own p95."""
    if not times:
        return None
    ordered = sorted(times)
    rank = max(1, min(len(ordered), -(-int(round(fraction * len(ordered) * 1000)) // 1000)))
    return float(ordered[rank - 1])


def receipt_probability(result: Result) -> float | None:
    if not result.receipts_generated:
        return None
    return result.receipts_delivered / result.receipts_generated


def convergence_metrics(result: Result, config: Config) -> ConvergenceMetrics:
    facts = {fact.id: fact for fact in config.facts}
    earned = _earned(result, config)
    times = [arrival - facts[fid].created for fid, _, arrival in earned]
    hops = [result.hops[user][fid] for fid, user, _ in earned if fid in result.hops.get(user, {})]
    return ConvergenceMetrics(
        coverage_percent=coverage_percent(result, config),
        median_seconds=float(median(times)) if times else None,
        p95_seconds=percentile_seconds(times, 0.95),
        receipt_probability=receipt_probability(result),
        mean_hops=fmean(hops) if hops else None,
    )


def _sweep(config: Config, field: str, values: Iterable, policy: str, seed: int,
           start_time: datetime | None) -> dict:
    start = start_time or DEFAULT_START
    return {value: convergence_metrics(
        run_simulation(replace(config, **{field: value}), policy, seed, start), config)
        for value in values}


def sweep_carry_windows(config: Config, windows: Iterable[int], policy: str = "epidemic", seed: int = 1,
                        start_time: datetime | None = None) -> dict[int, ConvergenceMetrics]:
    return _sweep(config, "carry_seconds", windows, policy, seed, start_time)


def sweep_resource_limits(config: Config, held_limits: Iterable[int], policy: str = "epidemic", seed: int = 1,
                          start_time: datetime | None = None) -> dict[int, ConvergenceMetrics]:
    return _sweep(config, "max_held", held_limits, policy, seed, start_time)


def sweep_receipt_hops(config: Config, hop_limits: Iterable[int], policy: str = "epidemic", seed: int = 1,
                       start_time: datetime | None = None) -> dict[int, ConvergenceMetrics]:
    return _sweep(config, "receipt_max_hops", hop_limits, policy, seed, start_time)


def sweep_usefulness_windows(config: Config, windows: Iterable[int], policy: str = "focus", seed: int = 1,
                             start_time: datetime | None = None) -> dict[int, ConvergenceMetrics]:
    return _sweep(config, "usefulness_seconds", windows, policy, seed, start_time)


def sweep_hop_limits(config: Config, hop_limits: Iterable[int | None], policy: str = "epidemic", seed: int = 1,
                     start_time: datetime | None = None) -> dict[int | None, ConvergenceMetrics]:
    return _sweep(config, "max_hops", hop_limits, policy, seed, start_time)

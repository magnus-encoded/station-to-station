"""Small, deterministic reducers used to compare relay policies."""

from dataclasses import dataclass
from statistics import median
from typing import Iterable

from .simulation import Config, Result, run_simulation


@dataclass(frozen=True)
class ConvergenceMetrics:
    coverage_percent: float
    median_seconds: float | None
    p95_seconds: float | None
    receipt_probability: float


def coverage_percent(result: Result, fact_ids: Iterable[str]) -> float:
    ids = tuple(fact_ids)
    if not ids or not result.received:
        return 0.0
    reached = sum(bool(set(ids) & set(events)) for events in result.received.values())
    return reached * 100.0 / (len(ids) * len(result.received))


def convergence_metrics(result: Result, fact_ids: Iterable[str], created_at: int = 0) -> ConvergenceMetrics:
    ids = tuple(fact_ids)
    times = [t - created_at for events in result.received.values() for fid, t in events.items() if fid in ids and t >= created_at]
    times.sort()
    p95 = times[min(len(times) - 1, int(len(times) * 0.95))] if times else None
    return ConvergenceMetrics(coverage_percent(result, ids), median(times) if times else None, p95,
                              1.0 if times else 0.0)


def sweep_carry_windows(config: Config, windows: Iterable[int], policy: str = "epidemic", seed: int = 1,
                        start_time=None) -> dict[int, ConvergenceMetrics]:
    from datetime import datetime, timezone
    start = start_time or datetime(2026, 9, 10, tzinfo=timezone.utc)
    return {window: convergence_metrics(run_simulation(config.__class__(**{**config.__dict__, "carry_seconds": window}), policy, seed, start),
                                        (fact.id for fact in config.facts)) for window in windows}


def sweep_resource_limits(config: Config, held_limits: Iterable[int], policy: str = "epidemic", seed: int = 1,
                          start_time=None) -> dict[int, ConvergenceMetrics]:
    from datetime import datetime, timezone
    start = start_time or datetime(2026, 9, 10, tzinfo=timezone.utc)
    return {limit: convergence_metrics(run_simulation(config.__class__(**{**config.__dict__, "max_held": limit}), policy, seed, start),
                                       (fact.id for fact in config.facts)) for limit in held_limits}

"""One test per reducer and predicate the sweeps depend on."""

from dataclasses import replace

import pytest

from station_to_station_sim.metrics import (DEFAULT_START, convergence_metrics, coverage_percent,
                                            percentile_seconds, receipt_probability,
                                            sweep_receipt_hops)
from station_to_station_sim.policies import is_useful
from station_to_station_sim.simulation import Config, Encounter, Fact, run_simulation


def relay(**overrides) -> tuple[Config, object]:
    """Alice authors, Carol relays, Bob is Alice's Contact and owes a receipt."""
    config = Config(users=("alice", "bob", "carol"), facts=(Fact("f", "alice", 0, 10_000),),
                    encounters=(Encounter(1, "alice", "carol"), Encounter(2, "carol", "bob")),
                    contacts=(("alice", "bob"),), **overrides)
    return config, run_simulation(config, "epidemic", 1, DEFAULT_START)


def test_hops_count_relay_depth_from_the_author() -> None:
    config, result = relay()
    assert result.hops["alice"]["f"] == 0
    assert result.hops["carol"]["f"] == 1
    assert result.hops["bob"]["f"] == 2
    assert convergence_metrics(result, config).mean_hops == pytest.approx(1.5)


def test_hop_limit_refuses_the_forward_it_would_exceed() -> None:
    config = Config(users=("alice", "bob", "carol"), facts=(Fact("f", "alice", 0, 10_000),),
                    encounters=(Encounter(1, "alice", "carol"), Encounter(2, "carol", "bob")),
                    max_hops=1)
    result = run_simulation(config, "epidemic", 1, DEFAULT_START)
    assert "f" in result.received["carol"]
    assert "f" not in result.received["bob"]
    assert result.forwards_refused_by_hop_limit == 1


def test_one_hop_receipt_needs_the_author_in_person() -> None:
    _, stranded = relay()
    assert stranded.receipts_generated == 1
    assert stranded.receipts_delivered == 0

    config = Config(users=("alice", "bob"), facts=(Fact("f", "alice", 0, 10_000),),
                    encounters=(Encounter(1, "alice", "bob"), Encounter(2, "bob", "alice")),
                    contacts=(("alice", "bob"),))
    delivered = run_simulation(config, "epidemic", 1, DEFAULT_START)
    assert delivered.receipts_delivered == 1
    assert receipt_probability(delivered) == 1.0


def test_a_second_hop_lets_a_relay_carry_the_receipt_home() -> None:
    config = Config(users=("alice", "bob", "carol"), facts=(Fact("f", "alice", 0, 10_000),),
                    encounters=(Encounter(1, "alice", "carol"), Encounter(2, "carol", "bob"),
                                Encounter(3, "bob", "carol"), Encounter(4, "carol", "alice")),
                    contacts=(("alice", "bob"),), receipt_max_hops=2)
    result = run_simulation(config, "epidemic", 1, DEFAULT_START)
    assert result.receipts_delivered == 1
    assert result.receipt_transmissions == 2


def test_receipt_probability_is_none_when_none_were_owed() -> None:
    config = Config(users=("alice", "bob"), facts=(Fact("f", "alice", 0, 10_000),),
                    encounters=(Encounter(1, "alice", "bob"),))
    assert receipt_probability(run_simulation(config, "epidemic", 1, DEFAULT_START)) is None


def test_usefulness_is_binary_and_ties_are_random() -> None:
    assert is_useful(100, 50, 120) is True
    assert is_useful(300, 50, 120) is False
    assert is_useful(0, None) is False
    boundary = [is_useful(220, 100, 120, __import__("random").Random(s)) for s in range(40)]
    assert True in boundary and False in boundary


def test_usefulness_rejects_a_delivery_from_the_future() -> None:
    with pytest.raises(ValueError):
        is_useful(10, 50)


def test_percentile_uses_nearest_rank() -> None:
    assert percentile_seconds([], 0.95) is None
    assert percentile_seconds([7], 0.95) == 7.0
    assert percentile_seconds(list(range(1, 101)), 0.95) == 95.0
    assert percentile_seconds([3, 1, 2], 0.5) == 2.0


def test_sweep_receipt_hops_is_reproducible_and_monotone() -> None:
    config, _ = relay()
    first = sweep_receipt_hops(config, (1, 2))
    assert first == sweep_receipt_hops(config, (1, 2))
    assert coverage_percent(run_simulation(config, "epidemic", 1, DEFAULT_START), config) == 100.0

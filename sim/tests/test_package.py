from station_to_station_sim import EventType, MobilityClass, Platform
from station_to_station_sim.metrics import coverage_percent, eligible_pairs, sweep_carry_windows
from station_to_station_sim.simulation import Config, Encounter, Fact, run_simulation
from station_to_station_sim.metrics import DEFAULT_START


def test_package_exposes_core_enums() -> None:
    assert Platform.IOS is not Platform.ANDROID
    assert MobilityClass.WANDERER.name == "WANDERER"
    assert EventType.CHECK_IN_CLAIM.name == "CHECK_IN_CLAIM"


def test_metrics_and_carry_sweep_are_reproducible() -> None:
    config = Config(users=("alice", "bob"), facts=(Fact("fact", "alice", 0, 100),),
                    encounters=(Encounter(1, "alice", "bob"),))
    first = sweep_carry_windows(config, (60, 900))
    second = sweep_carry_windows(config, (60, 900))
    assert first == second
    assert first[60].coverage_percent == 100.0


def test_coverage_counts_pairs_not_nodes() -> None:
    """Two facts, and only one of them reaches bob: that is half the crowd's copies."""
    config = Config(users=("alice", "bob"),
                    facts=(Fact("early", "alice", 0, 100), Fact("late", "alice", 50, 100)),
                    encounters=(Encounter(1, "alice", "bob"),))
    result = run_simulation(config, "epidemic", 1, DEFAULT_START)
    assert eligible_pairs(config) == 2
    assert "early" in result.received["bob"] and "late" not in result.received["bob"]
    assert coverage_percent(result, config) == 50.0


def test_author_copy_is_not_counted_as_a_delivery() -> None:
    config = Config(users=("alice", "bob"), facts=(Fact("fact", "alice", 0, 100),),
                    encounters=(Encounter(1, "alice", "bob"),))
    unreached = Config(**{**config.__dict__, "encounters": (Encounter(1, "bob", "alice"),)})
    assert coverage_percent(run_simulation(unreached, "epidemic", 1, DEFAULT_START), unreached) == 0.0

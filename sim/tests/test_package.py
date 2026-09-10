from station_to_station_sim import EventType, MobilityClass, Platform
from station_to_station_sim.metrics import coverage_percent, sweep_carry_windows
from station_to_station_sim.simulation import Config, Encounter, Fact


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
    assert coverage_percent(type("R", (), {"received": {"a": {"fact": 1}}})(), ("fact",)) == 100.0

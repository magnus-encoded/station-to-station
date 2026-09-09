from station_to_station_sim import EventType, MobilityClass, Platform


def test_package_exposes_core_enums() -> None:
    assert Platform.IOS is not Platform.ANDROID
    assert MobilityClass.WANDERER.name == "WANDERER"
    assert EventType.CHECK_IN_CLAIM.name == "CHECK_IN_CLAIM"


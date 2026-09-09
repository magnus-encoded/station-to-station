"""Shared simulation enums and data models."""

from enum import Enum, auto


class Platform(Enum):
    IOS = auto()
    ANDROID = auto()


class MobilityClass(Enum):
    STATIONARY_GROUP = auto()
    CONCERT_WATCHER = auto()
    WANDERER = auto()
    BAR_HOPPER = auto()
    TOILET_RUNNER = auto()
    SOCIAL_BUTTERFLY = auto()


class EventType(Enum):
    CHECK_IN_CLAIM = auto()
    WITNESS_RECEIPT = auto()
    LOG_OBSERVATION = auto()
    LOG_CORRECTION = auto()
    SETLIST_RESOLUTION = auto()
    ARRIVAL_OBSERVATION = auto()


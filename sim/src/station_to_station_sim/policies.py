"""Replaceable relay policies, and the predicates they are built from.

A policy decides what a phone offers a peer it has already met. The predicates
here are separate from `run_simulation` so a sweep can exercise one of them
directly, without a whole trace.
"""

from random import Random

USEFULNESS_SECONDS = 120


def is_useful(now: int, delivered_at: int | None, window_seconds: int = USEFULNESS_SECONDS,
              rng: Random | None = None) -> bool:
    """Was this peer useful recently enough to keep spending copies on it?

    Binary, as the spec says, with the tie broken randomly: a delivery exactly
    `window_seconds` old sits on the boundary, and rounding it consistently one way
    would make the sweep's answer an artefact of the comparison operator. A peer
    that has never taken a useful copy is never useful.
    """
    if delivered_at is None or window_seconds <= 0:
        return False
    age = now - delivered_at
    if age < 0:
        raise ValueError("a delivery cannot be in the future")
    if age == window_seconds:
        return (rng or Random()).random() < 0.5
    return age < window_seconds

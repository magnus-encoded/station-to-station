"""Venue movement and physical encounters.

One clustered crowd model, deliberately simple: people mostly meet inside their
own cluster and rarely across them. That gap is the only interesting structure —
it is where a Carry window either bridges the venue or fails to. The meeting
probabilities are assumptions, not measurements of a real venue or a real radio.
"""

from functools import lru_cache
from random import Random

from .simulation import Config, Encounter, Fact


@lru_cache(maxsize=None)
def venue_trace(users: int = 120, clusters: int = 8, night: int = 4 * 3600, facts: int = 20,
                seed: int = 7, density: int = 40, cross: float = 0.06,
                within: float = 0.55, contacts_each: int = 4) -> Config:
    """A night at one venue, as a replayable Config.

    Encounters are directional and recorded in both directions, because a real
    handoff is one-way and the trace must let either phone be the sender.
    """
    rng = Random(seed)
    names = tuple(f"u{i:03d}" for i in range(users))
    cluster = {name: index % clusters for index, name in enumerate(names)}
    encounters = []
    for at in range(0, night, 10):
        for _ in range(density):
            a, b = rng.sample(names, 2)
            if rng.random() < (within if cluster[a] == cluster[b] else cross):
                encounters.append(Encounter(at, a, b))
                encounters.append(Encounter(at + 1, b, a))
    authors = rng.sample(names, facts)
    workload = tuple(Fact(f"f{i:02d}", author, rng.randrange(0, night // 2), night + 6 * 3600)
                     for i, author in enumerate(authors))
    contacts = tuple((name, other) for name in names
                     for other in (rng.choice(names) for _ in range(contacts_each))
                     if other != name)
    return Config(users=names, facts=workload, encounters=tuple(encounters), contacts=contacts)

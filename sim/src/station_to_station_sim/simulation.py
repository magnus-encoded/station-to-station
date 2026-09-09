"""Replay an immutable encounter trace without sharing inventories between phones.

Resource estimates are explicit inputs, not measurements of a real BLE radio.
A trace records successful directional opportunities; platform availability belongs
in trace generation, so every policy sees the same opportunities.
"""
from dataclasses import dataclass, field
from datetime import datetime
from random import Random


@dataclass(frozen=True)
class Fact:
    id: str
    author: str
    created: int
    expires: int
    bytes: int = 600


@dataclass(frozen=True)
class Encounter:
    at: int
    sender: str
    receiver: str
    bytes: int = 4000


@dataclass(frozen=True)
class Config:
    users: tuple[str, ...]
    facts: tuple[Fact, ...]
    encounters: tuple[Encounter, ...]
    contacts: tuple[tuple[str, str], ...] = ()
    carry_seconds: int = 900
    max_held: int = 128
    max_bytes: int = 128_000
    fanout: int = 4
    connection_j: float = 0.03
    byte_j: float = 0.00001


@dataclass(eq=True)
class Result:
    received: dict[str, dict[str, int]]
    transmissions: int = 0
    duplicates: int = 0
    useful_deliveries: int = 0
    bytes: int = 0
    connections: int = 0
    energy_j: float = 0
    evictions: int = 0
    peak_outbox_bytes: int = 0


def run_simulation(config: Config, policy: str, rng_seed: int, start_time: datetime) -> Result:
    if policy not in {"contact", "epidemic", "controlled", "spray", "focus", "adaptive"}:
        raise ValueError("unknown policy")
    if start_time.tzinfo is None or config.carry_seconds <= 0:
        raise ValueError("an aware start time and positive Carry window are required")
    if len(set(config.users)) != len(config.users):
        raise ValueError("duplicate user")
    rng = Random(rng_seed)
    result = Result({u: {} for u in config.users})
    held = {u: {} for u in config.users}
    sent = {u: set() for u in config.users}
    contacts = {frozenset(pair) for pair in config.contacts}
    useful = {}
    copies = {}
    facts = {f.id: f for f in config.facts}
    if len(facts) != len(config.facts) or any(f.author not in held or f.expires <= f.created for f in facts.values()):
        raise ValueError("invalid Fact workload")
    authored = set()
    for encounter in sorted(config.encounters, key=lambda e: e.at):
        now, sender, receiver = encounter.at, encounter.sender, encounter.receiver
        if sender not in held or receiver not in held or sender == receiver:
            raise ValueError("invalid encounter")
        for fact in config.facts:
            if fact.created <= now and fact.id not in authored:
                authored.add(fact.id)
                held[fact.author][fact.id] = fact.created
                result.received[fact.author][fact.id] = fact.created
                copies[fact.author, fact.id] = config.fanout
        for user in config.users:
            for fid, arrival in list(held[user].items()):
                if now >= min(facts[fid].expires, arrival + config.carry_seconds):
                    del held[user][fid]
                    result.evictions += 1
        # The sender sees its own outbox and completed handoffs only.
        candidates = [fid for fid in held[sender] if (fid, receiver) not in sent[sender]]
        rng.shuffle(candidates)
        used = 0
        for fid in candidates:
            fact = facts[fid]
            if policy == "contact" and (frozenset((sender, receiver)) not in contacts or
                                        frozenset((fact.author, receiver)) not in contacts):
                continue
            budget = copies.get((sender, fid), 1)
            if policy in {"spray", "focus"} and budget <= 1:
                if not (policy == "focus" and useful.get(receiver, -1) > now - 120):
                    continue
            if policy == "controlled" and rng.random() < 0.5:
                continue
            if policy == "adaptive" and len([p for f, p in sent[sender] if f == fid]) >= config.fanout:
                continue
            if used + fact.bytes > encounter.bytes:
                continue
            used += fact.bytes
            sent[sender].add((fid, receiver))
            result.transmissions += 1
            result.bytes += fact.bytes
            if fid in result.received[receiver]:
                result.duplicates += 1
                held[receiver].pop(fid, None)
                continue
            result.received[receiver][fid] = now
            held[receiver][fid] = now
            if policy in {"spray", "focus"}:
                transferred = max(1, budget // 2)
                copies[sender, fid] = budget - transferred
                copies[receiver, fid] = transferred
            if frozenset((fact.author, receiver)) in contacts:
                useful[sender] = now
                result.useful_deliveries += 1
            while len(held[receiver]) > config.max_held or sum(facts[f].bytes for f in held[receiver]) > config.max_bytes:
                oldest = min(held[receiver], key=held[receiver].get)
                del held[receiver][oldest]
                result.evictions += 1
            result.peak_outbox_bytes = max(result.peak_outbox_bytes, sum(facts[f].bytes for f in held[receiver]))
        if used:
            result.connections += 1
            result.energy_j += config.connection_j + used * config.byte_j
    return result
